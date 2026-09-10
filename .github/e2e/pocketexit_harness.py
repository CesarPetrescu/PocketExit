"""Shared machinery for driving the PocketExit data plane from a test.

Two tests use it. socks-e2e.py points it at a backend process on loopback, so
a failure there is the backend's. system-test.py points the same code at the
compose stack through nginx, so a failure there is the gateway's: the only
difference between the two runs is the address and whether TLS is in the way.

Standard library only, so CI needs nothing beyond Python.
"""
from __future__ import annotations

import base64
import binascii
import contextlib
import hashlib
import json
import re
import secrets
import socket
import ssl
import struct
import sys
import threading
import time
import typing
import urllib.error
import urllib.request

CIRCUIT_SUBPROTOCOL = "pocketexit.circuit.v1"
WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

# 512 KiB crosses the backend's 32 KiB and 64 KiB copy buffers many times over,
# so a framing bug shows up as a mismatch rather than passing by luck.
TCP_PAYLOAD_SIZE = 512 * 1024
_PATTERN = b"PocketExit-SOCKS5-end-to-end-"
TCP_PAYLOAD = (_PATTERN * (TCP_PAYLOAD_SIZE // len(_PATTERN) + 1))[:TCP_PAYLOAD_SIZE]


class Credentials(typing.NamedTuple):
    username: str
    password: str


def log(message: str) -> None:
    print(message, flush=True)


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def development_tls_context() -> ssl.SSLContext:
    """A context that speaks TLS without validating the peer.

    The gateway presents the self-signed certificate scripts/gen-dev-certs.sh
    writes, which no trust store carries. Verification is off because the test
    is proving that nginx terminates TLS and forwards correctly, not that a
    development certificate chains to a public CA. Nothing here is used by the
    agent, which pins the key instead; see docs/pairing-protocol.md.
    """
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return context


def connect_socks(
    address: tuple[str, int], context: ssl.SSLContext | None = None
) -> socket.socket:
    """Open the SOCKS transport, optionally inside TLS.

    The gateway publishes SOCKS twice: plain on 1080 and TLS-wrapped on 1081
    for clients behind stunnel or gost. Both carry the identical SOCKS5
    conversation, so the only thing that changes is this wrapper.
    """
    connection = socket.create_connection(address, timeout=30)
    if context is not None:
        connection = context.wrap_socket(connection, server_hostname=address[0])
    connection.settimeout(60)
    return connection


# --------------------------------------------------------------------------
# A minimal RFC 6455 client. The agent's circuit transport is a plain binary
# WebSocket, so a client is about a hundred lines and saves a dependency.
# --------------------------------------------------------------------------
class WebSocket:
    def __init__(self, connection: socket.socket) -> None:
        self.connection = connection
        self.buffer = bytearray()
        self.closed = False
        self.send_lock = threading.Lock()

    @classmethod
    def connect(
        cls,
        host: str,
        port: int,
        target: str,
        headers: dict[str, str],
        context: ssl.SSLContext | None = None,
    ) -> "WebSocket":
        key = base64.b64encode(secrets.token_bytes(16)).decode()
        request = [
            f"GET {target} HTTP/1.1",
            f"Host: {host}:{port}",
            "Upgrade: websocket",
            "Connection: Upgrade",
            "Sec-WebSocket-Version: 13",
            f"Sec-WebSocket-Key: {key}",
            f"Sec-WebSocket-Protocol: {CIRCUIT_SUBPROTOCOL}",
        ]
        request += [f"{name}: {value}" for name, value in headers.items()]
        connection = socket.create_connection((host, port), timeout=20)
        if context is not None:
            connection = context.wrap_socket(connection, server_hostname=host)
        connection.sendall(("\r\n".join(request) + "\r\n\r\n").encode())

        raw = b""
        while b"\r\n\r\n" not in raw:
            chunk = connection.recv(4096)
            if not chunk:
                raise RuntimeError("the server closed the connection during the WebSocket handshake")
            raw += chunk
        head, _, rest = raw.partition(b"\r\n\r\n")
        status = head.split(b"\r\n", 1)[0].decode()
        if "101" not in status:
            raise RuntimeError(f"WebSocket upgrade returned {status}: {head.decode(errors='replace')}")
        expected = base64.b64encode(hashlib.sha1((key + WEBSOCKET_GUID).encode()).digest()).decode()
        accept = re.search(r"(?im)^sec-websocket-accept:\s*(\S+)", head.decode(errors="replace"))
        if accept is None or accept.group(1) != expected:
            raise RuntimeError("the server returned a bad Sec-WebSocket-Accept")
        protocol = re.search(r"(?im)^sec-websocket-protocol:\s*(\S+)", head.decode(errors="replace"))
        if protocol is None or protocol.group(1) != CIRCUIT_SUBPROTOCOL:
            raise RuntimeError("the server did not select the circuit subprotocol")
        socket_wrapper = cls(connection)
        socket_wrapper.buffer.extend(rest)
        return socket_wrapper

    def _recv_exactly(self, count: int) -> bytes:
        while len(self.buffer) < count:
            chunk = self.connection.recv(65536)
            if not chunk:
                raise ConnectionError("the peer closed the transport")
            self.buffer.extend(chunk)
        taken = bytes(self.buffer[:count])
        del self.buffer[:count]
        return taken

    def send(self, payload: bytes) -> None:
        """Send one binary message, masked as a client must."""
        header = bytearray([0x80 | 0x02])
        length = len(payload)
        if length < 126:
            header.append(0x80 | length)
        elif length < (1 << 16):
            header.append(0x80 | 126)
            header += struct.pack("!H", length)
        else:
            header.append(0x80 | 127)
            header += struct.pack("!Q", length)
        mask = secrets.token_bytes(4)
        header += mask
        masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
        with self.send_lock:
            self.connection.sendall(bytes(header) + masked)

    def recv(self) -> bytes:
        """Return the next binary message, or b"" once the peer closes."""
        message = bytearray()
        while True:
            first, second = self._recv_exactly(2)
            final = bool(first & 0x80)
            opcode = first & 0x0F
            length = second & 0x7F
            if length == 126:
                (length,) = struct.unpack("!H", self._recv_exactly(2))
            elif length == 127:
                (length,) = struct.unpack("!Q", self._recv_exactly(8))
            if second & 0x80:
                raise RuntimeError("the server masked a frame, which it must not")
            payload = self._recv_exactly(length) if length else b""
            if opcode == 0x8:
                self.closed = True
                return b""
            if opcode == 0x9:
                self._send_control(0xA, payload)
                continue
            if opcode == 0xA:
                continue
            message += payload
            if final:
                return bytes(message)

    def _send_control(self, opcode: int, payload: bytes) -> None:
        mask = secrets.token_bytes(4)
        masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
        with self.send_lock:
            self.connection.sendall(bytes([0x80 | opcode, 0x80 | len(payload)]) + mask + masked)

    def close(self) -> None:
        if not self.closed:
            self.closed = True
            with contextlib.suppress(OSError):
                self._send_control(0x8, b"\x03\xe8")
        with contextlib.suppress(OSError):
            self.connection.close()


class DatagramStream:
    """The circuit carries UDP as 2-byte big-endian length prefixed payloads,
    which do not line up with WebSocket message boundaries."""

    def __init__(self, socket_wrapper: WebSocket) -> None:
        self.socket = socket_wrapper
        self.buffer = bytearray()

    def _fill(self, count: int) -> bool:
        while len(self.buffer) < count:
            try:
                message = self.socket.recv()
            except (OSError, ConnectionError):
                # The circuit ended. Through nginx the transport can be gone
                # before the WebSocket close frame arrives, so this is the same
                # end of stream as an empty message, not a fault.
                return False
            if not message:
                return False
            self.buffer.extend(message)
        return True

    def read(self) -> bytes | None:
        if not self._fill(2):
            return None
        length = struct.unpack("!H", bytes(self.buffer[:2]))[0]
        del self.buffer[:2]
        if not self._fill(length):
            return None
        payload = bytes(self.buffer[:length])
        del self.buffer[:length]
        return payload

    def write(self, payload: bytes) -> None:
        self.socket.send(struct.pack("!H", len(payload)) + payload)


# --------------------------------------------------------------------------
# Echo destinations the circuits are pointed at.
# --------------------------------------------------------------------------
def serve_tcp_echo(listener: socket.socket, stop: threading.Event) -> None:
    while not stop.is_set():
        try:
            connection, _ = listener.accept()
        except OSError:
            return
        threading.Thread(target=echo_tcp_connection, args=(connection,), daemon=True).start()


def echo_tcp_connection(connection: socket.socket) -> None:
    with connection:
        while True:
            try:
                chunk = connection.recv(65536)
            except OSError:
                return
            if not chunk:
                return
            try:
                connection.sendall(chunk)
            except OSError:
                return


def serve_udp_echo(server: socket.socket, stop: threading.Event) -> None:
    while not stop.is_set():
        try:
            payload, peer = server.recvfrom(65535)
        except OSError:
            return
        with contextlib.suppress(OSError):
            server.sendto(b"echo:" + payload, peer)


# --------------------------------------------------------------------------
# The simulated phone. It heartbeats, long-polls for commands, and relays each
# circuit exactly as the Android agent does.
# --------------------------------------------------------------------------
class SimulatedPhone(threading.Thread):
    def __init__(
        self,
        base_url: str,
        host: str,
        port: int,
        node_id: str,
        token: str,
        context: ssl.SSLContext | None = None,
    ) -> None:
        super().__init__(daemon=True)
        self.base_url = base_url
        self.host = host
        self.port = port
        self.node_id = node_id
        self.token = token
        self.context = context
        self.stop = threading.Event()
        self.failure: BaseException | None = None
        self.circuits = 0
        self.lock = threading.Lock()

    def run(self) -> None:
        try:
            while not self.stop.is_set():
                command = self.next_command()
                if command is None:
                    continue
                kind = command.get("type")
                if kind in ("open_tcp", "open_udp"):
                    with self.lock:
                        self.circuits += 1
                    threading.Thread(target=self.relay, args=(command,), daemon=True).start()
        except BaseException as error:  # surfaced by the main thread
            self.fail(error, "control poll")

    def fail(self, error: BaseException, where: str) -> None:
        """Record the first failure and report it as it happens, so a stuck
        circuit shows its cause instead of only an open timeout."""
        if self.stop.is_set():
            return
        if self.failure is None:
            self.failure = error
        sys.stderr.write(f"simulated phone: {where}: {error!r}\n")
        sys.stderr.flush()

    def next_command(self) -> dict | None:
        request = urllib.request.Request(
            f"{self.base_url}/agent/v1/control?node_id={self.node_id}",
            headers={"Authorization": f"Bearer {self.token}"},
        )
        try:
            with urllib.request.urlopen(request, timeout=30, context=self.context) as response:
                if response.status == 204:
                    return None
                return json.loads(response.read())
        except urllib.error.URLError:
            if self.stop.is_set():
                return None
            time.sleep(0.2)
            return None

    def relay(self, command: dict) -> None:
        circuit_id = command["circuit_id"]
        target_host = command["target_host"]
        target_port = command["target_port"]
        try:
            socket_wrapper = WebSocket.connect(
                self.host,
                self.port,
                f"/agent/v1/circuits/{circuit_id}/ws?node_id={self.node_id}",
                {"Authorization": f"Bearer {self.token}"},
                self.context,
            )
        except Exception as error:
            self.fail(error, f"circuit {circuit_id} WebSocket")
            return
        try:
            self.post_status(circuit_id, "connected")
            if command["type"] == "open_tcp":
                self.relay_tcp(socket_wrapper, target_host, target_port)
            else:
                self.relay_udp(socket_wrapper, target_host, target_port)
        except Exception as error:
            self.fail(error, f"circuit {circuit_id} relay")
        finally:
            socket_wrapper.close()
            with contextlib.suppress(Exception):
                self.post_status(circuit_id, "closed")

    def relay_tcp(self, socket_wrapper: WebSocket, host: str, port: int) -> None:
        target = socket.create_connection((host, port), timeout=10)
        done = threading.Event()

        def upstream() -> None:
            try:
                while True:
                    chunk = target.recv(65536)
                    if not chunk:
                        break
                    socket_wrapper.send(chunk)
            except (OSError, ConnectionError, RuntimeError):
                pass
            finally:
                done.set()

        pump = threading.Thread(target=upstream, daemon=True)
        pump.start()
        try:
            while True:
                message = socket_wrapper.recv()
                if not message:
                    break
                target.sendall(message)
        except (OSError, ConnectionError, RuntimeError):
            pass
        finally:
            # Half-close, the way a relay ends a connection: stop writing so the
            # destination sees the end of the request and closes, which ends the
            # pump on its own. Shutting both directions instead would cut off
            # whatever the pump had not sent yet and turn an ordinary teardown
            # into a truncated response.
            with contextlib.suppress(OSError):
                target.shutdown(socket.SHUT_WR)
            done.wait(5)
            target.close()

    def relay_udp(self, socket_wrapper: WebSocket, host: str, port: int) -> None:
        stream = DatagramStream(socket_wrapper)
        target = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        target.settimeout(20)
        target.connect((host, port))

        def upstream() -> None:
            try:
                while True:
                    stream.write(target.recv(65535))
            except (OSError, ConnectionError, RuntimeError):
                pass

        threading.Thread(target=upstream, daemon=True).start()
        try:
            while True:
                payload = stream.read()
                if payload is None:
                    break
                target.send(payload)
        finally:
            target.close()

    def post_status(self, circuit_id: str, status: str) -> None:
        body = json.dumps({"node_id": self.node_id, "status": status}).encode()
        request = urllib.request.Request(
            f"{self.base_url}/agent/v1/circuits/{circuit_id}/status",
            data=body,
            method="POST",
            headers={
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(request, timeout=10, context=self.context) as response:
            if response.status != 204:
                raise RuntimeError(f"circuit status returned {response.status}")


# --------------------------------------------------------------------------
# SOCKS5 client.
# --------------------------------------------------------------------------
def socks_handshake(connection: socket.socket, credentials: Credentials) -> None:
    connection.sendall(b"\x05\x01\x02")
    if connection.recv(2) != b"\x05\x02":
        raise RuntimeError("the proxy did not select username/password authentication")
    username = credentials.username.encode()
    password = credentials.password.encode()
    connection.sendall(
        bytes([0x01, len(username)]) + username + bytes([len(password)]) + password
    )
    if connection.recv(2) != b"\x01\x00":
        raise RuntimeError("the proxy rejected the SOCKS credentials")


def read_socks_reply(connection: socket.socket) -> tuple[str, int]:
    header = recv_exactly(connection, 4)
    if header[0] != 0x05:
        raise RuntimeError("the proxy answered with a non-SOCKS5 reply")
    if header[1] != 0x00:
        raise RuntimeError(f"the proxy refused the request with code {header[1]}")
    kind = header[3]
    if kind == 0x01:
        host = socket.inet_ntoa(recv_exactly(connection, 4))
    elif kind == 0x04:
        host = socket.inet_ntop(socket.AF_INET6, recv_exactly(connection, 16))
    elif kind == 0x03:
        length = recv_exactly(connection, 1)[0]
        host = recv_exactly(connection, length).decode()
    else:
        raise RuntimeError(f"the proxy answered with address type {kind}")
    (port,) = struct.unpack("!H", recv_exactly(connection, 2))
    return host, port


def recv_exactly(connection: socket.socket, count: int) -> bytes:
    data = b""
    while len(data) < count:
        chunk = connection.recv(count - len(data))
        if not chunk:
            raise ConnectionError("the proxy closed the connection early")
        data += chunk
    return data


def exercise_tcp(
    socks_address: tuple[str, int],
    destination_port: int,
    credentials: Credentials,
    context: ssl.SSLContext | None = None,
    destination_host: str = "127.0.0.1",
) -> None:
    connection = connect_socks(socks_address, context)
    try:
        socks_handshake(connection, credentials)
        connection.sendall(
            b"\x05\x01\x00\x01"
            + socket.inet_aton(destination_host)
            + struct.pack("!H", destination_port)
        )
        read_socks_reply(connection)

        received = bytearray()
        ending = ["the read is still running"]

        def reader() -> None:
            try:
                while len(received) < len(TCP_PAYLOAD):
                    chunk = connection.recv(65536)
                    if not chunk:
                        ending[0] = "the proxy closed the connection"
                        return
                    received.extend(chunk)
                ending[0] = "every byte came back"
            except OSError as error:
                ending[0] = f"the read failed: {error!r}"

        pump = threading.Thread(target=reader, daemon=True)
        pump.start()
        connection.sendall(TCP_PAYLOAD)
        pump.join(60)
        if bytes(received) != TCP_PAYLOAD:
            raise RuntimeError(
                f"the CONNECT circuit echoed {len(received)} of {len(TCP_PAYLOAD)} bytes: {ending[0]}"
            )
    finally:
        connection.close()
    log(f"  CONNECT circuit round-tripped {len(TCP_PAYLOAD)} bytes")


def exercise_tcp_pingpong(
    socks_address: tuple[str, int],
    destination_port: int,
    credentials: Credentials,
    context: ssl.SSLContext | None = None,
    destination_host: str = "127.0.0.1",
    rounds: int = 8,
    chunk: int = 8192,
) -> None:
    """Round-trip a CONNECT circuit one chunk at a time.

    exercise_tcp streams half a megabyte with a reader thread running against
    the writer, which is the harder test and the right one for a plain socket.
    It cannot be used through TLS: an ssl.SSLSocket wraps a single OpenSSL
    connection object that is not safe for a concurrent read and write, so the
    TLS-wrapped listener is exercised by alternating instead. That still proves
    the wrapper carries both directions and preserves framing across many
    round trips, which is all the extra listener adds over the plain one.
    """
    connection = connect_socks(socks_address, context)
    try:
        socks_handshake(connection, credentials)
        connection.sendall(
            b"\x05\x01\x00\x01"
            + socket.inet_aton(destination_host)
            + struct.pack("!H", destination_port)
        )
        read_socks_reply(connection)
        for index in range(rounds):
            payload = bytes([index]) * chunk
            connection.sendall(payload)
            echoed = recv_exactly(connection, chunk)
            if echoed != payload:
                raise RuntimeError(f"round {index} of the CONNECT circuit came back altered")
    finally:
        connection.close()
    log(f"  CONNECT circuit round-tripped {rounds * chunk} bytes in {rounds} exchanges")



def exercise_udp(
    socks_address: tuple[str, int],
    destination_port: int,
    credentials: Credentials,
    context: ssl.SSLContext | None = None,
    destination_host: str = "127.0.0.1",
) -> None:
    control = connect_socks(socks_address, context)
    try:
        socks_handshake(control, credentials)
        control.sendall(b"\x05\x03\x00\x01" + socket.inet_aton("0.0.0.0") + struct.pack("!H", 0))
        relay_host, relay_port = read_socks_reply(control)

        client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        client.settimeout(30)
        try:
            payload = b"PocketExit-UDP-associate"
            packet = (
                b"\x00\x00\x00\x01"
                + socket.inet_aton(destination_host)
                + struct.pack("!H", destination_port)
                + payload
            )
            client.sendto(packet, (relay_host, relay_port))
            response, _ = client.recvfrom(65535)
        finally:
            client.close()
    finally:
        control.close()

    if len(response) < 10 or response[:3] != b"\x00\x00\x00" or response[3] != 0x01:
        raise RuntimeError(f"the UDP relay returned a malformed packet: {binascii.hexlify(response[:16])!r}")
    body = response[10:]
    if body != b"echo:" + payload:
        raise RuntimeError(f"the UDP circuit returned {body!r}")
    log(f"  UDP ASSOCIATE circuit round-tripped {len(body)} bytes via {relay_host}:{relay_port}")


# --------------------------------------------------------------------------
# --------------------------------------------------------------------------
# Control plane helpers. Every one takes an optional TLS context so the same
# call reaches a loopback backend or the gateway.
# --------------------------------------------------------------------------
def fetch(
    base_url: str,
    path: str,
    token: str,
    method: str = "GET",
    body: dict | None = None,
    context: ssl.SSLContext | None = None,
) -> bytes:
    data = None if body is None else json.dumps(body).encode()
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(base_url + path, data=data, method=method, headers=headers)
    with urllib.request.urlopen(request, timeout=15, context=context) as response:
        return response.read()


def api(
    base_url: str,
    path: str,
    token: str,
    method: str = "GET",
    body: dict | None = None,
    context: ssl.SSLContext | None = None,
) -> dict:
    raw = fetch(base_url, path, token, method, body, context)
    return json.loads(raw) if raw else {}


def status_of(
    base_url: str,
    path: str,
    token: str = "",
    context: ssl.SSLContext | None = None,
) -> int:
    """The status code alone, with a rejection reported rather than raised.

    Used for the negative assertions, where a 401 is the pass condition.
    """
    try:
        fetch(base_url, path, token, context=context)
    except urllib.error.HTTPError as error:
        return error.code
    return 200


def wait_for_health(
    base_url: str,
    context: ssl.SSLContext | None = None,
    is_alive: typing.Callable[[], bool] | None = None,
    attempts: int = 150,
) -> None:
    """Block until /api/v1/health answers 200.

    is_alive lets a caller that owns the process fail immediately when it dies
    instead of waiting out the whole window for a server that is never coming.
    """
    for _ in range(attempts):
        if is_alive is not None and not is_alive():
            raise SystemExit("the backend exited before it became healthy")
        try:
            request = urllib.request.Request(base_url + "/api/v1/health")
            with urllib.request.urlopen(request, timeout=2, context=context) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, OSError):
            time.sleep(0.2)
    raise SystemExit(f"{base_url}/api/v1/health never answered 200")


def heartbeat_body(node_id: str, device_name: str) -> dict:
    """The heartbeat the Android agent sends, with both radios up.

    Both are marked validated so the selector has a real choice to make; a
    policy that could only ever pick one network would not prove much.
    """
    return {
        "node_id": node_id,
        "device_name": device_name,
        "app_version": "ci",
        "control_policy": "AUTO",
        "exit_policy": "CELLULAR_PREFERRED",
        "active_control_network": "WIFI",
        "transport_protocol": "h3",
        "wifi": {"available": True, "validated": True},
        "cellular": {"available": True, "validated": True},
        "battery_percent": 80,
        "charging": True,
        "active_circuits": 0,
        "bytes_up": 0,
        "bytes_down": 0,
    }


class EchoDestinations:
    """A TCP and a UDP echo server on loopback.

    The simulated phone dials these directly, exactly as a real phone dials the
    Internet, so they live wherever the phone runs rather than wherever the
    backend runs.
    """

    def __init__(self) -> None:
        self.stop = threading.Event()
        self.tcp = socket.socket()
        self.tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.tcp.bind(("127.0.0.1", 0))
        self.tcp.listen(16)
        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp.bind(("127.0.0.1", 0))
        threading.Thread(target=serve_tcp_echo, args=(self.tcp, self.stop), daemon=True).start()
        threading.Thread(target=serve_udp_echo, args=(self.udp, self.stop), daemon=True).start()

    @property
    def tcp_port(self) -> int:
        return self.tcp.getsockname()[1]

    @property
    def udp_port(self) -> int:
        return self.udp.getsockname()[1]

    def close(self) -> None:
        self.stop.set()
        for closeable in (self.tcp, self.udp):
            with contextlib.suppress(OSError):
                closeable.close()

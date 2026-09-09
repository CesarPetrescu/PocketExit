#!/usr/bin/env python3
"""End-to-end exercise of the data plane: a real backend process, a simulated
phone agent speaking the agent protocol over WebSockets, and a SOCKS5 client
pushing TCP and UDP traffic through both.

scripts/smoke-backend.sh proves the control plane answers. This proves bytes
actually make the round trip: SOCKS5 client -> backend -> agent WebSocket ->
destination and back, for a CONNECT circuit and for a UDP ASSOCIATE circuit.

Standard library only, so CI needs nothing beyond Python and the Go toolchain.
"""
from __future__ import annotations

import base64
import binascii
import contextlib
import hashlib
import json
import os
import pathlib
import re
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
NODE_ID = "e2e-phone"
ADMIN_TOKEN = "e2e-admin-token-2026"
AGENT_TOKEN = "e2e-agent-token-2026"
SOCKS_USERNAME = "proxy"
SOCKS_PASSWORD = "e2e-proxy-password-2026"
CIRCUIT_SUBPROTOCOL = "pocketexit.circuit.v1"
WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
UDP_PORT_START = 24000
UDP_PORT_END = 24007

# 512 KiB crosses the backend's 32 KiB and 64 KiB copy buffers many times over,
# so a framing bug shows up as a mismatch rather than passing by luck.
TCP_PAYLOAD_SIZE = 512 * 1024
_PATTERN = b"PocketExit-SOCKS5-end-to-end-"
TCP_PAYLOAD = (_PATTERN * (TCP_PAYLOAD_SIZE // len(_PATTERN) + 1))[:TCP_PAYLOAD_SIZE]


def log(message: str) -> None:
    print(message, flush=True)


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


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
    def connect(cls, host: str, port: int, target: str, headers: dict[str, str]) -> "WebSocket":
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
            message = self.socket.recv()
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
    def __init__(self, base_url: str, host: str, port: int) -> None:
        super().__init__(daemon=True)
        self.base_url = base_url
        self.host = host
        self.port = port
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
            f"{self.base_url}/agent/v1/control?node_id={NODE_ID}",
            headers={"Authorization": f"Bearer {AGENT_TOKEN}"},
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
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
                f"/agent/v1/circuits/{circuit_id}/ws?node_id={NODE_ID}",
                {"Authorization": f"Bearer {AGENT_TOKEN}"},
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
            with contextlib.suppress(OSError):
                target.shutdown(socket.SHUT_RDWR)
            target.close()
            done.wait(5)

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
        body = json.dumps({"node_id": NODE_ID, "status": status}).encode()
        request = urllib.request.Request(
            f"{self.base_url}/agent/v1/circuits/{circuit_id}/status",
            data=body,
            method="POST",
            headers={
                "Authorization": f"Bearer {AGENT_TOKEN}",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(request, timeout=10) as response:
            if response.status != 204:
                raise RuntimeError(f"circuit status returned {response.status}")


# --------------------------------------------------------------------------
# SOCKS5 client.
# --------------------------------------------------------------------------
def socks_handshake(connection: socket.socket) -> None:
    connection.sendall(b"\x05\x01\x02")
    if connection.recv(2) != b"\x05\x02":
        raise RuntimeError("the proxy did not select username/password authentication")
    username = SOCKS_USERNAME.encode()
    password = SOCKS_PASSWORD.encode()
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


def exercise_tcp(socks_address: tuple[str, int], destination_port: int) -> None:
    connection = socket.create_connection(socks_address, timeout=30)
    connection.settimeout(60)
    try:
        socks_handshake(connection)
        connection.sendall(
            b"\x05\x01\x00\x01"
            + socket.inet_aton("127.0.0.1")
            + struct.pack("!H", destination_port)
        )
        read_socks_reply(connection)

        received = bytearray()

        def reader() -> None:
            while len(received) < len(TCP_PAYLOAD):
                chunk = connection.recv(65536)
                if not chunk:
                    return
                received.extend(chunk)

        pump = threading.Thread(target=reader, daemon=True)
        pump.start()
        connection.sendall(TCP_PAYLOAD)
        pump.join(60)
        if bytes(received) != TCP_PAYLOAD:
            raise RuntimeError(
                f"the CONNECT circuit echoed {len(received)} of {len(TCP_PAYLOAD)} bytes incorrectly"
            )
    finally:
        connection.close()
    log(f"  CONNECT circuit round-tripped {len(TCP_PAYLOAD)} bytes")


def exercise_udp(socks_address: tuple[str, int], destination_port: int) -> None:
    control = socket.create_connection(socks_address, timeout=30)
    control.settimeout(60)
    try:
        socks_handshake(control)
        control.sendall(b"\x05\x03\x00\x01" + socket.inet_aton("0.0.0.0") + struct.pack("!H", 0))
        relay_host, relay_port = read_socks_reply(control)

        client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        client.settimeout(30)
        try:
            payload = b"PocketExit-UDP-associate"
            packet = (
                b"\x00\x00\x00\x01"
                + socket.inet_aton("127.0.0.1")
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
def fetch(base_url: str, path: str, token: str, method: str = "GET", body: dict | None = None) -> bytes:
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(
        base_url + path,
        data=data,
        method=method,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        return response.read()


def api(base_url: str, path: str, token: str, method: str = "GET", body: dict | None = None) -> dict:
    raw = fetch(base_url, path, token, method, body)
    return json.loads(raw) if raw else {}


def wait_for_health(base_url: str, process: subprocess.Popen) -> None:
    """The caller prints the backend log for any failure, so this only says
    which of the two ways the process failed to come up."""
    for _ in range(150):
        if process.poll() is not None:
            raise SystemExit("the backend exited before it became healthy")
        try:
            with urllib.request.urlopen(base_url + "/api/v1/health", timeout=2) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, OSError):
            time.sleep(0.1)
    raise SystemExit("the backend never answered /api/v1/health")


def main() -> int:
    temporary = tempfile.mkdtemp(prefix="pocketexit-e2e-")
    workspace = pathlib.Path(temporary)
    binary = workspace / "pocketexit"
    subprocess.run(
        ["go", "build", "-o", str(binary), "./cmd/server"],
        cwd=ROOT / "backend",
        check=True,
    )

    http_port = free_port()
    socks_port = free_port()
    base_url = f"http://127.0.0.1:{http_port}"
    log_path = workspace / "backend.log"

    environment = dict(os.environ)
    environment.update(
        {
            "HTTP_ADDR": f"127.0.0.1:{http_port}",
            "SOCKS_ADDR": f"127.0.0.1:{socks_port}",
            "PUBLIC_PROXY_HOST": "127.0.0.1",
            "UDP_BIND_HOST": "127.0.0.1",
            "UDP_PORT_START": str(UDP_PORT_START),
            "UDP_PORT_END": str(UDP_PORT_END),
            "ADMIN_TOKEN": ADMIN_TOKEN,
            "SOCKS_USERNAME": SOCKS_USERNAME,
            "SOCKS_PASSWORD": SOCKS_PASSWORD,
            "AGENT_TOKENS_JSON": json.dumps({NODE_ID: AGENT_TOKEN}),
            "AUDIT_LOG_PATH": str(workspace / "audit.jsonl"),
            # The echo destinations are on loopback, which the ACL blocks by
            # default; server mode's own default stays false everywhere else.
            "ALLOW_PRIVATE_DESTINATIONS": "true",
            "COMMAND_WAIT": "2s",
            "OPEN_TIMEOUT": "20s",
            "LOG_JSON": "false",
        }
    )

    stop = threading.Event()
    tcp_echo = socket.socket()
    tcp_echo.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    tcp_echo.bind(("127.0.0.1", 0))
    tcp_echo.listen(16)
    udp_echo = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp_echo.bind(("127.0.0.1", 0))
    threading.Thread(target=serve_tcp_echo, args=(tcp_echo, stop), daemon=True).start()
    threading.Thread(target=serve_udp_echo, args=(udp_echo, stop), daemon=True).start()

    phone: SimulatedPhone | None = None
    with open(log_path, "wb") as log_file:
        process = subprocess.Popen(
            [str(binary)], env=environment, stdout=log_file, stderr=subprocess.STDOUT
        )
    try:
        wait_for_health(base_url, process)
        log("backend is healthy")

        heartbeat = {
            "node_id": NODE_ID,
            "device_name": "End-to-end Phone",
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
        registered = api(base_url, "/agent/v1/heartbeat", AGENT_TOKEN, "POST", heartbeat)
        if registered.get("node_id") != NODE_ID:
            raise SystemExit(f"the heartbeat registered {registered}")
        log("simulated phone registered")

        phone = SimulatedPhone(base_url, "127.0.0.1", http_port)
        phone.start()

        exercise_tcp(("127.0.0.1", socks_port), tcp_echo.getsockname()[1])
        exercise_udp(("127.0.0.1", socks_port), udp_echo.getsockname()[1])
        # A second CONNECT proves the agent survives a closed circuit and the
        # backend hands out a fresh one rather than reusing a dead stream.
        exercise_tcp(("127.0.0.1", socks_port), tcp_echo.getsockname()[1])

        if phone.failure is not None:
            raise SystemExit(f"the simulated phone failed: {phone.failure!r}")
        with phone.lock:
            opened = phone.circuits
        if opened < 3:
            raise SystemExit(f"the simulated phone only saw {opened} circuits")

        circuits = api(base_url, "/api/v1/circuits", ADMIN_TOKEN)
        protocols = {entry.get("protocol") for entry in circuits.get("circuits", [])}
        if not {"tcp", "udp"} <= protocols:
            raise SystemExit(f"the circuit list is missing a protocol: {protocols}")
        # /api/v1/metrics answers Prometheus text, not JSON.
        metrics = fetch(base_url, "/api/v1/metrics", ADMIN_TOKEN).decode()
        if "pocketexit_nodes_online 1" not in metrics:
            raise SystemExit("metrics did not report exactly one node online")
        log(f"  backend recorded {len(circuits.get('circuits', []))} circuits: {sorted(protocols)}")
    except BaseException:
        # The backend's own log is the only place a refused circuit explains
        # itself, so it goes to stderr before the workspace is torn down.
        sys.stderr.write(log_path.read_text())
        raise
    finally:
        stop.set()
        if phone is not None:
            phone.stop.set()
        for closeable in (tcp_echo, udp_echo):
            with contextlib.suppress(OSError):
                closeable.close()
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=15)

    shutil.rmtree(workspace, ignore_errors=True)
    log("SOCKS5 end-to-end test passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""System test: real traffic through the whole deployed stack.

socks-e2e.py drives the backend directly on loopback, so it proves the backend.
This drives the same traffic through the compose stack — nginx in front, the
backend behind it on a private network — so it proves the parts only the
gateway owns and that nothing else covers:

  * TLS termination and the security headers on the dashboard origin;
  * the plain HTTP listener redirecting to HTTPS;
  * proxying of /api/ and, with the Upgrade map, the agent's long poll and
    circuit WebSockets under /agent/;
  * the stream SOCKS5 listener on 1080;
  * the TLS-wrapped stream SOCKS5 listener on 1081;
  * the UDP stream listeners on 12000-12031 and the $server_port map that
    sends each one to the matching backend port.

The gateway's stream configuration is otherwise never executed: nginx -t only
parses it. A typo in the UDP port map is invisible until a phone tries to use
the proxy, which is exactly what this runs.

Requires docker with the compose plugin, and a .env produced by
scripts/setup.sh. Nothing in .env is modified: the values this test needs to
differ are supplied through a generated compose override instead.
"""
from __future__ import annotations

import argparse
import contextlib
import http.client
import json
import pathlib
import ssl
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from pocketexit_harness import (  # noqa: E402
    Credentials,
    EchoDestinations,
    SimulatedPhone,
    api,
    connect_socks,
    development_tls_context,
    exercise_tcp,
    exercise_tcp_pingpong,
    exercise_udp,
    fetch,
    heartbeat_body,
    log,
    status_of,
    wait_for_health,
)

ROOT = pathlib.Path(__file__).resolve().parents[2]
NODE_ID = "system-test-phone"
AGENT_TOKEN = "system-test-agent-token-2026"
GATEWAY = "127.0.0.1"
BASE_URL = f"https://{GATEWAY}"
SOCKS_PORT = 1080
SOCKS_TLS_PORT = 1081

# nginx sets these on every dashboard response. They are the difference between
# a gateway and an open origin, so a change that drops one has to fail here.
REQUIRED_HEADERS = {
    "content-security-policy": "default-src 'self'",
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY",
    "referrer-policy": "no-referrer",
    "permissions-policy": "camera=()",
}


def read_env(path: pathlib.Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, _, value = line.partition("=")
        values[name.strip()] = value.strip()
    return values


def write_override(directory: pathlib.Path) -> pathlib.Path:
    """The three settings this test needs that a deployment must not have.

    Written as a compose override rather than into .env so a developer's own
    configuration survives the run untouched. JSON is valid YAML, and using it
    keeps the embedded token map out of quoting trouble.
    """
    override = directory / "system-test-override.yml"
    override.write_text(
        json.dumps(
            {
                "services": {
                    "backend": {
                        "environment": {
                            # The echo destinations live on the runner's
                            # loopback, which the ACL blocks by default.
                            "ALLOW_PRIVATE_DESTINATIONS": "true",
                            # What the backend tells a SOCKS client to send its
                            # datagrams to: the gateway's published UDP ports.
                            "PUBLIC_PROXY_HOST": GATEWAY,
                            "AGENT_TOKENS_JSON": json.dumps({NODE_ID: AGENT_TOKEN}),
                        }
                    }
                }
            },
            indent=2,
        )
    )
    return override


class Stack:
    def __init__(self, override: pathlib.Path) -> None:
        self.command = [
            "docker",
            "compose",
            "-f",
            str(ROOT / "docker-compose.yml"),
            "-f",
            str(override),
        ]

    def run(self, *arguments: str, check: bool = True) -> subprocess.CompletedProcess:
        return subprocess.run(self.command + list(arguments), cwd=ROOT, check=check)

    def up(self) -> None:
        self.run("up", "-d")

    def down(self) -> None:
        self.run("down", "-v", check=False)

    def dump_logs(self) -> None:
        sys.stderr.write("\n===== docker compose logs =====\n")
        sys.stderr.flush()
        self.run("logs", "--no-color", "--tail", "400", check=False)


def check_nginx_configuration(stack: Stack) -> None:
    stack.run("exec", "-T", "nginx", "nginx", "-t")
    log("  nginx accepted its configuration")


def check_https_surface(context: ssl.SSLContext) -> None:
    connection = http.client.HTTPSConnection(GATEWAY, 443, timeout=15, context=context)
    try:
        connection.request("GET", "/")
        response = connection.getresponse()
        body = response.read().decode(errors="replace")
        if response.status != 200:
            raise SystemExit(f"the dashboard answered {response.status}")
        if "<title>" not in body:
            raise SystemExit("the gateway served something that is not the dashboard")
        headers = {name.lower(): value for name, value in response.getheaders()}
        for name, expected in REQUIRED_HEADERS.items():
            if expected not in headers.get(name, ""):
                raise SystemExit(f"the dashboard response is missing {name}: {expected}")
        if "h3" not in headers.get("alt-svc", ""):
            raise SystemExit("the gateway did not advertise HTTP/3")
    finally:
        connection.close()
    log(f"  dashboard served over TLS with {len(REQUIRED_HEADERS)} security headers and Alt-Svc")


def check_static_routing(context: ssl.SSLContext) -> None:
    """try_files sends unknown paths to index.html; the scripts must not be one.

    A missing script does not 404 here — it falls through to index.html and
    answers 200 with HTML, which the browser then refuses as a module for the
    wrong MIME type. app.js imports lib.js, so both are checked by name and by
    content type rather than by status alone.
    """
    for path in ("/app.js", "/lib.js"):
        connection = http.client.HTTPSConnection(GATEWAY, 443, timeout=15, context=context)
        try:
            connection.request("GET", path)
            response = connection.getresponse()
            script = response.read().decode(errors="replace")
            if response.status != 200:
                raise SystemExit(f"the gateway answered {response.status} for {path}")
            if "javascript" not in response.getheader("Content-Type", ""):
                raise SystemExit(f"{path} was not served with a script content type")
            if "<title>" in script:
                raise SystemExit(f"try_files swallowed {path} into index.html")
        finally:
            connection.close()

    connection = http.client.HTTPSConnection(GATEWAY, 443, timeout=15, context=context)
    try:
        connection.request("GET", "/a/route/the/dashboard/owns")
        response = connection.getresponse()
        if response.status != 200 or "<title>" not in response.read().decode(errors="replace"):
            raise SystemExit("try_files did not fall back to the dashboard")
    finally:
        connection.close()
    log("  static routing serves every module and falls back to the dashboard")


def check_plain_http_redirects() -> None:
    connection = http.client.HTTPConnection(GATEWAY, 80, timeout=15)
    try:
        connection.request("GET", "/api/v1/health")
        response = connection.getresponse()
        response.read()
        location = response.getheader("Location", "")
        if response.status != 308 or not location.startswith("https://"):
            raise SystemExit(f"port 80 answered {response.status} to {location!r}, not a 308")
    finally:
        connection.close()
    log("  plain HTTP redirects to HTTPS with 308")


def check_admin_authentication(admin_token: str, context: ssl.SSLContext) -> None:
    unauthenticated = status_of(BASE_URL, "/api/v1/nodes", "", context)
    if unauthenticated != 401:
        raise SystemExit(f"the admin API answered {unauthenticated} without a token")
    nodes = api(BASE_URL, "/api/v1/nodes", admin_token, context=context)
    if "nodes" not in nodes:
        raise SystemExit(f"the admin API returned an unexpected shape: {nodes}")
    log("  the admin API is reachable through the gateway and rejects an anonymous caller")


def check_socks_rejects_a_bad_password(credentials: Credentials) -> None:
    wrong = Credentials(credentials.username, credentials.password + "-wrong")
    connection = connect_socks((GATEWAY, SOCKS_PORT))
    try:
        connection.sendall(b"\x05\x01\x02")
        if connection.recv(2) != b"\x05\x02":
            raise SystemExit("the gateway did not carry the SOCKS method negotiation")
        username = wrong.username.encode()
        password = wrong.password.encode()
        connection.sendall(
            bytes([0x01, len(username)]) + username + bytes([len(password)]) + password
        )
        reply = connection.recv(2)
        if reply == b"\x01\x00":
            raise SystemExit("the proxy accepted a wrong SOCKS password")
        if reply and reply[0] != 0x01:
            raise SystemExit(f"the proxy answered the auth attempt with {reply!r}")
    finally:
        connection.close()
    log("  the SOCKS listener rejects a wrong password through the gateway")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--keep",
        action="store_true",
        help="leave the stack running after the test, for debugging",
    )
    options = parser.parse_args()

    env_path = ROOT / ".env"
    if not env_path.exists():
        raise SystemExit("no .env; run scripts/setup.sh localhost first")
    environment = read_env(env_path)
    admin_token = environment.get("ADMIN_TOKEN", "")
    credentials = Credentials(
        environment.get("SOCKS_USERNAME", "proxy"), environment.get("SOCKS_PASSWORD", "")
    )
    if not admin_token or not credentials.password:
        raise SystemExit(".env is missing ADMIN_TOKEN or SOCKS_PASSWORD")

    context = development_tls_context()
    workspace = pathlib.Path(tempfile.mkdtemp(prefix="pocketexit-system-"))
    stack = Stack(write_override(workspace))
    echo = EchoDestinations()
    phone: SimulatedPhone | None = None

    try:
        log("starting the stack")
        stack.up()
        wait_for_health(BASE_URL, context, attempts=300)
        log("the gateway is healthy")

        check_nginx_configuration(stack)
        check_https_surface(context)
        check_static_routing(context)
        check_plain_http_redirects()
        check_admin_authentication(admin_token, context)

        registered = api(
            BASE_URL,
            "/agent/v1/heartbeat",
            AGENT_TOKEN,
            "POST",
            heartbeat_body(NODE_ID, "System Test Phone"),
            context,
        )
        if registered.get("node_id") != NODE_ID:
            raise SystemExit(f"the heartbeat through the gateway registered {registered}")
        log("  the simulated phone registered through the gateway")

        phone = SimulatedPhone(BASE_URL, GATEWAY, 443, NODE_ID, AGENT_TOKEN, context)
        phone.start()

        log("driving traffic through the gateway")
        exercise_tcp((GATEWAY, SOCKS_PORT), echo.tcp_port, credentials)
        exercise_udp((GATEWAY, SOCKS_PORT), echo.udp_port, credentials)
        exercise_tcp_pingpong((GATEWAY, SOCKS_TLS_PORT), echo.tcp_port, credentials, context)
        check_socks_rejects_a_bad_password(credentials)

        if phone.failure is not None:
            raise SystemExit(f"the simulated phone failed: {phone.failure!r}")
        with phone.lock:
            opened = phone.circuits
        if opened < 3:
            raise SystemExit(f"the simulated phone only saw {opened} circuits")

        circuits = api(BASE_URL, "/api/v1/circuits", admin_token, context=context)
        protocols = {entry.get("protocol") for entry in circuits.get("circuits", [])}
        if not {"tcp", "udp"} <= protocols:
            raise SystemExit(f"the circuit list is missing a protocol: {protocols}")
        metrics = fetch(BASE_URL, "/api/v1/metrics", admin_token, context=context).decode()
        if "pocketexit_nodes_online 1" not in metrics:
            raise SystemExit("metrics did not report exactly one node online")
        log(
            f"  the gateway carried {len(circuits.get('circuits', []))} circuits: "
            f"{sorted(protocols)}"
        )
    except BaseException:
        stack.dump_logs()
        raise
    finally:
        if phone is not None:
            phone.stop.set()
        echo.close()
        if options.keep:
            log("leaving the stack running; tear it down with 'docker compose down -v'")
        else:
            stack.down()
        with contextlib.suppress(OSError):
            (workspace / "system-test-override.yml").unlink()
            workspace.rmdir()

    log("system test passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""End-to-end exercise of the data plane against a backend process on loopback.

A real backend, a simulated phone speaking the agent protocol over WebSockets,
and a SOCKS5 client pushing TCP and UDP through both. scripts/smoke-backend.sh
proves the control plane answers; this proves bytes make the round trip:
SOCKS5 client -> backend -> agent WebSocket -> destination and back, for a
CONNECT circuit and for a UDP ASSOCIATE circuit.

Nothing here goes through nginx. system-test.py runs the same traffic through
the gateway, so a failure in one and not the other says which side is at fault.
"""
from __future__ import annotations

import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from pocketexit_harness import (  # noqa: E402
    Credentials,
    EchoDestinations,
    SimulatedPhone,
    api,
    exercise_tcp,
    exercise_udp,
    fetch,
    free_port,
    heartbeat_body,
    log,
    wait_for_health,
)

ROOT = pathlib.Path(__file__).resolve().parents[2]
NODE_ID = "e2e-phone"
ADMIN_TOKEN = "e2e-admin-token-2026"
AGENT_TOKEN = "e2e-agent-token-2026"
CREDENTIALS = Credentials("proxy", "e2e-proxy-password-2026")
UDP_PORT_START = 24000
UDP_PORT_END = 24007


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
            "SOCKS_USERNAME": CREDENTIALS.username,
            "SOCKS_PASSWORD": CREDENTIALS.password,
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

    echo = EchoDestinations()
    phone: SimulatedPhone | None = None
    with open(log_path, "wb") as log_file:
        process = subprocess.Popen(
            [str(binary)], env=environment, stdout=log_file, stderr=subprocess.STDOUT
        )
    try:
        wait_for_health(base_url, is_alive=lambda: process.poll() is None)
        log("backend is healthy")

        registered = api(
            base_url,
            "/agent/v1/heartbeat",
            AGENT_TOKEN,
            "POST",
            heartbeat_body(NODE_ID, "End-to-end Phone"),
        )
        if registered.get("node_id") != NODE_ID:
            raise SystemExit(f"the heartbeat registered {registered}")
        log("simulated phone registered")

        phone = SimulatedPhone(base_url, "127.0.0.1", http_port, NODE_ID, AGENT_TOKEN)
        phone.start()

        socks = ("127.0.0.1", socks_port)
        exercise_tcp(socks, echo.tcp_port, CREDENTIALS)
        exercise_udp(socks, echo.udp_port, CREDENTIALS)
        # A second CONNECT proves the agent survives a closed circuit and the
        # backend hands out a fresh one rather than reusing a dead stream.
        exercise_tcp(socks, echo.tcp_port, CREDENTIALS)

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
        if phone is not None:
            phone.stop.set()
        echo.close()
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

# Architecture

## Design goals

PocketExit is designed for a small, owner-operated fleet of unrooted Android phones. Its priorities are:

1. independent control and exit routing;
2. no inbound listener on a phone;
3. no root, ADB, VPN service, or device-wide route override;
4. one public gateway;
5. QUIC on the mobile-facing path;
6. TCP and UDP proxy support;
7. explicit authentication and conservative destination filtering.

## Components

### Nginx gateway

Nginx is the only container with published host ports.

| Port | Transport | Purpose |
|---|---|---|
| 80 | TCP | HTTPS redirect |
| 443 | TCP | HTTPS and HTTP/2 |
| 443 | UDP | HTTP/3/QUIC |
| 1080 | TCP | Authenticated SOCKS5 |
| 1081 | TLS/TCP | TLS-wrapped authenticated SOCKS5 |
| 12000–12031 | UDP | Dynamically allocated SOCKS5 UDP relays |

Nginx serves the static dashboard, reverse-proxies `/api/` and `/agent/` to the
Go backend, and upgrades circuit requests to WebSockets. Port 1080 is the raw
SOCKS endpoint for trusted networks; port 1081 adds an outer TLS session for a
TLS-wrapper client.

HTTP/3 terminates at Nginx. Nginx forwards the private hop to Go using HTTP/1.1
over the Compose bridge network. Control requests may use HTTP/3 while circuit
data uses an authenticated WebSocket, which hosted connectors can relay without
HTTP request-body streaming timeouts.

### Go backend

The Go process has four logical subsystems:

```text
HTTP API ───────────────┐
Node registry ──────────┼── Circuit manager ── pipes + quotas
SOCKS5 TCP/UDP server ──┤
Scheduler ──────────────┘
```

The node registry stores the latest heartbeat and a bounded command queue for every registered phone. The scheduler selects an online, enabled node with a validated network satisfying the requested policy. With no explicit node selector, it prefers the least-loaded eligible node.

Each proxy connection creates one circuit. The circuit manager owns two in-memory pipes:

```text
client → phone: down pipe
phone  → client: up pipe
```

### Android agent

The Android application has four layers:

```text
Compose UI
    │ StateFlow
Foreground service
    ├── NetworkMonitor
    ├── CronetTransport (control)
    ├── WebSocketTransport (circuit data)
    └── CircuitManager
            ├── bound TCP sockets
            └── bound UDP sockets
```

`NetworkMonitor` registers a Wi-Fi callback and requests a cellular `Network`. Requesting cellular keeps it available while Wi-Fi remains Android's default route. The process itself is never globally bound.

For control requests, Cronet binds each HTTP request to the selected `Network` handle. For destination traffic, DNS resolution and sockets use the selected exit `Network` object.

## Independent routing

Two policies are evaluated independently:

```text
control policy → network carrying heartbeats, commands, and circuit WebSockets
exit policy    → network carrying DNS and destination TCP/UDP sockets
```

Examples:

| Control | Exit | Result |
|---|---|---|
| Wi-Fi preferred | Cellular only | Control traffic uses Wi-Fi when possible; public egress uses SIM data |
| Cellular only | Cellular only | Entire path uses SIM data |
| Cellular preferred | Wi-Fi only | Control survives outside Wi-Fi; destination requires Wi-Fi |
| Automatic | Automatic | Wi-Fi first, cellular fallback |

A circuit's control and exit network are selected when it opens. If either selected network disappears, the circuit closes. The agent's control loop then reconnects according to policy and can accept new circuits.

## TCP data path

```text
SOCKS client TCP
  → Nginx stream proxy
  → Go SOCKS5 parser/authenticator
  → circuit down pipe
  → Nginx WebSocket reverse proxy
  → authenticated binary WebSocket
  → Android OkHttp callback
  → TCP socket bound to selected Android Network
  → destination
```

The return direction shares the same full-duplex WebSocket. Each circuit has an
independent connection, bounded Android-side buffering, ping frames, and one
combined up/down byte quota.

## UDP data path

SOCKS5 UDP ASSOCIATE first authenticates over TCP. The backend allocates one port from a bounded pool and Nginx forwards that public UDP port to the matching backend port. The association accepts datagrams only from the first observed client endpoint.

Each destination tuple within the association creates a connected Android UDP circuit. Datagram boundaries are preserved with a two-byte big-endian length prefix over the circuit stream.

```text
[uint16 payload length][payload]
```

This implementation intentionally uses a reliable WebSocket, not QUIC DATAGRAM
or MASQUE CONNECT-UDP. It is suitable for DNS and ordinary low-volume UDP use,
but real-time applications may experience head-of-line delay after loss.

## Failure handling

- Heartbeats run every 15 seconds.
- Control long polls return after 5 seconds by default so hosted connectors deliver commands inside the circuit-open window.
- Missing networks produce a waiting state rather than route leakage.
- Circuit-open acknowledgement is required before SOCKS reports success.
- TCP destination connect timeout is 10 seconds.
- Backend open timeout is 45 seconds by default.
- WebSocket completion closes both directions.
- Stale closed circuit records are pruned.
- Agent restarts are serialized so two transports cannot run concurrently.

## State and scale

Node, command, and circuit state is process-local and in memory. Structured
security events are persisted as JSON Lines on the backend data volume. This is
appropriate for three phones and one backend instance. Horizontal replication
would require shared node presence, command delivery, circuit ownership, and
session affinity; those concerns are deliberately outside this MVP.

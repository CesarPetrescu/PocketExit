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
| 12000–12031 | UDP | Dynamically allocated SOCKS5 UDP relays |

Nginx serves the static dashboard and reverse-proxies `/api/` and `/agent/` to the Go backend. Request and response buffering are disabled under `/agent/` so circuit bodies stream rather than accumulate on disk or in memory.

HTTP/3 terminates at Nginx. Nginx forwards the private hop to Go using HTTP/1.1 over the Compose bridge network. This keeps the Go implementation dependency-free while retaining QUIC's benefits on the lossy mobile/public segment.

### Go backend

The Go process has four logical subsystems:

```text
HTTP API ───────────────┐
Node registry ──────────┼── Circuit manager ── streaming pipes
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
    ├── CronetTransport
    └── CircuitManager
            ├── bound TCP sockets
            └── bound UDP sockets
```

`NetworkMonitor` registers a Wi-Fi callback and requests a cellular `Network`. Requesting cellular keeps it available while Wi-Fi remains Android's default route. The process itself is never globally bound.

For control requests, Cronet binds each HTTP request to the selected `Network` handle. For destination traffic, DNS resolution and sockets use the selected exit `Network` object.

## Independent routing

Two policies are evaluated independently:

```text
control policy → network carrying heartbeats, commands, and HTTP/3 circuit streams
exit policy    → network carrying DNS and destination TCP/UDP sockets
```

Examples:

| Control | Exit | Result |
|---|---|---|
| Wi-Fi preferred | Cellular only | QUIC uses Wi-Fi when possible; public egress uses SIM data |
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
  → Nginx HTTPS reverse proxy
  → HTTP/3 stream over QUIC
  → Android Cronet callback
  → TCP socket bound to selected Android Network
  → destination
```

The return direction uses a separate streaming HTTP request. One circuit therefore maps to independent HTTP/3 streams instead of multiplexing every connection inside one custom byte stream.

## UDP data path

SOCKS5 UDP ASSOCIATE first authenticates over TCP. The backend allocates one port from a bounded pool and Nginx forwards that public UDP port to the matching backend port. The association accepts datagrams only from the first observed client endpoint.

Each destination tuple within the association creates a connected Android UDP circuit. Datagram boundaries are preserved with a two-byte big-endian length prefix over the circuit stream.

```text
[uint16 payload length][payload]
```

This implementation intentionally uses reliable HTTP/3 streams, not QUIC DATAGRAM or MASQUE CONNECT-UDP. It is suitable for DNS and ordinary low-volume UDP use, but real-time applications may experience head-of-line delay after loss.

## Failure handling

- Heartbeats run every 15 seconds.
- Control long polls return after 5 seconds by default so hosted connectors deliver commands inside the circuit-open window.
- Missing networks produce a waiting state rather than route leakage.
- Circuit-open acknowledgement is required before SOCKS reports success.
- TCP destination connect timeout is 10 seconds.
- Backend open timeout is 45 seconds by default.
- Circuit stream completion closes both directions.
- Stale closed circuit records are pruned.
- Agent restarts are serialized so two transports cannot run concurrently.

## State and scale

All state is process-local and in memory. This is appropriate for three phones and a single backend instance. Horizontal replication would require shared node presence, command delivery, circuit ownership, and session affinity; those concerns are deliberately outside this MVP.

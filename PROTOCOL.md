# Protocol

Wire formats for the three surfaces: agent, admin, and pairing. The pairing
surface exists only in personal mode, and
[docs/pairing-protocol.md](docs/pairing-protocol.md) is its binding
specification — certificate parameters, pin construction, code alphabet, URI
grammar, status-code mapping, and threat model. This document says what the
endpoints are and how they fit together; where the two disagree, the contract
wins.

## Authentication

### Admin

Dashboard and API requests use:

```http
Authorization: Bearer <admin token>
```

In server mode that token is `ADMIN_TOKEN`. In personal mode it is generated on
first run, stored in `$POCKETEXIT_HOME/state.json`, and printed in the startup
block. Comparison is constant time in both modes.

### Android agent

Every node ID has exactly one token. Agent requests use:

```http
Authorization: Bearer <node token>
```

The node ID is also present in the request body or query. The backend verifies
that the token belongs to that exact node ID, and that a circuit belongs to
that node. In server mode tokens come from `AGENT_TOKENS_JSON` and are
immutable at runtime. In personal mode they are minted by a pairing claim and
persisted in `state.json`.

### SOCKS5

The server requires RFC 1929 username/password authentication. The configured
base username can carry selectors:

```text
BASE[@NODE_ID][!POLICY]
```

The password is always the configured SOCKS password.

## Pairing endpoints — personal mode only

These routes are mounted only when the process runs as `pocketexit personal`.
Server mode answers `404`.

### Claim

```http
POST /pair/v1/claim
Content-Type: application/json
```

The only unauthenticated write endpoint in the system. It is guarded by the
pairing code, a per-code attempt counter, and a per-address rate limiter that
reads the address from the connection rather than any forwarding header.

```json
{"code": "A1B2-C3D4", "device_name": "Pixel 8", "node_id": "pixel-8"}
```

`node_id` is optional. Whatever the client sends is sanitised to
`[A-Za-z0-9._-]{1,64}`; when it is missing or already taken the server derives
one from `device_name` plus a random suffix.

```json
{
  "node_id": "pixel-8-629b2c2d",
  "agent_token": "example-agent-token-not-a-real-credential-0",
  "server_url": "https://192.168.1.50:8443",
  "socks": {"host": "127.0.0.1", "port": 1080, "username": "proxy"}
}
```

The `socks` block is a display hint for the phone's "you're paired" screen. It
carries no password.

| Status | Condition |
|---|---|
| `200` | Code valid, node registered, token minted |
| `400` | Malformed body, or `device_name` missing or too long |
| `401` | No active code, code expired, or code mismatch |
| `429` | More than 10 claim attempts from one address in 10 minutes |

`401` deliberately does not distinguish an expired code from a wrong one.

### Pairing code lifecycle

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/pairing` | Current code, or `active: false` |
| `POST` | `/api/v1/pairing` | Mint a code, replacing any active one |
| `DELETE` | `/api/v1/pairing` | Cancel the active code, `204` |

`GET` and `POST` share one response shape:

```json
{
  "active": true,
  "code": "B3NJ-B6GD",
  "expires_at": "2026-09-09T19:59:55Z",
  "uri": "pocketexit://configure?fp=…&name=laptop&pair=B3NJB6GD&server=https%3A%2F%2F192.168.1.50%3A8443&v=2",
  "qr_svg": "<svg …>",
  "fingerprint": "3aCuAD5-R6Hm9iPc-PsJyTW4h4ZLsjvI78yCc5qNs-Q",
  "server_url": "https://192.168.1.50:8443"
}
```

When no code is active, `active` is `false` and `code`, `uri`, and `qr_svg` are
empty. `fingerprint` and `server_url` are always present, so the dashboard can
show the current pin whether or not a code is outstanding.

Codes are 8 Crockford base32 characters, rendered in groups of four for reading
aloud; separators and case are ignored on submission. One code is active at a
time, it lives 10 minutes, it is consumed by a successful claim, and it burns
after five failed attempts. Codes are held in memory only, so restarting the
server invalidates an outstanding one.

### Onboarding URI

```text
pocketexit://configure?v=2&server=…&pair=…&fp=…&name=…
```

| Field | v1 | v2 | Meaning |
|---|---|---|---|
| `v` | `1` | `2` | Version, exact match |
| `server` | required | required | `https://` origin, no path, query, fragment, or userinfo |
| `node` | required | absent | v1 only; v2 lets the server assign the node id |
| `token` | required | absent | v1 only; v2 obtains the token by claiming |
| `pair` | absent | required | Pairing code |
| `fp` | absent | optional | Certificate pin; absent means use the platform trust store |
| `name` | absent | optional | Human label for the server, display only, max 64 characters |

Both versions reject duplicate keys, unknown keys, and a URI carrying a path,
fragment, or userinfo. Version 1 is still accepted so existing server-mode
deployments keep working; it is what `GET /api/v1/nodes/{nodeID}/onboarding`
returns in server mode.

`fp` is optional in v2 so a personal-mode server placed behind a real
certificate — Tailscale, a reverse proxy, a LAN CA — can pair without pinning.
When `fp` is present the agent supplies its own trust manager, trust is decided
only by the pin, the chain is not walked, and hostname verification is off
because the pin already binds the connection to one key.

## Agent endpoints

### Heartbeat

```http
POST /agent/v1/heartbeat
Content-Type: application/json
```

Representative body:

```json
{
  "node_id": "s24u",
  "device_name": "S24 Ultra",
  "app_version": "0.4.1",
  "control_policy": "WIFI_PREFERRED",
  "exit_policy": "CELLULAR_ONLY",
  "active_control_network": "WIFI",
  "transport_protocol": "h3",
  "wifi": {
    "available": true,
    "validated": true,
    "metered": false,
    "interface_name": "wlan0",
    "addresses": ["192.168.1.44"],
    "dns_servers": ["192.168.1.1"],
    "mtu": 1500,
    "down_kbps": 866000,
    "up_kbps": 433000
  },
  "cellular": {
    "available": true,
    "validated": true,
    "metered": true,
    "interface_name": "rmnet_data0"
  },
  "battery_percent": 81,
  "charging": true,
  "active_circuits": 2,
  "bytes_up": 12345,
  "bytes_down": 67890
}
```

### Control long poll

```http
GET /agent/v1/control?node_id=s24u
```

No command returns `204 No Content` after `COMMAND_WAIT`. A node that has not
sent a heartbeat yet gets `409`. Commands are JSON objects.

Open TCP:

```json
{
  "type": "open_tcp",
  "circuit_id": "0123456789abcdef0123456789abcdef",
  "target_host": "example.com",
  "target_port": 443,
  "exit_policy": "CELLULAR_ONLY",
  "allow_private": false
}
```

Open UDP uses `"type": "open_udp"`. The other commands are:

```json
{"type":"close","circuit_id":"..."}
```

```json
{
  "type": "policy_update",
  "control_policy": "WIFI_PREFERRED",
  "exit_policy": "CELLULAR_ONLY"
}
```

### Circuit status

```http
POST /agent/v1/circuits/{circuitID}/status
```

```json
{
  "node_id": "s24u",
  "status": "connected",
  "error": ""
}
```

Valid states sent by the agent are `connected`, `failed`, and `closed`. A
`connected` status releases the waiting SOCKS handshake.

### Circuit WebSocket

```http
GET /agent/v1/circuits/{circuitID}/ws?node_id=s24u
Sec-WebSocket-Protocol: pocketexit.circuit.v1
```

The subprotocol is required; an upgrade that does not negotiate it is closed
with a policy violation. After an authenticated upgrade each binary message
carries circuit bytes. The connection is full duplex: server-to-phone bytes
come from the SOCKS client and phone-to-server bytes come from the destination
socket. Text messages are rejected. The connection closes when either side ends
the circuit or its combined byte quota is exhausted.

For TCP, each message contains raw stream bytes; message boundaries have no TCP
semantics. For UDP, the byte stream carries repeated frames:

```text
0                   1                   2
0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
+-------------------------------+
|       payload length          |  uint16, network byte order
+-------------------------------+
|       datagram payload ...    |
+-------------------------------+
```

Maximum payload length is 65,507 bytes.

The v0.3.0 `GET .../down` and `POST .../up` streaming endpoints remain
available for rolling upgrades, but current agents use the WebSocket endpoint.

## Admin endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/health` | Unauthenticated liveness check |
| `GET` | `/api/v1/nodes` | Node inventory |
| `PATCH` | `/api/v1/nodes/{nodeID}` | Enable/disable and update policies |
| `DELETE` | `/api/v1/nodes/{nodeID}` | Unpair a phone |
| `GET` | `/api/v1/nodes/{nodeID}/onboarding` | Onboarding URI and inline QR SVG |
| `GET` | `/api/v1/circuits` | Circuit inventory |
| `DELETE` | `/api/v1/circuits/{circuitID}` | Close a circuit |
| `GET` | `/api/v1/metrics` | Prometheus text metrics |
| `GET` `POST` `DELETE` | `/api/v1/pairing` | Pairing code, personal mode only |

Policy patch example:

```json
{
  "enabled": true,
  "control_policy": "WIFI_PREFERRED",
  "exit_policy": "CELLULAR_ONLY"
}
```

A policy patch is transactional: if the node's command queue is full, the
dashboard state is rolled back rather than drifting from the phone.

`DELETE /api/v1/nodes/{nodeID}` revokes the node's token, disables it, and
closes its circuits. It answers `204` on success and `404` when the node is
unknown. In server mode tokens come from `AGENT_TOKENS_JSON` and cannot be
revoked at runtime, so it answers `409`.

`GET /api/v1/nodes/{nodeID}/onboarding` returns a `v=1` URI carrying the node's
token in server mode. In personal mode there is no token to embed, so it
returns the current `v=2` pairing URI and answers `409` when no code is active.

In personal mode `/api/v1/nodes` and `/api/v1/metrics` hide phones whose token
has been revoked, so an unpaired node does not linger on the dashboard behind a
stale heartbeat record.

## QUIC behaviour

Cronet enables QUIC and receives an nginx `Alt-Svc` advertisement for `h3`. The
negotiated protocol is reported in heartbeat telemetry. HTTP/3 is
opportunistic: a path that blocks UDP 443 may temporarily use HTTPS over TCP. A
strict deployment should monitor `transport_protocol` and allow UDP 443 through
every intervening firewall. Personal mode terminates TLS in the Go process over
TCP only, so `transport_protocol` there reports HTTP/1.1 or HTTP/2.

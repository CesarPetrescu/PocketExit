# Protocol

## Authentication

### Admin

Dashboard/API requests use:

```http
Authorization: Bearer <ADMIN_TOKEN>
```

### Android agent

Every node ID has one token in `AGENT_TOKENS_JSON`. Agent requests use:

```http
Authorization: Bearer <node-token>
```

The node ID is also present in the request body or query. The backend verifies that the token belongs to that exact node ID and that a circuit belongs to that node.

### SOCKS5

The server requires RFC 1929 username/password authentication. The configured base username can include selectors:

```text
BASE[@NODE_ID][!POLICY]
```

The password is always `SOCKS_PASSWORD`.

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
  "app_version": "0.1.0",
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

No command returns `204 No Content`. Commands are JSON objects.

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

Open UDP uses `"type": "open_udp"`. Other commands are:

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

Valid states sent by the agent are `connected`, `failed`, and `closed`. A `connected` status releases the waiting SOCKS handshake.

### Circuit WebSocket

```http
GET /agent/v1/circuits/{circuitID}/ws?node_id=s24u
Sec-WebSocket-Protocol: pocketexit.circuit.v1
```

After an authenticated upgrade, each binary message carries circuit bytes. The
connection is full duplex: server-to-phone bytes come from the SOCKS client and
phone-to-server bytes come from the destination socket. Text messages are
rejected. The connection closes when either side ends the circuit or its
combined byte quota is exhausted.

For TCP, each message contains raw stream bytes. Message boundaries have no TCP
semantics. For UDP, the byte stream contains repeated frames:

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

The v0.3.0 `GET .../down` and `POST .../up` streaming endpoints remain available
for rolling upgrades, but new agents use the WebSocket endpoint.

## Admin endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/health` | Unauthenticated liveness check |
| GET | `/api/v1/nodes` | Node inventory |
| PATCH | `/api/v1/nodes/{nodeID}` | Enable/disable and update policies |
| GET | `/api/v1/nodes/{nodeID}/onboarding` | Pairing URI and inline QR SVG |
| GET | `/api/v1/circuits` | Circuit inventory |
| DELETE | `/api/v1/circuits/{circuitID}` | Close a circuit |
| GET | `/api/v1/metrics` | Prometheus text metrics |

Policy patch example:

```json
{
  "enabled": true,
  "control_policy": "WIFI_PREFERRED",
  "exit_policy": "CELLULAR_ONLY"
}
```

## QUIC behavior

Cronet enables QUIC and receives an Nginx `Alt-Svc` advertisement for `h3`. The negotiated protocol is reported in heartbeat telemetry. HTTP/3 is opportunistic: a path that blocks UDP 443 may temporarily use HTTPS over TCP. A strict deployment should monitor `transport_protocol` and allow UDP 443 through every intervening firewall.

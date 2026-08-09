# PocketExit

PocketExit turns Android phones you own into selectable private Internet exit nodes. The phones maintain an outbound control/data connection to one public Nginx gateway. A Go backend exposes an authenticated SOCKS5 proxy, selects a phone, and asks the Android agent to open the destination through Wi-Fi or cellular data.

The Android implementation uses ordinary public Android APIs only:

- no root;
- no ADB dependency;
- no `VpnService`;
- no process-wide route changes;
- no custom ROM or device-owner mode.

## Architecture

```text
SOCKS5 client
     │ TCP :1080 / UDP relay :12000-12031
     ▼
┌──────────────── public Nginx container ────────────────┐
│  HTTPS + HTTP/2 + HTTP/3/QUIC :443                    │
│  static dashboard                                     │
│  API/agent reverse proxy                              │
│  SOCKS TCP/UDP stream proxy                           │
└───────────────────────┬────────────────────────────────┘
                        │ private Docker network
                        ▼
                 ┌───────────────┐
                 │ Go backend    │
                 │ node registry │
                 │ circuit mgr   │
                 │ SOCKS5        │
                 └───────┬───────┘
                         │ commands + per-circuit streams
                         ▼
                 ┌─────────────────┐
                 │ Android agent   │
                 │ control: Wi-Fi  │
                 │ exit: LTE / 5G  │
                 └────────┬────────┘
                          ▼
                       Internet
```

Only `nginx` publishes host ports in `docker-compose.yml`. The Go process is reachable only on the internal Compose network.

For every phone, the control route and exit route are independent. Typical operation is:

```text
Android ↔ gateway control/data transport: Wi-Fi preferred
Android → destination socket:             cellular only
```

If Wi-Fi disappears, the control connection can reconnect through cellular while destination sockets remain governed by their own exit policy.

## Implemented features

### Android agent

- Kotlin and Jetpack Compose.
- Foreground service with a persistent notification.
- Simultaneous Wi-Fi and cellular `Network` tracking.
- `AUTO`, `WIFI_ONLY`, `CELLULAR_ONLY`, `WIFI_PREFERRED`, and `CELLULAR_PREFERRED` policies.
- Per-request Cronet binding to the selected control `Network`.
- HTTP/3/QUIC support through embedded Cronet.
- Destination DNS and TCP/UDP sockets bound to the selected exit `Network`.
- TCP circuits and connected UDP circuits.
- Android Keystore encryption for the agent token.
- Optional start after reboot.
- Network, traffic, battery, route, circuit, and negotiated-protocol telemetry.

### Go backend

- Authenticated SOCKS5 CONNECT and UDP ASSOCIATE.
- Automatic or explicit phone selection.
- Per-request exit-policy override in the SOCKS username.
- Authenticated node registration, long polling, and circuit streams.
- In-memory node registry and circuit manager.
- Private/local destination blocking by default.
- Admin API, health endpoint, and Prometheus-text metrics.
- Graceful shutdown, timeouts, connection limits, and stale-circuit pruning.

### Gateway and dashboard

- One Nginx container for all public ingress.
- TLS, HTTP/2, and HTTP/3/QUIC on port 443.
- Static dependency-free administration dashboard.
- TCP stream proxy for SOCKS5.
- Dedicated UDP relay port pool.
- Read-only containers and `no-new-privileges` in Compose.

## Repository layout

```text
android/             Android Studio / Gradle project
backend/             Go control plane and SOCKS5 proxy
frontend/            Static dashboard
nginx/               Nginx image, configuration, and local certificates
scripts/             Setup, validation, smoke-test, and packaging scripts
docker-compose.yml   Complete server deployment
ARCHITECTURE.md       Component and data-flow design
PROTOCOL.md           HTTP and circuit protocol
SECURITY.md           Threat model and deployment checklist
TEST-REPORT.md        Verification performed for this handoff
```

## Server quick start

### Prerequisites

- Docker Engine with the Compose plugin;
- OpenSSL;
- a DNS name pointing to the server for normal deployment;
- TCP 80, TCP/UDP 443, TCP 1080, and UDP 12000–12031 allowed through the firewall.

Generate random credentials and development certificates:

```bash
DOMAIN=proxy.example.com make setup
```

Review the generated `.env`:

```bash
chmod 600 .env
```

Start the services:

```bash
docker compose up --build -d
```

Validate them:

```bash
curl -k https://127.0.0.1/api/v1/health
docker compose exec nginx nginx -t
docker compose ps
```

The `-k` option is only for the generated development certificate. Do not use it as a production trust strategy.

### Production TLS

For production, replace:

```text
nginx/certs/server.crt
nginx/certs/server.key
```

with a certificate and key trusted by Android's system trust store. The Nginx container deliberately does not automate ACME issuance. Certificate renewal can therefore be integrated with the certificate workflow already used on the server without changing PocketExit.

The release Android build trusts system certificate authorities only. The debug build additionally permits user-installed certificate authorities for local testing.

## Build and install the Android application

Use Android Studio or JDK 17 plus an Android SDK containing API 36.

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is produced at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

To install without ADB, copy the APK to each phone using USB file transfer, cloud storage, a browser download, or another normal file-transfer method. Open the APK on the phone and approve installation from that source.

For development certificates, also copy `nginx/certs/ca.crt` to the phone and install it as a user CA through Android Settings. Use only the debug APK with this CA. A production/release APK should use a publicly trusted server certificate instead.

### Configure the three phones

The generated `.env` contains entries similar to:

```text
AGENT_TOKENS_JSON={"s20u":"...","s22u":"...","s24u":"..."}
```

On each phone enter:

| Field | Example |
|---|---|
| Backend URL | `https://proxy.example.com` |
| Node ID | `s20u`, `s22u`, or `s24u` |
| Agent token | Matching value from `AGENT_TOKENS_JSON` |
| Control tunnel | `Wi-Fi preferred` |
| Proxy exit | `Cellular only` or `Cellular preferred` |

`CELLULAR_ONLY` fails the circuit rather than silently leaking it through Wi-Fi. `CELLULAR_PREFERRED` falls back to Wi-Fi when cellular is unavailable.

Android can request the cellular transport but cannot force the radio to stay specifically on 5G NR. The carrier/modem may use LTE when 5G is unavailable.

## Use the proxy

The public `:1080` listener is standard SOCKS5 over raw TCP. SOCKS5 does not encrypt its username/password exchange or non-TLS destination traffic. Restrict TCP 1080 to trusted source addresses, access it through a VPN/SSH tunnel, or place a client-side TLS wrapper in front of it. HTTPS destinations still provide their own end-to-end TLS, but the SOCKS credentials themselves otherwise remain exposed to the network path.

Default automatic node selection:

```bash
curl --proxy socks5h://proxy.example.com:1080 \
  --proxy-user 'proxy:YOUR_SOCKS_PASSWORD' \
  https://api.ipify.org
```

Select a specific phone:

```bash
curl --proxy socks5h://proxy.example.com:1080 \
  --proxy-user 'proxy@s24u:YOUR_SOCKS_PASSWORD' \
  https://api.ipify.org
```

Select a phone and force cellular for that circuit:

```bash
curl --proxy socks5h://proxy.example.com:1080 \
  --proxy-user 'proxy@s24u!cellular:YOUR_SOCKS_PASSWORD' \
  https://api.ipify.org
```

Supported username forms:

```text
proxy
proxy@NODE_ID
proxy!POLICY
proxy@NODE_ID!POLICY
```

Policy aliases:

```text
auto
wifi
cellular, cell, lte, 5g
wifi_preferred
cellular_preferred
```

The `5g` alias means `CELLULAR_ONLY`; it does not guarantee that the modem is currently using NR rather than LTE.

A SOCKS5 client that implements UDP ASSOCIATE can use UDP. The server allocates one authenticated relay port from 12000–12031 for each association.

## Dashboard

Open:

```text
https://proxy.example.com/
```

Enter `ADMIN_TOKEN` from `.env`. The browser retains it only in `sessionStorage`, so it is cleared when that browser session ends.

The dashboard shows:

- online/offline and selectable state;
- Wi-Fi and cellular validation, addresses, interface, MTU, metering, and estimated link rates;
- active control route and negotiated HTTP protocol;
- battery/charging status;
- active circuits and traffic counters;
- remote control and exit-policy selection;
- circuit termination.

## Tests

Core tests that do not require Docker or an Android SDK:

```bash
make test
```

Android build, unit tests, and lint:

```bash
make test-android
```

Compose build, startup, health check, and Nginx configuration test:

```bash
make test-docker
```

The GitHub Actions workflow runs all three groups on a fully provisioned runner.

## Operational limitations

- State is in memory. Restarting the Go backend clears node records and circuit history; phones re-register automatically.
- TCP and UDP circuits are not resumed after an Android control-network change. They close, while the agent reconnects and accepts new circuits.
- UDP payloads are length-framed over reliable HTTP/3 request streams. This preserves order and delivery but is not equivalent to QUIC DATAGRAM/MASQUE and can add latency during packet loss.
- SOCKS5 UDP relay datagrams do not carry reusable credentials. Each relay port is created only after authenticated `UDP ASSOCIATE` and locks to its first observed Nginx upstream session, but the fixed public port pool remains susceptible to a first-packet race. Keep UDP 12000–12031 firewalled to trusted clients or a VPN, and do not publish the range when UDP is unnecessary.
- The Android foreground service improves survivability, but some OEM battery-management policies may still stop it. Exempting the app from battery optimization can be done manually in device settings.
- The project does not implement multi-user tenancy, billing, persistent audit storage, mTLS, or automatic certificate issuance.
- Carrier terms and local laws may restrict proxying or sustained tethering-like traffic. Use only devices, SIMs, accounts, and services you control and are authorized to operate.

See [SECURITY.md](SECURITY.md) before exposing the service to the Internet.

# Test report

Date: 2026-08-10

## Scope

This report covers the source package delivered for PocketExit: Go backend, static frontend, Android source, Nginx/Compose configuration, setup scripts, and packaging checks.

## Locally executed

The following checks were executed in the delivery environment:

- `go test ./...`
- `go vet ./...`
- `go test -race ./...`
- full authenticated SOCKS5 TCP circuit test with a simulated Android agent;
- two independent 8.4 MB full-duplex transfers over authenticated circuit
  WebSockets, including circuit close and reconnect;
- full SOCKS5 UDP ASSOCIATE round trip with a simulated Android agent;
- backend process-level HTTP/control-plane smoke test;
- JavaScript syntax validation with `node --check`;
- Compose YAML parsing and topology assertions;
- Android Gradle unit tests, lint, debug APK compilation, and unsigned release
  compilation in the repository's Android builder image;
- API 36 emulator installation, notification permission, UI launch, network
  detection, foreground service startup, public heartbeat registration, and
  deep-link QR onboarding confirmation/import without displaying the token;
- Docker image construction and live Compose health checks;
- public SparkTunnel dashboard, health, authenticated heartbeat, and admin
  readback checks at `https://exit.photonspark.ro`;
- physical Samsung SM-G988B, SM-S908B, and SM-S928B registration, independent
  Wi-Fi/cellular validation, and short Hetzner range downloads through each node;
- the reusable `make test-live` suite: public health, HTTP, HTTPS/JSON, POST,
  Git smart-HTTP, WSS upgrade, forced-cellular HTTPS, cross-node download hash,
  selector rejection, private-destination blocking, and circuit cleanup;
- Hetzner 100 MB and 1 GB transfer probes through that physical phone, both of
  which reproduced SparkTunnel's sustained-stream cutoff after 16–17 seconds;
- Android XML parsing;
- shell syntax validation;
- source-policy scan for `VpnService`, process-wide binding, and root-shell use;
- standalone Kotlin compile/run smoke checks for UDP framing, route-policy selection, and destination ACLs;
- ZIP secret/excluded-artifact inspection.

Combined Go statement coverage across repeated final runs was **56.8%–58.9%**; the latest clean, extracted ZIP verification run reported **58.9%**. The end-to-end SOCKS5 TCP and UDP tests and the HTTP control-plane test were also rerun individually under the Go race detector with caching disabled.

## Tests included in source

### Go

- configuration parsing and invalid-value rejection;
- destination ACLs, including IPv4-mapped IPv6 bypass attempts;
- UDP framing;
- circuit lifecycle and concurrent stream behavior;
- node registration, scheduling, policy updates, and command-queue rollback;
- HTTP authentication and endpoint behavior;
- SOCKS5 authentication, selectors, TCP CONNECT, and UDP ASSOCIATE;
- end-to-end simulated-agent TCP and UDP data paths.

### Android JVM tests

- network policy selection;
- UDP frame fragmentation/coalescing/size handling;
- destination ACL behavior, including mapped IPv6;
- agent configuration validation.
- onboarding URI validation, including rejection of unsafe or incomplete links;
- route-policy state changes used to simulate roaming/network validation loss.

### CI-only integration

The included GitHub Actions workflow additionally performs:

- Android Gradle unit tests;
- Android lint;
- debug APK compilation;
- Docker Compose validation and image builds;
- live Compose startup;
- Nginx `nginx -t` inside the exact image;
- HTTPS health check through the Nginx gateway.

## Physical-phone result

All three phones registered on app v0.3.0 with validated Wi-Fi and cellular:
SM-G988B (`wlan0`/`rmnet1`), SM-S908B (`wlan0`/`rmnet0`), and SM-S928B
(`wlan0`/`rmnet_data0`). Short authenticated Hetzner range downloads completed
through each selected node. A SOCKS request explicitly selecting
`s20u!cellular` also returned the phone's cellular public IPv4 address. The
address, credentials, circuit IDs, and local network details are intentionally
excluded from this repository.

### Live multi-phone suite

`make test-live` completed **25/25 checks** against the live deployment. Each
of `s20u`, `s22u`, and `s24u` passed HTTPS with remote DNS, plain HTTP, an HTTPS
POST round trip, Git smart-HTTP discovery, a WSS `101 Switching Protocols`
upgrade, and a forced-cellular HTTPS request whose public address was not
printed. Each phone returned the same SHA-256 for an exact 256 KiB range of
Hetzner's `100MB.bin`. The suite also confirmed that an unknown node selector
and a loopback destination are rejected, then verified all three nodes remained
online with no open or pending circuits.

Short requests succeeded. The 100 MB probe received 1,982,208 bytes in 17.16
seconds and the 1 GB probe received 474,878 bytes in 17.11 seconds before the
SparkTunnel HTTP streams closed with TLS EOF. No test payload was retained.

## Historical physical-phone limitation

The physical-phone results above used PocketExit v0.3.0. Its paired streaming
HTTP requests closed through SparkTunnel after roughly 16–17 seconds. v0.4.0
replaces that data plane with one full-duplex WebSocket per circuit; the
simulated-phone test completed two independent 8.4 MB transfers and reconnects
through the new implementation. A fresh sustained physical-phone run remains
necessary before assigning a carrier/OEM endurance result to v0.4.0.

Wi-Fi-to-cellular control reconnection, sustained direct-ingress throughput,
OEM background-process behavior, and carrier-specific NAT/IPv6 conditions have
not yet been measured on the physical phone.

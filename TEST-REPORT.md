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
- full SOCKS5 UDP ASSOCIATE round trip with a simulated Android agent;
- backend process-level HTTP/control-plane smoke test;
- JavaScript syntax validation with `node --check`;
- Compose YAML parsing and topology assertions;
- Android Gradle unit tests, lint, and debug APK compilation;
- API 36 emulator installation, notification permission, UI launch, network
  detection, foreground service startup, and public heartbeat registration;
- Docker image construction and live Compose health checks;
- public SparkTunnel dashboard, health, authenticated heartbeat, and admin
  readback checks at `https://exit.photonspark.ro`;
- physical Samsung SM-G988B registration over Wi-Fi, independent cellular
  validation, and a forced `s20u!cellular` public-IP request;
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

The Samsung SM-G988B reported validated Wi-Fi (`wlan0`) and cellular (`rmnet1`)
networks. A SOCKS request explicitly selecting `s20u!cellular` returned the
phone's cellular public IPv4 address. The address, credentials, circuit IDs,
and local network details are intentionally excluded from this repository.

Short requests succeeded. The 100 MB probe received 1,982,208 bytes in 17.16
seconds and the 1 GB probe received 474,878 bytes in 17.11 seconds before the
SparkTunnel HTTP streams closed with TLS EOF. No test payload was retained.

## Environment limitations

SparkTunnel 0.3.0 successfully carries the dashboard, API, heartbeat, control,
and short circuit requests, but its HTTP path still closes sustained PocketExit
circuit body streams after roughly 16–17 seconds. Direct Nginx exposure remains
required for TCP/UDP circuits until SparkTunnel supports these long-lived
streams or the agent data protocol moves to a supported transport such as
WebSockets.

Wi-Fi-to-cellular control reconnection, sustained direct-ingress throughput,
OEM background-process behavior, and carrier-specific NAT/IPv6 conditions have
not yet been measured on the physical phone.

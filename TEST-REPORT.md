# Test report

Date: 2026-08-09

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

## Environment limitations

The delivery environment did not contain Docker Engine, Gradle, or an Android SDK. Consequently, Docker image construction and Android APK compilation could not be executed locally. They are represented by reproducible source, verified wrapper checksums, static validation, and CI jobs, but remain to be run on a machine with those toolchains.

No physical-phone LTE/5G throughput or handover test was possible in this environment. Real-device validation should cover Wi-Fi-to-cellular control reconnection, cellular-only public IP confirmation, OEM background-process behavior, and carrier-specific NAT/IPv6 conditions.

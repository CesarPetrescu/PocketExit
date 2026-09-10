# Test report

**Last updated: 2026-09-10.** This file has two halves. The first is current:
what the automated suites check, and what was actually run on that date. The
second is an archive of a 2026-08-10 hardware session against PocketExit
v0.3.0, kept because it is the only physical-phone evidence that exists — it is
not a statement about the current tree.

Nothing below is a throughput benchmark. Where a number is a duration or a byte
count, it comes from a functional check that happened to be timed.

## Current status

| Area | Verified | Where |
|---|---|---|
| Go unit, integration, and race tests | Yes, every push | CI job *Go tests* |
| Go statement coverage | 65.1% at `aeccaad`, floor 62.0% | CI job *Go tests* |
| Server-mode process smoke test | Yes, every push | `scripts/smoke-backend.sh` |
| Personal-mode pairing smoke test | Yes, every push | `.github/e2e/personal-smoke.sh` |
| SOCKS5 TCP and UDP end to end | Yes, every push | `.github/e2e/socks-e2e.py` |
| Android unit tests, lint, debug APK | Yes, every push touching `android/` | CI job *Android* |
| Android screens driven and asserted | Yes, every push touching `android/` | CI job *Android* |
| Dashboard logic | Yes, every push | CI job *Source, YAML and frontend checks* |
| Compose stack build and image scan | Yes, every push touching deployment files | CI job *Compose gateway* |
| Real traffic through nginx: SOCKS5 TCP, TLS-wrapped SOCKS5, UDP, agent WebSockets | Yes, every push touching deployment files | `.github/e2e/system-test.py` |
| Personal mode on a physical phone | **No** | — |
| Sustained transfer on a physical phone | **No, not since v0.3.0** | see the archive |

Personal mode is newer than the `0.4.1` version string in the tree; it has not
been part of a tagged release, and it has not been exercised against a physical
Android device. Everything asserted about it below was measured against the
real backend process with a scripted client standing in for the phone.

## Run on 2026-09-09

Executed in a Linux container with Go 1.24.7, against commit `aeccaad`
(`claude/repo-ci-gha-audit-ijcwvf`), which is `HEAD` as this file is written.
Every figure below was re-measured at that commit. An earlier revision of this
section quoted `830b229`, which is six commits behind: neither end-to-end script
it listed existed there, and the `nodes` registry and `personal` certificate
tests have grown since. Commands and their actual results:

```text
$ go test -race -covermode=atomic -coverprofile=coverage.out ./...
ok      .../backend/cmd/server            1.031s  coverage: 7.9% of statements
ok      .../backend/internal/circuit      1.026s  coverage: 67.6% of statements
ok      .../backend/internal/config       1.038s  coverage: 87.0% of statements
ok      .../backend/internal/httpapi      2.774s  coverage: 64.0% of statements
        .../backend/internal/model                coverage: 0.0% of statements
ok      .../backend/internal/nodes        1.033s  coverage: 73.6% of statements
ok      .../backend/internal/personal     2.793s  coverage: 82.5% of statements
ok      .../backend/internal/protocol     1.023s  coverage: 75.0% of statements
ok      .../backend/internal/proxy        1.039s  coverage: 70.2% of statements
ok      .../backend/internal/security     1.023s  coverage: 84.0% of statements

$ go tool cover -func=coverage.out | tail -1
total:                                  (statements)            65.1%

$ go vet ./...            # clean
$ gofmt -l .              # no output

$ ./scripts/smoke-backend.sh
Backend HTTP/control-plane smoke test passed

$ ./.github/e2e/personal-smoke.sh
certificate pin matches the SubjectPublicKeyInfo
Personal-mode pairing smoke test passed

$ python3 .github/e2e/socks-e2e.py
backend is healthy
simulated phone registered
  CONNECT circuit round-tripped 524288 bytes
  UDP ASSOCIATE circuit round-tripped 29 bytes via 127.0.0.1:24000
  CONNECT circuit round-tripped 524288 bytes
  backend recorded 3 circuits: ['tcp', 'udp']
SOCKS5 end-to-end test passed

$ node --check frontend/app.js          # clean
$ python3 scripts/check-compose.py
Compose topology checks passed
$ python3 scripts/verify-source.py
Source policy checks passed
```

The coverage floor in CI is 62.0%, set just under the observed `-race` range so
it ratchets upward rather than flapping. The 65.1% figure above is one
measurement of one commit, not a target.

### Personal mode, manually exercised the same day

Beyond the smoke script, the built binary was driven by hand to confirm the
contract's observable surface:

- `pocketexit version`, `pocketexit help`, and `pocketexit personal --help`
  print the documented commands and the five documented flags.
- A first run created the state directory at mode `0700`, with `tls.key` and
  `state.json` at `0600` and `tls.crt` at `0644`.
- The startup block printed the dashboard URL, admin token, SOCKS credentials,
  certificate pin, and a scannable QR, and the advertised origin matched the
  bound listener in both the wildcard and the pinned-interface cases.
- `GET /api/v1/health` answered over the process's own certificate with
  `--cacert`, and `GET /` served the dashboard with `200`.
- `GET /api/v1/pairing` returned `active`, the grouped code, `expires_at`, the
  `v=2` URI, an inline SVG, the fingerprint, and the server URL.
- `POST /pair/v1/claim` with a valid code returned a node id derived from the
  device name plus a random suffix, an agent token, the server URL, and the
  password-free SOCKS hint; `state.json` gained the paired node.
- The SOCKS listener rejected a wrong password (`User was rejected by the SOCKS5
  server`) and, with no phone attached, refused the CONNECT rather than routing
  it (`Can't complete SOCKS5 connection`).

The smoke script additionally proves, on every CI run, that a wrong code
answers `401` without consuming the right one, that a consumed code answers
`401`, that the printed pin equals the SHA-256 of the certificate's
SubjectPublicKeyInfo, that the minted token authenticates a heartbeat, that
`DELETE /api/v1/nodes/{id}` revokes it, and that the process exits cleanly on
`SIGTERM`.

### Not run on 2026-09-09

- **Android.** No Android SDK or Gradle plugin cache was reachable from the
  environment, so `./gradlew testDebugUnitTest` could not resolve AGP 8.13.2.
  CI runs the Android job in the pinned `android/Dockerfile` toolchain on every
  push that touches `android/`.
- **Docker Compose.** The `test-docker` and CI *Compose gateway* paths were not
  exercised here.
- **`make test-live`.** It requires the ignored `.env`, a running deployment,
  and online physical phones.

## Tests included in the source

### Go

- configuration parsing and invalid-value rejection, for both modes;
- personal-mode primitives: state directory permissions and persistence,
  certificate issue/renew/pin, pairing code lifetime, single use, attempt
  burn-through, and per-address rate limiting;
- destination ACLs, including IPv4-mapped IPv6 bypass attempts;
- UDP framing;
- circuit lifecycle and concurrent stream behaviour;
- node registration, scheduling, policy updates, and command-queue rollback;
- HTTP authentication and endpoint behaviour, including the pairing endpoints
  and the personal-mode-only routing;
- SOCKS5 authentication, selectors, TCP CONNECT, and UDP ASSOCIATE;
- end-to-end simulated-agent TCP and UDP data paths, including two independent
  8.4 MB full-duplex transfers over circuit WebSockets with a close and
  reconnect between them
  (`httpapi.TestSimulatedPhoneWebSocketLargeTransferAndReconnect`).

### Android JVM tests

- network policy selection, including route changes that simulate roaming and
  loss of network validation;
- UDP frame fragmentation, coalescing, and size handling;
- destination ACL behaviour, including mapped IPv6;
- agent configuration validation;
- onboarding URI parsing for both versions, including rejection of duplicate
  and unknown fields, links carrying a path or fragment, and an `fp` that is
  present but malformed or empty;
- pinned trust-manager behaviour.

### Android screens

Robolectric runs the Compose screens on the JVM, so they are covered by the
same `testDebugUnitTest` CI already runs — no emulator and no device. The
screens are pure functions of their arguments, and the tests drive them the way
a person would:

- the home screen renders each of the five connection states and offers the way
  out that belongs to it, and reports every button press back to its caller;
- the settings form hands every edit back rather than keeping it, masks the
  agent token until it is revealed, shows the pin in full, and asks before
  unpairing;
- the pairing sheet shows the origin, the server name and the pin before
  anything is claimed, refuses to be dismissed while a single-use code is in
  flight, keeps the server's own words alongside the mapped failure reason, and
  never fires a confirmation callback that was not pressed;
- the welcome screen explains the three steps and keeps the manual form
  reachable for a server-mode deployment.

### Dashboard

`frontend/lib.js` holds the dashboard's decisions — byte and rate formatting,
the age of a heartbeat, the pairing countdown, the traffic-counter deltas, and
the check that refuses to put anything but an SVG in the QR's `img` source.
`node --test frontend/lib.test.js` covers it with no build step and no
dependencies, the same deal the rest of the frontend gets.

### CI-only integration

- Android Gradle unit tests, lint, and debug APK compilation in the pinned
  toolchain image;
- Docker Compose validation, image builds, live startup, and a system test
  that drives the whole stack through nginx: TLS termination and the security
  headers, the 308 from port 80, the admin API, a simulated phone whose control
  poll and circuit WebSockets go through the gateway's Upgrade map, 512 KiB over
  the stream SOCKS5 listener on 1080, a round trip over the TLS-wrapped listener
  on 1081, and a UDP ASSOCIATE through the published 12000-12031 range and the
  `$server_port` map behind it. `nginx -t` only parses the stream block; this is
  the only thing that executes it;
- `govulncheck`, CodeQL, gitleaks, actionlint, zizmor, hadolint, shellcheck,
  yamllint, and eslint.

---

# Archive: 2026-08-10 hardware session, PocketExit v0.3.0

Everything in this section was measured on **2026-08-10** against **v0.3.0**,
on a Compose deployment reached through SparkTunnel. It is retained as
evidence, not as current status. The data plane it exercised — paired streaming
HTTP requests — was replaced in v0.4.0 by one WebSocket per circuit, precisely
because of the cutoff recorded below. None of these numbers have been
reproduced since.

## Physical devices

All three phones registered on app v0.3.0 with validated Wi-Fi and cellular:

| Node | Device | Interfaces | Result |
|---|---|---|---|
| `s20u` | Galaxy S20 Ultra (`SM-G988B`) | `wlan0` + `rmnet1` | Online; browsing, WSS and download passed |
| `s22u` | Galaxy S22 Ultra (`SM-S908B`) | `wlan0` + `rmnet0` | Online; browsing, WSS and download passed |
| `s24u` | Galaxy S24 Ultra (`SM-S928B`) | `wlan0` + `rmnet_data0` | Online; browsing, WSS and download passed |

A SOCKS request explicitly selecting `s20u!cellular` returned the phone's
cellular public IPv4 address. That address, the credentials, circuit IDs, and
local network details are deliberately excluded from this repository.

## Live multi-phone suite

`make test-live` completed **25/25 checks** against that deployment on
2026-08-10. Each of `s20u`, `s22u`, and `s24u` passed HTTPS with remote DNS,
plain HTTP, an HTTPS POST round trip, Git smart-HTTP discovery, a WSS
`101 Switching Protocols` upgrade, and a forced-cellular HTTPS request whose
public address was not printed. Each phone returned the same SHA-256 for an
exact 256 KiB range of Hetzner's `100MB.bin`. The suite also confirmed that an
unknown node selector and a loopback destination are rejected, then verified
all three nodes remained online with no open or pending circuits.

Concurrent 1 MiB downloads completed in 7.0 s on `s20u`, 2.6 s on `s22u`, and
10.3 s on `s24u`. Those times include connection, container, and circuit setup,
so they are health checks rather than throughput measurements.

## The v0.3.0 sustained-stream cutoff

| Probe through `s20u!cellular` | Result | Bytes received | Duration |
|---|---|---:|---:|
| `v4.ident.me` public-IP check | Passed | complete response | short request |
| Hetzner `100MB.bin` | Stream closed | 1,982,208 | 17.16 s |
| Hetzner `1GB.bin` | Stream closed | 474,878 | 17.11 s |

Both large probes received HTTP 200 before the paired-HTTP transport ended with
an unexpected EOF after roughly 17 seconds. This is what motivated the v0.4.0
circuit WebSocket. No test payload was retained.

## Other checks from that session

Also executed on 2026-08-10, against the delivered source package:

- API 36 emulator installation, notification permission, UI launch, network
  detection, foreground service startup, public heartbeat registration, and
  deep-link QR onboarding confirmation and import without displaying the token;
- public SparkTunnel dashboard, health, authenticated heartbeat, and admin
  readback checks at `https://exit.photonspark.ro`;
- Docker image construction and live Compose health checks;
- unsigned release compilation in the repository's Android builder image;
- Android XML parsing and shell syntax validation;
- a source-policy scan for `VpnService`, process-wide binding, and root-shell
  use;
- standalone Kotlin compile-and-run smoke checks for UDP framing, route-policy
  selection, and destination ACLs;
- ZIP secret and excluded-artifact inspection.

**Superseded number:** that session reported combined Go statement coverage of
56.8%–58.9%. It described a different tree and should not be compared with the
65.1% above.

## Still unmeasured on physical hardware

Carried forward from 2026-08-10 and still true:

- sustained transfer through the v0.4.x WebSocket data plane;
- Wi-Fi-to-cellular control reconnection;
- sustained direct-ingress throughput;
- OEM background-process behaviour over hours;
- carrier-specific NAT and IPv6 conditions;
- the whole of personal mode: pairing from a real camera, the pinned trust
  manager against a real Android release build, and proxying through a paired
  phone.

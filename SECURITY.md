# Security

PocketExit is a private proxy system. Exposing it without authentication, destination controls, and transport security would create an abuse path attached to your mobile subscriptions.

It runs in two modes whose security properties differ, and their instructions are not interchangeable:

- **Server mode** — a deployed backend behind nginx, agent tokens read once at boot from `AGENT_TOKENS_JSON`. Complete the [deployment checklist](#deployment-checklist--server-mode) before Internet deployment.
- **Personal mode** — `pocketexit personal` on your own machine, a self-signed certificate authenticated by a pin the phone reads off your screen, tokens minted at pairing time. See [Personal mode](#personal-mode). `docs/pairing-protocol.md` is the binding protocol contract; this file is the operational summary.

## Deployment checklist — server mode

Every item below is about a server-mode deployment. Personal mode has no `.env`, no nginx, and no static token map, and several of these items invert there — do not apply this list to it.

- Replace every placeholder in `.env` with cryptographically random values.
- Use a distinct agent token for every phone.
- Keep `.env`, private keys, and generated CA keys out of source control and backups that are not encrypted.
- Use a system-trusted production TLS certificate.
- Restrict the dashboard and SOCKS5 ports by source IP or VPN where practical.
- Keep UDP 12000–12031 closed unless SOCKS5 UDP is needed.
- Keep `ALLOW_PRIVATE_DESTINATIONS=false` unless private-network access is an explicit requirement.
- Monitor authentication failures, traffic volume, carrier usage, and unexpected destinations.
- Revoke a lost phone by removing its node token and restarting the backend.
- Rotate `ADMIN_TOKEN` and `SOCKS_PASSWORD` after suspected disclosure.

## Personal mode

Personal mode replaces that deployment with a single process on your own
machine. Three items in the checklist above invert here: the certificate is
deliberately self-signed rather than system-trusted, a lost phone is revoked
while the process keeps running instead of by editing a file and restarting,
and there is no `ADMIN_TOKEN` or `SOCKS_PASSWORD` in the environment to rotate
— both are generated on first run and live in `state.json`.

### The state directory is the whole secret

`$POCKETEXIT_HOME`, `~/.pocketexit` by default, is created with mode `0700` and
holds everything an attacker would need:

| File | Mode | Contents |
|---|---|---|
| `tls.key` | `0600` | The private key phones pin. Reused across a network move so paired phones keep connecting |
| `tls.crt` | `0644` | The self-signed leaf certificate |
| `state.json` | `0600` | Admin token, SOCKS password, and every paired phone's agent token |
| `audit.jsonl` | `0600` | The structured JSONL audit log, written here rather than at `AUDIT_LOG_PATH` |

The process refuses to start when that directory is readable by group or other,
and names the `chmod 700` that fixes it. Anyone who can read the directory can
impersonate your machine to every paired phone, drive the admin API, and use the
proxy. Back it up like a password file, or not at all. To rotate everything,
delete `state.json`: the next run generates a fresh admin token and SOCKS
password, and every phone has to pair again.

### Trust is a pin carried on your screen

The phone authenticates the server by SHA-256 over the certificate's
SubjectPublicKeyInfo — base64url, unpadded — carried out of band in the QR code
you scan. The chain is not walked, the platform trust store is not consulted,
and hostname verification is off, because the pin already binds the connection
to one specific key. Pinning the key rather than the whole certificate is what
lets the certificate be re-issued for a new address without breaking paired
phones; it also makes `tls.key` the single file whose disclosure lets a LAN
attacker impersonate the server to every phone paired with it.

Consistent with the threat model in `docs/pairing-protocol.md`, this defends
against:

- a passive LAN eavesdropper — the connection is TLS;
- an active LAN attacker impersonating the machine — without the pinned private
  key the handshake fails;
- a malicious `pocketexit://` link — pairing needs a live code, and the app
  shows the origin, the server name and the pin and stores nothing and contacts
  nobody until you confirm;
- brute force of the pairing code, covered below.

It does not defend against:

- **anyone who can see the screen** while a code is displayed: they can pair a
  phone. The 10-minute window and the single use are the whole defence;
- **a compromised machine**, which holds the private key, the admin token, the
  SOCKS password, and every paired phone's agent token. There is no second
  factor;
- **the carrier**, which sees the destinations of exit traffic as it always
  does.

### The pairing-code window

- Eight characters of Crockford base32 from `crypto/rand` with rejection
  sampling: 40 bits of entropy, compared in constant time.
- Valid for 10 minutes, single use, and consumed by five failed attempts.
- At most one code is active; minting a new one replaces the old. Codes are held
  in memory only, so restarting the process invalidates any outstanding one.
- `POST /pair/v1/claim` is the only unauthenticated write endpoint in the
  system. It is rate limited to 10 attempts per source address per 10 minutes,
  answering `429` beyond that.
- The HTTPS listener defaults to `0.0.0.0:8443`, so everything on your network
  can reach that endpoint while a code is live. Cancel a code you no longer need
  with the dashboard's cancel action (`DELETE /api/v1/pairing`) rather than
  waiting out the window.

### Revoking a phone

`DELETE /api/v1/nodes/{nodeID}`, with the admin token, revokes that phone's
agent token, removes it from the registry, closes its circuits, and rewrites
`state.json`. No restart, and the phone cannot register again without a new
pairing code. The dashboard's **Unpair** action on a node calls it.

In server mode the same endpoint answers `409`: `AGENT_TOKENS_JSON` is read once
at boot, so revocation there really does mean editing the file and restarting,
as the checklist says.

### Two more personal-mode defaults worth knowing

- SOCKS binds to `127.0.0.1:1080` because the machine running the binary is the
  only client. `--socks-addr` can move it, and the process logs a warning when
  the result is not a loopback address: publishing it puts an authenticated open
  proxy on your local network.
- The dashboard is served by the process itself over that self-signed
  certificate, so the browser warns. That warning is expected — the pin, not the
  browser's chain check, is what protects the phone.

## Implemented controls

### Authentication

- Admin API: bearer token compared in constant time.
- Android agents: exact node-ID-to-token mapping.
- SOCKS5: mandatory username/password authentication.
- UDP relay: allocated only after authenticated TCP UDP ASSOCIATE and locked to the first observed upstream session.

### Network isolation (server mode)

Only Nginx publishes host ports. The backend uses an internal Compose network, a read-only root filesystem, no Linux capabilities, and `no-new-privileges`.

### SOCKS client transport

The `:1080` endpoint is conventional SOCKS5 over raw TCP. Use it only through a
trusted private network, VPN, or SSH tunnel. Port `:1081` exposes the same SOCKS
service inside TLS for clients using a local wrapper such as `stunnel` or
`gost`. Standard SOCKS clients do not automatically add this outer TLS layer.

### Resource and audit controls

Every circuit has a shared upload/download byte ceiling configured with
`MAX_BYTES_PER_CIRCUIT`. Authentication outcomes, circuit lifecycle events,
node changes, and administrative closes are written as structured JSON Lines to
the persistent `AUDIT_LOG_PATH`. This log is durable across container restarts,
but it is not tamper-evident and should be exported to protected storage when
that property is required.

### Destination filtering

By default, both the backend and Android agent reject:

- loopback and unspecified addresses;
- RFC 1918 private IPv4;
- carrier-grade NAT ranges;
- link-local addresses;
- multicast/reserved ranges;
- documentation/test networks;
- IPv6 unique-local/link-local/multicast/documentation ranges;
- IPv4-mapped IPv6 forms of blocked IPv4 addresses;
- `localhost` names.

The Android agent resolves DNS through the selected exit network and filters all returned addresses before connecting. This limits DNS rebinding into private ranges.

### Android secret storage

The agent token is encrypted using an AES-GCM key generated in Android Keystore. Shared preferences and the token are excluded from Android backup/device transfer.

### Browser token handling

The dashboard stores the admin token in `sessionStorage`, not `localStorage`. It is still available to JavaScript in that origin, so the gateway must not host third-party scripts. The bundled dashboard has no external dependencies and Nginx sends a restrictive Content Security Policy.

## Development certificate warning — server mode

Personal mode never runs this script and never creates a CA: it issues its own self-signed leaf certificate and the phone authenticates it by pin.

`scripts/gen-dev-certs.sh` creates a local CA and server key. The CA private key is highly sensitive because it can issue certificates trusted by any device on which that CA is installed.

- Never ship `ca.key` to a phone.
- Install only `ca.crt` on an owned test phone.
- Remove the test CA from the phone after testing.
- Do not use the generated CA for public production service.
- Do not put any generated certificate/private-key files in the ZIP or repository.

## Known security limitations

This deployment does not provide:

- mTLS or hardware-backed remote attestation;
- account-based multi-tenancy or role-based access control;
- per-user bandwidth quotas;
- automatic secret rotation;
- automatic certificate issuance/renewal;
- distributed rate limiting;
- malware/content inspection;
- destination domain allowlists;
- cryptographic authentication inside each SOCKS5 UDP datagram;
- protection against an already compromised/rooted phone.

The in-memory backend is intended for one trusted operator and a small number of owned phones.

## Threat-model notes

### Stolen agent token

An attacker with a valid node token can impersonate that node ID to the agent API. They cannot authenticate to SOCKS5 or the admin API unless those credentials are also compromised. Revoke the token immediately.

### Stolen SOCKS credentials

An attacker can consume the exit fleet and mobile data. Rotate the password and
use firewall restrictions. The per-circuit limit contains a single transfer but
is not a per-user or time-window quota.

### Malicious destination

The phone establishes an ordinary outbound socket and receives arbitrary response bytes. No content is executed by PocketExit itself. The data still consumes carrier/Wi-Fi bandwidth and can expose the mobile public IP to the destination.

### Compromised backend or gateway

A compromised server can command agents to contact arbitrary public destinations permitted by the ACL. Run the deployment on a hardened host and protect its credentials and update path.

### UDP relay first-packet race

RFC 1928 UDP relay packets do not contain credentials. PocketExit opens a relay port only after an authenticated TCP `UDP ASSOCIATE` and then locks it to the first Nginx upstream session that sends a valid packet. Because the public pool is fixed, an Internet attacker who can reach UDP 12000–12031 could race the legitimate client's first datagram. Restrict that range to trusted source networks or a VPN, or remove the UDP port mappings when UDP is not required. TCP proxying is not affected.

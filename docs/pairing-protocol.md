# PocketExit personal mode — pairing protocol v2

This document is the contract between the backend, the Android agent, and the
web dashboard for **personal mode**: a single person running the PocketExit
binary on their own laptop, pairing their own phone, with no public server, no
domain name, no certificate authority, and no hand-minted tokens.

Server mode (a deployed backend behind nginx with `AGENT_TOKENS_JSON`) is
unchanged and keeps working exactly as before. Everything here is additive.

## The problem personal mode solves

In server mode, adding a phone means: deploy a backend somewhere public, obtain
a domain, obtain a CA-signed certificate, generate an agent token, add it to
`AGENT_TOKENS_JSON`, restart the backend, then type the token into the phone.

In personal mode, adding a phone means: run `pocketexit personal`, scan the code
on screen.

## Why a pinned self-signed certificate

The laptop has no domain name and no CA-signed certificate, so ordinary TLS
validation cannot work. Android makes this worse: `network_security_config.xml`
trusts only `system` anchors in release builds, so installing a user CA does not
help a release APK.

The answer is to authenticate the server by its **public key**, carried
out-of-band in the QR code the user scans. This is not trust-on-first-use: the
pin is transferred over a channel (the screen, physically in front of the user)
that an attacker on the network cannot influence.

The agent supplies its own `X509TrustManager` to OkHttp, which bypasses the
platform trust store entirely. A release APK therefore pairs with a self-signed
LAN certificate without the user installing anything.

## Server state directory

`$POCKETEXIT_HOME`, defaulting to `~/.pocketexit`. Created with mode `0700`.

| File | Mode | Contents |
| --- | --- | --- |
| `tls.crt` | `0644` | Self-signed leaf certificate |
| `tls.key` | `0600` | ECDSA P-256 private key, PKCS#8 |
| `state.json` | `0600` | Admin token, SOCKS password, paired nodes |

`state.json`:

```json
{
  "version": 1,
  "admin_token": "<32 random bytes, base64url>",
  "socks_username": "proxy",
  "socks_password": "<32 random bytes, base64url>",
  "nodes": {
    "pixel-8-a1b2c3d4": {
      "token": "<32 random bytes, base64url>",
      "device_name": "Pixel 8",
      "paired_at": "2026-09-09T18:04:11Z"
    }
  }
}
```

The certificate is re-issued when it is missing, unparsable, expired, within 30
days of expiry, or no longer covers the host's current set of IP addresses.

The **key** rotates only when there is no usable one to keep: missing,
unparsable, not matching the certificate, or genuinely near expiry. A laptop
that simply moved to another network keeps its key and is re-issued a
certificate covering the new address, so the pin survives and phones paired
against it keep connecting. Rotating the key there would lock out every paired
phone with re-pairing the only way back, which is the exact failure that
pinning the SubjectPublicKeyInfo instead of the whole certificate exists to
prevent.

Because the key can rotate on expiry, the dashboard always renders the
*current* pin rather than caching one.

## Certificate

- ECDSA P-256, SHA-256 signature, self-signed.
- Subject/Issuer CN: `PocketExit Personal`.
- Validity: 397 days from generation.
- `KeyUsage`: `digitalSignature`, `keyEncipherment`. `ExtKeyUsage`: `serverAuth`.
- `BasicConstraints`: `CA:FALSE`, critical.
- SANs: `localhost`, `127.0.0.1`, `::1`, plus every non-loopback unicast IP
  address bound to the host at generation time (both families).

### Pin construction

The pin is over the **SubjectPublicKeyInfo**, not the whole certificate, so a
certificate can be re-issued for new SANs without invalidating already-paired
phones as long as the key is reused.

```
pin = base64url_nopad( SHA-256( DER(SubjectPublicKeyInfo) ) )
```

This is the same input as an HPKP / OkHttp `sha256/` pin; only the encoding
differs (base64url without padding, so it survives a URI query string without
escaping).

## Pairing code

- 8 characters drawn from the Crockford base32 alphabet
  `0123456789ABCDEFGHJKMNPQRSTVWXYZ` (no `I`, `L`, `O`, `U`), uniformly at
  random via `crypto/rand` with rejection sampling. 40 bits of entropy.
- Rendered in groups of four (`A1B2-C3D4`) for reading aloud.
- Normalised before comparison, always on the server as the code is claimed, so
  a client may submit exactly what the user typed. A client that pre-validates
  or reformats a typed code has to fold it the same way or it will reject input
  the server would have accepted. The fold, in order:

  1. upper-case the whole string;
  2. drop `-` and any Unicode whitespace;
  3. map `I` and `L` to `1`;
  4. map `O` to `0`.

  Nothing else is rewritten, so any other character survives the fold and simply
  fails to match. The alphabet omits `I`, `L`, `O` and `U`, so no generated code
  can contain a character this fold rewrites, and folding can never turn one
  valid code into another. `U` has no confusable and is not folded, so a code
  typed with a `U` in it never matches.
- Time to live: 10 minutes.
- Single use. A successful claim consumes it.
- Five failed attempts consume it. Compared in constant time.
- At most one code is active at a time; minting a new one replaces the old.
- Held in memory only. Restarting the server invalidates any outstanding code.

## Onboarding URI

Version 2 adds `pair` and `fp` and drops `token`. Version 1 remains accepted so
existing server-mode deployments keep working.

```
pocketexit://configure
  ?v=2
  &server=https%3A%2F%2F192.168.1.50%3A8443
  &pair=A1B2C3D4
  &fp=<base64url-nopad SHA-256 of SubjectPublicKeyInfo>
  &name=Cesar%27s%20laptop
```

| Field | v1 | v2 | Meaning |
| --- | --- | --- | --- |
| `v` | `1` | `2` | Version, exact match |
| `server` | required | required | `https://` origin, no path, query, fragment, or userinfo |
| `node` | required | absent | v1 only; v2 lets the server assign the node id |
| `token` | required | absent | v1 only; v2 obtains the token by claiming |
| `pair` | absent | required | Pairing code |
| `fp` | absent | optional | Certificate pin. Absent means use the platform trust store |
| `name` | absent | optional | Human label for the server, display only, max 64 chars |

Parsing rules, both versions: duplicate keys are rejected; unknown keys are
rejected; a URI with a path, fragment, or userinfo is rejected; the resulting
configuration must pass the agent's existing `validationError()`.

`fp` is optional in v2 so that a personal-mode server placed behind a real
certificate (Tailscale, a reverse proxy, a LAN CA) can pair without pinning.
When `fp` is absent the agent uses ordinary platform validation.

## Endpoints

### `POST /pair/v1/claim` — unauthenticated

The only unauthenticated write endpoint in the system. It is guarded by the
pairing code, the attempt counter, and a rate limiter.

Request:

```json
{"code": "A1B2-C3D4", "device_name": "Pixel 8", "node_id": "pixel-8"}
```

`node_id` is optional; when omitted or already taken the server derives one from
`device_name` plus a random suffix. Whatever the client sends is sanitised to
`[A-Za-z0-9._-]{1,64}` — the client does not get to choose freely.

Response `200`:

```json
{
  "node_id": "pixel-8-a1b2c3d4",
  "agent_token": "<32 random bytes, base64url>",
  "server_url": "https://192.168.1.50:8443",
  "socks": {"host": "127.0.0.1", "port": 1080, "username": "proxy"}
}
```

| Status | Condition |
| --- | --- |
| `200` | Code valid, node registered, token minted |
| `400` | Malformed body, or `device_name` missing/too long |
| `401` | No active code, code expired, or code mismatch |
| `429` | More than 10 claim attempts from one address in 10 minutes |

The `socks` block is a display hint for the phone's "you're paired" screen. It
carries no password.

### `GET /api/v1/pairing` — admin

```json
{
  "active": true,
  "code": "A1B2-C3D4",
  "expires_at": "2026-09-09T18:14:11Z",
  "uri": "pocketexit://configure?...",
  "qr_svg": "<svg …>",
  "fingerprint": "<pin>",
  "server_url": "https://192.168.1.50:8443"
}
```

When no code is active, `active` is `false` and `code`, `uri`, and `qr_svg` are
empty.

### `POST /api/v1/pairing` — admin

Mints a code, replacing any active one. Same response shape as `GET`.

### `DELETE /api/v1/pairing` — admin

Cancels the active code. `204`.

### `DELETE /api/v1/nodes/{nodeID}` — admin

Unpairs a phone: revokes its token, drops it from the registry, and closes its
circuits. `204`. `404` when the node is unknown. In server mode, nodes come from
`AGENT_TOKENS_JSON` and cannot be revoked at runtime — the endpoint returns
`409`.

## Agent claim flow

1. User scans the on-screen QR with the phone's ordinary camera app. Android
   resolves `pocketexit://configure` to `MainActivity`. No in-app scanner, no
   camera permission.
2. The app parses the URI. On any parse failure it shows the reason and stops.
3. The app shows a confirmation sheet: server origin, server name, and the pin's
   short form. Nothing is written and no network request is made until the user
   confirms — a malicious link cannot silently repoint an agent.
4. On confirm, the app `POST`s to `/pair/v1/claim` through an OkHttp client
   built with the pinned trust manager.
5. On `200`, the app stores `server_url`, `node_id`, `agent_token`, and `pin`,
   leaves the agent **stopped**, and moves the user to the start screen.
6. On failure, the app shows the mapped message and stores nothing.

The pin is stored alongside the server URL in plain `SharedPreferences` — it is
a public hash, not a secret. The agent token continues to go through the
existing Android Keystore-backed `SecretStore`.

## Pinned trust manager

Applies to every request the agent makes when `pin` is non-empty: claim,
heartbeat, control long-poll, and circuit WebSockets.

- Trust is decided **only** by the pin. The chain is not walked, and the
  platform trust store is not consulted.
- The server's leaf certificate is the first entry of the presented chain. Its
  SubjectPublicKeyInfo is hashed and compared to the stored pin in constant
  time.
- Hostname verification is disabled, because the pin already binds the
  connection to a specific key and the server has no verifiable name. This is
  safe only because the pin is exact; it must never be combined with an empty
  or wildcard pin.
- A pin mismatch fails the handshake with a `CertificateException` naming the
  expected and presented pins, so the failure is diagnosable rather than a bare
  "handshake failed".

## Threat model

What personal mode defends against:

- **Passive LAN eavesdropper.** TLS, with the key authenticated by the pin.
- **Active LAN attacker impersonating the laptop.** They do not have the pinned
  private key, so the handshake fails.
- **Malicious `pocketexit://` link.** Pairing requires a live code from the
  server, and the app requires explicit user confirmation showing the origin
  before any request is made.
- **Brute-forcing the pairing code.** 40 bits, 10-minute window, five attempts
  per code, ten attempts per address per ten minutes.

What it does not defend against, stated plainly:

- **An attacker who can read the laptop screen** at pairing time. They can pair
  a phone. The window is 10 minutes and the code is single-use.
- **A compromised laptop.** It holds the private key, the admin token, and the
  SOCKS password.
- **Traffic analysis at the carrier.** Exit traffic leaves the phone's SIM in
  the clear beyond whatever end-to-end encryption the client already had.

## Defaults in personal mode

| Setting | Personal mode | Server mode |
| --- | --- | --- |
| HTTPS listener | `0.0.0.0:8443`, direct TLS, no nginx | `:8080` behind nginx |
| SOCKS listener | `127.0.0.1:1080` — loopback only | `:1080`, published |
| UDP relay | `127.0.0.1:12000-12031` | `0.0.0.0:12000-12031` |
| Admin token | Generated, stored in `state.json` | `ADMIN_TOKEN` |
| Agent tokens | Minted by pairing | `AGENT_TOKENS_JSON` |
| Audit log | `$POCKETEXIT_HOME/audit.jsonl` | `AUDIT_LOG_PATH` |
| `ALLOW_PRIVATE_DESTINATIONS` | `false` | `false` |

SOCKS binds to loopback in personal mode because the laptop is the only client.
Binding it to the LAN would expose an authenticated open proxy to the local
network; `--socks-addr` can override this for the user who genuinely wants it,
and the server logs a warning when it is not a loopback address.

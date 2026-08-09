# Security

PocketExit is a private proxy system. Exposing it without authentication, destination controls, and transport security would create an abuse path attached to your mobile subscriptions. Complete this checklist before Internet deployment.

## Deployment checklist

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

## Implemented controls

### Authentication

- Admin API: bearer token compared in constant time.
- Android agents: exact node-ID-to-token mapping.
- SOCKS5: mandatory username/password authentication.
- UDP relay: allocated only after authenticated TCP UDP ASSOCIATE and locked to the first observed upstream session.

### Network isolation

Only Nginx publishes host ports. The backend uses an internal Compose network, a read-only root filesystem, no Linux capabilities, and `no-new-privileges`.

### SOCKS client transport

The `:1080` endpoint is conventional SOCKS5 over raw TCP. SOCKS5 username/password authentication is not transport encryption. Unless the client reaches it through a trusted private network, VPN, SSH tunnel, or an added TLS wrapper, the credentials and any non-TLS proxied traffic can be observed or modified on the network path. Source-restrict port 1080; do not treat the SOCKS password as protected merely because authentication is enabled.

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

## Development certificate warning

`scripts/gen-dev-certs.sh` creates a local CA and server key. The CA private key is highly sensitive because it can issue certificates trusted by any device on which that CA is installed.

- Never ship `ca.key` to a phone.
- Install only `ca.crt` on an owned test phone.
- Remove the test CA from the phone after testing.
- Do not use the generated CA for public production service.
- Do not put any generated certificate/private-key files in the ZIP or repository.

## Known security limitations

This MVP does not provide:

- native TLS wrapping for the public SOCKS5 listener;
- mTLS or hardware-backed remote attestation;
- account-based multi-tenancy or role-based access control;
- persistent tamper-evident audit logs;
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

An attacker can consume the exit fleet and mobile data. Rotate the password and use firewall restrictions. The backend does not yet maintain per-user quotas.

### Malicious destination

The phone establishes an ordinary outbound socket and receives arbitrary response bytes. No content is executed by PocketExit itself. The data still consumes carrier/Wi-Fi bandwidth and can expose the mobile public IP to the destination.

### Compromised backend or gateway

A compromised server can command agents to contact arbitrary public destinations permitted by the ACL. Run the deployment on a hardened host and protect its credentials and update path.

### UDP relay first-packet race

RFC 1928 UDP relay packets do not contain credentials. PocketExit opens a relay port only after an authenticated TCP `UDP ASSOCIATE` and then locks it to the first Nginx upstream session that sends a valid packet. Because the public pool is fixed, an Internet attacker who can reach UDP 12000–12031 could race the legitimate client's first datagram. Restrict that range to trusted source networks or a VPN, or remove the UDP port mappings when UDP is not required. TCP proxying is not affected.

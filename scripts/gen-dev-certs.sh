#!/usr/bin/env sh
set -eu

DOMAIN="${1:-pocketexit.local}"
CERT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../nginx/certs" && pwd)"
mkdir -p "$CERT_DIR"

if [ -f "$CERT_DIR/server.crt" ] && [ -f "$CERT_DIR/server.key" ] && [ "${2:-}" != "--force" ]; then
  echo "Certificates already exist in $CERT_DIR (pass --force as the second argument to replace them)."
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/openssl.cnf" <<CONFIG
[req]
distinguished_name = dn
prompt = no
req_extensions = req_ext

[dn]
CN = $DOMAIN
O = PocketExit Development

[req_ext]
subjectAltName = @alt_names

[alt_names]
DNS.1 = $DOMAIN
DNS.2 = localhost
IP.1 = 127.0.0.1
IP.2 = ::1

[v3_ca]
basicConstraints = critical, CA:TRUE
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash

[v3_server]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @alt_names
CONFIG

openssl genrsa -out "$CERT_DIR/ca.key" 3072
openssl req -x509 -new -sha256 -days 3650 \
  -key "$CERT_DIR/ca.key" \
  -subj "/CN=PocketExit Development CA/O=PocketExit Development" \
  -extensions v3_ca -config "$TMP/openssl.cnf" \
  -out "$CERT_DIR/ca.crt"
openssl genrsa -out "$CERT_DIR/server.key" 2048
openssl req -new -sha256 -key "$CERT_DIR/server.key" \
  -config "$TMP/openssl.cnf" -out "$TMP/server.csr"
openssl x509 -req -sha256 -days 825 \
  -in "$TMP/server.csr" \
  -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" -CAcreateserial \
  -extensions v3_server -extfile "$TMP/openssl.cnf" \
  -out "$CERT_DIR/server.crt"
chmod 600 "$CERT_DIR/server.key" "$CERT_DIR/ca.key"
chmod 644 "$CERT_DIR/server.crt" "$CERT_DIR/ca.crt"
rm -f "$CERT_DIR/ca.srl"

echo "Created development TLS certificate for $DOMAIN in $CERT_DIR"
echo "For production, replace server.crt/server.key with a public CA certificate."

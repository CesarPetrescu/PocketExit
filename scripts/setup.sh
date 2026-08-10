#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DOMAIN=${1:-pocketexit.local}
ENV_FILE="$ROOT/.env"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

need openssl
need python3

random_secret() {
  openssl rand -hex 32
}

if [ ! -f "$ENV_FILE" ]; then
  ADMIN_TOKEN=$(random_secret)
  SOCKS_PASSWORD=$(random_secret)
  S20_TOKEN=$(random_secret)
  S22_TOKEN=$(random_secret)
  S24_TOKEN=$(random_secret)
  cat > "$ENV_FILE" <<CONFIG
PUBLIC_PROXY_HOST=$DOMAIN
ADMIN_TOKEN=$ADMIN_TOKEN
SOCKS_USERNAME=proxy
SOCKS_PASSWORD=$SOCKS_PASSWORD
AGENT_TOKENS_JSON={"s20u":"$S20_TOKEN","s22u":"$S22_TOKEN","s24u":"$S24_TOKEN"}
HTTP_ADDR=:8080
SOCKS_ADDR=:1080
UDP_BIND_HOST=0.0.0.0
UDP_PORT_START=12000
UDP_PORT_END=12031
NODE_OFFLINE_AFTER=45s
COMMAND_WAIT=5s
OPEN_TIMEOUT=45s
IDLE_TIMEOUT=2m
MAX_CIRCUITS_PER_NODE=128
MAX_BYTES_PER_CIRCUIT=1073741824
ALLOW_PRIVATE_DESTINATIONS=false
LOG_JSON=true
LOG_LEVEL=info
AUDIT_LOG_PATH=/data/audit.jsonl
CONFIG
  chmod 600 "$ENV_FILE"
  echo "Created $ENV_FILE with random credentials."
else
  echo "$ENV_FILE already exists; leaving credentials unchanged."
fi

"$ROOT/scripts/gen-dev-certs.sh" "$DOMAIN"

cat <<MESSAGE
Setup complete.

Development dashboard: https://$DOMAIN/
SOCKS5 endpoint:       $DOMAIN:1080
Android backend URL:   https://$DOMAIN

The generated certificate is for development. A debug APK trusts user-installed
CAs; a release APK requires a system-trusted certificate. See README.md.
MESSAGE

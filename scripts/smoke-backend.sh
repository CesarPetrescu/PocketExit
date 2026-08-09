#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d)
HTTP_PORT=${SMOKE_HTTP_PORT:-18080}
SOCKS_PORT=${SMOKE_SOCKS_PORT:-11080}
PID=""
cleanup() {
  [ -z "$PID" ] || kill "$PID" 2>/dev/null || true
  [ -z "$PID" ] || wait "$PID" 2>/dev/null || true
  rm -rf "$TMP"
}
trap cleanup EXIT INT TERM

command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 1; }

(
  cd "$ROOT/backend"
  go build -o "$TMP/pocketexit" ./cmd/server
)

HTTP_ADDR="127.0.0.1:$HTTP_PORT" \
SOCKS_ADDR="127.0.0.1:$SOCKS_PORT" \
UDP_BIND_HOST="127.0.0.1" \
UDP_PORT_START=13000 \
UDP_PORT_END=13003 \
PUBLIC_PROXY_HOST=127.0.0.1 \
ADMIN_TOKEN=smoke-admin-token-2026 \
SOCKS_USERNAME=proxy \
SOCKS_PASSWORD=smoke-proxy-password-2026 \
AGENT_TOKENS_JSON='{"smoke-phone":"smoke-agent-token-2026"}' \
LOG_JSON=false \
"$TMP/pocketexit" >"$TMP/backend.log" 2>&1 &
PID=$!

BASE="http://127.0.0.1:$HTTP_PORT"
i=0
until curl -fsS "$BASE/api/v1/health" >/dev/null 2>&1; do
  i=$((i+1))
  if [ "$i" -ge 50 ]; then
    cat "$TMP/backend.log" >&2
    exit 1
  fi
  sleep 0.1
done

curl -fsS -X POST "$BASE/agent/v1/heartbeat" \
  -H 'Authorization: Bearer smoke-agent-token-2026' \
  -H 'Content-Type: application/json' \
  --data '{"node_id":"smoke-phone","device_name":"Smoke Phone","app_version":"test","control_policy":"AUTO","exit_policy":"CELLULAR_PREFERRED","active_control_network":"WIFI","transport_protocol":"h3","wifi":{"available":true,"validated":true},"cellular":{"available":true,"validated":true},"battery_percent":80,"charging":true,"active_circuits":0,"bytes_up":0,"bytes_down":0}' \
  | grep -q 'smoke-phone'

curl -fsS "$BASE/api/v1/nodes" -H 'Authorization: Bearer smoke-admin-token-2026' | grep -q 'smoke-phone'

curl -fsS -X PATCH "$BASE/api/v1/nodes/smoke-phone" \
  -H 'Authorization: Bearer smoke-admin-token-2026' \
  -H 'Content-Type: application/json' \
  --data '{"control_policy":"WIFI_ONLY","exit_policy":"CELLULAR_ONLY"}' \
  | grep -q 'CELLULAR_ONLY'

curl -fsS "$BASE/agent/v1/control?node_id=smoke-phone" \
  -H 'Authorization: Bearer smoke-agent-token-2026' | grep -q 'policy_update'

curl -fsS "$BASE/api/v1/metrics" -H 'Authorization: Bearer smoke-admin-token-2026' | grep -q 'pocketexit_nodes_online 1'

echo "Backend HTTP/control-plane smoke test passed"

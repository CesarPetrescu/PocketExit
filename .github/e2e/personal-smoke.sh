#!/usr/bin/env sh
# Personal-mode process smoke test: start "pocketexit personal" in a throwaway
# state directory, reach it over its own self-signed certificate, mint a
# pairing code, claim it, and prove the minted token authenticates an agent.
#
# This is the server-mode smoke test's twin (scripts/smoke-backend.sh) for the
# zero-server entry point, so it stays in the same shape and idiom.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TMP=$(mktemp -d)
free_tcp_port() {
  python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}
HTTPS_PORT=${PERSONAL_HTTPS_PORT:-$(free_tcp_port)}
SOCKS_PORT=${PERSONAL_SOCKS_PORT:-$(free_tcp_port)}
HOME_DIR="$TMP/state"
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

# "pocketexit version" and "pocketexit help" are part of the CLI surface the
# personal-mode entry point added, so they are exercised before the server runs.
"$TMP/pocketexit" version | grep -q '^pocketexit '
"$TMP/pocketexit" help | grep -q 'pocketexit personal'

POCKETEXIT_HOME="$HOME_DIR" "$TMP/pocketexit" personal \
  --https-addr "127.0.0.1:$HTTPS_PORT" \
  --socks-addr "127.0.0.1:$SOCKS_PORT" \
  --frontend "$ROOT/frontend" \
  >"$TMP/personal.log" 2>&1 &
PID=$!

BASE="https://127.0.0.1:$HTTPS_PORT"
CURL="curl -fsS --cacert $HOME_DIR/tls.crt"
# status runs a request that is expected to be rejected and prints only the
# code, so a deliberate 401 does not look like a failure in the log.
status() {
  curl -sS --cacert "$HOME_DIR/tls.crt" -o /dev/null -w '%{http_code}' "$@"
}
i=0
until [ -f "$HOME_DIR/tls.crt" ] && $CURL "$BASE/api/v1/health" >/dev/null 2>&1; do
  if ! kill -0 "$PID" 2>/dev/null; then
    cat "$TMP/personal.log" >&2
    exit 1
  fi
  i=$((i+1))
  if [ "$i" -ge 100 ]; then
    cat "$TMP/personal.log" >&2
    echo "personal mode did not answer /api/v1/health over its own certificate" >&2
    exit 1
  fi
  sleep 0.1
done

# The contract fixes the state directory layout and its modes.
[ "$(stat -c '%a' "$HOME_DIR")" = "700" ] || { echo "state directory is not 0700" >&2; exit 1; }
[ "$(stat -c '%a' "$HOME_DIR/tls.key")" = "600" ] || { echo "tls.key is not 0600" >&2; exit 1; }
[ "$(stat -c '%a' "$HOME_DIR/state.json")" = "600" ] || { echo "state.json is not 0600" >&2; exit 1; }

# json <file> <key>... prints one field of a JSON document. Strings print bare
# so they can be compared with shell equality; everything else prints as JSON,
# so a boolean reads "true" rather than Python's "True".
json() {
  python3 -c 'import json, sys
value = json.load(open(sys.argv[1]))
for key in sys.argv[2:]:
    value = value[key]
print(value if isinstance(value, str) else json.dumps(value))' "$@"
}

python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d["admin_token"])' \
  "$HOME_DIR/state.json" > "$TMP/admin-token"
ADMIN_TOKEN=$(cat "$TMP/admin-token")
[ -n "$ADMIN_TOKEN" ] || { echo "state.json has no admin token" >&2; exit 1; }

# Minting replaces any active code and answers the pairing block.
$CURL -X POST "$BASE/api/v1/pairing" -H "Authorization: Bearer $ADMIN_TOKEN" > "$TMP/pairing.json"
PAIRING_CODE=$(json "$TMP/pairing.json" code)
FINGERPRINT=$(json "$TMP/pairing.json" fingerprint)
SERVER_URL=$(json "$TMP/pairing.json" server_url)
json "$TMP/pairing.json" active | grep -q '^true$'
URI=$(json "$TMP/pairing.json" uri)
# url.Values.Encode sorts the query, so the fields are asserted individually
# rather than as one fixed string.
echo "$URI" | grep -q '^pocketexit://configure?'
echo "$URI" | grep -q 'v=2'
echo "$URI" | grep -q "pair=$(echo "$PAIRING_CODE" | tr -d '-')"
echo "$URI" | grep -q "fp=$FINGERPRINT"
json "$TMP/pairing.json" qr_svg | grep -q '<svg'
echo "$PAIRING_CODE" | grep -Eq '^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$'
[ "$SERVER_URL" = "$BASE" ] || { echo "advertised server URL $SERVER_URL is not $BASE" >&2; exit 1; }

# The pin travels in the QR code, so it has to be the hash of the certificate's
# SubjectPublicKeyInfo and nothing else.
python3 - "$HOME_DIR/tls.crt" "$FINGERPRINT" <<'PY'
import base64, hashlib, re, ssl, subprocess, sys
der = ssl.PEM_cert_to_DER_cert(open(sys.argv[1]).read())
spki = subprocess.run(
    ["openssl", "x509", "-pubkey", "-noout", "-in", sys.argv[1]],
    check=True, capture_output=True, text=True).stdout
body = "".join(re.findall(r"^(?!-----).*$", spki, re.M))
digest = hashlib.sha256(base64.b64decode(body)).digest()
pin = base64.urlsafe_b64encode(digest).decode().rstrip("=")
if pin != sys.argv[2]:
    raise SystemExit(f"pin mismatch: certificate {pin}, server {sys.argv[2]}")
if not der:
    raise SystemExit("certificate did not parse")
print("certificate pin matches the SubjectPublicKeyInfo")
PY

# A wrong code is rejected without consuming the right one.
STATUS=$(status -X POST "$BASE/pair/v1/claim" \
  -H 'Content-Type: application/json' \
  --data '{"code":"0000-0000","device_name":"CI Phone"}')
[ "$STATUS" = "401" ] || { echo "a wrong pairing code answered $STATUS, not 401" >&2; exit 1; }

$CURL -X POST "$BASE/pair/v1/claim" \
  -H 'Content-Type: application/json' \
  --data "{\"code\":\"$PAIRING_CODE\",\"device_name\":\"CI Phone\",\"node_id\":\"ci-phone\"}" \
  > "$TMP/claim.json"
NODE_ID=$(json "$TMP/claim.json" node_id)
AGENT_TOKEN=$(json "$TMP/claim.json" agent_token)
[ "$NODE_ID" = "ci-phone" ] || { echo "claim assigned node $NODE_ID" >&2; exit 1; }
[ -n "$AGENT_TOKEN" ] || { echo "claim minted no agent token" >&2; exit 1; }
json "$TMP/claim.json" server_url | grep -q "^$BASE\$"
json "$TMP/claim.json" socks host | grep -q '^127\.0\.0\.1$'

# The code is single use.
STATUS=$(status -X POST "$BASE/pair/v1/claim" \
  -H 'Content-Type: application/json' \
  --data "{\"code\":\"$PAIRING_CODE\",\"device_name\":\"CI Phone\"}")
[ "$STATUS" = "401" ] || { echo "a consumed pairing code answered $STATUS, not 401" >&2; exit 1; }

# The whole point of the claim: the minted token authenticates an agent.
$CURL -X POST "$BASE/agent/v1/heartbeat" \
  -H "Authorization: Bearer $AGENT_TOKEN" \
  -H 'Content-Type: application/json' \
  --data "{\"node_id\":\"$NODE_ID\",\"device_name\":\"CI Phone\",\"app_version\":\"ci\",\"control_policy\":\"AUTO\",\"exit_policy\":\"CELLULAR_PREFERRED\",\"active_control_network\":\"WIFI\",\"transport_protocol\":\"h3\",\"wifi\":{\"available\":true,\"validated\":true},\"cellular\":{\"available\":true,\"validated\":true},\"battery_percent\":80,\"charging\":true,\"active_circuits\":0,\"bytes_up\":0,\"bytes_down\":0}" \
  | grep -q "$NODE_ID"

$CURL "$BASE/api/v1/nodes" -H "Authorization: Bearer $ADMIN_TOKEN" | grep -q "$NODE_ID"
$CURL "$BASE/api/v1/metrics" -H "Authorization: Bearer $ADMIN_TOKEN" | grep -q 'pocketexit_nodes_online 1'

# Unpairing is a personal-mode-only capability and has to revoke the token.
$CURL -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/v1/nodes/$NODE_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | grep -q '^204$'
STATUS=$(status -X POST "$BASE/agent/v1/heartbeat" \
  -H "Authorization: Bearer $AGENT_TOKEN" \
  -H 'Content-Type: application/json' \
  --data "{\"node_id\":\"$NODE_ID\",\"device_name\":\"CI Phone\"}")
[ "$STATUS" = "401" ] || { echo "a revoked agent token answered $STATUS, not 401" >&2; exit 1; }

grep -q '"event":"node_paired"' "$HOME_DIR/audit.jsonl"

# A clean SIGTERM shutdown, not a killed process.
kill -TERM "$PID"
wait "$PID"
PID=""

echo "Personal-mode pairing smoke test passed"

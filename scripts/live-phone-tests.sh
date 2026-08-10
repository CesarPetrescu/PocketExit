#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
[ -f "$ROOT/.env" ] || { echo '.env is required' >&2; exit 2; }

set -a
. "$ROOT/.env"
set +a

: "${SOCKS_USERNAME:?SOCKS_USERNAME is required}"
: "${SOCKS_PASSWORD:?SOCKS_PASSWORD is required}"
: "${ADMIN_TOKEN:?ADMIN_TOKEN is required}"
: "${PUBLIC_PROXY_HOST:?PUBLIC_PROXY_HOST is required}"

CURL_IMAGE=${LIVE_CURL_IMAGE:-curlimages/curl:8.16.0}
DOCKER_NETWORK=${LIVE_DOCKER_NETWORK:-pocketexit_internal}
NODES=${LIVE_TEST_NODES:-s20u s22u s24u}
PROXY=socks5h://backend:1080
PUBLIC_BASE=https://$PUBLIC_PROXY_HOST
TMP=$(mktemp -d)
PASSED=0
FAILED=0
BASELINE_HASH=""

cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

pass() {
    PASSED=$((PASSED + 1))
    printf '  PASS  %s\n' "$1"
}

fail() {
    FAILED=$((FAILED + 1))
    printf '  FAIL  %s\n' "$1" >&2
}

proxy_curl() {
    selector=$1
    shift
    docker run --rm --network "$DOCKER_NETWORK" "$CURL_IMAGE" \
        --silent --show-error \
        --proxy "$PROXY" \
        --proxy-user "$selector:$SOCKS_PASSWORD" \
        "$@"
}

command -v docker >/dev/null 2>&1 || { echo 'docker is required' >&2; exit 2; }
command -v curl >/dev/null 2>&1 || { echo 'curl is required' >&2; exit 2; }
command -v jq >/dev/null 2>&1 || { echo 'jq is required' >&2; exit 2; }
docker network inspect "$DOCKER_NETWORK" >/dev/null 2>&1 || {
    echo "Docker network $DOCKER_NETWORK is unavailable" >&2
    exit 2
}

printf 'PocketExit live phone suite\n'
printf 'Target: %s (credentials redacted)\n' "$PUBLIC_BASE"

if curl -fsS --max-time 10 "$PUBLIC_BASE/api/v1/health" | jq -e '.status == "ok"' >/dev/null; then
    pass 'public health endpoint'
else
    fail 'public health endpoint'
fi

for node in $NODES; do
    printf '\n[%s]\n' "$node"
    selector=$SOCKS_USERNAME@$node

    github_json=$TMP/$node-github.json
    if proxy_curl "$selector" --fail --max-time 20 \
        https://api.github.com/repos/CesarPetrescu/PocketExit >"$github_json" \
        && jq -e '.full_name == "CesarPetrescu/PocketExit"' "$github_json" >/dev/null; then
        pass 'HTTPS + remote DNS + JSON'
    else
        fail 'HTTPS + remote DNS + JSON'
    fi

    example_html=$TMP/$node-example.html
    if proxy_curl "$selector" --fail --max-time 20 http://example.com/ >"$example_html" \
        && grep -qi 'Example Domain' "$example_html"; then
        pass 'plain HTTP response'
    else
        fail 'plain HTTP response'
    fi

    post_json=$TMP/$node-post.json
    if proxy_curl "$selector" --fail --max-time 20 \
        --data 'pocketexit=probe' https://postman-echo.com/post >"$post_json" \
        && jq -e '.form.pocketexit == "probe"' "$post_json" >/dev/null; then
        pass 'HTTPS POST upload + response'
    else
        fail 'HTTPS POST upload + response'
    fi

    range_file=$TMP/$node-range.bin
    if proxy_curl "$selector" --fail --max-time 30 --range 0-262143 \
        https://ash-speed.hetzner.com/100MB.bin >"$range_file" \
        && [ "$(wc -c <"$range_file")" -eq 262144 ]; then
        current_hash=$(sha256sum "$range_file" | cut -d' ' -f1)
        if [ -z "$BASELINE_HASH" ]; then
            BASELINE_HASH=$current_hash
            pass '256 KiB ranged download'
        elif [ "$current_hash" = "$BASELINE_HASH" ]; then
            pass '256 KiB ranged download + cross-node hash'
        else
            fail '256 KiB ranged download + cross-node hash'
        fi
    else
        fail '256 KiB ranged download'
    fi

    git_refs=$TMP/$node-git-refs
    if proxy_curl "$selector" --fail --max-time 20 \
        'https://github.com/CesarPetrescu/PocketExit.git/info/refs?service=git-upload-pack' \
        >"$git_refs" && grep -a -q '# service=git-upload-pack' "$git_refs"; then
        pass 'Git smart-HTTP discovery'
    else
        fail 'Git smart-HTTP discovery'
    fi

    ws_log=$TMP/$node-websocket.log
    proxy_curl "$selector" --max-time 5 --verbose wss://echo.websocket.org \
        >/dev/null 2>"$ws_log" || true
    if grep -q 'Received 101' "$ws_log"; then
        pass 'WSS upgrade (101 Switching Protocols)'
    else
        fail 'WSS upgrade (101 Switching Protocols)'
    fi

    cellular_json=$TMP/$node-cellular.json
    if proxy_curl "$SOCKS_USERNAME@$node!cellular" --fail --max-time 20 \
        'https://api.ipify.org?format=json' >"$cellular_json" \
        && jq -e '.ip | strings | length > 2' "$cellular_json" >/dev/null; then
        pass 'forced-cellular HTTPS (public IP redacted)'
    else
        fail 'forced-cellular HTTPS (public IP redacted)'
    fi
done

printf '\n[negative and recovery checks]\n'
if proxy_curl "$SOCKS_USERNAME@missing-node" --fail --max-time 10 \
    https://example.com/ >/dev/null 2>&1; then
    fail 'unknown-node selector rejected'
else
    pass 'unknown-node selector rejected'
fi

first_node=$(printf '%s\n' "$NODES" | awk '{print $1}')
if proxy_curl "$SOCKS_USERNAME@$first_node" --fail --max-time 10 \
    http://127.0.0.1/ >/dev/null 2>&1; then
    fail 'private destination blocked'
else
    pass 'private destination blocked'
fi

sleep 2
nodes_json=$TMP/nodes.json
circuits_json=$TMP/circuits.json
if curl -fsS --max-time 10 -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$PUBLIC_BASE/api/v1/nodes" >"$nodes_json" \
    && curl -fsS --max-time 10 -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$PUBLIC_BASE/api/v1/circuits" >"$circuits_json" \
    && [ "$(jq '[.nodes[] | select(.online)] | length' "$nodes_json")" -eq "$(printf '%s\n' $NODES | wc -l)" ] \
    && [ "$(jq '[.circuits[] | select(.status == "open" or .status == "pending")] | length' "$circuits_json")" -eq 0 ]; then
    pass 'all nodes recovered; no active circuits leaked'
else
    fail 'all nodes recovered; no active circuits leaked'
fi

printf '\nResult: %s passed, %s failed\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]

#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

printf '%s\n' '== Go unit and integration tests =='
(
  cd "$ROOT/backend"
  go test ./...
  go vet ./...
  go test -race ./...
  go test -coverprofile=coverage.out ./...
  go tool cover -func=coverage.out | tail -1
  rm -f coverage.out
)

printf '%s\n' '== Backend process smoke test =='
"$ROOT/scripts/smoke-backend.sh"

printf '%s\n' '== Frontend syntax =='
node --check "$ROOT/frontend/app.js"

printf '%s\n' '== YAML/XML/shell/source checks =='
python3 "$ROOT/scripts/check-compose.py"
python3 "$ROOT/scripts/verify-source.py"
python3 - "$ROOT" <<'PY'
import pathlib, sys, xml.etree.ElementTree as ET, yaml
root = pathlib.Path(sys.argv[1])
yaml.safe_load((root / "docker-compose.yml").read_text())
for path in (root / "android/app/src/main").rglob("*.xml"):
    ET.parse(path)
print("YAML and Android XML parsing passed")
PY
find "$ROOT/scripts" -type f -name '*.sh' -exec sh -n {} \;
sh -n "$ROOT/android/gradlew"

printf '%s\n' 'Core verification passed.'

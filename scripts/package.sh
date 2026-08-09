#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PARENT=$(dirname "$ROOT")
NAME=$(basename "$ROOT")
OUT=${1:-"$PARENT/$NAME.zip"}

command -v python3 >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 1; }

python3 - "$ROOT" "$OUT" <<'PY'
from __future__ import annotations
import hashlib
import pathlib
import sys
import zipfile

root = pathlib.Path(sys.argv[1]).resolve()
out = pathlib.Path(sys.argv[2]).resolve()
excluded_parts = {".git", ".gradle", ".idea", "build", "__pycache__"}
excluded_files = {
    ".env", "server", "pocketexit", "coverage.out", "gradle-wrapper.jar",
    "ca.crt", "ca.key", "server.crt", "server.key", "ca.srl",
}

out.parent.mkdir(parents=True, exist_ok=True)
if out.exists():
    out.unlink()
with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if path.is_dir() or any(part in excluded_parts for part in relative.parts):
            continue
        if path.name in excluded_files:
            continue
        archive.write(path, pathlib.Path(root.name) / relative)

digest = hashlib.sha256(out.read_bytes()).hexdigest()
sha_path = out.with_suffix(out.suffix + ".sha256")
sha_path.write_text(f"{digest}  {out.name}\n", encoding="utf-8")
print(out)
print(sha_path)
print(digest)
PY

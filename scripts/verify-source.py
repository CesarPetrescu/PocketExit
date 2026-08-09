#!/usr/bin/env python3
"""Repository policy checks for the no-root/no-ADB Android architecture."""
from __future__ import annotations

import pathlib
import sys

root = pathlib.Path(__file__).resolve().parents[1]
android = root / "android"
errors: list[str] = []

for path in android.rglob("*"):
    if not path.is_file() or path.suffix.lower() in {".jar", ".png", ".webp"}:
        continue
    text = path.read_text(encoding="utf-8", errors="ignore")
    forbidden = {
        "android.net.VpnService": "VpnService",
        "bindProcessToNetwork(": "process-wide network binding",
        "Runtime.getRuntime().exec(\"su": "root shell",
    }
    for needle, label in forbidden.items():
        if needle in text:
            errors.append(f"{path.relative_to(root)} contains forbidden {label}")

manifest = (android / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
for permission in ("INTERNET", "ACCESS_NETWORK_STATE", "CHANGE_NETWORK_STATE", "FOREGROUND_SERVICE"):
    if permission not in manifest:
        errors.append(f"Android manifest is missing {permission}")

for secret_path in (root / ".env", root / "nginx/certs/server.key", root / "nginx/certs/ca.key"):
    # These files are legitimate in a configured working tree but must never be
    # included by scripts/package.sh. This checker only warns during development.
    if secret_path.exists():
        print(f"warning: local secret exists and will be excluded: {secret_path.relative_to(root)}")

if errors:
    print("Source policy checks failed:", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)
print("Source policy checks passed")

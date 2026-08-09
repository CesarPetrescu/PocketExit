#!/usr/bin/env python3
"""Static checks for the Compose topology and public exposure contract."""
from __future__ import annotations

import pathlib
import sys

import yaml

root = pathlib.Path(__file__).resolve().parents[1]
path = root / "docker-compose.yml"
data = yaml.safe_load(path.read_text(encoding="utf-8"))
services = data.get("services", {})
errors: list[str] = []

if set(services) != {"backend", "nginx"}:
    errors.append("Compose must contain exactly the backend and nginx services")

backend = services.get("backend", {})
nginx = services.get("nginx", {})
if backend.get("ports"):
    errors.append("backend must not publish host ports")
if not backend.get("expose"):
    errors.append("backend should expose its internal ports")
if not nginx.get("ports"):
    errors.append("nginx must publish the public ports")

published = {str(item).split(":")[-1] for item in nginx.get("ports", [])}
for required in {"80/tcp", "443/tcp", "443/udp", "1080/tcp", "12000-12031/udp"}:
    if required not in published:
        errors.append(f"nginx is missing public mapping {required}")

for name, service in services.items():
    if "no-new-privileges:true" not in service.get("security_opt", []):
        errors.append(f"{name} is missing no-new-privileges")

if not backend.get("read_only") or not nginx.get("read_only"):
    errors.append("both containers must use read-only root filesystems")

if errors:
    print("Compose checks failed:", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)
print("Compose topology checks passed")

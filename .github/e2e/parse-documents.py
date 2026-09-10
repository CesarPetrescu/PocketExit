#!/usr/bin/env python3
"""Parse every YAML and Android XML document in the repository.

scripts/test.sh does this inline for the Compose files, the workflows and the
Android resources. CI runs it as its own step so a malformed workflow or a
broken string resource fails in seconds rather than inside a container build.
"""
from __future__ import annotations

import pathlib
import sys
import xml.etree.ElementTree as ElementTree

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]


class ComposeLoader(yaml.SafeLoader):
    """docker-compose.tunnel.yml uses Compose's !override tag."""


ComposeLoader.add_constructor(
    "!override", lambda loader, node: loader.construct_sequence(node)
)
ComposeLoader.add_constructor(
    "!reset", lambda loader, node: loader.construct_scalar(node)
)


def main() -> int:
    documents = [
        ROOT / "docker-compose.yml",
        ROOT / "docker-compose.tunnel.yml",
        *sorted((ROOT / ".github/workflows").glob("*.yml")),
        *sorted((ROOT / ".github/workflows").glob("*.yaml")),
        *sorted((ROOT / ".github/linters").glob("*.yml")),
        *sorted((ROOT / ".github/linters").glob("*.yaml")),
    ]
    dependabot = ROOT / ".github/dependabot.yml"
    if dependabot.exists():
        documents.append(dependabot)

    failures: list[str] = []
    for path in documents:
        try:
            yaml.load(path.read_text(encoding="utf-8"), Loader=ComposeLoader)
        except yaml.YAMLError as error:
            failures.append(f"{path.relative_to(ROOT)}: {error}")

    xml_documents = sorted((ROOT / "android/app/src/main").rglob("*.xml"))
    for path in xml_documents:
        try:
            ElementTree.parse(path)
        except ElementTree.ParseError as error:
            failures.append(f"{path.relative_to(ROOT)}: {error}")

    if failures:
        print("Document parsing failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print(
        f"Parsed {len(documents)} YAML and {len(xml_documents)} Android XML documents"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

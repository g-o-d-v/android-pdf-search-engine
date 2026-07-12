#!/usr/bin/env python3
"""Ensure a Git release tag exactly matches VERSION_NAME."""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def version_name() -> str:
    for raw in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if raw.startswith("VERSION_NAME="):
            return raw.split("=", 1)[1].strip()
    raise SystemExit("VERSION_NAME is missing from gradle.properties")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("tag")
    args = parser.parse_args()
    expected = version_name()
    if args.tag != expected:
        print(f"ERROR: release tag {args.tag!r} must equal {expected!r}")
        return 1
    print(f"Release tag matches VERSION_NAME: {expected}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Fast, offline checks before pushing a public JitPack release."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

REQUIRED = [
    "README.md",
    "LICENSE",
    "NOTICE",
    "THIRD_PARTY_NOTICES.md",
    "MODEL_PROVENANCE.md",
    "CONTRIBUTING.md",
    "CODE_OF_CONDUCT.md",
    "SECURITY.md",
    "CHANGELOG.md",
    ".gitignore",
    ".gitattributes",
    "jitpack.yml",
    "gradlew",
    "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties",
    "docs/INTEGRATION.md",
    "docs/OFFICIAL_OCR_MODELS.md",
    "docs/assets/donation/wechat-pay.png",
    "docs/assets/donation/alipay.png",
    "sample/build.gradle",
]

OBSOLETE_TRACKED = {
    "COMMERCIAL-LICENSE.md",
    "CONTRIBUTOR-LICENSE-AGREEMENT.md",
    "COPYING",
    "MODEL_PERMISSION_RECORD.md",
    "PROJECT_OWNERSHIP.md",
    "local.properties",
}

FORBIDDEN_TRACKED_PARTS = {".idea", ".gradle", ".cxx", "build", "__pycache__"}


def fail(message: str, errors: list[str]) -> None:
    errors.append(message)


def property_value(name: str) -> str:
    props = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    match = re.search(rf"^{re.escape(name)}=(.+)$", props, re.MULTILINE)
    return match.group(1).strip() if match else ""


def tracked_paths() -> list[Path]:
    git_dir = ROOT / ".git"
    if git_dir.exists():
        result = subprocess.run(
            ["git", "-C", str(ROOT), "ls-files", "-z"],
            check=True,
            stdout=subprocess.PIPE,
        )
        return [Path(item.decode("utf-8")) for item in result.stdout.split(b"\0") if item]

    paths: list[Path] = []
    for path in ROOT.rglob("*"):
        relative = path.relative_to(ROOT)
        if any(part in FORBIDDEN_TRACKED_PARTS or part == ".git" for part in relative.parts):
            continue
        if path.is_file():
            paths.append(relative)
    return paths


def main() -> int:
    errors: list[str] = []

    for relative in REQUIRED:
        path = ROOT / relative
        if not path.is_file() or path.stat().st_size == 0:
            fail(f"Missing or empty required file: {relative}", errors)

    tracked = tracked_paths()
    tracked_text = {path.as_posix() for path in tracked}

    for name in OBSOLETE_TRACKED:
        if name in tracked_text:
            fail(f"Obsolete/private file is tracked by Git: {name}", errors)

    for relative in tracked:
        if any(part in FORBIDDEN_TRACKED_PARTS for part in relative.parts):
            fail(f"Generated/IDE path is tracked by Git: {relative}", errors)
        if relative.suffix.lower() == ".nb":
            fail(f"Generated OCR model must not be tracked: {relative}", errors)

    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    if "Apache License 2.0" not in readme:
        fail("README does not state the Apache-2.0 license.", errors)
    if "com.github.g-o-d-v:android-pdf-search-engine" not in readme:
        fail("README does not contain the expected JitPack coordinate.", errors)
    if "sample" not in readme or "不会进入 Release AAR" not in readme:
        fail("README must explain that sample is not published in the AAR.", errors)

    version = property_value("VERSION_NAME")
    if not version:
        fail("VERSION_NAME is missing from gradle.properties.", errors)
    if property_value("GROUP") != "com.github.g-o-d-v":
        fail("GROUP must be com.github.g-o-d-v for this GitHub account.", errors)
    if property_value("POM_ARTIFACT_ID") != "android-pdf-search-engine":
        fail("POM_ARTIFACT_ID must match the GitHub repository name.", errors)

    gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
    for pattern in (".idea/", "local.properties", ".cxx/", "**/build/", "*.jks"):
        if pattern not in gitignore:
            fail(f".gitignore is missing: {pattern}", errors)

    build_gradle = (ROOT / "build.gradle").read_text(encoding="utf-8")
    for marker in (
        "PP-OCRv4_mobile_det.tar.gz",
        "PP-OCRv4_mobile_rec.tar.gz",
        "id 'maven-publish'",
        "MavenPublication",
        "Apache License, Version 2.0",
        "dependsOn ':sample:assembleRelease'",
    ):
        if marker not in build_gradle:
            fail(f"build.gradle is missing expected publication/model marker: {marker}", errors)

    stale_files = [
        "README.md",
        "CONTRIBUTING.md",
        "docs/ABI_EXPANSION.md",
        "docs/SAMPLE_PDF.md",
        ".github/workflows/ci.yml",
        "reset-native-build.bat",
        "build.gradle",
    ]
    for relative in stale_files:
        content = (ROOT / relative).read_text(encoding="utf-8")
        if "myapplication" in content:
            fail(f"Stale module name 'myapplication' remains in {relative}", errors)

    if errors:
        print("JitPack preflight failed:")
        for error in errors:
            print(f"  - {error}")
        return 1

    print("JitPack preflight passed.")
    print(f"  Version: {version}")
    print("  Coordinate: com.github.g-o-d-v:android-pdf-search-engine:" + version)
    print("  License: Apache-2.0")
    print("  Published module: root Android Library")
    print("  Sample module: not included in AAR")
    print("  Generated .nb models: packaged at publication time, not tracked")
    return 0


if __name__ == "__main__":
    sys.exit(main())

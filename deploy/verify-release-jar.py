#!/usr/bin/env python3
"""Check the released bytes and version before they enter a runner image.

Authenticity is checked separately with gh attestation verify in the workflow.
"""

import hashlib
from pathlib import Path
import re
import sys
import zipfile


def verify(jar: Path, checksums: Path, version: str) -> None:
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("expected a stable release version")
    if jar.name != f"faultora-{version}.jar" or jar.is_symlink() or not jar.is_file():
        raise ValueError("expected the versioned release JAR")
    entries = [line.split() for line in checksums.read_text(encoding="utf-8").splitlines()]
    matches = [entry for entry in entries if len(entry) >= 2 and entry[1] == jar.name]
    if len(matches) != 1 or len(matches[0]) != 2 or not re.fullmatch(r"[a-fA-F0-9]{64}", matches[0][0]):
        raise ValueError("expected exactly one SHA-256 entry for the JAR")
    digest = hashlib.sha256()
    with jar.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != matches[0][0].lower():
        raise ValueError("release JAR checksum mismatch")
    with zipfile.ZipFile(jar) as archive:
        try:
            info = archive.getinfo("META-INF/MANIFEST.MF")
        except KeyError as error:
            raise ValueError("release JAR has no manifest") from error
        if info.file_size > 65536:
            raise ValueError("release JAR manifest is too large")
        manifest = archive.read(info).decode("utf-8")
    versions = [line.removeprefix("Implementation-Version: ").strip()
                for line in manifest.splitlines() if line.startswith("Implementation-Version: ")]
    if versions != [version]:
        raise ValueError("release JAR version does not match the tag")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("usage: verify-release-jar.py JAR SHA256SUMS VERSION")
    try:
        verify(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3])
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        raise SystemExit(f"Release JAR verification failed: {error}") from error
    print("Release JAR checksum and version verified")

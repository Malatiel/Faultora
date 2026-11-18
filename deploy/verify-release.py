#!/usr/bin/env python3
"""Verify a downloaded Faultora release file before using it.

Two questions, answered in order. Are these the bytes the release lists? The
file must have exactly one SHA-256 entry in the release's SHA256SUMS. A JAR or
SBOM must also identify the release version. Did this project's release workflow
build them for this tag? GitHub's attestation for the file must have been signed by
.github/workflows/release.yml, from refs/tags/<tag>, on a GitHub-hosted runner.

A checksum alone detects a damaged download; anyone who can replace the file
can replace SHA256SUMS beside it. The attestation is what ties the bytes to
this repository's release, so it is checked unless --checksum-only is given,
and that mode says it has not checked authenticity. --require-sums-attestation
also checks the release workflow attestation for SHA256SUMS itself.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

DEFAULT_REPO = "Malatiel/Faultora"


def release_names(version: str):
    return {f"faultora-{version}.jar", f"faultora-{version}.zip", f"faultora-{version}.tar.gz",
            f"faultora-{version}-sbom.json", "LICENSE"}


def version_of(tag: str) -> str:
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
        raise ValueError("expected a stable release tag such as v1.2.3")
    return tag[1:]


def verify_checksum(artifact: Path, checksums: Path, tag: str) -> None:
    version = version_of(tag)
    if artifact.name not in release_names(version) or artifact.is_symlink() or not artifact.is_file():
        raise ValueError("expected a file published with release " + tag)
    entries = [line.split() for line in checksums.read_text(encoding="utf-8").splitlines()]
    matches = [entry for entry in entries if len(entry) >= 2 and entry[1] == artifact.name]
    if len(matches) != 1 or len(matches[0]) != 2 or not re.fullmatch(r"[a-fA-F0-9]{64}", matches[0][0]):
        raise ValueError("expected exactly one SHA-256 entry for " + artifact.name)
    digest = hashlib.sha256()
    with artifact.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != matches[0][0].lower():
        raise ValueError("checksum mismatch: " + artifact.name + " is not the file the release lists")
    if artifact.suffix == ".jar":
        verify_jar_version(artifact, version)
    elif artifact.name == f"faultora-{version}-sbom.json":
        verify_sbom_identity(artifact, version)


def verify_jar_version(jar: Path, version: str) -> None:
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


def verify_sbom_identity(sbom: Path, version: str) -> None:
    try:
        bom = json.loads(sbom.read_text(encoding="utf-8"))
    except (ValueError, RecursionError) as error:
        raise ValueError("release SBOM is not valid CycloneDX JSON") from error
    spec_version = bom.get("specVersion") if isinstance(bom, dict) else None
    if (not isinstance(spec_version, str) or not re.fullmatch(r"[1-9][0-9]*\.[0-9]+", spec_version)
            or bom.get("bomFormat") != "CycloneDX"):
        raise ValueError("release SBOM is not a CycloneDX JSON document")

    metadata = bom.get("metadata")
    component = metadata.get("component") if isinstance(metadata, dict) else None
    if (not isinstance(component, dict)
            or (component.get("group"), component.get("name"), component.get("version"))
            != ("dev.faultora", "faultora-parent", version)):
        raise ValueError("release SBOM does not describe Faultora " + version)

    components = bom.get("components")
    if not isinstance(components, list) or not components:
        raise ValueError("release SBOM must contain a nonempty component inventory")
    for entry in components:
        if (not isinstance(entry, dict)
                or not all(isinstance(value, str) and value.strip()
                           for value in (entry.get("name"), entry.get("type")))):
            raise ValueError("release SBOM component inventory entries require a name and type")


def attestation_command(artifact: Path, tag: str, repo: str, source_digest=None):
    command = ["gh", "attestation", "verify", str(artifact), "--repo", repo,
               "--signer-workflow", repo + "/.github/workflows/release.yml",
               "--source-ref", "refs/tags/" + tag, "--deny-self-hosted-runners"]
    if source_digest:
        command += ["--source-digest", source_digest]
    return command


def verify_attestation(artifact: Path, tag: str, repo: str, source_digest=None, run=subprocess.run) -> None:
    try:
        result = run(attestation_command(artifact, tag, repo, source_digest), check=False)
    except FileNotFoundError as error:
        raise ValueError("GitHub CLI (gh) is needed to verify the attestation; "
                         "install it, or use --checksum-only and accept an unauthenticated file") from error
    if result.returncode != 0:
        raise ValueError("attestation refused: " + artifact.name + " was not built by " + repo
                         + "'s release workflow for " + tag)


def verify(artifact: Path, checksums: Path, tag: str, repo: str = DEFAULT_REPO,
           checksum_only: bool = False, source_digest=None, run=subprocess.run, *,
           require_sums_attestation: bool = False) -> None:
    if require_sums_attestation and checksum_only:
        raise ValueError("cannot combine --require-sums-attestation with --checksum-only")
    verify_checksum(artifact, checksums, tag)
    if require_sums_attestation:
        verify_attestation(checksums, tag, repo, source_digest, run)
    if not checksum_only:
        verify_attestation(artifact, tag, repo, source_digest, run)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("file", type=Path, help="downloaded release file")
    parser.add_argument("--sums", type=Path, required=True, help="SHA256SUMS from the same release")
    parser.add_argument("--tag", required=True, help="release tag, for example v0.13.0")
    parser.add_argument("--repo", default=DEFAULT_REPO)
    parser.add_argument("--source-digest", help="also require this source commit")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--checksum-only", action="store_true",
                      help="skip the attestation; detects damage, not substitution")
    mode.add_argument("--require-sums-attestation", action="store_true",
                      help="also require a release workflow attestation for SHA256SUMS")
    args = parser.parse_args()
    try:
        verify(args.file, args.sums, args.tag, args.repo, args.checksum_only, args.source_digest,
               require_sums_attestation=args.require_sums_attestation)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print("Release verification failed: " + str(error), file=sys.stderr)
        return 1
    if args.checksum_only:
        print(args.file.name + ": checksum matches; authenticity NOT verified (--checksum-only)")
    elif args.require_sums_attestation:
        print(args.file.name + ": checksum matches and the release workflow attested both files for " + args.tag)
    else:
        print(args.file.name + ": checksum matches and the release workflow attested it for " + args.tag)
    return 0


if __name__ == "__main__":
    sys.exit(main())

import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("verify_release", Path(__file__).with_name("verify-release.py"))
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)

TAG = "v1.2.3"


class Attestation:
    """Stands in for gh: records each command and answers with a fixed exit code."""

    def __init__(self, exit_code=0):
        self.exit_code = exit_code
        self.commands = []

    def __call__(self, command, check):
        self.commands.append(command)
        return subprocess.CompletedProcess(command, self.exit_code)


class ReleaseVerificationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.jar = self.root / "faultora-1.2.3.jar"
        self.archive = self.root / "faultora-1.2.3.tar.gz"
        self.sbom = self.root / "faultora-1.2.3-sbom.json"
        self.bom = {
            "bomFormat": "CycloneDX",
            "specVersion": "1.6",
            "metadata": {"component": {"group": "dev.faultora", "name": "faultora-parent", "version": "1.2.3"}},
            "components": [{"type": "library", "name": "synthetic-component", "version": "1.0.0"}],
        }
        self.sums = self.root / "SHA256SUMS"
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Version: 1.2.3\r\n\r\n")
        self.archive.write_bytes(b"synthetic archive bytes")
        self.checksum()

    def checksum(self, *files):
        files = files or (self.jar, self.archive)
        self.sums.write_text("".join(
            f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in files), encoding="utf-8")

    def test_a_listed_file_the_release_workflow_attested_is_accepted(self):
        for artifact in (self.jar, self.archive):
            attestation = Attestation()
            VERIFIER.verify(artifact, self.sums, TAG, run=attestation)
            self.assertEqual(len(attestation.commands), 1)

    def test_the_attestation_must_come_from_the_release_workflow_for_this_tag(self):
        attestation = Attestation()
        VERIFIER.verify(self.archive, self.sums, TAG, run=attestation)
        command = attestation.commands[0]
        self.assertEqual(command[:4], ["gh", "attestation", "verify", str(self.archive)])
        for flag, value in (("--repo", "Malatiel/Faultora"),
                            ("--signer-workflow", "Malatiel/Faultora/.github/workflows/release.yml"),
                            ("--source-ref", "refs/tags/v1.2.3")):
            self.assertEqual(command[command.index(flag) + 1], value)
        self.assertIn("--deny-self-hosted-runners", command)

    def test_manifest_and_artifact_use_the_same_attestation_requirements(self):
        attestation = Attestation()
        VERIFIER.verify(self.archive, self.sums, TAG, repo="example/project", source_digest="abc123",
                        run=attestation, require_sums_attestation=True)
        self.assertEqual([command[3] for command in attestation.commands], [str(self.sums), str(self.archive)])
        for command in attestation.commands:
            self.assertEqual(command[:3], ["gh", "attestation", "verify"])
            self.assertEqual(command[4:], [
                "--repo", "example/project",
                "--signer-workflow", "example/project/.github/workflows/release.yml",
                "--source-ref", "refs/tags/v1.2.3",
                "--deny-self-hosted-runners", "--source-digest", "abc123",
            ])

    def test_manifest_attestation_failure_refuses_the_release(self):
        attestation = Attestation(exit_code=1)
        with self.assertRaisesRegex(ValueError, "attestation refused: SHA256SUMS"):
            VERIFIER.verify(self.archive, self.sums, TAG, run=attestation, require_sums_attestation=True)
        self.assertEqual([command[3] for command in attestation.commands], [str(self.sums)])

    def test_a_valid_manifest_attestation_does_not_replace_the_artifact_attestation(self):
        attestation = Attestation()

        def attest_manifest_only(command, check):
            attestation(command, check)
            return subprocess.CompletedProcess(command, int(command[3] != str(self.sums)))

        with self.assertRaisesRegex(ValueError, "attestation refused: faultora-1.2.3.tar.gz"):
            VERIFIER.verify(self.archive, self.sums, TAG, run=attest_manifest_only, require_sums_attestation=True)
        self.assertEqual([command[3] for command in attestation.commands], [str(self.sums), str(self.archive)])

    def test_manifest_attestation_conflicts_with_checksum_only(self):
        attestation = Attestation()
        with self.assertRaisesRegex(ValueError, "cannot combine.*checksum-only"):
            VERIFIER.verify(self.archive, self.sums, TAG, run=attestation, checksum_only=True,
                            require_sums_attestation=True)
        self.assertEqual(attestation.commands, [])

        result = subprocess.run(
            [sys.executable, str(VERIFIER.__file__), str(self.archive), "--sums", str(self.sums), "--tag", TAG,
             "--checksum-only", "--require-sums-attestation"],
            capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 2)
        self.assertIn("not allowed with argument --checksum-only", result.stderr)

    def test_a_tampered_file_is_refused_before_any_attestation_is_asked_for(self):
        self.archive.write_bytes(self.archive.read_bytes() + b"tampered")
        attestation = Attestation()
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            VERIFIER.verify(self.archive, self.sums, TAG, run=attestation)
        self.assertEqual(attestation.commands, [])

    def test_accepts_a_listed_cyclonedx_sbom_and_checks_its_attestation(self):
        self.write_sbom(json.dumps(self.bom))
        attestation = Attestation()
        VERIFIER.verify(self.sbom, self.sums, TAG, run=attestation)
        self.assertEqual(len(attestation.commands), 1)

    def test_refuses_an_sbom_for_a_different_project_or_version(self):
        component = self.bom["metadata"]["component"]
        for field, value in (("group", "example"), ("name", "other-parent"), ("version", "9.9.9")):
            with self.subTest(field=field):
                expected = component[field]
                component[field] = value
                self.write_sbom(json.dumps(self.bom))
                component[field] = expected
                attestation = Attestation()
                with self.assertRaisesRegex(ValueError, "SBOM.*does not describe"):
                    VERIFIER.verify(self.sbom, self.sums, TAG, run=attestation)
                self.assertEqual(attestation.commands, [])

    def test_refuses_non_cyclonedx_or_invalid_component_inventory(self):
        cases = (
            ("not JSON", "CycloneDX"),
            ("[]", "CycloneDX"),
            (json.dumps({**self.bom, "bomFormat": "SPDX"}), "CycloneDX"),
            (json.dumps({**self.bom, "specVersion": 1.6}), "CycloneDX"),
            (json.dumps({**self.bom, "metadata": []}), "SBOM.*does not describe"),
            (json.dumps({**self.bom, "components": {}}), "component inventory"),
            (json.dumps({**self.bom, "components": []}), "component inventory"),
            (json.dumps({**self.bom, "components": [{}]}), "component inventory"),
            (json.dumps({**self.bom, "components": [{"type": "library", "name": ""}]}), "component inventory"),
            (json.dumps({**self.bom, "components": [{"type": False, "name": "synthetic"}]}), "component inventory"),
        )
        for payload, reason in cases:
            with self.subTest(payload=payload):
                self.write_sbom(payload)
                with self.assertRaisesRegex(ValueError, reason):
                    VERIFIER.verify(self.sbom, self.sums, TAG, checksum_only=True)

    def write_sbom(self, payload):
        self.sbom.write_text(payload, encoding="utf-8")
        self.checksum(self.sbom)

    def test_a_file_with_a_matching_checksum_but_no_attestation_from_this_release_is_refused(self):
        # A replaced file arrives with a replaced SHA256SUMS: the checksum then
        # matches, and only the attestation can tell it was not built here.
        self.archive.write_bytes(b"built somewhere else")
        self.checksum(self.jar, self.archive)
        with self.assertRaisesRegex(ValueError, "attestation refused.*release workflow for v1.2.3"):
            VERIFIER.verify(self.archive, self.sums, TAG, run=Attestation(exit_code=1))

    def test_checksum_only_never_asks_for_an_attestation(self):
        attestation = Attestation(exit_code=1)
        VERIFIER.verify(self.archive, self.sums, TAG, checksum_only=True, run=attestation)
        self.assertEqual(attestation.commands, [])

    def test_a_missing_gh_says_how_to_proceed(self):
        def absent(command, check):
            raise FileNotFoundError("gh")
        with self.assertRaisesRegex(ValueError, "GitHub CLI.*--checksum-only"):
            VERIFIER.verify(self.archive, self.sums, TAG, run=absent)

    def test_a_source_digest_is_passed_through_when_required(self):
        attestation = Attestation()
        VERIFIER.verify(self.archive, self.sums, TAG, source_digest="abc123", run=attestation)
        command = attestation.commands[0]
        self.assertEqual(command[command.index("--source-digest") + 1], "abc123")

    def test_refuses_duplicate_or_missing_checksum_entries(self):
        self.sums.write_text(self.sums.read_text() * 2, encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "exactly one"):
            VERIFIER.verify(self.jar, self.sums, TAG, run=Attestation())
        self.sums.write_text("", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "exactly one"):
            VERIFIER.verify(self.jar, self.sums, TAG, run=Attestation())

    def test_refuses_a_jar_of_another_version_even_with_a_matching_checksum(self):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Implementation-Version: 9.9.9\n")
        self.checksum()
        with self.assertRaisesRegex(ValueError, "version does not match"):
            VERIFIER.verify(self.jar, self.sums, TAG, run=Attestation())

    def test_refuses_a_jar_without_a_manifest_or_that_is_not_a_zip(self):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("some-file", "synthetic")
        self.checksum()
        with self.assertRaisesRegex(ValueError, "no manifest"):
            VERIFIER.verify(self.jar, self.sums, TAG, run=Attestation())
        self.jar.write_bytes(b"not a zip")
        self.checksum()
        with self.assertRaises(zipfile.BadZipFile):
            VERIFIER.verify(self.jar, self.sums, TAG, run=Attestation())

    def test_refuses_a_file_that_is_not_part_of_this_release(self):
        with self.assertRaisesRegex(ValueError, "published with release v1.2.4"):
            VERIFIER.verify(self.jar, self.sums, "v1.2.4", run=Attestation())

    def test_refuses_a_tag_that_is_not_a_stable_release(self):
        for tag in ("1.2.3", "v1.2.3-rc1", "../other"):
            with self.assertRaisesRegex(ValueError, "stable release tag"):
                VERIFIER.verify(self.jar, self.sums, tag, run=Attestation())


if __name__ == "__main__":
    unittest.main()

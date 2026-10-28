import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("verify_release_jar", Path(__file__).with_name("verify-release-jar.py"))
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class ReleasedJarTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.jar = self.root / "faultora-1.2.3.jar"
        self.sums = self.root / "SHA256SUMS"
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Version: 1.2.3\r\n\r\n")
        self.checksum()

    def checksum(self):
        digest = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        self.sums.write_text(f"{digest}  {self.jar.name}\n", encoding="utf-8")

    def test_accepts_only_matching_bytes_and_version(self):
        VERIFIER.verify(self.jar, self.sums, "1.2.3")

    def test_refuses_changed_jar(self):
        self.jar.write_bytes(self.jar.read_bytes() + b"changed")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            VERIFIER.verify(self.jar, self.sums, "1.2.3")

    def test_refuses_duplicate_checksum_entry(self):
        self.sums.write_text(self.sums.read_text() * 2, encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "exactly one"):
            VERIFIER.verify(self.jar, self.sums, "1.2.3")

    def test_refuses_missing_checksum_entry(self):
        self.sums.write_text("", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "exactly one"):
            VERIFIER.verify(self.jar, self.sums, "1.2.3")

    def test_refuses_matching_checksum_of_wrong_version(self):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Implementation-Version: 9.9.9\n")
        self.checksum()
        with self.assertRaisesRegex(ValueError, "version does not match"):
            VERIFIER.verify(self.jar, self.sums, "1.2.3")

    def test_refuses_non_jar_even_with_matching_checksum(self):
        self.jar.write_bytes(b"not a zip")
        self.checksum()
        with self.assertRaises(zipfile.BadZipFile):
            VERIFIER.verify(self.jar, self.sums, "1.2.3")

    def test_refuses_missing_manifest(self):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("some-file", "synthetic")
        self.checksum()
        with self.assertRaisesRegex(ValueError, "no manifest"):
            VERIFIER.verify(self.jar, self.sums, "1.2.3")

    def test_refuses_wrong_artifact_name(self):
        with self.assertRaisesRegex(ValueError, "versioned release JAR"):
            VERIFIER.verify(self.jar, self.sums, "1.2.4")

    def test_refuses_non_version_input(self):
        with self.assertRaisesRegex(ValueError, "stable release version"):
            VERIFIER.verify(self.jar, self.sums, "../other")


if __name__ == "__main__":
    unittest.main()

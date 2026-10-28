"""The installation guide's command blocks stop at a refused check.

They are run as written, with gh, tar and the archive replaced by stubs that
leave a marker. A refused checksum or attestation, or no gh at all, must not
reach extraction or the launcher; when every check passes, the launcher runs.
"""

import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

GUIDE = Path(__file__).resolve().parent.parent / "docs" / "INSTALLATION.md"
VERSION = re.search(r"FAULTORA_VERSION=([0-9.]+)", GUIDE.read_text(encoding="utf-8")).group(1)


def block(section, language):
    text = GUIDE.read_text(encoding="utf-8")
    start = text.index("## " + section)
    match = re.compile(r"```" + language + r"\n(.*?)```", re.S).search(text, start)
    return match.group(1)


class Case:
    """A directory holding a downloaded archive, its SHA256SUMS and stub tools."""

    def __init__(self, archive_name, checksum_matches=True):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.tools = self.root / "tools"
        self.tools.mkdir()
        archive = self.root / archive_name
        archive.write_bytes(b"synthetic archive")
        digest = hashlib.sha256(archive.read_bytes() if checksum_matches else b"other").hexdigest()
        (self.root / "SHA256SUMS").write_text(f"{digest}  {archive_name}\n", encoding="utf-8")

    def marker(self, name):
        return (self.root / name).exists()

    def close(self):
        self.directory.cleanup()


@unittest.skipUnless(shutil.which("bash") and shutil.which("sha256sum") and shutil.which("grep"),
                     "needs bash, sha256sum and grep")
class LinuxBlockTest(unittest.TestCase):
    def run_block(self, gh_exit=0, gh_present=True, checksum_matches=True):
        case = Case(f"faultora-{VERSION}.tar.gz", checksum_matches)
        self.addCleanup(case.close)
        # PATH holds only these wrappers, so a gh installed on the machine
        # cannot stand in for the missing one.
        for tool in ("grep", "sha256sum", "mkdir", "cat", "chmod"):
            self.wrapper(case, tool, f'exec "{shutil.which(tool)}" "$@"')
        if gh_present:
            self.wrapper(case, "gh", f"exit {gh_exit}")
        home = f"faultora-{VERSION}"
        root = case.root.as_posix()
        launcher = case.tools / "launcher"
        launcher.write_text("#!" + shutil.which("bash") + f'\n: > "{root}/LAUNCHED"\n', encoding="utf-8")
        self.wrapper(case, "cp", f'exec "{shutil.which("cp")}" "$@"')
        self.wrapper(case, "tar", "\n".join([
            f': > "{root}/EXTRACTED"',
            f'mkdir -p "{home}/bin"',
            f'cp "{launcher.as_posix()}" "{home}/bin/faultora"',
            f'chmod +x "{home}/bin/faultora"']))
        result = subprocess.run([shutil.which("bash"), "-c", block("Linux", "sh")], cwd=case.root,
                                env={"PATH": str(case.tools), "HOME": str(case.root)},
                                capture_output=True, text=True, check=False)
        return case, result

    @staticmethod
    def wrapper(case, name, body):
        path = case.tools / name
        path.write_text("#!" + shutil.which("bash") + "\n" + body + "\n", encoding="utf-8")
        path.chmod(0o755)

    def test_a_verified_archive_is_extracted_and_run(self):
        case, result = self.run_block()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(case.marker("EXTRACTED") and case.marker("LAUNCHED"))

    def test_a_checksum_mismatch_stops_before_extraction(self):
        case, result = self.run_block(checksum_matches=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(case.marker("EXTRACTED") or case.marker("LAUNCHED"))

    def test_a_refused_attestation_stops_before_extraction(self):
        case, result = self.run_block(gh_exit=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(case.marker("EXTRACTED") or case.marker("LAUNCHED"))

    def test_a_missing_gh_stops_before_extraction(self):
        case, result = self.run_block(gh_present=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(case.marker("EXTRACTED") or case.marker("LAUNCHED"))


@unittest.skipUnless(sys.platform == "win32" and shutil.which("powershell"), "needs Windows PowerShell")
class PowerShellBlockTest(unittest.TestCase):
    def run_block(self, gh_exit=0, gh_present=True, checksum_matches=True):
        case = Case(f"faultora-{VERSION}.zip", checksum_matches)
        self.addCleanup(case.close)
        home = case.root / f"faultora-{VERSION}"
        stubs = [
            # Functions take precedence over cmdlets and applications, so these
            # replace the real Expand-Archive and gh for the block that follows.
            "function Expand-Archive { New-Item -ItemType File -Path '%s' | Out-Null;"
            " New-Item -ItemType Directory -Path '%s' | Out-Null;"
            " Set-Content -LiteralPath '%s' -Value '@echo off& type nul > \"%s\"' }"
            % (case.root / "EXTRACTED", home / "bin", home / "bin" / "faultora.cmd", case.root / "LAUNCHED"),
        ]
        if gh_present:
            stubs.append("function gh { $global:LASTEXITCODE = %d }" % gh_exit)
        else:
            stubs.append("function gh { throw [System.Management.Automation.CommandNotFoundException]::new('gh') }")
        script = "\n".join(stubs) + "\n" + block("Windows PowerShell", "powershell")
        result = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                                cwd=case.root, capture_output=True, text=True, check=False)
        return case, result

    def test_a_verified_archive_is_extracted_and_run(self):
        case, result = self.run_block()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(case.marker("EXTRACTED") and case.marker("LAUNCHED"))

    def test_a_checksum_mismatch_stops_before_extraction(self):
        case, _ = self.run_block(checksum_matches=False)
        self.assertFalse(case.marker("EXTRACTED") or case.marker("LAUNCHED"))

    def test_a_refused_attestation_stops_before_extraction(self):
        case, _ = self.run_block(gh_exit=1)
        self.assertFalse(case.marker("EXTRACTED") or case.marker("LAUNCHED"))

    def test_a_missing_gh_stops_before_extraction(self):
        case, _ = self.run_block(gh_present=False)
        self.assertFalse(case.marker("EXTRACTED") or case.marker("LAUNCHED"))


if __name__ == "__main__":
    unittest.main()

import os
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from verify_qualification import REQUIRED, verify_reports


class QualificationReportTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.reports = self.root / "module/target/surefire-reports"
        self.reports.mkdir(parents=True)
        for name, count in REQUIRED.items():
            self.report(name, count)

    def report(self, name, tests=1, skipped=0, failures=0):
        suite = ET.Element("testsuite", name=name, tests=str(tests), failures=str(failures),
                           errors="0", skipped=str(skipped))
        for number in range(tests):
            ET.SubElement(suite, "testcase", name="synthetic" + str(number))
        path = self.reports / ("TEST-" + name + ".xml")
        ET.ElementTree(suite).write(path)
        return path

    def test_complete_gate_passes(self):
        self.assertEqual(verify_reports(self.root, 0), (7, 27))

    def test_absent_required_suite_fails(self):
        self.report("dev.faultora.integration.EventE2ETest", 0)
        with self.assertRaisesRegex(ValueError, "Required qualification cases missing"):
            verify_reports(self.root, 0)

    def test_new_optional_skip_cannot_silently_join_strict_gate(self):
        self.report("synthetic.NewDockerSuite", skipped=1)
        with self.assertRaisesRegex(ValueError, "skipped qualification suite"):
            verify_reports(self.root, 0)

    def test_failure_and_contradictory_case_counts_fail(self):
        path = self.report("synthetic.FailedSuite", failures=1)
        with self.assertRaisesRegex(ValueError, "Failed, errored"):
            verify_reports(self.root, 0)
        path.write_text('<testsuite name="synthetic" tests="1" failures="0" errors="0" skipped="0"/>')
        with self.assertRaisesRegex(ValueError, "count mismatch"):
            verify_reports(self.root, 0)

    def test_stale_report_cannot_prove_current_run(self):
        path = self.report("synthetic.StaleSuite")
        os.utime(path, (1, 1))
        with self.assertRaisesRegex(ValueError, "Stale qualification report"):
            verify_reports(self.root, 2)

    def test_malformed_xml_fails(self):
        path = self.report("synthetic.BrokenSuite")
        path.write_text("<invalid")
        with self.assertRaises(ET.ParseError):
            verify_reports(self.root, 0)


if __name__ == "__main__":
    unittest.main()

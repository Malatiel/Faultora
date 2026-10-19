"""Fail the Linux qualification gate on missing, stale, skipped or failed tests."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


REQUIRED = {
    "dev.faultora.integration.CrossComponentE2ETest": 8,
    "dev.faultora.integration.EventE2ETest": 3,
    "dev.faultora.integration.KafkaPayloadPolicyE2ETest": 5,
    "dev.faultora.integration.RemoteCrossComponentE2ETest": 4,
    "dev.faultora.integration.RunnerImageE2ETest": 3,
    "dev.faultora.cli.RunnerIsolationTest": 2,
    "dev.faultora.cli.RunnerShutdownTest": 2,
}


def verify_reports(root, since):
    suites = {}
    for path in sorted(Path(root).glob("**/target/surefire-reports/TEST-*.xml")):
        if path.stat().st_mtime < since:
            raise ValueError("Stale qualification report; run clean verify")
        suite = ET.parse(path).getroot()
        if suite.tag != "testsuite":
            raise ValueError("Expected a Surefire testsuite")
        name = suite.attrib["name"]
        if name in suites:
            raise ValueError("Duplicate qualification suite: " + name)
        counts = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
        if any(value < 0 for value in counts.values()):
            raise ValueError("Invalid qualification test count: " + name)
        if counts["failures"] or counts["errors"] or counts["skipped"]:
            raise ValueError("Failed, errored or skipped qualification suite: " + name)
        if suite.findall(".//skipped") or suite.findall(".//failure") or suite.findall(".//error"):
            raise ValueError("Unsuccessful qualification case: " + name)
        if len(suite.findall("testcase")) != counts["tests"]:
            raise ValueError("Qualification case count mismatch: " + name)
        suites[name] = counts["tests"]
    for name, minimum in REQUIRED.items():
        if suites.get(name, 0) < minimum:
            raise ValueError("Required qualification cases missing: " + name)
    return len(suites), sum(suites.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--since", type=float, required=True, help="Unix seconds before clean verify started")
    args = parser.parse_args()
    try:
        suites, cases = verify_reports(args.root, args.since)
    except (ValueError, KeyError, OSError, ET.ParseError) as failure:
        print("Qualification report gate failed: " + str(failure), file=sys.stderr)
        return 1
    print(f"Qualification report gate passed: {suites} suites, {cases} cases, 0 skips")
    return 0


if __name__ == "__main__":
    sys.exit(main())

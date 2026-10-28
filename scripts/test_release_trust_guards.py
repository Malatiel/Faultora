"""The trust boundaries of image publication, held in place by their text.

Each guard here answered a review finding: code from an unverified tag ran
before the release attestation was checked; the attested image digest was
looked up by a tag another writer could move; and an image built from any
branch would have passed the documented verification.
"""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = (ROOT / ".github" / "workflows" / "runner-image.yml").read_text(encoding="utf-8")


def step(name):
    """The text of one workflow step, from its name to the next step."""
    start = WORKFLOW.index("- name: " + name)
    following = WORKFLOW.find("\n      - ", start + 1)
    return WORKFLOW[start:following if following != -1 else len(WORKFLOW)]


class RunnerImageTrustTest(unittest.TestCase):
    def test_the_release_attestation_is_checked_before_any_code_from_the_tag_runs(self):
        verify = step("Verify and obtain the released JAR")
        gate = verify.index("gh attestation verify")
        self.assertLess(gate, verify.index("python3"), "tag code would run before the trust gate")
        command = verify[gate:verify.index("\n\n", gate) if "\n\n" in verify[gate:] else len(verify)]
        for required in ('--signer-workflow "$GITHUB_REPOSITORY/.github/workflows/release.yml"',
                         '--source-ref "refs/tags/$RELEASE_TAG"', '--source-digest "$revision"',
                         "--deny-self-hosted-runners"):
            self.assertIn(required, command)

    def test_publication_is_refused_unless_dispatched_from_main(self):
        guard = step("Publish only from main")
        self.assertIn('"$GITHUB_REF" != "refs/heads/main"', guard)
        self.assertIn("exit 1", guard)
        self.assertLess(WORKFLOW.index("- name: Publish only from main"),
                        WORKFLOW.index("uses: actions/checkout"))

    def test_the_attested_digest_is_the_one_this_run_pushed(self):
        publish = step("Publish the two-platform index")
        self.assertIn('--metadata-file "$metadata"', publish)
        self.assertIn('containerimage.digest', publish)
        self.assertIn('imagetools inspect "$IMAGE@$digest"', publish)
        self.assertNotRegex(publish, r'imagetools inspect "\$IMAGE:\$VERSION"',
                            "a tag lookup could return someone else's image")
        self.assertIn("digest: ${{ steps.publish.outputs.digest }}", WORKFLOW)

    def test_the_documented_image_check_accepts_only_main(self):
        guide = (ROOT / "docs" / "RUNNER_IMAGE.md").read_text(encoding="utf-8")
        command = re.search(r"gh attestation verify oci://.*?```", guide, re.S).group(0)
        self.assertIn("--signer-workflow Malatiel/Faultora/.github/workflows/runner-image.yml", command)
        self.assertIn("--source-ref refs/heads/main", command)
        self.assertIn("--deny-self-hosted-runners", command)

    def test_the_documented_download_checks_pin_the_release_workflow_and_tag(self):
        guide = (ROOT / "docs" / "INSTALLATION.md").read_text(encoding="utf-8")
        commands = re.findall(r"gh attestation verify .*?--deny-self-hosted-runners", guide, re.S)
        self.assertEqual(len(commands), 2, "one Linux and one PowerShell command")
        for command in commands:
            self.assertIn("--signer-workflow Malatiel/Faultora/.github/workflows/release.yml", command)
            self.assertIn("refs/tags/v", command)


if __name__ == "__main__":
    unittest.main()

"""Every input that runs in a workflow or builds the image is pinned.

A tag can be moved or republished by whoever controls the action's or the
image's repository; a commit SHA or an image digest cannot. These checks keep
a new `uses:` line or an image reference from arriving unpinned.
"""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parent.parent
WORKFLOWS = sorted((ROOT / ".github" / "workflows").glob("*.yml"))
PINNED_ACTION = re.compile(r"^[\w.-]+/[\w./-]+@[0-9a-f]{40} # v\d+\.\d+\.\d+$")
PINNED_IMAGE = re.compile(r"^[\w./-]+:[\w.-]+@sha256:[0-9a-f]{64}$")


def uses_lines():
    for workflow in WORKFLOWS:
        for number, line in enumerate(workflow.read_text(encoding="utf-8").splitlines(), 1):
            match = re.match(r"^\s*(?:- )?uses:\s*(.+?)\s*$", line)
            if match:
                yield f"{workflow.name}:{number}", match.group(1)


class PinnedBuildInputsTest(unittest.TestCase):
    def test_every_action_is_pinned_to_a_commit_with_its_release_named(self):
        found = list(uses_lines())
        self.assertGreater(len(found), 0, "no uses: lines found; the parser is broken")
        for where, reference in found:
            with self.subTest(where=where):
                self.assertRegex(reference, PINNED_ACTION)

    def test_one_action_is_pinned_to_one_commit_everywhere(self):
        commits = {}
        for where, reference in uses_lines():
            repository = "/".join(reference.split("@")[0].split("/")[:2])
            commits.setdefault(repository, set()).add(reference.split("@")[1].split()[0])
        for repository, shas in commits.items():
            with self.subTest(repository=repository):
                self.assertEqual(len(shas), 1, "the same action at two commits")

    def test_the_runner_base_image_is_pinned_by_digest(self):
        dockerfile = (ROOT / "Dockerfile").read_text(encoding="utf-8")
        bases = re.findall(r"^FROM\s+(\S+)", dockerfile, re.M)
        self.assertEqual(len(bases), 1)
        self.assertRegex(bases[0], PINNED_IMAGE)

    def test_the_emulator_and_builder_that_publish_the_image_are_pinned(self):
        workflow = (ROOT / ".github" / "workflows" / "runner-image.yml").read_text(encoding="utf-8")
        emulator = re.search(r"^\s+image:\s*(\S+)$", workflow, re.M)
        builder = re.search(r"driver-opts:\s*image=(\S+)$", workflow, re.M)
        self.assertIsNotNone(emulator, "setup-qemu-action would pull binfmt:latest")
        self.assertIsNotNone(builder, "setup-buildx-action would pull buildx-stable-1")
        self.assertRegex(emulator.group(1), PINNED_IMAGE)
        self.assertRegex(builder.group(1), PINNED_IMAGE)

    def test_dependabot_proposes_updates_for_actions_and_the_base_image(self):
        config = (ROOT / ".github" / "dependabot.yml").read_text(encoding="utf-8")
        ecosystems = re.findall(r"package-ecosystem:\s*(\S+)", config)
        self.assertEqual(sorted(ecosystems), ["docker", "github-actions"])


if __name__ == "__main__":
    unittest.main()

"""Execute the workflow's Bash with a fake gh; no GitHub mutation or credentials."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASH = shutil.which("bash")
if os.name == "nt":
    BASH = "C:/Program Files/Git/bin/bash.exe"
STUB = r"""
gh() {
  printf '%s\n' "$*" >> "$STUB_LOG"
  local state
  state=$(cat "$STUB_STATE")
  case "$1 $2" in
    'release view')
      [[ "$state" != missing ]] || return 1
      if [[ "$*" == *isDraft* ]]; then
        if [[ "$state" == published ]]; then echo false; else echo true; fi
      elif [[ "$*" == *targetCommitish* ]]; then
        if [[ "$state" == wrong ]]; then echo other-commit; else echo "$GITHUB_SHA"; fi
      fi ;;
    'api repos/'*) return "${TAG_EXISTS:-1}" ;;
    'release create') echo draft > "$STUB_STATE" ;;
    'release upload') [[ "$state" == draft ]] ;;
    'release edit') echo published > "$STUB_STATE" ;;
    *) if [[ "$1" == api ]]; then return "${TAG_EXISTS:-1}"; fi; return 2 ;;
  esac
}
"""

class ReleaseWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = os.environ.copy()
        self.env.update(STUB_LOG=str(self.root / "calls").replace("\\", "/"),
                        STUB_STATE=str(self.root / "state").replace("\\", "/"),
                        GITHUB_OUTPUT=str(self.root / "output").replace("\\", "/"),
                        GITHUB_SHA="a" * 40, GITHUB_REPOSITORY="test/spyglass",
                        TAG="v2.0.0", GH_TOKEN="fake-test-token")
        (self.root / "state").write_text("missing")
        (self.root / "gradle.properties").write_text("version=2.0.0\n")

    def run_step(self, name, expected=0):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        section = workflow.split("      - name: " + name + "\n", 1)[1].split("\n      - ", 1)[0]
        script = textwrap.dedent(section.split("        run: |\n", 1)[1])
        result = subprocess.run([BASH, "-e", "-c", STUB + "\n" + script], cwd=self.root,
                                env=self.env, text=True, capture_output=True)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result

    def calls(self):
        path = self.root / "calls"
        return path.read_text() if path.exists() else ""

    def test_snapshot_skips_publication_without_gh_calls(self):
        (self.root / "gradle.properties").write_text("version=2.0.0-SNAPSHOT\n")
        self.run_step("Select release")
        self.assertIn("publish=false", (self.root / "output").read_text())
        self.assertEqual(self.calls(), "")

    def test_published_release_is_skipped(self):
        (self.root / "state").write_text("published")
        self.run_step("Select release")
        self.assertIn("publish=false", (self.root / "output").read_text())

    def test_new_release_uploads_all_assets_to_one_tag(self):
        self.run_step("Select release")
        self.assertIn("tag=v2.0.0", (self.root / "output").read_text())
        self.assertIn("publish=true", (self.root / "output").read_text())
        dist = self.root / "dist"
        dist.mkdir()
        for i in range(18):
            (dist / f"artifact-{i}.jar").write_bytes(b"fixture")
        (dist / "SHA256SUMS").write_text("fixture")
        self.run_step("Create or resume draft at this exact commit")
        self.run_step("Publish release")
        calls = self.calls()
        self.assertEqual(calls.count("release create "), 1)
        self.assertIn("--target " + "a" * 40, calls)
        upload = next(line for line in calls.splitlines() if line.startswith("release upload"))
        self.assertEqual(upload.count(".jar"), 18)
        self.assertIn("release edit v2.0.0 --draft=false --latest", calls)

    def test_wrong_commit_draft_cannot_upload_or_publish(self):
        (self.root / "state").write_text("wrong")
        self.run_step("Create or resume draft at this exact commit", expected=1)
        self.assertNotIn("release upload", self.calls())
        self.assertNotIn("release edit", self.calls())

    def test_matching_draft_resumes_without_recreating(self):
        (self.root / "state").write_text("draft")
        self.run_step("Create or resume draft at this exact commit")
        self.assertNotIn("release create", self.calls())
        self.assertIn("release upload", self.calls())

    def test_existing_tag_without_release_is_rejected(self):
        self.env["TAG_EXISTS"] = "0"
        self.run_step("Create or resume draft at this exact commit", expected=1)
        self.assertNotIn("release create", self.calls())

    def test_successful_build_covers_all_targets(self):
        (self.root / "gradlew").write_text(
            '#!/usr/bin/env bash\necho "$*" >> builds\n', newline="\n")
        (self.root / "gradlew").chmod(0o755)
        self.run_step("Build and test matching dependencies")
        builds = (self.root / "builds").read_text().splitlines()
        self.assertEqual(len(builds), 3)
        for target, command in zip(("26.1.2", "26.2", "26.3"), builds):
            self.assertIn("-PminecraftTarget=" + target, command)

    def test_central_without_credentials_is_skipped(self):
        self.env["CENTRAL_PORTAL_USERNAME"] = ""
        result = self.run_step("Publish target-specific API to Central")
        self.assertIn("Central credentials absent", result.stdout)
        self.assertFalse((self.root / "builds").exists())

    def test_central_publishes_all_target_coordinates_when_configured(self):
        self.env["CENTRAL_PORTAL_USERNAME"] = "fake-user"
        (self.root / "gradlew").write_text(
            '#!/usr/bin/env bash\necho "$*" >> builds\n', newline="\n")
        (self.root / "gradlew").chmod(0o755)
        self.run_step("Publish target-specific API to Central")
        builds = (self.root / "builds").read_text().splitlines()
        self.assertEqual(len(builds), 3)
        for target, command in zip(("26.1.2", "26.2", "26.3"), builds):
            self.assertIn("publishAggregationToCentralPortal", command)
            self.assertIn("-PminecraftTarget=" + target, command)

    def test_invalid_version_is_rejected_before_any_gh_call(self):
        (self.root / "gradle.properties").write_text("version=not-a-release\n")
        self.run_step("Select release", expected=1)
        self.assertEqual(self.calls(), "")

    def test_publication_steps_are_gated_and_follow_build_and_staging(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        build = workflow.index("- name: Build and test matching dependencies")
        staging = workflow.index("- name: Stage labeled assets")
        create = workflow.index("- name: Create or resume draft at this exact commit")
        self.assertLess(build, staging)
        self.assertLess(staging, create)
        for name in ("Create or resume draft at this exact commit", "Publish release",
                     "Publish target-specific API to Central"):
            section = workflow.split("      - name: " + name + "\n", 1)[1].split("\n      - ", 1)[0]
            self.assertIn("if: steps.release.outputs.publish == 'true'", section)

    def test_failed_build_stops_before_third_target(self):
        (self.root / "gradlew").write_text(
            '#!/usr/bin/env bash\necho "$*" >> builds\n'
            '[[ "$*" != *26.2* ]]\n', newline="\n")
        (self.root / "gradlew").chmod(0o755)
        self.run_step("Build and test matching dependencies", expected=1)
        builds = (self.root / "builds").read_text()
        self.assertIn("26.1.2", builds)
        self.assertIn("26.2", builds)
        self.assertNotIn("26.3", builds)

if __name__ == "__main__":
    unittest.main()

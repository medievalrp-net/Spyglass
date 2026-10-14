from pathlib import Path
import runpy
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "stage-release.py"
LIBS = "spyglass/build/mc26.1.2/libs"

class StageReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / ".github/scripts").mkdir(parents=True)
        self.script = self.root / ".github/scripts/stage-release.py"
        self.script.write_bytes(SCRIPT.read_bytes())
        (self.root / "gradle.properties").write_text("version=1.1.1\n")
        (self.root / ".github/release-notes.md").write_text("- A fix.\n")
        for name in ("Spyglass-1.1.1.jar", "Spyglass-1.1.1-shaded.jar", "Spyglass-1.1.1-thin.jar",
                     "Spyglass-1.1.1-sources.jar"):
            self.jar(name)

    def jar(self, name, target="26.1.2", version="1.1.1"):
        path = self.root / LIBS / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w") as jar:
            jar.writestr("spyglass-target.properties",
                         f"minecraft={target}\nsupported=26.1.2,26.2,26.3\n")
            jar.writestr("plugin.yml", f"version: {version}\n")

    def stage(self):
        runpy.run_path(str(self.script), run_name="__main__")

    def test_release_is_the_lean_and_shaded_jar_with_notes(self):
        self.stage()
        dist = self.root / "dist"
        self.assertEqual(sorted(p.name for p in dist.iterdir()),
                         ["NOTES.md", "Spyglass-1.1.1-shaded.jar", "Spyglass-1.1.1.jar"])
        notes = (dist / "NOTES.md").read_text()
        self.assertIn("Minecraft 26.1.2, 26.2 and 26.3", notes)
        self.assertIn("`Spyglass-1.1.1.jar`", notes)
        self.assertIn("- A fix.", notes)

    def test_missing_jar_prevents_partial_staging(self):
        (self.root / LIBS / "Spyglass-1.1.1-shaded.jar").unlink()
        with self.assertRaisesRegex(SystemExit, "Missing release asset"):
            self.stage()
        self.assertFalse((self.root / "dist").exists())

    def test_wrong_embedded_target_prevents_staging(self):
        self.jar("Spyglass-1.1.1.jar", target="26.3")
        with self.assertRaisesRegex(SystemExit, "Wrong embedded target"):
            self.stage()
        self.assertFalse((self.root / "dist").exists())

    def test_wrong_embedded_version_prevents_staging(self):
        self.jar("Spyglass-1.1.1-shaded.jar", version="1.1.0")
        with self.assertRaisesRegex(SystemExit, "Wrong embedded version"):
            self.stage()
        self.assertFalse((self.root / "dist").exists())

    def test_existing_distribution_is_not_overwritten(self):
        self.stage()
        with self.assertRaisesRegex(SystemExit, "dist must be empty"):
            self.stage()

if __name__ == "__main__":
    unittest.main()

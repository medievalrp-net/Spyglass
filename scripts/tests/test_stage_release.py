import hashlib
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "stage-release.py"
TARGETS = ("26.1.2", "26.2", "26.3")

class StageReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        self.script = self.root / "scripts/stage-release.py"
        self.script.write_bytes(SCRIPT.read_bytes())
        (self.root / "gradle.properties").write_text("version=1.1.0\n")
        for mc in TARGETS:
            for module, name, suffix in [
                ("spyglass", "Spyglass", ""), ("spyglass", "Spyglass", "-shaded"),
                ("spyglass-velocity", "Spyglass-Velocity", ""),
                ("spyglass-api", "spyglass-api", ""),
                ("spyglass-api", "spyglass-api", "-sources"),
                ("spyglass-api", "spyglass-api", "-javadoc")]:
                path = self.root / module / f"build/mc{mc}/libs/{name}-1.1.0-mc{mc}{suffix}.jar"
                path.parent.mkdir(parents=True, exist_ok=True)
                with zipfile.ZipFile(path, "w") as jar:
                    jar.writestr("spyglass-target.properties", f"minecraft={mc}\n")
                    jar.writestr("plugin.yml", f"version: 1.1.0-mc{mc}\n")

    def stage(self):
        with patch("subprocess.check_output", return_value="a" * 40):
            runpy.run_path(str(self.script), run_name="__main__")

    def test_complete_release_has_all_targets_and_checksums(self):
        self.stage()
        dist = self.root / "dist"
        self.assertEqual(len(list(dist.glob("*.jar"))), 18)
        for line in (dist / "SHA256SUMS").read_text().splitlines():
            digest, filename = line.split("  ")
            self.assertEqual(digest, hashlib.sha256((dist / filename).read_bytes()).hexdigest())
        notes = (dist / "NOTES.md").read_text()
        for mc in TARGETS:
            self.assertIn(f"Minecraft {mc}", notes)
        self.assertIn("a" * 40, notes)

    def test_missing_target_asset_prevents_partial_staging(self):
        next((self.root / "spyglass/build/mc26.3/libs").glob("*.jar")).unlink()
        with self.assertRaisesRegex(SystemExit, "Missing release asset"):
            self.stage()
        self.assertFalse((self.root / "dist").exists())

    def test_wrong_embedded_target_prevents_staging(self):
        path = next((self.root / "spyglass/build/mc26.2/libs").glob("*.jar"))
        with zipfile.ZipFile(path, "w") as jar:
            jar.writestr("spyglass-target.properties", "minecraft=26.3\n")
            jar.writestr("plugin.yml", "version: 1.1.0-mc26.2\n")
        with self.assertRaisesRegex(SystemExit, "Wrong embedded target"):
            self.stage()
        self.assertFalse((self.root / "dist").exists())

    def test_existing_distribution_is_not_overwritten(self):
        self.stage()
        with self.assertRaisesRegex(SystemExit, "dist must be empty"):
            self.stage()

if __name__ == "__main__":
    unittest.main()

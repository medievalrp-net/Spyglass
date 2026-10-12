"""Stage the release's two plugin jars and notes from one checkout; never publish anything."""
from pathlib import Path
import shutil
import zipfile

# The one released build: it runs on its target and every newer supported version.
TARGET = "26.1.2"
root = Path(__file__).resolve().parents[1]
version = next(line.split("=", 1)[1].strip() for line in
               (root / "gradle.properties").read_text(encoding="utf-8").splitlines()
               if line.startswith("version="))
libs = root / "spyglass" / "build" / ("mc" + TARGET) / "libs"
sources = [libs / f"Spyglass-{version}.jar", libs / f"Spyglass-{version}-shaded.jar"]
supported = None
for source in sources:
    if not source.is_file():
        raise SystemExit(f"Missing release asset: {source}")
    with zipfile.ZipFile(source) as jar:
        properties = jar.read("spyglass-target.properties").decode().splitlines()
        descriptor = jar.read("plugin.yml").decode().splitlines()
    if f"minecraft={TARGET}" not in properties:
        raise SystemExit(f"Wrong embedded target: {source}")
    if f"version: {version}" not in descriptor:
        raise SystemExit(f"Wrong embedded version: {source}")
    supported = next((p.split("=", 1)[1] for p in properties if p.startswith("supported=")), "")
    if not supported.startswith(TARGET):
        raise SystemExit(f"Wrong embedded supported versions: {source}")
# Validate both jars before copying anything; never stage a partial release.
dist = root / "dist"
if dist.exists() and any(dist.iterdir()):
    raise SystemExit("dist must be empty before staging a release")
dist.mkdir(exist_ok=True)
for source in sources:
    shutil.copy2(source, dist / source.name)
minecraft = supported.split(",")
listed = ", ".join(minecraft[:-1]) + " and " + minecraft[-1] if len(minecraft) > 1 else minecraft[0]
notes = [f"**Minecraft {listed} - Java 25.** Download `{sources[0].name}`; use "
         f"`{sources[1].name}` if your host can't download libraries.",
         "Minecraft 1.21.11: use [1.0.13](https://github.com/medievalrp-net/Spyglass/releases/tag/v1.0.13).", ""]
release_notes = root / ".github/release-notes.md"
if release_notes.exists():
    notes += [release_notes.read_text(encoding="utf-8").strip()]
(dist / "NOTES.md").write_text("\n".join(notes) + "\n", encoding="utf-8")

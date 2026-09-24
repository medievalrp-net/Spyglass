"""Stage all modern Minecraft builds from one checkout; never publish anything."""
import hashlib
from pathlib import Path
import shutil
import subprocess
import zipfile

TARGETS = ("26.1.2", "26.2", "26.3")
root = Path(__file__).resolve().parents[1]
base = next(line.split("=", 1)[1].strip() for line in
            (root / "gradle.properties").read_text(encoding="utf-8").splitlines()
            if line.startswith("version="))
commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
assets = [("spyglass", "Spyglass", ""), ("spyglass", "Spyglass", "-shaded"),
          ("spyglass-velocity", "Spyglass-Velocity", ""),
          ("spyglass-api", "spyglass-api", ""),
          ("spyglass-api", "spyglass-api", "-sources"),
          ("spyglass-api", "spyglass-api", "-javadoc")]
sources = []
versions = {}
for mc in TARGETS:
    version = base.removesuffix("-SNAPSHOT") + "-mc" + mc
    if base.endswith("-SNAPSHOT"):
        version += "-SNAPSHOT"
    versions[mc] = version
    for module, name, classifier in assets:
        source = root / module / "build" / ("mc" + mc) / "libs" / f"{name}-{version}{classifier}.jar"
        if not source.is_file():
            raise SystemExit(f"Missing release asset: {source}")
        if module == "spyglass":
            with zipfile.ZipFile(source) as jar:
                target = jar.read("spyglass-target.properties").decode()
                descriptor = jar.read("plugin.yml").decode()
                if f"minecraft={mc}" not in target.splitlines():
                    raise SystemExit(f"Wrong embedded target: {source}")
                if f"version: {version}" not in descriptor.splitlines():
                    raise SystemExit(f"Wrong embedded version: {source}")
        sources.append(source)
# Validate the complete set before copying anything; never stage a partial release.
dist = root / "dist"
if dist.exists() and any(dist.iterdir()):
    raise SystemExit("dist must be empty before staging a release")
dist.mkdir(exist_ok=True)
for source in sources:
    shutil.copy2(source, dist / source.name)
(dist / "SHA256SUMS").write_text("".join(
    f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n"
    for p in sorted(dist.glob("*.jar"))), encoding="utf-8")
notes = [f"Spyglass {base} for Minecraft 26.1.2, 26.2 and 26.3 (Java 25).",
         f"\nAll builds use source commit `{commit}`.",
         "\nInstall only the build matching your server's Minecraft version.",
         "\n## Downloads\n"]
for mc, version in versions.items():
    notes += [f"### Minecraft {mc}",
              f"- `Spyglass-{version}.jar`: recommended lean plugin.",
              f"- `Spyglass-{version}-shaded.jar`: bundles external libraries.",
              f"- `Spyglass-Velocity-{version}.jar`: optional proxy companion.",
              f"- `spyglass-api-{version}*.jar`: developer API, sources and Javadoc.",
              f"- Maven: `net.medievalrp:spyglass-api:{version}` (when Central is configured).\n"]
notes += ["Checksums: `SHA256SUMS`.",
          "Minecraft 1.21.x remains a separate legacy release on `maintenance/1.21`."]
release_notes = root / ".github/release-notes.md"
if release_notes.exists():
    notes += ["\n## Changes\n", release_notes.read_text(encoding="utf-8")]
(dist / "NOTES.md").write_text("\n".join(notes) + "\n", encoding="utf-8")

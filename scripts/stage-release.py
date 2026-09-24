"""Stage one tested Minecraft distribution; does not publish anything."""
import hashlib
from pathlib import Path
import shutil
import sys

mc = sys.argv[1]
if mc not in ("26.1.2", "26.2", "26.3"):
    raise SystemExit("Unsupported Minecraft target")
root = Path(__file__).resolve().parents[1]
base = next(line.split("=", 1)[1].strip() for line in
            (root / "gradle.properties").read_text().splitlines() if line.startswith("version="))
version = base.removesuffix("-SNAPSHOT") + "-mc" + mc
if base.endswith("-SNAPSHOT"):
    version += "-SNAPSHOT"
dist = root / "dist"
dist.mkdir(exist_ok=True)
# Refuse a mixed staging directory: it could publish another target's binaries.
if any(dist.iterdir()):
    raise SystemExit("dist must be empty before staging a release")
assets = [("spyglass", "Spyglass", ""), ("spyglass", "Spyglass", "-shaded"),
          ("spyglass-velocity", "Spyglass-Velocity", ""),
          ("spyglass-api", "spyglass-api", ""),
          ("spyglass-api", "spyglass-api", "-sources"),
          ("spyglass-api", "spyglass-api", "-javadoc")]
for module, name, classifier in assets:
    source = root / module / "build" / ("mc" + mc) / "libs" / f"{name}-{version}{classifier}.jar"
    shutil.copy2(source, dist / source.name)
(dist / "SHA256SUMS").write_text("".join(
    f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in sorted(dist.glob("*.jar"))))
(dist / "NOTES.md").write_text(f"""Spyglass {version} for **Minecraft {mc}**, Java 25.

Install the matching Minecraft build; other server versions are rejected at startup.

- `Spyglass-{version}.jar`: recommended lean plugin; downloads external libraries on first boot.
- `Spyglass-{version}-shaded.jar`: bundles external libraries for restricted hosts.
- `Spyglass-Velocity-{version}.jar`: optional proxy companion.
- `spyglass-api-*`: developer API, sources and Javadoc; not a server plugin.

Maven coordinates: `net.medievalrp:spyglass-api:{version}` (when Central publication is configured).
Checksums are in SHA256SUMS. Minecraft 1.21 maintenance remains on `maintenance/1.21`.
""")

release_notes = root / ".github/release-notes.md"
if release_notes.exists():
    with (dist / "NOTES.md").open("a") as notes:
        notes.write("\n## Changes\n\n" + release_notes.read_text())

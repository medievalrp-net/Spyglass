# Minecraft-specific builds and releases

One shared modern codebase produces three distributions. Java 25 is required for all of them.

| Minecraft | Paper compile API | Artifact version example |
| --- | --- | --- |
| 26.1.2 | 26.1.2.build.74-stable | 1.1.0-mc26.1.2 |
| 26.2 | 26.2.build.129-stable | 1.1.0-mc26.2 |
| 26.3 | 26.3.build.38-alpha | 1.1.0-mc26.3 |

Every distribution runs on all three Minecraft versions. The target only picks the
Paper API it compiles against and the artifact name. The inventory menus use
[inventory-framework](https://github.com/devnatan/inventory-framework) 3.7.2, which
only uses the Bukkit API, so one dependency version serves every target.
26.1 means the supported patched release **26.1.2**; 26.1 and 26.1.1 are not claimed supported.
Minecraft 1.21.x remains on `maintenance/1.21.11` with its existing 1.x build system.

## Branches

`main` is the shared modern development line for Minecraft 26.1.2, 26.2 and 26.3.
`maintenance/1.21.11` retains the legacy InvUI 1.x implementation and separate releases.
There are no modern per-Minecraft maintenance branches. Dependency versions are
selected by the Gradle target, not by different source branches.


## Build

```powershell
./gradlew.bat build '-PminecraftTarget=26.1.2'
./gradlew.bat build '-PminecraftTarget=26.2'
./gradlew.bat build '-PminecraftTarget=26.3'
```

The default target is 26.3. Unknown targets fail configuration. Each module writes to
`build/mc<target>/`, so successive builds preserve the other targets' binaries
and reports. For example, the current lean build is
`spyglass/build/mc26.2/libs/Spyglass-1.1.0-mc26.2.jar`; the fallback adds
`-shaded` before `.jar`. Both bundle relocated inventory-framework and FoliaLib.
The embedded target, plugin API version, Paper API pin and artifact version
come from the selected Gradle target. Every build also embeds the full supported
list (26.1.2, 26.2, 26.3) and starts on any of them; startup rejects any other
Minecraft version before loading configuration. Future patch releases need
explicit validation.

All modules, including the Velocity companion and developer API, carry the target
suffix. This avoids publishing different Paper-dependent builds under one Maven
coordinate: for example `net.medievalrp:spyglass-api:1.1.0-mc26.2`.

## Releases

The Verify workflow builds and tests all three modern targets. On a stable version
bump pushed to `main`, or manual dispatch, the Release workflow builds all three
from the same checkout. Only after every build and test succeeds does it stage
all assets and publish **one release**, tagged `v<version>` (for example `v1.1.0`).
Snapshot versions are skipped. Published releases are never overwritten; a draft
can be resumed only from its original commit.

The release contains all three targets' lean, shaded, Velocity and developer API
jars (12 jars total), one SHA256SUMS file, and notes recording the source commit.
`python scripts/stage-release.py` validates and stages the complete set into an
empty `dist` directory without publishing anything. Missing assets or mismatched
embedded plugin versions/targets fail staging. Central publication retains the
separate target-specific Maven coordinates.

This combined modern release receives GitHub's Latest label. Minecraft 1.21 remains
on its separate legacy release line. Pin downloads to the desired release and
Minecraft-labeled artifact; `Spyglass.jar` is not a modern release asset name.

No workflow or release has been pushed or published as part of this local setup.

## Inventory UIs

Every distribution uses an inventory-framework menu for `/sg inventory`
(aliases `/sg inv`, `/sg salvage`), container snapshots and player snapshots.
`/sg rollback storage` is not a registered command. Normal feedback, errors and
empty-result messages can still appear in chat. All supported builds use the inventory browser exclusively. Console/RCON receives
an in-game-only error. An unavailable or failing GUI reports an error without
listing or recovering items. The text recovery commands have been removed.

Player inventory history requires `snapshot.players.enabled=true`; it remains off
by default because periodic capture consumes storage. Container snapshots do not
require that setting. Permissions and available history still apply.

## Validation

After removing text recovery, each target passed Gradle `build`: 1,068 tests discovered, 975 passed, 93 skipped,
zero failures. The skips are 92 Docker-dependent tests plus the Windows-only
read-only-directory assumption. Both lean and shaded artifact metadata were checked
for the target API version, embedded target and relocated InvUI classes. Release
staging was exercised for all three targets; both workflows pass actionlint 1.7.12.
An unknown Gradle target (26.4) fails configuration as intended.

Both variants booted and answered the version command on Paper 26.1.2 build 74,
26.2 build 129 and 26.3 build 38. Installing the 26.1.2 shaded build on 26.3 produced
the explicit wrong-target error and disabled Spyglass before initialization.

All six GUI runs passed (lean and shaded on each of the three targets), with no
snapshot GUI fallback warnings. The initial 26.3 bot connection was blocked by
that test server's default whitelist; explicitly disabling the disposable server's
whitelist allowed the complete probe to run. All test servers were stopped afterward.

The GUI probe uses SQLite, WorldEdit 7.4.6-beta-01 and ViaVersion/ViaBackwards
5.12.0 with a Mineflayer 1.21.8 client protocol. Inventory clicks traverse the live
connection; item counts are checked through server-side entity data. It covers
container and player snapshot rendering/copying, snapshot permission revocation,
full-inventory refusal, the three salvage browser levels, exact withdrawal counts,
no duplicate recovery after reopening, and rollback/undo world-state checks.

This is not a native-client walkthrough or exhaustive coverage of pagination,
every click mode, every backend, FAWE or every event type. Test servers are isolated
from production. The runnable probe remains `regression/bot/_26.3-gui.mjs` and now
supports the same checks on all three targets; configure it as described in the
[port report](minecraft-26.3.md#repeating-the-gui-probe).

After removing chat alternatives, the shaded GUI probe passed again on all three
targets, including container/player snapshots and rollback salvage recovery.
Console commands were verified to return in-game-only errors. New regression
tests cover missing views and thrown GUI errors without text listing or store access.

Combined-release packaging also passes four regression tests: the complete 18-jar
bundle and checksums, missing-asset rejection before staging, wrong-target rejection,
and protection against overwriting a staging directory. The combined workflow passes
actionlint, and staging the real local artifacts produced all 18 verified checksums.

## inventory-framework validation

Validated 2026-10-08 on macOS, before any push or release. After the menus moved
from InvUI to inventory-framework 3.7.2, all three targets passed Gradle `build`:
1,100 tests each, 0 failures; the skips are the Docker-dependent tests and the
Windows-only assumption. Both jars carry inventory-framework and FoliaLib only under
`net.medievalrp.spyglass.libs`, with no InvUI classes, and embed
`supported=26.1.2,26.2,26.3`.

The 26.1.2 lean and shaded jars each booted on Paper 26.1.2 build 74, 26.2
build 129 and 26.3 build 38 (SQLite, WorldEdit 7.4.6-beta-01, ViaVersion/ViaBackwards 5.12.0,
isolated loopback servers, offline mode). On each it passed
`regression/bot/_26.3-gui.mjs` (13 checks) and the new
`regression/bot/_gui-exploits.mjs` (21 checks), with no Spyglass or
inventory-framework errors in the server logs. The exploit probe runs in survival
and tries shift-click, number-key and offhand swaps, Q and ctrl-Q drops,
double-click collect, middle-click clone and drags on snapshot and salvage content,
filler and nav cells, plus click bursts while salvage switches levels. Server-side
inventory and ground counts never exceeded the one legitimate take. Run it like
the GUI probe, with the same server setup.

The first exploit run found a salvage duplicate: two withdraws of one slot 1 ms
apart. `SalvageWithdrawals` read the store before claiming the slot, so a write
that settled during the second click's read let it pay out again from the
pre-write row. The slot is now claimed before the read, with a regression test.
The same probe against `main`'s InvUI build passed once, with fewer burst clicks
reaching the take handler; the race was in the shared withdraw engine.

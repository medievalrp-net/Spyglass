# Minecraft-specific builds and releases

One source branch produces three distributions. Java 25 is required for all of them.

| Minecraft | Paper compile API | Bundled InvUI | Release tag example |
| --- | --- | --- | --- |
| 26.1.2 | 26.1.2.build.74-stable | 2.1.1 | v2.0.0-mc26.1.2 |
| 26.2 | 26.2.build.129-stable | 2.3.2 | v2.0.0-mc26.2 |
| 26.3 | 26.3.build.38-alpha | 2.5.0 | v2.0.0-mc26.3 |

These pins follow [InvUI's compatibility table](https://github.com/NichtStudioCode/InvUI#version-compatibility).
26.1 means the supported patched release **26.1.2**; 26.1 and 26.1.1 are not claimed supported.
Minecraft 1.21.x remains on `maintenance/1.21` with its existing 1.x build system.

## Build

```powershell
./gradlew.bat build '-PminecraftTarget=26.1.2'
./gradlew.bat build '-PminecraftTarget=26.2'
./gradlew.bat build '-PminecraftTarget=26.3'
```

The default is 26.3. Unknown targets fail configuration. Each module writes to
`build/mc<target>/`, so successive builds preserve the other targets' binaries
and reports. For example, the current lean build is
`spyglass/build/mc26.2/libs/Spyglass-2.0.0-mc26.2-SNAPSHOT.jar`; the fallback adds
`-shaded` before `.jar`. Both bundle the matching relocated InvUI dependency.
The embedded target, plugin API version, dependency pins and artifact version
come from the selected Gradle target. Startup rejects any other Minecraft version
before loading configuration or InvUI. Future patch releases need explicit validation.

All modules, including the Velocity companion and developer API, carry the target
suffix. This avoids publishing different Paper-dependent builds under one Maven
coordinate: for example `net.medievalrp:spyglass-api:2.0.0-mc26.2`.

## Releases

The Verify workflow builds and tests all three targets independently. The Release
workflow on `main` creates one release per target after a stable version bump in
`gradle.properties`. `-SNAPSHOT` builds are never published. Manual dispatch can
select one target or all three. Each job tests before staging assets and creates
its release at the workflow's exact commit. Published releases are never overwritten.

Each release has explicitly versioned lean, shaded, Velocity and API jars, plus
SHA256SUMS. The release assets are staged by `python scripts/stage-release.py 26.2`;
this command requires an empty `dist` directory and never publishes anything.
Configured Central credentials publish each target's unique API coordinates.

GitHub has one repository-wide Latest release. Only the 26.3 release is marked
Latest; 26.1.2 and 26.2 remain available through their specific tags. Pin download
links to a release tag and labeled artifact. Old `/releases/latest/download/Spyglass.jar`
links do not select a Minecraft version and are no longer used for modern releases.
A future update of the default target must update both the Gradle default and the
workflow's Latest selection and matrices.

No workflow or release has been pushed or published as part of this local setup.

## Inventory UIs

Every matching distribution uses an InvUI inventory window for `/sg inventory`
(aliases `/sg inv`, `/sg salvage`), container snapshots and player snapshots.
`/sg rollback storage` is not a registered command. Normal feedback, errors and
empty-result messages can still appear in chat. All supported builds enable the inventory browser. An unexpected snapshot GUI
exception still logs a warning and provides the existing emergency text listing.

Player inventory history requires `snapshot.players.enabled=true`; it remains off
by default because periodic capture consumes storage. Container snapshots do not
require that setting. Permissions and available history still apply.

## Validation

Each target passed Gradle `build`: 1,062 tests discovered, 969 passed, 93 skipped,
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

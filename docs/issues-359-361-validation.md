# Issues 359–361 and update checker validation

Validated 2026-09-24. Validation covered the modern Minecraft builds and the 1.21.11 maintenance build before release publication; no production deployment was performed. The separate personal 26.3 test server was left running with its existing jar.

Implemented permanent wand protection (#359), exact-location MongoDB indexing and ClickHouse skip indexes (#360), supported live config reload (#361), and compatible GitHub release notifications. See [configuration and updates](live-configuration-and-updates.md) for supported settings and retention semantics.

The recorder tests exposed an in-flight save gap in `flush()`. Admission counting now includes batches already removed from the queue. A blocked-save regression proves flush waits for persistence. SQL pruning pins one retention policy per sweep, so a concurrent reload cannot mix old and new defaults/overrides.

| Minecraft | Plugin candidate | Tests passed | Skipped | Live GUI/reload checks |
| --- | --- | ---: | ---: | --- |
| 26.1.2 | 2.0.0-mc26.1.2-SNAPSHOT | 990 | 93 | Passed |
| 26.2 | 2.0.0-mc26.2-SNAPSHOT | 990 | 93 | Passed |
| 26.3 | 2.0.0-mc26.3-SNAPSHOT | 990 | 93 | Passed |
| 1.21.11 | 1.0.13-SNAPSHOT | 988 | 93 | Passed |

Build command: `gradlew.bat build -PminecraftTarget=<target>` for each modern target; `gradlew.bat build` for legacy. Both lean and shaded jars were built and their embedded plugin versions checked. Final lean artifact hashes match the jars used in the matching live server tests. Shaded variants were also exercised before the final retention/version-metadata adjustments; they are not presented as identical final binaries.

The 93 skips per target are 92 Docker-dependent database integration cases and one Windows read-only-directory assumption. Docker was unavailable. MongoDB/ClickHouse/MariaDB integration and performance against a production-sized database were not verified. SQLite tests ran locally. No latency improvement is claimed from unmeasured benchmarks.

All 16 release-staging/workflow tests passed. These run local substitutes for GitHub/Gradle and publish nothing. An incremental version-only change regenerated `plugin.yml` in both branches, then the normal candidate version was restored; this caught and fixed a missing Gradle resource input declaration.

Live Paper builds: 26.1.2 build 74, 26.2 build 129, 26.3 build 38, and 1.21.11 build 132. Tests used isolated loopback servers with WorldEdit and a Mineflayer 1.21.8 client through ViaVersion/ViaBackwards. Real inventory clicks covered snapshot display/copy, permission removal, full-inventory rejection, rollback salvage browsing/withdrawal, duplicate prevention, undo and player snapshots. Reload checks covered event catalog changes, wand material, rejection of restart-only changes and invalid retention, and restoring the original config.

On 1.21.11, 26.1.2 and 26.2, placement checks distinguished protected inactive wands from an ordinary lamp at the same target. On 26.3 the ordinary-lamp control also failed with Spyglass removed, so bridged placement is explicitly inconclusive. A live Paper event probe verified both-hand inactive/non-op wand protection, old-material protection and ordinary-lamp acceptance. **Native 26.3 client wand placement still needs manual acceptance.** The 26.3 inventory GUI checks passed separately.

`/sg version` on the live 26.3 server completed a real GitHub check and reported no compatible published release, which is correct while modern work remains unpublished. Release-selection tests cover matching target assets, legacy assets, numeric version ordering, snapshots, drafts and prereleases.

Reproduction code: `regression/bot/verify-config-and-guis.mjs`, `regression/bot/cases/live-config-wand.js`, and `regression/probe/`. In the modern audit worktree, runtime logs and manifests are local under `workspace/issues-final-runtime-*.json` and `.audit-server/final/<version>/issues-final-lean-*.log`; generated jars, test worlds and credentials are excluded from commits.

## Artifact SHA-256

| Minecraft | Artifact | SHA-256 |
| --- | --- | --- |
| 26.1.2 | `Spyglass-2.0.0-mc26.1.2-SNAPSHOT.jar` | `26bd9f4884633acd2954d31df859f7146dc786da9b316bf0c81e83efeb1dd991` |
| 26.1.2 | `Spyglass-2.0.0-mc26.1.2-SNAPSHOT-shaded.jar` | `b934dfa77cadcafb47303e29729781c2f2d67cab88190d86fdf2b5e1d8277738` |
| 26.2 | `Spyglass-2.0.0-mc26.2-SNAPSHOT.jar` | `6fa760d6a2cbec4f9cb5b6a615bab70bff8bca32cae97d833fe608a9604f7d50` |
| 26.2 | `Spyglass-2.0.0-mc26.2-SNAPSHOT-shaded.jar` | `5251751aaaff24cb8c3d13ccb73dddd0dacaa73ca4b6accbf63e6c5d02bd054e` |
| 26.3 | `Spyglass-2.0.0-mc26.3-SNAPSHOT.jar` | `6c1ffcf579723d0e79eadcf85cd2271460900053457890372501e56cfbc63620` |
| 26.3 | `Spyglass-2.0.0-mc26.3-SNAPSHOT-shaded.jar` | `4f3ac18d07ec0acd4b015615f0f0e677b23bb89651c59c2ff0b9274ab9556a2f` |
| 1.21.11 | `Spyglass-1.0.13-SNAPSHOT.jar` | `8d1cf32c54798c90fca0f9d82d06b18f53de406860a9b0bbe821cd939001bf2e` |
| 1.21.11 | `Spyglass-1.0.13-SNAPSHOT-shaded.jar` | `c33c4e6ac6aad6f27271a6478e50fa8ba9fb13bede031cc155f2dce1cecfc080` |

# Minecraft builds and releases

From 1.1.1 one jar runs on Minecraft 26.1.2, 26.2 and 26.3, and releases ship only
that build: `Spyglass-<version>.jar` and `Spyglass-<version>-shaded.jar`. Java 25 is
required. The other targets still build, only to compile and test against newer
Paper APIs.

| Minecraft target | Paper compile API | Artifact version example |
| --- | --- | --- |
| 26.1.2 (default, released) | 26.1.2.build.74-stable | 1.1.1 |
| 26.2 | 26.2.build.129-stable | 1.1.1-mc26.2 |
| 26.3 | 26.3.build.38-alpha | 1.1.1-mc26.3 |

A distribution runs on its target and every newer supported version, so the 26.1.2
build runs on all three. Paper refuses a build on an older server ("Unsupported API
version"), because the target is also the plugin's `api-version`. The inventory menus use
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

The default target is 26.1.2, the released one, and its artifacts carry the plain
version. Unknown targets fail configuration. Each module writes to
`build/mc<target>/`, so successive builds preserve the other targets' binaries
and reports. For example, the release's lean build is
`spyglass/build/mc26.1.2/libs/Spyglass-1.1.1.jar`; the fallback adds
`-shaded` before `.jar`. Both bundle relocated inventory-framework and FoliaLib.
The embedded target, plugin API version, Paper API pin and artifact version
come from the selected Gradle target. Every build also embeds the versions it
supports (its target and the newer ones) and starts on any of them; startup rejects
any other Minecraft version before loading configuration. Future patch releases need
explicit validation.

Non-default targets give every module, including the Velocity companion and
developer API, the target suffix, so they can never be confused with the release.
Only the 26.1.2 API is published: `net.medievalrp:spyglass-api:1.1.1`. 1.1.0 was
published per target (`1.1.0-mc26.2` and so on).

## Releases

The Verify workflow builds and tests all three targets. On a stable version bump
pushed to `main`, or manual dispatch, the Release workflow builds and tests the
26.1.2 target only, then publishes **one release** tagged `v<version>` with two
assets: `Spyglass-<version>.jar` (libraries downloaded by Paper on first start) and
`Spyglass-<version>-shaded.jar` (everything bundled). The notes are a short header
plus `.github/release-notes.md`. Snapshot versions are skipped. Published releases
are never overwritten; a draft can be resumed only from its original commit.

`python scripts/stage-release.py` validates and stages both jars and the notes into
an empty `dist` directory without publishing anything. A missing jar, or one whose
embedded target, supported versions or plugin version is wrong, fails staging.
Velocity and API jars are no longer release assets; the API stays on Maven Central.

Installed 1.1.0 copies only recognize `-mc<target>` asset names, so they do not get
an in-game notice for 1.1.1. From 1.1.1 the update check also accepts the plain
names on any 26.x server. Minecraft 1.21 remains on its separate legacy release line.

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

Validated 2026-10-08 to 2026-10-10 on macOS, before any push or release. After the
menus moved from InvUI to inventory-framework 3.7.2, all three targets passed
Gradle `build`: 1,101 tests each, 0 failures; the skips are the Docker-dependent
tests and the Windows-only assumption. Both jars carry inventory-framework and
FoliaLib only under `net.medievalrp.spyglass.libs`, with no InvUI classes.

Each target's lean jar was booted on Paper 26.1.2 build 74, 26.2 build 129 and
26.3 build 38. A build enables on its target and every newer version, and Paper
refuses it on an older one with "Unsupported API version", so only the 26.1.2
build covers all three. Each build embeds that list (`supported=` in
`spyglass-target.properties`).

The servers ran SQLite, WorldEdit 7.4.6-beta-01 and ViaVersion/ViaBackwards
5.12.0 on isolated loopback ports in offline mode and peaceful difficulty (the
probes play in survival), with the bot probes
connecting through ViaBackwards. On each version the 26.1.2 lean and shaded jars
passed, with no Spyglass or inventory-framework errors in the server logs:

- `regression/bot/_26.3-gui.mjs` (13 checks): the existing snapshot and salvage walkthrough.
- `regression/bot/_gui-exploits.mjs` (21 checks): in survival, shift-click,
  number-key and offhand swaps, Q and ctrl-Q drops, double-click collect,
  middle-click clone and drags on snapshot and salvage content, filler and nav
  cells, plus click bursts while salvage switches levels. Nobody ends up with more
  than the one legitimate take, in their inventory or on the ground.
- `regression/bot/_gui-coverage.mjs` (26 checks): two staff click-bursting the same
  salvage at once (each stack paid out exactly once between them); a 50-container
  salvage paged 45 + 5 with working previous/next, nothing taken from page-two
  nav icons, and a page-two take; a double-chest (6-row) snapshot with its info in
  chat; and a salvage take refused, then paid once, around a full inventory.
- `regression/bot/_gui-reload.mjs` (8 checks): `bukkit:reload confirm` with a
  salvage menu open closes the menu, nothing can be taken across the reload, and
  the salvage survives and pays out once afterwards. ViaVersion does not survive a
  reload, so the probe reconnects first; native clients need no reconnect.
- `regression/bot/_gui-player-snapshot.mjs` (12 checks): the player-snapshot layout
  (armor, offhand, main inventory, hotbar, info cells) and that copies carry no
  display label.

The 26.2 and 26.3 builds also passed `_26.3-gui.mjs` on their own versions.

Testing found and fixed three problems:

- A salvage duplicate: two withdraws of one slot 1 ms apart. `SalvageWithdrawals`
  read the store before claiming the slot, so a write that settled during the
  second click's read let it pay out again from the pre-write row. The slot is now
  claimed before the read, with a regression test. The exploit probe against
  `main`'s InvUI build passed once, with fewer burst clicks reaching the take
  handler; the race was in the shared withdraw engine.
- A stale container list: store writes run concurrently with the list re-read, so
  a container emptied by the take that triggered the re-read could still be listed.
  It could not pay out twice, but it showed. The list now also filters slots that
  were in flight when its read started.
- The probes undercounted inventories: `data get entity ... Inventory` output is
  truncated after the first stack or so. They now count with `clear <player>
  <item> 0` (`itemCount` in `regression/bot/cases/lib.js`), including the
  existing `_26.3-gui.mjs`.

Still untested: a native 26.x client walkthrough.

## Storage backend validation

Validated 2026-10-11/12 on macOS with Docker (Colima, Docker Engine 29) and the
images the integration tests use: `mongo:7.0`,
`clickhouse/clickhouse-server:24.8-alpine`, `mariadb:11`, plus `mysql:8.4` for the
`mysql` spelling of the MariaDB backend.

The integration tests had never run on a current Docker: Testcontainers 1.21.3
requests Docker API 1.32 and Docker Engine 29 refuses anything below 1.40, so every
store IT assume-skipped, and the build's "Docker not reachable" warning stayed quiet
because the `docker` CLI still worked. With the client forced to API 1.44 all 1,101
tests ran, the 92 store ITs included (MongoDB, ClickHouse, MariaDB), 0 failures.
Testcontainers 1.21.4 is the drop-in fix.

Each backend then got a clean install: a fresh database container with nothing
created up front (Spyglass creates its own databases), and a fresh Paper 26.3
server running the 26.1.2 lean jar. On each, `_26.3-gui.mjs`, `_gui-exploits.mjs`,
`_gui-coverage.mjs`, `_gui-player-snapshot.mjs`, `_gui-reload.mjs` and
`_backend-core.mjs` ran, then the server was fully restarted and
`_backend-core.mjs` ran again with `PHASE=after`. `_backend-core.mjs` covers chat
and command logging, a 72-block WorldEdit edit searched, rolled back, restored and
undone, a real player block break through the bridge, and, after the restart, that
the chat line, the placements, an unwithdrawn salvage and a player snapshot are
still there and still work. Each run then counted records in the database itself,
proving the backend received them.

The first pass found a salvage duplicate on every networked backend. Every take
rewrote the container's whole item list from its own read while only its slot was
claimed, so two overlapping takes of different slots could write back a stack the
other had taken. Two staff click-bursting one salvage got 224 items from a
160-item salvage on MongoDB and 320 on ClickHouse; MariaDB and MySQL duplicated
too. Takes are now serialized per container (a click inside the write window is
refused and can be repeated). With that fix every backend passed all 98 checks:

| Backend | GUI, exploit, coverage, snapshot, reload, core | After restart | Records stored |
| --- | --- | --- | --- |
| SQLite | 90/90 | 8/8 | 325 |
| MongoDB | 90/90 | 8/8 | 327 |
| ClickHouse | 90/90 | 8/8 | 328 |
| MariaDB 11 | 90/90 | 8/8 | 327 |
| MySQL 8.4 | 90/90 | 8/8 | 327 |

The only errors in the server logs were Paper's own `ConcurrentModificationException`
in its async command builder during `bukkit:reload`, with no Spyglass frames.

### Migration chain

One clean server carried the same history across every backend with
`/sg migrate`: seeded on SQLite by `_26.3-gui.mjs` and `_backend-core.mjs`
(`PHASE=before`), then SQLite → MongoDB → ClickHouse → MariaDB → a fresh SQLite
file → MySQL. Each hop ran `_backend-migrate.mjs` as an in-game operator,
restarted the server on the target, and ran `_backend-history.mjs` there: the
seeded chat line, the 72 WorldEdit placements and the commands are searchable,
and the placements still roll back and undo.

The first run lost history leaving ClickHouse: the ClickHouse → MariaDB hop
copied 147 of ~205 records, only block changes. `queryPage`, which `/sg migrate`
pages through, reused the rollback reader's rollbackable-only filter and trimmed
columns, so chat, commands, joins, quits, teleports, rollback ops, salvage takes
and snapshot takes were dropped. ClickHouse's `queryPage` now reads every event
type with full columns (SQLite, MariaDB and MongoDB already did), with an IT
covering it. Rerun with the fix:

| Hop | Copied | Stored on target | History checks |
| --- | --- | --- | --- |
| SQLite → MongoDB | 181 | 191 | 5/5 |
| MongoDB → ClickHouse | 193 | 202 | 5/5 |
| ClickHouse → MariaDB 11 | 204 | 214 | 5/5 |
| MariaDB → SQLite (new file) | 216 | 226 | 5/5 |
| SQLite → MySQL 8.4 | 228 | 237 | 5/5 |

"Stored" is counted in the database after the target's checks, so it includes
the records the checking bots themselves wrote. After the ClickHouse → MariaDB
hop, every event type in ClickHouse was present in MariaDB with at least the
same count. No errors in any of the server logs.

Salvage, undo history, tool state and player snapshots are separate stores and
are not part of `/sg migrate`.

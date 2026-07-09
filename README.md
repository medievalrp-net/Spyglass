# Spyglass (Preview)

Forensic logging and rollback for Paper 1.21.x. Spyglass records block, container, chat, command, combat, and movement events, lets you query them with a `key:value` language, and rolls any of them back by block, player, cause, or in bulk while the server holds 20 TPS.

> **Preview.** Spyglass is built for medium and large servers. The embedded SQLite backend runs it with no external database, so a small server can use it too, though CoreProtect or Prism stay lighter-weight there.

A standalone CLI imports existing CoreProtect databases into Spyglass — see [`docs/importer.md`](docs/importer.md) and [Migrating from CoreProtect](#migrating-from-coreprotect).

Support: [discord.gg/XkpVHcHvH](https://discord.gg/XkpVHcHvH)

**Docs:** [Commands & query reference](COMMANDS.md) · [API](API.md) · [Licensing](LICENSING.md) · [Contributing](CONTRIBUTING.md) · [AI policy](AI-POLICY.md)

## Sponsors

Proudly sponsored by and running on:

<table>
<tr>
<td align="center">
<a href="https://crusalis.net"><img src=".github/assets/crusalis.webp" width="220" alt="Crusalis: Glory of Rome"></a><br>
<a href="https://crusalis.net"><b>crusalis.net</b></a> · 1,500+ players
</td>
<td align="center">
<a href="https://apply.istoria.events/"><img src=".github/assets/istoria.jpg" width="130" alt="Istoria"></a><br>
<a href="https://apply.istoria.events/"><b>apply.istoria.events</b></a> · 500+ players
</td>
</tr>
</table>

## Performance

A 2,000,376-block rollback on Spyglass (ClickHouse) versus CoreProtect (MySQL), on one server (Paper 1.21.8, 6 GB heap, stock Aikar flags).

| 2M-block rollback | Spyglass · ClickHouse | CoreProtect · MySQL |
|---|---|---|
| Rollback wall-clock | **~5 s** | ~12 s |
| Undo / restore wall-clock | **~4 s** | ~10 s |
| TPS during the op (min / avg) | **20.0 / 20.0** | 13 / 20 |
| Worst single tick | **~50 ms** | ~270 ms |
| On-disk footprint (data + index) | **~11 MiB** | ~180 MiB |

A live [spark profile](https://spark.lucko.me/5JzJrfOmaM) of Spyglass running in production on [Crusalis](https://crusalis.net), with 1,000 players online at the time.

> **Why MongoDB?** the60th asks me the same question. I really just like using MongoDB. Although we recommend servers utilize ClickHouse for performance and efficient disk space usage.

## Features

| | Spyglass | CoreProtect |
|---|---|---|
| Block, container, and entity logging | ✓ | ✓ |
| Chat, command, session, and IP logging | ✓ | ✓ |
| Explosions, fire, liquids, and growth | ✓ | ✓ |
| Combat damage logging (hits and shots) | ✓ |  |
| Movement and teleport logging | ✓ |  |
| WorldEdit capture | ✓ | ✓ |
| Password redaction in command logs | ✓ |  |
| Inspector wand and lookup | ✓ | ✓ |
| Item search by name, lore, or enchantment | ✓ |  |
| Cross-server search | ✓ |  |
| Extension API (custom events, keys, display) | ✓ |  |
| Rollback and restore by player, time, or region | ✓ | ✓ |
| Recover items a rollback destroyed | ✓ |  |
| One-command undo, any size | ✓ |  |
| Crash-resume an interrupted rollback | ✓ |  |
| No events lost on a crash | ✓ |  |
| Rollback adds no new log rows | ✓ |  |
| Preview a rollback before applying |  | ✓ |
| Rollback runs off the main thread | ✓ |  |
| TPS during a 2M rollback | **20.0, flat** | dips to ~13 |
| Worst single tick | **~100 ms** | up to ~900 ms |
| Automatic data pruning | ✓ |  |
| Storage engines | SQLite, MongoDB, ClickHouse, MariaDB/MySQL | SQLite, MySQL |
| Minecraft versions | 1.21.x | 1.7+ |

Spyglass runs on SQLite, MongoDB, ClickHouse, or MariaDB/MySQL. The embedded SQLite backend needs no external database, so the zero-ops install CoreProtect offers is available on Spyglass too; MongoDB, ClickHouse, and MariaDB/MySQL are there when you outgrow it or already run one.

## Requirements

- Paper 1.21.8 or newer 1.21.x
- Java 21
- A database, one of:
  - Embedded SQLite, no external database (the default); writes to a file under the plugin folder
  - MongoDB (set `database.backend = "mongo"`) at `mongodb://localhost:27017`
  - ClickHouse (set `database.backend = "clickhouse"`)
  - MariaDB or MySQL (set `database.backend = "mariadb"`, or `"mysql"`) at `localhost:3306`
- Optional: WorldEdit 7.3+ or FastAsyncWorldEdit 2.15+ for WorldEdit-edit capture and `-we` queries. Capture hooks the edit-session pipeline, not command names, so every block-mutating operation is recorded - `//set`, `//replace`, `//walls`, `//overlay`, `//paste`, schematic paste, brushes, generation, `//move`/`//stack`, and `//undo`/`//redo` alike - for player and non-player (console/plugin) edits

## Commands

Root command is `/spyglass`, aliased to `/sg`. The full reference - every command, permission, query key, flag, and worked example - lives in **[COMMANDS.md](COMMANDS.md)**.

```
/sg search p:Steve b:diamond_ore t:1d r:50    # who touched this block?
/sg rollback p:griefer t:6h r:100             # revert a griefer's last 6h nearby
/sg undo                                       # reverse your last rollback
/sg tool                                       # toggle the inspector wand
/sg import database.db                         # import a CoreProtect history
/sg migrate clickhouse                         # move all records to another backend
```

## Migrating from CoreProtect

Drop your CoreProtect `database.db` in `plugins/Spyglass/import/` and run it in-game - no separate tooling:

```
/sg import database.db          # CoreProtect SQLite, into whatever backend you run
/sg import mysql old-survival   # live CoreProtect MySQL (sources in import.conf)
```

Imports run async, stream progress, and warn up front if part of the history is older than your `storage.retention` (set it `"never"` first to keep everything). Re-imports dedup instead of duplicating. Once imported, everything works on the old data - search, and rolling back a griefer by name even if they never joined the new server.

Switching storage later? `/sg migrate <backend>` copies every record from the active backend into another configured one - fill in the target's block in `config.conf`, migrate, flip `database.backend`, restart.

The standalone CLI importer still exists for offline runs and row-parity auditing (`validate`); the step-by-step operator runbook is [`docs/migration-from-coreprotect.md`](docs/migration-from-coreprotect.md), and the whole rig (ClickHouse + importer + validate + bench) runs via Docker: [`docker/README.md`](docker/README.md).

Imports cover blocks, kills, sessions, chat, commands, container deposits/withdraws, and item drops/pickups. Full table coverage and known gaps are documented in [`docs/importer.md`](docs/importer.md).

## Modules

- `spyglass-api/` is the public API. Third-party plugins depend on this only. Published to Maven Central as `net.medievalrp:spyglass-api`; see [API.md](API.md).
- `spyglass-core/` holds the shared internals (codecs, storage glue).
- `spyglass/` is the Paper plugin.
- `spyglass-velocity/` is an optional Velocity proxy companion for cross-server search. It is read-only by design: it never writes records and never rolls back.
- `spyglass-importer/` is a standalone CLI for migrating CoreProtect databases into Spyglass, plus a side-by-side query bench. See [`docs/importer.md`](docs/importer.md).

## AI policy

Spyglass is human-led. We make the architectural and design decisions, and we will defend every line that ships. AI assists with boilerplate, testing, and drafting issues. Nothing merges to main without a review and a passing test suite, and every performance claim here is measured on a real server. Read the full policy in [AI-POLICY.md](AI-POLICY.md).

## License

Spyglass is open source under a split license, mapped in [LICENSING.md](LICENSING.md):

- The public extension API (`spyglass-api`) is licensed under the [Apache License 2.0](spyglass-api/LICENSE), so third-party plugins can depend on it freely.
- The plugin and its internals (`spyglass-core`, `spyglass`, `spyglass-velocity`) are licensed under the [GNU General Public License v3.0](LICENSE).

Contributions are covered by the [CLA](CLA.md); see [CONTRIBUTING.md](CONTRIBUTING.md).

The CoreProtect importer keeps a clean-room discipline against CoreProtect's GPL source: schemas and on-disk formats are paraphrased from upstream into [`docs/importer.md`](docs/importer.md), and the implementation is written fresh.

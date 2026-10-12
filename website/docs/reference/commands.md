# Commands

The root command is `/spyglass`. `/sg` always works too, and so does `/s` unless you've turned it off (`commands.s-alias`). Every permission defaults to server operators.

| Command | Aliases | Permission | What it does |
| --- | --- | --- | --- |
| `/sg help [page]` | `h`, `?` | `spyglass.use` | List commands |
| `/sg search <keys>` | `s`, `sc`, `lookup`, `l` | `spyglass.search` | [Search the log](../guide/searching.md) |
| `/sg page <n>` | `p`, `pg` | `spyglass.use` | Show another page of your last search |
| `/sg tool` | `t`, `inspect` | `spyglass.tool` | Toggle the [inspection wand](../guide/inspecting.md) |
| `/sg rollback <keys>` | `rb`, `roll` | `spyglass.rollback` | [Roll back](../guide/rollback.md) matching changes |
| `/sg restore <keys>` | `rs`, `rst` | `spyglass.rollback` | Re-apply rolled-back changes |
| `/sg undo` | `u` | `spyglass.rollback` | Reverse your last rollback or restore (in-game only) |
| `/sg rbqueue [stop \| cancel <id> \| resume <id>]` | `queue`, `rbq` | `spyglass.rollback` | List and manage rollback jobs |
| `/sg inventory` | `inv`, `salvage` | `spyglass.salvage` | Open [salvage](../guide/salvage.md): items rollbacks destroyed (in-game only) |
| `/sg snapshot <keys>` | `snap` | `spyglass.snapshot` | View an [inventory or container in the past](../guide/snapshots.md) (in-game only) |
| `/sg events` | `e` | `spyglass.use` | List the event types being recorded |
| `/sg import <file>` | | `spyglass.import` | [Import a CoreProtect](../guide/coreprotect.md) SQLite file from `plugins/Spyglass/import/` |
| `/sg import mysql <source>` | | `spyglass.import` | Import a CoreProtect MySQL database listed in `import.conf` |
| `/sg migrate <backend>` | | `spyglass.migrate` | [Copy all records](../guide/databases.md#moving-to-another-database) to another database |
| `/sg reload` | | `spyglass.reload` | [Reload](../guide/maintenance.md) the settings that can change live |
| `/sg version` | `ver` | `spyglass.use` | Version and update status |
| `/sg stats` | `analytics` | `spyglass.use` | Disk overflow backlog and recording stats |
| `/sg tele <world> <x> <y> <z>` | | `spyglass.tele` | Teleport to a result (used when you click a search line) |

`/sg import` and `/sg migrate` take `--confirm` to run against a target that already has data.

## Velocity proxy

| Command | Permission | What it does |
| --- | --- | --- |
| `/sgv search <keys>` | `spyglass.search` | Search every server on the network |
| `/sgv page <n>` | `spyglass.search` | Next page |
| `/sgv help` | `spyglass.search` | Key syntax |

See [Cross-server search](../guide/velocity.md).

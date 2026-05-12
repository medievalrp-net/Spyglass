# Spyglass

Forensic logging and rollback for Paper 1.21.x. Tracks every block, container, chat, command, combat, and movement event, lets staff search through them with a flexible query language, and can roll any of it back per-block or in bulk.

## Status

`v1.0.0` - feature-complete. Ships 40+ event types, block + container + entity-NBT rollback, vanilla and FAWE WorldEdit capture, the `/spyglass tool` inspection wand, an undo stack, a job queue with crash-resume, and a public API for third-party integration.

## Requirements

- Paper 1.21.8 (or newer 1.21.x)
- Java 21
- One of:
  - MongoDB at `mongodb://localhost:27017` (default)
  - ClickHouse (set `database.backend = "clickhouse"` in config)
- Optional: WorldEdit 7.3+ or FastAsyncWorldEdit 2.15+ for `//set` / `//paste` capture and `-we` queries

## Install

1. Drop `Spyglass-1.0.0.jar` into `plugins/`.
2. Start the server once. This generates `plugins/Spyglass/config.conf`.
3. Edit `config.conf`. At minimum:
   - Point `database.uri` (Mongo) or `database.clickhouse.*` (ClickHouse) at your DB.
   - Grant `spyglass.*` permissions to trusted roles (see below).
4. Restart.

## Permissions

All default to `op`. Grant via your permissions plugin to scope access.

| Permission | What it unlocks |
|---|---|
| `spyglass.use` | `/spyglass help`, `/spyglass events`, `/spyglass page` |
| `spyglass.search` | `/spyglass search` |
| `spyglass.rollback` | `/spyglass rollback`, `/spyglass restore`, `/spyglass undo`, `/spyglass rbqueue` |
| `spyglass.tool` | `/spyglass tool` (inspection wand) |
| `spyglass.tele` | `/spyglass tele` (jump to a search result) |
| `spyglass.worldedit` | Allows the `-we` flag (uses your WE selection as the search region) |

## Commands

Root: `/spyglass`. Short alias: `/sg`. All subcommands accept short aliases too (`/sg s` = `/sg search`).

| Command | Aliases | What it does |
|---|---|---|
| `/sg help` | `h`, `?` | Command help |
| `/sg events` | `e` | List enabled event types |
| `/sg search <query>` | `s`, `sc`, `lookup`, `l` | Search the event log |
| `/sg page <n>` | `p`, `pg` | Page through the last search result |
| `/sg rollback <query>` | `rb`, `roll` | Revert matched events |
| `/sg restore <query>` | `rs`, `rst` | Re-apply previously-rolled-back events |
| `/sg undo` | `u` | Undo your last rollback / restore |
| `/sg rbqueue [...]` | `queue`, `rbq` | List, cancel, or resume rollback jobs |
| `/sg tool` | `t`, `inspect` | Toggle the inspection wand |
| `/sg tele <world> <x> <y> <z>` | - | Teleport (used by clickable search results) |

### Query syntax

Search, rollback, and restore all take the same `key:value` query language. Combine as many keys as you want; results match all of them.

```
/sg search p:Steve b:diamond_ore t:1d r:50
```

Reads: "every diamond-ore-related event Steve caused in the last day within 50 blocks of me."

#### Query keys

| Key | Aliases | Example | Notes |
|---|---|---|---|
| `p:` | `player:` | `p:Steve` or `p:Steve,Alex` | Comma-separated for OR |
| `a:` | `action:`, `event:` | `a:break,place` | Event type. See `/sg events` for the live list |
| `b:` | `block:` | `b:diamond_ore` | Target block material |
| `i:` | `item:` | `i:netherite_sword` | Item material involved (drop, pickup, container, etc.) |
| `iname:` | `itemname:` | `iname:Excalibur` | Item display name (substring match) |
| `ilore:` | `itemlore:`, `d:` | `ilore:cursed` | Item lore line (substring) |
| `ench:` | `enchant:`, `enchantment:` | `ench:sharpness` | Item enchantment |
| `cu:` | `custom:` | `cu:my-custom-item` | Plugin custom-item id (via the API) |
| `e:` | `entity:` | `e:creeper` | Entity type involved |
| `c:` | `cause:` | `c:explosion` | Change cause (e.g. `explosion`, `burn`, `decay`) |
| `m:` | `message:` | `m:hello` | Chat / sign / book text (substring) |
| `rcp:` | `recipient:` | `rcp:Steve` | Private-message recipient |
| `r:` | `radius:` | `r:50` | Search radius around you (blocks). Default radius applies if omitted; use `-g` for global |
| `t:` | `since:` | `t:1d`, `t:30m`, `t:2w` | Time window. Default is 4h |
| `w:` | `world:` | `w:world_nether` | Restrict to one world |
| `srv:` | `server:` | `srv:survival` | Restrict to one server name (multi-server setups) |
| `trg:` | `target:` | `trg:100,64,200` | Specific block coords |
| `ip:` | - | `ip:192.168.1.10` | Source IP (requires the IP module enabled) |

#### Flags

Flags start with `-` and don't take a value (unless noted).

| Flag | Aliases | Effect |
|---|---|---|
| `-g` | `-global` | Skip the default radius. Whole-world / whole-server search |
| `-we` | `-worldedit` | Use your active WorldEdit selection as the region |
| `-ord:<asc\|desc>` | `-order:` | Sort order. Default: newest first for search/rollback, oldest first for restore |
| `-ng` | `-nogroup` | Don't merge duplicate adjacent events in the result list |
| `-nc` | `-nochat` | Don't echo the summary line to chat (action-bar only) |
| `-ex` | `-extended` | Include extra detail columns in the result list |
| `-nod:<keys>` | `-nodefault:` | Suppress defaults. Example: `-nod:r,t` runs unbounded |

#### Time formats

`30s`, `15m`, `4h`, `2d`, `1w`, `1mo`. Combine: `t:1d12h`.

### Inspection wand

```
/sg tool
```

Toggles an inspection mode. Left-click a block to see what happened to it. Right-click to see what's about to happen near it. Toggle off the same way.

### Rollback workflow

```
/sg rollback p:griefer t:6h r:100
```

Reverts everything that player did in the last 6 hours within 100 blocks. Behavior:

1. The request is queued. Only one rollback runs at a time; others wait.
2. Each page of results applies in tick-budgeted batches so the server stays at ~20 TPS.
3. When it finishes, you get a summary: blocks reverted, chunks touched, time taken.
4. The inverse is saved on your personal undo stack (capped; very large rollbacks may not be undoable).

To take it back:

```
/sg undo
```

To redo:

```
/sg restore p:griefer t:6h r:100
```

`restore` re-applies the original event from the log, in case `undo` wasn't enough or you only meant to roll back a subset.

### Managing the queue

```
/sg rbqueue              # list in-flight, pending, recent, and resumable jobs
/sg rbqueue stop         # cancel the running job (current chunk finishes, then it stops)
/sg rbqueue cancel <id>  # cancel a pending or in-flight job by short id
/sg rbqueue resume <id>  # re-run a job that was interrupted by a crash or restart
```

If the JVM crashes mid-rollback, on next startup `/sg rbqueue` shows the interrupted job as resumable. The original query gets re-run from a saved cursor, so a 2M-block job that crashed at 75% only re-applies the remaining 25%.

## Configuration

`plugins/Spyglass/config.conf` has the full annotated reference. The knobs you're most likely to touch:

| Setting | What it does |
|---|---|
| `database.backend` | `"mongo"` or `"clickhouse"` |
| `database.uri` (mongo) | Connection string |
| `database.clickhouse.host/port/...` | CH connection |
| `storage.durability` | `"ram"` (fast, last ~250 ms lost on crash) or `"wal-batched"` (fsync per batch, no loss) |
| `storage.retention` | How long to keep records. Default `4w` |
| `defaults.radius` | Default `r:` value. Set to 0 to imply `-g` |
| `defaults.time` | Default `t:` value. Default `4h` |
| `limits.max-radius` | Cap on `r:` and `-we` |
| `events.<name>.enabled` | Toggle individual event types |

## Examples

```
# Who broke this block in the last day?
/sg search b:* t:1d r:5

# Roll back every place + break a player did in the last 2 hours, globally.
/sg rollback p:Bob a:place,break t:2h -g

# Who picked up that diamond sword named Excalibur?
/sg search a:pickup iname:Excalibur t:1w -g

# Every command a specific IP ran today.
/sg search ip:1.2.3.4 a:command t:1d -g

# Restore everything inside my WorldEdit selection that was broken last hour.
//wand   (select region with WorldEdit)
/sg restore a:break t:1h -we
```

## Build

```
./gradlew :spyglass:shadowJar       # plugin jar -> spyglass/build/libs/Spyglass-1.0.0.jar
./gradlew build                     # everything: jars, tests, coverage
./gradlew deployToRpServer          # build + copy to ../RP_Server/plugins/
```

## Modules

- `spyglass-api/` - public API. Third-party plugins depend on this only.
- `spyglass-core/` - shared internals (codecs, storage glue).
- `spyglass/` - the Paper plugin.
- `spyglass-velocity/` - optional Velocity proxy companion for cross-server chat and command capture.

## Stack

- Paper 1.21.8, JDK 21
- Gradle 9 with the Shadow plugin
- MongoDB Java driver (POJO codec) or ClickHouse client v2
- Kyori Adventure for chat rendering
- Incendo Cloud 2.x for the command framework

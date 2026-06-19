# Spyglass

Forensic logging and rollback for Paper 1.21.x. Spyglass records every block, container, chat, command, combat, and movement event on the server, exposes them through a composable `key:value` query language, and reverses any subset of them — per block, per player, per cause, or in bulk — while the server holds 20 TPS.

## Performance

The same 2,000,376-block grief, rolled back four ways — Spyglass and CoreProtect each on both of their backends — measured head-to-head on one server (Paper 1.21.8, 6 GB heap, stock Aikar G1 flags). Each figure is the warmed steady state across three 2M runs driven by [`regression/bot/compare.js`](regression/bot/compare.js).

| 2M-block rollback | Spyglass · ClickHouse | Spyglass · MongoDB | CoreProtect · SQLite | CoreProtect · MySQL |
|---|---|---|---|---|
| Rollback wall-clock | **~6 s** | ~19 s | ~11 s | ~15 s |
| Undo / restore wall-clock | **~5 s** | ~21 s | ~10 s | ~65 s |
| TPS during the op (min / avg) | **20.0 / 20.0** | **20.0 / 20.0** | 15 / 19 | 13 / 19 |
| Worst single tick | ~60 ms | ~100 ms¹ | ~350 ms | ~700 ms |

¹ A garbage-collection pause on Mongo's record-read path — often under 20 ms warmed, occasionally ~390 ms.

**Spyglass never drops below 20 TPS on either backend.** It streams records into bounded windows and applies them off the main thread, tick-paced, so the server holds a flat 20 TPS and the worst single tick stays small. CoreProtect applies on the main thread: its rollback sags the server's TPS, and its worst single ticks run into the hundreds of milliseconds — restores approach a full second.

**Backend choice drives the wall-clock.** On ClickHouse, Spyglass's columnar lean read makes it both the fastest and the smoothest. On MongoDB the read path is heavier — every record is a full BSON decode — so per-operation wall-clock trails CoreProtect's, and Spyglass trades raw speed for the flat TPS. CoreProtect is quicker against a local SQLite file than over MySQL's per-row TCP round-trips, especially on restore.

Whichever backend, a rollback adds **one row** to Spyglass's store — the operation is synthesized on read, not re-logged — while CoreProtect re-logs every rolled-back block. Run Spyglass on ClickHouse for the best speed and the flat TPS together.

## Status

`v1.0.0` — feature-complete. Ships 40+ event types, block + container + entity rollback, vanilla WorldEdit and FAWE capture, the `/spyglass tool` inspection wand, an unlimited-depth undo stack, a job queue with crash-resume, and a public API for third-party integration.

## Requirements

- Paper 1.21.8 (or newer 1.21.x)
- Java 21
- One of:
  - MongoDB at `mongodb://localhost:27017` (default)
  - ClickHouse (set `database.backend = "clickhouse"` in config)
- Optional: WorldEdit 7.3+ or FastAsyncWorldEdit 2.15+ for `//set` / `//paste` capture and `-we` queries

## Commands

Root: `/spyglass`. Short alias: `/sg`. All subcommands accept short aliases too (`/sg s` = `/sg search`). Every permission defaults to `op`; grant them through your permissions plugin to scope access.

| Command | Aliases | Permission | What it does |
|---|---|---|---|
| `/sg help` | `h`, `?` | `spyglass.use` | Command help |
| `/sg events` | `e` | `spyglass.use` | List enabled event types |
| `/sg search <query>` | `s`, `sc`, `lookup`, `l` | `spyglass.search` | Search the event log |
| `/sg page <n>` | `p`, `pg` | `spyglass.use` | Page through the last search result |
| `/sg rollback <query>` | `rb`, `roll` | `spyglass.rollback` | Revert matched events |
| `/sg restore <query>` | `rs`, `rst` | `spyglass.rollback` | Re-apply previously-rolled-back events |
| `/sg undo` | `u` | `spyglass.rollback` | Undo your most recent rollback / restore |
| `/sg rbqueue [...]` | `queue`, `rbq` | `spyglass.rollback` | List, cancel, or resume rollback jobs |
| `/sg tool` | `t`, `inspect` | `spyglass.tool` | Toggle the inspection wand |
| `/sg tele <world> <x> <y> <z>` | - | `spyglass.tele` | Teleport (used by clickable search results) |

Two permissions gate a feature rather than a whole command:

- `spyglass.search.ip` — see join IPs in results and use the `ip:` key (on Paper and the proxy). Without it, IPs render as `(ip hidden)` and `ip:` errors.
- `spyglass.worldedit` — allows the `-we` flag (use your WorldEdit selection as the search region).

### Query syntax

Search, rollback, and restore all take the same `key:value` query language. Combine as many keys as you want; results match all of them. On `p:`, `a:`, `b:`, and `c:`, prefix a value with `!` to exclude it instead.

```
/sg search p:Steve b:diamond_ore t:1d r:50
/sg rollback p:griefer a:break,!place t:6h r:100
```

#### Query keys

| Key | Aliases | Example | Notes |
|---|---|---|---|
| `p:` | `player:` | `p:Steve,Alex` · `p:!Steve` | Comma-separated for OR; `!name` excludes |
| `a:` | `action:`, `event:` | `a:break,place` · `a:!place` | Event type. See `/sg events`; `!name` excludes |
| `b:` | `block:` | `b:diamond_ore` · `b:!chest` | Target block material; `!material` excludes |
| `i:` | `item:` | `i:netherite_sword` | Item material involved (drop, pickup, container, etc.) |
| `iname:` | `itemname:` | `iname:Excalibur` | Item display name (substring). Works on both backends |
| `ilore:` | `itemlore:`, `d:` | `ilore:cursed` | Item lore line (substring). Works on both backends |
| `ench:` | `enchant:`, `enchantment:` | `ench:sharpness=5` | Item enchantment. Works on both backends |
| `cu:` | `custom:` | `cu:my-custom-item` | Plugin custom-item id (via the API) |
| `e:` | `entity:` | `e:creeper` | Entity type involved |
| `c:` | `cause:` | `c:tnt,!creeper` | Change cause; `!cause` excludes |
| `m:` | `message:` | `m:hello` | Chat / sign / book text (substring) |
| `rcp:` | `recipient:` | `rcp:Steve` | Private-message recipient |
| `r:` | `radius:` | `r:50` | Search radius around you (blocks). Default applies if omitted; use `-g` for global |
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

### Explosion attribution

Player-lit TNT records the igniter as the actor: `p:<griefer>` searches and rollbacks cover the crater directly. Chained, dispensed, or redstone-primed TNT and mob explosions are entity-attributed — sweep those with `c:tnt`, `c:creeper`, etc.

### Inspection wand

```
/sg tool
```

Toggles an inspection mode. Left-click a block to see its full history — including entries for blocks a previous rollback restored. Right-click to see what's about to happen near it. Toggle off the same way.

### Rollback

Rollback takes the same `key:value` query as search. Preview with `/sg search` first to confirm what will be reverted, then swap in `rollback`:

```
/sg search p:griefer t:6h r:100      # see what matched
/sg rollback p:griefer t:6h r:100    # revert it
```

That reverts everything `griefer` did in the last 6 hours within 100 blocks. Matched blocks are force-overwritten to their recorded state regardless of what occupies them now, so water, lava, or fire that flowed in afterward is cleared too — scope the query by player, region, and time to the grief you mean to undo. There is no size limit.

Undo and restore:

```
/sg undo                             # reverse your last rollback or restore
/sg restore p:griefer t:6h r:100     # re-apply something you undid
```

`/sg undo` reverses your most recent operation; run it again to keep unwinding, newest first. References last 24 hours.

### Queue

Rollbacks run one at a time; the rest wait. Manage them with `/sg rbqueue`:

```
/sg rbqueue              # list running, pending, and resumable jobs
/sg rbqueue stop         # stop the running job after its current batch
/sg rbqueue cancel <id>  # cancel a pending or running job by id
/sg rbqueue resume <id>  # resume a job interrupted by a crash or restart
```

If the server crashes mid-rollback, the job shows up as resumable on next start. `resume` re-runs it from a saved cursor and lands on the same result.

## Examples

```
# Who broke this block in the last day?
/sg search b:* t:1d r:5

# Roll back every place + break a player did in the last 2 hours, globally.
/sg rollback p:Bob a:place,break t:2h -g

# TNT crater: player-lit TNT is attributed to the igniter.
/sg rollback p:griefer t:1h r:30

# Roll back everything except containers (don't disturb chest contents).
/sg rollback p:griefer t:6h r:100 b:!chest

# Who picked up that diamond sword named Excalibur?
/sg search a:pickup iname:Excalibur t:1w -g

# Track a specific enchanted item through drops and pickups.
/sg search ench:sharpness=5 t:1d -g

# Every command a specific IP ran today.
/sg search ip:1.2.3.4 a:command t:1d -g

# Restore everything inside my WorldEdit selection that was broken last hour.
//wand   (select region with WorldEdit)
/sg restore a:break t:1h -we
```

## Modules

- `spyglass-api/` - public API. Third-party plugins depend on this only.
- `spyglass-core/` - shared internals (codecs, storage glue).
- `spyglass/` - the Paper plugin.
- `spyglass-velocity/` - optional Velocity proxy companion for cross-server search. Read-only by design: it never writes records and never rolls back.

## Stack

- Paper 1.21.8, JDK 21
- Gradle 9 with the Shadow plugin
- MongoDB Java driver (POJO codec) or ClickHouse client v2
- Kyori Adventure for chat rendering
- Incendo Cloud 2.x for the command framework

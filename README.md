# Spyglass (Preview)

Forensic logging and rollback for Paper 1.21.x. Spyglass records block, container, chat, command, combat, and movement events, lets you query them with a `key:value` language, and rolls any of them back by block, player, cause, or in bulk while the server holds 20 TPS.

> **Preview.** Spyglass is built for medium and large servers. The embedded SQLite backend runs it with no external database, so a small server can use it too, though CoreProtect or Prism stay lighter-weight there.

Support: [discord.gg/XkpVHcHvH](https://discord.gg/XkpVHcHvH)

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

A 2,000,376-block rollback, measured five ways: Spyglass on each of its three backends and CoreProtect on both of theirs, on one server (Paper 1.21.8, 6 GB heap, stock Aikar flags).

| 2M-block rollback | Spyglass · ClickHouse | Spyglass · MongoDB | Spyglass · SQLite | CoreProtect · SQLite | CoreProtect · MySQL |
|---|---|---|---|---|---|
| Rollback wall-clock | ~5 s | ~7 s | **~3 s** | ~11 s | ~12 s |
| Undo / restore wall-clock | ~4 s | ~6 s | **~3 s** | ~9 s | ~210 s |
| TPS during the op (min / avg) | **20.0 / 20.0** | **20.0 / 20.0** | **20.0 / 20.0** | 14 / 19 | 13 / 20 |
| Worst single tick | ~50 ms | ~100 ms | **~37 ms** | ~670 ms | ~270 ms |
| On-disk footprint (data + index) | **~11 MiB** | ~145 MiB | ~156 MiB | ~160 MiB | ~180 MiB |

A live [spark profile](https://spark.lucko.me/5JzJrfOmaM) of Spyglass running in production on [Crusalis](https://crusalis.net), with 1,000 players online at the time.

> **Why MongoDB?** the60th asks me the same question. I really just like using MongoDB. Although we recommend servers utilize ClickHouse for performance and efficient disk space usage. MySQL will not come to Spyglass. 

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
| Storage engines | SQLite, MongoDB, ClickHouse | SQLite, MySQL |
| Minecraft versions | 1.21.x | 1.7+ |

Spyglass runs on SQLite, MongoDB, or ClickHouse. The embedded SQLite backend needs no external database, so the zero-ops install CoreProtect offers is available on Spyglass too; MongoDB and ClickHouse are there when you outgrow it.

## Requirements

- Paper 1.21.8 or newer 1.21.x
- Java 21
- A database, one of:
  - Embedded SQLite, no external database (the default); writes to a file under the plugin folder
  - MongoDB (set `database.backend = "mongo"`) at `mongodb://localhost:27017`
  - ClickHouse (set `database.backend = "clickhouse"`)
- Optional: WorldEdit 7.3+ or FastAsyncWorldEdit 2.15+ for WorldEdit-edit capture and `-we` queries. Capture hooks the edit-session pipeline, not command names, so every block-mutating operation is recorded - `//set`, `//replace`, `//walls`, `//overlay`, `//paste`, schematic paste, brushes, generation, `//move`/`//stack`, and `//undo`/`//redo` alike - for player and non-player (console/plugin) edits

## Commands

Root command is `/spyglass`, aliased to `/sg`. Subcommands take short aliases too, so `/sg s` is `/sg search`. Every permission defaults to `op`; grant them through your permissions plugin to open them up.

| Command | Aliases | Permission | What it does |
|---|---|---|---|
| `/sg help` | `h`, `?` | `spyglass.use` | Command help |
| `/sg events` | `e` | `spyglass.use` | List enabled event types |
| `/sg search <query>` | `s`, `sc`, `lookup`, `l` | `spyglass.search` | Search the event log |
| `/sg page <n>` | `p`, `pg` | `spyglass.use` | Page through the last search result |
| `/sg rollback <query>` | `rb`, `roll` | `spyglass.rollback` | Revert matched events |
| `/sg restore <query>` | `rs`, `rst` | `spyglass.rollback` | Re-apply previously-rolled-back events |
| `/sg undo` | `u` | `spyglass.rollback` | Undo your most recent rollback or restore |
| `/sg rbqueue [...]` | `queue`, `rbq` | `spyglass.rollback` | List, cancel, or resume rollback jobs |
| `/sg tool` | `t`, `inspect` | `spyglass.tool` | Toggle the inspection wand |
| `/sg tele <world> <x> <y> <z>` | - | `spyglass.tele` | Teleport (used by clickable search results) |

Two permissions gate a feature rather than a command:

- `spyglass.search.ip` lets you see join IPs in results and use the `ip:` key, on Paper and the proxy. Without it, IPs show as `(ip hidden)` and `ip:` errors.
- `spyglass.worldedit` lets you pass the `-we` flag to use your WorldEdit selection as the search region.

### Query syntax

Search, rollback, and restore share one `key:value` query language. Combine as many keys as you want; a result has to match all of them. On `p:`, `a:`, `b:`, and `c:`, put `!` in front of a value to exclude it.

```
/sg search p:Steve b:diamond_ore t:1d r:50
/sg rollback p:griefer a:break,!place t:6h r:100
```

#### Query keys

Values are plain terms. Wrap in double quotes to search for a value that contains spaces or colons: `iname:"Storm Caller"`, `itags:"mmoitems:type"`.

| Key | Aliases | Example | Notes |
|---|---|---|---|
| `p:` | `player:` | `p:Steve,Alex` · `p:!Steve` | Comma-separated for OR; `!name` excludes |
| `a:` | `action:`, `event:` | `a:break,place` · `a:!place` | Event type. See `/sg events`; `!name` excludes |
| `b:` | `block:` | `b:diamond_ore` · `b:!chest` | Target block material; `!material` excludes |
| `i:` | `item:` | `i:netherite_sword` | Item material involved (drop, pickup, container, etc.) |
| `iname:` | `itemname:` | `iname:Excalibur` · `iname:"Storm Caller"` | Item display name (substring). Works on both backends |
| `ilore:` | `itemlore:`, `d:` | `ilore:cursed` · `ilore:"for the worthy"` | Item lore line (substring). Works on both backends |
| `itags:` | `itag:` | `itags:deliver_letter` · `itags:"mmoitems:type"` | Item custom data / NBT (substring): vanilla `custom_data`, datapack, and plugin PDC values. Works on all backends |
| `ench:` | `enchant:`, `enchantment:`, `ienchant:`, `ienchantments:` | `ench:sharpness=5` | Item enchantment. Works on both backends |
| `cu:` | `custom:` | `cu:my-custom-item` | Item carries metadata: custom name, lore, enchants, or custom data |
| `e:` | `entity:` | `e:creeper` | Entity type involved |
| `c:` | `cause:` | `c:tnt,!creeper` | Change cause; `!cause` excludes |
| `m:` | `message:` | `m:hello` | Chat, sign, or book text (substring) |
| `rcp:` | `recipient:` | `rcp:Steve` | Private-message recipient |
| `r:` | `radius:` | `r:50` | Search radius around you in blocks. Default applies if omitted; use `-g` for global |
| `t:` | `since:` | `t:1d`, `t:30m`, `t:2w` | Time window. Default is 4h |
| `w:` | `world:` | `w:world_nether` | Restrict to one world |
| `srv:` | `server:` | `srv:survival` | Restrict to one server name (multi-server setups) |
| `trg:` | `target:` | `trg:100,64,200` | Specific block coords |
| `ip:` | - | `ip:192.168.1.10` | Source IP. Requires the `spyglass.search.ip` permission |

#### Flags

Flags start with `-` and take no value unless noted.

| Flag | Aliases | Effect |
|---|---|---|
| `-g` | `-global` | Skip the default radius for a whole-world or whole-server search |
| `-we` | `-worldedit` | Use your active WorldEdit selection as the region |
| `-ord:<asc\|desc>` | `-order:` | Sort order. Default is newest first for search and rollback, oldest first for restore |
| `-ng` | `-nogroup` | Don't merge duplicate adjacent events in the result list |
| `-nc` | `-nochat` | Don't echo the summary line to chat (action-bar only) |
| `-ex` | `-extended` | Include extra detail columns in the result list |
| `-nod:<keys>` | `-nodefault:` | Drop defaults. `-nod:r,t` runs with no radius or time bound |

#### Time formats

`30s`, `15m`, `4h`, `2d`, `1w`, `1mo`. Combine them: `t:1d12h`.

### Explosion attribution

Player-lit TNT records the igniter as the actor, so `p:<griefer>` searches and rollbacks cover the crater. Chained, dispensed, or redstone-primed TNT and mob explosions are attributed to the entity instead; reach those with `c:tnt`, `c:creeper`, and so on.

### Inspection wand

```
/sg tool
```

Toggles inspection mode. Left-click a block to see its full history, including blocks a previous rollback restored. Right-click to preview what is about to happen near it. Toggle off the same way.

### Rollback

Rollback uses the same query as search. Run it as a `/sg search` first to confirm what it matches, then swap in `rollback`:

```
/sg search p:griefer t:6h r:100      # see what matched
/sg rollback p:griefer t:6h r:100    # revert it
```

This reverts everything `griefer` did in the last 6 hours within 100 blocks. Matched blocks are force-overwritten to their recorded state no matter what occupies them now, so water, lava, or fire that flowed in afterward is cleared out as well. Scope the query by player, region, and time to match the grief you want to undo. There is no size limit.

Undo and restore:

```
/sg undo                             # reverse your last rollback or restore
/sg restore p:griefer t:6h r:100     # re-apply something you undid
```

`/sg undo` reverses your most recent operation. Run it again to keep walking back, newest first. Undo references last 24 hours.

### Queue

Rollbacks run one at a time and the rest wait in line. Manage them with `/sg rbqueue`:

```
/sg rbqueue              # list running, pending, and resumable jobs
/sg rbqueue stop         # stop the running job after its current batch
/sg rbqueue cancel <id>  # cancel a pending or running job by id
/sg rbqueue resume <id>  # resume a job interrupted by a crash or restart
```

If the server crashes mid-rollback, the job comes back as resumable on the next start. `resume` re-runs it from a saved cursor and lands on the same result.

## Examples

```
# Who broke this block in the last day?
/sg search b:* t:1d r:5

# Roll back every place and break a player did in the last 2 hours, globally.
/sg rollback p:Bob a:place,break t:2h -g

# TNT crater: player-lit TNT is attributed to the igniter.
/sg rollback p:griefer t:1h r:30

# Roll back everything except containers, leaving chest contents alone.
/sg rollback p:griefer t:6h r:100 b:!chest

# Who picked up that diamond sword named Excalibur?
/sg search a:pickup iname:Excalibur t:1w -g

# Track a specific enchanted item through drops and pickups.
/sg search ench:sharpness=5 t:1d -g

# Find items tagged by a plugin. Values that contain colons require quotes.
/sg search a:deposit itags:"mmoitems:type" t:1d -g

# Find an item by its full multi-word name. Values with spaces require quotes.
/sg search iname:"Storm Caller" t:1w -g

# Every command a specific IP ran today.
/sg search ip:1.2.3.4 a:command t:1d -g

# Restore everything inside my WorldEdit selection that was broken last hour.
//wand   (select region with WorldEdit)
/sg restore a:break t:1h -we
```

## Modules

- `spyglass-api/` is the public API. Third-party plugins depend on this only.
- `spyglass-core/` holds the shared internals (codecs, storage glue).
- `spyglass/` is the Paper plugin.
- `spyglass-velocity/` is an optional Velocity proxy companion for cross-server search. It is read-only by design: it never writes records and never rolls back.

## AI policy

Spyglass is human-led. We make the architectural and design decisions, and we will defend every line that ships. AI assists with boilerplate, testing, and drafting issues. Nothing merges to main without a review and a passing test suite, and every performance claim here is measured on a real server.

## License

Spyglass is open source under a split license, mapped in [LICENSING.md](LICENSING.md):

- The public extension API (`spyglass-api`) is licensed under the [Apache License 2.0](spyglass-api/LICENSE), so third-party plugins can depend on it freely.
- The plugin and its internals (`spyglass-core`, `spyglass`, `spyglass-velocity`) are licensed under the [GNU General Public License v3.0](LICENSE).

Contributions are accepted under the [Contributor License Agreement](CLA.md); see [CONTRIBUTING.md](CONTRIBUTING.md).

# Spyglass

Forensic logging and rollback for Paper 1.21.x. Spyglass records block, container, chat, command, combat, and movement events, lets you query them with a `key:value` language, and rolls any of them back by block, player, cause, or in bulk while the server holds 20 TPS.

> **Preview.** Spyglass is built for medium and large servers. It will run on a small one, but it is overkill there; CoreProtect or Prism are a better fit for small servers.

## Performance

A 2,000,376-block rollback, measured four ways: Spyglass and CoreProtect each on both of their backends, on one server (Paper 1.21.8, 6 GB heap, stock Aikar flags). Each number is the warmed steady state over three runs of [`regression/bot/compare.js`](regression/bot/compare.js).

| 2M-block rollback | Spyglass · ClickHouse | Spyglass · MongoDB | CoreProtect · SQLite | CoreProtect · MySQL |
|---|---|---|---|---|
| Rollback wall-clock | **~6 s** | ~19 s | ~11 s | ~15 s |
| Undo / restore wall-clock | **~5 s** | ~21 s | ~10 s | ~65 s |
| TPS during the op (min / avg) | **20.0 / 20.0** | **20.0 / 20.0** | 15 / 19 | 13 / 19 |
| Worst single tick | ~60 ms | ~100 ms | ~350 ms | ~700 ms |

## Requirements

- Paper 1.21.8 or newer 1.21.x
- Java 21
- A database, one of:
  - MongoDB at `mongodb://localhost:27017` (default)
  - ClickHouse (set `database.backend = "clickhouse"` in config)
- Optional: WorldEdit 7.3+ or FastAsyncWorldEdit 2.15+ for `//set` and `//paste` capture and `-we` queries

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

## License

Spyglass is source-available under the [PolyForm Internal Use License 1.0.0](LICENSE), with an added permission covering Minecraft servers. You may run it and fork it for your own servers, including commercial ones. You may not redistribute it, sublicense it, or sell it or access to it.

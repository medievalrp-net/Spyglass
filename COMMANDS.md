# Spyglass Commands & Permissions

Full reference for every command, permission, query key, and flag. For the project overview and feature comparison, see the [README](README.md).

Root command is `/spyglass`, aliased to `/sg`. Subcommands take short aliases too, so `/sg s` is `/sg search`. Every permission defaults to `op`; grant them through your permissions plugin to open them up.

## Commands

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
| `/sg inventory` | `inv`, `salvage` | `spyglass.salvage` | Recover items a rollback destroyed (GUI, or a listing where there is no GUI) |
| `/sg inventory <id>` | `inv`, `salvage` | `spyglass.salvage` | Recover a container's items by id, via command |
| `/sg tool` | `t`, `inspect` | `spyglass.tool` | Toggle the inspection wand |
| `/sg import <file \| mysql <source>>` | - | `spyglass.import` | Import a CoreProtect database: a SQLite file from `plugins/Spyglass/import/`, or a live MySQL source defined in `import.conf` |
| `/sg migrate <backend>` | - | `spyglass.migrate` | Copy every record from the active backend into another configured backend |

## Permissions

All default to `op`.

| Permission | Grants |
|---|---|
| `spyglass.use` | help, events, page |
| `spyglass.search` | search |
| `spyglass.search.ip` | reveals join IPs in results and unlocks the `ip:` key. Without it, IPs render as `(ip hidden)` and `ip:` errors. Applies on Paper and the proxy |
| `spyglass.rollback` | rollback, restore, undo, rbqueue |
| `spyglass.salvage` | `/sg inventory` container salvage (recover items a rollback destroyed). Independent of `spyglass.rollback` |
| `spyglass.tool` | inspection wand |
| `spyglass.tele` | teleport (used by clickable result rows) |
| `spyglass.worldedit` | allows the `-we` flag to use your WorldEdit selection as the search region |
| `spyglass.import` | `/sg import` - CoreProtect imports |
| `spyglass.migrate` | `/sg migrate` - moving records between storage backends |

## Query syntax

Search, rollback, and restore share one `key:value` query language. Combine as many keys as you want; a result has to match all of them. On `p:`, `a:`, `b:`, and `c:`, put `!` in front of a value to exclude it.

```
/sg search p:Steve b:diamond_ore t:1d r:50
/sg rollback p:griefer a:break,!place t:6h r:100
```

### Query keys

Values are plain terms. Wrap in double quotes to search for a value that contains spaces or colons: `iname:"Storm Caller"`, `itags:"mmoitems:type"`.

| Key | Aliases | Example | Notes |
|---|---|---|---|
| `p:` | `player:` | `p:Steve,Alex` · `p:!Steve` | Comma-separated for OR; `!name` excludes |
| `a:` | `action:`, `event:` | `a:break,place` · `a:!place` | Event type. See `/sg events`; `!name` excludes |
| `b:` | `block:` | `b:diamond_ore` · `b:!chest` | Target block material; `!material` excludes |
| `i:` | `item:` | `i:netherite_sword` | Item material involved (drop, pickup, container, etc.) |
| `iname:` | `itemname:` | `iname:Excalibur` · `iname:"Storm Caller"` | Item display name (substring) |
| `ilore:` | `itemlore:`, `d:` | `ilore:cursed` · `ilore:"for the worthy"` | Item lore line (substring) |
| `itags:` | `itag:` | `itags:deliver_letter` · `itags:"mmoitems:type"` | Item custom data / NBT (substring): vanilla `custom_data`, datapack, and plugin PDC values |
| `ench:` | `enchant:`, `enchantment:`, `ienchant:`, `ienchantments:` | `ench:sharpness=5` | Item enchantment |
| `cu:` | `custom:` | `cu:my-custom-item` | Item carries metadata: custom name, lore, enchants, or custom data |
| `e:` | `entity:` | `e:creeper` | Entity type involved |
| `c:` | `cause:` | `c:tnt,!creeper` | Change cause; `!cause` excludes |
| `m:` | `message:` | `m:hello` | Chat, sign, or book text (substring) |
| `rcp:` | `recipient:` | `rcp:Steve` | Private-message recipient |
| `r:` | `radius:` | `r:50` | Search radius around you in blocks. Default applies if omitted; use `-g` for global |
| `cr:` | `chunkradius:` | `cr:1` · `cr:3` | Search radius in chunks, full height. `cr:1` = your chunk, `cr:2` = 3x3. Overrides the default radius |
| `t:` | `since:` | `t:1d`, `t:30m`, `t:2w` | Lower time bound (how far back). Default is 4h |
| `before:` | - | `t:12h before:6h` | Upper time bound. `t:12h before:6h` = events between 12h and 6h ago |
| `w:` | `world:` | `w:world_nether` | Restrict to one world |
| `srv:` | `server:` | `srv:survival` | Restrict to one server name (multi-server setups) |
| `trg:` | `target:` | `trg:100,64,200` | Specific block coords `x,y,z` |
| `ip:` | - | `ip:192.168.1.10` | Source IP. Requires the `spyglass.search.ip` permission |

### Flags

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

### Time formats

`30s`, `15m`, `4h`, `2d`, `1w`, `1mo`. Combine them: `t:1d12h`.

## Explosion attribution

Player-lit TNT records the igniter as the actor, so `p:<griefer>` searches and rollbacks cover the crater. Chained, dispensed, or redstone-primed TNT and mob explosions (creeper, wither) are attributed to the entity instead; reach those with `c:tnt`, `c:creeper`, and so on.

## Inspection wand

```
/sg tool
```

Toggles inspection mode. Left-click a block to see its full history, including blocks a previous rollback restored. Right-click to preview what is about to happen near it. Toggle off the same way.

## Rollback

Rollback uses the same query as search. Run it as a `/sg search` first to confirm what it matches, then swap in `rollback`:

```
/sg search p:griefer t:6h r:100      # see what matched
/sg rollback p:griefer t:6h r:100    # revert it
```

This reverts everything `griefer` did in the last 6 hours within 100 blocks. There is no size limit.

Rollback and restore **force-overwrite**: each matched block is set back to its recorded state regardless of what is there now (matching the original Spyglass and CoreProtect). This is what you want for grief recovery - if water, lava, fire, or falling blocks drifted into a griefed area after the edit, the rollback still restores the original blocks over them. It also means a re-run is idempotent (re-applies to the same state) and a `/sg undo` cleanly reverses it.

The trade-off: a rollback does not skip a cell just because someone changed it afterward, so a rollback scoped to one player can overwrite a *legitimate* later edit by another player in those exact cells. Scope the query by player, region `r:`, and time `t:` to the grief you mean to revert; review with `/sg search` first if unsure. Items in a container the rollback overwrites are recoverable - see [Container salvage](#container-salvage).

### Undo and restore

```
/sg undo                             # reverse your last rollback or restore
/sg restore p:griefer t:6h r:100     # re-apply something you undid
```

`/sg undo` reverses your most recent operation. Run it again to keep walking back, newest first. Undo references the last 24 hours.

## Queue

Rollbacks run one at a time and the rest wait in line. Manage them with `/sg rbqueue`:

```
/sg rbqueue              # list running, pending, and resumable jobs
/sg rbqueue stop         # stop the running job after its current batch
/sg rbqueue cancel <id>  # cancel a pending or running job by id
/sg rbqueue resume <id>  # resume a job interrupted by a crash or restart
```

If the server crashes mid-rollback, the job comes back as resumable on the next start. `resume` re-runs it from a saved cursor and lands on the same result.

## Container salvage

When a force-overwrite rollback destroys a container that had items in it - a chest someone filled after the grief, restored back to stone - those items are **not lost**. The rollback captures the destroyed inventory first and files it under `/sg inventory`, gated behind its own `spyglass.salvage` node (granted independently of `spyglass.rollback`).

On Minecraft 1.21.x, `/sg inventory` (alias `inv`) opens a paginated GUI, grouped by rollback. The first screen lists each **rollback** that destroyed containers (operator, time, and how many containers); click one to see that rollback's **containers** (icons showing type and coordinates); click a container to open its **items**. The bottom row has Back / Previous / Next buttons - every level paginates (45 per page), so a rollback that wiped a 124-chest base browses cleanly. It is **extract-only**: take items out, but you cannot put any in. A container disappears from the GUI once emptied.

On servers without the GUI (Minecraft 26.x) and from the console or RCON, `/sg inventory` prints a text listing instead. Each captured container shows an id; recover it with `/sg inventory <id>` (in-game players only), or click the `[Recover]` prompt next to a listing line. The command withdraws that container's items straight into your inventory server-side, with no inventory-drag surface. Either way, every withdrawal is logged as a `salvage-withdraw` event (find them with `/sg search a:salvage-withdraw`).

Only containers the rollback *actually* destroys are salvaged - a chest in the rolled region that the rollback leaves untouched is never captured, so items are never duplicated. Salvage snapshots are kept for 30 days.

## Importing and migrating

**`/sg import <file>`** imports a CoreProtect (20+) SQLite database. Drop the `.db` file in `plugins/Spyglass/import/` and run the command - the filename tab-completes. **`/sg import mysql <source>`** imports a live CoreProtect MySQL database instead; define sources (host, credentials) in `plugins/Spyglass/import.conf`, kept deliberately separate from Spyglass's own `database.*` config.

Imports run async off the main thread and stream progress to you and the console. Records get deterministic ids, so re-importing a known source is refused unless you pass `--confirm` - and with it, re-imports dedup rather than duplicate (except into MongoDB, where re-import is blocked). If part of the source history is older than `storage.retention`, the import warns up front how much would age out after import and how far back the source goes - raise the retention (or set it `"never"`) first if you want it all kept. Every world the source references must exist on this server (resolved via `<world>/uid.dat`); the import fails cleanly before writing anything if one is missing.

**`/sg migrate <sqlite|mongo|clickhouse|mariadb>`** copies every record from the active backend into another configured one - for switching storage without losing history. Fill in the target backend's block in `config.conf`, run the migrate, then flip `database.backend` and restart. A non-empty target requires `--confirm` (re-runs dedup by record id, except into MongoDB). One migration runs at a time, and it refuses to start while an import is running. Undo history, wand state, and salvage snapshots are operational state and start fresh on the new backend.

The full operator runbook, including the standalone CLI importer and parity validation, is [`docs/migration-from-coreprotect.md`](docs/migration-from-coreprotect.md).

## Event names

`/sg events` prints the live list. Names are stable; they are what you pass to `a:`.

| Name | Covers |
|---|---|
| `break` | Block broken (player, explosion, entity-break, burn, dependent attachments cascade) |
| `place` | Block placed |
| `drop` | Item dropped (player, dispenser, mob, container destroyed) |
| `pickup` | Item picked up |
| `deposit` / `withdraw` | Container slot in / out |
| `entity-deposit` / `entity-withdraw` | Item frame in / out |
| `open` / `close` | Container open / close |
| `shulker-open` / `shulker-close` | Shulker variants of open/close |
| `shulker-deposit` / `shulker-withdraw` | Shulker slot in / out |
| `use` | Block right-click |
| `useSign` | Sign use |
| `bookshelf-insert` / `bookshelf-remove` | Chiseled bookshelf slot edits |
| `pot-insert` / `pot-remove` | Decorated pot transactions |
| `bundle-insert` / `bundle-extract` | Bundle transactions |
| `brush` | Brush on suspicious sand / gravel |
| `vault` | 1.21 vault interaction |
| `crafter` | Crafter block crafted an item (Crafter, not crafting table) |
| `sculk` | Sculk sensor / shrieker triggered by a player |
| `mount` / `dismount` | Entity ride start / end |
| `named` | Name tag rename |
| `say` | Chat |
| `command` | Command (player or console) |
| `join` / `quit` | Login / logout |
| `teleport` | Player teleport |
| `hit` / `shot` / `death` | Combat events |
| `clone` | Creative middle-click pick |
| `ignite` | Fire start (later `burn` records chain back to this) |
| `decay` / `form` / `grow` | Block decay, form, grow |
| `rolled-place` / `rolled-break` | Synthesized records emitted by rollback / restore / undo |

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

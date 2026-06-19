# Spyglass Commands & Permissions

Quick reference. The README has the full user-facing guide with examples.

## Permissions

All default to `op`.

| Permission | Description |
|---|---|
| `spyglass.use` | help, events, page |
| `spyglass.search` | search |
| `spyglass.search.ip` | reveals join IPs in results and unlocks the `ip:` key (without it IPs render as `(ip hidden)`) |
| `spyglass.rollback` | rollback, restore, undo, rbqueue, inventory |
| `spyglass.tool` | inspection wand |
| `spyglass.tele` | teleport (used by clickable result rows) |
| `spyglass.worldedit` | allows the `-we` flag |

## Commands

Root: `/spyglass`. Short alias: `/sg`.

| Command | Subcommand aliases |
|---|---|
| `/spyglass help` | `h`, `?` |
| `/spyglass events` | `e` |
| `/spyglass search <query>` | `s`, `sc`, `lookup`, `l` |
| `/spyglass page <n>` | `p`, `pg` |
| `/spyglass rollback <query>` | `rb`, `roll` |
| `/spyglass restore <query>` | `rs`, `rst` |
| `/spyglass undo` | `u` |
| `/spyglass rbqueue [list\|stop\|cancel <id>\|resume <id>]` | `queue`, `rbq` |
| `/spyglass tool` | `t`, `inspect` |
| `/spyglass inventory` | `inv`, `salvage` |
| `/spyglass tele <world> <x> <y> <z>` | - |

## Query keys

`key:value`. Combine freely; results match all keys. On `p:`, `a:`, `b:`, and `c:`, prefix a value with `!` to exclude it instead — `b:stone,!chest` matches stone but never chests; `a:!place` matches everything except placements.

| Key | Aliases | Notes |
|---|---|---|
| `p` | `player` | Player(s). Comma-separated for OR; `!name` excludes |
| `a` | `action`, `event` | Event type. See `/sg events`; `!name` excludes |
| `b` | `block` | Target block material; `!material` excludes |
| `i` | `item` | Item material |
| `iname` | `itemname` | Item display name (substring) |
| `ilore` | `itemlore`, `d` | Item lore (substring) |
| `ench` | `enchant`, `enchantment` | Enchantment |
| `cu` | `custom` | Plugin custom-item id |
| `e` | `entity` | Entity type |
| `c` | `cause` | Change cause |
| `m` | `message` | Chat / sign / book text |
| `rcp` | `recipient` | Private-message recipient |
| `r` | `radius` | Radius in blocks. Default applies if omitted |
| `t` | `since` | Time window. Default 4h |
| `w` | `world` | World name |
| `srv` | `server` | Server name (multi-server) |
| `trg` | `target` | Block coords `x,y,z` |
| `ip` | - | Source IP |

## Flags

| Flag | Aliases | Effect |
|---|---|---|
| `-g` | `-global` | Skip the default radius |
| `-we` | `-worldedit` | Use the active WorldEdit selection as the region |
| `-ord:<asc\|desc>` | `-order:` | Sort order |
| `-ng` | `-nogroup` | Don't merge duplicate adjacent rows |
| `-nc` | `-nochat` | Suppress the chat summary |
| `-ex` | `-extended` | Show extra detail columns |
| `-nod:<keys>` | `-nodefault:` | Suppress defaults (e.g. `-nod:r,t`) |

## Rollback behavior

Rollback and restore **force-overwrite**: each matched block is set back to its recorded state regardless of what is there now (matching the original Spyglass and CoreProtect). This is what you want for grief recovery — if water, lava, fire, or falling blocks drifted into a griefed area after the edit, the rollback still restores the original blocks over them. It also means a rollback re-run is idempotent (re-applies to the same state) and a `/spyglass undo` cleanly reverses it.

The trade-off: a rollback does not skip a cell just because someone changed it afterward, so a rollback scoped to one player can overwrite a *legitimate* later edit by another player in those exact cells. Scope the rollback (by player, region `r:`, and time `t:`) to the grief you mean to revert; review with `/spyglass lookup` first if unsure. Items in a container the rollback overwrites are recoverable — see below.

## Container salvage

When a force-overwrite rollback destroys a container that had items in it — a chest someone filled after the grief, restored back to stone — those items are **not lost**. The rollback captures the destroyed inventory first and files it under `/spyglass inventory`.

`/spyglass inventory` (alias `inv`) opens a paginated GUI, grouped by rollback. The first screen lists each **rollback** that destroyed containers (operator, time, and how many containers); click one to see that rollback's **containers** (icons showing the type and coordinates); click a container to open its **items**. The bottom row has Back / Previous / Next buttons — every level paginates (45 per page), so a rollback that wiped a 124-chest base browses cleanly. It's **extract-only**: take items out, but you cannot put any in. Each withdrawal is logged as a `salvage-withdraw` event (find them with `/sg search a:salvage-withdraw`); a container disappears from the GUI once emptied. From the console or RCON the command prints a flat text listing instead.

Only containers the rollback *actually* destroys are salvaged — a chest in the rolled region that the rollback leaves untouched is never captured, so items are never duplicated. Salvage snapshots are kept for 30 days.

## Explosion grief

Player-lit TNT records the igniter as the source — `p:<griefer>` searches and rollbacks cover the crater directly. Chained, dispensed, or redstone-primed TNT and mob explosions (creeper, wither) are entity-attributed: sweep those with `c:tnt`, `c:creeper`, etc.

## Time formats

`30s`, `15m`, `4h`, `2d`, `1w`, `1mo`. Combine: `t:1d12h`.

## Event names

`/sg events` prints the live list. Names are stable; they're what you pass to `a:`.

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

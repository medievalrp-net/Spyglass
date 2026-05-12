# Spyglass Commands & Permissions

Quick reference. The README has the full user-facing guide with examples.

## Permissions

All default to `op`.

| Permission | Description |
|---|---|
| `spyglass.use` | help, events, page |
| `spyglass.search` | search |
| `spyglass.rollback` | rollback, restore, undo, rbqueue |
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
| `/spyglass tele <world> <x> <y> <z>` | - |

## Query keys

`key:value`. Combine freely; results match all keys.

| Key | Aliases | Notes |
|---|---|---|
| `p` | `player` | Player(s). Comma-separated for OR |
| `a` | `action`, `event` | Event type. See `/sg events` |
| `b` | `block` | Target block material |
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

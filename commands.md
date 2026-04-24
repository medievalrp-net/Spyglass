# Spyglass Commands & Permissions

## Permissions

All permissions default to **op**:

| Permission | Description |
|---|---|
| `spyglass.use` | Basic commands (help, events, page) |
| `spyglass.search` | Search forensics data |
| `spyglass.rollback` | Rollback/restore/undo commands |
| `spyglass.tool` | Inspection wand tool |
| `spyglass.tele` | Teleport to event locations |
| `spyglass.worldedit` | WorldEdit selection in searches |

## Commands

| Command | Permission | Syntax | Description |
|---------|-----------|--------|-------------|
| `/sg help` | `spyglass.use` | `/sg help` | Display command help |
| `/sg events` | `spyglass.use` | `/sg events` | List enabled event types |
| `/sg search` | `spyglass.search` | `/sg search <params> [flags]` | Search forensics database |
| `/sg rollback` | `spyglass.rollback` | `/sg rollback <params> [flags]` | Revert changes |
| `/sg restore` | `spyglass.rollback` | `/sg restore <params> [flags]` | Reapply changes |
| `/sg undo` | `spyglass.rollback` | `/sg undo` | Undo last rollback/restore (players only) |
| `/sg page` | `spyglass.use` | `/sg page <number>` | Navigate result pages |
| `/sg tool` | `spyglass.tool` | `/sg tool` | Toggle inspection wand (players only) |
| `/sg tele` | `spyglass.tele` | `/sg tele <world> <x> <y> <z>` | Teleport to coordinates (players only) |

## Search Parameters

Search, rollback, and restore commands use `alias:value` format:

| Alias | Description | Example |
|-------|-------------|---------|
| `p` | Player | `p:Steve` |
| `b` | Block type | `b:DIAMOND_ORE` |
| `c` | Change cause | `c:PLAYER_BREAK` |
| `e` | Enchantment | `e:SHARPNESS` |
| `ent` | Entity type | `ent:CREEPER` |
| `ev` | Event type | `ev:BLOCK_PLACE` |
| `i` | IP address | `i:192.168.1.100` |
| `lore` | Item lore | `lore:Cursed` |
| `m` | Material | `m:DIAMOND` |
| `n` | Item name | `n:Excalibur` |
| `msg` | Chat message | `msg:hello` |
| `r` | Radius (blocks) | `r:50` |
| `rcpt` | Message recipient | `rcpt:Steve` |
| `t` | Target location | `t:100,64,200` |
| `time` | Time range | `time:1h` |
| `w` | World | `w:world` |
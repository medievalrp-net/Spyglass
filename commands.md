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
| `/omni2 help` | `spyglass.use` | `/omni2 help` | Display command help |
| `/omni2 events` | `spyglass.use` | `/omni2 events` | List enabled event types |
| `/omni2 search` | `spyglass.search` | `/omni2 search <params> [flags]` | Search forensics database |
| `/omni2 rollback` | `spyglass.rollback` | `/omni2 rollback <params> [flags]` | Revert changes |
| `/omni2 restore` | `spyglass.rollback` | `/omni2 restore <params> [flags]` | Reapply changes |
| `/omni2 undo` | `spyglass.rollback` | `/omni2 undo` | Undo last rollback/restore (players only) |
| `/omni2 page` | `spyglass.use` | `/omni2 page <number>` | Navigate result pages |
| `/omni2 tool` | `spyglass.tool` | `/omni2 tool` | Toggle inspection wand (players only) |
| `/omni2 tele` | `spyglass.tele` | `/omni2 tele <world> <x> <y> <z>` | Teleport to coordinates (players only) |

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
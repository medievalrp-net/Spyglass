# Omniscience2 Commands & Permissions

## Permissions

All permissions default to **op**:

| Permission | Description |
|---|---|
| `omniscience2.use` | Basic commands (help, events, page) |
| `omniscience2.search` | Search forensics data |
| `omniscience2.rollback` | Rollback/restore/undo commands |
| `omniscience2.tool` | Inspection wand tool |
| `omniscience2.tele` | Teleport to event locations |
| `omniscience2.worldedit` | WorldEdit selection in searches |

## Commands

| Command | Permission | Syntax | Description |
|---------|-----------|--------|-------------|
| `/omni2 help` | `omniscience2.use` | `/omni2 help` | Display command help |
| `/omni2 events` | `omniscience2.use` | `/omni2 events` | List enabled event types |
| `/omni2 search` | `omniscience2.search` | `/omni2 search <params> [flags]` | Search forensics database |
| `/omni2 rollback` | `omniscience2.rollback` | `/omni2 rollback <params> [flags]` | Revert changes |
| `/omni2 restore` | `omniscience2.rollback` | `/omni2 restore <params> [flags]` | Reapply changes |
| `/omni2 undo` | `omniscience2.rollback` | `/omni2 undo` | Undo last rollback/restore (players only) |
| `/omni2 page` | `omniscience2.use` | `/omni2 page <number>` | Navigate result pages |
| `/omni2 tool` | `omniscience2.tool` | `/omni2 tool` | Toggle inspection wand (players only) |
| `/omni2 tele` | `omniscience2.tele` | `/omni2 tele <world> <x> <y> <z>` | Teleport to coordinates (players only) |

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
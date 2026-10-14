# Spyglass

Logging and rollback for Paper 26.1.2, 26.2 and 26.3. See who changed what, search it in seconds, and roll back griefing without lagging the server.

[Install Spyglass](guide/installation.md){ .md-button .md-button--primary }
[Quick start](guide/quick-start.md){ .md-button }
[Download](https://github.com/medievalrp-net/Spyglass/releases/latest){ .md-button }

## How it compares to CoreProtect

| | Spyglass | CoreProtect |
| --- | :---: | :---: |
| Block, container and entity logging | ✓ | ✓ |
| Chat, command, session and IP logging | ✓ | ✓ |
| Explosions, fire, liquids and growth | ✓ | ✓ |
| Combat damage logging (hits and shots) | ✓ | |
| Teleport and mount logging | ✓ | |
| WorldEdit capture | ✓ | ✓ |
| Password redaction in command logs | ✓ | |
| Inspection wand and lookup | ✓ | ✓ |
| Item search by name, lore or enchantment | ✓ | |
| Cross-server search | ✓ | |
| Extension API (custom events, keys, display) | ✓ | |
| Rollback and restore by player, time or region | ✓ | ✓ |
| Recover items a rollback destroyed | ✓ | |
| One-command undo, any size | ✓ | |
| Resume a rollback a crash interrupted | ✓ | |
| Huge WorldEdit edits survive a crash (disk overflow) | ✓ | |
| Rollbacks add no new log rows | ✓ | |
| Preview a rollback before applying | | ✓ |
| Rollbacks run off the main thread | ✓ | |
| TPS during a 2M-block rollback | **20, flat** | dips to 13 |
| Worst single tick during it | **~50 ms** | ~270 ms |
| Automatic data pruning | ✓ | |
| Databases | ClickHouse, SQLite, MariaDB/MySQL, MongoDB | SQLite, MySQL |
| Minecraft versions | 26.1.2, 26.2, 26.3 ([1.21.11 on legacy](https://github.com/medievalrp-net/Spyglass/releases/tag/v1.0.13)) | 1.7+ |

Performance figures are from a 2,000,376-block rollback on the same server: Spyglass on ClickHouse against CoreProtect on MySQL.

# Permissions

Every permission defaults to **op**. Give them to staff with your permissions plugin, like LuckPerms.

| Permission | Allows |
| --- | --- |
| `spyglass.use` | `/sg help`, `/sg events`, `/sg page`, `/sg version`, `/sg stats` |
| `spyglass.search` | `/sg search`, and `/sgv` on the proxy |
| `spyglass.search.ip` | Seeing IP addresses in results, and the `ip:` key. Without it, IPs show as `(ip hidden)` |
| `spyglass.tool` | The inspection wand (`/sg tool`) |
| `spyglass.tele` | Teleporting by clicking a search result |
| `spyglass.worldedit` | The `-we` flag (search inside your WorldEdit selection) |
| `spyglass.rollback` | `/sg rollback`, `/sg restore`, `/sg undo`, `/sg rbqueue` |
| `spyglass.salvage` | `/sg inventory`: taking back items rollbacks destroyed |
| `spyglass.snapshot` | `/sg snapshot`: viewing past inventories and containers |
| `spyglass.snapshot.take` | Taking a copy of an item out of a snapshot |
| `spyglass.import` | `/sg import` |
| `spyglass.migrate` | `/sg migrate` |
| `spyglass.reload` | `/sg reload` |
| `spyglass.update` | Getting update notices in chat |

## Suggested staff setup

| Role | Permissions |
| --- | --- |
| Helper | `spyglass.use`, `spyglass.search`, `spyglass.tool`, `spyglass.tele` |
| Moderator | Helper, plus `spyglass.rollback`, `spyglass.salvage`, `spyglass.snapshot`, `spyglass.worldedit` |
| Admin | Moderator, plus `spyglass.search.ip`, `spyglass.snapshot.take` |
| Owner | Everything: op, or `spyglass.*` in LuckPerms |

Some permissions are deliberately separate: `spyglass.salvage` and `spyglass.snapshot.take` let someone take items, and `spyglass.search.ip` shows personal data, so none of them come with `spyglass.search` or `spyglass.rollback`.

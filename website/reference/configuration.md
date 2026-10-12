# Configuration

Spyglass is configured in `plugins/Spyglass/config.conf`, created on first start. The file has comments explaining each setting. Some settings apply with [`/sg reload`](../guide/maintenance#reloading-the-config); the rest need a restart.

Times are written like `"30s"`, `"4h"`, `"7d"` or `"26w"`. `"0"` or `"never"` means forever.

## Database

Which storage to use, and how to connect. See [Choosing a database](../guide/databases).

```hocon
database {
  backend = "sqlite"   # "sqlite", "mariadb", "mysql", "mongo" or "clickhouse"
  sqlite { ... }
  mariadb { ... }
  mongo { ... }
  clickhouse { ... }
}
```

## Storage

| Setting | Default | Meaning |
| --- | --- | --- |
| `retention` | `"26w"` | How long records are kept. Reloads live |
| `queue-capacity` | `100000` | Queue size that triggers a "falling behind" warning |
| `queue-max` | `500000` | Hard queue limit. Beyond it, huge WorldEdit edits overflow to disk |
| `spill-to-disk` | `true` | Overflow to `plugins/Spyglass/spill/` instead of memory |
| `spill-drain-rate` | `20000` | Overflow records written back per second (`0` = no limit) |
| `flush-timeout` | `"5s"` | On shutdown, how long to keep trying to save queued events |

## Events

One line per [event type](./events). You can turn any of them off, change the verb shown in results, or give it its own retention:

```hocon
events {
  break = { enabled = true, past-tense = "broke" }
  say   = { enabled = true, past-tense = "said", retention = "30d" }
  transfer-deposit = { enabled = false, past-tense = "piped in" }
}
```

A disabled event isn't recorded at all, so it can't be searched or rolled back. All of this reloads live.

### Hiding passwords in commands

The `command` event has a `redact` list. Arguments of these commands are stored as `***`, so `/login hunter2` is saved as `/login ***`:

```hocon
command = { enabled = true, past-tense = "ran",
  redact = ["login", "register", "l", "reg", "changepassword", "changepw",
            "unregister", "premium", "2fa", "totp", "auth"] }
```

## Search defaults

| Setting | Default | Meaning |
| --- | --- | --- |
| `defaults.enabled` | `true` | Limit searches without `r:` or `-g` to a radius around you. `false` makes `r:` or `-g` required |
| `defaults.radius` | `250` | That radius in blocks. `0` searches everywhere by default |
| `defaults.time` | `"4h"` | How far back a search without `t:` looks |

## Limits

| Setting | Default | Meaning |
| --- | --- | --- |
| `max-radius` | `500` | Largest allowed `r:` |
| `search-result` | `5000` | Most lines a search shows (rollbacks aren't limited) |
| `chat-dump` | `50` | Most chat lines printed at once |
| `rollback-batch-size` | `4000` | Block changes per tick during a rollback. Lower it if chest-heavy rollbacks lag |
| `rollback-tick-budget-ms` | `15` | Milliseconds per tick a rollback may use. Higher is faster but costs TPS |
| `rollback-flush-timeout` | `30s` | How long a rollback waits for very recent events to be saved first |

## Inspection wand

| Setting | Default | Meaning |
| --- | --- | --- |
| `tool.material` | `"REDSTONE_LAMP"` | The wand item. Reloads live |
| `tool.lookback` | `"26w"` | How far back a wand click looks. Reloads live |

## Other settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `commands.s-alias` | `true` | Register `/s` as a shortcut |
| `worldedit.enabled` | `true` | Log WorldEdit and FAWE edits. This is the only switch for them; turning off `place`/`break` doesn't affect WorldEdit |
| `server.name` | `"default"` | This server's name, for `srv:` when servers share a database |
| `metrics.enabled` | `true` | Anonymous [bStats](https://bstats.org) usage stats |
| `analytics.enabled` | `false` | Track recording stats for `/sg stats` and log them every `analytics.interval` |
| `updates.enabled` | `true` | Check GitHub for new releases |

## Inventory snapshots

| Setting | Default | Meaning |
| --- | --- | --- |
| `snapshot.players.enabled` | `false` | Save player inventories for `/sg snapshot p:` |
| `snapshot.players.interval` | `"1m"` | How often online players' inventories are saved (only if they changed) |
| `snapshot.players.retention` | `"30d"` | How long saved inventories are kept |

Container snapshots need no settings.

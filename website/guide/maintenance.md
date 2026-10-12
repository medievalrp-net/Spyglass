# Reloading and updates

## Reloading the config

```
/sg reload
```

Applies config changes without a restart. Needs `spyglass.reload`.

Only these settings reload live:

- Event settings: on/off, the past-tense verb and per-event retention
- `storage.retention`
- Command redaction (the `redact` list)
- The wand item and how far back it looks

Everything else, like the database, queue sizes, search defaults, snapshots and WorldEdit logging, needs a restart. If you changed one of those, the reload is refused and nothing is applied, so you never end up half-reloaded. A config with a syntax error is refused too, and the old settings keep running.

Retention changes apply to new records right away. On SQLite and MariaDB/MySQL, old records are also pruned by the new retention. On MongoDB and ClickHouse, old records keep the expiry they were written with.

## Update notices

Spyglass checks GitHub for a newer release on startup and every six hours. If one is out, players with `spyglass.update` get a clickable notice in chat. `/sg version` shows the latest result.

It only offers releases that run on your Minecraft version. It never downloads or installs anything, and it sends nothing about your players.

To turn it off, set this in `config.conf` and restart:

```hocon
updates { enabled = false }
```

## Live stats

```
/sg stats
```

Shows the disk overflow backlog: events from a huge WorldEdit edit that were parked on disk and aren't searchable until they're written to the database.

For more, set `analytics.enabled = true` in `config.conf` and restart. `/sg stats` then also shows events recorded per second, the write queue and database throughput, and the console logs them every minute.

## Usage statistics (bStats)

Spyglass sends anonymous usage stats to [bStats](https://bstats.org): server version, player-count range, Java version and storage backend. To opt out, set `metrics.enabled = false`.

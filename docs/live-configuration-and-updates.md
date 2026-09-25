# Live configuration and compatible updates

`/sg reload` (permission `spyglass.reload`, default op) validates `config.conf` before applying event enabled/past-tense/retention settings, global event retention, command redaction, and wand material/lookback. Existing tagged wands remain protected when disabled or after changing material. Inspection still requires `spyglass.tool`. Wands cannot be placed, renamed, consumed in processing inventories, or dispensed. While inspection is active, `/sg tool` deactivates it regardless of the selected item. Throwing a tagged tool also deactivates inspection and discards the dropped tool; both paths remove all tagged tools from the player inventory and cursor. Death/container drops also discard tagged wands. A full inventory must have space before a new wand is issued.

Database, queue, defaults/limits, snapshot capture, analytics, aliases, WorldEdit and other service settings require a restart. If a changed service setting is detected, the live reload is rejected without applying the supported settings. Syntax errors and invalid per-event retention also reject reload. The recorder, database, command registrations and pending records remain running.

Retention changes govern new writes immediately. MongoDB and ClickHouse retain the expiry already stored on older records; SQLite and MariaDB pruning uses the current retention policy for existing records as well. Reload does not rewrite historical expiry timestamps or recover records already purged. Shortening retention can cause the next SQL prune to delete older records.

## Update checks

Spyglass anonymously checks the [GitHub releases API](https://docs.github.com/en/rest/releases/releases#list-releases) asynchronously on startup and every six hours. It does not send player information, require a GitHub token, or download/install jars. Set `updates.enabled = false` in `config.conf` and restart to disable it.

Only published stable releases with a plugin asset matching the exact Minecraft version are offered. The legacy line also recognizes the existing `Spyglass.jar` / `Spyglass-shaded.jar` names on 1.x releases. Modern releases must use `Spyglass-<version>-mc<target>.jar` or its `-shaded.jar` companion. API/source/Velocity jars do not qualify. A matching stable release can replace a same-version SNAPSHOT; older releases are never offered as upgrades.

Online players with `spyglass.update` (default op) receive a clickable notification once per offered version per server session; joining operators receive the cached result. `/sg version` and `/sg ver` show cached status without blocking on a network request. Rate limits/network failures report an unavailable check, not an up-to-date result. History is bounded to 1,000 releases and an incomplete scan is reported unavailable.

## Exact-location indexes (#360)

MongoDB creates `spyglass_exact_location_v1` over world ID, x, y, z, descending occurrence time and ID at startup. It complements the existing chunk index used for region searches. Radius-zero queries now use coordinate equality. The initial index build consumes database resources, particularly after large CoreProtect imports; plan the upgrade accordingly.

ClickHouse adds `idx_exact_x`, `idx_exact_y`, and `idx_exact_z` as scalar bloom filters with granularity 1. New data parts use them immediately. Existing data requires an explicit maintenance operation for each index (substitute your configured database and table):

```sql
ALTER TABLE spyglass.event_records MATERIALIZE INDEX idx_exact_x;
ALTER TABLE spyglass.event_records MATERIALIZE INDEX idx_exact_y;
ALTER TABLE spyglass.event_records MATERIALIZE INDEX idx_exact_z;
```

Use `EXPLAIN indexes = 1 SELECT ...` with the actual inspection query to verify granule skipping. These are probabilistic skip indexes, not MongoDB-style B-trees; effectiveness depends on coordinate distribution. Spyglass does not automatically rebuild a large historical ClickHouse table on startup. See the [ClickHouse index documentation](https://clickhouse.com/docs/concepts/features/performance/skip-indexes/skipping-indexes-examples).

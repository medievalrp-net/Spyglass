Mostly a performance pass on the storage backends and the rollback apply path.

- Storage: cheaper SQLite/MariaDB retention sweeps, leaner Mongo inserts, and a fix for a ClickHouse read-after-flush visibility gap.
- Rollback: salvage serialization moved off the main thread, paced chunk loads, faster container reads, and partial-drain rollbacks now log their backlog.
- Fix: cascade dedup key no longer overflows the Y coordinate.

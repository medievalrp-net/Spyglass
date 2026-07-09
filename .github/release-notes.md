The CoreProtect migration release: bring your CoreProtect history into Spyglass, and move it between storage backends.

- Import: /spyglass import loads a CoreProtect 20+ database (SQLite file or live MySQL) into whatever backend you run, with dedup-safe re-imports. It warns up front when part of the history is older than storage.retention and would age out after import.
- Migrate: /spyglass migrate <backend> copies every record from the active backend into another configured one - fill in the target's block in config.conf, migrate, flip database.backend, restart.
- Config: storage.retention now accepts "never" (and 0/forever/off) instead of disabling the plugin on boot.
- Search/rollback: p:<name> resolves players this server never saw (imported histories, shared stores), so rolling back the old griefer by name works.
- Fix: imported expiries are clamped below ClickHouse's TTL ceiling; unclamped they wrapped past 2106 and OPTIMIZE deleted the rows.

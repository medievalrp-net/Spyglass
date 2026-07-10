Command UX polish and two stability fixes, on top of 1.0.8's CoreProtect import and backend migration.

- Help: /sg help now lists every command (import, migrate, inventory, stats, rbqueue...) and paginates, with the same red clickable page arrows as search results.
- Consistency: /sg import and /sg migrate output now use the standard "Spyglass" chat styling, /sg version spacing is fixed, and /sg migrate tab-completes its backend argument.
- /s alias: register /s as a third root command next to /spyglass and /sg (opt in with commands.s-alias, off by default).
- Fix: a null-location record no longer wedges the ClickHouse ingest drain. Such a record is stored with a sentinel location and rejected at the API boundary, so one bad third-party event can't stop the audit log.
- Fix: a capture whose world just unloaded no longer throws on the block break/place path; it records with a worldless sentinel instead.
- Removed: the storage.durability knob and its wal-batched mode. The in-RAM ingest path (with spill-to-disk for bulk edits) is now the only behavior; the fsync-per-batch WAL coupled ingest to disk latency on slow hosts for little real-world gain. Existing configs migrate automatically (the key is dropped, everything else is kept, the old file is backed up). One-time upgrade shim: leftover wal/pending batch files from an unclean stop are replayed into the database on first boot; this shim is removed next release, so upgrade through this version if you ran wal-batched.

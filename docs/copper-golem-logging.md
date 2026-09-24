# Copper-golem inventory history (#364)

Paper 1.21.11 copper golems do not fire `InventoryMoveItemEvent`. Spyglass now observes nearby, validated chest targets and matches the completed inventory delta against the golem's held-item delta. It records `transfer-withdraw` and `transfer-deposit` with the golem UUID and complete before/after slot items. Double chests use the owning half's coordinates and local slot number; copper chest materials are resolved through the generic Container API.

Target validation alone never creates a transfer record. Conflicting player/hopper traffic or multiple matching targets creates `transfer-uncertain` markers instead of guessed slots. `/sg snapshot` treats these markers as uncertain. Automated movement remains excluded from direct area rollback, consistent with hopper transfers; its slot history contributes to container reconstruction.

The listener registers the newer Paper target event by capability, so the 1.21.8 compile API remains supported. Registration and per-event settings participate in `/sg reload`.

Validation: Gradle build passed on Java 21. On the isolated Paper 1.21.11 build 132 fixture, a vanilla copper golem withdrew 16 diamonds from copper-chest slot 0 and deposited 16 into ordinary-chest slot 0. SQLite held both records with the same golem UUID and complete item data (source 32 to 16; destination 1 to 17). Unit tests cover metadata mismatch, competing counts, split deposits and snapshot uncertainty. Live double-chest/weathering permutations and concurrent-machine stress remain unverified.

`regression/bot/verify-copper-golem.mjs` requires an isolated server and the existing regression RCON environment variables. It edits the fixture arena at x=98..120, y=79..86, z=98..106. Never point it at production. Leave `SG_LOGGING_PROBE` unset on legacy servers.

These changes are local; no release or push has occurred.

# Minecraft logging audit (26.1.2 / 26.2 / 26.3)

This audit compares Spyglass's listener registration and record paths with the official release notes and the Paper 26.3 build 38 API/server classes. The six reviewed gaps (#362–#367) now have implementations. The table describes their recording and rollback semantics; dedicated validation and remaining coverage limits are recorded in `docs/logging-fixes-validation.md`.

| Issue / mechanic | Applicable versions | Implementation |
| --- | --- | --- |
| #362 Golden-dandelion age locking | 26.1.2, 26.2, 26.3 | `age-lock` records confirmed before/after state, actor and entity UUID; audit only |
| #363 Sulfur-cube interactions | 26.2, 26.3 | Content, growth, ignition and bucket records preserve item data and actor context; audit only to avoid duplicating items |
| #364 Copper-golem transport | 1.21.11 and modern targets | Tick-boundary inventory/hand comparisons produce exact slot transfers; ambiguous changes mark snapshots uncertain |
| #365 Sulfur-spike cascades | 26.2, 26.3 | Both attachment orientations and connected columns participate in dependent-block capture, including WorldEdit support removal |
| #366 Cushions | 26.3 | Placement and destruction retain full serialized entity state for rollback/undo |
| #367 Straw-bed consumption | 26.3 | Records both consumed halves as block breaks, with occupied state cleared for rollback |


New event entries are merged into existing configuration with bundled defaults; existing explicit settings are preserved. The listeners participate in `/sg reload`. Version-specific event registration is capability-gated, so older supported servers do not need absent Paper event classes.

Ordinary container clicks, drags and open/close use generic block-container handling. Chest repair uses the chest block-data interface. The newer chest materials should therefore not receive duplicate listeners merely because their material names are new. Copper-golem transfers use their own source/destination slot history. Like hopper transfers, these inform inventory reconstruction and are excluded from direct area rollback. Player snapshot and rollback-storage inventory GUIs were exercised separately as regression checks.

Ordinary placement/breaking of the new wood, stone, wool and concrete blocks is already handled generically. New mob names alone do not require new damage/death handlers; the missing interaction mechanics are the issue scope. New support-sensitive plants and mushrooms merit regression coverage when extending the dependent-block rules, without assuming that every registry tag misses them.

Sources: [26.1](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-1), [26.2](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2), [26.3](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3). Paper evidence was inspected from the installed API and server jars, including `TransportItemsBetweenContainers`, `StrawBedBlock`, and `Cushion`.

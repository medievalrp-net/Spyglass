# Minecraft logging audit (26.1.2 / 26.2 / 26.3)

This audit compares Spyglass's listener registration and record paths with the official release notes and the Paper 26.3 build 38 API/server classes. The findings below are for issue review, not implemented features. Acceptance paths in the issues still need dedicated gameplay coverage; source inspection is not presented as an end-to-end reproduction.

| Gap | Applicable versions | Evidence |
| --- | --- | --- |
| Golden-dandelion age locking | 26.1.2, 26.2, 26.3 | No `PlayerToggleEntityAgeLockEvent` handler |
| Sulfur-cube content interactions | 26.2, 26.3 | No swallow/content-change handling; generic pickup is insufficient |
| Copper-golem chest transport | 1.21.11 and modern targets | Golem transport directly mutates containers; existing hopper listener only consumes `InventoryMoveItemEvent` |
| Sulfur-spike support/falling cascades | 26.2, 26.3 | Hard-coded dependent/falling classifications omit sulfur spikes |
| Cushion placement/destruction | 26.3 | Non-living block-attached entities; no corresponding placement/removal recording |
| Straw-bed consumption | 26.3 | Use/leave destroys the bed through a direct block update; no bed-use/leave listener |

Ordinary container clicks, drags and open/close use generic block-container handling. Chest repair uses the chest block-data interface. The newer chest materials should therefore not receive duplicate listeners merely because their material names are new. Copper-golem transfers are a separate mechanic and need source/destination slot history. Player snapshot and rollback-storage inventory GUIs were exercised separately as regression checks.

Ordinary placement/breaking of the new wood, stone, wool and concrete blocks is already handled generically. New mob names alone do not require new damage/death handlers; the missing interaction mechanics are the issue scope. New support-sensitive plants and mushrooms merit regression coverage when extending the dependent-block rules, without assuming that every registry tag misses them.

Sources: [26.1](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-1), [26.2](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2), [26.3](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3). Paper evidence was inspected from the installed API and server jars, including `TransportItemsBetweenContainers`, `StrawBedBlock`, and `Cushion`.

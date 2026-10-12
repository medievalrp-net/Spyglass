# Events

Every record has an event type. Use these names with `a:`, like `/sg search a:break,place`. `/sg events` lists the ones your server records. Each can be turned off or kept for a different time in [`config.conf`](configuration.md#events).

## Blocks

| Event | Logged when |
| --- | --- |
| `break` | A block is broken: by a player, explosion, fire, an entity, or because the block it was attached to broke |
| `place` | A block is placed |
| `decay` / `form` / `grow` | Leaves decay, blocks form (ice, concrete, cobblestone...), crops and trees grow |
| `ignite` | A fire starts. Later burned blocks link back to it |
| `bucket-empty` / `bucket-fill` | Water, lava and other fluids are poured or scooped with a bucket |
| `use` | A block is right-clicked (doors, buttons, levers...) |
| `useSign` | A sign is used |
| `brush` | Suspicious sand or gravel is brushed |
| `vault` | A vault is unlocked |
| `crafter` | A crafter block crafts an item |
| `sculk` | A player triggers a sculk sensor or shrieker |
| `straw-bed-consume` | A straw bed is consumed |
| `cushion-place` / `cushion-break` | A cushion is placed or removed |
| `sulfur-ignite` / `sulfur-content` / `sulfur-growth` / `sulfur-bucket` | Sulfur blocks are ignited, change contents, grow or are bucketed |
| `age-lock` | A block's age lock changes |

## Containers and items

| Event | Logged when |
| --- | --- |
| `deposit` / `withdraw` | Items go into or out of a container slot |
| `open` / `close` | A container is opened or closed |
| `shulker-open` / `shulker-close` | A shulker box is opened or closed |
| `shulker-deposit` / `shulker-withdraw` | Items go into or out of a shulker box |
| `entity-deposit` / `entity-withdraw` | An item is put in or taken from an item frame |
| `bookshelf-insert` / `bookshelf-remove` | A book goes into or out of a chiseled bookshelf |
| `pot-insert` / `pot-remove` | An item goes into or out of a decorated pot |
| `bundle-insert` / `bundle-extract` | An item goes into or out of a bundle |
| `transfer-deposit` / `transfer-withdraw` | A hopper, dropper or dispenser moves items. On servers with big hopper farms, consider turning these off or shortening their retention |
| `transfer-uncertain` | Automated movement Spyglass couldn't pin to exact slots |
| `drop` / `pickup` | An item is dropped or picked up |
| `clone` | A creative player middle-click copies a block |

## Players

| Event | Logged when |
| --- | --- |
| `say` | Chat messages |
| `command` | Commands, from players or the console. Passwords can be [hidden](configuration.md#hiding-passwords-in-commands) |
| `join` / `quit` | Logging in and out (with IP address) |
| `teleport` | A player teleports |
| `named` | Something is renamed with a name tag |
| `mount` / `dismount` | A player starts or stops riding something |

## Combat

| Event | Logged when |
| --- | --- |
| `hit` / `shot` | Melee and projectile damage |
| `death` | An entity dies |
| `kill` / `mob-kill` | A player or mob kills something |

## Spyglass's own records

| Event | Logged when |
| --- | --- |
| `rollback-op` | A rollback, restore or undo finishes |
| `rolled-place` / `rolled-break` / `rolled-deposit` / `rolled-withdraw` | Shown in results for changes a rollback made. Built from `rollback-op`, not stored separately |
| `salvage-withdraw` | Someone takes an item from [salvage](../guide/salvage.md) |
| `snapshot-take` | Someone copies an item out of a [snapshot](../guide/snapshots.md) |

## What can be rolled back

Rollbacks change **blocks** by default, **containers** with `--containers`, and **mobs players killed** with `--entities`. Chat, commands, logins, teleports, combat and other records are for searching only.

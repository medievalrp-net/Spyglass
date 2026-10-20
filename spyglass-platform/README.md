# spyglass-platform — the Crusalis event fabric integration

Optional. Off by default. A server that has never heard of Crusalis loads no class from
this module, which is asserted by a test rather than claimed by a comment
(`CrusalisPlatformLoaderTest.theLoaderHoldsNoSymbolicReferenceToAnyCrusalisClass`).

When it is on, every record Spyglass commits is also published to the Crusalis event
fabric through that network's SDK plugin. **Spyglass's own storage is written exactly as
before**: this is a copy for someone else's dashboards, not a storage backend, and
turning it on cannot change what `/sg` or the inspector wand show.

```hocon
integrations {
  crusalis-platform {
    enabled = false
    include-actions = ["*"]
    exclude-actions = []
    redact-commands = ["login", "l", "register", "reg", "changepassword", "cp", "2fa"]
    private-commands = ["msg", "tell", "w", "whisper", "r", "reply", "m", "pm", "dm"]
    publish-rollbacks = true
  }
}
```

`include-actions` and `exclude-actions` are spelled that way because `include` is a
reserved word in HOCON — a file-inclusion directive — so `include = ["*"]` is a parse
error that stops the plugin booting. A quoted `"include"` is read too, for anyone who
copied the key out of the Crusalis spec.

Requires the `CrusalisPlatformSDK` plugin (a `softdepend`). With the block enabled and
the plugin absent, the loader logs one line and does nothing.

## How it hooks in

`RecordCommittedEvent`, at `MONITOR`. That event fires once a record is on Spyglass's
durable pipeline, which is exactly the point §6.1 asks for: after Spyglass's own
filtering, and without waiting for the storage write. The integration therefore cannot
affect what Spyglass stores, cannot cancel anything, and cannot slow a storage write
down.

Two things do not come from that event:

- **Explosions** are a Bukkit listener (`BlockExplodeEvent`, `EntityExplodeEvent`),
  because the destroyed-block list only exists while the event is being handled. One
  `spyglass.explosion` per explosion, with the block count and the measured radius. The
  per-block break records still publish as `spyglass.block_break` with the cause.
- **WorldEdit and FAWE operations** are coalesced per (actor, world) and flushed when
  the operation goes quiet. One `spyglass.worldedit` per operation — a 100,000-block
  `//set` is one event, with the bounding box and the block count, not 100,000 events.

## The authoritative action key list

This is the mapping `ActionMap` implements, and `ActionMapTest` fails the build if
Spyglass's `EventCatalog` gains a key that appears in neither table below. Crusalis's
registry is coarser than Spyglass's catalog on purpose: Spyglass separates
`break`/`decay`/`brush` because each rolls back differently, and Crusalis stores them as
one type with the cause in the payload, because the question asked of that store is "who
removed this block".

| Crusalis event type | Spyglass action keys |
| --- | --- |
| `spyglass.block_break` | `break`, `decay`, `bucket-fill`, `brush`, `vault`, `cushion-break`, `straw-bed-consume` |
| `spyglass.block_place` | `place`, `form`, `bucket-empty`, `cushion-place` |
| `spyglass.block_use` | `use`, `usesign`, `sculk`, `sulfur-content`, `sulfur-bucket`, `age-lock` |
| `spyglass.chat` | `say` |
| `spyglass.combat_hit` | `hit` |
| `spyglass.command` | `command` |
| `spyglass.container_add` | `deposit`, `transfer-deposit`, `entity-deposit`, `bookshelf-insert`, `pot-insert`, `shulker-deposit`, `bundle-insert` |
| `spyglass.container_open` | `open`, `close`, `shulker-open`, `shulker-close` |
| `spyglass.container_remove` | `withdraw`, `transfer-withdraw`, `entity-withdraw`, `salvage-withdraw`, `bookshelf-remove`, `pot-remove`, `shulker-withdraw`, `bundle-extract`, `crafter` |
| `spyglass.entity_death` | `death` |
| `spyglass.entity_kill` | `kill`, `mob-kill` |
| `spyglass.entity_mount` | `mount`, `dismount` |
| `spyglass.entity_named` | `named` |
| `spyglass.fire` | `ignite`, `sulfur-ignite` |
| `spyglass.growth` | `grow`, `sulfur-growth` |
| `spyglass.item_drop` | `drop` |
| `spyglass.item_pickup` | `pickup`, `clone`, `snapshot-take` |
| `spyglass.projectile` | `shot` |
| `spyglass.rollback` | `rollback-op` |
| `spyglass.session_join` | `join` |
| `spyglass.session_quit` | `quit` |
| `spyglass.teleport` | `teleport` |

Produced by this module with no action key of their own:

| Crusalis event type | Source |
| --- | --- |
| `spyglass.explosion` | one per explosion, from Bukkit's explode events |
| `spyglass.worldedit` | one per WorldEdit/FAWE operation, coalesced |

Deliberately never published:

| Spyglass action key | Why |
| --- | --- |
| `transfer-out` | legacy |
| `transfer-in` | legacy |
| `transfer-uncertain` | no actor |
| `rolled-place` | rollback artifact |
| `rolled-break` | rollback artifact |
| `rolled-deposit` | rollback artifact |
| `rolled-withdraw` | rollback artifact |

A key in neither list is reported once in the console and dropped. On the Crusalis side
an unregistered type is dropped by the sink anyway, so a new action needs two lines
here and a schema there.

## What is never published

- **IP addresses.** Spyglass records one on a join; the mapper never reads that field,
  and a test asserts over every record fixture that nothing IP-shaped reaches a payload.
  Crusalis's D12 forbids it, and a ninety-day store of addresses is a liability nobody
  asked for.
- **Player names.** The envelope carries the account's UUID; a name in the payload is
  wrong the day the player changes it. Crusalis resolves names on its own side.
- **Item and entity NBT.** Kilobytes per event against a 2 KiB payload budget, and still
  in Spyglass's native storage, which is what the wand and the rollback read. A
  material, a name and an enchantment count is what a query can use.
- **The arguments of `redact-commands`.** `/login hunter2` publishes as
  `/login <redacted>`. The command name is kept: "this player ran /login at 03:12" is
  the useful fact and carries no secret.
- **A rollback's synthesized per-block records.** `publish-rollbacks` controls the one
  event per completed job; the `rolled-*` records are never published, because they
  would double every action the rollback reversed.

## When the platform misbehaves

Every publish is wrapped. A failure is counted, not thrown — Spyglass is the record of
record and must not break because a copy of it failed. A thousand failures inside one
minute turn the integration off for the rest of the session, with one console line
saying so; the alternative is an hour of stack traces and a main thread spending time on
a call that cannot succeed.

## Measured cost

The §6.1 criterion is ≤ 1 µs mean and 0 B/op on the calling thread, per action.
`./gradlew :spyglass-platform:jmh` measures the mapping — the SDK's publish has its own
budgets in the Crusalis repository — against a slot that writes into one reused array
and allocates nothing, so the allocation reported is this module's and nobody else's.

JDK 25, ZGC, `-Xms512m -Xmx512m`, 3×2 s warmup and 3×4 s measurement on the operator's
development machine (WSL2 on a desktop, which is not a reference box):

| Benchmark | mean | alloc |
| --- | --- | --- |
| `mapBlockBreak` | 88 ns | ~0 B |
| `mapContainerWithdraw` | 87 ns | ~0 B |
| `mapChatWithoutExtensions` | 54 ns | ~0 B |
| `mapChat` (one extension entry) | 190 ns | 352 B |
| `mapRedactedCommand` | 90 ns | 248 B |

Zero on the high-volume actions, which is where it matters: `break` and `place` are
twelve million a day between them.

Two actions allocate, both honestly:

- **An integrator's extension map.** Iterating a `Map<String, String>` allocates its
  entry set, an iterator and an entry per pair; `mapChatWithoutExtensions` is the same
  record without one and is zero, which is how that 352 B is attributed rather than
  guessed at.
- **A redacted command line.** The redacted string has to be built. Resolving the
  command's name once instead of three times took this from 488 B to 248 B — the
  benchmark's doing; nothing else would have noticed.

Both are on types that run at about twelve and six events a second respectively, so the
remaining allocation is roughly 5 KB/s against the SDK's 10 MB/s budget for the whole
integration. Worth knowing, not worth contorting the code for.

## Building

`platform-sdk-api` is `compileOnly` and resolves from `https://maven.crusalis.net`
without credentials, so a fork of Spyglass builds this module with no Crusalis token.
`-PwithoutCrusalisPlatform` drops the module from the build entirely; the loader then
never finds the class, which is the same path as "the SDK is not installed".

Working on both repositories at once:

```sh
cd ../CrusalisEnterprise/java && ./gradlew :platform-sdk-api:publishToMavenLocal
cd ../../Spyglass && ./gradlew :spyglass-platform:test
```

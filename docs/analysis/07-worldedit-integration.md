# v1 Dissection — 07: WorldEdit and FastAsyncWorldEdit Integration

Files covered:

- Core: `the v1 core/src/main/java/net/medievalrp/v1/worldedit/WorldEditLogger.java`
- Core: `the v1 core/src/main/java/net/medievalrp/v1/worldedit/FAWERollbackHandler.java` (dead)
- Core: `the v1 core/src/main/java/net/medievalrp/v1/worldedit/fawe/FaweBatchLogger.java`
- Core: `the v1 core/src/main/java/net/medievalrp/v1/worldedit/fawe/FaweHook.java`
- Core: `the v1 core/src/main/java/net/medievalrp/v1/worldedit/fawe/FaweTileCapture.java`
- Core (lives under an API-shaped package): `the v1 core/src/main/java/net/medievalrp/v1/api/flag/FlagWorldEditSel.java`
- API: `v1API/src/main/java/net/medievalrp/v1/api/interfaces/WorldEditHandler.java`
- Glue: `sg.onWorldEditStatusChange` (`sg.java:159`), `PluginInteractionListener.handleToggle` (`PluginInteractionListener.java:22`), `SearchCallback.formatOriginTag` (`SearchCallback.java:168`), `OEntry.with` (`OEntry.java:48`), `OEntry.custom` (`OEntry.java:442`).

## Responsibility

Log a WorldEdit operation with the same fidelity as a hand-placed / hand-broken block: material, block data, container inventories, tile entity metadata, source player. Make those logs searchable (`a:break`, `p:Steve`, `r:30`, …) and rollback-able (`/sg rollback`) with no loss of information relative to the vanilla block listener path. Do this correctly on two backends:

1. **Vanilla WorldEdit** — every `setBlock` on the operation's `EditSession` goes through an extent chain; subscribe to `EditSessionEvent` at `BEFORE_CHANGE`, wrap the extent, intercept each `setBlock`.
2. **FastAsyncWorldEdit** — the above extent wrap silently stops catching anything when FAWE is running in `fast-placement` mode, because FAWE's fast path bypasses the WE extent chain and writes directly to FAWE's chunk queue. So: register an `IBatchProcessor` onto FAWE's per-EditSession `ExtentBatchProcessorHolder` and iterate the chunk diff at commit time on FAWE's worker thread.

Both paths produce pairs of `break` / `place` entries tagged `ORIGIN="worldedit"` or `ORIGIN="fawe"`, which renders as `[WE]` / `[FAWE]` prefixes in search output. The distinction matters because `[FAWE]` means the entry came from an async batch pass and had its tile entity NBT reconstructed via WE's adapter, while `[WE]` means it came from a synchronous Bukkit `BlockState` read.

A secondary responsibility: a `-we` search flag that replaces the default radius condition with the player's current WorldEdit cuboid selection (`FlagWorldEditSel`).

## Classes

### Vanilla WE path

- **`WorldEditLogger`** (`WorldEditLogger.java:38`) — the event subscriber. One public API: `register()` / `unregister()`. `register` installs `this` as a subscriber on `WorldEdit.getInstance().getEventBus()`. Detects FAWE-presence once statically in `faweDetected()` at `WorldEditLogger.java:61` by `Class.forName("com.fastasyncworldedit.core.Fawe")` and a `PluginManager.isPluginEnabled` check.
- **`WorldEditLogger.LoggingExtent`** (`WorldEditLogger.java:108`, private nested) — an `AbstractDelegateExtent` that intercepts `setBlock(BlockVector3, BlockStateHolder)` on the vanilla WE path. Captures the pre-change `BlockState` *before* calling `super.setBlock`, then saves post-change.

### FAWE path

- **`FaweHook`** (`FaweHook.java:18`) — the thin bridge that references FAWE classes. Gated behind `FAWE_PRESENT` in `WorldEditLogger` so the classloader never tries to resolve `ExtentBatchProcessorHolder` on a non-FAWE server. Single entry point `tryInstall(event, player, world)` walks the event's extent chain up to 16 deep looking for an `ExtentBatchProcessorHolder` and calls `addProcessor(new FaweBatchLogger(...))` on it. Returns `true` on success; the caller falls back to the extent-wrap path on `false`.
- **`FaweBatchLogger`** (`FaweBatchLogger.java:36`) — the `IBatchProcessor` itself. `processSet(IChunk, IChunkGet, IChunkSet)` runs per affected chunk on FAWE's worker thread during commit. Walks each set section, iterates the 4096-slot char array of set block IDs, reads the pre-commit state from `IChunkGet`, captures tile NBT via `get.getTile(lx, y, lz)`, emits `break`+`place` entries via a static `emit(...)` helper.
- **`FaweTileCapture`** (`FaweTileCapture.java:35`) — the NBT → Bukkit `ItemStack` bridge used by `FaweBatchLogger`. Walks the `Items` list on a container's tile-entity `CompoundTag`, constructs a WE `BaseItemStack(ItemType, CompoundTag, count)` per entry, converts via `BukkitAdapter.adapt(BaseItemStack)` to get a Bukkit `ItemStack` with 1.21 data components intact. Writes the result into `ORIGINAL_BLOCK.Inventory.<slot>` so `BlockEntry.rollback` sees the same shape it sees for vanilla break events.

### Dead code

- **`FAWERollbackHandler`** (`FAWERollbackHandler.java:32`) — `batchRollback(List<DataEntry>, CommandSender)` and `batchRestore(...)`. Intended as a FAWE-accelerated rollback path that would group entries by world and push them through a single `EditSession` per world instead of the current per-block Bukkit API. Never called from anywhere in the plugin (`Grep FAWERollbackHandler` returns only the definition file). Kept as compile-time dead code for a rewrite that hasn't happened.

### Flag

- **`FlagWorldEditSel`** (`FlagWorldEditSel.java:25`) — the `-we` search flag. Adds a `SearchConditionGroup` matching the player's current WE cuboid selection (world + X/Y/Z ranges) onto the query. Registered conditionally by `sg.onWorldEditStatusChange` only when WE is both loaded and enabled in config.

### Unused API

- **`WorldEditHandler`** (`WorldEditHandler.java:3`) — two-method interface (`enableWorldEditLogging`, `disableWorldEditLogging`) on the API surface. `sg` has a slot for one (`sg.java:38`) and a setter (`registerWorldEditHandler` at `sg.java:322`), but nothing in-tree ever registers a handler and nothing ever calls either method on it. It exists so external plugins could theoretically register their own WE-logging backend; no one does.

## Two code paths side by side

### Vanilla WE path — `WorldEditLogger.onEditSession`

```
//set stone
    │
    ▼
  WorldEdit dispatches EditSessionEvent, stage=BEFORE_CHANGE
    │
    ▼
  WorldEditLogger.onEditSession(event) WorldEditLogger.java:71
    │
    ├─ stage != BEFORE_CHANGE → return
    ├─ actor null or !isPlayer() → return
    ├─ Bukkit player resolution → null → return
    ├─ BukkitAdapter.adapt(event.getWorld()) → Bukkit World
    │
    ├─ FAWE_PRESENT?
    │ ├─ yes: FaweHook.tryInstall(event, player, world)
    │ │ ├─ installed → return (FAWE path owns this edit)
    │ │ └─ not installed → fall through
    │ └─ no: fall through
    │
    ▼
  event.setExtent(new LoggingExtent(event.getExtent(), event.getWorld(), player))
    │
    │ (WorldEdit continues, runs the operation; each setBlock flows through
    │ our LoggingExtent.setBlock)
    │
    ▼
  LoggingExtent.setBlock(position, block) WorldEditLogger.java:119
    │
    ├─ Bukkit Location from WE position
    ├─ originalState = location.getBlock().getState()
    │ ← full BlockState, tile entity still live
    ├─ originalIsAir = material.isAir()
    │
    ├─ if !originalIsAir:
    │ brokenEntry = OEntry.create().source(player)
    │ .brokeBlock(LocationTransaction(loc, originalState, null))
    │ .with(DataKeys.ORIGIN, "worldedit")
    │ ← NOT saved yet. brokeBlock captures the inventory/sign/banner/jukebox
    │ eagerly inside writeExtraStateData, using the still-live tile entity.
    │
    ├─ result = super.setBlock(position, block)
    │ ← WE writes the block. Tile entity is now gone.
    │
    ├─ newState = location.getBlock().getState()
    ├─ if originalIsAir && newIsAir → nothing to log
    │
    ├─ if brokenEntry != null: brokenEntry.save()
    │ ← queue the break entry now that the write succeeded
    ├─ if !newIsAir:
    │ OEntry.create().source(player)
    │ .placedBlock(LocationTransaction(loc, null, newState))
    │ .with(DataKeys.ORIGIN, "worldedit")
    │ .save()
    │
    ▼
  return result
```

The subtle and load-bearing detail is at `WorldEditLogger.java:127-142`: the `BlockState` is captured and the entry **prepared** before `super.setBlock` runs, but `.save()` is called *after*. Why: `state.getInventory()` for a container returns a live view backed by the tile entity. After `super.setBlock` replaces the block, the old tile entity is gone and reading the inventory returns empty. `OEntry.brokeBlock` → `writeExtraStateData` (`OEntry.java:459`) iterates the live inventory *while building the wrapper*, so the wrapper has a stable snapshot by the time we get to `save()`. If the order were reversed (call super first, then build the entry), the vanilla-WE path would silently log every broken chest with empty contents. This was almost certainly the proximate reason the method was structured this way.

### FAWE path — `FaweHook.tryInstall` + `FaweBatchLogger.processSet`

```
//set stone (FAWE, fast-placement: true)
    │
    ▼
  WorldEdit dispatches EditSessionEvent, stage=BEFORE_CHANGE
    │
    ▼
  WorldEditLogger.onEditSession(event)
    │
    ├─ FAWE_PRESENT == true
    ▼
  FaweHook.tryInstall(event, player, world) FaweHook.java:31
    │
    ├─ walk event.getExtent() chain up to 16 deep
    │ (for-each delegate: if instanceof ExtentBatchProcessorHolder → found)
    │
    ├─ holder.addProcessor(new FaweBatchLogger(player, world))
    │
    ▼
  return true — WorldEditLogger does NOT also install the extent wrap
    │
    │ ... FAWE runs the operation ...
    │ ... FAWE's chunk queue flushes, one chunk at a time, on its worker thread ...
    │
    ▼
  FaweBatchLogger.processSet(chunk, get, set) FaweBatchLogger.java:47
    │
    ├─ set.isEmpty() → return set (no edits in this chunk)
    ├─ resolve Bukkit.getPlayer(playerId) — may be null if logged out
    ├─ resolve Bukkit.getWorld(worldUid)
    │
    ├─ for each section layer in [minSectionPosition..maxSectionPosition]:
    │ if !hasSection → skip
    │ blocks = set.loadIfPresent(layer) ← char[4096], 0 = not-set
    │ for i in 0..4095:
    │ setId = blocks[i]
    │ if setId == 0 → skip
    │ newWe = BlockTypesCache.states[setId]
    │ lx = i & 15 ; lz = (i>>4) & 15 ; ly = (i>>8) & 15 ; y = sectionBaseY + ly
    │ oldWe = get.getBlock(lx, y, lz)
    │ if oldWe.equalsFuzzy(newWe) → skip (no real change)
    │ oldBukkit, newBukkit = BukkitAdapter.adapt(oldWe/newWe)
    │ originalTile = get.getTile(lx, y, lz) ← jnbt CompoundTag or null
    │
    │ if !originalIsAir:
    │ emit(player, world, wx, y, wz, "break", oldBukkit, originalTile)
    │ if !newIsAir:
    │ emit(player, world, wx, y, wz, "place", newBukkit, null)
    │
    ▼
  emit(...) FaweBatchLogger.java:143
    │
    ├─ w = DataWrapper.createNew()
    ├─ w.set(TARGET, material name)
    ├─ stateKey = ORIGINAL_BLOCK if break, else NEW_BLOCK
    ├─ w.set(stateKey.then(MATERIAL_TYPE), ...)
    ├─ w.set(stateKey.then(BLOCK_DATA), blockData.getAsString())
    ├─ w.set(LOCATION.then(X/Y/Z/WORLD), ...) — manual, because custom() doesn't do location
    ├─ if tile != null && break:
    │ FaweTileCapture.applyInventoryFromTile(w, stateKey, tile, data)
    │ → walks tile.Items ListTag, builds BaseItemStack per entry,
    │ adapts through BukkitAdapter → Bukkit ItemStack,
    │ writes to stateKey.Inventory.<slot> as ConfigurationSerializable
    │
    ├─ OEntry.create().source(player).custom(eventName, w)
    │ .with(DataKeys.ORIGIN, "fawe").save()
    │
    ▼
  OEntry.save puts the wrapper into EntryQueue, runner drains it, Mongo insert
```

One decision worth flagging: `FaweBatchLogger.processSet` runs on a FAWE worker thread, which means it does a lot of work off the main thread — `BukkitAdapter.adapt`, tile CompoundTag walks, `DataWrapper` construction, `EntryQueue.submit`. `Bukkit.getPlayer(uuid)` is called here (`FaweBatchLogger.java:52`) and Bukkit doesn't explicitly guarantee thread safety for player lookups, but in practice the `UUID → Player` map in Paper is a `ConcurrentHashMap` and the lookup is safe. `OEntry.save` pushes into `EntryQueue.getQueue()` which is a `LinkedBlockingDeque`, also safe off-thread. The whole FAWE path is read-only against the world — every value is pulled from `IChunkGet`/`IChunkSet`, never from a live Bukkit `BlockState`. That's by design, because touching live Bukkit state off the main thread is where async plugins typically crash.

### Why FAWE needs its own path

The extent wrap in `LoggingExtent` works perfectly on vanilla WorldEdit because vanilla WE pipes every `setBlock` through the extent chain an edit session is built from, and `event.setExtent(...)` at `EditSessionEvent.BEFORE_CHANGE` splices the wrapper into the pipeline.

FAWE's fast-placement mode skips the extent chain. Its own internal chunk queue accepts block changes directly, and the extent that FAWE exposes at `EditSessionEvent.BEFORE_CHANGE` is a different beast — the terminal extent is an `ExtentBatchProcessorHolder` that admits per-session `IBatchProcessor` instances rather than delegating through `setBlock`. A `LoggingExtent` wrapped around it would see none of the fast-placement writes; the downstream FAWE code routes around it.

The fix is structural: stop wrapping, start plugging into FAWE's batch pipeline. `FaweHook.tryInstall` finds the `ExtentBatchProcessorHolder` in the chain and calls `addProcessor(new FaweBatchLogger(player, world))`. FAWE then hands every committed chunk diff to the processor on a worker thread, giving us a complete view of what actually landed on disk regardless of which placement mode FAWE used.

Footnote on FAWE's `extent.allowed-plugins` whitelist: FAWE guards against third-party extent wraps by default because unknown wraps usually defeat its fast path. Any class that *wraps* the WE extent needs to be listed in FAWE's `config.yml` under `extent.allowed-plugins` (a Set of FQCNs). That would apply to `net.medievalrp.v1.worldedit.WorldEditLogger$LoggingExtent` if we were ever forced onto that path on a FAWE server — for example, if `FaweHook.tryInstall` returned `false` because the extent chain didn't expose a holder. The `IBatchProcessor` model used by the FAWE path does **not** require a whitelist entry; it's the sanctioned extension point. So on a FAWE server in practice we don't need the allowed-plugins entry, but on a server that later removes FAWE while keeping WorldEdit, the fallback path kicks in with no warning and no whitelist — which is the correct behavior but needs documenting.

## Tile entity capture — how inventories survive

The vanilla path gets tile entity NBT for free because it calls `location.getBlock().getState()` while the tile is still live, hands it to `OEntry.brokeBlock`, and `writeExtraStateData` serializes the `Container`/`Sign`/`Banner`/`Jukebox` state into the wrapper. The Bukkit `ItemStack` objects are themselves `ConfigurationSerializable` and round-trip through `DataWrapper.ofConfig` (see `02-data-and-storage.md` for the `CONFIG_CLASS` schema).

The FAWE path cannot do that. At `processSet` time there's no Bukkit `BlockState` for the old block — the write already happened in FAWE's chunk queue, and even if it hadn't, we're on the wrong thread to call `location.getBlock().getState()`. What we *do* have is `get.getTile(lx, y, lz)` which returns a jnbt `CompoundTag` representing the pre-commit tile entity NBT exactly as it was on disk. That's the ground truth, but it's a raw NBT structure keyed by Mojang's internal names (`Items`, `Slot`, `id`, `count`, `components`), not a Bukkit-shaped `ItemStack`.

`FaweTileCapture.applyInventoryFromTile` bridges the two. The critical sequence (`FaweTileCapture.java:99-105`):

```java
BaseItemStack baseStack = new BaseItemStack(itemType, itemTag, count);
ItemStack bukkitStack = BukkitAdapter.adapt(baseStack);
```

`BaseItemStack` is WorldEdit's own item model; its three-arg constructor accepts a `CompoundTag` of extra NBT that's meant to be the full item NBT including 1.21's `components` map. `BukkitAdapter.adapt(BaseItemStack)` is the FAWE platform's authoritative NBT→Bukkit conversion: it walks the NBT, builds a Bukkit `ItemStack`, and populates the `ItemMeta` *and* the 1.21 data-components tree that ordinary Bukkit serialization knows about. The resulting Bukkit `ItemStack` is a `ConfigurationSerializable` that walks cleanly through `DataWrapper.ofConfig` → Mongo document → rollback.

Stored shape matches the vanilla break event exactly:

```
OriginalBlock: {
  MaterialType: "CHEST",
  BlockData: "minecraft:chest[facing=east,type=single,waterlogged=false]",
  Inventory: {
    "0": { ClassName: "ItemStack", type: "DIAMOND_SWORD", amount: 1, meta: {...}, components: {...} },
    "3": { ClassName: "ItemStack", type: "COBBLESTONE", amount: 64 }
  }
}
```

`BlockEntry.rollback` (`BlockEntry.java:75`) walks `ORIGINAL_BLOCK.Inventory.<slot>` and calls `wrapper.getConfigSerializable(key)` to get each `ItemStack` back. It has no idea — and no need to know — whether the entry came from the vanilla path or the FAWE path. The serialization format is the shared contract.

Materials that aren't containers are filtered out early by `FaweTileCapture.isContainerMaterial` at `FaweTileCapture.java:121`, which is a substring-match heuristic (`CHEST`, `BARREL`, `HOPPER`, `DISPENSER`, `DROPPER`, `FURNACE`, `SMOKER`, `BLAST_FURNACE`, `BREWING_STAND`, `SHULKER_BOX`). That's a tolerable list; the notable gap is `TRAPPED_CHEST` (covered by `CHEST`), `JUKEBOX` (not covered, but its state isn't an `Items` list so wouldn't work here anyway), and 1.21's `CRAFTER` (not covered — crafter NBT is shaped the same way and *should* be captured). Sign text, banner patterns, and jukebox discs are **not** captured on the FAWE path at all — only container inventories. The vanilla path captures all four via `writeExtraStateData`.

## The `[WE]` / `[FAWE]` origin tag

End-to-end:

1. **Write side.** Both paths call `.with(DataKeys.ORIGIN, "worldedit" | "fawe")` on the `OEntry`. `DataKeys.ORIGIN` is the `DataKey` registered at `DataKeys.java:52` as `DataKey.of("Origin")`. The value lands as a string at the top level of the stored Mongo document: `{ Origin: "worldedit" }` or `{ Origin: "fawe" }`.
2. **Render side.** `SearchCallback.buildComponent` reads `DataKeys.ORIGIN` at `SearchCallback.java:63` and renders a prefix via `formatOriginTag` at `SearchCallback.java:168`:
   ```java
   private String formatOriginTag(String origin) {
       if (origin == null || origin.isEmpty()) return "";
       return ChatColor.AQUA + "[" + switch (origin.toLowerCase()) {
           case "worldedit" -> "WE";
           case "fastasyncworldedit", "fawe" -> "FAWE";
           default -> origin;
       } + "]" + ChatColor.RESET;
   }
   ```
   Result: a search line like `= [FAWE] Steve placed 8,192 stone 30s ago`. There's also a `Origin: <origin>` row in the hover tooltip (`SearchCallback.java:72`).

The tag is set by exactly three call sites — `WorldEditLogger.java:138`, `WorldEditLogger.java:164`, `FaweBatchLogger.java:163` — and rendered by exactly one. If the distinction between a WE-source entry and a hand-broken entry matters elsewhere (future query filters, rollback policies that treat WE edits specially), the tag already carries the information.

One subtle difference from the rest of the origin-tagged data: the tag is purely display metadata. It isn't indexed, isn't searchable via any parameter (no `origin:` parameter exists), and there's no way for a user to filter results to "only WE edits." The flag exists on the data model without reaching either the query DSL or the renderer-beyond-prefix. That's a reasonable floor and probably the right scope for the current data volume.

## `FlagWorldEditSel` (the `-we` flag)

`FlagWorldEditSel.java:25`. One registered alias: `we`. `acceptsSource` restricts to `Player`; `acceptsValue` returns `true` (no value is meaningful — `-we` is a boolean-style flag, written as `-we` without an `=<value>`).

Flow (`FlagWorldEditSel.process`, `FlagWorldEditSel.java:45`):

1. Pull the player's `LocalSession` from `WorldEditPlugin.getSession(player)`.
2. If the selection world is `null`, print "Please make a WorldEdit selection first" via `Formatter.error` and return.
3. Get the `RegionSelector` for the selection world; if `null`, same error.
4. Call `selector.getRegion()`:
   - If it's a `CuboidRegion`, call `query.addCondition(fromSelection(region, player.getWorld()))` to add a `SearchConditionGroup` with `LOCATION.World = <world uuid>` + `LOCATION.X/Y/Z BETWEEN` three open `Range`s constructed from the region's `minPoint` / `maxPoint`.
   - If it's not a `CuboidRegion`, print "Cannot use the flag `-we` with a non-cuboid region" and return.
5. Catch `IncompleteRegionException` and print "Cannot use the flag `-we` with an incomplete region".

The `SearchConditionGroup.Operator.AND` at `FlagWorldEditSel.java:81` guarantees the four conditions combine into a single AND'd group in the query.

One minor oddity, `FlagWorldEditSel.java:68`:

```java
session.isIgnoredDefault(v1.getParameterHandler("r").orElse(null));
```

This **reads** the ignored-default state for `r` and throws away the result. It was almost certainly meant to be `addIgnoredDefault`, matching the pattern from `WorldParameter` where supplying an alternate location constraint suppresses the default radius group (see `04-query-dsl.md` pain point 12). As shipped, `-we` emits the correct X/Y/Z/world group *and* the default 5-block radius group then also fires, so the returned results are the intersection of "inside my selection" AND "within 5 blocks of me". Two simultaneous spatial filters, which usually means zero results when the selection is more than 5 blocks from the player.

**Registration.** `FlagWorldEditSel` is not registered unconditionally. `sg.registerFlags()` (`sg.java:144`) adds the stock flags and then checks `Bukkit.getPluginManager().isPluginEnabled("WorldEdit")` at `sg.java:154`; if true, calls `onWorldEditStatusChange(true)` (`sg.java:155`). `onWorldEditStatusChange` is at `sg.java:159`, and it:

1. Adds `new FlagWorldEditSel(Bukkit.getPluginManager().getPlugin("WorldEdit"))` to `flagHandlerList` if not already present.
2. Calls `WorldEditLogger.register()`.

The `status=false` branch removes the flag and calls `WorldEditLogger.unregister()`. `PluginInteractionListener.handleToggle` (`PluginInteractionListener.java:22`) watches `PluginEnable/DisableEvent` for the literal name `"WorldEdit"` and forwards to `v1.onWorldEditStatusChange(on)`. So the flag and the logger come up and down together as WorldEdit is loaded and unloaded at runtime.

The conditional-registration is load-bearing for classloading: `FlagWorldEditSel` has a hard compile-time import of `com.sk89q.worldedit.bukkit.WorldEditPlugin`. If the class were instantiated without WE on the classpath, the JVM would throw `NoClassDefFoundError` at `FlagWorldEditSel.java:31` (`(WorldEditPlugin) worldEdit`). The whole reason `registerFlags()` doesn't just `add(new FlagWorldEditSel(...))` directly and instead funnels through `onWorldEditStatusChange` is to keep the class off the classloader path until WE is known-present.

## `WorldEditHandler` — the unused interface

`v1API/.../interfaces/WorldEditHandler.java`:

```java
public interface WorldEditHandler {
    void enableWorldEditLogging();
    void disableWorldEditLogging();
}
```

`sg` has a field `WorldEditHandler worldEditHandler` at `sg.java:38` and a setter `registerWorldEditHandler(WorldEditHandler)` at `sg.java:322`. `sg.registerWorldEditHandler` forwards to it. Nothing in tree calls `enableWorldEditLogging` or `disableWorldEditLogging` on the stored handler — `Grep WorldEditHandler` returns only the import, field, and setter, plus the API-side interface declaration.

The intent was probably "external plugins implement their own WE logging backend and register it via the API, so v1 can delegate enable/disable to them." The actual implementation hard-codes `WorldEditLogger.register() / .unregister()` in `onWorldEditStatusChange`, and never touches the handler field. Dead interface, dead field, dead setter.

## Dead code: `FAWERollbackHandler`

`FAWERollbackHandler.java:32`. Two public entry points — `batchRollback(List<DataEntry>, CommandSender)` and `batchRestore(List<DataEntry>, CommandSender)` — plus the private `batchProcess(List<DataEntry>, CommandSender, boolean)` they both delegate to. `Grep FAWERollbackHandler` in the Core source returns only the file itself; zero callers.

The intended design was:

1. Group entries by world in a `Map<World, List<BlockChange>>`.
2. For each world, open a single `EditSession` via `WorldEdit.getInstance().newEditSession(weWorld)`.
3. For each change, convert the stored `ORIGINAL_BLOCK.BlockData` (rollback) or `NEW_BLOCK.BlockData` (restore) to a WE `BlockState` via `BukkitAdapter.adapt(BlockData)` and call `editSession.setBlock(BlockVector3, BlockState)`.
4. Commit via try-with-resources `close()`.

`applyChangesWithFAWE` at `FAWERollbackHandler.java:153` is what would do the write. The class is instrumented with info-level logs throughout (`[FAWE] Processing X entries`, `[FAWE] Applying X block changes in world`, `[FAWE] Committed X`) that would be very noisy if it were wired up. It isn't.

Tile entities: this rollback path has **no** tile-entity restoration. It only restores block data. A rolled-back chest would come back as an empty chest even if the entry had a saved inventory, because the code never calls into the inventory-restore logic. That would have been a regression versus the current synchronous `BlockEntry.rollback` path, and may well be why the class was never called.

Superseded by: `BlockEntry.rollback()` (`BlockEntry.java:28`), which runs synchronously per-entry and calls `handleTileEntity(editState, ORIGINAL_BLOCK)` at `BlockEntry.java:43` to restore containers, signs, banners, jukeboxes. That's the only live rollback path in the plugin.

## The compile-time FAWE dependency

`the v1 core/build.gradle:13`:

```groovy
compileOnly files("$projectDir/libs/FastAsyncWorldEdit.jar")
```

FAWE is a `compileOnly` dependency (so it's not shaded into the plugin jar) and it's sourced from a local file dropped into `the v1 core/libs/`. The `libs/` directory is gitignored (the file exists locally per `ls` but is not tracked), which means:

- A fresh `git clone` of this repo cannot compile Core. The compilation of `FaweHook`, `FaweBatchLogger`, `FaweTileCapture` will fail with "package `com.fastasyncworldedit.core...` does not exist" until the developer drops in a `FastAsyncWorldEdit.jar` by hand.
- There's no documentation in the build script or a README explaining where to get the jar.
- Version-pinning is implicit: whatever jar was last dropped in is the version the code compiles against. No artifact-level reproducibility.

WorldEdit itself comes from a proper Maven repo (presumably `sk89q` or `intellectualsites`) — the issue is only with FAWE. IntellectualSites publishes FAWE to `https://maven.enginehub.org/repo/` and `https://mvn.intellectualsites.com/content/groups/public/`, either of which would work as a proper `compileOnly` dep. The reason it's a local file isn't documented.

## Pain points

1. **Two parallel loggers for the same conceptual operation.** `WorldEditLogger.LoggingExtent` and `FaweBatchLogger` both exist to turn "WE wrote a block at (x,y,z)" into an `OEntry`. The detection at `WorldEditLogger.java:41` (static `FAWE_PRESENT`) picks one; the other lives alongside, compiled, untested on servers that took the other branch. Each implementation has its own tile-entity story (live Bukkit `BlockState` vs. jnbt `CompoundTag` routed through `BukkitAdapter.adapt(BaseItemStack)`) and its own set of captured state (vanilla catches sign/banner/jukebox text/data; FAWE catches only container inventories). Drift is automatic.

2. **`FAWERollbackHandler` is compiled but dead.** Nothing calls `batchRollback` or `batchRestore`. The class is 215 lines of code the compiler validates, the shade pipeline bundles, and no branch of the program exercises. Risk: someone reading the code assumes it's the rollback path for FAWE edits (the name implies it) and wires it in without noticing that (a) it doesn't restore inventories, and (b) the current `BlockEntry.rollback` already handles the FAWE-origin entries just fine. The name alone is a hazard.

3. **FAWE `extent.allowed-plugins` is a distribution footgun — partially.** On a FAWE server, the `IBatchProcessor` path we use doesn't require a whitelist entry, so things work out-of-the-box. But on a server without FAWE we install `LoggingExtent` directly — no whitelist concern because there's no FAWE to enforce one. The footgun only materializes in a specific sequence: vanilla WE server → v1 installed and running against `LoggingExtent` path → admin adds FAWE later. At that point FAWE's conflict logic will refuse to allow `LoggingExtent` through the chain until its FQCN is added to `extent.allowed-plugins`, but by then `faweDetected()` is `true` (static, evaluated at class load — stays `true` forever unless the JVM restarts, except it actually re-reads the plugin manager each call — see below) and `FaweHook.tryInstall` takes over. This means the footgun actually *doesn't* materialize in practice on FAWE servers because the batch-processor path kicks in and the extent wrap path is never used. But the sequence above (add FAWE mid-run) is not a plugin reload away from a fresh JVM restart, and `FAWE_PRESENT` is resolved once at `WorldEditLogger` class init (`WorldEditLogger.java:41`), so the first post-FAWE-install WE edit may still try the extent wrap path until the server restarts. Documentation-in-code would help.

4. **`FaweBatchLogger` iterates every slot of every set section.** `FaweBatchLogger.java:76` is `for (int i = 0; i < 4096; i++)`. The loop bails cheaply on `setId == 0` (position not modified), but the outer loop still runs 4096 times per affected section in every affected chunk. For a 100-chunk paste with one section changed per chunk, that's ~409,600 iterations just to skip-check. Minor, but there's no short-circuit for sections that were marked `hasSection` but whose `blocks` array is entirely zeros — can happen when a processor earlier in the pipeline clears edits. A `setId != 0` bitmask or a "first non-zero index" hint from `IChunkSet` would be a meaningful optimization for dense edits, but the FAWE API doesn't surface one today.

5. **`FaweBatchLogger.emit` sets location manually because `custom` doesn't.** `OEntry.EventBuilder.custom(eventName, wrapper)` at `OEntry.java:442` takes a pre-built `DataWrapper` and copies its top-level keys into the entry's wrapper, but doesn't touch `LOCATION` — if you want location data you use `customWithLocation(eventName, wrapper, Location)` at `OEntry.java:450`. `FaweBatchLogger.emit` can't use `customWithLocation` because we don't have a Bukkit `Location` — we have raw `wx/y/wz` ints and the world UID. We could construct a `Location` and use `customWithLocation`, but the current code does a manual `w.set(LOCATION.then(X), wx)` / `Y` / `Z` / `WORLD` at `FaweBatchLogger.java:151-154`. Works, but slightly sloppy — two ways to set the same thing, one of which exists as an API convenience that we don't use.

6. **`Bukkit.getPlayer(uuid)` on FAWE worker thread.** `FaweBatchLogger.java:52`. Paper's player map is a `ConcurrentHashMap<UUID, Player>` so the lookup itself is safe, but Bukkit's docs don't guarantee it. Edge case: player logs out mid-commit → lookup returns `null` → the batch bails and no entries are logged for blocks processed after the logout. Those block changes *do* land on disk (FAWE's commit doesn't depend on us), they just don't end up in v1. The block was written but not logged. Small window, but not an empty one: a `//set` with a few thousand blocks across a dozen chunks commits over multiple hundreds of milliseconds.

7. **`construct(Extent child)` returns the child unchanged.** `FaweBatchLogger.java:134`. `IBatchProcessor.construct` is what FAWE calls to let a processor wrap an extent when the pipeline is running in non-batch modes. Returning `child` as-is is the "don't wrap" signal, which is correct if we only want batch-mode logging. Untested: what happens on a FAWE edit that skips batch mode entirely (some `EditSession` configurations do) — in that case neither `processSet` nor our wrap fires, and the edit is unlogged. Needs investigation.

8. **`FlagWorldEditSel` bug at `FlagWorldEditSel.java:68`.** `session.isIgnoredDefault(...)` is a getter called for side-effect, but there is no side-effect. Should be `session.addIgnoredDefault(...)`. Intersection-with-default-radius problem documented in the flag section above.

9. **`WorldEditHandler` is a phantom API surface.** Interface exists, field exists, setter exists, zero real uses. External plugins that see `sg.registerWorldEditHandler(...)` might reasonably assume "if I register my own handler v1 will use it" — nothing downstream does. Either implement the contract (delegate to the registered handler from `onWorldEditStatusChange` when one is present) or delete the interface.

10. **Compile-time FAWE dep via local jar is bad hygiene.** `compileOnly files("$projectDir/libs/FastAsyncWorldEdit.jar")` combined with a gitignored `libs/` means `git clone && ./gradlew build` fails out of the box on a clean checkout. No build documentation, no pinned version, no easy bisection across FAWE versions. Switching to the IntellectualSites Maven repo would fix all three.

11. **Non-container tile state isn't captured on the FAWE path.** `FaweTileCapture.applyInventoryFromTile` handles only container inventories. Signs, banners, and jukeboxes — all captured on the vanilla path via `writeExtraStateData` — are not captured on the FAWE path. A WE `//set` over a row of signs will log the break with material + block data but without the sign text, so a rollback loses the text. Same for banners (pattern data) and jukeboxes (record inside). Reasonable near-term gap, needs to be closed for feature parity.

12. **FAWE `CRAFTER` container (1.21+) isn't in the `isContainerMaterial` list.** `FaweTileCapture.java:121` checks a substring list that doesn't include `CRAFTER`. Crafters are real containers with `Items` ListTag NBT, so the FAWE path silently drops their inventory on break. Easy fix.

13. **`faweDetected()` is called once at class load but re-checks `isPluginEnabled`.** `WorldEditLogger.java:41` is `static final boolean FAWE_PRESENT = faweDetected();`. That's evaluated at class init and frozen. If FAWE is loaded later (e.g. via runtime plugin install with PlugMan), `FAWE_PRESENT` stays `false` and the FAWE path is never taken, even though `FastAsyncWorldEdit` is now enabled. Conversely: if FAWE was loaded at WorldEditLogger class-init and then unloaded, `FAWE_PRESENT` stays `true` and `FaweHook.tryInstall` will still be called — and will throw `NoClassDefFoundError` when FAWE's classes are gone. The detection should either be dynamic (re-check each time) or the logger should fully re-register on plugin enable/disable for FAWE specifically. Currently `PluginInteractionListener` only listens for `"WorldEdit"`, not `"FastAsyncWorldEdit"`.

14. **No test coverage.** Zero tests in the `the v1 core` module reach the WE/FAWE integration. Both paths are hot, user-facing, and silently fail when they break (a logger that logs zero entries just looks like "nothing happened yet"). Regressions are detected only by operators noticing that `/sg l p:SomeWEUser` returns fewer results than expected.

15. **Origin tag is string-typed.** `.with(DataKeys.ORIGIN, "worldedit" | "fawe")` — two spellings, lowercased for dispatch in `formatOriginTag`. Anywhere else that emits an origin has to know the exact string. A small enum + `DataKeys.ORIGIN = DataKey.of("Origin")` with a typed setter would eliminate the stringliness.

## Modernization hotspots

1. **Delete `FAWERollbackHandler`.** Replace with a new `FaweBatchRollback` that actually uses the `IBatchProcessor` model to write blocks in bulk. Trigger only when the rollback set exceeds some configured threshold (say, 500 entries) — the current synchronous path is the right answer for small rollbacks. The new class should:
   - Group `DataEntry`s by world.
   - Open one `EditSession` per world.
   - For each entry, pre-build `(BlockVector3, BlockState, CompoundTag tile)` triples.
   - Register a custom `IBatchProcessor` on the session's holder that applies both the block and the tile NBT in one commit.
   - Restore container inventories from `ORIGINAL_BLOCK.Inventory` via the inverse of `FaweTileCapture` (Bukkit `ItemStack` → `BaseItemStack` → `CompoundTag`).
   
   That's the logger path in reverse. Without tile-entity restoration the batch path is strictly worse than the synchronous path, no matter how fast it is per block.

2. **Consolidate the two logging paths behind one abstraction.** Something like:
   ```java
   sealed interface WorldEditLoggerBackend permits VanillaBackend, FaweBackend {
       void capture(EditContext ctx);
   }
   record EditContext(UUID player, Location location, BlockState before, BlockState after, CompoundTag tile) {}
   ```
   Where each backend is responsible for building an `EditContext` from its native event type, and a shared `EntryEmitter.emit(ctx, origin)` writes the actual `OEntry`. The origin tag becomes a property of the backend, not a thing each backend hard-codes into its emit call. Tile capture is centralized: one `TileCaptureStrategy` for each `BlockState.class` / `CompoundTag` source, sharing a common "produce a `Map<Integer, ItemStack>` for containers, `List<String>` for sign text, etc." interface.

3. **Feature-complete `FaweTileCapture`.** Beyond containers: capture sign text (`front_text` / `back_text` NBT), banner patterns (`patterns` NBT), jukebox records (`RecordItem` NBT). Same path as inventory: walk the NBT, route through `BaseItemStack` + `BukkitAdapter.adapt` where applicable, emit into the existing `SIGN_TEXT` / `BANNER_PATTERNS` / `RECORD` `DataKey`s so the stored shape matches vanilla break events. Also add `CRAFTER` to the container material list.

4. **Verify 1.21 component preservation on rollback.** The FAWE path routes items through `BaseItemStack(ItemType, CompoundTag, count)` + `BukkitAdapter.adapt(BaseItemStack)` which is FAWE's authoritative NBT→Bukkit path. On paper this preserves everything including custom model data, attribute modifiers, and 1.21 `components`. In practice this needs a regression test: paste a chest containing an item with a custom name + enchantments + attribute modifiers + custom model data via a FAWE `//paste`, roll it back, verify every component survives. If it doesn't, the fallback at `FaweTileCapture.java:110-114` (plain `ItemStack(material, count)` sans meta) is what you'll actually see, which silently loses data.

5. **Switch FAWE dep to a proper Maven repo.** Change `the v1 core/build.gradle:13` from `compileOnly files("$projectDir/libs/FastAsyncWorldEdit.jar")` to a `compileOnly "com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:<version>"` with the IntellectualSites repo declared in the repositories block. Removes the gitignored-libs-dir problem, pins the version, enables fresh-clone builds.

6. **Dynamic FAWE detection.** Replace the static `FAWE_PRESENT` at `WorldEditLogger.java:41` with a method-local check evaluated each time `onEditSession` fires. Or tie the state to the FAWE plugin's own enable/disable via a new branch in `PluginInteractionListener.handleToggle`. Either closes the "FAWE loaded after v1" and "FAWE unloaded after v1" edge cases.

7. **Implement or delete `WorldEditHandler`.** If external plugins should be able to swap in their own WE-logging backend, wire `onWorldEditStatusChange` to delegate to the registered handler when one is present, falling back to the in-tree logger otherwise. If not, delete the interface, field, setter, and the API entry point.

8. **Pick one truth for the integration toggles.** `config.yml`'s `integration.worldEdit` and `integration.fastAsyncWorldEdit` are read by `sg` (`doWorldEditInteraction()` and `doFaweInteraction()`) and consumed half-heartedly: `onWorldEditStatusChange` at `sg.java:160` checks `doWorldEditInteraction()` before registering, so that toggle works for WE logging. `doFaweInteraction()` is never actually checked anywhere — `FAWE_PRESENT` is detected independently of it. A v2 should either honor both toggles uniformly or drop the FAWE one (since FAWE presence already implies the FAWE path). Related: `01-core-lifecycle.md` calls out the dead if-blocks at `sg.onEnable` lines 79-87 that were meant to gate integration and don't.

9. **Typed origin tag.**
   ```java
   enum EditOrigin { WORLDEDIT, FAWE;
       String wireValue() { return name().toLowerCase(); }
       String renderTag() { return this == FAWE ? "FAWE" : "WE"; }
   }
   ```
   `OEntry.with(EditOrigin)` overload, `formatOriginTag` keyed on the enum not a string. String round-trip through Mongo still uses `wireValue()`, but every in-memory reference is typed.

10. **Fix `FlagWorldEditSel.java:68`.** Change the line to `session.addIgnoredDefault(v1.getParameterHandler("r").orElse(null))`. One-character fix that makes the flag actually do what `WorldParameter` does. Also consider `-we` being registered only when WE is enabled should match exactly how other WE-dependent features gate — right now that's the case, but a v2 DI-based registration would pass WE-presence in as a dependency rather than checking `Bukkit.getPluginManager()` at various points.

11. **Add test coverage.** Minimum:
    - Unit: feed `FaweTileCapture.applyInventoryFromTile` a synthetic `CompoundTag` that mirrors a 1.21 chest NBT and assert the resulting `DataWrapper` matches the shape `BlockEntry.rollback` expects.
    - Integration (requires a test server fixture): run a `//set stone` over a chest, query the resulting entries, run `/sg rollback`, verify the chest comes back with contents.
    - The tests need a MockBukkit-level harness plus a WE fixture. Not trivial, but high ROI given the silent-failure mode.

12. **Collapse the FAWE extent-walk budget.** `FaweHook.findHolder` at `FaweHook.java:47` walks up to 16 extents deep. 16 is arbitrary. Real-world FAWE chains are typically 3-6 deep. Either bump the budget to infinity (the loop terminates on null anyway) or drop it; the magic number suggests concern the loop might not terminate, and that concern isn't founded.

## What v2 should keep

- **Dual-path detection, singular API.** Having both a vanilla WE and a FAWE path is necessary (the fast-placement bypass problem doesn't go away). What matters is that the rest of the code (search, rollback, display) treats entries from either path identically. The current shared-storage-schema approach is right — both paths produce entries with the same `ORIGINAL_BLOCK`/`NEW_BLOCK`/`Inventory` shape and the same origin-tag mechanism. Keep that contract.

- **The `IBatchProcessor` installation strategy.** `FaweHook.tryInstall` walking the extent chain to find an `ExtentBatchProcessorHolder` is the sanctioned FAWE extension point. Don't regress to pre-install wrapping or reflection hacks.

- **Tile-entity NBT via `BaseItemStack` + `BukkitAdapter.adapt`.** The roundtrip through WE's own adapter is what makes 1.21 component preservation work off the main thread. Pre-Adventure-era plugins hand-rolled NBT → ItemMeta conversions that broke on every component schema change. Keep the adapter route.

- **`DataKeys.ORIGIN` as a first-class wrapper field.** Storing the logging origin on the entry itself (rather than a sidecar table or a filename suffix) is correct. It means queries can eventually filter on origin (`origin:fawe`) just by adding one `FieldCondition`, no schema change.

- **Config-gated WE integration.** `integration.worldEdit: false` really disabling the logger and flag is the right UX. Keep the toggle, clean up the dead FAWE toggle.

- **The `-we` flag concept.** Replace-default-radius-with-WE-selection is a genuinely useful search tool — faster than typing out four coordinate ranges. Keep, just fix the bug at line 68 and consider a companion `-we2` flag for a secondary selection if WE ever gets regions beyond cuboid.

- **Structural detection over reflection.** `Class.forName("com.fastasyncworldedit.core.Fawe")` + `PluginManager.isPluginEnabled("FastAsyncWorldEdit")` is two cheap checks that don't require probing the classpath. Keep the pattern, just make it dynamic instead of class-init-frozen.

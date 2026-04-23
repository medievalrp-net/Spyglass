# v1 Dissection — 06: Rollback and Restore

Files covered:

- API: `v1API/src/main/java/net/medievalrp/v1/api/entry/Actionable.java`, `ActionResult.java`, `ActionableException.java`, `SkipReason.java`
- Core entry impls: `the v1 core/src/main/java/net/medievalrp/v1/api/entry/BlockEntry.java`, `ContainerEntry.java`, `EntityEntry.java`
- Core command glue: `the v1 core/src/main/java/net/medievalrp/v1/command/SpyglassCommands.java` (`runApplier`, `runUndo`)
- Core helper: `v1API/src/main/java/net/medievalrp/v1/api/util/DataHelper.java` (the deserialization spine — `unwrapConfigSerializable`, `dataWrapperToMap`, `unwrapValue`)
- Core dead code: `the v1 core/src/main/java/net/medievalrp/v1/worldedit/FAWERollbackHandler.java`
- Core state holder: `the v1 core/src/main/java/net/medievalrp/v1/sg.java` (`lastActionResults`)

## Responsibility

Take a historical record of a change (a `DataEntry` with `ORIGINAL_BLOCK` / `NEW_BLOCK` / `BEFORE` / `AFTER` / `ENTITY` / etc. in its `DataWrapper`) and **reverse** it back into the live world — or **reapply** it forward. Also: remember the last batch of reversals per player, so `/sg undo` can un-reverse them.

This is the most write-heavy of v1's runtime operations and the one most likely to tick-lag the server. It is the only part of the plugin that takes a query result and mutates the world; every other command surface is read-only.

The subsystem has three moving parts with conspicuously different shapes:

1. **`Actionable.rollback()` / `Actionable.restore()`** — per-entry, typed, implemented on each Core-side `DataEntry` subclass that supports reversal (`BlockEntry`, `ContainerEntry`, `EntityEntry`). This is the primary path.
2. **`SpyglassCommands.runApplier`** — the driver: query storage, iterate results, dispatch to `rollback()` or `restore()` based on sort direction, tally results, stash the batch for potential undo.
3. **`SpyglassCommands.runUndo`** — the parallel-universe implementation: walks the stashed `ActionResult` list in reverse, but does **not** go back through `Actionable`. It duck-types the transactions and mutates the world directly.

There is a fourth part — `FAWERollbackHandler.batchRollback` / `batchRestore` — that is structurally correct (it batches block changes into a FAWE `EditSession` for async performance) and has zero callers. It is dead code with its own static-init side effects. The whole v2 opportunity turns on whether this path is resurrected or rewritten.

## Classes

### API side (2 interfaces, 2 value types, 1 enum)

- **`Actionable`** (`Actionable.java:3`) — marker + two methods:
  ```java
  public interface Actionable {
      ActionResult rollback() throws Exception;
      ActionResult restore() throws Exception;
      default ActionableException skipped(SkipReason reason) {
          return new ActionableException(ActionResult.skipped(reason));
      }
  }
  ```
  Both methods throw raw `Exception`. The `skipped(...)` default is a helper to produce a checked exception that carries a pre-wrapped `ActionResult.skipped(...)` — implementers can `throw skipped(SkipReason.INVALID)` from inside an `orElseThrow`. This is the idiom every concrete impl uses.
- **`ActionResult`** (`ActionResult.java:5`) — immutable `(boolean applied, SkipReason reason, Transaction transaction)`. Two private constructors, two static factories: `success(Transaction)` sets `applied=true` and a non-null transaction; `skipped(SkipReason)` sets `applied=false` and a non-null reason. Either/or by construction. No getters for enforcing that invariant from outside, though — a caller can read `getReason()` on a success and get `null`.
- **`ActionableException`** (`ActionableException.java:3`) — checked exception wrapping an `ActionResult`. Its `super(reason.name())` blows up with NPE if the wrapped result is a success (since success has `reason=null`). Not a problem in practice because nothing constructs a success-wrapped exception.
- **`SkipReason`** (`SkipReason.java:3`) — flat enum: `INVALID_LOCATION`, `INVALID`, `OCCUPIED`, `UNIMPLEMENTED`, `UNKNOWN`. Zero metadata on each entry (no message template, no severity). Five values for the entire failure taxonomy; `INVALID` does most of the heavy lifting and means "nothing more specific fits."
- **`Transaction<T>`** (not rollback-specific, lives in `api/data/`) — the before/after pair returned inside a successful `ActionResult`. `BlockEntry.rollback` returns `Transaction<BlockState>`; `ContainerEntry.rollback` returns `LocationTransaction<Inventory>` (a subclass that adds a `Location`); `EntityEntry.rollback` returns `Transaction<Entity>`. The type parameter is lost at the `ActionResult` boundary (it's stored as raw `Transaction`). `runUndo` has to `instanceof`-test every transaction against each possible shape.

### Core side (3 implementations)

- **`BlockEntry`** (`BlockEntry.java:21`) — the big one. Handles `break`, `place`, `grow`, `form` per `sg.registerEventWrapperClasses`. `rollback()` restores `ORIGINAL_BLOCK`; `restore()` applies `NEW_BLOCK` (or AIR if absent). Both delegate tile-entity restoration to `handleTileEntity(editState, parent)` which knows about containers, signs, banners, and jukeboxes.
- **`ContainerEntry`** (`ContainerEntry.java:11`) — slot-level. Handles `withdraw`, `deposit`. Reads `ITEM_SLOT`, pulls `BEFORE.ITEMSTACK` (rollback) or `AFTER.ITEMSTACK` (restore), writes into the live container's inventory via `container.getInventory().setItem(slot, stack)`. Does **not** extend `BlockEntry` — different semantics.
- **`EntityEntry`** (`EntityEntry.java:11`) — death-revival. `rollback()` spawns a fresh entity of the stored `EntityType` at the location and runs stored NBT back into it via `ReflectionHandler.loadEntityFromNBT`. `restore()` returns `ActionResult.skipped(SkipReason.UNIMPLEMENTED)` — entity "restore" has no sane meaning.

Only these three `DataEntry` subclasses implement `Actionable`. The other ~33 registered events (chat, commands, joins, pickups, drops, item frame interactions, bundle inserts, sculk activations, bookshelf inserts, decorated pot inserts, brush strokes, vault unlocks, teleports, mounts, dismounts, ignites, dependant-breaks-as-separate-events, shulker deposits, etc.) fall through to `DataEntryComplete` in `DataEntry.from` and *cannot be reversed* — a `/sg rollback a:say p:alice t:1h` completes instantly with "0 reversals" because none of the returned entries implement `Actionable`. See doc 03's Table B for the enumeration; only `break`/`place`/`grow`/`form` → `BlockEntry`, `death` → `EntityEntry`, `withdraw`/`deposit` → `ContainerEntry`.

## Flow of a rollback

Canonical path: `/sg rollback p:alice r:30 t:1h`.

**Step 1 — Command dispatch.** Cloud routes the handler to `runApplier(sender, "p:alice r:30 t:1h", QuerySession.Sort.NEWEST_FIRST)` at `SpyglassCommands.java:103`. Note: `rollback` and `restore` are registered as literal aliases of the *same* handler (`SpyglassCommands.java:100-108`), differing only in the sort argument. `NEWEST_FIRST` → rollback, `OLDEST_FIRST` → restore. This mapping is implicit; see Pain Points #3.

**Step 2 — Session build + force `NO_GROUP`.** `runApplier` at `SpyglassCommands.java:199-200`:
```java
final QuerySession session = new QuerySession(sender);
session.addFlag(Flag.NO_GROUP);
```
The NO_GROUP flag prevents the Mongo backend's `$group` aggregation stage from collapsing repeated events into aggregate rows, which would lose the per-record detail rollback needs. Without this, `/sg rollback` against a grouped query would return `DataAggregateEntry` rows that don't implement `Actionable` — and you'd silently get zero reversals.

**Step 3 — Parse parameters.** `session.newQueryFromArguments(args)` — the same machinery as `/sg search` (see doc 04). Returns a `CompletableFuture<Void>` that fires when all parameter handlers have finished adding their conditions to the `Query`. Sort is set explicitly *after* the future is kicked but *before* the `thenAccept` chain is attached (`SpyglassCommands.java:203-204`), which is subtle but safe — `setSortOrder` mutates session state, and the future's `thenAccept` closes over `session` by reference.

**Step 4 — Hoist the limit, fetch results.** Inside the `thenAccept` continuation at `SpyglassCommands.java:205-209`:
```java
session.getQuery().setSearchLimit(sg.INSTANCE.getActionablesLimit());
CompletableFuture<List<DataEntry>> futureResults =
    v1.getStorageHandler().records().query(session);
```
`getActionablesLimit()` returns `10000` by default (`config.yml:limits.actionables`). The default `/sg search` limit is `limits.lookup.size=1000`, so rollback gets 10× the result ceiling. This is a guardrail — without a cap, a zealous `/sg rollback t:30d` would load every break event from the past month into memory, which on a busy server is millions of records. But the limit is a **silent truncation**: no warning to the user, no indication that the set was clipped, no follow-up paginated rollback. The 10001st block from the newest end of the query doesn't get rolled back, and nobody finds out.

**Step 5 — Iterate and actuate.** `SpyglassCommands.java:216-228`:
```java
for (DataEntry entry : results) {
    if (entry instanceof Actionable actionable) {
        try {
            if (sort.equals(QuerySession.Sort.NEWEST_FIRST)) {
                actionResults.add(actionable.rollback());
            } else {
                actionResults.add(actionable.restore());
            }
        } catch (ActionableException ae) {
            actionResults.add(ae.getResult());
        }
    }
}
```
Note the silent filter: non-`Actionable` entries are dropped without even a skip record. If a player ran `/sg rollback a:say` by mistake, the `for` loop produces an empty `actionResults` and the "0 reversals" message reveals nothing about *why* — the chat events loaded fine, they just weren't reversible.

Also note: this loop runs on **whatever thread completed `futureResults`**. That's the Mongo driver's async thread (see doc 02), which is fine for the loop logic itself — but `BlockEntry.rollback` calls `location.getBlock().setType(...)` and `editState.update(...)`, which are main-thread-only Bukkit operations. Paper will throw an `IllegalStateException: Asynchronous world access!` as soon as it hits the first non-thread-safe call. This is a live bug hiding behind the fact that Paper's async world check isn't always as aggressive as it should be — depending on JVM/plugin load order, the access may sneak through and corrupt state silently. A sync scheduler hop around the loop body would fix it; there isn't one.

**Step 6 — Tally, report, stash.** `SpyglassCommands.java:233-260`:
```java
int appliedCount = 0, skippedCount = 0;
for (ActionResult result : actionResults) {
    if (result.applied()) appliedCount++;
    else skippedCount++;
}
// ...
sender.sendMessage(Formatter.success(...));
if (sender instanceof Player player) {
    v1.addLastActionResults(player.getUniqueId(), actionResults);
}
```
Skipped entries each get a line dumped to chat: `"Skip Reason: INVALID"` with no extra context — no block coordinates, no entry ID, no timestamp (`SpyglassCommands.java:247-251`). So a rollback that reverses 8,000 blocks and skips 2,000 will spam two thousand `"Skip Reason: INVALID"` messages into the sender's chat buffer. Paper's chat queue will happily drop half of them on the floor.

**Step 7 — Stash for undo.** `v1.addLastActionResults` delegates to `sg.addLastActionResults` (`sg.java:220-222`) which just does `this.lastActionResults.put(id, results)` into a `HashMap<UUID, List<ActionResult>>`. In-memory only; a server restart (or plugin reload) erases the entire map. Not persisted. One slot per player — running a second rollback overwrites the first; there's no undo history stack.

### Inside `BlockEntry.rollback`

`BlockEntry.java:27-48`:
```java
@Override
public ActionResult rollback() throws Exception {
    DataWrapper original = data.getWrapper(ORIGINAL_BLOCK)
            .orElseThrow(() -> skipped(SkipReason.INVALID));
    BlockData originalData = DataHelper.getBlockDataFromWrapper(original)
            .orElseThrow(() -> skipped(SkipReason.INVALID));
    Location location = DataHelper.getLocationFromDataWrapper(data)
            .orElseThrow(() -> skipped(SkipReason.INVALID_LOCATION));

    BlockState beforeState = location.getBlock().getState();

    location.getBlock().setType(originalData.getMaterial());

    BlockState editState = location.getBlock().getState();
    editState.setBlockData(originalData);

    handleTileEntity(editState, ORIGINAL_BLOCK);

    editState.update(false, false);

    return ActionResult.success(new Transaction<>(beforeState, location.getBlock().getState()));
}
```

Three stages:

1. **Deserialize inputs.** Pull `ORIGINAL_BLOCK` wrapper, convert the `BlockData` string (e.g. `"minecraft:chest[facing=east,type=single,waterlogged=false]"`) back into a live `BlockData` via `DataHelper.getBlockDataFromWrapper` → `Bukkit.getServer().createBlockData(...)`. Pull the location from `LOCATION.X/Y/Z/World`. Any missing piece throws an `ActionableException` via the `skipped(...)` helper, short-circuiting up to `runApplier`'s catch at line 224.
2. **Apply block state.** Capture the live block's state into `beforeState` (for reporting), then `setType(material)` on the underlying block, then fetch a fresh `BlockState` and call `setBlockData(originalData)`. The double-hop (`setType` then `setBlockData`) is intentional: `setType` clears any existing tile entity (so the chest inventory we're about to restore goes in clean), then `setBlockData` rebuilds the data string's properties. A direct `setBlockData` without the prior `setType` would leak the previous block's tile entity data into the restored block.
3. **Restore tile-entity state.** Delegate to `handleTileEntity(editState, ORIGINAL_BLOCK)`. Then `editState.update(false, false)` commits the snapshot back to the world.

The `editState.update(false, false)` call matters. Signature is `update(boolean force, boolean applyPhysics)`. Both false means: don't force if the block has changed since we took the snapshot (harmless here since we literally just set it), and **don't apply physics updates to adjacent blocks**. That last part prevents a cascade (a torch on a restored wall would otherwise immediately fall off if the wall's block data differs from what the torch's physics expects). But: adjacent blocks that *should* update visually because of the restoration (flowing water, redstone, lit-vs-unlit furnace, etc.) won't — they retain their current state until a chunk reload or a manual re-trigger. See Pain Points #5.

### Inside `BlockEntry.restore`

`BlockEntry.java:51-73`. Symmetric to `rollback` but keyed off `NEW_BLOCK`, with one conditional branch:

```java
Optional<DataWrapper> oFinalState = data.getWrapper(NEW_BLOCK);
BlockState beforeState = location.getBlock().getState();
BlockState editState = location.getBlock().getState();
if (!oFinalState.isPresent()) {
    location.getBlock().setBlockData(Material.AIR.createBlockData());
    return ActionResult.success(new Transaction<>(beforeState, location.getBlock().getState()));
}
// ... else: setBlockData(finalData), handleTileEntity(editState, NEW_BLOCK), update(true, false)
```

Absent `NEW_BLOCK` means: the entry was a break event (nothing was placed), so "restore forward" means re-break the block → AIR. This is the right behavior for a break: breaking is idempotent, so `/sg restore` on a break entry just re-clears the block. **But it's wrong for a place entry where `NEW_BLOCK` somehow got dropped in serialization.** Current codepath can't tell the difference — both "break entry" (expected no `NEW_BLOCK`) and "place entry with corrupt data" (expected `NEW_BLOCK`, missing) hit the same AIR fallback. See Pain Points #6.

Also worth noting: `restore` uses `update(true, false)` (force apply, no physics) while `rollback` uses `update(false, false)` (no force, no physics). The asymmetry is unexplained in comments. Probably a historical accident.

### Inside `handleTileEntity`

`BlockEntry.java:75-141`. Four if-else branches on the `BlockState` type:

**Container** (`BlockEntry.java:76-120`). The meatiest branch and the one that hit the `components=null` bug.
```java
Inventory inventory = container.getSnapshotInventory();
int inventorySize = inventory.getSize();
data.getWrapper(parent.then(INVENTORY)).ifPresent(wrapper -> {
    Set<DataKey> keys = wrapper.getKeys(false);
    for (DataKey key : keys) {
        int slot = Integer.parseInt(key.toString());
        if (slot >= inventorySize) { /* warn + skip */ continue; }
        Optional<ItemStack> itemOpt = wrapper.getConfigSerializable(key);
        if (itemOpt.isPresent()) inventory.setItem(slot, itemOpt.get());
        else /* warn + skip */;
    }
});
```
Uses `getSnapshotInventory()`, not `getBlockInventory()` or `getInventory()`. Comment at `BlockEntry.java:79-82` explains: the snapshot-inventory variant writes into the `BlockState` snapshot, which is persisted back to the world when `editState.update(...)` fires at `BlockEntry.java:45`. Using the live inventory would bypass the snapshot mechanism and race with any active player opening the container. The fix-up logic added this session (count-and-warn) is forensic only — it doesn't retry, doesn't roll back the rollback, just logs to console.

The actual deserialization of each `ItemStack` happens in `wrapper.getConfigSerializable(key)` which drops into `DataHelper.unwrapConfigSerializable(wrapper)` for that slot:

```java
// DataHelper.java:69-94 (simplified)
public static <T extends ConfigurationSerializable> T unwrapConfigSerializable(DataWrapper wrapper) {
    Optional<String> oClassName = wrapper.getString(CONFIG_CLASS);
    if (!oClassName.isPresent()) return null;
    Class clazz = ConfigurationSerialization.getClassByAlias(oClassName.get());
    DataWrapper localWrapper = wrapper.copy().remove(CONFIG_CLASS);
    Map<String, Object> configMap = dataWrapperToMap(localWrapper);
    ConfigurationSerializable config = ConfigurationSerialization.deserializeObject(configMap, clazz);
    return (T) config;
}
```

And `dataWrapperToMap` + `unwrapValue` (`DataHelper.java:104-125`) are where today's bug fix lives:

```java
private static Object unwrapValue(Object val) {
    if (val instanceof DataWrapper) {
        DataWrapper dw = (DataWrapper) val;
        if (dw.getString(CONFIG_CLASS).isPresent()) {
            return unwrapConfigSerializable(dw);
        }
        return dataWrapperToMap(dw); // <-- fallback for nested-Map wrappers without CONFIG_CLASS
    } else if (val instanceof Collection) {
        return unwrapAsNeeded((Collection) val);
    } else if (val instanceof Map) {
        return unwrapAsNeeded((Map<?, ?>) val);
    }
    return val;
}
```

The `CONFIG_CLASS`-less fallback at line 118 is the load-bearing fix. Before it existed, a nested wrapper that originated from a plain `Map<String, Object>` (the 1.21+ `ItemStack.components` tree) returned `null` from `unwrapConfigSerializable` because there was no class tag, and the parent deserializer dropped that field entirely — so a rollback of a container full of books would restore each book with `components = null`, stripping custom model data, item names, the entire data-components layer that Paper 1.21+ uses for nearly everything above the `NAME` level.

The fix works because `ConfigurationSerialization.deserializeObject(Map, ItemStack.class)` is happy to accept a nested raw `Map` for the `components` field — it's Paper's own ItemStack deserializer that knows how to parse the component map from a `Map<String, Object>`. So the trick is just: "if it has `CONFIG_CLASS`, treat it as a ConfigurationSerializable; if not, pass it through as a plain Map." Previously the code only knew the first half.

Two things worth noting about this fix:

1. **It's Band-Aid on top of a schema that conflates two things.** A `DataWrapper` with `CONFIG_CLASS` and a `DataWrapper` without it look identical in the class hierarchy. The distinction is a runtime presence check on a string key. A proper schema (records with named fields, or even explicit wrapper-types like `SerializableWrapper` vs `MapWrapper`) would make this unrepresentable.
2. **The fallback catches *nested* Map-shaped wrappers but the write side still calls the wrapper `DataWrapper`.** The round-trip is Map → DataWrapper → Document → DataWrapper → Map, with the CONFIG_CLASS tag absent on the Map branch at every hop. That means any future layer that checks `CONFIG_CLASS` presence to decide handling still needs the same two-branch logic. The bug class is closed only if every caller routes through `dataWrapperToMap`/`unwrapValue`.

**Sign** (`BlockEntry.java:121-129`). Read `SIGN_TEXT` as a `List<String>`, call `sign.setLine(i, text)` for i∈[0,3]. No component support — signs on 1.20+ have front/back sides and color formatting; this code only writes the front. Restoring a colored sign loses the color. Restoring a 1.20+ back-side sign loses the back.

**Banner** (`BlockEntry.java:130-132`). Read `BANNER_PATTERNS` as a `List<Pattern>` (the banner-pattern ConfigurationSerializable, not the regex kind), `banner.setPatterns(list)`. The deserialization here goes through `DataWrapper.getSerializableList` which is its own path, not `unwrapConfigSerializable` — so the `components=null` bug fix doesn't cover banners. Banners don't use components for their patterns (as of 1.21) so no symptom yet, but the divergence in how lists-of-serializables are deserialized vs single serializables is a footgun. If a future MC version adds a component-like nested field to banner patterns, we'd hit the same class of bug again.

**Jukebox** (`BlockEntry.java:133-140`). Read `RECORD` as a `ConfigurationSerializable`, cast-check it's an `ItemStack`, `jukebox.setRecord(stack)`. `setRecord` is deprecated in favor of the `getInventory().setItem(0, stack)` pattern but still works. Only handles the spinning record; doesn't restore whether the jukebox is playing.

### Inside `ContainerEntry`

`ContainerEntry.java:14-34` (rollback):
```java
Location location = DataHelper.getLocationFromDataWrapper(data)
    .orElseThrow(() -> new ActionableException(ActionResult.skipped(SkipReason.INVALID_LOCATION)));
if (!(location.getBlock().getState() instanceof Container)) {
    throw new ActionableException(ActionResult.skipped(SkipReason.INVALID));
}
int slotAffected = data.getInt(DataKeys.ITEM_SLOT).orElseThrow(...);
ItemStack before = (ItemStack) data.getConfigSerializable(
    DataKeys.BEFORE.then(DataKeys.ITEMSTACK)).orElse(null);
Container container = (Container) location.getBlock().getState();
Inventory snapshot = container.getSnapshotInventory();
container.getInventory().setItem(slotAffected, before);
return ActionResult.success(new LocationTransaction<>(location, snapshot, container.getInventory()));
```

Note: `setItem` is called on `container.getInventory()` (the live inventory), not on `snapshot`. The `snapshot = container.getSnapshotInventory()` line is captured *before* the write and stuffed into the returned `LocationTransaction` as the original state — so the transaction records "here's what the inventory looked like before this rollback." The actual write goes to the live inventory and doesn't require an `update()` call because we're not mutating block state, only container contents.

Compare this to `BlockEntry.handleTileEntity`'s Container branch, which writes to `container.getSnapshotInventory()` and then relies on `editState.update(...)` to commit. Two different patterns for "restore container contents," used by two different entry types, in the same file tree. `ContainerEntry` goes straight to live; `BlockEntry` goes through the snapshot. The `ContainerEntry` path is race-prone: if a player has the container open mid-rollback, the live-inventory write will step on their UI.

`restore()` (`ContainerEntry.java:37-57`) is symmetric, reading `AFTER.ITEMSTACK` instead of `BEFORE.ITEMSTACK`.

Both methods return `LocationTransaction<Inventory>` as the success payload — inventory snapshot as `originalState`, live inventory as `finalState`. This is what lets `runUndo` key off `rawOriginal instanceof Inventory` for the container-undo case.

### Inside `EntityEntry`

`EntityEntry.java:13-41` (rollback):
```java
String entityData = data.getString(DataKeys.ENTITY).orElseThrow(() -> skipped(SkipReason.INVALID));
String entityType = data.getString(DataKeys.ENTITY_TYPE).orElseThrow(() -> skipped(SkipReason.INVALID));
EntityType type = EntityType.valueOf(entityType);
if (type == EntityType.PLAYER) return ActionResult.skipped(SkipReason.INVALID);

Entity baseEntity = DataHelper.getLocationFromDataWrapper(data)
    .map(loc -> loc.getWorld().spawnEntity(loc, type))
    .orElseThrow(() -> skipped(SkipReason.INVALID_LOCATION));

// This is an UNSAFE operation. Data can change from version to version.
ReflectionHandler.loadEntityFromNBT(baseEntity, entityData);

return ActionResult.success(new Transaction<>(null, baseEntity));
```

Comment calls it out explicitly: NBT format shifts across MC versions, and the reflection path goes under Bukkit's API into NMS. Resurrecting a zombie from NBT stored on 1.20.1 and applied on 1.21.4 may silently mangle its attributes, equipment, or age. No schema version check, no graceful degradation.

`restore()` returns `UNIMPLEMENTED` — "restore the death" has no inverse (the entity is already dead in the live world at the time of rollback, re-death isn't meaningful).

Transaction returns `Transaction<Entity>(null, baseEntity)`: original state is null (there was no entity — it was dead), final state is the newly-spawned entity. This is what lets `runUndo` key off `rawFinal instanceof Entity` and call `.remove()`.

## Flow of `/sg undo`

`SpyglassCommands.java:273-333`. The parallel-universe implementation.

**Step 1 — Player gate.** Console can't undo (`SpyglassCommands.java:274-277`). Reasonable: the stash is keyed by UUID.

**Step 2 — Fetch the stash.** `v1.getLastActionResults(player.getUniqueId())` returns `Optional<List<ActionResult>>`. Empty → "You have no valid actions to undo" (`SpyglassCommands.java:279-283`).

**Step 3 — Reverse and walk.** `List<ActionResult> results = Lists.reverse(oResults.get());` — Guava's reverse view. Then a `for (ActionResult result : results)` loop at `SpyglassCommands.java:289-324`. For each result:

```java
Object rawOriginal = result.getTransaction().getOriginalState();
Object rawFinal = result.getTransaction().getFinalState();

if (result.getTransaction() instanceof LocationTransaction locTx) {
    Location location = locTx.getLocation();
    if (rawOriginal instanceof Inventory inventory) {
        if (location.getBlock().getState() instanceof Container container) {
            container.getInventory().setContents(inventory.getContents());
            applied++;
        } else {
            skipped++;
        }
    }
}

if (rawOriginal instanceof BlockState state) {
    BlockData data = state.getBlockData();
    state.getBlock().setBlockData(data);
    if (state.getBlockData().equals(data)) applied++;
    else skipped++;
}

if (rawFinal instanceof Entity entity) {
    if (!entity.isDead() && entity.isValid()) {
        entity.remove();
        applied++;
    } else {
        skipped++;
    }
}
```

Three independent `if` branches, not `else if`. A single `ActionResult` could in principle match multiple branches (a `LocationTransaction<BlockState>` whose final state happened to be an `Entity` — not a real combination the current code produces, but there's nothing structurally preventing it). Each branch bumps its own counter. No single path through the code.

What's happening here: the `ContainerEntry` path produces `LocationTransaction<Inventory>`, caught by the first branch. The `BlockEntry` path produces `Transaction<BlockState>` (not a `LocationTransaction`!), caught by the second branch — which checks `rawOriginal instanceof BlockState`. The `EntityEntry` path produces `Transaction<Entity>` (again, not a `LocationTransaction`), caught by the third branch via `rawFinal instanceof Entity`.

Three different transaction shapes from three different `Actionable` impls, handled by three different duck-typing branches in `runUndo`. Zero dispatch through `Actionable`. If someone adds a fourth `Actionable` impl that produces a novel transaction shape, the rollback path works and the undo path silently no-ops.

Also worth squinting at: the `BlockState` branch at line 309-314 is nonsense. It reads `state.getBlockData()`, immediately writes it back via `state.getBlock().setBlockData(data)`, then checks whether `state.getBlockData().equals(data)` — which is trivially true since we just read it. The `applied++` counter bumps on every invocation. **The BlockState undo effectively reapplies the `beforeState` that the rollback captured** (because `BlockEntry.rollback` returns `Transaction<BlockState>(beforeState, afterState)` — so `rawOriginal` is the pre-rollback live state, and we're writing that back into the world, undoing the rollback). The tautology in the success check (`getBlockData().equals(data)` where `data = getBlockData()`) is still confused code, and the whole block doesn't restore tile-entity state (no `handleTileEntity` call), so un-undoing a rolled-back chest loses the chest's contents. Container inventories are handled by the `LocationTransaction<Inventory>` branch above but only for `ContainerEntry` rollbacks; if the rolled-back entry was a `BlockEntry` on a chest, the undo restores the block shell but leaves the chest empty.

No `editState.update(...)` call anywhere in the undo path. Physics cascades are left to whatever Bukkit does by default (which is usually nothing on a direct `setBlockData`).

Also: `Lists.reverse` is a live view, not a copy. If anything mutates the underlying list during iteration, the behavior is undefined. Nothing currently mutates during iteration, but the pattern is fragile.

**Step 4 — Tally.** Same shape as `runApplier`: send `"X reversals(). Y skipped"`. The `reversals()` with the trailing parens is a typo in the format string (`SpyglassCommands.java:328`). User-facing.

## Semantics matrix

How the three `Actionable` impls handle each direction and what the transaction carries:

| Entry | `rollback()` reads | `rollback()` writes | `restore()` reads | `restore()` writes | Transaction shape |
|---|---|---|---|---|---|
| `BlockEntry` | `ORIGINAL_BLOCK` (+ tile entity subkeys) | Block + tile entity | `NEW_BLOCK` (or AIR if absent) | Block + tile entity | `Transaction<BlockState>` |
| `ContainerEntry` | `BEFORE.ITEMSTACK` + `ITEM_SLOT` | Container slot | `AFTER.ITEMSTACK` + `ITEM_SLOT` | Container slot | `LocationTransaction<Inventory>` |
| `EntityEntry` | `ENTITY` (NBT) + `ENTITY_TYPE` + `LOCATION` | Spawns new entity from NBT | skipped (UNIMPLEMENTED) | — | `Transaction<Entity>` |

How `runApplier` dispatches:

| Sort direction | Dispatches to | Command |
|---|---|---|
| `NEWEST_FIRST` | `actionable.rollback()` | `/sg rollback ...` |
| `OLDEST_FIRST` | `actionable.restore()` | `/sg restore ...` |

How `runUndo` reverses each transaction shape:

| Transaction shape | Undo action | Skips when |
|---|---|---|
| `LocationTransaction` with `Inventory` original | `container.getInventory().setContents(original.getContents())` | block is no longer a Container |
| Any transaction with `BlockState` original (`BlockEntry` path) | `state.getBlock().setBlockData(state.getBlockData())` (tautology but writes) | never (bogus equality check always true) |
| Any transaction with `Entity` final (`EntityEntry` path) | `entity.remove()` | entity is already dead or invalid |

Failure modes by SkipReason:

| SkipReason | Raised by | Meaning |
|---|---|---|
| `INVALID_LOCATION` | all three entries | `LOCATION.*` missing / world UUID doesn't resolve |
| `INVALID` | all three entries | `ORIGINAL_BLOCK`/`NEW_BLOCK`/`ITEM_SLOT`/`ENTITY` key missing, or block state doesn't match expected type, or EntityType is PLAYER |
| `OCCUPIED` | defined in enum, **never raised** | intended for "someone is standing on the block"; nothing checks |
| `UNIMPLEMENTED` | `EntityEntry.restore` | restore-for-entity has no meaning |
| `UNKNOWN` | defined in enum, **never raised** | catch-all that nothing uses |

## Pain points

### 1. Only three entry types are `Actionable`

`BlockEntry`, `ContainerEntry`, `EntityEntry`. Nothing else. This is the most consequential design constraint of the subsystem — and it's imposed by the registration table at `sg.registerEventWrapperClasses` (`sg.java:103-111`), which hard-codes seven events to three classes and drops everything else into `DataEntryComplete`. There is no public API for an external plugin to register a fourth `Actionable` subclass (see doc 03, pain point #8).

Events a forensics tool might reasonably want to rollback that currently can't:
- `withdraw`/`deposit` from inventory → ContainerEntry already covers container slots but not *player* inventory changes
- `entity-withdraw`/`entity-deposit` (item frames, armor stands) → no `Actionable`
- `bundle-insert`/`bundle-extract`, `pot-insert`/`pot-remove`, `bookshelf-insert`/`bookshelf-remove`, `shulker-*` → 1.20+/1.21+ container events, all just `DataEntryComplete`
- `ignite` (fire placed on a block) → would be trivial to reverse (remove fire)
- `drop`/`pickup` → in principle reversible (respawn the dropped item stack at the original location)

A user running `/sg rollback t:1h` on a griefing incident mid-server-war gets the blocks back but loses all the fire, all the pickups, all the bundles. The rollback is partial without any indication that it's partial.

### 2. Synchronous block writes on the Mongo thread

`runApplier`'s inner loop runs inside `futureResults.thenAccept(...)`, which completes on whatever thread the Mongo driver ran the aggregation on (an internal driver pool). From that thread, the code calls `actionable.rollback()` which invokes `location.getBlock().setType(...)`, `editState.setBlockData(...)`, `editState.update(...)`, and `container.getInventory().setItem(...)` — all main-thread-only operations.

Paper's async world access check is supposed to catch this with `IllegalStateException`. In practice, the check fires unreliably depending on which exact method is called and which Paper/server version. On a server where the check is loose, the world writes go through on the wrong thread and silently corrupt the chunk's queue. On a server where the check is strict, every single rollback fails loudly with a stack trace and the user sees "0 reversals" with no skip reasons printed (the exception is caught by the outer `try/catch` which just prints a stack trace to console).

The fix is a `Bukkit.getScheduler().runTask(plugin, () -> {...})` around the loop body. Missing.

### 3. `NEWEST_FIRST` means rollback, `OLDEST_FIRST` means restore — implicit

`SpyglassCommands.java:219-223`:
```java
if (sort.equals(QuerySession.Sort.NEWEST_FIRST)) {
    actionResults.add(actionable.rollback());
} else {
    actionResults.add(actionable.restore());
}
```

The command alias determines sort, sort determines intent. If a future flag handler sets sort based on some other criterion (e.g. `-ord=asc` in a `/sg rollback` query, which would flip the sort to `OLDEST_FIRST` — perfectly parseable by `FlagOrder`), the rollback silently becomes a restore. No `Intent` enum distinguishes "I want to reverse history" from "I want to fast-forward history."

The actual sort matters for correctness: rolling back from newest-first ensures that a block broken and then re-placed has its break reversed before the place (so the chain is: live world → place gets rolled back to break → break gets rolled back to original block). OLDEST_FIRST would apply them in the wrong order and leave the world in a weird intermediate state. The fact that sort order and intent are the same variable is a coincidence, not a design.

### 4. Actionables limit is a silent truncation

`limits.actionables=10000` (config default). `Query.setSearchLimit` clips the result set. Rollback over a larger window returns the 10000 most recent rows only, with no "result set truncated, run again with a narrower time window" message. A griefer who destroyed 50000 blocks in a raid gets 40000 of them left in place after `/sg rollback p:griefer t:1h` "succeeds."

A good guardrail would either:
- Hard-error with `"Too many actionables (X); narrow the query"`, or
- Paginate: do the rollback in batches of 10000 with a progress report, or
- At minimum, warn loudly when the result count hits the limit

### 5. Physics updates suppressed → visual desync

`editState.update(false, false)` on the rollback path. Adjacent blocks that depend on the restored block (torches attached to walls, pressure plates, redstone, doors/trapdoors, water/lava flow, crops needing farmland) don't re-evaluate their physics. Until a chunk reload or a neighboring tick, a rolled-back wall might be missing its torch visually (because the rollback of the wall happened after the rollback of the torch, and the torch's physics-check doesn't re-fire on the main thread).

In practice this is mostly harmless because:
- `editState.update(true, false)` on `restore` does force, which is slightly more aggressive
- The next chunk-save tick will sort it out
- Players reconnecting to the chunk get a fresh client-side state

But it means a rollback of a complex griefing-prone contraption (redstone door, piston-based trap) doesn't visually "work" immediately even when every block is restored to its original state. Looks buggy, is technically correct.

A `force=true, applyPhysics=true` would cascade changes but could trigger infinite loops on contrived builds, which is why the conservative pair was picked. There's no middle ground in Bukkit's API; either you apply physics everywhere or nowhere.

### 6. `BlockEntry.restore` with missing `NEW_BLOCK` sets AIR unconditionally

`BlockEntry.java:57-60`:
```java
if (!oFinalState.isPresent()) {
    location.getBlock().setBlockData(Material.AIR.createBlockData());
    return ActionResult.success(new Transaction<>(beforeState, location.getBlock().getState()));
}
```

Correct for break events (no `NEW_BLOCK` = "after the break, it's air" = restoring forward means breaking again = set AIR).

Wrong for place events where `NEW_BLOCK` should have been written but the wrapper got corrupted (Mongo column missing, schema drift, older record from before `NEW_BLOCK` was stored). The restore happily clears the block to AIR and reports success.

The fix is a disambiguator: either the event type ("if this was a break, AIR is right; if this was a place, INVALID is right") or an explicit "was there a NEW_BLOCK intended?" flag at write time. The current `BlockEntry.restore` has no way to tell the two cases apart because it only has the wrapper, not the event name that produced it.

### 7. `runUndo` is a divergent second implementation

Covered in detail above. The undo path is duck-typing the three transaction shapes produced by the three `Actionable` impls, and its logic disagrees with what the rollback did:
- Block undo doesn't call `handleTileEntity`, so containers come back empty
- Block undo's success-counting tautology (`blockData.equals(blockData)`) always succeeds even when nothing changed
- No `editState.update(...)` physics ticker
- No support for transaction shapes other than the three hard-coded ones
- `Lists.reverse` is a live view

A single `Actionable.undo(ActionResult)` method would collapse this — each entry type knows how to reverse its own transaction, and `runUndo` becomes `for (result : results) entry.undo(result)`. Doesn't exist.

### 8. `lastActionResults` is in-memory, unscoped, unbounded, global

`sg.java:34`: `private Map<UUID, List<ActionResult>> lastActionResults = Maps.newHashMap();`

Observations:

- **Not persisted.** Server restart → lost. Plugin reload → lost. `/sg rollback ...` then `/stop` → un-undoable.
- **One slot per player.** Running a second rollback before undoing the first wipes the first. No history stack.
- **No TTL.** If a player runs a rollback and quits without undoing, their entry lives until the server restarts. Memory footprint per stuck entry: up to 10000 `ActionResult` objects (ceilinged by `limits.actionables`), each holding a `Transaction` that holds a `BlockState` (full tile-entity snapshot including inventory `ItemStack`s). On a busy forensics server with 20 admins each having stashed rollbacks, this is non-trivial heap.
- **Not thread-safe.** `HashMap`, not `ConcurrentHashMap`. `addLastActionResults` is called from whatever thread completed the Mongo future; `getLastActionResults` from the main thread via `runUndo`. Concurrent access on a different player's write is possible during busy rollback periods. Paper's async scheduler makes this moderately unlikely to race but the type is unsound.

### 9. `FAWERollbackHandler.batchRollback` is dead code

`FAWERollbackHandler.java:56-68` declares `batchRollback` and `batchRestore`, and the class self-logs "FAWE/WorldEdit detected - batch rollback enabled" in its static initializer (`FAWERollbackHandler.java:40`) — but nothing calls either method. Zero call sites across the codebase.

The implementation is straightforward:
1. Filter entries to block entries only (`FAWERollbackHandler.java:80`)
2. Resolve location, get target `BlockData`
3. Group changes by world
4. For each world: open a FAWE `EditSession`, `setBlock(pos, blockData)` in a loop, let the try-with-resources close the session (which flushes the batched changes asynchronously to FAWE's worker threads)

For a 10,000-block rollback this would be roughly one order of magnitude faster than the per-block Bukkit path, with zero main-thread cost. Tile entity restoration isn't handled (the implementation only calls `setBlock`, not `handleTileEntity`), which is the missing piece — containers, signs, banners, and jukeboxes would come back empty through this path. But the block layer is done and working.

The reason it's uncalled: someone started a FAWE integration path, got the block-layer piece done, ran into the tile-entity problem, and left it. Nothing hooks it up to the command dispatcher. `runApplier` always goes through the per-entry `Actionable` path. The FAWE batch path is pure dead weight in the classpath.

There's also a bug in the dead code: each successful block change produces an `ActionResult.success(null)` (`FAWERollbackHandler.java:138`) — a success with a null transaction. The `runUndo` code would NPE on this if the results were ever stashed and an undo attempted.

### 10. Skip reasons are stringly-typed noise

`SkipReason` has five values; `INVALID` is used for at least six distinct error conditions across the three Actionable impls:
- Missing `ORIGINAL_BLOCK` wrapper (BlockEntry rollback)
- `BlockData` fails to parse from string (BlockEntry rollback)
- Missing `ITEM_SLOT` (ContainerEntry)
- Block at location is not a Container (ContainerEntry)
- Missing `ENTITY` NBT string (EntityEntry)
- EntityType is PLAYER (EntityEntry) — semantically an "explicitly refuse" case, not an "invalid data" case

One error taxonomy, one opaque tag. `OCCUPIED` and `UNKNOWN` are defined but never raised. `UNIMPLEMENTED` is used once (EntityEntry.restore).

User sees `"Skip Reason: INVALID"` and can't tell whether the block data was corrupted, the entity was a player, or the container was replaced with a non-container since the original event. Debugging requires reading the code and running the exact scenario to see which branch fired.

### 11. No dry-run

The user types `/sg rollback p:alice r:20 t:30m` and the world changes. There's no preview, no "this will affect 347 blocks, confirm with `/sg rollback confirm`," no particle outline of affected positions. A wrong parameter (wrong name, too-wide radius, too-long time window) is a commit.

### 12. Container-undo path mutates the live inventory, not a snapshot

`SpyglassCommands.java:300-302`:
```java
if (location.getBlock().getState() instanceof Container container) {
    container.getInventory().setContents(inventory.getContents());
}
```

Writes to the live inventory. If a player is mid-transaction on the container (looking at it, moving items around), the write steps on their UI. Paper's inventory model is more forgiving than Bukkit's — it will resync to the player — but it's still a race.

The `ContainerEntry.rollback` path has the same issue (writes to live inventory per `ContainerEntry.java:31`). `BlockEntry.handleTileEntity` uses the snapshot inventory path correctly. Three separate container-mutation paths in this subsystem, two of which go live, one through snapshot.

### 13. `FlagDrain` is a flag no one reads

`sg` and the parser register `-drain`, which sets `Flag.DRAIN` on the session (`FlagDrain.java:29`). Nothing reads it. A search of the codebase turns up zero `Flag.DRAIN` consumers in any rollback or entry path. The user types `/sg rollback p:alice -drain` and nothing happens differently than without `-drain`.

The flag name implies "empty containers' inventories during rollback" — a common operator request when rolling back someone's stolen-goods deposits. The feature was never built. The flag remains, harmless but misleading.

### 14. `ActionResult.getReason()` returns `null` on success

No guardrail. A caller that forgets to check `applied()` first will NPE or log `"Skip Reason: null"`. The chat-dump loop in `runApplier` at `SpyglassCommands.java:247-251` gates correctly, but anything else reading an `ActionResult` has to remember.

A sealed `RollbackResult permits Success, Skipped` with typed variants would prevent this at the type level.

### 15. `ActionableException` extends `Exception` (checked)

Every `Actionable` implementation's `rollback`/`restore` declares `throws Exception`. Callers (i.e. `runApplier`) have to catch `ActionableException` specifically to extract the result, but the catch block at `SpyglassCommands.java:224-226` falls through to the outer `try` for any *other* exception, which just prints a stack trace to console — the user gets nothing. An exception from e.g. `ReflectionHandler.loadEntityFromNBT` due to NBT schema drift is entirely invisible to the player running the undo.

### 16. No transaction atomicity

A rollback of 10000 blocks that fails after 3000 has:
- 3000 blocks in the "restored" state
- 7000 blocks in the "griefed" state
- No state tracked anywhere to resume or reverse

If a subsequent `/sg undo` is attempted, the stash contains 3000 `ActionResult`s (only the successful ones were appended to the list). The 7000 un-rolled-back blocks are un-accounted for. The user has to re-run the rollback and hope the query returns a consistent result.

Realistically, partial failures are rare (the per-block code path is simple and the failure modes above are edge cases). But there's no framework for "transaction in progress, reverse everything on failure" — if the plugin crashed halfway through a 10000-block rollback, the world is permanently in a half-reversed state with no record of what was done.

### 17. Tile-entity restoration is conflated between `BlockEntry` and `ContainerEntry`

`BlockEntry.handleTileEntity` handles Container/Sign/Banner/Jukebox for a *block's tile entity state during a block-level rollback*. `ContainerEntry.rollback` handles a *single slot change in an existing container*. Both share the container concept but the code paths don't share logic.

What this means in practice: rolling back a break of a chest uses `BlockEntry` which uses the snapshot-inventory path and goes through `handleTileEntity`. Rolling back a withdraw from the same chest uses `ContainerEntry` which writes to the live inventory and bypasses the block-state mechanism entirely. The two are not composable. A `/sg rollback` spanning both types of events on the same container applies them in the wrong order (because sort is NEWEST_FIRST and Mongo doesn't know to ordering them by "block-level first, then slot-level") and will get confused results.

## Modernization hotspots

### 1. Unified rollback dispatcher

One code path for rollback, restore, and undo, driven by the entry's stored before/after states. Pseudocode:
```java
sealed interface EntryEffect permits BlockReplace, ContainerSlotWrite, EntitySpawn, EntityRemove, ...;
sealed interface RollbackResult permits Applied, Skipped;
record Applied(EntryEffect forward, EntryEffect reverse) implements RollbackResult {}
record Skipped(SkipReason reason, String detail) implements RollbackResult {}

interface Rollbackable {
    EntryEffect forwardEffect(); // what restore should apply
    EntryEffect reverseEffect(); // what rollback should apply
}

class Engine {
    RollbackResult apply(EntryEffect effect, World world) { ... }
    // Undo is: Engine.apply(result.reverse(), world)
}
```

One dispatcher. One `apply(effect, world)` method. The distinction between rollback/restore is just "which effect field do I pass." Undo inverts the effect returned in `Applied.reverse()`. Three different call sites become one.

### 2. Wire up a batched FAWE path, delete or finish the dead one

The `FAWERollbackHandler` as it stands has the block-layer right and no tile-entity handling. Either:

a. **Resurrect and complete it.** Add tile-entity restoration. Route `runApplier` through it when (i) FAWE is available, (ii) the operation is ≥ N blocks (config `limits.fawe-threshold=100` or similar), and (iii) all entries are `BlockEntry`. For operations below the threshold, keep the per-block Bukkit path (already fast enough for small operations, and FAWE's EditSession has fixed startup overhead).

b. **Delete it and start from scratch.** A FAWE batch layer is genuinely the right architecture for large rollbacks, but the existing implementation has bugs (null transactions, missing tile-entity handling, static-init noise) and may be a worse starting point than a clean rewrite.

Either way: the "FAWE detected - batch rollback enabled" startup log message that currently prints even though batch rollback is **not** enabled is actively misleading and should go immediately.

### 3. Async rollback with progress reporting

`/sg rollback p:alice r:50 t:6h` on a busy server might affect tens of thousands of blocks. The current sync path tick-lags the server for seconds. A Paper 1.21 virtual-thread path:
```java
Thread.ofVirtual().name("sg-rollback-" + player.getUniqueId()).start(() -> {
    int total = entries.size();
    for (int i = 0; i < total; i++) {
        // apply entry i on the main thread via a scheduled task
        // or batch-flush every 100 entries to FAWE
        if (i % 500 == 0) {
            player.sendActionBar("Rolling back: " + i + " / " + total);
        }
    }
    player.sendMessage("Rollback complete: " + applied + " / " + total);
});
```

The apply-each-entry inner loop still has to hop to the main thread for block writes (unless routed through FAWE). But the *orchestration* — progress reporting, batching, timeout handling — lives on the virtual thread and doesn't block anything.

### 4. Dry-run with particle preview

`/sg rollback p:alice r:50 t:6h --preview` (or default to preview mode, require `--apply` to commit). Flow:
1. Run the query, get entries.
2. For each entry, compute the target location + outcome (block change, container slot change, entity spawn).
3. Spawn short-lived particle effects at each location (Paper's `World.spawnParticle(Particle.REDSTONE, ...)`, color-coded by action type).
4. Send `"1,247 affected blocks. Type /sg confirm to apply, auto-cancels in 15s."`
5. Either `/sg confirm` fires the apply, or a scheduled task silently drops the pending operation.

The preview is read-only on the world, so it can run freely async. The apply requires a confirmation flow with per-player state (similar to `lastActionResults` but for pending ops).

### 5. Extend `Actionable` to more entry types

The right subset:

- **`ItemDropEntry`** — `drop` events are reversible: remove the dropped item stack if still on the ground, or if picked up, consider it gone. Windowed to ~5 min.
- **`EntityInteractEntry`** — `entity-withdraw`/`entity-deposit` on item frames and armor stands are slot-level, same shape as `ContainerEntry`.
- **`ContainerExtendedEntry`** — bundle-insert/extract, pot-insert/remove, bookshelf-insert/remove, shulker-\* all fit the slot-diff shape.
- **`FireEntry`** — `ignite` events can be reversed (remove the fire block at the target).
- **`TerrainGrowthEntry`** — `grow` and `form` are already `BlockEntry` but the rollback writes the pre-growth state, which is usually AIR. Fine; no change needed.

Limit each new rollbackable type to a sensible window (don't let someone rollback drops from 2 weeks ago). Add a per-type `maxAge` config.

### 6. Sealed `RollbackResult` with typed failure cases

Replace `SkipReason` with:
```java
sealed interface RollbackResult permits Applied, Skipped;
record Applied(EntryEffect forward, EntryEffect reverse, Instant at) implements RollbackResult {}
sealed interface Skipped extends RollbackResult permits
    InvalidLocation, // LOCATION.* missing
    WorldUnloaded, // world UUID doesn't resolve
    BlockChanged, // block at location isn't what we expected
    MissingData, // required wrapper key absent
    SchemaMismatch, // CONFIG_CLASS unknown, or NBT format drift
    PlayerTarget, // refused because subject is a player
    Unimplemented, // restore-for-entity
    NotRollbackable; // entry type has no Actionable impl
record BlockChanged(Location at, Material expected, Material actual) implements Skipped { ... }
// etc.
```

Each case carries the specific context. `"Skip Reason: BlockChanged at (-123, 64, 512): expected CHEST, found STONE"` is useful; `"Skip Reason: INVALID"` is not.

### 7. Persistent per-player undo history

`lastActionResults` moves to Mongo. Schema: collection `UndoHistory` keyed by `(playerUuid, operationId)` with fields `operationType`, `createdAt`, `entries[]`. TTL of 24 hours. Max 5 operations per player (evict oldest).

Survives restarts. Supports an undo history stack: `/sg undo` pops the most recent; `/sg undo 3` pops the one before that. Keyed by player UUID (same as now) but also with a per-server scope if multi-server support lands.

### 8. Transaction atomicity

Mark every rollback operation with an operation ID at start. Track each entry's state (`pending` → `applied` or `skipped` or `failed`). On a fatal failure mid-operation, the reverse path runs: take the `applied` set, reverse each, then mark the whole op as `aborted`.

Cost: double the work on the write side, storage overhead for the per-operation metadata. Benefit: a 10000-block rollback that crashes at 7000 either completes (on resume) or reverses cleanly. No more "world is permanently in half-rolled-back state."

Could be scoped: only required for operations above, say, 1000 entries. Smaller operations accept the partial-failure risk.

### 9. Collapse the three rollback/undo code paths

Today: `BlockEntry.rollback` writes a block. `ContainerEntry.rollback` writes a slot. `EntityEntry.rollback` spawns an entity. `runUndo` duck-types three transaction shapes. `FAWERollbackHandler.batchRollback` writes blocks in bulk.

Five code paths, five different mutation models.

v2: each `DataEntry` subclass computes an `EntryEffect` from its stored state. A single `EffectApplier` (with a FAWE sub-path for batched blocks and a Bukkit sub-path for everything else) takes a list of `EntryEffect`s and applies them. The effect taxonomy is bounded (see hotspot #1); adding a new entry type means implementing `EntryEffect` for its change shape, not writing a new rollback/undo pair.

### 10. Physics-update flag should be tunable

`editState.update(false, false)` suppresses physics everywhere. Some rollbacks want physics (restoring a water pool wants water to re-spread). Some don't (restoring a piston-based farm mid-cycle shouldn't tick the pistons). Expose as a per-operation flag: `/sg rollback --physics` or `--no-physics` (default off).

### 11. Snapshot-vs-live inventory should be consistent

Pick one path for container mutation (snapshot + `update()`, or live-inventory writes) and use it everywhere. The current two-track approach is race-prone and confusing.

### 12. Error handling in `runApplier` should report to the user

Today's `try/catch (Exception ex)` at `SpyglassCommands.java:262-265` prints the stack to console and shows `Formatter.error(e.getMessage())` to the sender — but only for exceptions in the *outer* `thenAccept`. Exceptions from inside the per-entry loop (e.g. async world access violations on the Mongo thread) are caught by the inner `try/catch (Exception ex)` at line 229-231 which just prints to console. The user sees "0 reversals" with no stack trace, no indication that anything went wrong.

Exception dispatch needs a single choke point that always surfaces errors to the sender with a meaningful message, plus full stack traces to console for ops.

## What v2 should keep

- **The `ActionResult` success/skipped bifurcation.** It's the right shape — either the change applied (with a before/after transaction) or it didn't (with a reason). Just make it a sealed type and add structured reasons (hotspot #6).

- **The two-direction `Actionable` interface** — forward (restore) and backward (rollback) as distinct methods on the entry type. Keep the duality. The symmetry between a place event's rollback and a break event's restore is real and worth modeling explicitly. Just extend the interface to include `undo(ActionResult)` as well so the third divergent code path collapses into the interface.

- **Per-entry type dispatch.** `BlockEntry` knows how to reverse a block change; `ContainerEntry` knows how to reverse a slot change; `EntityEntry` knows how to resurrect a mob. The responsibility boundary is correct; the mechanism (sealed interface + effect records) just needs modernizing.

- **The `actionables` config limit** as a guardrail on operation size. Keep the cap, fix the silent-truncation behavior, probably promote to a hard-error + paginate-to-continue model (hotspot #4 on silent truncation / pain point #4).

- **Per-player scoping of undo state.** Tying `lastActionResults` to a player UUID is correct — rollback is a user-level action and undo needs to be user-level too. Just persist it (hotspot #7).

- **The `NO_GROUP` auto-flag in `runApplier`.** Forcing `-ng` on rollback queries prevents the aggregation stage from collapsing the result set. Correct and necessary; should stay even in a typed redesign.

- **Separation of query from action.** `runApplier` runs a query, gets entries, iterates. The query pipeline doesn't know or care that rollback is coming next. Clean separation. A typed v2 can preserve this: `Query → List<DataEntry>` → `List<DataEntry> → List<RollbackResult>` as two distinct stages with an obvious seam between them.

- **The snapshot-inventory path in `BlockEntry.handleTileEntity`.** It's the correct abstraction for "restore container contents as part of a block-state write." The `ContainerEntry` path that writes to the live inventory is the one that should be rewritten to match, not the other way around.

- **Tile-entity delegation in `handleTileEntity`.** The branch-per-block-type shape (Container / Sign / Banner / Jukebox) is fine; it's just missing newer types (1.21 decorated pots, chiseled bookshelves, vault states, brush-able blocks). Extending the list is easy; the structure is right.

- **`DataHelper.unwrapConfigSerializable` + `dataWrapperToMap` + `unwrapValue`.** The fix added today is the right shape — branch on `CONFIG_CLASS` presence at every nesting level, fall back to raw Map for plain nested maps. Keep this path; just make the schema itself stop conflating the two cases so the branch isn't load-bearing.

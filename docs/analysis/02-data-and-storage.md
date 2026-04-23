# Omniscience Dissection — 02: Data and Storage

Files covered:

- API: `OmniscienceAPI/src/main/java/net/medievalrp/omniscience/api/data/DataKey.java`, `DataKeys.java`, `DataWrapper.java`, `Transaction.java`, `LocationTransaction.java`, `InventoryTransaction.java`
- Core: `Omniscience-Core/src/main/java/net/medievalrp/omniscience/io/StorageHandler.java`, `RecordHandler.java`, `mongo/MongoStorageHandler.java`, `mongo/MongoRecordHandler.java`, `dynamo/DynamoStorageHandler.java`, `dynamo/DynamoRecordHandler.java`

## Responsibility

This is the data-plane of the plugin. The API module defines a schemaless, hierarchical, string-keyed document (`DataWrapper`) along with the dotted-path keys (`DataKey` / `DataKeys`) used to read and write into it, plus the transaction record types that describe before/after state. The Core module defines a `StorageHandler` / `RecordHandler` interface pair for persisting a batch of wrappers and querying them back, and ships two implementations: a working Mongo backend that flattens each `DataWrapper` into a BSON `Document`, and a stub Dynamo backend. Everything between `OEntry.save()` and "a row in the database" is this layer.

## Classes

- **`DataKey`** (`DataKey.java:17`) — immutable dotted-path key. Internally an `ImmutableList<String>` (e.g. `["Location", "X"]`). Supports `then(String)`, `then(DataKey)` for path composition, `pop()` / `popFirst()` for walking, and `asString('.')` for serialization back to a single string. Comment at the top attributes the concept to Sponge. Implements `equals`/`hashCode` over the parts.
- **`DataKeys`** (`DataKeys.java:8`) — static registry of every canonical key. 40 constants: `EVENT_NAME = DataKey.of("Event")`, `PLAYER_ID = DataKey.of("Player")`, `LOCATION`, `X`/`Y`/`Z`/`WORLD`, `ORIGINAL_BLOCK`, `NEW_BLOCK`, `INVENTORY`, `SIGN_TEXT`, `BANNER_PATTERNS`, `RECORD`, `DAMAGE_CAUSE`, `IPADDRESS`, etc. Also defines meta keys `DISPLAY_METHOD` and `ORIGIN`, plus `CONFIG_CLASS = DataKey.of("ClassName")` used to tag any nested `ConfigurationSerializable`.
- **`DataWrapper`** (`DataWrapper.java:29`) — the ubiquitous, schemaless document. A `LinkedHashMap<String, Object>` of child data plus a back-pointer to the parent wrapper and the key this wrapper lives at. Eight-hundred-line Swiss-army knife: `set(key, value)` handles `DataWrapper`/`ConfigurationSerializable`/`Map`/`Collection`/array/primitive distinctly; `get(key)` walks dotted paths; `ofBlock(BlockState)` constructs a wrapper of `MaterialType` + `BlockData`; `ofConfig(ConfigurationSerializable)` recursively serializes Bukkit-serializable objects, tagging each level with `CONFIG_CLASS`. The only strongly-typed accessors are `getBoolean`/`getString`/`getInt` plus a `getConfigSerializable` and `getStringList`/`getByteList` family. Also quietly mutates all inbound strings through `sanitizeUtf8` (see pain points).
- **`Transaction<T>`** (`Transaction.java:13`) — two `Optional<T>` slots (`originalState`, `finalState`) with `equals`/`hashCode`. Trivial.
- **`LocationTransaction<T>`** (`LocationTransaction.java:10`) — `Transaction<T>` plus a Bukkit `Location`. Used for every block-shaped event (break, place, grow, decay, form, explode, etc.).
- **`InventoryTransaction<T>`** (`InventoryTransaction.java:6`) — `Transaction<T>` plus `ItemStack diff`, an `int slot`, the `InventoryHolder`, and an `ActionType` enum (`WITHDRAW` / `DEPOSIT` / `CLONE`). Used exclusively by the inventory listeners. Despite the class name it doesn't extend `LocationTransaction`, even though container events are inherently located — callers have to pull the location off the `InventoryHolder`.
- **`StorageHandler`** (`StorageHandler.java:5`) — three-method interface: `connect(Omniscience)`, `records()`, `close()`. Tiny; the actual persistence logic is in `RecordHandler`.
- **`RecordHandler`** (`RecordHandler.java:10`) — two methods: `write(List<DataWrapper>)` (batch insert) and `query(QuerySession) -> CompletableFuture<List<DataEntry>>` (read). Note the asymmetry — writes are synchronous-on-caller-thread but reads are futures. In practice both end up on the async scheduler thread (`EntryQueueRunner` for writes, `SearchCallback` chain for reads), but the interface is inconsistent about it.
- **`MongoStorageHandler`** (`MongoStorageHandler.java:23`) — parses the `mongodb.servers` config list into `ServerAddress` objects, builds a `MongoClient` with optional credentials, stashes the `MongoDatabase` in a `static` field (!), constructs the record handler, and tries to create three indexes. `close()` is empty — the client is never explicitly shut down.
- **`MongoRecordHandler`** (`MongoRecordHandler.java:36`) — the real work. `write()` does a `bulkWrite` of unordered inserts with `Expires` stamped per document. `query()` translates the `Query` into a Mongo aggregation pipeline with a `$match` stage, optional `$group`, `$sort`, `$limit`, then translates each result back into a `DataEntry` via `documentToDataWrapper`. Owns the `DataWrapper ↔ Document` bidirectional conversion.
- **`DynamoStorageHandler`** (`DynamoStorageHandler.java:9`) — hard-codes `.withRegion("@TODO")` at line 17, returns `false` from `connect()`, and has an empty `close()`. This would never work in production. See pain points.
- **`DynamoRecordHandler`** (`DynamoRecordHandler.java:23`) — `write()` builds a `List<Item>` from the wrappers and then *discards* it — there is no `putItem`/`batchWriteItem` call. `query()` constructs an empty `QueryRequest`, ignores it, and returns `null`. Literal dead code that claims to be a backend.

## How a value round-trips

Walk one `BlockBreakEvent` from listener to Mongo and back.

### Write path

1. **Listener → OEntry**. In a `BlockBreakListener`, the code creates a `LocationTransaction<BlockState>` with the block's pre-break `BlockState` and `null` for the final state, then calls:
   ```java
   OEntry.create().source(player).brokeBlock(txn).save();
   ```
2. **EventBuilder.brokeBlock** (`OEntry.java:124`). Gets an `eventName = "break"` tag and writes into the internal `DataWrapper`:
   - `ORIGINAL_BLOCK` → `DataWrapper.ofBlock(state)` which contains `MaterialType` (material enum name) and `BlockData` (the Bukkit block-data string).
   - `TARGET` → material name again (used as the display target).
   - `writeExtraStateData(ORIGINAL_BLOCK, state)` — if the block was a sign, a container, a banner, or a jukebox, attach `SIGN_TEXT` / `INVENTORY` / `BANNER_PATTERNS` / `RECORD` at `ORIGINAL_BLOCK.<thing>`.
   - `writeLocationData(txn.getLocation())` — writes `LOCATION.X`, `LOCATION.Y`, `LOCATION.Z`, `LOCATION.WORLD` (world UUID as string).
3. **OEntry.save** (`OEntry.java:53`). Stamps `EVENT_NAME = "break"` and `CREATED = new Date()` onto the root wrapper, figures out whether the source is a `Player` (→ `PLAYER_ID`) or not (→ `CAUSE`), writes the UUID/name/tag there, and calls `EntryQueue.submit(wrapper)`.
4. **EntryQueue** (`EntryQueue.java:16`). Pushes onto a `LinkedBlockingDeque<DataWrapper>`. Returns immediately; the player tick is free.
5. **EntryQueueRunner** (`EntryQueueRunner.java:13`, fires every 20 server ticks on an async scheduler thread). Drains the queue, calls `Omniscience.getStorageHandler().records().write(batchWrappers)`.
6. **MongoRecordHandler.write** (`MongoRecordHandler.java:46`). For each wrapper:
   - `documentFromDataWrapper(wrapper)` recursively walks the wrapper. For each top-level key in `wrapper.data`, if the value is a `DataWrapper` it recurses into a nested `Document`; if it's a `List`, it rebuilds the list mapping nested wrappers to documents; if it's a primitive it appends directly. Special case: the `Player` key is re-appended under its literal string (leftover from an earlier model where PLAYER_ID was an ObjectId).
   - Appends `Expires` stamped with `DateUtil.parseTimeStringToDate(config.getRecordExpiry(), true)` — a `Date` in the future relative to now.
   - Wraps each document in an `InsertOneModel` and issues one `bulkWrite` with `ordered(false)`.
7. **Mongo** stores the BSON document. The `Expires` TTL index reaps it later.

### Read path

1. **Command → SearchParameterHelper → Query**. A user runs `/omni l p:Steve r:10`, the parameter parsers build a `Query` with a `FieldCondition(PLAYER_ID = steve-uuid)` and `FieldCondition(LOCATION.X BETWEEN ...)` etc.
2. **MongoRecordHandler.query** (`MongoRecordHandler.java:62`). If the `NO_CHAT` flag is set, appends an `Message EXISTS false` condition. Builds a `$match` document via `buildConditions`, which walks the `SearchCondition` tree and emits Mongo operators (`$in`, `$nin`, `$exists`, `$gte`, `$lte`, or plain `field: value` for `EQUALS`).
3. Builds a `$sort` on `CREATED` and a `$limit`. If grouping is enabled (default), adds a `$group` stage keyed on `Event + Player + Cause + Target + EntityType + day/month/year`, summing a `Count` field.
4. Runs `collection.aggregate(pipeline)` synchronously, iterates the cursor.
5. For each result:
   - `documentToDataWrapper(document)` recursively walks the `Document`: nested `Document` → nested `DataWrapper`, `Collection` → list with inner docs mapped to wrappers, everything else verbatim.
   - If grouped, hydrates a `DataAggregateEntry` with the calendar date from the `_id`; otherwise a plain `DataEntry`.
   - If the document has `PLAYER_ID`, looks up the `OfflinePlayer` by UUID and overwrites `CAUSE` on the wrapper with the resolved name (so display code can just read `CAUSE` and always get a string). A player name replaces a UUID here, which is a silent type-lossy mutation — the UUID is gone from the returned entry.
6. Completes the `CompletableFuture` and hands the list to the caller.

### Round-trip shape

An inbound `ItemStack` with components (a book) demonstrates the layering:

- `ItemStack` (`ConfigurationSerializable`) → `DataWrapper.ofConfig(stack)` → a `DataWrapper` with `CONFIG_CLASS = "ItemStack"`, `type = "WRITTEN_BOOK"`, `meta = DataWrapper{CONFIG_CLASS = "ItemMeta", title = "X", pages = [...], ...}`.
- Stored in Mongo as a nested `Document` — one nested document per wrapper level, each tagged with `ClassName`.
- Read back as a tree of `DataWrapper` with `CONFIG_CLASS` still set.
- Rollback calls `DataHelper.unwrapConfigSerializable(wrapper)` → recursively reconstructs the `ItemStack` via `ConfigurationSerialization.deserializeObject(configMap, ItemStack.class)`.

In 1.21+ the `components` sub-tree of `ItemStack` is a plain `Map<String, Object>`, *not* a `ConfigurationSerializable`. That Map goes through `DataWrapper.ofConfig`'s `Map` branch, which calls `setMap`, which creates a sub-wrapper *without* `CONFIG_CLASS`. On read-back, the naive `unwrapConfigSerializable` call on the nested wrapper returned null (no class tag), so the reconstituted stack had `components = null` and the rollback silently inserted an item with no meta. That bug lineage is the whole reason `DataHelper.dataWrapperToMap` / `unwrapValue` exist now — they look at `CONFIG_CLASS` presence and choose between `unwrapConfigSerializable` and raw `Map` unwrapping. See `DataHelper.java:104-125`.

## Mongo schema as currently shaped

Every event is a single document in the `DataEntry` collection. A player breaking a chest with stuff in it produces roughly:

```json
{
  "_id": ObjectId("..."),
  "Event": "break",
  "Created": ISODate("2026-04-23T..."),
  "Expires": ISODate("2026-05-21T..."),
  "Player": "2f58e6a8-...-...-...-0c1d3a8c",
  "Target": "CHEST",
  "Location": {
    "X": -123,
    "Y": 64,
    "Z": 512,
    "World": "7f9b8c2d-...-..."
  },
  "OriginalBlock": {
    "MaterialType": "CHEST",
    "BlockData": "minecraft:chest[facing=east,type=single,waterlogged=false]",
    "Inventory": {
      "0": {
        "ClassName": "ItemStack",
        "type": "DIAMOND_SWORD",
        "amount": 1,
        "meta": {
          "ClassName": "ItemMeta",
          "meta-type": "UNSPECIFIC",
          "display-name": "Excalibur",
          "enchants": { "DAMAGE_ALL": 5 }
        },
        "components": {
          "minecraft:custom_model_data": { "floats": [1.0] }
        }
      },
      "3": { "ClassName": "ItemStack", "type": "COBBLESTONE", "amount": 64 }
    }
  },
  "NewBlock": {
    "MaterialType": "AIR",
    "BlockData": "minecraft:air"
  }
}
```

Observations:

- The root doc is flat-ish: event metadata at top level, event-specific fields nested one level down. There's no discriminator other than `Event` — the reader has to know that `break` has `OriginalBlock`/`NewBlock`, `say` has `Message`/`Recipient`, `join` has `IpAddress`, etc.
- `Player` is a UUID string. `Cause` never appears on this document because the source was a player; a `natural` event like `decay` would have `Cause: "environment"` instead.
- World is a UUID string, not a name. Read code has to `Bukkit.getWorld(UUID.fromString(...))` to render a human-readable world.
- Nested `ClassName` is the Bukkit serialization alias, *not* a FQCN — safe to rename Java packages without breaking data, but also means the schema is coupled to whatever `ConfigurationSerialization.getAlias(…)` returns for the current Bukkit version.
- `components` is a raw nested map (no `ClassName`) because Paper's 1.21+ component data isn't `ConfigurationSerializable`. Already a footgun; see the bug above.
- `Expires` is a millisecond-precision `Date`, and Mongo's TTL index reaps on it.

Indexes (created in `MongoStorageHandler.connect` lines 73-77):

```
{ Location.X: 1, Location.Z: 1, Location.Y: 1, Created: -1 }
{ Created: -1, EventName: 1 }                 ← BROKEN: field is "Event", not "EventName"
{ Expires: 1 }  (TTL, expireAfterSeconds=0)
```

The compound `Created+EventName` index indexes a non-existent field, so it's pure overhead — it'll match zero documents on any realistic predicate and Mongo still has to maintain it on every write. That's a live bug. The `Location.X/Z/Y/Created` index is real but ordered weirdly — XZY rather than XYZ, and with `Created` at the end. That's fine for a pure location box query that also bounds time, but won't serve a player-scoped query. There is no `Player`-prefixed index anywhere, so `/omni l p:Alice` does a full collection scan filtered in memory by Mongo. Same for `Event`. The claim in the overview that there's "one index: TTL" undersells the mess — there are three indexes, one of which is dead.

## Pain points

1. **`components=null` bug lineage.** `DataWrapper.ofConfig` handles only three nested value shapes: another `ConfigurationSerializable` (recurse with `CONFIG_CLASS`), a `Collection`, or a `Map`. The `Map` branch goes through `setMap` (`DataWrapper.java:184`) which creates a raw sub-wrapper with **no** `CONFIG_CLASS`. `DataHelper.unwrapConfigSerializable` keys entirely off `CONFIG_CLASS`, so any naive call on a nested wrapper that was originally a plain `Map` returns `null` and the parent deserialization silently drops that field. Paper 1.21+ made `ItemStack.components` exactly that shape: a plain `Map<String, Object>`. Restoration rolled back items with `components = null`, which clobbers real item data. Fixed earlier today in `DataHelper.dataWrapperToMap` / `unwrapValue` by branching on `CONFIG_CLASS` presence — but the underlying model still conflates "tagged ConfigurationSerializable" and "plain nested Map" in the same wrapper type. A v2 schema with explicit discriminators would not have this class of bug.
2. **Dual serialization layers.** Every event is serialized twice: once into `DataWrapper` (a bespoke tree with its own rules for maps, collections, enums, arrays, `ConfigurationSerializable`, and primitives), and then a *second* time into a `Document` by `documentFromDataWrapper` (another tree walker that mostly mirrors the first but with subtly different behavior — e.g. special-cases `PLAYER_ID`, warns on enums inside a list only, ignores the `key.equals(PLAYER_ID.toString())` branch value because it just re-appends the same thing). The Mongo Java driver already knows how to walk a `POJO` or a `Map`; feeding it a `DataWrapper` that it then has to be manually translated to a `Document` is two hops where one would do. Every new field type has to be handled in both layers.
3. **No useful indexes.** `Player` is the single most-queried field in the plugin (every `p:Steve` lookup) and it has no index. `Event` is second (every `a:break` lookup) and the index that was *meant* to cover it is on a misnamed field (`EventName` vs the actual `Event`), so it's a no-op. Every production query is currently a collection scan filtered by `$match`, and with TTL retention set to 4w on a busy server this is tens of millions of documents. The only thing that works is the location-prefixed compound for bounded-box radius queries. Worse: the broken `EventName` index still costs one B-tree update per write, forever.
4. **Schema field name drift.** `DataKeys.EVENT_NAME = DataKey.of("Event")`. The MongoStorage index says `EventName`. Somewhere in the refactor history the key was renamed from `EventName` to `Event` and the index definition wasn't updated. Nothing catches this — Mongo will happily create an index on a field that doesn't exist in a single document. A test or a schema-validated collection would.
5. **DynamoDB backend is stub code.** `DynamoStorageHandler.connect` returns `false` (which should be treated as a connection failure but nothing actually checks the return value at the call site — `OmniCore.onEnable` ignores it). `withRegion("@TODO")` is a compile-time string that has never been a valid AWS region. `DynamoRecordHandler.write` builds `Item` objects then drops them on the floor. `query` returns `null`. There is no user who could be using this backend. It should be deleted, not modernized.
6. **`sanitizeUtf8` strips characters silently.** `DataWrapper.set` (`DataWrapper.java:425`) routes every string value through `sanitizeUtf8`, which does `input.replaceAll("[^\\x00-\\x7F\\xA0-\\uFFFF]", "")`. The comment says "Remove characters outside the valid UTF-8 range that MongoDB can't store." This is wrong on two counts: MongoDB stores full UTF-8 including emoji and astral-plane characters with no issue (BSON strings are UTF-8 by specification), and the regex actually excludes `\x80-\x9F` (C1 controls — probably fine) and everything above `\uFFFF` (the entire supplementary plane, i.e. emoji and non-BMP CJK). Any player with an emoji in their name or chat gets those characters silently stripped on save. This regex also can't handle high-surrogate / low-surrogate pairs as pairs — `replaceAll` with char classes operates on code units, so a valid surrogate pair would have both halves stripped independently. It's a kludge masking an earlier, unrelated bug.
7. **`DataWrapper` is a god object.** 500 lines, nine overloaded `get*` methods, recursive value-type handling in six places, static helper methods for byte/string/serializable coercion, private regex parsing of number strings in `sanitiseNumber`, and duplicate-safe `equals` over `data.entrySet()`. It's both a serialization DTO and a query-result container and a live-building model. Every listener touches it directly.
8. **`MongoDatabase` is a `static` field.** `MongoStorageHandler.java:25`. Reloading the plugin without restarting the server will not reset this, which may hide lifecycle bugs behind a half-closed client.
9. **`close()` does nothing.** Both Mongo and Dynamo `close()` are empty. The Mongo client is never told to shut down; the JVM shutdown cleans it up. On a `/reload` that's not ideal.
10. **`records()` returns the raw handler.** Every call site does `Omniscience.getStorageHandler().records().write(...)`. The `StorageHandler` could just expose `write` and `query` directly; the two-level accessor is pointless indirection.
11. **Type-unsafe accessors.** `DataWrapper.getInt` is `get(key).map(obj -> (Integer) obj)`. If the stored value is a `Long` (which Mongo returns for anything that fit in a 32-bit int but was written from a Java `long`) this `ClassCastException`s at runtime. The API suggests type safety and the implementation has none.
12. **`documentFromDataWrapper` special-cases `PLAYER_ID` uselessly.** Lines 192-196: `if (key.equals(PLAYER_ID.toString())) { document.append(PLAYER_ID.toString(), object); } else { document.append(key, object); }`. Both branches do the same thing. This is vestigial code from when `PLAYER_ID` was stored as a Mongo `ObjectId` or similar non-string type.
13. **`RecordHandler.query` throws `Exception`.** Not `IOException`, not a domain-specific error type, just raw `Exception`. Every call site has to either declare it or swallow it. `MongoRecordHandler.query` doesn't actually throw anything checked. This is signature noise.
14. **`InventoryTransaction` doesn't extend `LocationTransaction`.** Containers are always at a location. Every call site has to fish the location out of the `InventoryHolder` separately. Modeled as if inventories could be locationless, but they can't be in any event that's worth logging.
15. **`documentToDataWrapper` → player name clobbers UUID.** `MongoRecordHandler.java:148`: the UUID is replaced with a player name on `CAUSE`. Downstream code can no longer distinguish "player whose UUID resolved to name X" from "an unsourced event with cause string X". Display code treats both the same.
16. **`Location.WORLD` stored as UUID.** If a world is removed or renamed, every historic entry referencing it produces `Bukkit.getWorld(uuid) == null` in `DataHelper.getLocationFromDataWrapper`, which silently fails the `isPresent` check and the entry can't be rolled back. Storing the world name *and* UUID would survive either kind of change.
17. **No schema migration.** Rename a `DataKey`, ship it, and every historic entry instantly becomes unreadable for that field. There is no versioning, no migration script, no document-level schema version tag.

## Modernization hotspots

1. **Replace `DataWrapper` with typed event records.** Define one Java record per event:
   ```java
   sealed interface OmniEvent permits BreakEvent, PlaceEvent, ChatEvent, ... {
       Instant createdAt();
       Source source();
   }
   record BreakEvent(Instant createdAt, Source source, BlockLocation at, BlockSnapshot original, ...) implements OmniEvent {}
   ```
   Bind them to Mongo via the POJO codec (`PojoCodecProvider`) with `@BsonProperty` for the on-disk names. Every listener now takes a typed builder; every query returns typed results; the `DataWrapper` tree-walker disappears. No more `components=null` class of bug because there is a typed slot per field.
2. **Drop the Mongo `Document` translation layer.** Once records exist, the driver's codec registry does the BSON round-trip. `documentFromDataWrapper` / `documentToDataWrapper` / `ensureCorrectDataTypes` all delete. That's ~200 lines gone and one whole class of drift bugs gone with it.
3. **Proper indexes.** At minimum:
   - `{ Player: 1, Created: -1 }` — covers `p:X` + time range, which is half of all queries
   - `{ Event: 1, Created: -1 }` — covers `a:break` + time range
   - `{ Location.World: 1, Location.X: 1, Location.Z: 1, Location.Y: 1, Created: -1 }` — radius + time, with world as the leading field (absent today)
   - Keep the TTL `{ Expires: 1 }`
   - Delete the broken `{ Created: -1, EventName: 1 }` index
   Consider a partial index for `{ Event: 1, Created: -1 } where Message exists` for chat-specific lookups. Measure before committing — a write-heavy collection with too many indexes is worse than none.
4. **Delete DynamoDB.** No one is using it, `connect()` returns `false`, `write()` drops data, `query()` returns `null`. The `StorageHandler` abstraction is still worth keeping (it's one interface and two methods), but ship one working implementation rather than one working and one broken. If a NoSQL-alternative backend is ever genuinely wanted, start from the POJO-codec-backed Mongo implementation and add a second concrete type then.
5. **Kill `sanitizeUtf8`.** Remove the regex. Mongo stores UTF-8 natively. If whatever original bug it was masking resurfaces, fix the root cause (likely a corrupted `String` coming in from a specific Paper API) rather than silently mangling every string in the plugin. Player names with emojis, Korean/Japanese/Chinese chat, mathematical symbols — all currently get trimmed.
6. **Async writes, real thread pool.** `MongoRecordHandler.write` is called from the scheduler's async thread pool, which is *Bukkit's* thread pool. A Mongo bulkWrite that stalls — network blip, slow election — starves that pool. Give storage its own `ExecutorService` with bounded queue and visible metrics. Back-pressure when Mongo is slow rather than bulking up `EntryQueue` until OOM.
7. **Batch write metrics.** Today an `EntryQueueRunner` fires every 20 ticks (1s), drains everything, and issues one bulkWrite. There is no observability of queue depth, batch size, write latency, write errors. Instrument all four. Expose via a `/omni stats` command or a metrics library if Prometheus is on the table.
8. **Schema versioning on each document.** Add `_v: 1` on every document at write. When the shape changes, the reader branches on `_v`. The entire migration story costs one int and one switch statement.
9. **Replace `Object` source with a tagged enum.** `OEntry.save` has a ladder of `instanceof` checks to figure out what "source" means — Player, Entity, Plugin, ConsoleCommandSender, RemoteConsoleCommandSender, BlockCommandSender. A sealed `Source` interface with `Player(UUID)`, `NonPlayerEntity(EntityType, UUID)`, `Plugin(String name)`, `Console`, `CommandBlock(...)` cases would make this exhaustive and typed.
10. **Retire the `Object` in `DataWrapper.get(DataKey)`.** Typed query reads via the POJO model would replace every `Optional<Object>` with something compile-checked.
11. **Collapse `Cause`/`Player` onto one field.** Today `Player` holds a UUID for player-sourced events, `Cause` holds a string for everything else, and on read `Cause` gets overwritten with the player's name when `Player` is set. One `Source` sub-document with a discriminator would be simpler and queryable without union-type magic.
12. **Store both world UUID and name.** Belt and suspenders. `{ World: { uuid: "...", name: "world_nether" } }` survives rename in one direction and delete-then-recreate-with-same-name in the other.

## What v2 should keep

- **The `StorageHandler` / `RecordHandler` abstraction.** One interface, batch write, async query. The shape is correct. Modernize the implementation, keep the contract.
- **TTL-based retention.** Mongo's native TTL index on `Expires` is the right tool. Zero maintenance, predictable eviction, no out-of-band cleanup job to break. Keep it, maybe make the retention policy configurable per event type (chat logs retained longer than redstone triggers, say).
- **Per-event discriminator at the top level.** The `Event` field is the single most-important pivot for querying. Keep a string discriminator (even with typed records behind it), because that's what indexes can key on and what operators will grep for.
- **Dotted-path addressing for queries.** `Location.X`, `OriginalBlock.Inventory.0` — this idea is fine and maps directly to Mongo's field-path syntax. It can survive the move away from `DataWrapper` because it's just a BSON convention. Keep the `DataKey` concept (or something equivalent like a typed path DSL) as the query-side address-of.
- **`Transaction` / `LocationTransaction` as listener-side inputs.** The idea that a listener hands in a "before → after" pair plus a location is ergonomic and reusable. Keep it; just have the OEntry/builder map a `LocationTransaction<BlockState>` into a typed `BreakEvent` record directly rather than into a `DataWrapper` free-for-all.
- **Index-friendly top-level fields.** `Player`, `Event`, `Created`, `Location.*` all being shallow top-level (or one-deep) fields is deliberate and correct for index support. Don't let future serialization abstractions bury them.
- **Async queue drainer.** `EntryQueue` + `EntryQueueRunner` keep listeners cheap and smooth out burst writes. The pattern is good; only the execution context (Bukkit async pool) and observability (none) need fixing.

# Omniscience Dissection — 10: Modernization Hotspots (Synthesis)

This is the rollup. Sections 01–09 are the evidence; this doc is the ranking. It groups the patterns that most deserve redesign in a v2 rewrite by tier and theme, with pointers back to where each one is documented in detail.

## Guiding principles for v2

1. **Typed everything.** Strings are for user input and wire protocols. In-memory, use records + sealed interfaces.
2. **One serialization layer.** Bukkit type → Mongo document. Not Bukkit → DataWrapper → BSON Document.
3. **Adventure, not Bungee.** Paper's native component API for every rendered message.
4. **Dependency injection, not ambient singletons.** A registry passed into each subsystem at construction.
5. **API/impl boundary is sacred.** The public API module holds interfaces + records + constants. Nothing else.
6. **Async-first threading.** Virtual threads and `CompletableFuture` chains. Keep the main thread off the write path entirely.
7. **Indexes match queries.** Every common query shape has a compound index.
8. **Dead code has no right to exist in a commercial product.** Delete it.
9. **A feature without a test is a future bug.** Ship with coverage on the hot paths.

---

## Tier 1 — Foundational architectural changes

These are the big ones. Do them first because the rest depend on them.

### 1.1 Typed event model + Mongo POJO codec → replaces DataWrapper *and* the OEntry builder monolith

*Covered in:* [02-data-and-storage.md], [03-events-and-entries.md]

**Current:** Every event flows through a string-keyed tree (`DataWrapper`) that is then flattened into a BSON `Document` by `MongoRecordHandler.documentFromDataWrapper`. Two serialization layers. Source of the `components=null` bug we fixed this session. OEntry is a ~700-line god class holding every typed builder method (`brokeBlock`, `placedBlock`, `said`, `ranCommand`, etc.).

**Target:**

```java
public sealed interface EventRecord permits
        BlockBreakRecord, BlockPlaceRecord, ChatRecord,
        CommandRecord, ContainerTransactionRecord, /* ... */ {
    UUID id();
    Instant occurred();
    Origin origin();           // sealed: Player | World | Plugin | WorldEdit | Fawe
    Location location();
}

public record BlockBreakRecord(
        UUID id, Instant occurred, Origin origin, Location location,
        BlockSnapshot original, Optional<BlockSnapshot> replacement,
        Optional<InventorySnapshot> containerContents
) implements EventRecord, Actionable { ... }
```

Mongo POJO codec handles serialization automatically via `@BsonProperty`. No more manual `documentFromDataWrapper`. Adding a new event type = write a record + listener. No touching storage code. OEntry retires entirely — replaced by a `Recorder` service that takes a typed record and queues it.

**Why first:** Every other simplification depends on having typed events. Query DSL, rollback dispatch, display handlers all become cleaner once events aren't a stringly-typed bag.

### 1.2 Sealed interface hierarchies for query predicates, parameters, flags

*Covered in:* [04-query-dsl.md]

**Current:** `FieldCondition` + `MatchRule` enum + `SearchConditionGroup` for AND/OR. Every parameter implementation does its own ad-hoc value parsing and builds `FieldCondition`s via a switch-y `buildForQuery` method. Flag handling is a `List<FlagHandler>` iterated linearly.

**Target:**

```java
public sealed interface QueryPredicate permits
        FieldEquals, FieldIn, FieldRange, FieldExists, Not, And, Or { }

public sealed interface QueryParam permits
        PlayerParam, RadiusParam, TimeParam, EventParam, TargetParam, /* ... */ {
    QueryPredicate toPredicate(QueryContext ctx);
}
```

`EnumSet<Flag>` instead of `List<FlagHandler>`. Cloud's annotation-based parameter parsing gets wired to Brigadier suggestions server-side — no more manual `SearchParameterHelper`.

### 1.3 Adventure everywhere, kill `net.md_5.bungee`

*Covered in:* [05-commands-and-display.md]

**Current:** `SearchCallback.buildComponent`, all five display handlers, `Formatter`, and `OEntry.said` all use `net.md_5.bungee.api.chat.TextComponent` / `ComponentBuilder` / `HoverEvent` / `ClickEvent`. Legacy Spigot, deprecated on Paper.

**Target:** Every rendered message is a Kyori `Component`. MiniMessage strings for static templates (`<aqua>[WE]</aqua> <yellow><source></yellow> broke <green><target></green>`) with placeholders resolved from the `EventRecord`. `SearchResultRenderer` interface takes a record and returns a `Component`, with per-event overrides pluggable the way `DisplayHandler` works today.

**Side benefits:** i18n becomes trivial (swap MiniMessage bundle), customization becomes trivial (admins edit templates in config), and the chat layer drops a whole deprecated dependency.

### 1.4 API/impl boundary cleanup

*Covered in:* [00-overview.md], [08-api-surface.md]

**Current mess:**

- Concrete `Parameter*` / `Flag*` / `BlockEntry` / `ContainerEntry` / `EntityEntry` classes live in Core under `net.medievalrp.omniscience.api.*` packages. Consumers shading only the API module don't get them but the package layout lies.
- `OmniApi` (singleton) and `Omniscience` (the plugin class) both expose ~25 static delegates to `OmniCore`. External consumers might use either. Internal code uses `Omniscience`.
- `IOmniscience` is a 30-method grab-bag covering both "register a handler" (consumer-facing) and "get storage handler" (implementation-facing) on the same interface.

**Target:**

- `OmniscienceApi` module → public interfaces, records, constants, MiniMessage placeholder keys. Only things external plugins should touch.
- `omniscience-core` → everything that implements the interfaces. Package `net.medievalrp.omniscience.core.*`.
- Remove `OmniApi`. External plugins get the api via Bukkit's `ServicesManager`: `Bukkit.getServicesManager().load(OmniscienceApi.class)`.
- Split `IOmniscience` into `OmniscienceApi` (external contract, narrow) and an internal `OmniscienceContext` or just DI'd dependencies.
- Publish `OmniscienceApi` as a real Maven artifact with semver.

### 1.5 Kill the five singletons, use a typed Registry

*Covered in:* [01-core-lifecycle.md]

**Current:** `Omniscience.INSTANCE` + `Omniscience.PLUGIN_INSTANCE` + `OmniConfig.INSTANCE` + `OmniApi`'s internal reference + `OmniEventRegistrar.INSTANCE`. Five singletons reaching across the codebase.

**Target:**

```java
public record OmniscienceContext(
        OmniscienceConfig config,
        RecordStore storage,
        EventRegistry events,
        ParameterRegistry parameters,
        FlagRegistry flags,
        DisplayRegistry displays,
        WorldEditBackend worldEdit,
        @Nullable AiAssistant ai
) { }
```

Built once in `onEnable`. Passed to every listener, command, and handler at construction. Tests construct with mocks.

---

## Tier 2 — Functional wins

### 2.1 Actual Mongo indexes for common queries

*Covered in:* [02-data-and-storage.md]

**Current:** One TTL index on `Expires`. Everything else is a collection scan — every `/omni search p:name r:30 t:1h` scans every document in the collection, filters in-driver. For a big server's multi-month log, this is tens of millions of docs per query.

**Target:** Compound indexes on common shapes:

```
{ Player: 1, Created: -1 }
{ Event: 1, Created: -1 }
{ "Location.World": 1, "Location.X": 1, "Location.Z": 1, Created: -1 }
{ Recipient: 1, Created: -1 }
```

Define them in an `IndexSetup` class, create at startup idempotently. Also fix the live bug where one index targets `EventName` but `DataKeys.EVENT_NAME` is `"Event"` — the index is never used. (See 02-data-and-storage.md for the details.)

### 2.2 Async rollback + wire up the batched FAWE path

*Covered in:* [06-rollback.md], [07-worldedit-integration.md]

**Current:** `runApplier` iterates entries and calls `actionable.rollback()` on the main thread, which calls `location.getBlock().setBlockData(...)`. For 10k blocks, this freezes the server tick for seconds. The existing `FAWERollbackHandler.batchRollback` is wired for exactly this use case — and never called.

**Target:**

- Small rollbacks (< 500 entries): current sync path, fine.
- Large rollbacks: route through `FaweBatchRollback` (rewrite, replacing `FAWERollbackHandler`) that builds a single FAWE EditSession, calls `setBlock` on it for every entry, commits async. Reports progress back to the player via action bar.
- Both paths produce `ActionResult`s; the dispatcher is the only thing that changes.

### 2.3 Typed config via Configurate or record-based loader

*Covered in:* [01-core-lifecycle.md], [09-utilities-and-gaps.md]

**Current:** `OmniConfig.setup(FileConfiguration)` eagerly parses ~40 instance fields via stringly-typed `getString`/`getBoolean`/`getInt` calls. Misspellings silently default. No validation.

**Target:**

```java
@ConfigSerializable
public record OmniscienceConfig(
    DatabaseConfig database,
    StorageConfig storage,
    Defaults defaults,
    Limits limits,
    Map<String, EventConfig> events,
    AiConfig ai,
    IntegrationConfig integration,
    DisplayConfig display
) { }
```

Configurate (Sponge's config library, already used for HOCON by Reserv/Cauldron/WhisperNet/VestaPersona) handles load, validation, comments, defaults. Matches the stack the other MedievalRP plugins already use.

### 2.4 One event pipeline abstraction, not 30 listeners

*Covered in:* [03-events-and-entries.md]

**Current:** ~30 listener classes each do `extract fields from event + call OEntry.create().source(p).<builder>.save()` with minor variations. Every new event = a new listener class.

**Target:**

```java
public interface EventExtractor<E extends Event, R extends EventRecord> {
    Class<E> eventType();
    EventPriority priority();
    Optional<R> extract(E event);
}

// Registered once:
extractors.register(new BlockBreakExtractor());
extractors.register(new BlockPlaceExtractor());
// ...
// Auto-wired: for each extractor, register a Bukkit listener that calls extract() and pipes the record to the recorder.
```

Half the listener LOC vanishes. Adding an event = write one extractor class. All go through one `EventRecorder.record(EventRecord)` entry point — logging, queueing, backpressure, flushing on disable all centralized.

### 2.5 Cloud annotation-based commands, drop custom parameter parser

*Covered in:* [04-query-dsl.md], [05-commands-and-display.md]

**Current:** `OmniCommands.register` manually builds every subcommand via the Cloud builder API. `SearchParameterHelper.suggestParameterCompletion` reimplements tab completion client-side-reimplemented even though Cloud exposes Brigadier suggestions natively.

**Target:** Cloud's `@Command`/`@Argument` annotations on handler methods. Each parameter type implements `ArgumentParser<CommandSourceStack, ?>` so Cloud handles parsing + Brigadier suggestions natively. Half as much code, and client-side tab completion becomes free and correct.

### 2.6 Flush `EntryQueue` on disable

*Covered in:* [01-core-lifecycle.md], [03-events-and-entries.md]

**Current:** `onDisable` is empty. Server stop kills pending writes.

**Target:** `onDisable` drains the queue synchronously with a timeout (say 5 seconds), logs a warning per dropped entry if the timeout expires. Zero-code-change for normal shutdowns; prevents silent data loss on slow writes.

---

## Tier 3 — Polish and ship-readiness

*Covered in:* [09-utilities-and-gaps.md]

### 3.1 Delete dead code

- `FAWERollbackHandler` — never called, superseded by v2's batched rollback above.
- `WorldEditHandler` interface — stored, never invoked.
- `PermissionListener` — exists, registered nowhere.
- `OmniCore.onCraftBookStatusChange` — `//TODO turn off craft book related events` with an empty body.
- `OmniCore.onEnable` integration `if`-blocks (lines 79-87) — empty branches.
- `CommandResult` / `UseResult` — leftovers from the pre-Cloud command system. Now unreferenced.
- `registerEventWrapperClasses` — only wires 7 event names, the rest are handled via the `OmniEventRegistrar` past-tense table. Redundant registration.

### 3.2 Fix known bugs flagged by the dissection

- `EventInventoryListener.onInventoryDrag` (line 84): builds an `OEntry` and never calls `.save()`. Events from inventory drags are silently dropped. *(Found by agent in 03 + 09.)*
- Index field-name mismatch: one index targets `EventName`, `DataKeys.EVENT_NAME` is `"Event"`. Index never used. *(02.)*
- `FlagWorldEditSel.isIgnoredDefault` bug at line 68. *(07.)*
- `DataWrapper.sanitizeUtf8` strips arbitrary characters from stored strings. Opaque, undocumented, and wrong behavior in the age of full UTF-8 Mongo. *(02.)*
- `plugin.yml` website points to `itdontmatta/Omniscience` (personal fork) instead of `medievalrp-net/Omniscience`. *(09.)*

### 3.3 Real test coverage

**Current:** `OmniCoreTest` has its setup commented out and a single test with no assertions. No tests anywhere else.

**Target:** A test dir with, at minimum:

- Unit tests for `DateUtil.parseTimeString` — edge cases (bad input, `4w2d`, overflow).
- `DataHelper.unwrapConfigSerializable` round-trip tests with representative BSON documents (block + place + container entry).
- `QueryBuilder` tests with a fake record store.
- `FaweBatchLogger.processSet` test with a fake `IChunkSet` / `IChunkGet`.
- A Mockito-driven test for `EventBreakExtractor` that verifies a crafted `BlockBreakEvent` produces the expected record.

### 3.4 Proper FAWE compile-time dep

*Covered in:* [09-utilities-and-gaps.md]

**Current:** `compileOnly files("$projectDir/libs/FastAsyncWorldEdit.jar")` + the jar is gitignored. Clean clone = broken build. New contributors hit a wall.

**Target:** `compileOnly "com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.15.1"` with the IntellectualSites maven repo added. Delete `libs/`. Document in the README.

### 3.5 Adventure MiniMessage for all messages → i18n-ready

Covered in 1.3 but worth calling out as shipping polish. Users see error messages all the time (`"You do not have any search results"`, `"AI features are not enabled"`, etc.). Moving these to a `messages.conf` MiniMessage bundle gets translation for free and lets admins customize copy without recompiling.

### 3.6 Semver + `@ApiStatus` on the API module

Every public class in `OmniscienceApi` gets `@ApiStatus.Stable` / `.Experimental` / `.Internal`. Version bumps are semver. Breaking changes require a major bump + deprecation runway. Necessary for any plugin that wants consumer plugins to integrate.

---

## What v2 should keep from v1

### Working well (don't rewrite)

- **MongoDB as primary backend.** The choice is right. Just needs indexes + POJO codec + drop Dynamo.
- **TTL-based retention.** Passive, zero-maintenance, correct. Keep exactly as-is.
- **Event-name-keyed registry for extensibility.** External plugins register custom events, Omniscience stores them. Good pattern. Just needs typed redesign under the hood.
- **Cloud command framework.** Just migrated this session. Keep. Also take it further via annotations (2.5).
- **Origin tagging (`[WE]` / `[FAWE]`).** Added this session. Keep. Generalize into a sealed `Origin` type (1.1).
- **Recipient tracking on chat.** Added this session. Useful. Keep.
- **AI query assistant.** Novel differentiator. Keep, polish (virtual threads for HTTP, clearer error messages).
- **FAWE `IBatchProcessor` path for fast-placement.** Added this session. Keep. Extend with batched rollback (2.2).
- **Per-event enable/disable in config.** Operators will want this. Keep.
- **The `/omni undo` concept** (user's last rollback is reversible). Good UX. Fix the implementation divergence (6.x) but keep the behavior.

### Worth preserving but simplifying

- The storage handler abstraction. Two backends (Mongo, Dynamo) is overkill; keep the interface, drop Dynamo.
- Separate API module. Keep the split but tighten the boundary.
- Pluggable display handlers. Keep the pattern; migrate to Adventure.
- `Actionable` interface for rollback. Keep the interface shape; extend to more record types.

---

## Execution sequencing for v2

If building this from scratch (clean room or otherwise):

**Phase 1 — skeleton (week 1):**
- Paper plugin bootstrap + Configurate config + Mongo connection + typed EventRecord sealed hierarchy (just two types to start: BlockBreakRecord, BlockPlaceRecord) + EventExtractor pipeline for those two events + POJO codec writes.

**Phase 2 — query (week 2):**
- QueryPredicate sealed hierarchy + QueryBuilder + indexes + basic `/omni search` via Cloud annotations + Adventure-rendered results.

**Phase 3 — rollback (week 3):**
- `Actionable` on BlockBreak/Place records + sync rollback path for small ops + `/omni rollback` command + `/omni undo`.

**Phase 4 — remaining events (week 4):**
- All the other extractors (container, chat, entity, item) one by one. Each adds a record type + an extractor + possibly a query parameter.

**Phase 5 — WorldEdit + FAWE (week 5):**
- Port this session's work (WorldEditLogger + FaweHook + FaweBatchLogger) into the new architecture. Origin tagging as part of the sealed `Origin` type.

**Phase 6 — batched rollback + polish (week 6):**
- FaweBatchRollback for large ops. Progress reporting. Dry-run mode with particles.

**Phase 7 — AI + polish + tests + docs (week 7):**
- Port `AiHandler`, write tests for hot paths, MiniMessage bundle, semver tagging on API, real README.

**Phase 8 — migration tooling (week 8):**
- Read old Omniscience Mongo collection, translate to new record format, write into new collection. So existing v1 customers can upgrade without losing history.

Seven to eight focused weeks of solo-dev-with-AI work, producing a cleaner codebase that's both commercially viable and forkable-by-us-later.

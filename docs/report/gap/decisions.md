# Spyglass — design decisions

Recorded decisions made during the Phase 0-5 gap-fix work. Supplements
[`gap.md`](gap.md) and [`plan/plan.md`](plan/plan.md).

---

## 5.3 — Third-party plugins cannot register custom event records

**Status:** closed, seal preserved.

### Context

v1's public API included `OEntry.create().source(p).custom(eventName, wrapperData)` — any downstream plugin could mint event names on the fly and hand a `DataWrapper` full of arbitrary fields to v1's storage. The records rode the same collection as first-party events and were queryable through the same parameter handlers.

v2's [`EventRecord`](../../../api/src/main/java/net/medievalrp/spyglass/api/event/EventRecord.java) is a sealed interface. Downstream plugins that depend on `spyglass-api` cannot add a new `permits` entry — sealed types are a closed set. `SpyglassApi.record(EventRecord)` only accepts the 17 first-party records.

The gap report flagged this as a lost extension point. Phase 5.3 asked the question: keep the seal, or add an escape hatch?

### Options considered

**(a) Keep the seal.** Document that third-party custom events are out of scope. Plugins that want audit logging of their own events get their own Mongo collection and their own display path.

**(b) Add an escape hatch.** Introduce `CustomEventRecord(..., String customEventName, Map<String, Object> payload)` as another permit, so downstream plugins can construct one and hand it to `SpyglassApi.record()`.

### Decision — (a), preserve the seal.

Reasons:

1. **No concrete sister-plugin demand.** None of Reserv, Cauldron, VestaPersona, or WhisperNet were writing v1 custom events in the commits I reviewed. The extension point was there in v1 but wasn't actually in use. Keeping the seal costs nothing operationally.

2. **The seal is load-bearing for the polymorphic codec.** [`EventRecordCodec`](../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/storage/EventRecordCodec.java) dispatches on a precomputed simple-name map built from `EventCatalog.recordClasses()` at class-load. Adding `CustomEventRecord` would mean the codec has to handle an arbitrary payload map — either falling back to a BSON dump (which loses schema) or to `Map<String, Object>` (which loses type safety on queries). Both weaken the "typed events all the way down" property that was the v1 → v2 Tier-1 win.

3. **Query predicates are field-path keyed.** v2's param handlers translate to BSON paths like `target`, `source.playerId`, `item.lore`. A `Map<String, Object>` payload has no schema, so custom fields can't be indexed and can't be targeted by typed params — operators would have to drop into raw Mongo query strings for any field not in the base context. That's a bad UX, and it leaks BSON into the command layer.

4. **If a sister plugin needs audit logging, its own collection is cleaner.** The plugin owns its schema, picks its own indexes, writes its own query command. It loses Spyglass's timeline alongside its own records, but that's a genuinely separate concern — a plugin's internal audit log doesn't have to share rendering or rollback with v1's world-events log.

### If this ever needs revisiting

The signal is: a sister plugin files a concrete ask ("we want to write X event, queryable by field Y, rendered alongside omni2 search results"). At that point, evaluate whether the ask fits an existing record type (preferred) or needs an escape hatch. Until that ask arrives, the seal stays.

---

## 5.2 — `Source` sealed-interface refactor deferred

**Status:** deferred to a post-1.1 release, not Phase 5 scope.

### Context

[`Source`](../../../api/src/main/java/net/medievalrp/spyglass/api/event/Source.java) today is a flat record with 8 fields, most of which are null on any given instance (a player source has `playerId` / `playerName` populated; `entityId` / `entityType` / `pluginName` / `commandBlockLocation` / `description` are all null). The type screams for the same sealed treatment `Origin` didn't need because of its simpler shape:

```java
public sealed interface Source permits
    Source.PlayerSource, Source.EntitySource, Source.PluginSource,
    Source.ConsoleSource, Source.CommandBlockSource, Source.EnvironmentSource {
    String displayName();
    record PlayerSource(UUID id, String name) implements Source { ... }
    record EntitySource(UUID id, String type) implements Source { ... }
    // ...
}
```

Gap-report §10 (listener 5.2) has this as a follow-up; the plan file scoped it P3 / L effort.

### Decision — defer.

Reasons:

1. **Wide blast radius.** Every record type has a `Source source` field. Every extractor constructs one via `Source.player(...)` / `Source.entity(...)` / etc. Changing `Source` from a flat record to a sealed interface with 6 permits means every construction site needs rewriting, every `source.playerId()` / `source.entityType()` accessor becomes a `switch (source)` pattern match, and every test fixture needs updating. Rough count: ~25 listener classes, ~5 service classes, ~15 test files touch `Source`.

2. **The Mongo schema changes.** POJO-codec encoding of a sealed interface requires a discriminator — same pattern as `EventRecordCodec` and `RollbackEffectCodec`. That means a new `SourceCodec` + a second discriminator field per record (`source._class`). Existing records read without the discriminator via fallback logic, but that's another compatibility path to get wrong.

3. **Operational payoff is small.** Storage waste is the 7 null fields per document. At ~400 bytes per record and 384k v1-scale records, that's ~60MB of null padding — a rounding error against the containing fields (block snapshots, item stacks). Developer-experience payoff is larger but non-urgent: `source.playerId()` returning null for an entity source is a known convention in the codebase and hasn't caused bugs in Phase 0-5.

4. **Phase 5's budget is better spent on correctness wins.** The 5.1 per-type fan-out fix was a concrete performance win. 5.4 (WE presence hardening), 5.5 (page TTL docs), 4.7 (CraftBook restoration) were all operator-facing restorations. A `Source` refactor is churn with no user-visible impact; the gain is stylistic.

### Reconsideration criteria

- A new sister plugin needs to dispatch on source kind and finds the null-checking awkward.
- Coverage metrics show the null-check paths in every `switch` against `Source` are uncovered by tests and flag as risky.
- A future `RollbackEngine` effect wants to attribute to a source kind other than player, and the flat-record shape makes the plumbing ugly.

Until one of those shows up, `Source` stays flat. The refactor is known-available if we ever decide to do it.

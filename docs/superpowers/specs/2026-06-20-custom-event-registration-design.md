# Custom event registration API (`registerEvent` + `CustomRecord`)

**Date:** 2026-06-20
**Status:** Approved — ready for implementation

## Problem

Third-party plugins (e.g. the voicechat integration) want to log their own event
types and search them by name (`a:voice`). Today `EventCatalog` is a closed,
hardcoded map: a record with an unregistered `event` name is written but silently
dropped on read (`recordClassOf(name)` → null → row skipped), and `a:<name>` is
rejected by `EventParam` because the name isn't in `enabledEvents`. There is no
public registration hook (Omniscience, which Spyglass's API was modelled on, had
`OmniApi.registerEvent(name, verb)`).

## Goal

A public API so an integrating plugin can register a custom event name and log
freeform key/value data against it, fully searchable and displayable — mirroring
Omni's ergonomics, adapted to Spyglass's typed, sealed record model.

## Key decisions (resolved in brainstorming)

1. **Generic record, not an alias.** Add a new `CustomRecord` type to the sealed
   `EventRecord` hierarchy rather than aliasing an existing one.
2. **Strings-only bag, reusing `extensions`.** The key/value bag is
   `Map<String,String>` carried in the existing `extensions` component. Integrators
   stringify (as the voice example already does). This means **no new storage
   columns** in any backend — `extensions` already round-trips through Mongo,
   ClickHouse, and SQLite and is queryable as `extensions.<key>`.
3. **Reuse `target` + `message`.** `CustomRecord` exposes `target` (summary string)
   and `message` (primary freeform text, e.g. a transcript) — both already existing
   columns — so `t:` and `m:` searches work with no new plumbing.
4. **Recipients live in the bag**, not a first-class field (keeps the type generic).
5. **Plain `of(...)` factory**, consistent with every other record (no fluent
   `OEntry`-style builder).
6. **Enabled by default, no config required** (Omni-style); an `events.<name>`
   config entry, if present, overrides (lets operators disable / rename the verb).

## Data model — `CustomRecord` (spyglass-api)

```java
public record CustomRecord(
        UUID id, String event, Instant occurred, Instant expiresAt,
        Origin origin, Source source, BlockLocation location, String server,
        String target, String message,
        Map<String, String> extensions) implements EventRecord {

    public static CustomRecord of(RecordContext ctx, String eventName,
                                  String target, String message, Map<String,String> data);
}
```

Added to the `EventRecord permits` list. `of(...)` merges `data` into the context's
extensions bag. Not `Rollbackable` (custom events have no inverse effect).

## Runtime event registry (spyglass-api)

`EventCatalog` gains a thread-safe runtime layer (a `ConcurrentHashMap` alongside the
static map):

- `recordClassOf(name)` → static mapping, else a registered custom name →
  `CustomRecord.class`, else null.
- `eventNames()` → static names ∪ registered names.
- New: `register(String name, String pastTense)`, `isRegistered(String name)`,
  `pastTenseOf(String name)` (registry verb, for the renderer fallback).

Registration is idempotent and case-insensitive (matching `recordClassOf`).

## Public API (spyglass-api `SpyglassApi`)

```java
void registerEvent(String name, String pastTense);  // name -> CustomRecord, enabled
boolean isEventRegistered(String name);
```

`SpyglassApiImpl.registerEvent` registers in `EventCatalog`, marks the name enabled
in a **live** enabled-events set (see below), and stores the verb for rendering.

## Wiring (spyglass-plugin)

- **enabledEvents becomes live.** Today it's an immutable snapshot built at startup
  and passed to `EventParam` + `SpyglassApiImpl`. Change to a shared mutable
  (concurrent) set so `registerEvent` adds names at runtime and `EventParam` /
  `enabledEvents()` see them. `EventParam.parse` accepts a name that is in the live
  set (built-ins from config + registered customs).
- **Verb fallback.** `config.pastTense(name)` falls back to
  `EventCatalog.pastTenseOf(name)` when the name has no `events.<name>` config entry.
- **Rendering.** `ResultRenderer` handles `CustomRecord` in the exhaustive switches
  (`targetOf` → `target`, `quantityOf` → 0); the message renders inline and the
  bag shows in the hover (the hover already renders `extensions`). Verb comes from
  `config.pastTense` (→ registry fallback).

## Storage parity (spyglass-core) — no new columns

Each backend maps the new `CustomRecord` *class* onto existing columns
(`target`, `message`, `extensions`):

- **Mongo** `EventRecordCodec`: encode/decode `CustomRecord` (fields:
  target, message, extensions). Registered names decode via
  `recordClassOf` → `CustomRecord`.
- **ClickHouse** `ClickHouseRecordStore`: `writeRow` writes message + extensions
  (target is already common); `decodeRow` adds a `CustomRecord` branch.
- **SQLite** `SqliteRecordStore`: same — write message + extensions, decode branch.
- **`PredicateEvaluator`**: `CustomRecord` resolves `extensions.<key>` (already the
  generic path) and the common fields; no item/snapshot paths.

## Sealed-switch fan-out

Adding a sealed type makes the compiler flag every exhaustive `switch (EventRecord)`.
Expected touch points: `ResultRenderer.targetOf` / `quantityOf`, the Velocity
`ProxyResultRenderer`, `EventRecordCodec`, ClickHouse + SQLite `writeRow`/`decodeRow`,
and any `PredicateEvaluator` switch. Mechanical, but must all be handled for the
build to compile.

## Velocity

`ProxyResultRenderer` (read-only proxy) gets a `CustomRecord` rendering branch so
cross-server `/sgv` search displays custom events too.

## Integration shape (what voicechat ends up writing)

```java
if (!api.isEventRegistered("voice")) api.registerEvent("voice", "spoke");
RecordContext ctx = RecordContext.fresh(occurred, expiresAt, origin, source, loc, server);
api.record(CustomRecord.of(ctx, "voice", summary, transcript, voiceDataMap));
```

## Migration of the `voice` quick-fix

The interim `voice → ChatRecord` entry in `EventCatalog` and the `events.voice`
default in `config.conf` are removed once this lands: `voice` becomes a registered
`CustomRecord` event instead. Already-written `voice` rows were stored as the
`ChatRecord` shape (message + recipients + extensions); since `CustomRecord` reads
the same `message`/`extensions` columns, they still decode and remain searchable
(the `recipients` column is simply not surfaced on a `CustomRecord` — acceptable;
it was a stop-gap). This is called out so the cutover is deliberate, not silent.

## Testing

- **api:** `CustomRecord.of` merges data into extensions; `EventCatalog.register` /
  `isRegistered` / `recordClassOf` / `pastTenseOf` (incl. case-insensitivity and
  idempotency).
- **core (Docker-gated ITs):** round-trip a `CustomRecord` through Mongo,
  ClickHouse, and SQLite — `a:<name>`, `m:` on message, and `extensions.<key>`
  all match; `querySummary` returns it with the bag intact.
- **plugin:** `EventParam` accepts a registered custom name and rejects an
  unregistered one; `SpyglassApiImpl.registerEvent` makes `a:<name>` parse and
  `isEventRegistered` flip; `ResultRenderer` renders a `CustomRecord` (verb +
  target + message, bag in hover).
- `./gradlew check` stays green incl. jacoco floors.

## Non-goals

- Typed (number/boolean/list) bag values — strings only (deferred).
- Rollback support for custom events.
- A fluent builder API.

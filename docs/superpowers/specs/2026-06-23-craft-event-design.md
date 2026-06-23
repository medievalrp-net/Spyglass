# Player Craft Event

**Date:** 2026-06-23
**Status:** Approved (user pre-approved; implementing autonomously)
**Backend parity:** new sealed record type — touches all four backends (Mongo,
ClickHouse, SQLite, MariaDB), but only ClickHouse needs per-field column work.

## Problem

Spyglass logs how items move (`pickup`, `drop`, container `deposit`/`withdraw`)
and the auto-crafter block (`crafter`), but it does **not** log a *player*
crafting an item at a crafting table or in their 2×2 inventory grid. Operators
cannot answer "who crafted this TNT / these golden apples, and from what?"

## Goal

- New event `craft`: a player produces an item via `CraftItemEvent` (crafting
  table **and** the 2×2 inventory grid — both fire `CraftItemEvent`).
- Capture the **output** (searchable) and the **ingredients** consumed.
- `spyglass search p:the60th a:craft` → everything the60th crafted.
- Item-search params (`iname:`, `ilore:`, `ench:`, `itags:`) match against the
  **crafted output** item.
- Model it as an **abstract production event** so future stations (smithing,
  smelting, stonecutter, …) become additional event names (`smith`, `smelt`, …)
  that reuse the same `CraftRecord`, with **no further storage changes** — the
  same name-layer extensibility used by `deposit`/`withdraw` and `kill`/`mob-kill`.

## Design Overview

A new sealed `EventRecord` type, `CraftRecord`, captures a single production
event: who, where, the output item, the quantity produced, and the ingredients
consumed. `craft` is the only event name wired now; the record is deliberately
station-agnostic so later events map to it without schema churn.

### Why a new record type (not reuse `ItemPickupRecord`)

Crafting has data no existing record models: the **ingredient set**. Reusing
`ItemPickupRecord` would force the ingredients into an unrelated field or drop
them. A dedicated `CraftRecord` keeps the output searchable (like a pickup) and
adds a first-class `ingredients` list. The list reuses `List<StoredItem>`, a
shape already persisted by `BlockSnapshot.containerItems` across every backend —
so the new type costs no new storage primitive.

## Record: `CraftRecord` (spyglass-api)

```java
public record CraftRecord(
        UUID id,
        String event,                 // "craft" (future: "smith", "smelt", …)
        Instant occurred,
        Instant expiresAt,
        Origin origin,                // player origin
        Source source,                // the crafting player
        BlockLocation location,       // crafting-table block, or player loc (2×2 grid)
        String server,
        String target,                // output material name, e.g. "DIAMOND_PICKAXE"
        int amount,                   // total produced (shift-click aware, best-effort)
        StoredItem result,            // full projection of the output item
        List<StoredItem> ingredients  // lean projections of the recipe inputs
) implements EventRecord {

    public static CraftRecord of(RecordContext ctx, String target, int amount,
                                 StoredItem result, List<StoredItem> ingredients) { … }
}
```

### Field semantics

- `source` = the crafting player (so `p:` matches the crafter). `target` = the
  output `Material` name, for at-a-glance display and `a:craft` target search.
- `result` = full `StoredItem` projection of the crafted output (material + name
  + lore + enchants + tags + base64 data), so item params search the output and
  the hover can show custom names/enchants. Built off the main thread like
  `pickup`/`drop` (see Listener).
- `ingredients` = **lean** `StoredItem` projections (material + amount, NBT blob
  skipped — never reconstructed) of the crafting-grid matrix contents at craft
  time. Represents **one craft's recipe**, not the multiplied bulk total.
- `amount` = total items produced. For a normal click this is the recipe result
  amount; for a shift-click it is the best-effort estimate `sets × resultAmount`
  where `sets = min` non-empty matrix-slot stack size (see Quantity below).
- `location` = `event.getInventory().getLocation()` (the table block) when
  present, else the player's location (2×2 grid has no block).
- `origin` = `Origin.player()`.

### Quantity (shift-click) — best-effort, documented

`CraftItemEvent` fires **once** even for a shift-click that crafts a full stack.
Bukkit exposes no exact produced count. v1 estimates:

- normal click → `result.getAmount()`
- shift-click (`event.isShiftClick()`) → `sets × result.getAmount()`, where
  `sets` is the minimum stack size among the occupied matrix slots (each craft
  consumes one item per occupied slot).

This is an **upper bound** when the player's inventory fills mid-craft and not
every set is actually produced. Documented as a known v1 limitation; a
tick-later inventory-diff for exactness is out of scope.

## Listener: `CraftListener` (new)

`spyglass/.../listener/item/CraftListener.java`, implementing `RecordingListener`,
following `ItemPickupListener` exactly:

1. `@EventHandler(priority = MONITOR, ignoreCancelled = true)` on
   `org.bukkit.event.inventory.CraftItemEvent`.
2. Guard: `getWhoClicked()` is a `Player`; `getCurrentItem()` is non-null,
   non-AIR. Skip otherwise.
3. On the main thread: read the player (source/origin), the result material +
   amount, the matrix snapshot (clone of `getInventory().getMatrix()`), and the
   location; build the `RecordContext` (stamps `occurred` + the v7 id).
4. Off-thread (the injected `serializer` executor, as `pickup` does): build the
   full `result` projection and the lean `ingredients` projections, then
   `recorder.record(CraftRecord.of(...))`. `CraftRecord` is not `Rollbackable`,
   so deferring has no flush/read-your-writes interaction.
5. `events()` returns `Set.of("craft")`.

Registered in `SpyglassPlugin` alongside the other item listeners, gated by the
existing per-event enabled toggle.

## Catalog, Config, Rendering

- **`EventCatalog`**: `m.put("craft", CraftRecord.class);`
- **`EventRecord`** sealed `permits`: add `CraftRecord`.
- **`config.conf`** events block: `craft = { enabled = true, past-tense = "crafted" }`.
  (The auto-crafter block keeps its separate `crafter` event; both read
  "crafted" but are distinct names.)
- **`ResultRenderer`** + **`ProxyResultRenderer`**: inline
  `"<player> crafted <amount>x <material>"`; hover shows the ingredient list
  (`material ×amount` per line) plus the standard item detail (custom name,
  enchants) for the output via the existing `StoredItem` hover path.
- **`ItemFieldParams`**: add the `result` item path so `iname:`/`ilore:`/`ench:`/
  `itags:` match the crafted output.

## Storage parity

| Backend    | Work required |
|------------|---------------|
| Mongo      | none — record-codec is polymorphic; `List<StoredItem>` already round-trips. |
| SQLite     | none for the blob (full record stored as BSON); add `decodeRow` type branch. |
| MariaDB    | none for the blob; add `decodeRow` type branch. |
| ClickHouse | **per-field work**: reuse the existing flat item columns for `result` (so output item search works like `pickup`), add an `amount` write, add a `craft_ingredients` ZSTD blob column (same pattern as container-items blobs), add `writeCraftColumns` + a `decodeRow` branch + schema column + field-mapper entries as needed. |

No predicate/field changes are needed for ingredients (not independently
searchable in v1).

## Testing (per-backend round-trip parity)

- **Catalog**: `EventCatalogTest` — `craft` known, maps to `CraftRecord`.
- **Codec**: `EventRecordCodecTest` — `CraftRecord` (with a non-empty
  `ingredients` list) BSON round-trips.
- **Storage round-trip** on all four backends:
  `ClickHouseRecordStoreIT`, `SqliteRecordStoreTest`, `MongoRecordStoreIT`,
  `MariaDbRecordStoreIT` — save a `CraftRecord`, read it back, assert
  source/target/amount/result/ingredients survive.
- **Listener**: `CraftListenerTest` — player craft emits one `CraftRecord` with
  source = player, target = output material, ingredients = matrix contents;
  shift-click multiplies `amount`; non-player / AIR result emits nothing.
- **Rendering**: `ResultRendererTest` / proxy — the "crafted" inline form and
  the ingredient hover.

## Out of scope

- Smithing / furnace / stonecutter / brewing / loom / grindstone (future event
  names reusing `CraftRecord`).
- Exact shift-click counts via inventory diffing (v1 uses the documented estimate).
- Per-slot or custom-item-aware ingredient capture beyond the lean projection.
- Rollback (crafting is a forensic log, like `pickup`/`drop`).
- Backfilling historical data (no prior craft rows exist).

## Event-Type Parity Checklist

- [ ] Sealed `EventRecord` — add `CraftRecord` to `permits`; new record class.
- [ ] `EventCatalog` — `craft → CraftRecord`.
- [ ] Emitting listener — `CraftListener`, registered in `SpyglassPlugin`.
- [ ] Mongo codec — no change (verify via round-trip test).
- [ ] ClickHouse schema/mapper — result via flat item columns, `craft_ingredients`
      blob column, `writeCraftColumns`, `decodeRow` branch.
- [ ] SQLite / MariaDB — `decodeRow` type branch (blob handles the rest).
- [ ] Config default — `craft = { enabled = true, past-tense = "crafted" }`.
- [ ] Renderers — inline "crafted" + ingredient hover (Paper + proxy).
- [ ] `ItemFieldParams` — `result` item path for output item search.

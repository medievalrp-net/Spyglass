# Player Craft Event Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `craft` event that records when a player crafts an item (crafting table or 2×2 grid), capturing the output item and the ingredients consumed.

**Architecture:** A new sealed `EventRecord` type `CraftRecord` (output `StoredItem` + `List<StoredItem>` ingredients). Mongo/SQLite/MariaDB persist it for free via the polymorphic BSON-blob codec; only ClickHouse (wide flat-column table) needs per-field column work, reusing the existing `item`/`amount` columns for the output and a new `craft_ingredients` blob column for the ingredient list. A `CraftListener` on `CraftItemEvent` emits it, modeled station-agnostically so future events (`smith`, `smelt`) reuse the record.

**Tech Stack:** Java 21, Gradle (Kotlin DSL), Paper/Bukkit API, Velocity API, MongoDB driver, ClickHouse client, SQLite (JDBC), MariaDB (JDBC), JUnit 5, Testcontainers (CH/Mongo/MariaDB ITs).

## Global Constraints

- Sealed `EventRecord` hierarchy: any new record type MUST be added to the `permits` clause or the module won't compile.
- `EventCatalog` is the single source of truth for event-name → record-type; every event name must be registered there.
- `CraftRecord` is **not** `Rollbackable` (forensic log only, like `pickup`/`drop`).
- Item projections built **off the main thread** on a cloned stack (the injected `serializer` executor), never on the live event stack.
- Listeners use `@EventHandler(priority = MONITOR, ignoreCancelled = true)`.
- Backend storage parity: a record must round-trip on all four backends (Mongo, ClickHouse, SQLite, MariaDB).
- Config event entries: `<name> = { enabled = true, past-tense = "<verb>" }`.

---

### Task 1: `CraftRecord` type + catalog registration

**Files:**
- Create: `spyglass-api/src/main/java/net/medievalrp/spyglass/api/event/CraftRecord.java`
- Modify: `spyglass-api/src/main/java/net/medievalrp/spyglass/api/event/EventRecord.java` (permits clause, ~line 8-27)
- Modify: `spyglass-api/src/main/java/net/medievalrp/spyglass/api/event/EventCatalog.java` (static map, after line 57 `clone`)
- Test: `spyglass-api/src/test/java/net/medievalrp/spyglass/api/event/EventCatalogTest.java`

**Interfaces:**
- Produces: `CraftRecord(UUID id, String event, Instant occurred, Instant expiresAt, Origin origin, Source source, BlockLocation location, String server, String target, int amount, StoredItem result, List<StoredItem> ingredients)` plus `static CraftRecord of(RecordContext ctx, String target, int amount, StoredItem result, List<StoredItem> ingredients)`.
- Catalog: `craft → CraftRecord.class`.

- [ ] **Step 1: Failing catalog test** — add to `EventCatalogTest`:

```java
@Test
void craftIsRegisteredAsCraftRecord() {
    assertThat(EventCatalog.recordClassOf("craft")).isEqualTo(CraftRecord.class);
    assertThat(EventCatalog.eventNames()).contains("craft");
}
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :spyglass-api:test --tests EventCatalogTest` → fails (CraftRecord undefined / not mapped).

- [ ] **Step 3: Create `CraftRecord`** (mirror `ItemPickupRecord`, add `result`/`ingredients`):

```java
package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;

/**
 * A player crafting an item via {@code CraftItemEvent} (crafting table or the
 * 2x2 inventory grid). {@code result} is the full searchable projection of the
 * crafted output; {@code ingredients} are lean projections of the recipe matrix
 * consumed. Station-agnostic: future production stations (smithing, smelting)
 * reuse this record under their own event names. Not rollbackable.
 */
public record CraftRecord(
        UUID id,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String server,
        String target,
        int amount,
        StoredItem result,
        List<StoredItem> ingredients) implements EventRecord {

    public CraftRecord {
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
    }

    public static CraftRecord of(RecordContext ctx, String target, int amount,
                                 StoredItem result, List<StoredItem> ingredients) {
        return new CraftRecord(
                ctx.id(), "craft", ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), ctx.server(),
                target, amount, result, ingredients);
    }
}
```

- [ ] **Step 4: Add to `EventRecord` permits** — insert `CraftRecord,` into the `permits` list (e.g. after `ItemPickupRecord,`).

- [ ] **Step 5: Register in `EventCatalog`** — after `m.put("clone", ItemPickupRecord.class);` add:

```java
        // A player crafting an item (crafting table / 2x2 grid). Station-
        // agnostic: future production events (smith, smelt) reuse CraftRecord.
        m.put("craft", CraftRecord.class);
```

- [ ] **Step 6: Run, expect PASS** — `./gradlew :spyglass-api:test --tests EventCatalogTest`.

- [ ] **Step 7: Commit** — `git add -A && git commit -m "feat(api): CraftRecord type + craft event registration"`

---

### Task 2: BSON list codec + record round-trip

**Files:**
- Modify: `spyglass-core/src/main/java/net/medievalrp/spyglass/plugin/storage/BsonBlobs.java` (add public `encodeStoredItemList`/`decodeStoredItemList`)
- Test: `spyglass-core/src/test/java/net/medievalrp/spyglass/plugin/storage/EventRecordCodecTest.java`

**Interfaces:**
- Produces: `BsonBlobs.encodeStoredItemList(List<StoredItem>) -> String` (base64 or null when empty/null) and `BsonBlobs.decodeStoredItemList(String) -> List<StoredItem>` (never null; empty list on null input).
- Consumed by Task 5 (ClickHouse).

- [ ] **Step 1: Failing codec round-trip test** — in `EventRecordCodecTest` add a CraftRecord (with a 2-item ingredient list) and assert it survives `encodeRecordBytes`/`decodeRecordBytes`. Use the existing test's helper style; assert `result` material and `ingredients` size/materials match.

```java
@Test
void craftRecordRoundTripsThroughBson() {
    StoredItem out = new StoredItem(0, "DIAMOND_PICKAXE", null, null, java.util.List.of(), java.util.List.of(), null);
    java.util.List<StoredItem> ings = java.util.List.of(
            new StoredItem(0, "DIAMOND", null), new StoredItem(0, "STICK", null));
    CraftRecord rec = new CraftRecord(java.util.UUID.randomUUID(), "craft",
            java.time.Instant.ofEpochSecond(1000), java.time.Instant.ofEpochSecond(2000),
            new Origin("player", null), sampleSource(), sampleLocation(), "srv1",
            "DIAMOND_PICKAXE", 1, out, ings);
    byte[] bytes = BsonBlobs.encodeRecordBytes(rec);
    EventRecord back = BsonBlobs.decodeRecordBytes(bytes);
    assertThat(back).isInstanceOf(CraftRecord.class);
    CraftRecord cr = (CraftRecord) back;
    assertThat(cr.target()).isEqualTo("DIAMOND_PICKAXE");
    assertThat(cr.ingredients()).hasSize(2);
    assertThat(cr.ingredients().get(0).material()).isEqualTo("DIAMOND");
}
```

(If `sampleSource()`/`sampleLocation()` helpers don't exist in the test, inline minimal `new Source("player", uuid, "name", null,null,null,null,null)` and `new BlockLocation(uuid, "world", 1,2,3)`.)

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :spyglass-core:test --tests EventRecordCodecTest`. NOTE: the Mongo record codec may already handle `List<StoredItem>` (BlockSnapshot proves it). If this test PASSES immediately, the polymorphic codec already works — skip the codec change, keep the test.

- [ ] **Step 3: If FAIL**, the record codec doesn't auto-handle the list — confirm via the failure. Otherwise the codec is fine; this task's only remaining deliverable is the public list helpers for Task 5.

- [ ] **Step 4: Add list helpers to `BsonBlobs`** — mirror the single-item `encodeStoredItem`/`decodeStoredItem` (lines ~223-238) and the existing container-items array decode (lines ~169-180). Implement:

```java
public static @Nullable String encodeStoredItemList(@Nullable java.util.List<StoredItem> items) {
    if (items == null || items.isEmpty()) {
        return null;
    }
    // reuse the same BSON-document + base64 path as encodeStoredItem, wrapping
    // the list under an "items" array key (mirror encodeBlockExtras' array write).
    ...
}

public static java.util.List<StoredItem> decodeStoredItemList(@Nullable String base64) {
    if (base64 == null || base64.isBlank()) {
        return java.util.List.of();
    }
    ... // mirror the containerItems array decode at lines ~169-180
}
```

(Exact body: read the surrounding `encodeStoredItem`/`encodeBlockExtras` and the containerItems decode block first, then mirror them — same `BsonBinaryWriter`/base64 idiom.)

- [ ] **Step 5: Round-trip unit test for the helpers** (in `BsonBlobsTest` if it exists, else in `EventRecordCodecTest`):

```java
@Test
void storedItemListRoundTrips() {
    var list = java.util.List.of(new StoredItem(0,"DIAMOND",null), new StoredItem(0,"STICK",null));
    var back = BsonBlobs.decodeStoredItemList(BsonBlobs.encodeStoredItemList(list));
    assertThat(back).extracting(StoredItem::material).containsExactly("DIAMOND","STICK");
    assertThat(BsonBlobs.encodeStoredItemList(java.util.List.of())).isNull();
    assertThat(BsonBlobs.decodeStoredItemList(null)).isEmpty();
}
```

- [ ] **Step 6: Run, expect PASS** — `./gradlew :spyglass-core:test --tests EventRecordCodecTest --tests BsonBlobsTest`.

- [ ] **Step 7: Commit** — `git commit -am "feat(storage): BSON StoredItem-list codec + CraftRecord round-trip"`

---

### Task 3: Config default

**Files:**
- Modify: `spyglass/src/main/resources/config.conf` (events block, after the `clone` line)
- Test: `spyglass/src/test/java/net/medievalrp/spyglass/plugin/config/SpyglassConfigTest.java`

**Interfaces:** Consumes the per-event enabled/verb mechanism (same as `pickup`). Produces: `craft` is enabled by default with verb `crafted`.

- [ ] **Step 1: Confirm config is data-driven** — verify `SpyglassConfig` reads the events map generically (no hardcoded enum of names). If a hardcoded list exists, add `craft` there. (Read `SpyglassConfig` events parsing first.)

- [ ] **Step 2: Failing config test** — assert the default config enables `craft` with verb `crafted`:

```java
@Test
void craftEnabledByDefaultWithVerb() {
    SpyglassConfig cfg = SpyglassConfig.fromDefault(); // use whatever the existing tests use to load defaults
    assertThat(cfg.isEventEnabled("craft")).isTrue();
    assertThat(cfg.pastTense("craft")).isEqualTo("crafted");
}
```

(Match the actual accessor names used by neighboring tests in `SpyglassConfigTest`.)

- [ ] **Step 3: Run, expect FAIL** — `./gradlew :spyglass:test --tests SpyglassConfigTest`.

- [ ] **Step 4: Add config entry** — in `config.conf` events block, after the `clone = ...` line:

```
  craft = { enabled = true, past-tense = "crafted" }
```

- [ ] **Step 5: Run, expect PASS** — same command.

- [ ] **Step 6: Commit** — `git commit -am "feat(config): enable craft event with verb 'crafted'"`

---

### Task 4: `CraftListener` + registration

**Files:**
- Create: `spyglass/src/main/java/net/medievalrp/spyglass/plugin/listener/item/CraftListener.java`
- Modify: `spyglass/src/main/java/net/medievalrp/spyglass/plugin/SpyglassPlugin.java` (listener registration list, near the other item listeners)
- Test: `spyglass/src/test/java/net/medievalrp/spyglass/plugin/listener/item/CraftListenerTest.java`

**Interfaces:**
- Consumes: `Recorder`, `RecordingSupport`, `Executor serializer` (constructor mirrors `ItemPickupListener`). `RecordingSupport.playerOrigin()/playerSource(Player)/context(origin,source,location)`. `ItemSerialization.storedItemProjection(int slot, ItemStack)`.
- Produces: emits `CraftRecord` for each player `CraftItemEvent`. `events()` returns `Set.of("craft")`.

- [ ] **Step 1: Failing listener test** — mock a `CraftItemEvent` with a player, a result `ItemStack`, and a matrix; assert one `CraftRecord` recorded with `source` = player, `target` = result material, `ingredients` from the matrix. Mirror `ItemPickupListenerTest` setup (Bukkit mocks + a synchronous `serializer = Runnable::run`). Add cases: non-player clicker → nothing; AIR/null result → nothing; shift-click → `amount` multiplied.

```java
@Test
void playerCraftEmitsCraftRecord() {
    // event.getWhoClicked() -> player; getCurrentItem() -> DIAMOND_PICKAXE x1;
    // getInventory().getMatrix() -> [DIAMOND x3, STICK x2]; isShiftClick() false
    listener.onCraftItem(event);
    CraftRecord rec = captureRecorded(CraftRecord.class);
    assertThat(rec.target()).isEqualTo("DIAMOND_PICKAXE");
    assertThat(rec.amount()).isEqualTo(1);
    assertThat(rec.ingredients()).extracting(StoredItem::material)
            .containsExactlyInAnyOrder("DIAMOND", "STICK");
}
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :spyglass:test --tests CraftListenerTest`.

- [ ] **Step 3: Implement `CraftListener`** (structure mirrors `ItemPickupListener`):

```java
package net.medievalrp.spyglass.plugin.listener.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import net.medievalrp.spyglass.api.event.CraftRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class CraftListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final Executor serializer;

    public CraftListener(Recorder recorder, RecordingSupport support, Executor serializer) {
        this.recorder = recorder;
        this.support = support;
        this.serializer = serializer;
    }

    @Override
    public Set<String> events() {
        return Set.of("craft");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType() == Material.AIR) {
            return;
        }
        String target = result.getType().name();
        ItemStack resultSnapshot = result.clone();
        // Snapshot the crafting matrix (one craft's recipe inputs).
        ItemStack[] matrix = event.getInventory().getMatrix();
        List<ItemStack> matrixSnapshot = new ArrayList<>();
        int minOccupied = Integer.MAX_VALUE;
        for (ItemStack ing : matrix) {
            if (ing != null && ing.getType() != Material.AIR) {
                matrixSnapshot.add(ing.clone());
                minOccupied = Math.min(minOccupied, ing.getAmount());
            }
        }
        // Shift-click crafts as many full sets as the matrix allows (best-effort
        // upper bound; see spec). Normal click = one result.
        int sets = event.isShiftClick() && minOccupied != Integer.MAX_VALUE ? minOccupied : 1;
        int amount = sets * result.getAmount();

        BlockLocation location = event.getInventory().getLocation() != null
                ? BlockLocations.fromLocation(event.getInventory().getLocation())
                : BlockLocations.fromLocation(player.getLocation());
        Origin origin = support.playerOrigin();
        Source source = support.playerSource(player);
        RecordContext ctx = support.context(origin, source, location);
        serializer.execute(() -> {
            StoredItem out = ItemSerialization.storedItemProjection(0, resultSnapshot);
            List<StoredItem> ingredients = new ArrayList<>(matrixSnapshot.size());
            for (ItemStack ing : matrixSnapshot) {
                StoredItem si = ItemSerialization.storedItemProjection(0, ing);
                if (si != null) {
                    ingredients.add(si);
                }
            }
            recorder.record(CraftRecord.of(ctx, target, amount, out, ingredients));
        });
    }
}
```

(Verify `ItemSerialization.storedItemProjection(int, ItemStack)` signature and `RecordContext`/`RecordingSupport` method names against `ItemPickupListener`; adjust if the lean-projection helper has a different name.)

- [ ] **Step 4: Register in `SpyglassPlugin`** — add `new CraftListener(recorder, support, serializer)` to the listener list next to `new ItemPickupListener(...)` (match the exact constructor args used there for the serializer executor).

- [ ] **Step 5: Run, expect PASS** — `./gradlew :spyglass:test --tests CraftListenerTest`.

- [ ] **Step 6: Commit** — `git commit -am "feat(listener): record player crafts as craft events"`

---

### Task 5: ClickHouse storage (schema + write + decode + field map) + IT

**Files:**
- Modify: `spyglass-core/.../storage/ClickHouseSchema.java` (`buildEventRecordsTable`, add `craft_ingredients` column)
- Modify: `spyglass-core/.../storage/ClickHouseRecordStore.java` (`writeItemColumns` reuse for result; new `writeCraftColumns`; `decodeRow` branch; `SUMMARY_EXTRAS` if needed)
- Modify: `spyglass-core/.../storage/ClickHouseFieldMapper.java` (only if a craft-specific searchable field is added — none in v1, so likely no change)
- Test: `spyglass-core/src/test/java/.../storage/ClickHouseRecordStoreIT.java` (add CraftRecord to the all-types round-trip)

**Interfaces:** Consumes `BsonBlobs.encodeStoredItemList`/`decodeStoredItemList` (Task 2), `BsonBlobs.encodeStoredItem`/`decodeStoredItem`. Reuses existing `amount` and `item` columns for the craft output.

- [ ] **Step 1: Add CraftRecord to the IT round-trip** — in `ClickHouseRecordStoreIT` (the `savesAndQueriesAllRecordTypes`-style test), add a `CraftRecord` with a non-empty ingredient list to the saved set and assert it decodes back with matching `target`, `amount`, `result.material()`, and `ingredients` materials.

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :spyglass-core:test --tests ClickHouseRecordStoreIT` (requires Docker/Testcontainers). Fails: column missing / decode returns null.

- [ ] **Step 3: Add schema column** — in `ClickHouseSchema.buildEventRecordsTable`, near the item/container columns, add:

```
        + "    craft_ingredients String CODEC(ZSTD(1)),\n"
```

(Match the exact DDL string style of neighboring nullable blob columns like `before_item`/`item`.)

- [ ] **Step 4: Write the output item + amount + ingredients** —
  - In `writeItemColumns`, add `else if (record instanceof CraftRecord c) { item = c.result(); }` so the output reuses the `item` column.
  - In `writeContainerColumns`, add `else if (record instanceof CraftRecord c) { amount = c.amount(); }` so `amount` is written.
  - Add a new `writeCraftColumns` and call it in `writeRow` next to `writeItemColumns`:

```java
private void writeCraftColumns(RowBinaryFormatWriter writer, EventRecord record) throws IOException {
    String ingredients = null;
    if (record instanceof CraftRecord c) {
        ingredients = BsonBlobs.encodeStoredItemList(c.ingredients());
    }
    writer.setValue("craft_ingredients", ingredients);
}
```

(`craft_ingredients` is non-nullable `String` in DDL — write `""` instead of null if the column is non-nullable: `ingredients == null ? "" : ingredients`. Confirm nullability against how `item` is declared and mirror it.)

- [ ] **Step 5: Decode branch** — in `decodeRow`, after the `ItemPickupRecord` branch (~line 990):

```java
if (clazz == CraftRecord.class) {
    return new CraftRecord(id, event, occurred, expiresAt,
            origin, source, location, server, target,
            row.getInteger("amount"),
            BsonBlobs.decodeStoredItem(row.getString("item")),
            BsonBlobs.decodeStoredItemList(row.getString("craft_ingredients")));
}
```

- [ ] **Step 6: Run, expect PASS** — `./gradlew :spyglass-core:test --tests ClickHouseRecordStoreIT`.

- [ ] **Step 7: Commit** — `git commit -am "feat(clickhouse): persist CraftRecord (output via item cols, ingredients blob)"`

---

### Task 6: Mongo / SQLite / MariaDB round-trip ITs

**Files (tests only — no production code expected):**
- Modify: `spyglass-core/src/test/java/.../storage/MongoRecordStoreIT.java`
- Modify: `spyglass-core/src/test/java/.../storage/SqliteRecordStoreTest.java`
- Modify: `spyglass-core/src/test/java/.../storage/MariaDbRecordStoreIT.java`

**Interfaces:** None new. These backends persist `CraftRecord` via the BSON blob path (only break/place are column-stored), so the production code is already complete after Task 1–2.

- [ ] **Step 1: Add CraftRecord to each backend's round-trip test** — in each file's all-record-types test (or as a dedicated `craftRecordRoundTrips` test mirroring the existing `kill`/`mob-kill` additions), save a `CraftRecord` with a 2-item ingredient list and assert `target`/`amount`/`result.material()`/`ingredients` survive a read.

- [ ] **Step 2: Run each, expect PASS** (Docker for Mongo/MariaDB):
  - `./gradlew :spyglass-core:test --tests SqliteRecordStoreTest`
  - `./gradlew :spyglass-core:test --tests MongoRecordStoreIT`
  - `./gradlew :spyglass-core:test --tests MariaDbRecordStoreIT`
  - If any FAIL: the write path for that backend is column-storing CraftRecord instead of blobbing it (unexpected) — fall back to forcing the blob path or add a decode branch mirroring Task 5. Investigate before assuming.

- [ ] **Step 3: Commit** — `git commit -am "test(storage): CraftRecord round-trip on mongo/sqlite/mariadb"`

---

### Task 7: Rendering (Paper + proxy) + ItemFieldParams

**Files:**
- Modify: `spyglass/.../command/render/ResultRenderer.java` (`targetOf`, `quantityOf`, `hover`, `subjectItem`)
- Modify: `spyglass-velocity/.../proxy/command/ProxyResultRenderer.java` (mirror)
- Modify: `spyglass/.../command/param/ItemFieldParams.java` (add `result` item path)
- Test: `spyglass/src/test/java/.../command/render/ResultRendererTest.java`

**Interfaces:** Consumes `CraftRecord.result()`, `CraftRecord.ingredients()`, `CraftRecord.amount()`, `CraftRecord.target()`.

- [ ] **Step 1: Failing renderer test** — assert a `CraftRecord` renders inline as `"<player> crafted 1x DIAMOND_PICKAXE"` (match the exact verb-injection format the renderer uses for `pickup`) and that the hover lists ingredients. Mirror an existing `ItemPickupRecord` render test in `ResultRendererTest`.

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :spyglass:test --tests ResultRendererTest`.

- [ ] **Step 3: Implement Paper renderer** —
  - `targetOf`: add `case CraftRecord c -> c.target();` (or `instanceof` branch, matching the file's style).
  - `quantityOf`: add `case CraftRecord c -> c.amount();`.
  - `subjectItem` (the item shown on hover): add `case CraftRecord c -> c.result();` so the output item's name/enchants render.
  - `hover`: add ingredient lines, e.g. for each `ingredient` a `kv("Ingredient", ing.material() + " x" + <count>)` line — match the existing hover helper (`kv(...)`) and the StoredItem detail block used by pickups.

- [ ] **Step 4: Mirror in `ProxyResultRenderer`** — same `targetOf`/`quantityOf`/hover additions; add the past-tense `craft → crafted` if the proxy renderer keeps its own verb map (check; the Paper side is config-driven).

- [ ] **Step 5: Add `result` item path to `ItemFieldParams`** — append the `CraftRecord.result` projection path to the `ITEM_PATHS` list so `iname:`/`ilore:`/`ench:`/`itags:` match the crafted output. (Read the existing path entries — they reference record item fields; add the craft output the same way.)

- [ ] **Step 6: Run, expect PASS** — `./gradlew :spyglass:test --tests ResultRendererTest`.

- [ ] **Step 7: Commit** — `git commit -am "feat(render): craft inline + ingredient hover; output item search"`

---

### Task 8: Full build + verification

- [ ] **Step 1: Full build & test** — `./gradlew build` (compiles all modules incl. velocity; runs unit tests; ITs gated on Docker). Expect BUILD SUCCESSFUL.
- [ ] **Step 2: Manual sanity (optional, if a server is available)** — craft an item, run `/sg search a:craft`, confirm "crafted Nx <item>" and ingredient hover; run `/sg search p:<you> a:craft`.
- [ ] **Step 3: Final commit / push** — `git commit -am "..."` for any cleanup; leave the branch for review.

---

## Self-Review

**Spec coverage:**
- New `CraftRecord` (output + ingredients) → Task 1. ✓
- `craft` event name, station-agnostic reuse → Task 1 (catalog) + spec note. ✓
- Listener on `CraftItemEvent`, table + 2×2 grid, off-thread projection → Task 4. ✓
- Shift-click best-effort amount → Task 4 (`sets × resultAmount`). ✓
- Output item searchable (`iname`/`ilore`/`ench`/`itags`) → Task 7 (ItemFieldParams + CH `item` column reuse). ✓
- Ingredients as `List<StoredItem>` → Task 1 + Task 2 (codec) + Task 5 (CH blob). ✓
- All four backends round-trip → Task 5 (CH) + Task 6 (Mongo/SQLite/MariaDB). ✓
- Config default `craft = crafted` → Task 3. ✓
- Renderers inline + hover (Paper + proxy) → Task 7. ✓
- Not rollbackable → no `Rollbackable` impl (Task 1). ✓

**Placeholder scan:** Steps that say "match/verify against existing X" are deliberate (executor reads the exact neighboring code at that point); all type names and signatures are concrete.

**Type consistency:** `CraftRecord` component order `(…, int amount, StoredItem result, List<StoredItem> ingredients)` is identical in Task 1 (definition), Task 2 (codec test), Task 5 (CH decode), Task 6 (backend tests). `BsonBlobs.encodeStoredItemList`/`decodeStoredItemList` names consistent across Task 2 and Task 5. `events()` returns `Set.of("craft")` (Task 4) matching catalog name (Task 1).

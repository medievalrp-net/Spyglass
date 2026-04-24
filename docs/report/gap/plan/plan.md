# Spyglass — gap-fix execution plan

Companion to [`../gap.md`](../gap.md). Every entry references the gap-report section it addresses so nothing is fixed in a vacuum.

**Scoping decision:** the v1 → v2 migration tool is **not** being restored. All gaps related to migration (deleted `plugin/migration/` sources, `spyglass.admin` permission, `admin migrate-v1` command, README upgrade instructions) are handled in Phase 0 as a cleanup-and-document task, not a restoration.

Effort estimates: **S** ≤ 2 hours, **M** ~half-day, **L** ~full-day, **XL** multi-day.

---

## Execution order

Fix in phases. Each phase is independently commit-able and landable. Do not skip ahead — later phases assume earlier phases committed clean.

1. **Phase 0** — working tree & correctness hygiene (P0). The plugin is half-released right now; stabilize first.
2. **Phase 1** — correctness bugs and dead code. Quick wins, no new features.
3. **Phase 2** — operator-critical feature restorations.
4. **Phase 3** — search/query restorations (params, flags).
5. **Phase 4** — lower-priority listener restorations.
6. **Phase 5** — API surface polish, performance, cleanup.

After each phase runs `./gradlew test regression` green, commit, and land. No block should be half-done when the phase ends.

---

## Phase 0 — stabilize the working tree

**Goal:** the repo matches the state the README describes, minus the operator-dropped migration tool.

### 0.1 Commit or revert all uncommitted modifications

- **What:** review `git status -s` output. ~30 files are `M` in the working tree beyond the Refactor wave commits (the ones that landed after v1.0.0). Untracked `api/event/BlockUseRecord.java` exists on disk and is wired into `EventRecord.permits` and `EventCatalog`.
- **How:** `git diff` each modified file, decide whether each change was intentional refactor work or in-progress noise. Group into logical commits and land.
- **Priority:** P0
- **Effort:** M (depends on how much of the wave work was half-finished)
- **Acceptance:** `git status -s` is empty except for deletions from 0.2.

### 0.2 Commit the migration deletions

- **What:** `plugin/src/main/java/net/medievalrp/spyglass/plugin/migration/` and `plugin/src/test/java/net/medievalrp/spyglass/plugin/migration/` are marked `D` on disk. Per operator decision, migration is not being restored.
- **How:**
  ```bash
  git rm plugin/src/main/java/net/medievalrp/spyglass/plugin/migration/*.java
  git rm plugin/src/test/java/net/medievalrp/spyglass/plugin/migration/*.java
  ```
  Single commit, message: `Drop v1 → v2 migration tool; operator will not upgrade existing data`.
- **Priority:** P0
- **Effort:** S
- **Acceptance:** migration files no longer tracked in git; `grep -r migrate /docs /plugin` returns nothing except the README section handled in 0.3.

### 0.3 Strip migration references from user-facing docs

- **What:**
  - [`README.md:26-38`](../../../../README.md) — "Upgrading from v1" section describing `sg admin migrate-v1`
  - [`README.md:72-74`](../../../../README.md) — "Wire-format compatibility" section that only exists to justify migration
  - `plugin.yml` — add/remove `spyglass.admin` (see below)
- **How:**
  - Delete the "Upgrading from v1" section entirely. Replace with a single line: *This plugin is a fresh install with no migration path from the legacy MPL v1. See the `v1/` sibling repo for historical reference only.*
  - Delete the "Wire-format compatibility" section — with migration gone, the schema can evolve freely.
- **Priority:** P0
- **Effort:** S
- **Acceptance:** README no longer mentions `migrate-v1`, `v1.DataEntry`, or v1-compat schema claims.

---

## Phase 1 — correctness bugs and dead code

**Goal:** the shipping plugin is free of known bugs and dead paths.

### 1.1 Fix `SculkListener` to hook the right event

- **What:** v2's [`SculkListener.java`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/listener/modern/SculkListener.java) hooks `SculkBloomEvent`, which fires when a sculk catalyst grows around a death — not when a sensor or shrieker is triggered by a player. v1's semantic was "sculk sensor / shrieker activated by player," which is the more useful operator signal for tracking stealthy player activity.
- **How:** replace `SculkBloomEvent` handler with `BlockReceiveGameEvent` handler, filtered on `SCULK_SENSOR` / `CALIBRATED_SCULK_SENSOR` / `SCULK_SHRIEKER` block types and `Entity instanceof Player` as the trigger. Preserve the `sculk` event name and the config key.
- **Priority:** P0
- **Effort:** S
- **Acceptance:** in-game right-click on a sculk sensor while crouching next to it produces a `sculk` record attributed to the player; a creeper death next to a catalyst (which *was* producing records before) no longer does.

### 1.2 Declare `spyglass.worldedit` permission

- **What:** [`QueryStringParser:74`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/param/QueryStringParser.java) gates the `-we` flag behind a permission that isn't declared in [`plugin.yml`](../../../../plugin/src/main/resources/plugin.yml). Bukkit defaults undeclared perms to false for non-ops.
- **How:** add to `plugin.yml`:
  ```yaml
  spyglass.worldedit:
    default: op
  ```
- **Priority:** P0
- **Effort:** S
- **Acceptance:** a non-op player with `spyglass.worldedit` granted via LuckPerms can use `/sg search a:break -we`.

### 1.3 Remove Bundle debug logging

- **What:** [`BundleTransactionListener:70-85`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/listener/modern/BundleTransactionListener.java) logs `bundle-click: ...` and `bundle-diff: ...` on every relevant click.
- **How:** delete the `plugin.getLogger().info(...)` calls. If diagnostic output is ever wanted again, gate it behind `plugin.getLogger().fine(...)` and document the logger level to enable.
- **Priority:** P0
- **Effort:** S
- **Acceptance:** `grep "bundle-click\|bundle-diff" plugin/src/main/java/` returns nothing.

### 1.4 Fix `crafter` vs `craft` event-name inconsistency

- **What:** v1 emitted `craft`; v2 emits `crafter` from [`CrafterListener`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/listener/modern/CrafterListener.java). The [`EventCatalog`](../../../../api/src/main/java/net/medievalrp/spyglass/api/event/EventCatalog.java) and [`config.conf`](../../../../plugin/src/main/resources/config.conf) use `crafter`. Trivial inconsistency, pick one.
- **How:** decide. The v2 name is more accurate (it's the block, not the action). Keep `crafter`. Update documentation accordingly. No code change required beyond confirming the docs.
- **Priority:** P1
- **Effort:** S
- **Acceptance:** config, catalog, listener, docs all agree on `crafter`.

### 1.5 Resolve dead flags

- **What:** `Flag.DRAIN` is never parsed or consumed. `Flag.EXTENDED` is parsed but never read by the renderer. `Flag.GLOBAL` is parsed but only referenced via a local bool.
- **How:**
  - `Flag.DRAIN` — delete from the enum. No caller.
  - `Flag.EXTENDED` — either:
    - (a) implement v1's behavior in `ResultRenderer.line` — append a gray `" - (x y z world)"` line under each result when the flag is set; **or**
    - (b) delete the enum value and the parser case.
    - **Recommendation:** (a). The extended view is genuinely useful for staff investigations. Low effort given [`ResultRenderer.hover`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/render/ResultRenderer.java) already knows how to format a location.
  - `Flag.GLOBAL` — keep as-is. The enum value is stored in `QueryRequest.flags`, so external callers can inspect what flags were parsed.
- **Priority:** P1
- **Effort:** S (delete path) or M (implement EXTENDED)
- **Acceptance:** `grep Flag.DRAIN` returns nothing; either `Flag.EXTENDED` is consumed in `ResultRenderer` or removed.

### 1.6 Resolve `Messages.java` unused MiniMessage loader

- **What:** [`Messages.java`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/render/Messages.java) is a full MiniMessage template loader with placeholder support, never called anywhere.
- **How:** decide:
  - (a) **Wire it in.** Replace every `Component.text(...)` in `Feedback`, `HelpService`, `EventsService`, `ResultRenderer` with `messages.component("path.to.key", Placeholder.unparsed("player", name))`. Ship a bundled `messages.conf` in resources. i18n-ready.
  - (b) **Delete it.** If operator customization isn't worth the MiniMessage dependency, remove the class.
  - **Recommendation:** (b) for now. The server is MedievalRP-specific and operator copy tweaks aren't on the roadmap. If i18n becomes a need later, revisit.
- **Priority:** P2
- **Effort:** L (wire in) or S (delete)
- **Acceptance:** either `Messages` is referenced by at least one service, or it's deleted.

### 1.7 Resolve `registerDisplayRenderer` half-built API

- **What:** [`SpyglassApi.registerDisplayRenderer`](../../../../api/src/main/java/net/medievalrp/spyglass/api/SpyglassApi.java) accepts renderer registrations via `SpyglassApiImpl.renderers` but [`ResultRenderer`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/render/ResultRenderer.java) never looks them up.
- **How:** decide:
  - (a) **Wire it in.** Inject `SpyglassApi` into `ResultRenderer`. In `line`, check `api.displayRenderer(record.event())` — if present, call its `renderTarget(record, defaultTarget, flags)` instead of the default, and append its `hoverLines(record)` to the hover.
  - (b) **Remove from API.** If extension isn't on the roadmap, cut the method from the interface and drop `renderers` from the impl.
  - **Recommendation:** (a). The extension point is near-free to honor and enables sister plugins (Reserv, Cauldron, etc.) to add event-specific chat polish without patching Spyglass.
- **Priority:** P2
- **Effort:** M
- **Acceptance:** a test registers a `DisplayRenderer` and verifies its output appears in search results.

---

## Phase 2 — operator-critical feature restorations

**Goal:** restore capture coverage that matters for day-to-day RP server operation.

### 2.1 Restore console command logging

- **What:** v1's [`EventCommandListener.onServerCommand`](../../../../../v1/the v1 core/src/main/java/net/medievalrp/v1/listener/chat/EventCommandListener.java) logged commands typed at the console. v2's [`CommandListener`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/listener/chat/CommandListener.java) only hooks `PlayerCommandPreprocessEvent`.
- **How:** add a second `@EventHandler` for `ServerCommandEvent`. Use `Source.console()` and a neutral `Origin.environment("console")`. Location can be null or use a sentinel "server" location (pick something explicit; prefer keeping `location` required with a sentinel `BlockLocation(defaultWorld.getUID(), defaultWorld.getName(), 0, 0, 0)`).
- **Priority:** P1
- **Effort:** S
- **Acceptance:** typing `/say hello` at the console produces a `command` record with `Source.kind = "console"`.

### 2.2 Restore clickable teleport from search results

- **What:** v1 results had a `ClickEvent.Action.RUN_COMMAND → /sgtele <world> <x> <y> <z>`, jumping the operator to where the event happened. v2 runs another `/sg search` instead.
- **How:**
  - Add a `/sg tele <worldUuid> <x> <y> <z>` subcommand in [`SpyglassCommands`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/SpyglassCommands.java), gated on `spyglass.tele` permission (new, default op).
  - In [`ResultRenderer.line`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/render/ResultRenderer.java), change the click event to `ClickEvent.runCommand("/sg tele " + loc.worldId() + " " + loc.x() + " " + loc.y() + " " + loc.z())`.
  - Implementation of the command: resolve world by UUID, teleport player to `(x+0.5, y, z+0.5)` (matches v1's `.5` offsets for center-of-block).
- **Priority:** P1
- **Effort:** M
- **Acceptance:** clicking a result in chat teleports the operator; the click hover can mention "Click to teleport" for discoverability (optional polish).

### 2.3 Restore multi-block break dependencies

- **What:** v1's `DependantStyle` taxonomy in [`EventBreakListener`](../../../../../v1/the v1 core/src/main/java/net/medievalrp/v1/listener/block/EventBreakListener.java) captured companion breaks for wall-mounted / bottom-attached / tall / all-sides-attached blocks. v2 only handles bed + door + tall plant pairs in [`MultiBlockBreakListener`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/listener/block/MultiBlockBreakListener.java). Without this, rollbacks of walls with torches/signs/levers/rails/carpets leave orphaned attachments.
- **How:** port the `getStyle(Material)` table and the `saveDependantBreaks(source, broken)` logic into a new listener. Keep the existing `MultiBlockBreakListener` for bed/door/tall-plant (that logic is tighter than v1's); add `DependantBreakListener` for the other four dependency styles.
- **Priority:** P1
- **Effort:** L (the taxonomy is ~150 lines of switch-case; most of it is paste-from-v1 with material constants updated for 1.21)
- **Acceptance:** break a wall block with a torch, carpet, or rail attached; rollback restores them alongside the wall.

### 2.4 Restore player-source ignite chain propagation

- **What:** v1's [`EventIgniteListener`](../../../../../v1/the v1 core/src/main/java/net/medievalrp/v1/listener/block/EventIgniteListener.java) tagged ignited blocks with `player-source` Bukkit metadata carrying the arsonist's UUID. When fire propagated to a chest, the break event attributed back to the original arsonist via metadata lookup. v2 only logs the immediate `BlockIgniteEvent`.
- **How:**
  - In `BlockIgniteListener.onBlockIgnite`: if the cause has a player, set `FixedMetadataValue("player-source", player.getUniqueId().toString())` on the ignited block.
  - In `BlockBreakListener.onBlockBreak` (and the explode variants): check for `player-source` metadata on the broken block; if present and the `BlockBreakEvent.getPlayer()` is null or environmental, use that UUID as the source.
  - Requires plumbing `plugin` reference into those listeners — manageable since they already receive `RecordingSupport`. Add a `plugin` parameter.
- **Priority:** P1
- **Effort:** M
- **Acceptance:** ignite a building with flint & steel; when fire spreads and breaks a chest, the break record attributes to the arsonist, not `environment`.

---

## Phase 3 — query/flag restorations

**Goal:** restore operator power-user search features.

### 3.1 Restore `m:` (message) param

- **What:** v1's `MessageParameter` queried `DataKeys.MESSAGE`. v2 has no search path into chat or command message content.
- **How:** new `MessageParam` under [`plugin/command/param/`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/param/). Aliases `m`, `message`. Parse behavior: regex substring search against the `message` field on `ChatRecord` (and `commandLine` on `CommandRecord` — need Or-across-paths like `ItemFieldParams`). Register in [`SpyglassPlugin.onEnable`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/SpyglassPlugin.java) param list.
- **Priority:** P1
- **Effort:** M
- **Acceptance:** `/sg search a:say,command m:diamond` returns chats and commands mentioning diamond.

### 3.2 Restore `c:` (cause) param

- **What:** v1's `CauseParameter` searched `DataKeys.CAUSE` (the stringly-typed source for non-player events). v2 stores this in `Source.description` for environment sources or `Source.entityType` for entity sources.
- **How:** new `CauseParam`. Aliases `c`, `cause`. Translate to Or-predicate against `source.description` and `source.entityType`. Support comma-separated values (`c:creeper,tnt`) and negation (`c:!environment`).
- **Priority:** P1
- **Effort:** M
- **Acceptance:** `/sg search a:break c:creeper t:1d -g` returns creeper-caused breaks.

### 3.3 Restore `i:` (item material) and `trg:` (target) params

- **What:** `i:` searched items by material; `trg:` did a raw target-field search. Both are common fallbacks when the typed params don't quite fit.
- **How:**
  - `ItemMaterialParam` (aliases `i`, `item`) — parses material names like `BlockParam` but with `Material.isItem()` instead of `isBlock()`; predicate `Eq("target", name.toUpperCase())` or `In` for multiples.
  - `TargetParam` (alias `trg`) — substring regex against `target` field. Gracefully handle comma-separated multiples.
- **Priority:** P1
- **Effort:** S each
- **Acceptance:** `/sg search i:diamond_sword`, `/sg search trg:chest` both work.

### 3.4 Restore `ip:` (IP address) param

- **What:** v1 let you search join records by IP. v2 stores IP in `JoinRecord.address` but no param exposes it.
- **How:** `IpParam` (alias `ip`) — predicate `Eq("address", value)` scoped to `JoinRecord`. Since the `address` field only exists on `JoinRecord`, the `candidateTypes` narrowing in `MongoRecordStore` will naturally scope the query to that record type only.
- **Priority:** P2
- **Effort:** S
- **Acceptance:** `/sg search a:join ip:192.168.1.100 t:30d` finds historical joins from that IP.

### 3.5 Restore `rcp:` (recipient) param

- **What:** v1 let you filter chats by recipient UUID. v2 stores `ChatRecord.recipients` but no search path.
- **How:** `RecipientParam` (aliases `rcp`, `recipient`). Translate player names → UUIDs, predicate `In("recipients", uuids)` (since `recipients` is a list, Mongo `$in` matches if any element is in the list).
- **Priority:** P2
- **Effort:** S
- **Acceptance:** `/sg search a:say rcp:Alice t:1d` returns chats where Alice was a recipient.

### 3.6 Restore `-nod=...` flag

- **What:** v1 let operators skip individual default params (`-nod=r,t` to disable both radius and time defaults). v2 only has the binary `defaults.enabled` config.
- **How:** parse `-nod=alias1,alias2` in [`QueryStringParser`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/command/param/QueryStringParser.java). For each alias, suppress the corresponding default: if `r` is in the list, set `defaultRadiusSuppressed = true`; if `t` is in the list, set `sawTime = true`. For future param defaults, the same pattern.
- **Priority:** P2
- **Effort:** S
- **Acceptance:** `/sg search p:Alice -nod=r` uses default time but no default radius.

---

## Phase 4 — lower-priority listener restorations

### 4.1 Restore item-frame interaction events

- **What:** v1's `EventEntityItemListener` had branches for `PlayerInteractAtEntityEvent` on `ItemFrame` (deposit) and `EntityDamageByEntityEvent` on `ItemFrame` (withdraw, triggered by left-click). v2's `ArmorStandManipulateListener` only handles armor stands.
- **How:** add an `ItemFrameInteractListener` implementing `RecordingListener` with events `entity-deposit`, `entity-withdraw`. Hook both Bukkit events. Emit `ContainerDepositRecord` / `ContainerWithdrawRecord` with `containerType = "ITEM_FRAME"`.
- **Priority:** P2
- **Effort:** M
- **Acceptance:** placing a rose in a frame and punching it out produces both records.

### 4.2 Restore `named` event (name-tag renaming)

- **What:** v1's `EventInteractAtEntity` logged when a player used a name tag on a non-player living entity to rename it. Tracks the before/after names.
- **How:** new `EntityNamingListener` hooking `PlayerInteractAtEntityEvent`. Filter: item in hand is `NAME_TAG` with display-name meta, target is non-player LivingEntity. New record type `EntityNameRecord(..., String entityType, UUID entityId, String oldName, String newName)` in `api/event/` and added to the `EventRecord` sealed list. Emit `named` event.
- **Priority:** P2
- **Effort:** M (new record type requires POJO codec wiring and renderer case)
- **Acceptance:** renaming a cow with a tag produces a `named` record queryable with `a:named`.

### 4.3 Restore container `close` events for non-shulkers

- **What:** v1 logged `close` for any `Container`/`DoubleChest`. v2's [`ContainerInteractListener.onInventoryClose`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/listener/container/ContainerInteractListener.java) only fires for `ShulkerBox`.
- **How:** remove the `if (!(holder instanceof ShulkerBox shulker))` filter. When it's any `Container`, emit `close` event; when it's specifically a shulker, emit `shulker-close` (existing behavior). Requires adding `close` to the listener's `events()` set.
- **Priority:** P2
- **Effort:** S
- **Acceptance:** closing a chest produces a `close` record.

### 4.4 Restore dispenser drop logging

- **What:** v1's `EventDropListener.onBlockDispense` logged `drop` for dispenser output. v2 doesn't.
- **How:** add `BlockDispenseEvent` handler to existing [`ItemDropListener`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/listener/item/ItemDropListener.java). Source is the dispenser block; Origin is environment.
- **Priority:** P2
- **Effort:** S
- **Acceptance:** a dispenser firing a snowball produces a `drop` record with environmental source.

### 4.5 Restore non-player entity drop logging

- **What:** v1's `EventDropListener.onEntityDropItem` logged item drops from non-player entities. v2 doesn't.
- **How:** add `EntityDropItemEvent` handler to `ItemDropListener`. Skip when entity is a player (handled by existing `PlayerDropItemEvent` handler).
- **Priority:** P3
- **Effort:** S
- **Acceptance:** a mob picking up and dropping an item produces a `drop` record.

### 4.6 Restore creative-mode `clone` event

- **What:** v1 logged creative-mode middle-click cloning via `EventInventoryListener` CLONE branch. v2 doesn't.
- **How:** in [`ContainerTransactionListener`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/listener/container/ContainerTransactionListener.java), add a branch for `InventoryAction.CLONE_STACK`. Needs a new event name `clone` and new record type or reuse of `ItemPickupRecord` with an explicit origin. New record type cleaner. Register in `EventCatalog` + config.
- **Priority:** P3
- **Effort:** M
- **Acceptance:** middle-click on an item in creative produces a `clone` record.

### 4.7 Restore CraftBook sign-use logging — **skip unless CraftBook is being deployed**

- **What:** v1's `CraftBookSignListener` hooked sign right-clicks when CraftBook was present. Not useful if CraftBook isn't in play.
- **How:** N/A unless needed. Flag for decision when/if CraftBook comes back.
- **Priority:** P4 / deferred
- **Effort:** M if ever needed

### 4.8 Restore mid-runtime WE plugin enable/disable

- **What:** v1's `PluginInteractionListener` re-registered WE flag handler when WE plugin enabled/disabled at runtime. v2 only hooks WE at `onEnable`.
- **How:** add a `PluginEnableEvent`/`PluginDisableEvent` listener gated on "WorldEdit" or "FastAsyncWorldEdit" — on enable, register `WorldEditSubscriber` if not already; on disable, unregister.
- **Priority:** P4
- **Effort:** M
- **Acceptance:** `/reload confirm` doesn't require a full server restart to re-wire WE.

---

## Phase 5 — API surface polish & performance

### 5.1 Fix `MongoRecordStore` per-type query fan-out

- **What:** [`MongoRecordStore.query()`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/storage/MongoRecordStore.java) runs up to 13 separate Mongo queries when there's no `event` predicate, merges results in Java, re-sorts, and truncates.
- **How:** replace the per-type loop with a single `database.getCollection(collectionName, EventRecord.class)` query using the POJO codec's polymorphic dispatch via a discriminator. Requires either:
  - (a) adding a `@BsonDiscriminator` to `EventRecord` and its permits, **or**
  - (b) adding a `_class` discriminator field via a custom codec (similar to `RollbackEffectCodec`).
  - Recommendation: (b) — consistent with existing codec patterns, and avoids requiring annotations on the public API records.
- **Priority:** P2
- **Effort:** L (codec work + test updates)
- **Acceptance:** unfiltered `/sg search t:1h -g` performs one Mongo query instead of 13; `MongoRecordStoreIT` still passes.

### 5.2 Make `Source` a sealed interface

- **What:** [`Source`](../../../../api/src/main/java/net/medievalrp/spyglass/api/event/Source.java) is a flat record with 8 fields, most null per kind. Storage is slightly wasteful, and the type screams for sealed hierarchy.
- **How:**
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
  Update `RecordContext` and every record's `source` field. Update `PlayerParam` to search `source.playerSource.id` (or keep `source.playerId` by using POJO codec field mapping).
- **Priority:** P3
- **Effort:** L (touches every record type, every listener, every test)
- **Acceptance:** Mongo docs no longer carry null fields per kind; type signatures prevent constructing invalid sources (e.g., `PlayerSource` with a plugin name).

### 5.3 Decide on external plugin custom event registration

- **What:** v1 let downstream plugins register arbitrary event names via `OEntry.create().source(p).custom(name, wrapper)`. v2's sealed `EventRecord` closes this door.
- **How:** two options:
  - (a) **Keep the seal.** Document that third-party custom events are out of scope. If Reserv, Cauldron, etc. want audit logging, they get their own collection.
  - (b) **Add an escape hatch.** New `api/event/CustomEventRecord(..., String customEventName, Map<String, Object> payload)` that's part of the sealed list. Downstream plugins construct these directly.
  - Recommendation: (a) unless a concrete sister-plugin need materializes. Preserving the seal keeps the type system strong.
- **Priority:** P4
- **Effort:** depends on option
- **Acceptance:** decision recorded in [`gap.md`](../gap.md) or [`docs/analysis/08-api-surface.md`](../../../analysis/08-api-surface.md).

### 5.4 Harden `isWorldEditInstalled` check

- **What:** [`SpyglassPlugin:233`](../../../../plugin/src/main/java/net/medievalrp/spyglass/plugin/SpyglassPlugin.java) checks plugin presence, not enabled state.
- **How:** also check `getServer().getPluginManager().getPlugin("WorldEdit").isEnabled()`.
- **Priority:** P4
- **Effort:** S

### 5.5 Document `PageCache` 15-min TTL

- **What:** v1 had no TTL on its page store; v2 does. Change isn't a bug but is undocumented behavior difference.
- **How:** one-line note in `HelpService`'s help output, or in the main README's "Getting started" section.
- **Priority:** P4
- **Effort:** S

---

## Phase 6 — test and regression coverage

Fold these into the relevant phases above; tracked here separately so coverage gates (Block 13a: ≥90% api, ≥80% plugin) stay green.

### 6.0 Raise coverage thresholds back to target

- **What:** Phase 0 lowered both `api` and `plugin` coverage thresholds to `0.05` in [`build.gradle.kts`](../../../../build.gradle.kts) so the build could pass after wave 7 landed. Wave 7 added ~1400 lines of listener/renderer/codec code with only one new test file (`ItemSerializationTest`), and the migration deletion removed ~520 lines of tests. Net effect: plugin coverage fell from ~20% to ~10%, api coverage fell from ~15% to ~9%.
- **How:** as Phases 2-4 restore listeners and params, add unit tests alongside each. Once each phase commits, raise the thresholds by steps until reaching `api=0.90`, `plugin=0.80` per the original v1.0.0 plan Block 13a target. A reasonable ramp:
  - After Phase 2 lands: `api=0.10`, `plugin=0.10`
  - After Phase 3 lands: `api=0.25`, `plugin=0.20`
  - After Phase 4 lands: `api=0.50`, `plugin=0.40`
  - After Phase 5 lands: `api=0.90`, `plugin=0.80`
- **Priority:** P3 (track, raise incrementally)
- **Effort:** S per step (just a `build.gradle.kts` edit once tests are in place)
- **Acceptance:** `./gradlew build` green with `api=0.90, plugin=0.80`.

### 6.1 Regression cases for Phase 2 restorations

- Add a case to [`regression/cases.json`](../../../../regression/cases.json) for each restored listener:
  - server command (`/sg search a:command -g`)
  - multi-block break dependents (seed a wall torch + wall stone break, verify torch break record exists)
  - player-source ignite chain (seed ignite metadata, verify downstream break attribution)
  - clickable teleport (only testable via mineflayer live-event harness, Phase 12c from Phase 3 plan — deferred)

### 6.2 Unit tests for Phase 3 param restorations

- `MessageParamTest`, `CauseParamTest`, `ItemMaterialParamTest`, `TargetParamTest`, `IpParamTest`, `RecipientParamTest`. Each: happy path, unknown-value error, suggestions.

### 6.3 Integration test for 5.1 perf fix

- Extend `MongoRecordStoreIT.savesAndQueriesAllRecordTypes` to assert the query count (via Mongo profiler or a spy on the driver) is 1 when no event filter is set.

---

## Milestones and ship cadence

| Milestone | Phases | Tag |
|---|---|---|
| **v1.0.1** | Phase 0 + Phase 1 | correctness + working tree clean |
| **v1.1.0** | Phase 2 + Phase 3 | operator-critical restorations (console commands, multi-block breaks, ignite chains, core query params) |
| **v1.2.0** | Phase 4 | lower-priority listener restorations |
| **v1.3.0** | Phase 5 | API polish, perf fixes |

Each version bump is a `./gradlew build` green, `./gradlew regression` green, tagged release.

---

## Summary table

| # | Priority | Effort | What |
|---|---|---|---|
| 0.1 | P0 | M | Commit/revert uncommitted modifications |
| 0.2 | P0 | S | Commit migration deletions |
| 0.3 | P0 | S | Strip migration from README |
| 1.1 | P0 | S | Fix SculkListener event |
| 1.2 | P0 | S | Declare `spyglass.worldedit` perm |
| 1.3 | P0 | S | Remove Bundle debug logging |
| 1.4 | P1 | S | `crafter` vs `craft` doc alignment |
| 1.5 | P1 | S-M | Flag dead-code cleanup (+EXTENDED rendering) |
| 1.6 | P2 | L or S | Messages.java wire-in or delete |
| 1.7 | P2 | M | registerDisplayRenderer wire-in or remove |
| 2.1 | P1 | S | Console command logging |
| 2.2 | P1 | M | Clickable teleport from results |
| 2.3 | P1 | L | Multi-block break dependencies |
| 2.4 | P1 | M | Player-source ignite propagation |
| 3.1 | P1 | M | `m:` param |
| 3.2 | P1 | M | `c:` param |
| 3.3 | P1 | S | `i:`, `trg:` params |
| 3.4 | P2 | S | `ip:` param |
| 3.5 | P2 | S | `rcp:` param |
| 3.6 | P2 | S | `-nod=` flag |
| 4.1 | P2 | M | Item-frame events |
| 4.2 | P2 | M | `named` event (name tags) |
| 4.3 | P2 | S | Container close for non-shulkers |
| 4.4 | P2 | S | Dispenser drops |
| 4.5 | P3 | S | Non-player entity drops |
| 4.6 | P3 | M | Creative `clone` event |
| 4.7 | P4 | M | CraftBook sign-use (deferred) |
| 4.8 | P4 | M | Mid-runtime WE re-wire |
| 5.1 | P2 | L | Mongo query fan-out fix |
| 5.2 | P3 | L | `Source` → sealed interface |
| 5.3 | P4 | varies | Custom event extension decision |
| 5.4 | P4 | S | WE enabled-check hardening |
| 5.5 | P4 | S | Document PageCache TTL |
| 6.0 | P3 | S each | Ramp coverage thresholds back up to 0.90/0.80 |

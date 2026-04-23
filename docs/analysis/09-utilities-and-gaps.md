# v1 Dissection — 09: Utilities and Gaps

Files covered:

- **API utils** (`v1API/src/main/java/net/medievalrp/v1/api/util/`):
  `DataHelper.java`, `DateUtil.java`, `Formatter.java`, `InventoryUtil.java`,
  `PastTenseWithEnabled.java`, `TypeUtil.java`,
  `reflection/ReflectionHandler.java`.
- **Test tree** (`the v1 core/src/test/java/…`): `sg.java`,
  `DataWrapperTest.java`, `DataKeyTest.java`, `DataMapperTest.java`,
  `EntryConsumerTest.java`.
- **Build** (`build.gradle`, `v1API/build.gradle`,
  `the v1 core/build.gradle`, `settings.gradle`, `gradle.properties`,
  `the v1 core/src/main/resources/plugin.yml`).
- Cross-cutting: dead code and TODOs scattered across the rest of the tree
  (`sg`, `DataWrapper`, `EventInventoryListener`, `FAWERollbackHandler`,
  `PermissionListener`, `CommandResult`/`UseResult`, `DynamoStorageHandler`,
  `ItemDisplayHandler`, etc.).

This is the "everything that doesn't fit anywhere else" doc. Utility classes that
callers reach for but nobody owns, the test harness that isn't really testing
anything, the bag of half-implemented features that a 1.0 ship would need to
resolve one way or the other, and the build/distribution choices that make a
clean check-out fail in interesting ways. Read it as a punch list of the things
we would hit before shipping a paid release.

## Responsibility of the utils layer

`net.medievalrp.v1.api.util` is the grab-bag. Seven top-level files and
one nested package. Three distinct kinds of helpers collected under one
namespace:

1. **Serialization glue** — `DataHelper` (the `DataWrapper` ↔
   `ConfigurationSerializable` ↔ `ItemStack` bridge with a recently-added
   `dataWrapperToMap` path for 1.21 component maps), plus the regex compiler
   for query predicates.
2. **Conversion / parsing primitives** — `DateUtil` (shorthand like `4w`/`3d`/`1h`
   → `Date`), `TypeUtil.pregMatchAll` (PHP-style regex scan used only by
   `DateUtil`), `InventoryUtil.identifyTransactions` (the inventory-diff
   algorithm that turns one Bukkit click into zero-or-many typed
   `InventoryTransaction` records).
3. **Rendering helpers** — `Formatter` (string + legacy `ChatColor` helpers for
   prefixes, error banners, page headers), `DataHelper.buildLocation` (emits
   the clickable `BaseComponent[]` teleport link).

Plus `PastTenseWithEnabled` (a two-field struct backing the registrar's event
table) and `reflection/ReflectionHandler` (raw NMS reflection for entity-NBT
round-trips). These two are structurally different — one's a DTO, the other's
low-level glue — but they ended up in `util` because they had nowhere better
to sit.

Nothing in this package has an owning subsystem. Every util is called from
three or four places and mutated whenever someone needs a new helper. That's
fine for a fast-moving codebase but it's the first thing to bite when you try
to extract the API module for separate publication: `DataHelper` pulls in
`net.md_5.bungee.api.chat.*` for a single render method, so any consumer that
imports `v1API` drags the bungee chat API with them.

## Files

### `DataHelper.java`

The most load-bearing util in the plugin. 178 lines, eight public methods, no
state. Every method is static.

```
isPrimitiveType(Object) → boolean
getBlockDataFromWrapper(DataWrapper) → Optional<BlockData>
getLocationFromDataWrapper(DataWrapper) → Optional<Location>
unwrapConfigSerializable(DataWrapper) → T extends ConfigurationSerializable
dataWrapperToMap(DataWrapper) → Map<String, Object> [private, added this session]
unwrapValue(Object) → Object [private, added this session]
unwrapAsNeeded(Collection) → Collection<?>
unwrapAsNeeded(Map) → Map<?, ?>
convertArrayToMap(ConfigurationSerializable[]) → Map<Integer, ConfigurationSerializable>
buildLocation(Location, boolean) → BaseComponent[]
compileUserInput(String) → Pattern
writeLocationToString(Location) → String
```

Notes by method:

- **`unwrapConfigSerializable`** (`DataHelper.java:69-94`). The read side of the
  `ConfigurationSerialization` round-trip. Reads `CONFIG_CLASS` off the
  wrapper, looks up the aliased class via
  `ConfigurationSerialization.getClassByAlias`, recursively unwraps children
  to a `Map<String, Object>` via `dataWrapperToMap`, calls
  `ConfigurationSerialization.deserializeObject`. Returns null on any failure
  with warning-logging via `sg.warning`. This is the only way rollback /
  restore ever reconstructs an `ItemStack`, `ItemMeta`, `BannerPattern`, etc.
- **`dataWrapperToMap` + `unwrapValue`** (`DataHelper.java:104-125`). The fix
  for the `components=null` bug. Before this session, the recursion treated
  every nested `DataWrapper` as an encoded `ConfigurationSerializable` —
  calling `unwrapConfigSerializable` on it and expecting a non-null result. But
  the `ItemStack.components` sub-tree in Paper 1.21+ is a plain
  `Map<String, Object>`, not a `ConfigurationSerializable`, so it round-tripped
  into a wrapper *without* a `CONFIG_CLASS` tag. The unwrap returned null, the
  deserializer silently dropped the key, and `components` came back as null on
  every rolled-back item. `unwrapValue` now branches on whether
  `CONFIG_CLASS` is present — if yes, recurse via
  `unwrapConfigSerializable`; if no, treat the wrapper as a raw nested map.
  See `02-data-and-storage.md` §"components=null bug lineage" for the full
  story.
- **`getLocationFromDataWrapper`** (`DataHelper.java:51-67`). Pulls
  `LOCATION.X/Y/Z/WORLD` and reconstructs a Bukkit `Location`. `WORLD` is a
  UUID string; `Bukkit.getWorld(UUID.fromString(...))` returns null if the
  world is gone (rename or delete), at which point the method silently returns
  `Optional.empty()` — one of the observed failure modes noted in
  `02-data-and-storage.md`.
- **`buildLocation`** (`DataHelper.java:160-168`). Builds the clickable
  `BaseComponent[]` used in search-result hovers: "(x: 1 y: 64 z: -12 world:
  world)" with a hover "Click to teleport" and a `ClickEvent.RUN_COMMAND` of
  `/sgtele <worldUuid> <x> <y> <z>`. This is the entry point for the
  `/sgtele` attack surface described in `05-commands-and-display.md` §"The
  `/sgtele` command is exposed." The method is also the only reason
  `DataHelper` imports `net.md_5.bungee.api.chat.*`, which in turn forces
  every consumer of the API module to depend on the bungee chat API.
- **`compileUserInput`** (`DataHelper.java:170-173`). One line of regex
  escapes + `*` → `.*` expansion, producing a `Pattern` from user-typed
  search values. The escaping set is
  `[-.\+*?\[^\]$(){}=!<>|:\\\\]` — note `:` and `|` and `<>` are escaped
  inside a character class where they don't need to be, and `\\\\` at the end
  is a Java-source-level four-backslash literal that survives as two
  backslashes in the regex, which then matches a single backslash. It works
  but is over-escaped and hard to audit.
- **`writeLocationToString`** (`DataHelper.java:175-177`). "(X: 1, Y: 64, Z:
  -12)". One call site (`EventBreakListener.getStyle` for debug logging).
  Trivial.
- **`convertArrayToMap`** (`DataHelper.java:149-158`). Takes a sparse
  `ConfigurationSerializable[]` (e.g. an inventory slot array) and returns the
  non-null entries keyed by index. Used by `DataWrapper.setInventory`. Worth
  keeping.
- **`isPrimitiveType`** (`DataHelper.java:29-39`). Returns true for the nine
  boxed primitives plus `String`. Used by `DataWrapper.set` to decide which
  branch to take. Predates pattern matching for `instanceof`; a
  `switch(obj)` with pattern cases would be two lines.

### `DateUtil.java`

The cleanest util in the bag. 123 lines. `parseTimeStringToDate(String, boolean)`
parses a relative-time shorthand like `4w3d12h5m30s` into a `Date`, with the
`future` flag controlling sign. Uses `Calendar.add` internally so daylight
savings / month-boundary behavior is correct. `getTimeSince(Date)` produces
human-friendly deltas like "3d 4h ago" / "just now" for the search-result
renderer.

One wart: the supported units are `s`, `m`, `h`, `d`, `w`. No `mo` (month), no
`y` (year). `storage.expireRecords: 4w` (the default config value) is the one
production config knob that feeds through this, and four weeks is about as big
as any real retention gets, so it doesn't bite. But if someone wants "keep for
a year" they have to write `52w`.

Second wart: the MIT-license header from Prism (Helion3, 2015) at the top
(`DateUtil.java:1-23`) is preserved. That's correct attribution and we should
keep it — but we should also audit the other files lifted from Prism to make
sure their headers are likewise preserved. Skim of the codebase suggests this
is the only file with a carried-over third-party header.

`getTimeSince` has a minor UX bug: if the delta is exactly 1 minute it
falls into the `<= 1` branch and returns "just now" (`DateUtil.java:70`).
Users might expect "1m ago." Edge case, ignorable.

The `TypeUtil.pregMatchAll` helper in the sibling file exists *only* for
`DateUtil` — it scans `4w3d` into an array `["4w", "3d"]`. The
implementation concatenates matches with `"~"` and splits — which is both
silly (`new Matcher(...).results().map(MatchResult::group).toArray(String[]::new)`
is one line in modern Java) and unsafe if a user input contains `~`. In
`DateUtil`'s actual usage it can't, because the outer pattern `[0-9]+(s|h|m|d|w)`
won't match a tilde. But `TypeUtil.pregMatchAll` is a *public* method exposed
from the API module, so any future caller is a landmine.

### `Formatter.java`

Thirty-six lines. Nine static helpers that return `String` with embedded
`org.bukkit.ChatColor` codes:

```
getPageHeader(page, max) → "«v1» §7((Page 1/3))"
subHeader(text) → "§8§otext§r"
success(text) → "«v1» §atext"
error(text) → "«v1» §c (Error) §7text§r"
prefix() → "«§bv1§a»" [public]
bonus(text) → "§7text"
formatPrimaryMessage(msg) → "§bmsg"
formatSecondaryMessage(msg) → "§amsg"
p(), s() (colour helpers, private)
```

This is the third chat-API surface in the plugin (bungee `BaseComponent` in
`SearchCallback` + `DataHelper.buildLocation`; legacy `org.bukkit.ChatColor`
here; Paper's native Adventure `Component` — used nowhere). Every call site
that wants an "error" message concatenates `Formatter.error("foo")` with
whatever, and the result is a legacy-coloured `String`. Porting this file is
the cleanest way to start on the Adventure migration — but doing so means
every caller needs to be updated because the return type has to change.

No validation on the `prefix` literal — the text `«v1»` is hard-coded
at `Formatter.java:24`. No i18n, no theming, no way for server ops to rename
the plugin's prefix.

### `InventoryUtil.java`

Four hundred thirty-seven lines. The single ugliest class in the plugin, with
a javadoc that volunteers how ugly it is (`EventInventoryListener.java:32-38`:
"If this looks like a monster, it is … God won't suddenly decide we've gone
too far and annihilate humanity"). Two public static methods, each called
`identifyTransactions`, one per Bukkit event type:

- `identifyTransactions(InventoryClickEvent)` — a 14-branch `switch` on
  `e.getAction()` (`NOTHING`, `PICKUP_ALL`, `PICKUP_SOME`, `PICKUP_HALF`,
  `PICKUP_ONE`, `PLACE_SOME`, `PLACE_ALL`, `PLACE_ONE`, `SWAP_WITH_CURSOR`,
  `DROP_ALL_CURSOR`, `DROP_ONE_CURSOR`, `DROP_ALL_SLOT`, `DROP_ONE_SLOT`,
  `MOVE_TO_OTHER_INVENTORY`, `HOTBAR_MOVE_AND_READD`, `HOTBAR_SWAP`,
  `CLONE_STACK`, `COLLECT_TO_CURSOR`, `UNKNOWN`) that emits zero-or-many
  `InventoryTransaction<ItemStack>` records. Each branch reconstructs before /
  after stacks by cloning and mutating copies of the Bukkit objects.
- `identifyTransactions(InventoryDragEvent)` — the same idea for the drag
  path, much shorter, but the shape is different: it iterates
  `e.getNewItems()` and diffs each against `inventory.getItem(slot)` to
  compute deposits.

Plus two private nested classes (`ItemWrapper`, `ItemTransaction`) used only
in the `MOVE_TO_OTHER_INVENTORY` and `COLLECT_TO_CURSOR` branches. Identical
nested classes also exist in `EventInventoryListener` itself
(`EventInventoryListener.java:114-192`) — they are 100 % duplicated, byte-for-
byte. The duplication looks like a refactoring left in place mid-move: the
listener was once the logic holder, the logic moved to the util, but the
nested classes were copied rather than extracted. Fix: delete the
listener-side copies.

This file is where the actual interesting state-of-the-world reconstruction
happens. Every container event downstream trusts that the transactions
returned here are correct, including rollback. A bug in any one branch
silently produces wrong audit trails. There is zero test coverage for this
file (see Test Coverage below), which is the most load-bearing coverage gap
in the plugin.

One inline `//TODO` on line 93 (`PICKUP_ONE`): "we need to verify that this
isnt called when the itemstack in the players hand is @ max capacity." The
comment hasn't been resolved; if the player has a full hand and picks up one
more of the same type, the code may emit a `WITHDRAW` that the rollback
layer then "restores" into an already-full slot. Unverified.

### `PastTenseWithEnabled.java`

Nineteen lines. A tiny DTO with two fields: `String pastTense`, `boolean
enabled`. Used exclusively by `sg.eventMapping: Map<String,
PastTenseWithEnabled>`. The `enabled` field is mutable via the registrar
(`sg` toggles `enabled` when events are registered /
disabled). Being mutable-with-no-setter is a red flag — the only way to
mutate is reflection or direct field access from the same package, which is
how the registrar does it.

In v2: this becomes a `record EventRegistration(String pastTense, boolean
enabled)` and the registrar replaces the entry instead of mutating it.
Three-line file gone.

### `TypeUtil.java`

Twenty-six lines. Single method `pregMatchAll(Pattern, String)`. Used only by
`DateUtil.parseTimeStringToDate`. Explicitly attributed to
`https://github.com/raimonbosch` in the javadoc. The implementation is weird
(concat with `~` separator, split on `~`); modern Java has
`Matcher.results()` → `Stream<MatchResult>` as of JDK 9, so this is a
~one-line replacement. Inline into `DateUtil` and delete the file.

### `reflection/ReflectionHandler.java`

Two hundred twenty lines. This is the other "dragons here" file in the
plugin. Pure reflective NMS access to:

- Save an entity to compressed NBT (`getEntityAsBytes` → base64 string).
- Load a saved NBT back onto a spawned entity (`loadEntityFromNBT`).
- Save an `ItemStack` to NBT (`getItemJson` → stringified compound).

All three rely on reflective discovery of NMS methods at `static` init time,
walking the NMS method table for ones that match "takes an `NBTTagCompound`,
returns `void`" / "takes an `NBTTagCompound`, returns an `NBTTagCompound`" /
etc. (`ReflectionHandler.java:64-147`).

**Versioning.** The file does handle Paper's 1.17+ drop of versioned
packages: it parses `Bukkit.getServer().getClass().getPackage().getName()`
and falls through to an unversioned `org.bukkit.craftbukkit.` if the split
returns fewer than four parts (`ReflectionHandler.java:42-54`). Good
defensive coding.

**Silent failure.** If reflection setup fails, the static block swallows the
throwable (`ReflectionHandler.java:149-151`) and leaves `initialized = false`.
Every public method guards with `if (!initialized) return null;` — so if Paper
renames a method between versions, entity capture silently returns null and
the `EventDeathListener`'s attempt to persist entity state quietly drops it.
The JIT could be told with a `Log` here — one line of "v1 entity
reflection failed to initialize, entity-capture/restore disabled" — and the
behaviour would be the same, just observably broken instead of silently.

**Entity resurrect on UNSAFE code.** `loadEntityFromNBT`'s comment
(`ReflectionHandler.java:188-193`) is honest: it UUID-stamps the compound to
avoid "same uuid entities cause fucking problems" and Health-stamps the
compound to avoid the entity spawning already dead. Still, reflectively
loading into an NMS entity is the sort of thing that breaks every time
Mojang renames a field. The `EntityEntry.rollback()` (`06-rollback.md`) is
the only caller. In practice it's a "best effort" feature.

**Age.** "A class I basically stitched together from Sporadic's code. -
501warhead" (`ReflectionHandler.java:20-23`). This has been in the codebase
since the Sponge-era predecessor. It works but it shows its years.

## Hot spot: `DataHelper.unwrapConfigSerializable` and the `components=null` saga

Covered in passing in `02-data-and-storage.md`; the mechanics matter here too
because `DataHelper` is the rescuing layer.

The problem, in one sentence: `DataWrapper.ofConfig` writes every
`ConfigurationSerializable` as a sub-wrapper tagged with `CONFIG_CLASS`, and
writes every `Map` as a sub-wrapper **without** that tag; `ItemStack` in 1.21
has a `components` field of type `Map<String, Object>` (not
`ConfigurationSerializable`), so that sub-tree went through the Map branch and
was stored as an untagged wrapper. On read-back,
`unwrapConfigSerializable` was called on the outer `ItemStack` wrapper; it
walked children expecting each to be either a primitive or another
`CONFIG_CLASS`-tagged wrapper; when it hit `components` it called
`unwrapConfigSerializable(components)` recursively, found no `CONFIG_CLASS`,
returned `null`, and Bukkit's `deserializeObject` received a config map with
`components=null`. Items rolled back with no custom model data, no custom
lore, no anything in `components`.

The fix, added this session, is `DataHelper.dataWrapperToMap` +
`unwrapValue`: when walking children of a config map, check whether the
nested wrapper carries `CONFIG_CLASS` — if yes, recurse as a serializable; if
no, treat as a nested raw Map. This is a surgical patch on top of a bad
abstraction: `DataWrapper` still conflates "tagged serializable" and "plain
Map" at the same wrapper type, and every new 1.x component field that
surfaces as a plain Map (there will be more as Minecraft keeps adding
`minecraft:*` data-component kinds) will hit the same class of bug. The only
thing stopping a repeat is that `dataWrapperToMap` treats *any* untagged
wrapper as a raw Map, which is accidentally correct for the general shape but
wrong if someone introduces a custom serializable and forgets to register it
with `ConfigurationSerialization.registerClass`.

Lines of interest:

- `DataHelper.java:69-94` — the deserializer that rose to the problem.
- `DataHelper.java:104-125` — the fix, both methods.
- `DataHelper.java:115` — the branch: `if (dw.getString(CONFIG_CLASS).isPresent())`.
- `DataHelper.java:118` — the new recursion point: `return dataWrapperToMap(dw)`.

If this file is extracted into a new v2 POJO-codec path, the
`unwrapConfigSerializable` entry point disappears and the entire saga
collapses into `Document → record` with the Mongo driver's codec registry.
The hot spot exists because the codebase ingests via
`ConfigurationSerialization` + flattens into a bespoke tree + re-ingests via
`Document` — three adapters where one would do. See `02-data-and-storage.md`
modernization §1.

## Test coverage

Five test files exist. Total `@Test` methods: **seven**. Of those:

| File | `@Test` methods | State |
|---|---|---|
| `sg.java` | 3 (`onEnable`, `onLoad`, `onDisable`) | 2 are empty-body, 1 calls `core.onLoad(mock)` (no-op) |
| `DataKeyTest.java` | 1 (`testGetter_Setter`) | Trivial — `DataKey.of("test").toString().equals("test")` |
| `DataWrapperTest.java` | 0 | Entire file is commented-out between `/* ... */` at `DataWrapperTest.java:4-87` |
| `DataMapperTest.java` | 2 (`register`, `mapToEntry`) | Both empty-body |
| `EntryConsumerTest.java` | 1 (`run`) | Empty-body |

That is: **one real test** (the `DataKey.of(…).toString()` equality check).
Six other `@Test` annotations exist on empty methods that pass vacuously.

Details:

- **`sg`** (`sg.java:1-96`) has Mockito mocks set up for
  every `v1` lifecycle dependency — `PluginCommand`, `PluginManager`,
  `Server`, `BukkitScheduler`, `Logger`, `StorageHandler` — but all of
  `@Before setup()` is commented out (`sg.java:48-55`). The three
  test methods instantiate `new sg()` and then either do nothing or call
  the no-op `onLoad`. No assertions. `onEnable` test has its only real call
  commented out (`sg.java:61`: `//core.onEnable(v1,
  scheduler);`). The test class is a scaffold for a suite that was never
  built.
- **`DataWrapperTest`** (`DataWrapperTest.java:1-88`) is the big one: it had
  about thirty lines of actual test setup with mocked `World`, `ItemStack`,
  `Entity`, `BlockState`, `BlockData`, and two tests (`get`, `getKeys`) that
  exercise real `DataWrapper` round-trips — then the whole thing was commented
  out at some point. The tests *would* catch obvious regressions in
  `DataWrapper.set` / `get` behavior if uncommented. The comments-inside-
  comments are a red flag for "we changed an API and this stopped compiling
  and nobody re-fixed it." Probably referring to the `DataWrapper.of(entity)`
  signature — which still exists — so the stated reason might be stale. Worth
  uncommenting and seeing what breaks.
- **`DataMapperTest`**, **`EntryConsumerTest`** (`DataMapperTest.java:1-14`,
  `EntryConsumerTest.java:1-10`) are skeleton placeholders. No setup, no
  assertions, no logic. Presumably created when some abstraction called
  `DataMapper` / `EntryConsumer` existed; neither class exists in the current
  tree. The files are orphan tombstones.

**What's NOT tested**, i.e. the entire rest of the plugin:

- `InventoryUtil.identifyTransactions` — the most complex 400-line state
  machine in the codebase. Zero tests. Regressions silently corrupt audit
  trails. High-value testing target.
- `DateUtil.parseTimeStringToDate` — pure function, cheap to test, has
  edge cases around `w`/`d`/`h`/`m`/`s` combinations. Zero tests.
- `DataHelper.unwrapConfigSerializable` — the hot spot we fixed this session.
  Zero tests. A regression on `components=null` would not be caught.
- `MongoRecordHandler.buildConditions` — the query-to-BSON translation.
  Pure function of `(List<SearchCondition>, Flag) → Document`, ideal unit
  test target. Zero tests.
- Every parameter / flag handler. Every display handler. Every listener
  extract-transform block. `OEntry` builder chain. `DataEntry.from`
  reflective factory. `FlagWorldEditSel` WE cuboid → X/Y/Z range
  translation. `SearchCallback.buildComponent` render path. Etc.

The test harness declares `org.mockito:mockito-core:4.6.1`, `junit:junit:4.12`,
and `org.spigotmc:spigot-api:1.21.8-R0.1-SNAPSHOT` as testImplementation
dependencies (`the v1 core/build.gradle:15-17`), which is all we'd need
to build out a proper unit test suite. The infrastructure is there; it just
hasn't been used.

**Ship-blocker assessment.** For a paid 1.0 release, zero meaningful unit
coverage is below the acceptable floor. Critical subsystems to cover first:

1. `InventoryUtil.identifyTransactions` — both overloads, all 14+ Bukkit
   actions, with synthetic events.
2. `DataHelper.unwrapConfigSerializable` round-trip — Banner, ItemStack
   (simple + 1.21 components), BlockData, ItemMeta with enchants.
3. `DateUtil.parseTimeStringToDate` — all five unit suffixes, combinations,
   invalid input, future flag.
4. `MongoRecordHandler.buildConditions` / `documentToDataWrapper` /
   `documentFromDataWrapper` — BSON round-trip.
5. `SearchParameterHelper.suggestParameterCompletion` — tab completion
   branch coverage.

JUnit 4 → JUnit 5 upgrade is a nice-to-have. Mockito 4.6 is fine; AssertJ
would make assertions more legible. The existing `DataWrapperTest` can be
uncommented and used as a seed.

## Dead code inventory

Things that have no call site, no meaningful implementation, or are wired to
be called but with empty bodies. Listed in rough order of how much they'd
surprise a new reader.

### Core plugin dead bits

- **`sg.onEnable` — two empty integration `if` blocks**
  (`sg.java:79-87`). Both check a config flag × plugin-enabled
  condition for FastAsyncWorldEdit and WorldEdit respectively, then do
  nothing inside. The WE wiring actually happens via `registerFlags()`
  calling `onWorldEditStatusChange(true)` (`sg.java:154-156`). Toggling
  `integration.worldEdit: false` does not disable WE integration because the
  check is in the wrong code path. Covered in `01-core-lifecycle.md` §
  "Pain points #1".
- **`sg.onCraftBookStatusChange(boolean)`** (`sg.java:172-174`):
  method body is the single line `//TODO turn off craft book related events
  if craftbook isnt on the server`. Called from nowhere — grep turns up zero
  call sites. The `PluginInteractionListener` only calls
  `onWorldEditStatusChange`; the CraftBook branch was stubbed and never
  finished. Intent was symmetry with WE hot-reload; implementation never
  landed.
- **`sg.onDisable`** (`sg.java:95-97`): empty body. No storage
  shutdown, no queue flush, no teardown of the scheduler handle. Relies on
  JVM / Bukkit cleanup. Covered in `01-core-lifecycle.md` § "Pain points
  #8".
- **`sg.onLoad`** (`sg.java:92-93`): empty body. Probably
  intentional — no load-time work needed — but explicitly adding a
  `// no-op` comment would communicate intent.

### Listener dead bits

- **`EventInventoryListener.onInventoryDrag` still misses `.save()`**
  (`EventInventoryListener.java:84`): confirmed extant. The `DEPOSIT` case
  builds an `OEntry` via `OEntry.create().player(...).deposited(...)` and
  drops the result on the floor — no `.save()` call, so the event is never
  enqueued. First flagged by another agent; re-verified this session. Live
  data-loss bug on inventory-drag deposits. One-line fix.
- **`EventInventoryListener.debugEvent(InventoryClickEvent)`**
  (`EventInventoryListener.java:100-112`): a 13-line debug dump method.
  Private. Called from nowhere. Left in for next time someone needs to debug
  inventory transactions, but the convention-violating part is that similar
  debug paths in other listeners are gated on `v1.logDebug` — this
  one just sits there. Either delete or promote to `@EventHandler(priority
  = LOWEST)` gated on `sg.isDebugEnabled()`.
- **`EventInventoryListener.ItemWrapper` + `ItemTransaction`** nested classes
  (`EventInventoryListener.java:114-192`): byte-for-byte duplicates of the
  `InventoryUtil.ItemWrapper` / `ItemTransaction` private nested classes
  (`InventoryUtil.java:357-435`). Zero references from the outer class. Left
  behind when the transaction-identification logic was extracted to
  `InventoryUtil`. Delete.
- **`PermissionListener`** (`PermissionListener.java:1-23`): `@EventHandler
  public void onPlayerCommandSend(PlayerCommandSendEvent e) {}` — an empty
  method body. Registered nowhere (`grep PermissionListener` turns up only
  the file itself and the analysis docs). Two unused fields
  (`v1`, `pluginCommand`). Entirely dead. Flagged in
  `03-events-and-entries.md`. Delete.
- **`EventIgniteListener.java:26`** `//TODO we need to track this... but
  it's complex.` The listener otherwise works, but the author knew a
  follow-up (probably: attribute cascading explosions to the igniting
  player) was unfinished and left a note.

### WorldEdit integration dead bits

- **`FAWERollbackHandler`** (`FAWERollbackHandler.java:32-215`): 215 lines of
  batch rollback logic using FAWE's EditSession. `batchRollback` /
  `batchRestore` are public static methods that are never called — grep the
  tree, only the file itself and the analysis docs mention them. The
  `BlockEntry.rollback()` path does per-block Bukkit-native rollback
  (`06-rollback.md`); the FAWE-accelerated path was written, ostensibly
  tested, and never wired into the commands. `isFaweAvailable()` / the
  static-init block around `Class.forName("com.sk89q.worldedit.WorldEdit")`
  also exist for a gate check nobody is gating. Either wire it up (the
  clean path is a flag on `/sg rollback`: `-we` or `--batch` to use FAWE
  when available) or delete the file.
- **`api.interfaces.WorldEditHandler`** (`WorldEditHandler.java:1-8`): API
  interface with two methods, `enableWorldEditLogging()` /
  `disableWorldEditLogging()`. Registered via
  `sg.registerWorldEditHandler` (`sg.java:114-115`), stored into
  `sg.worldEditHandler` (`sg.java:322-324`), and then
  **never read**. The field is write-only. No code ever pulls it out and
  invokes `enableWorldEditLogging()`. The WorldEdit logging is instead
  orchestrated by `sg.onWorldEditStatusChange` +
  `WorldEditLogger.register/unregister`, which doesn't go through the
  handler at all. The `WorldEditHandler` interface was presumably the
  extension point so external plugins could swap out the WE wiring; the
  swap-in mechanic exists but the swap-out call doesn't, so registering a
  handler has zero effect.
- **`FaweTileCapture.java:115-116`**: the single empty-catch block in the
  plugin (`} catch (Exception ignored) {}`). Swallowing the exception is
  probably fine here (the method is collecting best-effort NBT from a FAWE
  tile capture), but the convention should be `// ignored: tile read is
  best-effort` or at minimum `ignored.printStackTrace()` at debug level.

### Command layer dead bits

- **`command/result/CommandResult.java`** and
  **`command/result/UseResult.java`**: neither referenced outside the
  analysis docs and each other. Covered in `05-commands-and-display.md` §
  "`CommandResult` and `UseResult` are dead." Post-Cloud migration these
  return-shape types have no callers. Delete both files and the enclosing
  package.
- **`AsyncCallback`** (`command/async/AsyncCallback.java:1-14`): interface
  with one implementation (`SearchCallback`). `05-commands-and-display.md` §
  "`AsyncCallback` has one implementation." Inline or keep as a sealed
  interface with `permits SearchCallback`.
- **`AiHandler.reloadPrompt()`** (`AiHandler.java:60-62`): public method,
  called from nowhere. Presumably intended for a `/sg ai reload-prompt`
  subcommand that never shipped. Either wire it up (it's one line to add to
  `SpyglassCommands.register`) or delete.

### API surface dead bits

- **`ServerParameter`** (`v1API/…/api/parameter/ServerParameter.java`):
  registered parameter with two `//TODO` comments at lines 57 and 66 — "implement
  server resolution when multi-server support is added" and "implement server
  discovery when multi-server support is added." `getAvailableServers()`
  returns `List.of()`, `resolveServerId` just lowercases the input. Covered
  in `04-query-dsl.md`. The parameter is registered and accepts values, but
  the values never resolve to real targets. Keep if multi-server is on the
  roadmap, otherwise delete.
- **`DataWrapper.java:56`** `//TODO We'll need a way to parse this. Return
  later when we know wtf this looks like.` (inside `ofBlock`, referring to
  the Bukkit `BlockData` string that `ofBlock` stores via
  `block.getBlockData().getAsString()`). Parsing happens on the read side
  via `Bukkit.createBlockData(String)` in
  `DataHelper.getBlockDataFromWrapper`. The TODO is stale; the round-trip
  actually works. Remove the comment.

### Dynamo backend

Entire package. Covered in detail in `02-data-and-storage.md` § "Pain points
#5." Executive summary: `DynamoStorageHandler.connect()` returns `false`
with `withRegion("@TODO")`, `DynamoRecordHandler.write()` builds `Item`
objects and discards them, `query()` returns `null`. No user could be using
it. Either delete or finish. Leaning delete.

### Summary table

| Thing | File:line | Recommendation |
|---|---|---|
| Empty WE/FAWE integration ifs | `sg.java:79-87` | Delete or wire to toggle |
| Empty `onCraftBookStatusChange` | `sg.java:172-174` | Implement or delete |
| Empty `onDisable` | `sg.java:95-97` | Add queue flush |
| Missing `.save()` in drag handler | `EventInventoryListener.java:84` | One-line fix |
| Unused `debugEvent` | `EventInventoryListener.java:100-112` | Delete or gate |
| Duplicate nested classes | `EventInventoryListener.java:114-192` | Delete (exist in `InventoryUtil`) |
| `PermissionListener` | whole file | Delete |
| `FAWERollbackHandler` | whole file | Wire up or delete |
| `WorldEditHandler` interface | never-read field | Either wire or drop interface |
| `CommandResult`, `UseResult` | whole files | Delete |
| `AiHandler.reloadPrompt` | `AiHandler.java:60-62` | Wire to a reload subcommand |
| `AsyncCallback` | one impl | Sealed or inline |
| `ServerParameter` stubs | whole file | Implement or delete |
| `DataWrapper.ofBlock` TODO | `DataWrapper.java:56` | Delete stale comment |
| `DynamoDB` backend | `io/dynamo/*` | Delete |

## TODO inventory

Exactly seven literal `TODO` / `FIXME` markers in the source tree (docs
excluded):

1. `DataWrapper.java:56` — stale; the BlockData string parses fine via
   `createBlockData`. Safe to delete.
2. `ServerParameter.java:57` — `// TODO: implement server resolution when
   multi-server support is added`. Multi-server feature never shipped.
   Indicative of the abandoned BungeeCord / Velocity forwarding dream;
   either commit to it or cut.
3. `ServerParameter.java:66` — `// TODO: implement server discovery when
   multi-server support is added`. Same.
4. `InventoryUtil.java:93` — `//TODO we need to verify that this isnt called
   when the itemstack in the players hand is @ max capacity`. Actual bug
   risk, unverified. Should be either turned into a test or confirmed
   manually + comment removed.
5. `sg.java:173` — `//TODO turn off craft book related events if
   craftbook isnt on the server`. Inside the empty
   `onCraftBookStatusChange`. Never implemented.
6. `EventIgniteListener.java:26` — `//TODO we need to track this... but
   it's complex.` (probably about cascading ignition → explosion attribution
   to the source player). Punt accepted.
7. `DynamoStorageHandler.java:17` — `.withRegion("@TODO")`. Extreme form of
   TODO: the literal string `@TODO` is what the AWS SDK receives as the
   region name. Fails at connect time. Covered above.

No `FIXME`, `XXX`, or `HACK` markers anywhere in the tree — a narrower
convention than most codebases, but means the `TODO`s above are the sole
in-line signal the author left.

No `@Deprecated` anywhere in the tree, despite the plugin's heavy use of
legacy bungee chat + Bukkit `ChatColor` APIs that are formally deprecated
upstream. When we port to Adventure, we should `@Deprecated` the intermediate
helpers (`Formatter.error(String) → String`) before we delete them, so
consumers using the API module get a compile-time nudge.

## Build and distribution gaps

### `libs/FastAsyncWorldEdit.jar` is gitignored

`the v1 core/build.gradle:13`:

```
compileOnly files("$projectDir/libs/FastAsyncWorldEdit.jar")
```

And `.gitignore`:

```
# Compile-only local jars (e.g. FAWE.jar referenced by the v1 core/build.gradle).
# Dev must drop a FAWE jar in the v1 core/libs/ locally or swap to a maven dep.
the v1 core/libs/
```

Consequences:

- **A fresh clone won't compile.** `./gradlew build` fails on day one
  because the jar reference resolves to a non-existent path. The error is
  Gradle's usual opaque "could not find" — a new dev has to read the comment
  in `.gitignore` to know what to do.
- **No reproducible builds.** Every developer has whatever FAWE version they
  happened to drop in `libs/`. Nothing pins the version. A bug that only
  manifests on FAWE 2.9.x vs 2.10.x would be untraceable from the version
  control history.
- **No CI.** Any automated build (GitHub Actions, Jenkins, whatever) would
  need a private step that downloads the FAWE jar from somewhere and places
  it. Depending on FAWE's licensing (GPL-3 for the IntelliJ plugin / AGPL
  for the FAWE core itself), bundling that jar into a CI secret or build
  artifact has legal implications.

FAWE is not published on Maven Central (or at least wasn't when this code
was written; they've since become more accessible via JitPack). Options:

1. **Add Maven coordinates.** `compileOnly
   "com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:2.11.0"` via the
   EngineHub Maven repo, which is already configured in the root
   `build.gradle:24`. Last time I checked EngineHub's repo didn't actually
   publish FAWE (only stock WE), so this might need JitPack: `maven { url
   'https://jitpack.io' }` + `compileOnly
   'com.github.IntellectualSites:FastAsyncWorldEdit:2.11.0'`.
2. **Commit the jar.** Bad form — 15 MB blob in git history — but
   unambiguous.
3. **Gradle download-dependencies task.** A bootstrap script that downloads
   the jar on first build. Adds moving parts.

Option 1 is the right answer. Until then, every clean build is broken.

### `plugin.yml` website URL points to a personal fork

`the v1 core/src/main/resources/plugin.yml:7`:

```yaml
website: https://github.com/itdontmatta/v1
```

That's `github.com/itdontmatta`, not `github.com/medievalrp-net`. The
MedievalRP infrastructure memo
(`~/.claude/projects/-Volumes-External-NVME-Documents-GitHub-MedievalRP/memory/repo_layout.md`)
notes that all plugin repos live under `medievalrp-net`; this URL either
points at an old personal fork or (if the fork doesn't exist) at a 404. For
a commercial release, `website` should resolve to whatever support/download
page we want players to hit. `https://medievalrp.net/plugins/v1`
or `https://github.com/medievalrp-net/v1` are the natural
candidates.

### `plugin.yml` `softdepend` omits CraftBook plugin correctness

The file lists `softdepend: [WorldEdit, FastAsyncWorldEdit, CraftBook]`
(`plugin.yml:4`). That's fine. But the code already silently guards against
CraftBook not being enabled (via
`Bukkit.getPluginManager().isPluginEnabled("CraftBook")` at
`sg.java:118`), so in practice the soft-depend is defensive only.

### Single authors field

`authors: [itdontmatta]`. For a paid release this is usually a list — or
points to a team / org account.

### Version string `V0.1-Alpha`

`build.gradle:7`:

```groovy
version = 'V0.1-Alpha'
```

Problems:

1. **Non-semver.** "V0.1-Alpha" is not a valid semantic version. Consumers
   (Bukkit, plugin managers like Polymart, Spiget's parser) that sort by
   version get undefined behavior. Should be `0.1.0-alpha.1` or similar.
2. **Hard-coded.** No way to cut a release from git without editing
   build.gradle. A CI setup that drives versions from git tags
   (`git describe`) is the norm.
3. **Expanded into plugin.yml.** `processResources` does `expand(tokens)`
   to inject `${version}` into `plugin.yml` (`the v1 core/build.gradle:
   21-27`). This side works, but if `build.gradle` is stale the plugin.yml
   reads stale too.

### Gradle toolchain / version

Gradle 9.4.1 (per the overview), JDK 21. Gradle 9 is recent enough; no
pressing modernization issue. One inconsistency: root `build.gradle:7`
declares `version = 'V0.1-Alpha'` inside `allprojects` but the
`v1API/build.gradle` is a `java-library` project with no publish
configuration, so the API jar can't be deployed independently of the Core.
If we want to publish the API module to Maven Central or EngineHub
separately (for third-party plugin consumers), we need a `maven-publish`
plugin + a POM + group/version metadata.

### `settings.gradle` + `foojay-resolver-convention`

`settings.gradle:1-3` uses `foojay-resolver-convention 0.9.0`. Fine.
`rootProject.name = 'v1'`. Fine.

### No `gradle.lockfile`

No dependency locking. Every build resolves against latest-available
snapshots of Paper (`1.21.8-R0.1-SNAPSHOT`), meaning a clean build can
pick up a different Paper API than the previous build. For a release
pipeline we want `dependencyLocking` enabled so the build is bit-for-bit
reproducible from `./gradlew build`.

### No CI config

No `.github/workflows/`, no `.gitlab-ci.yml`, no Jenkinsfile. Builds are
entirely local. For a commercial release, at minimum:

- Build + shadowJar on every push
- Unit tests (once they exist) on every PR
- Release artifact upload on tag push

### License header propagation

`build.gradle:42-44` in the shadow config:

```groovy
from(rootProject.file('LICENSE')) {
    into('/')
    rename { 'LICENSE' }
}
```

Good — the MPL 2.0 license travels in the jar. But shaded dependencies
(commons-lang3, v1API) don't have their licenses propagated
explicitly; commons-lang3 is Apache 2.0 which requires the NOTICE file to
travel. Low-priority compliance issue.

## Modernization hotspots

If the goal is "ship-worthy 1.0," this is the punch list from lowest-effort
highest-impact downward.

### 1. Fix the drag `.save()` bug (one line)

`EventInventoryListener.java:84`. `OEntry.create().player(e.getWhoClicked())
.deposited(transaction, loc, null);` → append `.save();`. Every inventory
drag deposit is currently silently dropped. Dead data loss bug.

### 2. Delete obvious dead code (small patch, big clean-up)

Purge the unused files in one go:

- `command/result/CommandResult.java`
- `command/result/UseResult.java`
- `listener/PermissionListener.java`
- Nested `ItemWrapper` + `ItemTransaction` inside `EventInventoryListener`
- `EventInventoryListener.debugEvent`
- `DataWrapper.java:56` stale TODO comment
- The two empty `if` blocks in `sg.onEnable:79-87`
- `the v1 core/src/test/java/…/consumer/EntryConsumerTest.java`
- `the v1 core/src/test/java/…/domain/DataMapperTest.java`
- `DynamoStorageHandler` + `DynamoRecordHandler` + `io/dynamo/` package
  (contingent on the product decision to drop the backend)

Committed as one "remove dead code" PR this is all grep-verifiable and
zero-regression.

### 3. Decide on `FAWERollbackHandler` / `WorldEditHandler` / DynamoDB

Each of these is a "finish or delete" decision:

- **FAWE rollback** — real feature, real performance win for large
  rollbacks. Delete-or-finish the wiring: a `-fawe` flag on `/sg
  rollback` that switches to `FAWERollbackHandler.batchRollback` when
  `isFaweAvailable()` and the session isn't player-inventory-touching.
- **`WorldEditHandler` interface** — never read. Either make
  `onWorldEditStatusChange` call into the registered handler (if any) to
  allow third parties to override the logging wiring, or delete the
  interface and the `registerWorldEditHandler` API method.
- **DynamoDB backend** — non-functional. Delete.

### 4. Fix the build

In priority order:

- Replace `compileOnly files("…/FastAsyncWorldEdit.jar")` with a Maven
  coordinate (JitPack or EngineHub). Drop the `libs/` directory and
  remove the `.gitignore` entry. Clean clones should build.
- Switch version string to semver (`0.1.0-alpha.1`). Add gradle property
  so version can be overridden from CI: `version = project.hasProperty
  ('sg') ? project.property('sg') : '0.1.0-SNAPSHOT'`.
- Update `plugin.yml` `website` to the medievalrp-net org URL.
- Add a minimal GitHub Actions workflow: build, test, upload-artifact.

### 5. Seed the test suite

Start by uncommenting `DataWrapperTest` (`DataWrapperTest.java:4-87`) and
making it compile. Then add:

- `InventoryUtilTest` covering each `InventoryAction` branch.
- `DateUtilTest` covering shorthand parsing.
- `DataHelperTest` specifically round-tripping a 1.21+ `ItemStack` with
  `components` to prove the `dataWrapperToMap` fix stays fixed.
- `MongoRecordHandlerTest` (integration-style, spins up a `MongoClient`
  against a dev DB — or use Testcontainers' MongoDB module).

Target: one test per non-trivial public method. Coverage metric isn't the
point; the point is that changes that break `unwrapConfigSerializable` or
`identifyTransactions` blow up in CI before they blow up on a live server.

### 6. Port `Formatter` to Adventure

`Formatter` is the narrowest entry point for the full Adventure migration.
Change each method to return `Component` instead of `String` + colour
codes. Every call site updates from `sender.sendMessage(Formatter.error
("oops"))` to `sender.sendMessage(Formatter.error("oops"))` (same name, now
takes a `ComponentSender`). Downstream, `SearchCallback.buildComponent` and
`DataHelper.buildLocation` and `AiHandler.sendFormattedResponse` all stop
needing `net.md_5.bungee.api.chat.*`. See `05-commands-and-display.md`
modernization §1.

### 7. Inline `TypeUtil.pregMatchAll`, delete the class

Modern replacement, one line, into `DateUtil`:

```java
String[] matches = relativeTimeDeclaration.matcher(shorthand)
    .results()
    .map(MatchResult::group)
    .toArray(String[]::new);
```

Drop `TypeUtil.java` from the API module.

### 8. Move `ReflectionHandler` to an internal package

Currently under `api.util.reflection`, exposed as public API. Nobody
outside Core should be calling `loadEntityFromNBT(entity, base64)`. Move to
`net.medievalrp.v1.internal.reflection` in the Core module,
package-private methods, so the API module stops leaking NMS-reflection
entry points to consumers.

### 9. Delete stale javadoc headers and fix the remaining ones

The comment at `EventInventoryListener.java:32-38` ("an abomination …
annihilate humanity") is funny but unprofessional for a paid release. Keep
the technical content, tone down the prose.

### 10. Resolve the version-string hardcode

`V0.1-Alpha` → semver + git-tag driven.

## What v2 should keep

Not every util is junk. The following are load-bearing and correct:

- **`DateUtil.parseTimeStringToDate`** — self-contained, no Bukkit / Mongo
  dependency, pure function. The shorthand `4w`/`3d`/`1h` syntax is both
  the search-parameter format (`t:4w`) and the config retention format
  (`storage.expireRecords: 4w`). Users know it. The implementation is
  correct (with one minor edge case on "just now" vs "1m ago"). Keep the
  API, modernize internals (drop `TypeUtil`).
- **`PastTenseWithEnabled`** — tiny DTO, correct shape. Turn into a
  record; keep the role.
- **`ReflectionHandler`** — it's ugly, but entity rollback depends on it
  and Paper hasn't given us a supported alternative. Harden it with
  better error reporting; move it out of the API package.
- **`InventoryUtil.identifyTransactions`** — ugly but load-bearing.
  Don't rewrite blind; cover with tests first, then refactor on top of
  green. This is the single highest-value piece of code in the util
  layer; losing correctness here breaks every container audit.
- **`DataHelper.getBlockDataFromWrapper` / `getLocationFromDataWrapper`
  / `compileUserInput`** — straightforward readers; keep. In v2 they
  become methods on typed record accessors but the semantics are
  correct.
- **`DataHelper.convertArrayToMap`** — used by the inventory wrapper
  code, correct shape.

And from the broader dead-code list, these need finishing (not deleting):

- **CraftBook status change** — if CraftBook hot-reload is ever a real
  workflow, finish the empty method. Otherwise delete.
- **`FAWERollbackHandler`** — genuinely useful, just needs wiring.
- **`DataWrapperTest`** — uncomment, get it compiling, expand. The
  framework (Mockito + spigot-api test) is in place.

## Punch list for 1.0

Everything above, compressed to one ordered list for a "ship this" push:

1. Fix `EventInventoryListener.onInventoryDrag` missing `.save()`. (bug)
2. Switch FAWE dependency from local jar to Maven coordinate. (build)
3. Update `plugin.yml` `website` to medievalrp-net URL. (distribution)
4. Switch version string to semver + `gradle.properties` override. (build)
5. Delete confirmed-dead code: `CommandResult`, `UseResult`,
   `PermissionListener`, nested dupes in `EventInventoryListener`,
   `debugEvent`, two empty integration ifs in `sg.onEnable`,
   `DynamoDB` backend, stale `DataWrapper.ofBlock` TODO. (cleanup)
6. Make a decision on `FAWERollbackHandler` / `WorldEditHandler` /
   CraftBook status change — wire or delete. (scope)
7. Uncomment `DataWrapperTest`, get it green, add tests for
   `InventoryUtil`, `DateUtil`, `DataHelper` `components` round-trip,
   `MongoRecordHandler.buildConditions`. (tests)
8. Add a minimal GitHub Actions workflow: build + test on push / PR.
   (CI)
9. Migrate `Formatter` to Adventure as the seed for the wider chat-API
   port. (modernization)
10. `onDisable` flush of `EntryQueue` with timeout. (resilience)

Ten items; none individually expensive; the combination turns a "runs on
our server" plugin into something we could reasonably ship to paying
customers.

# Omniscience Dissection — 04: Query DSL

Files covered:

- API `net.medievalrp.omniscience.api.query`: `Query`, `QueryBuilder`, `QuerySession`, `FieldCondition`, `SearchCondition`, `SearchConditionGroup`, `MatchRule`
- API `net.medievalrp.omniscience.api.parameter`: `ParameterHandler`, `BaseParameterHandler`, `ParameterException`, `RecipientParameter`, `ServerParameter`, `WorldParameter`
- API `net.medievalrp.omniscience.api.flag`: `Flag`, `FlagHandler`, `BaseFlagHandler`
- Core `net.medievalrp.omniscience.api.parameter.*`: 14 parameter implementations (same package root as API)
- Core `net.medievalrp.omniscience.api.flag.*`: 8 flag implementations (same package root as API)
- Core `net.medievalrp.omniscience.command.util.SearchParameterHelper`

## Responsibility

Turn a raw string like `a:break p:itdontmatta r:30 t:1h -ng` into a `Query` object (a list of `SearchCondition`s) plus some side-effects on a `QuerySession` (flags set, sort order, radius, ignored defaults), then hand that `QuerySession` to a `RecordHandler` that translates the conditions into a Mongo `$match` document and runs the aggregation.

Nothing in this layer is typed beyond `String`. Parameters are key/value strings, flags are dash-prefixed strings, values get regex-matched, and each handler does its own ad-hoc conversion into `FieldCondition` instances. The query engine is a thin tree of condition objects that knows nothing about its backend. The backend (currently Mongo) does a `instanceof`-dispatched conversion into BSON.

## Top-level data model

```
QuerySession
 ├── sender: CommandSender
 ├── query: Query
 │    ├── searchCriteria: List<SearchCondition>   // FieldCondition | SearchConditionGroup
 │    └── searchLimit: int (= 2500)
 ├── flags: List<Flag>                            // enum set
 ├── sortOrder: Sort (NEWEST_FIRST | OLDEST_FIRST)
 ├── radius: int
 ├── ignoredDefaults: List<ParameterHandler>
 └── ignoredDefaultNames: List<String>
```

`SearchCondition` (`OmniscienceAPI/.../query/SearchCondition.java:3`) is a *marker interface with zero methods*. Two implementations: `FieldCondition` (a single predicate) and `SearchConditionGroup` (AND- or OR-composed list of conditions). The Mongo backend pattern-matches on the concrete class (`MongoRecordHandler.java:263`).

`FieldCondition` (`OmniscienceAPI/.../query/FieldCondition.java:8`) is a `(DataKey field, MatchRule rule, Object value)` triple with a secondary constructor for `MatchRule.BETWEEN` that takes a Guava `Range`. The `value` is `Object` because it spans: primitives, UUID strings, `Date`, `Pattern` (compiled regex), `List<String>` (for INCLUDES/EXCLUDES), `List<Pattern>` (also INCLUDES/EXCLUDES), `Range<Integer>` (for BETWEEN), and `boolean` (for EXISTS). No type tag.

`MatchRule` (`OmniscienceAPI/.../query/MatchRule.java:3`):

| Rule | Mongo operator | Typical value type |
|---|---|---|
| `EQUALS` | bare equality (`filter.put(field, value)`) | primitive or `Pattern` |
| `INCLUDES` | `$in` | `List` |
| `EXCLUDES` | `$nin` | `List` |
| `GREATER_THAN_EQUAL` | `$gte` | `Date` or number |
| `LESS_THAN_EQUAL` | `$lte` | `Date` or number |
| `BETWEEN` | `$gte` + `$lte` on same field | `Range<?>` |
| `EXISTS` | `$exists` | `Boolean` |

The spec the dissection brief mentions (`NOT_EQUALS`, `GREATER_THAN`, `LESS_THAN`) doesn't actually exist in the enum — the code has `GREATER_THAN_EQUAL`/`LESS_THAN_EQUAL` and no `NOT_EQUALS` (negation is expressed as `EXCLUDES` against a singleton list). That's a fossil of the spec diverging from the code.

`SearchConditionGroup` (`OmniscienceAPI/.../query/SearchConditionGroup.java:12`) wraps a list plus an `Operator` (`AND` or `OR`). Has three static factory helpers: `from(Location)`, `from(Location, int radius)`, `from(World)`. Only `from(Location, int radius)` is actually used widely (by `RadiusParameter` and defaults); `from(Location)` (exact block match) and `from(World)` (just world match, used by `WorldParameter`) are one-use or unused.

Group composition is flat: the Mongo backend's `buildConditions` (`MongoRecordHandler.java:259`) recurses into a group, and for `AND` groups it does `filter.putAll(subFilter)` — which silently *overwrites* any existing key. For `OR` groups it appends to `$or`. There's no nested-AND-of-ORs support in practice; the group system exists mostly so the radius block can bundle four field conditions (world + X/Y/Z ranges) into one unit.

## `QueryBuilder.fromArguments` — parsing one raw arg array

The entry point from commands is `QuerySession.newQueryFromArguments(String[])` (`OmniscienceAPI/.../query/QuerySession.java:91`), which forwards to `QueryBuilder.fromArguments`. The sequence at `QueryBuilder.java:36–101`:

1. Construct an empty `Query` and an empty `definedParameters: Map<String, String>` (alias → value, used for conflict detection).
2. Iterate each raw arg token:
   - If the token matches the flag pattern `(-)([^\s]+)?` (`QueryBuilder.java:22`), call `parseFlagFromArgument`.
   - Otherwise, call `getParameterKeyValue` which splits on the *first* `:` into `(alias, value)`. If there's no `:`, alias defaults to `"p"` (so `/omni lookup itdontmatta` is interpreted as `p:itdontmatta`). Then `parseParameterFromArgument`.
3. Collect any `CompletableFuture<?>` the handlers returned. If the list is non-empty, await *all* of them (`CompletableFuture.allOf`) before completing the outer future with the query. If empty, complete the outer future synchronously.
4. After the loop, if defaults are enabled (`OmniApi.areDefaultsEnabled()`), iterate every registered `ParameterHandler`: if it's not in `ignoredDefaults` and no user-supplied arg used one of its aliases, call `handler.processDefault(session, query)`. Defaults that fired get reported to the sender as a "Defaults used: ..." subheader (`QueryBuilder.java:95`).

`parseFlagFromArgument` (`QueryBuilder.java:103–128`):

1. Strip the leading `-`.
2. If the remainder contains `=`, split on `=` into `(flag, value)`. Otherwise value is `null`.
3. Look up `FlagHandler` via `OmniApi.getFlagHandler(flag)` — which linearly searches `flagHandlerList` for one whose `handles(flag)` returns true (`OmniCore.java:262`).
4. Check `flagHandler.acceptsSource(session.getSender())` and `flagHandler.acceptsValue(value)` if value is non-null. Throw `ParameterException` on mismatch.
5. Call `flagHandler.process(session, flag, value, query)`.

`parseParameterFromArgument` (`QueryBuilder.java:145–175`):

1. Look up `ParameterHandler` via `OmniApi.getParameterHandler(alias)` (linear search on `parameterHandlerList`).
2. `handler.canRun(sender)` — gatekeeping (e.g. `RadiusParameter.canRun` requires a `Player`, `RadiusParameter.java:26`).
3. `handler.acceptsValue(value)` — typically a regex match, sometimes with extra validation.
4. `handler.doesConflict(pair, otherPair)` — iterate previously defined parameters and throw if any conflict. Default implementation in `ParameterHandler.doesConflict` returns `canHandle(otherAlias)`, which means "the same handler can't accept two different arg tokens" — each alias is single-use unless overridden (only `TimeParameter` overrides, to distinguish `t:`/`since:` from `before:`, which isn't actually registered anywhere but the code anticipates it).
5. Call `handler.buildForQuery(session, alias, value, query)`.

Every step throws `ParameterException` on error. The command layer catches this and prints the message via `Formatter.error` (`OmniCommands.java:185`).

## `ParameterHandler` contract

`OmniscienceAPI/.../parameter/ParameterHandler.java:13`:

```java
interface ParameterHandler {
    boolean canRun(CommandSender sender);
    boolean acceptsValue(String value);
    boolean canHandle(String cmd);
    ImmutableList<String> getAliases();
    Optional<CompletableFuture<?>> buildForQuery(QuerySession, String parameter, String value, Query);
    default Optional<Pair<String, String>> processDefault(QuerySession, Query);
    default Optional<List<String>> suggestTabCompletion(String partial);
    default boolean doesConflict(Pair<String,String>, Pair<String,String>);
}
```

`BaseParameterHandler` (`OmniscienceAPI/.../parameter/BaseParameterHandler.java:17`) stores aliases, implements `canHandle` as `aliases.contains`, and provides three shared helpers:

- `generateDefaultsBasedOnPartial(options, partial)` — the tab-completion filter. Splits `partial` on `,` (for comma-lists like `a:break,place`), takes the last segment as the `target`, supports `!` prefix (exclusion suggestion), and returns completions reassembled with the prior segments.
- `convertStringToIncludes(DataKey, value, Query)` — splits `value` on `,`, each entry goes to an `in` list (plain) or `nin` list (if prefixed with `!`), each string runs through `DataHelper.compileUserInput` to escape regex metacharacters and expand `*` to `.*`. Emits at most two `FieldCondition`s: one `INCLUDES` with the `in` patterns, one `EXCLUDES` with the `nin` patterns.
- `compileMessageSearch(String[])` — builds a single composite regex for free-text fields (message/lore/name) with optional `!`-prefix exclusion. The output is unusual: if exclusions exist it prefixes the whole pattern with `/` and suffixes with `/i`, as if it's about to be evaluated by a JS-style regex literal parser. The Mongo backend doesn't interpret that — it passes the compiled `Pattern` through as-is, meaning the `/.../i` wrapper may never match. Suspicious; probably a port artifact from Prism's PHP regex handling.

Returning a `CompletableFuture` from `buildForQuery` is almost always `Optional.empty()`. The brief flags this correctly: it's a hot-potato API that exists only so async-capable parameter resolution *could* work, but no shipped parameter actually uses it. Every handler in the tree returns `Optional.empty()`. The `CompletableFuture.allOf` machinery in `QueryBuilder` is load-bearing for a feature nobody uses.

## Parameter catalog

Every parameter is registered in `OmniCore.registerParameters()` (`OmniCore.java:124–142`) in this order:

| Alias(es) | Impl | Package | `acceptsValue` regex | DataKey(s) added to query |
|---|---|---|---|---|
| `a` | `EventParameter` | Core, `api.parameter` | `[!]?[\w,-\*]+` + `hasEventNames` | `EVENT_NAME` via `EQUALS` or `INCLUDES`/`EXCLUDES` |
| `p` | `PlayerParameter` | Core | `[\w,:-]+` | `PLAYER_ID` (UUID string) via `EQUALS`/`INCLUDES`/`EXCLUDES` |
| `m` | `MessageParameter` | Core | `[\w!,:-\*]+` | `MESSAGE` via `EQUALS` against `compileMessageSearch` pattern |
| `r` | `RadiusParameter` | Core | `NumberUtils.isDigits` | four-way group: `LOCATION.World` equals + X/Y/Z `BETWEEN` ranges |
| `t`, `since` | `TimeParameter` | Core | `[\w,:\-]+` + `DateUtil.parseTimeStringToDate` succeeds | `CREATED` via `GREATER_THAN_EQUAL` (for `t:`/`since:`); `before:` would be `LESS_THAN_EQUAL` (dead branch — no `BeforeParameter` registered) |
| `c` | `CauseParameter` | Core | `[\w,:-\*]+` | `CAUSE` via `EQUALS`/`INCLUDES`/`EXCLUDES` |
| `b` | `BlockParameter` | Core | `[\w,:-\*]+` + every value resolves to `Material.isBlock` | `TARGET` (the value is uppercased) |
| `ip` | `IpParameter` | Core | `[\w.:,-\*]+` | `TARGET` (uses TARGET, not `IPADDRESS`, which is surprising — see pain points) |
| `e` | `EntityParameter` | Core | `[\w,:-\*]+` | `ENTITY_TYPE` |
| `n` | `ItemNameParameter` | Core | `[\w!,:-\*]+` | `ITEMSTACK.meta.display-name` |
| `i` | `ItemParameter` | Core | `[\w,:-\*]+` + every value `Material.isItem` | `TARGET` (same field as `b:`) |
| `d` | `ItemDescParameter` | Core | `[\w!,:-\*]+` | `ITEMSTACK.meta.lore` |
| `cu` | `CustomItemParameter` | Core | `y`/`n`/`yes`/`no` | `ITEMSTACK.meta` with `EXISTS` (`true`/`false`) |
| `trg` | `TargetParameter` | Core | `[\S]+` | `TARGET` via `EQUALS` |
| `w`, `world` | `WorldParameter` | API | `Bukkit.getWorld(value) != null` | `LOCATION.World` equals world UUID; also calls `session.addIgnoredDefault("r")` so the default radius doesn't also fire |
| `rcp`, `recipient` | `RecipientParameter` | API | `[\w,!:-]+` | `RECIPIENT` via `EQUALS`/`INCLUDES`/`EXCLUDES` (UUID strings) |
| `server`, `srv` | `ServerParameter` | API | `[\w-]+` | `SERVER` via `EQUALS` (but `resolveServerId` is a TODO stub that returns input lowercased) |

Notes on the regex columns: the `-\\\\*` you see in the source is a Java-escaped form of `-\*`, i.e. literal hyphen + literal backslash + asterisk. Several of these regex classes have dead backslashes inside character classes — relics of a port or of misunderstanding Java's regex escaping. They still function because `\\*` inside a character class just matches `\` or `*` as literal chars, but it's accidentally tolerant.

### Per-parameter details worth flagging

- **`EventParameter.acceptsValue`** additionally cross-checks against `OmniApi.getEnabledEvents()` (`EventParameter.java:35`). Tab completion pulls from `OmniEventRegistrar.INSTANCE.getEventNames()` which includes *all* registered events, not only enabled ones — mismatch between suggest and validate.
- **`PlayerParameter`** calls `Bukkit.getOfflinePlayer(name)` which always returns non-null and always does a blocking Mojang API call for unknown names. `acceptsValue` does not pre-check — so a mistyped name reaches `buildForQuery` and silently costs a network round-trip. The class docs this as "if (player != null)" but `getOfflinePlayer` never returns null, so the null guard is theater.
- **`RadiusParameter`** enforces `OmniConfig.INSTANCE.getRadiusLimit()` unless the sender has `omniscience.override.maxradius` (`RadiusParameter.java:42`). On limit breach it mutates the value, sends a warning, and proceeds — it does *not* reject the query.
- **`RadiusParameter.processDefault`** re-adds a radius group at `OmniConfig.INSTANCE.getDefaultRadius()` if no `r:` was supplied, which is how `/omni lookup` with no args ends up radius-constrained to the player's location.
- **`TimeParameter`** keys its behavior off the parameter alias inside `buildForQuery` (`TimeParameter.java:50`): if the user typed `t:` or `since:` it emits `GREATER_THAN_EQUAL`, else `LESS_THAN_EQUAL` — but only `t` and `since` are registered. The `before:` branch is ever-dead. `processDefault` injects a `since:<config-default>` if neither `t:` nor `since:` was used (and `before:` can't preempt it).
- **`WorldParameter.buildForQuery`** emits only a world match — no X/Y/Z range. And it tells the session to ignore the `r:` default, so the query can actually return world-wide results. Without that side-effect the default radius would still clamp the search to a 5-block ball.
- **`ServerParameter`** is unfinished. `resolveServerId` returns the lowercase input; `getAvailableServers()` returns `List.of()`. Registered but non-functional until multi-server support lands.
- **`CustomItemParameter`** is the only parameter that uses `MatchRule.EXISTS`. Its values `y`/`n` map to `true`/`false` on `ITEMSTACK.meta`.
- **`IpParameter`** writes to `TARGET` (`IpParameter.java:38`), not to the `IPADDRESS` DataKey. Either the listener emits IPs as TARGET or this query will never match — a likely bug but not what this doc investigates.

## Flag catalog

Registered in `OmniCore.registerFlags()` (`OmniCore.java:144–157`). All flags live in `Omniscience-Core/.../api/flag/` even though the API package prefix makes them look API-side.

| Syntax | Impl | `acceptsValue` | `requiresArguments` | Effect on `QuerySession` / `Query` |
|---|---|---|---|---|
| `-ex` | `FlagExtended` | any | no | `addFlag(Flag.EXTENDED)` — display-only hint, read by display handlers |
| `-ng` | `FlagNoGroup` | any | no | `addFlag(Flag.NO_GROUP)` — read by `MongoRecordHandler.query` to skip the `$group` aggregation stage |
| `-ord=<arg>` | `FlagOrder` | `new`/`newest`/`desc`/`old`/`oldest`/`asc` | yes | `setSortOrder(NEWEST_FIRST | OLDEST_FIRST)` |
| `-drain` | `FlagDrain` | any | no | `addFlag(Flag.DRAIN)` — hint for rollback to drain containers |
| `-nc` | `FlagNoChat` | any | no | `addFlag(Flag.NO_CHAT)` — triggers `FieldCondition(MESSAGE, EXISTS, false)` in the Mongo backend (`MongoRecordHandler.java:67`) |
| `-nod=<params>` | `FlagIgnoreDefault` | csv of registered parameter aliases | yes | `addIgnoredDefault(handler)` for each named param, disabling its `processDefault` |
| `-g` | `FlagGlobal` | any | no | calls `addIgnoredDefault("r")` — suppresses the default-radius group so the query spans the whole world. Only registered if `defaults.enabled: true`. |
| `-we` | `FlagWorldEditSel` | any (must have WE selection) | no | Reads the sender's WE `LocalSession`, converts the cuboid into a world+X/Y/Z `SearchConditionGroup`, adds it, and calls `isIgnoredDefault` (a *read*, no side-effect — likely meant to be `addIgnoredDefault`, see pain points) |

`FlagHandler.acceptsValue(null)` is never called — the null-check is in `parseFlagFromArgument` (`QueryBuilder.java:123`). So `acceptsValue` returning `true` for all inputs on most flags is fine.

### `FlagWorldEditSel` — the special case

Unlike every other flag, `FlagWorldEditSel` is registered conditionally (`OmniCore.java:154`): only if `WorldEdit` is plugin-loaded *and* `integration.worldEdit` is true, through a separate hook `OmniCore.onWorldEditStatusChange(true)` (`OmniCore.java:159`). It takes a `Plugin` reference in its constructor (`FlagWorldEditSel.java:29`) and casts to `WorldEditPlugin`, so it has a hard compile-time dependency on `com.sk89q.worldedit.bukkit.WorldEditPlugin`. If WE isn't on the classpath at *load* time the class fails to resolve — hence the conditional registration has to also not touch the class until we know WE is present. It works because `OmniCore.registerFlags()` doesn't reference the class directly — it calls the `onWorldEditStatusChange` method, which *does* reference `FlagWorldEditSel` and would blow up with `NoClassDefFoundError` if WE were absent. The conditional guard around that call is the only thing keeping the classloader from flipping over.

The bug-shaped line is `FlagWorldEditSel.java:68`:

```java
session.isIgnoredDefault(Omniscience.getParameterHandler("r").orElse(null));
```

This reads the ignored-default flag for `r` and discards the result. It was almost certainly meant to be `addIgnoredDefault`, paralleling `WorldParameter`'s behavior. As shipped, `-we` emits a correct X/Y/Z/world condition group *and then also lets the default 5-block radius group fire*, producing an AND of two radius boxes.

## `SearchParameterHelper.suggestParameterCompletion`

Tab completion is hand-rolled (`SearchParameterHelper.java:13`). The branches:

1. **Partial starts with `-`** and contains `=`: the user has begun a flag like `-ord=as`. Split on `=` into `(flagAlias, flagValue)`, look up the flag, call `suggestCompletionOptions(flagValue)`, and prefix each completion with `-<flag>=`.
2. **Partial starts with `-`** and does not contain `=`: user is still typing the flag name. Enumerate every flag alias, prefix with `-`, append `=` if `requiresArguments()`, and filter by `startsWith(partial)`.
3. **Partial contains `:`**: user has chosen a parameter alias and is filling in the value. Split on `:` into `(alias, valueSoFar)`, look up the parameter, call `suggestTabCompletion(valueSoFar)`, prefix with `<alias>:`.
4. **Empty / plain partial**: suggest every flag (with dash, with `=` if it wants a value) concatenated with every parameter alias (with trailing `:`), filtered by prefix-match against the partial.

The helper is invoked from `OmniCommands.java:71` inside a Cloud `SuggestionProvider.blocking` — the Cloud framework provides the integration point, but the helper itself bypasses Cloud's parsers and rebuilds suggestions from scratch using the same `Omniscience.getFlagHandler` / `Omniscience.getParameters` registries that `QueryBuilder.fromArguments` uses. Cloud's Brigadier tab-completion graph doesn't see any of this — Omniscience registers the `/omni` subcommand as a greedy string argument and then drives completion from a single custom suggestion provider that parses the remaining input itself.

## End-to-end trace: `/omni search a:break p:itdontmatta r:30 t:1h -ng`

1. Cloud dispatches to the handler at `OmniCommands`. A `QuerySession` is constructed with `sender`. `paramString` is split on whitespace: `["a:break", "p:itdontmatta", "r:30", "t:1h", "-ng"]`. `session.newQueryFromArguments(args)` is called (`OmniCommands.java:182`).
2. Inside `QueryBuilder.fromArguments` (`QueryBuilder.java:36`):
   - `a:break` — `getParameterKeyValue` → `("a", "break")`. Lookup `EventParameter`. `acceptsValue` checks the regex and `hasEventNames("break")` against `OmniApi.getEnabledEvents()`. `buildForQuery`: no comma, so emits `FieldCondition(EVENT_NAME, EQUALS, DataHelper.compileUserInput("break"))` → a `Pattern` that matches literal `break`.
   - `p:itdontmatta` — `PlayerParameter.buildForQuery`: `Bukkit.getOfflinePlayer("itdontmatta")` → `OfflinePlayer`, `.getUniqueId().toString()` → some UUID. Emits `FieldCondition(PLAYER_ID, EQUALS, "<uuid>")`.
   - `r:30` — `RadiusParameter.canRun` requires `Player`. Assuming the sender is a player, `buildForQuery` reads `player.getLocation()`, clamps `30` against `radiusLimit` (default 250), stores radius on the session, emits `SearchConditionGroup.from(location, 30)` — which is a group containing `(LOCATION.World, EQUALS, worldUuid)`, `(LOCATION.X, BETWEEN, open(px-30, px+30))`, `Y` range, `Z` range.
   - `t:1h` — `TimeParameter`. `DateUtil.parseTimeStringToDate("1h", false)` returns `now() - 1 hour`. Alias is `t`, so `MatchRule.GREATER_THAN_EQUAL`. Emits `FieldCondition(CREATED, GREATER_THAN_EQUAL, <Date>)`.
   - `-ng` — `FlagNoGroup.process`: `session.addFlag(Flag.NO_GROUP)`. No condition added.
   - Defaults pass: `areDefaultsEnabled()` is true. For each registered parameter, check if the user supplied an alias that matches. `a`, `p`, `r`, `t` are in `definedParameters`, and `session.ignoredDefaults` is empty at this point — so no aliases match only for unused handlers. `TimeParameter.processDefault` would have fired with `since:3d` if no `t:` had been given, but it was, so skipped. Everything else has a no-op default. `usedDefaults` stays empty; no "Defaults used:" message.
3. `future.complete(query)` fires. `OmniCommands` chains `.thenAccept(ignored -> Async.lookup(session, new SearchCallback(session)))` which schedules the storage query on the async pool.
4. `MongoRecordHandler.query(session)` (`MongoRecordHandler.java:62`):
   - `session.hasFlag(Flag.NO_CHAT)` → false, so no MESSAGE-exists condition.
   - `buildConditions(query.getSearchCriteria())` walks the `List<SearchCondition>`. Each `FieldCondition` becomes one BSON field; the radius `SearchConditionGroup` recurses, and because its operator is `AND`, its four inner fields are `putAll`'d flat into the outer filter. Final `$match`:
     ```
     {
       Event:    /break/,
       Player:   "<uuid>",
       Location.World: "<world-uuid>",
       Location.X: { $gte: px-30, $lte: px+30 },
       Location.Y: { $gte: py-30, $lte: py+30 },
       Location.Z: { $gte: pz-30, $lte: pz+30 },
       Created:  { $gte: <1h ago> }
     }
     ```
   - Because `-ng` was set, the pipeline skips the `$group` stage and goes straight `match → sort (Created desc) → limit (2500)`. Raw rows are converted back to `DataWrapper`s and `DataEntry` subclasses (one per row, no aggregation).
5. The resulting `List<DataEntry>` is handed to `SearchCallback` which formats it via `DisplayHandler` and sends chat components to the player.

## Pain points

1. **`SearchCondition` is a zero-method marker.** Every consumer does `instanceof FieldCondition` / `instanceof SearchConditionGroup` dispatch (`MongoRecordHandler.java:263`). A sealed interface with a visitor would make the backend a switch expression and catch every future subtype at compile time. As written, adding a third condition type means hunting for `instanceof` chains across the codebase.
2. **`FieldCondition.value` is `Object`.** The valid types are: `Number`, `String`, `Date`, `Boolean`, `Pattern`, `Range<?>`, `List<String>`, `List<Pattern>` — implicit knowledge, not enforced. A typed hierarchy (`Equals<T>`, `Between<T extends Comparable>`, `Exists`, `In<T>`) would make `buildConditions` exhaustive.
3. **String-first DSL.** Everything is a `String` until a handler decides otherwise. Values are validated with per-handler regex, parsed in an ad-hoc way, and converted to a mix of concrete types. There's no canonical AST. The query the player types and the `Query` the backend consumes are two unrelated representations stitched together by dozens of little regex+split+branch blocks.
4. **Duplicated list/single-value branching.** Practically every parameter has the pattern:
   ```java
   if (value.contains(",")) { convertStringToIncludes(...) }
   else                     { query.addCondition(EQUALS, ...) }
   ```
   `BlockParameter`, `CauseParameter`, `EntityParameter`, `EventParameter`, `IpParameter`, `ItemDescParameter`, `ItemNameParameter`, `ItemParameter`, `MessageParameter`, `PlayerParameter`, `RecipientParameter`, `TargetParameter` all do this. Could be one helper on the base class; instead it's 12 copies.
5. **Tab-completion bypasses Cloud/Brigadier.** `SearchParameterHelper.suggestParameterCompletion` is a hand-rolled parser built from the same registries as `QueryBuilder.fromArguments`. The Cloud command framework is already in the stack and supports `@Parser` / `@Suggestions` annotations and full Brigadier integration, but the DSL sidesteps all of that, meaning client-side highlighting, typed argument nodes, and brigadier-based autocompletion are all unused.
6. **Parameter and flag impls live under `net.medievalrp.omniscience.api.parameter/flag.*`** in *Core*, duplicating the API package root. From a classpath perspective you can't tell which package is the consumer-facing interface layer and which is internal plugin code. Anyone importing `OmniscienceAPI` thinks `EventParameter` is part of the API; it's not — it depends on `OmniEventRegistrar` which is Core-only. This is the same layering leak called out in `00-overview.md` and it manifests here more than anywhere else.
7. **Async handshake via `Optional<CompletableFuture<?>>`.** `ParameterHandler.buildForQuery` and `FlagHandler.process` both return `Optional<CompletableFuture<?>>`. Nothing in the codebase ever returns a present future — every handler returns `Optional.empty()`. The orchestration in `QueryBuilder` to collect and `allOf` the futures is dead infrastructure for a feature that never landed. At the same time, `QuerySession.newQueryFromArguments` returns `CompletableFuture<Void>` which the command layer *does* chain, so the public surface commits to async semantics for what is currently a synchronous operation.
8. **`QuerySession` is a god object.** It holds: the command sender, the query being built, the flag list, the sort order, the applied radius, two parallel collections of ignored defaults (`List<ParameterHandler>` and `List<String>`, inconsistently populated), and it's passed to every handler, the storage backend, the display handlers, rollback, *and* any third-party parameter someone wires in. Mutation happens from every direction.
9. **Two ways to record "ignore this default".** `addIgnoredDefault(ParameterHandler)` and `addIgnoredDefault(String)`. `isIgnoredDefault` checks both. `WorldParameter` uses the string form, `FlagGlobal` uses the handler form (via `Omniscience.getParameterHandler("r").ifPresent(...)`), `FlagIgnoreDefault` uses the handler form. No particular reason for the split — looks like someone added the string overload to avoid a registry lookup and never consolidated.
10. **Hand-coded regex for free-text values.** `DataHelper.compileUserInput` and `BaseParameterHandler.compileMessageSearch` both escape user input into `Pattern`s then hand them to Mongo, which re-parses. `compileMessageSearch` emits `/.../i` wrapper syntax that Java `Pattern` doesn't recognize as case-insensitive (that would be `(?i)`) — the intent from a JS/PHP-style regex literal was lost in translation. Likely means excluded-message searches never match what the author intended.
11. **`b:` and `i:` and `trg:` all write to `TARGET`.** Three different parameter handlers, one field. The parameters differ only in the extra validation they apply (`isBlock` vs `isItem` vs none). Layering-wise these are three restrictive views of the same underlying query, which could be a single parameter with a type tag or subfield.
12. **No backwards consistency on defaults side-effect.** `RadiusParameter.processDefault` *adds* a radius group. `TimeParameter.processDefault` adds a time condition. Both are fine. But `WorldParameter.buildForQuery` also reaches into `session.addIgnoredDefault("r")` to suppress the radius default, and `FlagWorldEditSel` was *supposed* to do the same and doesn't (the bug at `FlagWorldEditSel.java:68`). The coupling between "I supply my own location constraint" and "therefore don't add the default radius" is informal — each handler has to remember to turn off the default itself, and one of them forgot.
13. **`FlagGlobal` registration is config-gated.** If `defaults.enabled: false`, `-g` is not registered (`OmniCore.java:150`). From the user's perspective, `-g` sometimes silently doesn't exist — the command will reject it with "not a valid flag". Making a flag's availability depend on a boolean somewhere else is a UX trap.
14. **Query default limit is 2500, hardcoded.** `Query.searchLimit = 2500` on construction (`Query.java:10`). `OmniCommands` later sets it to `OmniConfig.INSTANCE.getActionablesLimit()` specifically for rollback/restore flows, but the search path doesn't. So `/omni search` always returns ≤2500 rows regardless of config.
15. **`Query` is mutable and exposes a setter for the whole criteria list.** `setSearchCriteria(List)` (`Query.java:20`) would let a caller wipe the query mid-flight. Nothing exploits this, but the API surface allows it.
16. **Confliction-detection is O(n²) and string-based.** `parseParameterFromArgument` iterates every previously seen parameter pair and asks the new handler's `doesConflict` (`QueryBuilder.java:164`). Default behavior is "same alias = conflict", which is correct but re-implementable as a `Set<String>` check. `TimeParameter`'s override to handle `t`/`since`/`before` is the only non-trivial conflict rule — and one of those three isn't even registered.

## Modernization hotspots

1. **Sealed interface + records for the predicate tree.**
   ```java
   sealed interface QueryPredicate permits Eq, NotEq, In, NotIn, Range, Exists, And, Or { }
   record Eq<T>(DataKey field, T value) implements QueryPredicate { }
   record In<T>(DataKey field, List<T> values) implements QueryPredicate { }
   record Range<T extends Comparable<T>>(DataKey field, T lo, T hi) implements QueryPredicate { }
   record Exists(DataKey field, boolean expected) implements QueryPredicate { }
   record And(List<QueryPredicate> children) implements QueryPredicate { }
   record Or(List<QueryPredicate> children) implements QueryPredicate { }
   ```
   Backend dispatch becomes `switch (predicate)` with exhaustive patterns, caught by the compiler.
2. **Cloud annotation-driven parser with real Brigadier suggestions.** Each parameter becomes an annotated argument (`@Argument("player") @Parser("omni.player") OfflinePlayer target`) with its own `Suggestions` provider registered into Cloud's parser registry. Brigadier gets a proper argument node; tab-completion is client-side and typed; `acceptsValue` disappears because Cloud refuses malformed input before the handler runs. `SearchParameterHelper` goes away.
3. **Type-safe parameter return values.** `ParameterHandler<T>` produces a `T`, not a mutation on the `Query`. A downstream `QueryAssembler` takes the set of resolved parameters and emits the `QueryPredicate` tree. Parsing and query construction separate cleanly; you can build the same query programmatically without going through strings.
4. **Programmatic `QueryBuilder` as the first-class API.** Today the only way to construct a non-trivial query is to synthesize a string and push it through `QueryBuilder.fromArguments`. External plugins get zero help from the API. Flip it: programmatic builder is primary, string parsing is one optional adapter layered on top.
5. **Flags as an `EnumSet<Flag>` instead of a `List<Flag>`.** `QuerySession.hasFlag` is a linear scan (`QuerySession.java:43`). `addFlag` permits duplicates. `EnumSet.noneOf(Flag.class)` costs nothing and fixes both. Flag handlers that carry data beyond the enum (like `FlagOrder`'s sort direction) should write to typed fields on the session, not piggy-back on `Flag`.
6. **Move all parameter and flag *implementations* out of `net.medievalrp.omniscience.api.*`.** Relocate to `net.medievalrp.omniscience.parameter.*` and `net.medievalrp.omniscience.flag.*` in Core. Only the interfaces (`ParameterHandler`, `FlagHandler`, `BaseParameterHandler`, etc.) plus the truly-API parameters (`WorldParameter`? — debatable) stay under `api`. A consumer plugin should be able to read the API module's package list and know exactly what surface it inherits.
7. **Split `QuerySession` into focused records.** `QueryRequest(sender, predicates, sort, limit, flags)` for input, `QueryResult(entries, aggregations)` for output, `QueryBuildingContext(session, ignoredDefaults)` passed through the parser only. No single object that every layer writes to.
8. **Inline the Mongo-style regex construction at the backend.** `DataHelper.compileUserInput` and `compileMessageSearch` produce `java.util.regex.Pattern` objects as if the abstraction is portable, but the Mongo driver only uses them because BSON has a native regex type. The Dynamo backend (and any future SQL one) can't consume a `Pattern`. Predicates should carry the *intent* (`StartsWith`, `Contains`, `Wildcard`) and the backend should render that into whatever it supports.
9. **Consolidate `b:`/`i:`/`trg:` into a single parameter with type validation moved to a subfield.** Or at least share the "csv-or-single + include/exclude" implementation — the current copy-paste is the density center of the codebase's regex-and-split anti-pattern.
10. **Fix or delete the `CompletableFuture<?>` return type.** If async parameter resolution is a real future need (say, async Mojang lookups for player UUIDs) — commit to it, document it, and make one handler actually use it to prove the plumbing. If not, change the signature to void and kick the `allOf` machinery out of `QueryBuilder`.
11. **Promote the string DSL to a textual serialization of the programmatic query.** `Query.toDsl()` produces `a:break p:itdontmatta r:30 t:1h -ng` from a programmatic `Query`. Players and ops get the same textual form they have today; plugins get the programmatic builder; the parser becomes one consumer of a shared grammar instead of the only grammar.

## What v2 should keep

- The `DataKey` addressing scheme — typed paths into documents, reusable across backends.
- The `FieldCondition` / `SearchConditionGroup` split in spirit (just turn them into a proper algebra).
- The alias list per parameter. Multi-alias is a nice UX touch and costs nothing.
- The notion of defaults that fire only when no explicit value was given. Useful.
- The `-ignoreDefault` / `-nod` escape hatch. Keep it, but use typed parameter references instead of strings.
- Permission-gated overrides (e.g. `omniscience.override.maxradius`). Keep.
- Per-parameter source-gating (`canRun`) so e.g. `r:` refuses to run from console. Keep.

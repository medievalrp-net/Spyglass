# Spyglass API

Build plugins that record forensic events into Spyglass, query
the event log, or extend its search and rendering. Compile against
the `spyglass-api` jar; the running Spyglass plugin provides
the implementation at runtime via Bukkit's services manager.

This document is the entire surface area you need. You don't need
access to the Spyglass plugin source.

---

## 1. Adding the dependency

The API artifact is `net.medievalrp:spyglass-api:1.0.0`. Mark it
`compileOnly` (Gradle) or `provided` (Maven) — at runtime the
Spyglass plugin supplies the classes. Bundling them into your
shaded jar will cause classloader conflicts.

### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven("https://repo.medievalrp.net/releases") // or wherever it's published
}

dependencies {
    compileOnly("net.medievalrp:spyglass-api:1.0.0")
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    compileOnly 'net.medievalrp:spyglass-api:1.0.0'
    compileOnly 'io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT'
}
```

### Maven

```xml
<dependency>
    <groupId>net.medievalrp</groupId>
    <artifactId>spyglass-api</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

### Local jar (for prototyping)

Drop `spyglass-api-1.0.0.jar` into a `libs/` folder and:

```kotlin
dependencies {
    compileOnly(files("libs/spyglass-api-1.0.0.jar"))
}
```

### plugin.yml

Declare a soft dependency so your plugin loads after Spyglass and
can degrade gracefully when it's absent:

```yaml
name: YourPlugin
main: com.example.YourPlugin
version: 1.0.0
api-version: '1.21'
softdepend: [Spyglass]
```

Use `depend` instead if Spyglass is mandatory for your plugin.

---

## 2. Getting the API instance

The implementation registers itself with Bukkit's services manager
during its own `onEnable()`. Your plugin obtains the singleton with a
single call:

```java
import net.medievalrp.spyglass.api.SpyglassApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class YourPlugin extends JavaPlugin {

    private SpyglassApi sg;

    @Override
    public void onEnable() {
        sg = Bukkit.getServicesManager().load(SpyglassApi.class);
        if (sg == null) {
            getLogger().warning("Spyglass not present; "
                    + "forensic integrations disabled.");
            return;
        }
        // Register your renderers / param handlers here.
    }
}
```

`load()` returns `null` when Spyglass isn't installed. Always
null-check; never assume the API is there.

---

## 3. Recording events

Push a custom forensic event into the recorder. Useful when your
plugin does something the built-in listeners don't cover (a
faction territory claim, a custom shop transaction, etc.).

```java
import net.medievalrp.spyglass.api.event.*;
import net.medievalrp.spyglass.api.util.BlockLocation;
import java.time.Instant;
import java.util.UUID;

void logFactionClaim(Player claimant, Block flag, String factionName) {
    Instant now = Instant.now();
    BlockLocation loc = new BlockLocation(
            flag.getWorld().getUID(),
            flag.getWorld().getName(),
            flag.getX(), flag.getY(), flag.getZ());

    Origin origin = Origin.player();
    Source source = Source.player(claimant.getUniqueId(), claimant.getName());
    RecordContext ctx = RecordContext.fresh(
            now,
            now.plusSeconds(60 * 60 * 24 * 30), // 30-day retention
            origin, source, loc);

    // The static of(...) factories on each EventRecord subtype use a
    // fixed event name (e.g. BlockUseRecord.of() always says "use").
    // For a custom event name, use the record constructor directly:
    sg.record(new BlockUseRecord(
            ctx.id(),
            "faction-claim", // your custom event name
            ctx.occurred(),
            ctx.expiresAt(),
            ctx.origin(),
            ctx.source(),
            ctx.location(),
            factionName)); // target shown in search results
}
```

`record()` returns immediately and never blocks. The record is
batched onto the async drain and persisted within ~250 ms.

### Choosing an event type

Pick the `EventRecord` subtype that matches the shape of your event:

| Subtype | Use for |
|--------|---------|
| `BlockBreakRecord` | A block was destroyed or removed (carries before-state) |
| `BlockPlaceRecord` | A block was placed or restored (carries after-state) |
| `BlockUseRecord` | A block was interacted with (lightweight; no snapshot) |
| `ChatRecord` | Player or system text |
| `CommandRecord` | Slash-command invocations |
| `ContainerDepositRecord` / `ContainerWithdrawRecord` | Items moved into/out of a container |
| `ContainerInteractRecord` | Container was opened/closed |
| `ItemDropRecord` / `ItemPickupRecord` | Item entity events |
| `JoinRecord` / `QuitRecord` | Connection lifecycle |
| `TeleportRecord` | Player or entity teleported |
| `EntityDeathRecord` / `EntityHitRecord` / `EntityMountRecord` / `EntityNameRecord` | Entity lifecycle/combat |

Each subtype has a static `of(ctx, ...)` factory; the constructor
parameters list the type-specific fields. The Javadoc on each record
class documents them.

### Origin and Source

`Origin` is *who in the system* caused this — a player, a plugin, the
environment. `Source` is *what specifically* — a player UUID + name,
an entity, a command block, a plugin name, etc. Both are sealed
hierarchies with static factories:

```java
Origin.player()
Origin.environment("burn")
Origin.plugin("YourPlugin")
Origin.rollback("operatorName")

Source.player(uuid, name)
Source.entity(uuid, "ZOMBIE")
Source.commandBlock(blockLocation)
Source.console()
Source.plugin("YourPlugin")
Source.environment("explosion")
```

---

## 4. Querying events

`query()` returns a `CompletionStage<QueryResult>`. The query runs on
a worker pool; chain with `thenAcceptAsync` (or hop back to the main
thread with Bukkit's scheduler) before touching world state.

```java
import net.medievalrp.spyglass.api.query.*;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

void showRecentBreaksByPlayer(UUID playerId, CommandSender sender) {
    QueryRequest request = new QueryRequest(
            List.of(
                    new QueryPredicate.Eq("event", "break"),
                    new QueryPredicate.Eq("source.playerId", playerId)),
            Sort.NEWEST_FIRST,
            50, // limit
            EnumSet.noneOf(Flag.class), // flags
            false); // grouping

    sg.query(request).thenAccept(result -> {
        sender.sendMessage("Found " + result.records().size() + " breaks");
        for (EventRecord record : result.records()) {
            sender.sendMessage(record.event() + " by "
                    + record.source().displayName()
                    + " at " + record.location());
        }
    }).exceptionally(throwable -> {
        sender.sendMessage("Query failed: " + throwable.getMessage());
        return null;
    });
}
```

### Predicate types

`QueryPredicate` is sealed; pick the one that matches your filter:

```java
new QueryPredicate.Eq(field, value)
new QueryPredicate.In(field, List.of(v1, v2, v3))
new QueryPredicate.Range(field, lowerInclusive, upperInclusive) // either bound may be null
new QueryPredicate.Exists(field, true) // field IS NOT NULL
new QueryPredicate.Not(predicate)
new QueryPredicate.And(List.of(p1, p2))
new QueryPredicate.Or(List.of(p1, p2))
```

For string `Eq`, you may pass a `java.util.regex.Pattern` to use
regex matching.

### Field paths

Predicates use dotted Mongo-style field paths. The most useful are:

| Path | Type | Meaning |
|------|------|---------|
| `event` | String | Event name (e.g. `"break"`, `"say"`) |
| `occurred` | Instant / millis | When the event fired |
| `expiresAt` | Instant / millis | When the row will be TTL-expired |
| `target` | String | Subtype-specific summary (material, player name, etc.) |
| `source.playerId` | UUID | The acting player's UUID, if any |
| `source.playerName` | String | The acting player's name |
| `source.entityId` | UUID | The acting entity's UUID |
| `source.entityType` | String | The acting entity's type key |
| `source.pluginName` | String | When the source is a plugin |
| `location.worldId` | UUID | World UID |
| `location.worldName` | String | World name |
| `location.x` / `.y` / `.z` | int | Block coordinates |
| `origin.kind` | String | One of `player`, `environment`, `plugin`, `rollback` |
| `origin.detail` | String | Free-form detail (e.g. `"burn"`) |
| `message` | String | `ChatRecord.message` |

Item-payload paths (`item.name`, `item.lore`, `item.enchants`,
`originalBlock.containerItems.lore`, etc.) are searchable on the
Mongo backend. The ClickHouse backend stores those as opaque BSON
blobs and can't filter on them — those queries fail with a clear
error.

### Flags

`Flag` toggles renderer/query behaviour:

| Flag | Effect |
|------|--------|
| `NO_GROUP` | Disable result grouping even when `grouping=true` |
| `GLOBAL` | Skip the implicit radius constraint added by some commands |
| `NO_CHAT` | Exclude chat events from the result set |
| `EXTENDED` | Include hover-extended detail in renderers |

### Grouping

Set `grouping = true` to ask the backend for aggregations (one row
per `(event, target)` tuple plus a count). Read them via
`result.aggregations()`; raw records still come back in
`result.records()` unless flags say otherwise.

---

## 5. Adding a custom search parameter

Want users to type `/spyglass search faction=red` and have your plugin
translate that into a predicate? Implement `QueryParamHandler`:

```java
import net.medievalrp.spyglass.api.param.*;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;
import java.util.List;

public final class FactionParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("faction", "f");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context)
            throws ParamParseException {
        UUID factionLeaderId = lookupFactionLeader(value);
        if (factionLeaderId == null) {
            throw new ParamParseException("Unknown faction: " + value);
        }
        return new QueryPredicate.Eq("source.playerId", factionLeaderId);
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return knownFactionNames().stream()
                .filter(name -> name.toLowerCase().startsWith(input.toLowerCase()))
                .toList();
    }
}
```

Register during `onEnable()`:

```java
sg.registerQueryParamHandler(new FactionParam());
```

**Threading**: `parse()` and `suggestions()` are called on the main
server thread. Cache lookups eagerly; never block on I/O here.

**Errors**: throw `ParamParseException` with a user-facing message
for invalid input. Other `RuntimeException`s abort the command with
a generic error and a server-log entry.

---

## 5b. Adding a custom flag

Where parameters use `alias=value`, flags use the dash form
(`-alias` or `-alias=value`). Implement `FlagHandler` for things
that read more naturally as toggles than as `key=value`:

```java
import net.medievalrp.spyglass.api.extension.FlagHandler;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;
import java.util.List;

public final class FriendlyOnlyFlag implements FlagHandler {

    @Override
    public List<String> aliases() {
        return List.of("friendly", "fr");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext ctx)
            throws ParamParseException {
        // value is null for bare `-friendly`, populated for `-friendly=red`
        if (value == null) {
            return new QueryPredicate.Eq("source.faction.relation", "friendly");
        }
        return new QueryPredicate.Eq("source.faction.relation", value);
    }
}
```

Register during `onEnable()`:

```java
sg.registerFlagHandler(new FriendlyOnlyFlag());
```

Built-in flag aliases (`ng`, `g`, `nc`, `ex`, `we`, `ord`, `nod`)
cannot be shadowed — the parser checks built-ins first.

---

## 6. Customising display

Override how a specific event renders in `/spyglass search` output and
inspection-wand hovers:

```java
import net.medievalrp.spyglass.api.extension.DisplayRenderer;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.query.Flag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.EnumSet;
import java.util.List;

public final class FactionClaimRenderer implements DisplayRenderer {

    @Override
    public Component renderTarget(EventRecord record, Component defaultTarget,
                                  EnumSet<Flag> flags) {
        // record.target() is the faction name we recorded earlier
        return Component.text(" [" + record.target() + "]", NamedTextColor.GOLD);
    }

    @Override
    public List<Component> hoverLines(EventRecord record) {
        return List.of(
                Component.text("Faction: " + record.target(), NamedTextColor.GRAY),
                Component.text("Leader UUID: " + record.source().displayName(),
                        NamedTextColor.DARK_GRAY));
    }
}
```

Register against the event name you want to customise:

```java
sg.registerDisplayRenderer("faction-claim", new FactionClaimRenderer());
```

**Threading**: both methods run on the **main server thread** during
page rendering. Do not block, do not perform I/O, do not call into
other plugins that schedule sync work. Build and return Adventure
components from already-fetched record fields.

**Error handling**: if either method throws or returns null,
Spyglass silently falls back to the default rendering for that
line. Your custom output is dropped for that one record; subsequent
records still render through your renderer.

---

## 6b. Listening for record commits

Reactive integrations (Discord webhooks, SIEM forwarding, anti-grief
auto-alerts) subscribe to `RecordCommittedEvent` like any Bukkit
event:

```java
import net.medievalrp.spyglass.api.event.RecordCommittedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class CommitListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommit(RecordCommittedEvent e) {
        // Listener may run on any thread — check before touching world state.
        if (e.isAsynchronous()) {
            // Hop to main if needed, or stay async for I/O / network calls.
        }
        if ("break".equals(e.record().event())) {
            forwardToSiem(e.record());
        }
    }
}
```

Register the listener with Bukkit (`getServer().getPluginManager()
.registerEvents(new CommitListener(), this)`) — there is no
Spyglass-specific registration step.

The event auto-detects sync vs async based on the calling thread.
Cancellation is intentionally not supported: the record is already
on the durable pipeline by the time this fires. Filter upstream in
your own listeners if you want to suppress events.

---

## 6c. Custom rollback effects

For state your plugin owns that Spyglass's built-in rollback
can't model (faction territory, custom-block bridges, plugin-managed
NPCs), pair a `RollbackEffect.Custom` payload with a
`RollbackEffectHandler`:

```java
import net.medievalrp.spyglass.api.rollback.*;

public final class FactionTerritoryHandler implements RollbackEffectHandler {

    @Override
    public String type() {
        return "faction-territory";
    }

    @Override
    public RollbackResult apply(RollbackEffect.Custom effect) {
        // Decode your own payload — version it so older entries decode.
        TerritoryChange change = TerritoryChange.decode(effect.payload());

        boolean ok = factionService.restore(change);
        if (!ok) {
            return new RollbackResult.Skipped(effect,
                    new RollbackReason.Error("Faction service rejected restore"));
        }

        // Build the inverse so /spyglass undo can re-apply this rollback.
        RollbackEffect.Custom inverse = new RollbackEffect.Custom(
                "faction-territory",
                effect.location(),
                change.invert().encode());
        return new RollbackResult.Applied(effect, inverse);
    }
}
```

Register and emit:

```java
sg.registerRollbackEffectHandler(new FactionTerritoryHandler());

// Later, when you record a faction-claim event, attach the rollback:
RollbackEffect.Custom effect = new RollbackEffect.Custom(
        "faction-territory",
        location,
        change.encode());
// (Persist `effect` alongside your event record via your own storage,
// or push into Spyglass's undo ledger via /spyglass rollback when
// the operator runs a rollback that includes faction-claim events.)
```

The handler runs on the **main server thread**; world mutations are
safe but long-running I/O is not. Embed a version byte in your
payload — the undo ledger may hold effects emitted by older plugin
versions.

---

## 7. Discovering enabled events

Configurations vary — operators can disable any built-in event. Hide
your UI for events that aren't being recorded:

```java
Set<String> enabled = sg.enabledEvents();
if (!enabled.contains("break")) {
    // Don't show a "rollback breaks" button to the user.
}
```

The set is immutable and reflects the active configuration at the
time Spyglass enabled. It does not update if the operator
reloads config; re-fetch the API singleton if you need the latest.

### Operator limits

Align your own bounds with Spyglass's by reading
`sg.limits()`:

```java
SpyglassLimits limits = sg.limits();
int maxRadius = limits.maxRadius(); // hard cap on radius params
int defaultRadius = limits.defaultRadius(); // default when user omits one
Duration window = limits.defaultTimeWindow(); // default time= window
Duration ttl = limits.retention(); // matches storage TTL
```

Useful when picking an `expiresAt` for a record you push, or when
clamping a custom radius parameter.

### Plugin logger

`sg.logger()` returns the Spyglass plugin's `java.util.logging.Logger`.
Most plugins should prefer their own logger; reach for this only
when you want a diagnostic to surface under the Spyglass log
scope (e.g. inside an extension that's flagging malformed
extension config during startup).

---

## 8. Built-in event names

These names are what the bundled listeners record. You can use them
in `Eq`/`In` predicates, in `enabledEvents()` checks, or pass them to
`registerDisplayRenderer`. Custom event names you push via
`record()` are also valid — there's no registration step.

```
Block events: break, place, decay, form, grow, ignite, brush, vault
Block usage: use, useSign, sculk
Containers: open, close, deposit, withdraw,
                 shulker-open, shulker-close, shulker-deposit, shulker-withdraw,
                 bookshelf-insert, bookshelf-remove,
                 pot-insert, pot-remove,
                 bundle-insert, bundle-extract,
                 entity-deposit, entity-withdraw, crafter
Items: drop, pickup, clone
Combat / NPCs: death, hit, shot, mount, dismount, named
Player: join, quit, teleport
Chat: say, command
Rollback: rolled-place, rolled-break (synthesized — read-only)
```

---

## 9. Threading reference

| Method | Thread it's called ON | What you can do |
|--------|----------------------|-----------------|
| `record()` | Any | Anything; non-blocking. Fires `RecordCommittedEvent` synchronously |
| `query()` | Any (returns stage) | Chain stage continuations; the query body runs on a worker pool |
| `register*()` | Main, during `onEnable()` | Registration only |
| `enabledEvents()` / `queryParam*()` / `flag*()` / `displayRenderer()` / `rollbackEffectHandler()` / `limits()` / `logger()` | Any | Read-only lookup |
| `QueryParamHandler.parse()` | Main | Cache-only; no I/O |
| `QueryParamHandler.suggestions()` | Main | Same |
| `FlagHandler.parse()` | Main | Cache-only; no I/O |
| `FlagHandler.suggestions()` | Main | Same |
| `DisplayRenderer.renderTarget()` | Main | Build Components only |
| `DisplayRenderer.hoverLines()` | Main | Build Components only |
| `RollbackEffectHandler.apply()` | Main | World mutations OK; no long I/O |
| `RecordCommittedEvent` listener | Main or async (event auto-detects) | Treat as async-safe; hop to main for world access |

Inside a `thenAccept` continuation off `query()`, you're on the
async worker pool. Hop to the main thread before touching world
state:

```java
sg.query(request).thenAccept(result -> {
    Bukkit.getScheduler().runTask(plugin, () -> {
        // Touch world / inventory / etc. here.
    });
});
```

---

## 10. Versioning

The API jar follows semantic versioning. Within a major version:

- Methods will be added but never removed.
- Default methods on extension interfaces (`DisplayRenderer`,
  `QueryParamHandler`) shield existing implementors from new
  capabilities.
- Record fields are immutable and additive — new fields land on the
  end via new factory overloads, never reorder existing ones.

Pin to a specific minor version in your dependency declaration if
you need byte-for-byte stability. Otherwise depend on the major
version (`1.+` in Gradle) and stay forward-compatible.

---

## 11. Reference

- **Javadoc**: published as `spyglass-api-<version>-javadoc.jar`
  alongside the main artifact.
- **License**: see [LICENSE](LICENSE) — this API jar is shipped under
  the same terms as the plugin.
- **Issues / PRs**: https://github.com/medievalrp-net/Spyglass

For operator-side configuration (durability modes, retention,
limits), see the annotated `config.conf` shipped with the plugin.

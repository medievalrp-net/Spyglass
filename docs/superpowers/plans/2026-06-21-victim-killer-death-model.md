# Victim/Killer-Aware Death Model — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `p:<name> a:death` find a player's deaths and `p:<name> a:kill` find a player's kills, with mob kills captured as a separate `a:mob-kill` event.

**Architecture:** Record each lethal event from two perspectives so the relevant actor is the `source` (the only field `p:` matches): `death` flips to `source = victim`; new `kill` (player killer) and `mob-kill` (mob killer) reuse the existing `EntityHitRecord` shape (`source = killer`, `victimType`/`victimId = victim`), so no new record type or storage/codec change is needed. All three are emitted from `EntityDeathListener`.

**Tech Stack:** Java 21, Paper 1.21.x, Gradle (4 modules), JUnit 5 + Mockito + AssertJ. Backend in use: ClickHouse (no schema change required).

## Global Constraints

- Listeners record at `EventPriority.MONITOR`, `ignoreCancelled = true`; never block the main thread. (Entity NBT must be serialized at event time on the main thread — #129.)
- Structured logging only (plugin `Logger`; no `printStackTrace`/`System.out`).
- Event-type parity: this change reuses existing record types, so the parity touch-points are `EventCatalog` + emitting listener + `config.conf` default. Mongo codec and ClickHouse schema/mapper already handle `EntityHitRecord` and `EntityDeathRecord` — do not change them.
- Tests ship with non-trivial changes (jacoco floors: api 0.15/0.24, core 0.20, plugin 0.20).
- Work goes on a `feat/` branch, not `main`.
- Build/verify command: `./gradlew build` (run from repo root; Git Bash available).

---

## Task 0: Create the feature branch

- [ ] **Step 1: Branch off the current HEAD (has the spec commit)**

```bash
git checkout -b feat/victim-killer-death-model
git branch --show-current   # expect: feat/victim-killer-death-model
```

---

## Task 1: Register `kill` and `mob-kill` in the event catalog

**Files:**
- Modify: `spyglass-api/src/main/java/net/medievalrp/spyglass/api/event/EventCatalog.java` (the static `TYPES` map, after the `death`/`hit`/`shot` entries near line 59-60)
- Test: `spyglass-api/src/test/java/net/medievalrp/spyglass/api/event/EventCatalogTest.java`

**Interfaces:**
- Produces: event names `"kill"` and `"mob-kill"` both resolve via `EventCatalog.recordClassOf(name)` to `EntityHitRecord.class`.

- [ ] **Step 1: Write the failing test**

Add to `EventCatalogTest`:

```java
@Test
void killAndMobKillAreStoredAsEntityHitRecord() {
    assertThat(EventCatalog.recordClassOf("kill")).isEqualTo(EntityHitRecord.class);
    assertThat(EventCatalog.recordClassOf("mob-kill")).isEqualTo(EntityHitRecord.class);
    assertThat(EventCatalog.eventNames()).contains("kill", "mob-kill");
}
```

Ensure the test imports `net.medievalrp.spyglass.api.event.EntityHitRecord` and `static org.assertj.core.api.Assertions.assertThat`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spyglass-api:test --tests "net.medievalrp.spyglass.api.event.EventCatalogTest"`
Expected: FAIL — `recordClassOf("kill")` returns null.

- [ ] **Step 3: Add the catalog entries**

In `EventCatalog.java`, immediately after the line `m.put("death", EntityDeathRecord.class);` add:

```java
        // Killer-perspective mirror of a death (#kill). Reuses EntityHitRecord
        // (source = killer, victimType/victimId = victim) so both storage
        // backends already persist it — no codec/schema change. "kill" is a
        // player killer; "mob-kill" is a mob killer, split so a:kill stays
        // PvP/player-vs-mob and a:mob-kill is independently toggleable.
        m.put("kill", EntityHitRecord.class);
        m.put("mob-kill", EntityHitRecord.class);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :spyglass-api:test --tests "net.medievalrp.spyglass.api.event.EventCatalogTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add spyglass-api/src/main/java/net/medievalrp/spyglass/api/event/EventCatalog.java \
        spyglass-api/src/test/java/net/medievalrp/spyglass/api/event/EventCatalogTest.java
git commit -m "feat: register kill/mob-kill events as EntityHitRecord"
```

---

## Task 2: Flip `death` to victim-source and emit `kill`/`mob-kill`

This is the core change. `EntityDeathListener` gains the enabled-events set so it can gate the three death-family events independently (the plugin only gates at listener-registration granularity, so per-event gating must live in the listener).

**Files:**
- Modify: `spyglass/src/main/java/net/medievalrp/spyglass/plugin/listener/entity/EntityDeathListener.java`
- Modify: `spyglass/src/main/java/net/medievalrp/spyglass/plugin/SpyglassPlugin.java` (the `new EntityDeathListener(recorder, support)` construction inside the `listeners` list, ~line 323-368)
- Test: `spyglass/src/test/java/net/medievalrp/spyglass/plugin/listener/entity/EntityDeathListenerTest.java` (new)

**Interfaces:**
- Consumes: `EventCatalog` names `kill`/`mob-kill` (Task 1); `RecordingSupport.playerSource(Player)`, `entitySource(UUID,String)`, `playerOrigin()`, `environmentOrigin(String)`, `context(Origin,Source,BlockLocation)`, `expiresAt`, `serverName`, `newId`; `EntityDeathRecord.of(ctx, target, entityType, entityId, killerType, damageCause, nbt)`; `EntityHitRecord.of(ctx, event, target, victimType, victimId, damage, projectile, projectileType)`.
- Produces: new constructor `EntityDeathListener(Recorder recorder, RecordingSupport support, Set<String> enabledEvents)`; `events()` returns `{"death","drop","kill","mob-kill"}`.

- [ ] **Step 1: Write the failing test**

Create `EntityDeathListenerTest.java`:

```java
package net.medievalrp.spyglass.plugin.listener.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EntityHitRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EntityDeathListenerTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VICTIM = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MOB = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID WORLD_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private static final Set<String> ALL =
            Set.of("death", "drop", "kill", "mob-kill");

    private CapturingRecorder recorder;
    private RecordingSupport support;

    @BeforeEach
    void setUp() {
        recorder = new CapturingRecorder();
        support = new RecordingSupport(new Duration(3600), "test");
    }

    @Test
    void declaresAllDeathFamilyEvents() {
        EntityDeathListener listener = new EntityDeathListener(recorder, support, ALL);
        assertThat(listener.events()).isEqualTo(Set.of("death", "drop", "kill", "mob-kill"));
    }

    @Test
    void playerKillingMobEmitsDeathFromVictimAndKillFromPlayer() {
        Player alice = mockPlayer(ALICE, "Alice");
        LivingEntity zombie = mockLiving(VICTIM, EntityType.ZOMBIE);
        when(zombie.getKiller()).thenReturn(alice);
        stubDamageByEntity(zombie, alice, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 6.0);
        EntityDeathEvent event = mockDeath(zombie);

        new EntityDeathListener(recorder, support, ALL).onEntityDeath(event);

        EntityDeathRecord death = (EntityDeathRecord) findEvent("death");
        assertThat(death.source().entityType()).isEqualTo("zombie");
        assertThat(death.target()).isEqualTo("Alice");
        assertThat(death.killerType()).isEqualTo("player");

        EntityHitRecord kill = (EntityHitRecord) findEvent("kill");
        assertThat(kill.source().playerName()).isEqualTo("Alice");
        assertThat(kill.target()).isEqualTo("zombie");
        assertThat(kill.victimType()).isEqualTo("zombie");
    }

    @Test
    void mobKillingPlayerEmitsDeathFromVictimAndMobKillFromMob() {
        Player bob = mockPlayer(BOB, "Bob");
        when(bob.getType()).thenReturn(EntityType.PLAYER);
        when(bob.getKiller()).thenReturn(null);
        LivingEntity zombie = mockLiving(MOB, EntityType.ZOMBIE);
        stubDamageByEntity(bob, zombie, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 4.0);
        EntityDeathEvent event = mockDeath(bob);

        new EntityDeathListener(recorder, support, ALL).onEntityDeath(event);

        EntityDeathRecord death = (EntityDeathRecord) findEvent("death");
        assertThat(death.source().playerName()).isEqualTo("Bob");
        assertThat(death.target()).isEqualTo("zombie");

        EntityHitRecord mobKill = (EntityHitRecord) findEvent("mob-kill");
        assertThat(mobKill.source().entityType()).isEqualTo("zombie");
        assertThat(mobKill.target()).isEqualTo("Bob");
        assertThat(mobKill.victimType()).isEqualTo("player");
    }

    @Test
    void environmentDeathEmitsOnlyDeathFromVictim() {
        Player bob = mockPlayer(BOB, "Bob");
        when(bob.getType()).thenReturn(EntityType.PLAYER);
        when(bob.getKiller()).thenReturn(null);
        EntityDamageEvent fall = mock(EntityDamageEvent.class);
        when(fall.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
        when(fall.getFinalDamage()).thenReturn(8.0);
        when(bob.getLastDamageCause()).thenReturn(fall);
        EntityDeathEvent event = mockDeath(bob);

        new EntityDeathListener(recorder, support, ALL).onEntityDeath(event);

        assertThat(recorder.records).hasSize(1);
        EntityDeathRecord death = (EntityDeathRecord) recorder.records.get(0);
        assertThat(death.event()).isEqualTo("death");
        assertThat(death.source().playerName()).isEqualTo("Bob");
        assertThat(death.target()).isEqualTo("FALL");
        assertThat(death.damageCause()).isEqualTo("FALL");
    }

    @Test
    void killToggleDisabledSuppressesKillButKeepsDeath() {
        Player alice = mockPlayer(ALICE, "Alice");
        LivingEntity zombie = mockLiving(VICTIM, EntityType.ZOMBIE);
        when(zombie.getKiller()).thenReturn(alice);
        stubDamageByEntity(zombie, alice, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 6.0);
        EntityDeathEvent event = mockDeath(zombie);

        new EntityDeathListener(recorder, support, Set.of("death", "drop"))
                .onEntityDeath(event);

        assertThat(recorder.records).hasSize(1);
        assertThat(recorder.records.get(0).event()).isEqualTo("death");
    }

    private EventRecord findEvent(String name) {
        return recorder.records.stream()
                .filter(r -> r.event().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no record for event " + name
                        + " in " + recorder.records.stream().map(EventRecord::event).toList()));
    }

    private static Player mockPlayer(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.getType()).thenReturn(EntityType.PLAYER);
        stubLocation(player);
        return player;
    }

    private static LivingEntity mockLiving(UUID id, EntityType type) {
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.getUniqueId()).thenReturn(id);
        when(entity.getType()).thenReturn(type);
        stubLocation(entity);
        return entity;
    }

    private static void stubLocation(LivingEntity entity) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");
        Location location = new Location(world, 10, 64, 20);
        when(entity.getLocation()).thenReturn(location);
    }

    private static void stubDamageByEntity(LivingEntity victim, org.bukkit.entity.Entity damager,
                                           EntityDamageEvent.DamageCause cause, double finalDamage) {
        EntityDamageByEntityEvent dmg = mock(EntityDamageByEntityEvent.class);
        when(dmg.getCause()).thenReturn(cause);
        when(dmg.getDamager()).thenReturn(damager);
        when(dmg.getFinalDamage()).thenReturn(finalDamage);
        when(victim.getLastDamageCause()).thenReturn(dmg);
    }

    @SuppressWarnings("unchecked")
    private static EntityDeathEvent mockDeath(LivingEntity victim) {
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDrops()).thenReturn(List.of());
        return event;
    }

    private static final class CapturingRecorder implements Recorder {
        final List<EventRecord> records = new java.util.ArrayList<>();

        @Override
        public void record(EventRecord record) {
            records.add(record);
        }

        @Override
        public AsyncRecorder.ShutdownReport shutdown(Duration timeout) {
            return new AsyncRecorder.ShutdownReport(records.size(), 0, 0);
        }

        @Override
        public boolean flush(Duration timeout) {
            return true;
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spyglass:test --tests "net.medievalrp.spyglass.plugin.listener.entity.EntityDeathListenerTest"`
Expected: FAIL — constructor `EntityDeathListener(Recorder, RecordingSupport, Set)` does not exist / death source is still the killer.

- [ ] **Step 3: Rewrite `EntityDeathListener`**

Replace the whole class body of `EntityDeathListener.java` with:

```java
package net.medievalrp.spyglass.plugin.listener.entity;

import java.util.Base64;
import java.util.Set;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EntityHitRecord;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class EntityDeathListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;
    // Per-event gating: the plugin only gates at listener-registration
    // granularity, so the independent kill / mob-kill / death toggles must be
    // honoured here. Live, thread-safe view of the enabled set.
    private final Set<String> enabledEvents;

    public EntityDeathListener(Recorder recorder, RecordingSupport support, Set<String> enabledEvents) {
        this.recorder = recorder;
        this.support = support;
        this.enabledEvents = enabledEvents;
    }

    @Override
    public Set<String> events() {
        return Set.of("death", "drop", "kill", "mob-kill");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        BlockLocation location = BlockLocations.fromLocation(victim.getLocation());
        String victimType = victim.getType().getKey().getKey();
        java.util.UUID victimId = victim.getUniqueId();
        String victimDisplay = victim instanceof Player p ? p.getName() : victimType;

        String damageCause = victim.getLastDamageCause() != null
                ? victim.getLastDamageCause().getCause().name()
                : "UNKNOWN";

        // Resolve the killer perspective. Bukkit only fills getKiller() for
        // player killers; for mob killers we dig the damaging entity out of the
        // last damage event (resolving a projectile to its shooter), mirroring
        // EntityDamageListener.
        Player playerKiller = victim.getKiller();
        LivingEntity mobKiller = null;
        boolean projectile = false;
        String projectileType = null;
        if (victim.getLastDamageCause() instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile proj) {
                projectile = true;
                projectileType = damager.getType().getKey().getKey();
                if (proj.getShooter() instanceof Entity shooter) {
                    damager = shooter;
                }
            }
            if (playerKiller == null) {
                if (damager instanceof Player p) {
                    playerKiller = p;          // edge case getKiller() missed
                } else if (damager instanceof LivingEntity living) {
                    mobKiller = living;
                }
            }
        }

        Origin deathOrigin;
        String killerType;
        String deathTarget;
        if (playerKiller != null) {
            deathOrigin = support.playerOrigin();
            killerType = "player";
            deathTarget = playerKiller.getName();
        } else if (mobKiller != null) {
            deathOrigin = support.environmentOrigin("death:" + damageCause);
            killerType = mobKiller.getType().getKey().getKey();
            deathTarget = killerType;
        } else {
            deathOrigin = support.environmentOrigin("death:" + damageCause);
            killerType = damageCause;
            deathTarget = damageCause;
        }
        Source victimSource = victim instanceof Player p
                ? support.playerSource(p)
                : support.entitySource(victimId, victimType);

        // Entity NBT is rollbackable; serialize at event time on the main
        // thread — NMS reads aren't thread-safe and the victim is gone once the
        // event returns (#129).
        String nbt = serializeEntity(victim);

        if (enabledEvents.contains("death")) {
            RecordContext deathCtx = support.context(deathOrigin, victimSource, location);
            recorder.record(EntityDeathRecord.of(deathCtx, deathTarget, victimType, victimId,
                    killerType, damageCause, nbt));
        }

        double damage = victim.getLastDamageCause() != null
                ? victim.getLastDamageCause().getFinalDamage() : 0.0;
        if (playerKiller != null && enabledEvents.contains("kill")) {
            RecordContext killCtx = support.context(
                    support.playerOrigin(), support.playerSource(playerKiller), location);
            recorder.record(EntityHitRecord.of(killCtx, "kill", victimDisplay,
                    victimType, victimId, damage, projectile, projectileType));
        } else if (mobKiller != null && enabledEvents.contains("mob-kill")) {
            String mobType = mobKiller.getType().getKey().getKey();
            RecordContext killCtx = support.context(
                    support.environmentOrigin("kill:" + mobType),
                    support.entitySource(mobKiller.getUniqueId(), mobType), location);
            recorder.record(EntityHitRecord.of(killCtx, "mob-kill", victimDisplay,
                    victimType, victimId, damage, projectile, projectileType));
        }

        // Per-item drop records so loot tables stay searchable. Unchanged:
        // skip players (their drops are noisy / handled elsewhere); source is
        // the dead entity, origin is whoever triggered the death.
        if (victim instanceof Player) {
            return;
        }
        Source dropSource = support.entitySource(victimId, victimType);
        for (ItemStack drop : event.getDrops()) {
            StoredItem stored = ItemSerialization.storedItemProjection(0, drop);
            if (stored == null) {
                continue;
            }
            RecordContext dropCtx = support.context(deathOrigin, dropSource, location);
            recorder.record(ItemDropRecord.of(dropCtx, drop.getType().name(), drop.getAmount(), stored));
        }
    }

    private static String serializeEntity(LivingEntity entity) {
        try {
            byte[] bytes = Bukkit.getUnsafe().serializeEntity(entity);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Throwable thrown) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Wire the enabled set into the listener in `SpyglassPlugin`**

In `SpyglassPlugin.java`, find the `listeners` list construction and change:

```java
                new EntityDeathListener(recorder, support),
```
to:
```java
                new EntityDeathListener(recorder, support, enabledEvents),
```

(`enabledEvents` is already in scope — declared ~line 307.)

- [ ] **Step 5: Run the listener test + compile the plugin module**

Run: `./gradlew :spyglass:test --tests "net.medievalrp.spyglass.plugin.listener.entity.EntityDeathListenerTest"`
Expected: PASS (all five tests).

> If `EntityType.ZOMBIE.getKey()` throws headless (registry not loaded), fall back to stubbing the key chain: `when(zombie.getType()).thenReturn(type)` plus a real `NamespacedKey` is not needed — `EntityType` is an enum carrying its own key, so this should pass; only investigate if the run errors on registry access.

- [ ] **Step 6: Commit**

```bash
git add spyglass/src/main/java/net/medievalrp/spyglass/plugin/listener/entity/EntityDeathListener.java \
        spyglass/src/main/java/net/medievalrp/spyglass/plugin/SpyglassPlugin.java \
        spyglass/src/test/java/net/medievalrp/spyglass/plugin/listener/entity/EntityDeathListenerTest.java
git commit -m "feat: death records victim-as-source; emit kill/mob-kill records"
```

---

## Task 3: Config defaults + display verbs

`death`'s verb must change from "killed" to "died" (its source is now the victim), and `kill`/`mob-kill` need verb + enabled entries. The Paper renderer reads verbs from config (no code change). The Velocity proxy renderer bakes verbs into a static map (code change).

**Files:**
- Modify: `spyglass/src/main/resources/config.conf` (events block, ~line 267)
- Modify: `spyglass-velocity/src/main/java/net/medievalrp/spyglass/proxy/command/ProxyResultRenderer.java` (the `PAST_TENSE` map, ~line 85)
- Test: `spyglass/src/test/java/net/medievalrp/spyglass/plugin/command/render/ResultRendererTest.java`

**Interfaces:**
- Consumes: catalog names from Task 1; records from Task 2.
- Produces: `death` renders inline as `<victim> died <killer/cause>`; `kill`/`mob-kill` render as `<killer> killed <victim>`.

- [ ] **Step 1: Write the failing renderer test**

Add to `ResultRendererTest` (mirror the file's existing record-construction + render-to-plain-text helpers; use the existing helper that converts a rendered `Component` to a plain string — match how sibling tests assert inline text):

```java
@Test
void deathRendersVictimDiedKiller() {
    EntityDeathRecord death = EntityDeathRecord.of(
            ctx(Source.entity(UUID.randomUUID(), "zombie")),
            "Alice", "zombie", UUID.randomUUID(), "player", "ENTITY_ATTACK", null);
    String line = renderInline(death);
    assertThat(line).contains("died").contains("ALICE".toLowerCase()); // verb present
    assertThat(line).contains("died");
}

@Test
void killRendersKillerKilledVictim() {
    EntityHitRecord kill = EntityHitRecord.of(
            ctx(Source.player(UUID.randomUUID(), "Alice")),
            "kill", "zombie", "zombie", UUID.randomUUID(), 6.0, false, null);
    String line = renderInline(kill);
    assertThat(line).contains("Alice").contains("killed").contains("ZOMBIE");
}
```

> Adapt `ctx(...)` / `renderInline(...)` to the test file's actual existing helpers (it already constructs records and renders them — reuse those exact helpers rather than inventing new ones). The behavioural assertion that matters: `death` → verb "died"; `kill` → verb "killed" with victim as target.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spyglass:test --tests "net.medievalrp.spyglass.plugin.command.render.ResultRendererTest"`
Expected: FAIL — `death` currently renders verb "killed".

- [ ] **Step 3: Update `config.conf`**

Change the `death` line and add two new lines in the events block:

```hocon
  death = { enabled = true, past-tense = "died" }
  kill = { enabled = true, past-tense = "killed" }
  mob-kill = { enabled = true, past-tense = "killed" }
  hit = { enabled = true, past-tense = "hit" }
  shot = { enabled = true, past-tense = "shot" }
```

- [ ] **Step 4: Update the proxy renderer verb map**

In `ProxyResultRenderer.java`, change the `death` entry and add `kill`/`mob-kill` in the `PAST_TENSE` map:

```java
            Map.entry("death", "died"),
            Map.entry("kill", "killed"),
            Map.entry("mob-kill", "killed"),
            Map.entry("hit", "hit"),
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :spyglass:test --tests "net.medievalrp.spyglass.plugin.command.render.ResultRendererTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add spyglass/src/main/resources/config.conf \
        spyglass-velocity/src/main/java/net/medievalrp/spyglass/proxy/command/ProxyResultRenderer.java \
        spyglass/src/test/java/net/medievalrp/spyglass/plugin/command/render/ResultRendererTest.java
git commit -m "feat: death verb 'died'; kill/mob-kill enabled + verb 'killed'"
```

---

## Task 4: Storage round-trip for `kill`/`mob-kill`

Confirm a `kill` and a `mob-kill` record persist and decode back as `EntityHitRecord` with the correct event name. The fast SQLite unit test is the gate; the ClickHouse IT mirrors it for the in-use backend.

**Files:**
- Test: `spyglass-core/src/test/java/net/medievalrp/spyglass/plugin/storage/SqliteRecordStoreTest.java`
- Test (mirror, optional run — needs Docker): `spyglass-core/src/test/java/net/medievalrp/spyglass/plugin/storage/ClickHouseRecordStoreIT.java`

**Interfaces:**
- Consumes: `EventCatalog` mapping from Task 1; `EntityHitRecord` (existing).

- [ ] **Step 1: Write the failing SQLite test**

Add to `SqliteRecordStoreTest` (reuse the file's existing store fixture + a context/`now`/`expiresAt` helper exactly as the sibling tests do):

```java
@Test
void roundTripsKillAndMobKillAsEntityHitRecord() {
    EntityHitRecord kill = EntityHitRecord.of(
            ctx(Source.player(ALICE, "Alice")),
            "kill", "zombie", "zombie", UUID.randomUUID(), 6.0, false, null);
    EntityHitRecord mobKill = EntityHitRecord.of(
            ctx(Source.entity(UUID.randomUUID(), "zombie")),
            "mob-kill", "Bob", "player", UUID.randomUUID(), 4.0, false, null);
    store.save(List.of(kill, mobKill));

    List<EventRecord> kills = store.querySummary(
            requestForEvent("kill")).records();
    assertThat(kills).singleElement().isInstanceOf(EntityHitRecord.class)
            .extracting(EventRecord::event).isEqualTo("kill");

    List<EventRecord> mobKills = store.querySummary(
            requestForEvent("mob-kill")).records();
    assertThat(mobKills).singleElement().isInstanceOf(EntityHitRecord.class)
            .extracting(EventRecord::event).isEqualTo("mob-kill");
}
```

> Use the file's existing query-builder helper for "all records with event = X" (the test file already builds `QueryRequest`s for other events — copy that exact pattern; do not invent a new request shape). If the file lacks one, build a `QueryRequest` with an `Eq("event", "kill")` predicate the same way `EventParam` does.

- [ ] **Step 2: Run test to verify it fails or passes**

Run: `./gradlew :spyglass-core:test --tests "net.medievalrp.spyglass.plugin.storage.SqliteRecordStoreTest"`
Expected: PASS once Task 1's catalog mapping is in place (decode resolves `kill`/`mob-kill` → `EntityHitRecord`). If it FAILS with an unknown-event/decoding error, that confirms Task 1 is required and correct; re-run after Task 1.

- [ ] **Step 3: Mirror in the ClickHouse IT (no run required here)**

Add an analogous `roundTripsKillAndMobKillAsEntityHitRecord()` to `ClickHouseRecordStoreIT` following that file's container fixture + assertion style. It runs under the Docker-gated IT profile.

- [ ] **Step 4: Commit**

```bash
git add spyglass-core/src/test/java/net/medievalrp/spyglass/plugin/storage/SqliteRecordStoreTest.java \
        spyglass-core/src/test/java/net/medievalrp/spyglass/plugin/storage/ClickHouseRecordStoreIT.java
git commit -m "test: kill/mob-kill round-trip as EntityHitRecord"
```

---

## Task 5: Full build + manual query sanity

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; jacoco floors met.

- [ ] **Step 2: Confirm the requested queries are now expressible**

These are the acceptance queries (verify behaviour in-game after deploy, documented here for the reviewer):
- `spyglass search p:the60th a:death` → rows where the60th is the victim.
- `spyglass search p:the60th a:kill` → rows where the60th is the killer.
- `spyglass search a:mob-kill` → mob killings.

- [ ] **Step 3: Final commit if any build-driven fixes were needed**

```bash
git add -A
git commit -m "chore: build fixes for victim/killer death model" || echo "nothing to commit"
```

---

## Self-Review (completed by plan author)

- **Spec coverage:** death-flip (Task 2), kill (Task 2), mob-kill (Task 2 + toggle gating), catalog reuse of `EntityHitRecord` (Task 1), config defaults + verbs (Task 3), display (Task 3), storage no-change verified by round-trip (Task 4), historical-data decision = no migration (out of scope, documented in spec), testing across listener/render/storage (Tasks 2-4). All spec sections map to a task.
- **Placeholder scan:** no TBD/TODO; all code shown. Two test helpers (`ctx`/`renderInline`/`requestForEvent`) are explicitly delegated to the target test file's existing helpers with instructions — these are real, existing patterns in those files, not placeholders.
- **Type consistency:** constructor `EntityDeathListener(Recorder, RecordingSupport, Set<String>)` used consistently in Task 2 impl, test, and SpyglassPlugin wiring; `EntityHitRecord.of(ctx, event, target, victimType, victimId, damage, projectile, projectileType)` and `EntityDeathRecord.of(ctx, target, entityType, entityId, killerType, damageCause, nbt)` match the real signatures; event names `kill`/`mob-kill` consistent across catalog, listener, config, renderer, storage.
```

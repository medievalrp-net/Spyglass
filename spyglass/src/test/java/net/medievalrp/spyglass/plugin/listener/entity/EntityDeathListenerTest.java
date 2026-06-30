package net.medievalrp.spyglass.plugin.listener.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EntityHitRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EntityDeathListenerTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VICTIM = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MOB = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID WORLD_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private static final Set<String> ALL = Set.of("death", "drop", "kill", "mob-kill");
    // Same-thread serializer: the inline tests below assert on records
    // synchronously, so the deferred drop projection (#188) runs immediately.
    // The deferral-specific tests use a capturing executor instead.
    private static final Executor DIRECT = Runnable::run;

    private CapturingRecorder recorder;
    private RecordingSupport support;

    @BeforeEach
    void setUp() {
        recorder = new CapturingRecorder();
        support = new RecordingSupport(new Duration(3600), "test");
    }

    @Test
    void declaresAllDeathFamilyEvents() {
        EntityDeathListener listener = new EntityDeathListener(recorder, support, ALL, DIRECT);
        assertThat(listener.events()).isEqualTo(Set.of("death", "drop", "kill", "mob-kill"));
    }

    @Test
    void playerKillingMobEmitsDeathFromVictimAndKillFromPlayer() {
        Player alice = mockPlayer(ALICE, "Alice");
        LivingEntity zombie = mockLiving(VICTIM, EntityType.ZOMBIE);
        when(zombie.getKiller()).thenReturn(alice);
        stubDamageByEntity(zombie, alice, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 6.0);
        EntityDeathEvent event = mockDeath(zombie);

        new EntityDeathListener(recorder, support, ALL, DIRECT).onEntityDeath(event);

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
        when(bob.getKiller()).thenReturn(null);
        LivingEntity zombie = mockLiving(MOB, EntityType.ZOMBIE);
        stubDamageByEntity(bob, zombie, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 4.0);
        EntityDeathEvent event = mockDeath(bob);

        new EntityDeathListener(recorder, support, ALL, DIRECT).onEntityDeath(event);

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
        when(bob.getKiller()).thenReturn(null);
        EntityDamageEvent fall = mock(EntityDamageEvent.class);
        when(fall.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
        when(fall.getFinalDamage()).thenReturn(8.0);
        when(bob.getLastDamageCause()).thenReturn(fall);
        EntityDeathEvent event = mockDeath(bob);

        new EntityDeathListener(recorder, support, ALL, DIRECT).onEntityDeath(event);

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

        new EntityDeathListener(recorder, support, Set.of("death", "drop"), DIRECT)
                .onEntityDeath(event);

        assertThat(recorder.records).hasSize(1);
        assertThat(recorder.records.get(0).event()).isEqualTo("death");
    }

    @Test
    void playerDeathDropsRecordedAsDropAttributedToVictim() {
        // A player's inventory spills via EntityDeathEvent.getDrops(), not
        // PlayerDropItemEvent, so these must be captured here, attributed to
        // the dead player so `p:<name> a:drop` finds them.
        Player bob = mockPlayer(BOB, "Bob");
        when(bob.getKiller()).thenReturn(null);
        EntityDamageEvent fall = mock(EntityDamageEvent.class);
        when(fall.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
        when(fall.getFinalDamage()).thenReturn(8.0);
        when(bob.getLastDamageCause()).thenReturn(fall);
        ItemStack diamond = mockStack(Material.DIAMOND, 2);
        ItemStack iron = mockStack(Material.IRON_INGOT, 5);
        EntityDeathEvent event = mockDeath(bob);
        when(event.getDrops()).thenReturn(List.of(diamond, iron));

        new EntityDeathListener(recorder, support, ALL, DIRECT).onEntityDeath(event);

        List<EventRecord> drops = recorder.records.stream()
                .filter(r -> r.event().equals("drop")).toList();
        assertThat(drops).hasSize(2);
        assertThat(drops).allSatisfy(r ->
                assertThat(r.source().playerName()).isEqualTo("Bob"));
        assertThat(((ItemDropRecord) drops.get(0)).target()).isEqualTo("DIAMOND");
        assertThat(((ItemDropRecord) drops.get(1)).target()).isEqualTo("IRON_INGOT");
    }

    @Test
    void dropProjectionIsDeferredWhileDeathRecordsInlineAtEventTime() {
        // #188: the only main-thread work per drop is clone + type/amount +
        // context; the storedItemProjection build is handed to the serializer.
        // The death record (main-thread-only entity NBT) still records inline.
        Player bob = mockPlayer(BOB, "Bob");
        stubFallDeath(bob);
        ItemStack diamond = mockStack(Material.DIAMOND, 2);
        ItemStack iron = mockStack(Material.IRON_INGOT, 5);
        EntityDeathEvent event = mockDeath(bob);
        when(event.getDrops()).thenReturn(List.of(diamond, iron));

        List<Runnable> deferred = new ArrayList<>();
        new EntityDeathListener(recorder, support, ALL, deferred::add).onEntityDeath(event);

        assertThat(recorder.records).singleElement()
                .satisfies(r -> assertThat(r.event()).isEqualTo("death"));
        assertThat(deferred).hasSize(2);

        deferred.forEach(Runnable::run);

        List<EventRecord> drops = recorder.records.stream()
                .filter(r -> r.event().equals("drop")).toList();
        assertThat(drops).hasSize(2);
        assertThat(drops).allSatisfy(r ->
                assertThat(r.source().playerName()).isEqualTo("Bob"));
        assertThat(((ItemDropRecord) drops.get(0)).target()).isEqualTo("DIAMOND");
        assertThat(((ItemDropRecord) drops.get(0)).amount()).isEqualTo(2);
        assertThat(((ItemDropRecord) drops.get(0)).item().material()).isEqualTo("DIAMOND");
        assertThat(((ItemDropRecord) drops.get(1)).target()).isEqualTo("IRON_INGOT");
        assertThat(((ItemDropRecord) drops.get(1)).amount()).isEqualTo(5);
    }

    @Test
    void dropStampsOccurredAndIdAtEventTimeNotSerializationTime() {
        Player bob = mockPlayer(BOB, "Bob");
        stubFallDeath(bob);
        ItemStack diamond = mockStack(Material.DIAMOND, 1);
        EntityDeathEvent event = mockDeath(bob);
        when(event.getDrops()).thenReturn(List.of(diamond));

        List<Runnable> deferred = new ArrayList<>();
        Instant before = Instant.now();
        new EntityDeathListener(recorder, support, ALL, deferred::add).onEntityDeath(event);
        Instant after = Instant.now();

        // Built only when the deferred task runs, strictly after the handling
        // window, yet occurred + id (stamped via RecordContext on the main
        // thread) must reflect event time, not serialization time.
        deferred.forEach(Runnable::run);

        ItemDropRecord drop = (ItemDropRecord) recorder.records.stream()
                .filter(r -> r.event().equals("drop")).findFirst().orElseThrow();
        assertThat(drop.occurred()).isBetween(before, after);
        assertThat(drop.id()).isNotNull();
    }

    @Test
    void dropClonesStackOnHandlingThreadBeforeDeferring() {
        Player bob = mockPlayer(BOB, "Bob");
        stubFallDeath(bob);
        ItemStack diamond = mockStack(Material.DIAMOND, 2);
        EntityDeathEvent event = mockDeath(bob);
        when(event.getDrops()).thenReturn(List.of(diamond));

        List<Runnable> deferred = new ArrayList<>();
        new EntityDeathListener(recorder, support, ALL, deferred::add).onEntityDeath(event);

        // The snapshot is taken synchronously, before the deferred task could
        // observe a consumed/mutated live stack.
        verify(diamond).clone();
        assertThat(deferred).hasSize(1);
        assertThat(recorder.records).noneSatisfy(r ->
                assertThat(r.event()).isEqualTo("drop"));
    }

    @Test
    void airDropIsSkippedWithoutCloningOrDeferring() {
        Player bob = mockPlayer(BOB, "Bob");
        stubFallDeath(bob);
        ItemStack air = mock(ItemStack.class);
        when(air.getType()).thenReturn(Material.AIR);
        EntityDeathEvent event = mockDeath(bob);
        when(event.getDrops()).thenReturn(List.of(air));

        List<Runnable> deferred = new ArrayList<>();
        new EntityDeathListener(recorder, support, ALL, deferred::add).onEntityDeath(event);

        assertThat(deferred).isEmpty();
        verify(air, never()).clone();
        assertThat(recorder.records).noneSatisfy(r ->
                assertThat(r.event()).isEqualTo("drop"));
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
        // Location holds the world weakly; stub getWorld() so Mockito keeps a
        // strong reference and a mid-test GC can't clear it (mirrors
        // ChatListenerTest's "World unloaded" fix).
        when(entity.getWorld()).thenReturn(world);
    }

    private static void stubDamageByEntity(LivingEntity victim, org.bukkit.entity.Entity damager,
                                           EntityDamageEvent.DamageCause cause, double finalDamage) {
        EntityDamageByEntityEvent dmg = mock(EntityDamageByEntityEvent.class);
        when(dmg.getCause()).thenReturn(cause);
        when(dmg.getDamager()).thenReturn(damager);
        when(dmg.getFinalDamage()).thenReturn(finalDamage);
        when(victim.getLastDamageCause()).thenReturn(dmg);
    }

    private static ItemStack mockStack(Material material, int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.getItemMeta()).thenReturn(null);
        // The drop is cloned on the main thread and the projection is built
        // off-thread from that clone (#188), so the clone needs its type too.
        ItemStack clone = mock(ItemStack.class);
        when(clone.getType()).thenReturn(material);
        when(clone.getAmount()).thenReturn(amount);
        when(clone.getItemMeta()).thenReturn(null);
        when(stack.clone()).thenReturn(clone);
        return stack;
    }

    private static void stubFallDeath(Player victim) {
        when(victim.getKiller()).thenReturn(null);
        EntityDamageEvent fall = mock(EntityDamageEvent.class);
        when(fall.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
        when(fall.getFinalDamage()).thenReturn(8.0);
        when(victim.getLastDamageCause()).thenReturn(fall);
    }

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

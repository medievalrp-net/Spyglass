package net.medievalrp.spyglass.plugin.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.util.Duration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

/**
 * {@link PlayerSnapshotService} capture behavior: count normalization, the
 * dirty-hash skip, cause labeling, and the quit-time cache eviction.
 *
 * <p>{@code ItemStack.serializeAsBytes()} is a real CraftBukkit call that
 * can't run under a plain Mockito stack, so every test injects a fake
 * serializer via the package-private constructor - the production
 * constructor still wires the real {@code ItemStack::serializeAsBytes}. The
 * executor is a same-thread {@link DirectExecutorService} so a capture's
 * off-main work has completed by the time {@code capture()} returns, with no
 * latches or timeouts needed.
 */
class PlayerSnapshotServiceTest {

    // Keys purely off material: production normalizes amount to 1 before
    // serializing, so two stacks of the same material always produce the
    // same "payload" here, exactly like the real serializeAsBytes would for
    // two otherwise-identical stacks differing only in count.
    private static final Function<ItemStack, byte[]> FAKE_SERIALIZER =
            stack -> stack.getType().name().getBytes(StandardCharsets.UTF_8);

    @Test
    void countNormalizationProducesSamePayloadWithDifferentCounts() {
        FakeStore store = new FakeStore();
        PlayerSnapshotService service = newService(store);

        Player alice = player(UUID.randomUUID(), "Alice", inventoryOf(stack(Material.DIAMOND, 5)));
        Player bob = player(UUID.randomUUID(), "Bob", inventoryOf(stack(Material.DIAMOND, 64)));

        service.capture(alice, PlayerSnapshot.CAUSE_SWEEP);
        service.capture(bob, PlayerSnapshot.CAUSE_SWEEP);

        assertThat(store.saved).hasSize(2);
        SnapshotSlot aliceSlot = store.saved.get(0).slots().get(0);
        SnapshotSlot bobSlot = store.saved.get(1).slots().get(0);
        assertThat(aliceSlot.item().data())
                .as("normalized payload must not depend on stack size")
                .isEqualTo(bobSlot.item().data());
        assertThat(aliceSlot.count()).isEqualTo(5);
        assertThat(bobSlot.count()).isEqualTo(64);
    }

    @Test
    void identicalInventoryTwiceSkipsTheSecondSave() {
        FakeStore store = new FakeStore();
        PlayerSnapshotService service = newService(store);
        Player steve = player(UUID.randomUUID(), "Steve", inventoryOf(stack(Material.STONE, 10)));

        service.capture(steve, PlayerSnapshot.CAUSE_JOIN);
        service.capture(steve, PlayerSnapshot.CAUSE_SWEEP);

        assertThat(store.saved)
                .as("dirty-equal capture must skip the write entirely")
                .hasSize(1);
    }

    @Test
    void hashChangesWhenASlotChanges() {
        FakeStore store = new FakeStore();
        PlayerSnapshotService service = newService(store);
        UUID uuid = UUID.randomUUID();
        PlayerInventory inventory = mock(PlayerInventory.class);
        Player steve = mockPlayer(uuid, "Steve", inventory);

        // Stack construction (its own nested when/thenReturn pairs) must fully
        // finish as separate statements before the outer when(...).thenReturn(...)
        // below - interleaving them mid-chain confuses Mockito's stubbing state
        // (UnfinishedStubbingException).
        ItemStack[] withStone = inventoryOf(stack(Material.STONE, 1));
        when(inventory.getContents()).thenReturn(withStone);
        service.capture(steve, PlayerSnapshot.CAUSE_SWEEP);

        ItemStack[] withDirt = inventoryOf(stack(Material.DIRT, 1));
        when(inventory.getContents()).thenReturn(withDirt);
        service.capture(steve, PlayerSnapshot.CAUSE_SWEEP);

        assertThat(store.saved).hasSize(2);
        assertThat(store.saved.get(0).contentHash())
                .as("a changed slot must change the content hash")
                .isNotEqualTo(store.saved.get(1).contentHash());
    }

    @Test
    void emptySlotsAreAbsentFromTheSnapshot() {
        FakeStore store = new FakeStore();
        PlayerSnapshotService service = newService(store);
        ItemStack[] contents = new ItemStack[41];
        contents[3] = stack(Material.DIAMOND, 2);
        contents[5] = stack(Material.AIR, 1); // explicit air stack - must be excluded too
        Player steve = player(UUID.randomUUID(), "Steve", contents);

        service.capture(steve, PlayerSnapshot.CAUSE_SWEEP);

        assertThat(store.saved).hasSize(1);
        List<SnapshotSlot> slots = store.saved.get(0).slots();
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).slot()).isEqualTo(3);
    }

    @Test
    void causeIsRecordedVerbatimForEveryCapturePoint() {
        FakeStore store = new FakeStore();
        PlayerSnapshotService service = newService(store);
        List<String> causes = List.of(
                PlayerSnapshot.CAUSE_JOIN, PlayerSnapshot.CAUSE_QUIT, PlayerSnapshot.CAUSE_DEATH,
                PlayerSnapshot.CAUSE_WORLD_CHANGE, PlayerSnapshot.CAUSE_SWEEP);

        // Distinct players per cause so no dirty-skip or quit-eviction
        // interaction muddies this test - it only pins the label.
        for (String cause : causes) {
            Player p = player(UUID.randomUUID(), "p-" + cause, inventoryOf(stack(Material.STONE, 1)));
            service.capture(p, cause);
        }

        assertThat(store.saved).extracting(PlayerSnapshot::cause).containsExactlyElementsOf(causes);
    }

    @Test
    void quitEvictsTheHashCacheForcingAWarmOnTheNextCapture() {
        FakeStore store = new FakeStore();
        PlayerSnapshotService service = newService(store);
        UUID uuid = UUID.randomUUID();
        PlayerInventory inventory = mock(PlayerInventory.class);
        Player steve = mockPlayer(uuid, "Steve", inventory);

        ItemStack[] withStone = inventoryOf(stack(Material.STONE, 1));
        when(inventory.getContents()).thenReturn(withStone);
        service.capture(steve, PlayerSnapshot.CAUSE_JOIN); // cold: warm #1 (empty) + save #1
        service.capture(steve, PlayerSnapshot.CAUSE_QUIT); // cache hit: skip save, then evict

        ItemStack[] withDirt = inventoryOf(stack(Material.DIRT, 1));
        when(inventory.getContents()).thenReturn(withDirt);
        service.capture(steve, PlayerSnapshot.CAUSE_JOIN); // cache was evicted: warm #2, differs, save #2

        assertThat(store.saved).hasSize(2);
        assertThat(store.lastContentHashCalls)
                .as("the cache must have been evicted on quit, forcing a second warm from the store")
                .isEqualTo(2);
    }

    private static PlayerSnapshotService newService(FakeStore store) {
        return new PlayerSnapshotService(store, Duration.parse("5m"), Duration.parse("30d"),
                new DirectExecutorService(), FAKE_SERIALIZER, Logger.getLogger("player-snapshot-service-test"));
    }

    private static Player player(UUID uuid, String name, ItemStack[] contents) {
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getContents()).thenReturn(contents);
        return mockPlayer(uuid, name, inventory);
    }

    private static Player mockPlayer(UUID uuid, String name, PlayerInventory inventory) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        when(player.getInventory()).thenReturn(inventory);
        return player;
    }

    private static ItemStack[] inventoryOf(ItemStack... occupiedFromSlotZero) {
        ItemStack[] contents = new ItemStack[41];
        System.arraycopy(occupiedFromSlotZero, 0, contents, 0, occupiedFromSlotZero.length);
        return contents;
    }

    private static ItemStack stack(Material material, int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.clone()).thenReturn(stack);
        return stack;
    }

    /** In-memory {@link PlayerSnapshotStore} double: records every save, and
     *  counts {@link #lastContentHash} calls so the cache-eviction test can
     *  prove a second warm actually happened. */
    private static final class FakeStore implements PlayerSnapshotStore {
        final List<PlayerSnapshot> saved = new ArrayList<>();
        final Map<UUID, Long> priorHash = new HashMap<>();
        int lastContentHashCalls;

        @Override
        public void save(PlayerSnapshot snapshot) {
            saved.add(snapshot);
            priorHash.put(snapshot.player(), snapshot.contentHash());
        }

        @Override
        public Optional<PlayerSnapshot> latestAtOrBefore(UUID player, Instant instant) {
            return Optional.empty();
        }

        @Override
        public OptionalLong lastContentHash(UUID player) {
            lastContentHashCalls++;
            Long value = priorHash.get(player);
            return value == null ? OptionalLong.empty() : OptionalLong.of(value);
        }

        @Override
        public int prune(Instant cutoff) {
            return 0;
        }
    }

    /** Runs every submitted task synchronously on the calling thread, so a
     *  test's {@code capture()} call has already finished the off-main work
     *  (serialize/hash/save) by the time it returns. */
    private static final class DirectExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}

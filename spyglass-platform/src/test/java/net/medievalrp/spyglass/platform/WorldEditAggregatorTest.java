package net.medievalrp.spyglass.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class WorldEditAggregatorTest {

    private final FakePlatform platform = new FakePlatform();
    private final PlatformPublisher publisher = new PlatformPublisher(platform,
            new PlatformSettings(true, Set.of("*"), Set.of(), Set.of(), Set.of(), true),
            message -> { });
    private final WorldEditAggregator aggregator = new WorldEditAggregator(publisher);

    private static final UUID OPERATOR = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private BlockBreakRecord weBlock(int x, int y, int z) {
        return new BlockBreakRecord(UUID.randomUUID(), "break", Records.WHEN, Records.EXPIRES,
                Origin.fawe(), net.medievalrp.spyglass.api.event.Source.player(OPERATOR, "Op"),
                new BlockLocation(UUID.randomUUID(), "world", x, y, z), "srv:test",
                "minecraft:stone",
                new BlockSnapshot(Material.STONE, "minecraft:stone", java.util.List.of(),
                        java.util.List.of(), java.util.List.of(), java.util.List.of(), null,
                        java.util.List.of(), true),
                new BlockSnapshot(Material.AIR, "minecraft:air", java.util.List.of(),
                        java.util.List.of(), java.util.List.of(), java.util.List.of(), null,
                        java.util.List.of(), true));
    }

    @Test
    void worldEditAndFaweRecordsAreRecognised() {
        assertThat(WorldEditAggregator.isWorldEdit(weBlock(0, 0, 0))).isTrue();
        assertThat(WorldEditAggregator.isWorldEdit(
                Records.blockBreak("break", Records.player(), Origin.player()))).isFalse();
        assertThat(WorldEditAggregator.isWorldEdit(
                Records.blockBreak("break", Records.player(), Origin.worldEdit()))).isTrue();
    }

    /** The acceptance criterion, as literally as a unit test can put it. */
    @Test
    void oneHundredThousandBlocksProduceExactlyOneEvent() {
        long now = 1_000_000L;
        for (int i = 0; i < 100_000; i++) {
            aggregator.accumulate(weBlock(i % 320, 64 + (i % 16), i / 320), now + i / 1000);
        }
        assertThat(platform.published).as("published before the operation finished").isEmpty();

        aggregator.flushIdle(now + 100 + WorldEditAggregator.IDLE_FLUSH_MILLIS);

        assertThat(platform.published).hasSize(1);
        FakePlatform.Published event = platform.published.get(0);
        assertThat(event.type()).isEqualTo("spyglass.worldedit");
        assertThat(event.uuid()).isEqualTo(OPERATOR);
        assertThat(event.json()).contains("\"amount\":100000")
                .contains("\"cause\":\"#fawe\"")
                .contains("\"blocks\":100000")
                // The bounding box, which is what a query filters on.
                .contains("\"min\":{\"x\":0,\"y\":64,\"z\":0}")
                .contains("\"max\":{\"x\":319,\"y\":79,\"z\":312}");
    }

    @Test
    void anOperationStillRunningIsNotFlushed() {
        long now = 5_000L;
        aggregator.accumulate(weBlock(1, 64, 1), now);
        aggregator.flushIdle(now + WorldEditAggregator.IDLE_FLUSH_MILLIS - 1);

        assertThat(platform.published).isEmpty();
        assertThat(aggregator.pendingOperations()).isEqualTo(1);
    }

    @Test
    void twoOperatorsInTheSameWorldGetAnEventEach() {
        long now = 5_000L;
        aggregator.accumulate(weBlock(1, 64, 1), now);
        BlockBreakRecord other = new BlockBreakRecord(UUID.randomUUID(), "break", Records.WHEN,
                Records.EXPIRES, Origin.worldEdit(),
                net.medievalrp.spyglass.api.event.Source.player(UUID.randomUUID(), "Other"),
                new BlockLocation(UUID.randomUUID(), "world", 900, 64, 900), "srv:test",
                "minecraft:dirt", null, null);
        aggregator.accumulate(other, now);

        aggregator.flushAll();

        assertThat(platform.published).hasSize(2);
    }

    @Test
    void shutdownFlushesAnOperationInFlight() {
        aggregator.accumulate(weBlock(1, 64, 1), 1_000L);
        aggregator.flushAll();

        assertThat(platform.published).hasSize(1);
        assertThat(aggregator.pendingOperations()).isZero();
    }

    @Test
    void recordsWithNoLocationDoNotProduceNonsenseCoordinates() {
        BlockBreakRecord noLocation = new BlockBreakRecord(UUID.randomUUID(), "break",
                Records.WHEN, Records.EXPIRES, Origin.fawe(),
                net.medievalrp.spyglass.api.event.Source.player(OPERATOR, "Op"), null,
                "srv:test", "minecraft:stone", null, null);
        aggregator.accumulate(noLocation, 1_000L);
        aggregator.flushAll();

        // Without the normalisation the box would still hold its sentinels and the
        // centre arithmetic would overflow to something absurd.
        assertThat(platform.published.get(0).json())
                .contains("\"x\":0,\"y\":0,\"z\":0")
                .contains("\"min\":{\"x\":0,\"y\":0,\"z\":0}");
    }
}

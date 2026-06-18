package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Covers the default {@link RecordStore#streamRollbackEffects} routing (#67):
 * simple block-replaces are emitted as primitives, tile-entity blocks as
 * objects, non-rollbackable rows as cursor-only skips — in the requested
 * direction. The ClickHouse lean override is exercised by its IT.
 */
class StreamRollbackEffectsTest {

    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000ab");

    private static BlockSnapshot snapshot(Material material, String data) {
        return new BlockSnapshot(material, data, List.of(), List.of(), List.of(), List.of(), null);
    }

    private static BlockSnapshot signSnapshot() {
        // Non-empty signFront => not simple => object path.
        return new BlockSnapshot(Material.OAK_SIGN, "minecraft:oak_sign",
                List.of(), List.of("line"), List.of(), List.of(), null);
    }

    private static BlockBreakRecord block(BlockSnapshot before, BlockSnapshot after, int x) {
        Instant now = Instant.now();
        return new BlockBreakRecord(UUID.randomUUID(), "break", now, now.plusSeconds(60),
                Origin.player(), Source.player(UUID.randomUUID(), "Alice"),
                new BlockLocation(WORLD, "world", x, 64, 0), "srv", "STONE", before, after);
    }

    private static ChatRecord chat() {
        Instant now = Instant.now();
        return new ChatRecord(UUID.randomUUID(), "chat", now, now.plusSeconds(60),
                Origin.player(), Source.player(UUID.randomUUID(), "Bob"),
                new BlockLocation(WORLD, "world", 0, 64, 0), "srv", null,
                "hello", List.of(), java.util.Map.of());
    }

    /** In-memory store: the default streamRollbackEffects rides query(). */
    private static RecordStore storeOf(List<EventRecord> records) {
        return new RecordStore() {
            @Override
            public void save(List<EventRecord> r) {
            }

            @Override
            public QueryResult query(QueryRequest request) {
                return new QueryResult(records, List.of());
            }

            @Override
            public void close() {
            }
        };
    }

    private static final class CapturingSink implements RecordStore.RollbackEffectSink {
        record Block(UUID world, int x, int y, int z, String data, String expected) {
        }

        final List<Block> blocks = new java.util.ArrayList<>();
        final List<RollbackEffect> complex = new java.util.ArrayList<>();
        int skips;

        @Override
        public void block(UUID world, int x, int y, int z, String data, String expected,
                          Instant occurred, UUID id) {
            blocks.add(new Block(world, x, y, z, data, expected));
        }

        @Override
        public void complex(RollbackEffect effect, Instant occurred, UUID id) {
            complex.add(effect);
        }

        @Override
        public void skip(Instant occurred, UUID id) {
            skips++;
        }
    }

    private static QueryRequest request() {
        return new QueryRequest(List.of(), Sort.NEWEST_FIRST, 1000,
                java.util.EnumSet.noneOf(net.medievalrp.spyglass.api.query.Flag.class), false);
    }

    @Test
    void routesSimpleBlockToPrimitivesInRollbackDirection() {
        BlockSnapshot stone = snapshot(Material.STONE, "minecraft:stone");
        BlockSnapshot air = snapshot(Material.AIR, "minecraft:air");
        // A break: before=stone, after=air.
        RecordStore store = storeOf(List.of(block(stone, air, 7)));
        CapturingSink sink = new CapturingSink();

        store.streamRollbackEffects(request(), null, 1000, true, sink);

        assertThat(sink.complex).isEmpty();
        assertThat(sink.skips).isZero();
        assertThat(sink.blocks).hasSize(1);
        CapturingSink.Block b = sink.blocks.get(0);
        // rollback writes the before-state (stone), expects the after (air).
        assertThat(b.data()).isEqualTo("minecraft:stone");
        assertThat(b.expected()).isEqualTo("minecraft:air");
        assertThat(b.x()).isEqualTo(7);
        assertThat(b.world()).isEqualTo(WORLD);
    }

    @Test
    void restoreDirectionSwapsReplacementAndExpected() {
        BlockSnapshot stone = snapshot(Material.STONE, "minecraft:stone");
        BlockSnapshot air = snapshot(Material.AIR, "minecraft:air");
        RecordStore store = storeOf(List.of(block(stone, air, 0)));
        CapturingSink sink = new CapturingSink();

        store.streamRollbackEffects(request(), null, 1000, false, sink);

        assertThat(sink.blocks).hasSize(1);
        CapturingSink.Block b = sink.blocks.get(0);
        // restore re-applies the after-state (air), expects the before (stone).
        assertThat(b.data()).isEqualTo("minecraft:air");
        assertThat(b.expected()).isEqualTo("minecraft:stone");
    }

    @Test
    void routesTileEntityBlockToComplex() {
        BlockSnapshot sign = signSnapshot();
        BlockSnapshot air = snapshot(Material.AIR, "minecraft:air");
        // before=sign (non-simple); rollback writes the sign => object path.
        RecordStore store = storeOf(List.of(block(sign, air, 0)));
        CapturingSink sink = new CapturingSink();

        store.streamRollbackEffects(request(), null, 1000, true, sink);

        assertThat(sink.blocks).isEmpty();
        assertThat(sink.complex).hasSize(1);
        assertThat(sink.complex.get(0)).isInstanceOf(RollbackEffect.BlockReplace.class);
    }

    @Test
    void routesNonRollbackableToSkip() {
        RecordStore store = storeOf(List.of(chat()));
        CapturingSink sink = new CapturingSink();

        store.streamRollbackEffects(request(), null, 1000, true, sink);

        assertThat(sink.blocks).isEmpty();
        assertThat(sink.complex).isEmpty();
        assertThat(sink.skips).isEqualTo(1);
    }
}

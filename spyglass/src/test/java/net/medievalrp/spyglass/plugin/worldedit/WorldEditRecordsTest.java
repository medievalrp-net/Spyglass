package net.medievalrp.spyglass.plugin.worldedit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * The break / place gating that the off-main WorldEdit build runs for every
 * edited cell. Exercised directly (no live WorldEdit platform) since the gating
 * is exactly the bit that must not regress when the heavy work moved off the
 * main thread.
 */
class WorldEditRecordsTest {

    private static final RecordingSupport SUPPORT = new RecordingSupport(Duration.parse("30d"), "srv");
    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();
    private static final BlockLocation LOC = new BlockLocation(WORLD, "world", 10, 64, -20);
    private static final BlockSnapshot STONE = BlockSnapshots.of(Material.STONE, "minecraft:stone");
    private static final BlockSnapshot DIRT = BlockSnapshots.of(Material.DIRT, "minecraft:dirt");
    private static final BlockSnapshot AIR = BlockSnapshots.air();

    private List<EventRecord> append(BlockSnapshot before, BlockSnapshot after, Instant occurred) {
        List<EventRecord> out = new ArrayList<>();
        WorldEditRecords.appendCell(out, SUPPORT, Origin.worldEdit(),
                Source.player(PLAYER, "Joe"), "srv", LOC, occurred, before, after);
        return out;
    }

    @Test
    void solidToSolidEmitsBreakThenPlace() {
        Instant now = Instant.parse("2026-06-19T12:00:00Z");
        List<EventRecord> out = append(STONE, DIRT, now);

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).isInstanceOf(BlockBreakRecord.class);
        assertThat(out.get(1)).isInstanceOf(BlockPlaceRecord.class);

        BlockBreakRecord brk = (BlockBreakRecord) out.get(0);
        assertThat(brk.event()).isEqualTo("break");
        assertThat(brk.origin()).isEqualTo(Origin.worldEdit());
        assertThat(brk.source().playerName()).isEqualTo("Joe");
        assertThat(brk.location()).isEqualTo(LOC);
        assertThat(brk.server()).isEqualTo("srv");
        assertThat(brk.occurred()).isEqualTo(now);
        assertThat(brk.expiresAt()).isEqualTo(SUPPORT.expiresAt(now));
        assertThat(brk.target()).isEqualTo("STONE");
        assertThat(brk.originalBlock()).isEqualTo(STONE);
        assertThat(brk.newBlock()).isEqualTo(DIRT);

        BlockPlaceRecord place = (BlockPlaceRecord) out.get(1);
        assertThat(place.event()).isEqualTo("place");
        assertThat(place.target()).isEqualTo("DIRT");
        assertThat(place.originalBlock()).isEqualTo(STONE);
        assertThat(place.newBlock()).isEqualTo(DIRT);
    }

    @Test
    void clearingToAirEmitsOnlyBreak() {
        // //set air over a solid block: a break, no place.
        List<EventRecord> out = append(STONE, AIR, Instant.now());

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isInstanceOf(BlockBreakRecord.class);
        assertThat(((BlockBreakRecord) out.get(0)).newBlock().isAir()).isTrue();
    }

    @Test
    void placingOverAirEmitsOnlyPlace() {
        // //set stone over air: a place, no break.
        List<EventRecord> out = append(AIR, STONE, Instant.now());

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isInstanceOf(BlockPlaceRecord.class);
        assertThat(((BlockPlaceRecord) out.get(0)).originalBlock().isAir()).isTrue();
    }

    @Test
    void airToAirEmitsNothing() {
        // A no-op overwrite (air set to air) records neither side.
        assertThat(append(AIR, AIR, Instant.now())).isEmpty();
    }
}

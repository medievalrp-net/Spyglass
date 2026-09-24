package net.medievalrp.spyglass.plugin.listener.block;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.*;
import net.medievalrp.spyglass.api.capture.BlockSnapshots;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StrawBedListenerTest {
    @Test void consumedPairIsRecordedOnceAndBothSnapshotsSurvive() {
        try (var snapshots = mockStatic(BlockSnapshots.class, CALLS_REAL_METHODS)) {
            Fixture f = new Fixture();
            var before = BlockSnapshots.of(f.material, "minecraft:straw_bed[facing=north,part=head,occupied=false]");
            snapshots.when(() -> BlockSnapshots.capture(any(BlockState.class))).thenReturn(before);
            f.listener.onUse(f.event);
            f.listener.onUse(f.event);
            when(f.head.getType()).thenReturn(Material.AIR);
            when(f.foot.getType()).thenReturn(Material.AIR);
            f.tasks.forEach(Runnable::run);
            var records = ArgumentCaptor.forClass(EventRecord.class);
            verify(f.recorder, times(2)).record(records.capture());
            assertThat(records.getAllValues()).allSatisfy(record -> {
                assertThat(record.event()).isEqualTo("straw-bed-consume");
                assertThat(((BlockBreakRecord) record).rollbackEffect()).isNotNull();
            });
        }
    }
    @Test void unchangedCancelledAndManuallyBrokenBedsAreExcluded() {
        for (int mode = 0; mode < 3; mode++) {
            try (var snapshots = mockStatic(BlockSnapshots.class, CALLS_REAL_METHODS)) {
                Fixture f = new Fixture();
                var before = BlockSnapshots.of(f.material, "minecraft:straw_bed");
                snapshots.when(() -> BlockSnapshots.capture(any(BlockState.class))).thenReturn(before);
                f.listener.onUse(f.event);
                if (mode == 1) when(f.event.useInteractedBlock()).thenReturn(Event.Result.DENY);
                if (mode == 2) {
                    var broken = mock(BlockBreakEvent.class);
                    when(broken.getBlock()).thenReturn(f.head);
                    f.listener.onBreak(broken);
                }
                if (mode != 0) {
                    when(f.head.getType()).thenReturn(Material.AIR);
                    when(f.foot.getType()).thenReturn(Material.AIR);
                }
                f.tasks.forEach(Runnable::run);
                verifyNoInteractions(f.recorder);
            }
        }
    }
    private static class Fixture {
        final Material material = Material.getMaterial("STRAW_BED");
        final Recorder recorder = mock(Recorder.class);
        final List<Runnable> tasks = new ArrayList<>();
        final StrawBedListener listener = new StrawBedListener(recorder, new RecordingSupport(new Duration(3600), "test"), tasks::add);
        final Block head = mock(Block.class), foot = mock(Block.class);
        final PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        Fixture() {
            org.junit.jupiter.api.Assumptions.assumeTrue(material != null);
            World world = mock(World.class);
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(world.getName()).thenReturn("world");
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            int z = 0;
            for (Block block : List.of(head, foot)) {
                when(block.getWorld()).thenReturn(world);
                when(block.getType()).thenReturn(material);
                when(block.getY()).thenReturn(80);
                when(block.getZ()).thenReturn(z);
                when(block.getLocation()).thenReturn(new Location(world, 0, 80, z++));
                when(block.getState()).thenReturn(mock(BlockState.class));
            }
            Bed data = mock(Bed.class);
            when(data.getPart()).thenReturn(Bed.Part.HEAD);
            when(data.getFacing()).thenReturn(BlockFace.NORTH);
            when(head.getBlockData()).thenReturn(data);
            when(head.getRelative(BlockFace.SOUTH)).thenReturn(foot);
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getName()).thenReturn("tester");
            when(event.getPlayer()).thenReturn(player);
            when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
            when(event.getClickedBlock()).thenReturn(head);
        }
    }
}

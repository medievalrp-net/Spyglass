package net.medievalrp.spyglass.plugin.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalDurabilityTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");

    @Test
    void writeRoundTripsBatchThroughBson(@TempDir Path tmp) throws IOException {
        WalDurability wal = new WalDurability(tmp, true, Logger.getLogger("test"));
        List<EventRecord> batch = sampleBatch();

        Path file = wal.write(batch);
        assertThat(file).isNotNull();
        assertThat(Files.exists(file)).isTrue();

        List<EventRecord> recovered = wal.recover();
        assertThat(recovered).hasSize(batch.size());
        // Recovery deletes the file — second call returns nothing.
        assertThat(Files.exists(file)).isFalse();
        assertThat(wal.recover()).isEmpty();
    }

    @Test
    void ackDeletesTheBatchFile(@TempDir Path tmp) throws IOException {
        WalDurability wal = new WalDurability(tmp, true, Logger.getLogger("test"));
        Path file = wal.write(sampleBatch());
        assertThat(file).isNotNull();

        wal.ack(file);

        assertThat(Files.exists(file)).isFalse();
        assertThat(wal.recover()).isEmpty();
    }

    @Test
    void disabledModeIsNoOp(@TempDir Path tmp) throws IOException {
        WalDurability wal = new WalDurability(tmp, false, Logger.getLogger("test"));

        assertThat(wal.enabled()).isFalse();
        assertThat(wal.write(sampleBatch())).isNull();
        assertThat(wal.recover()).isEmpty();
    }

    @Test
    void recoversBatchesInChronologicalOrder(@TempDir Path tmp) throws IOException {
        WalDurability wal = new WalDurability(tmp, true, Logger.getLogger("test"));
        Instant base = Instant.now().minusSeconds(60);

        Path first = wal.write(List.of(chatAt(base, "first")));
        // Bump timestamps so the sort by lastModified is deterministic
        // even on filesystems with low mtime resolution (HFS+, FAT).
        Files.setLastModifiedTime(first, java.nio.file.attribute.FileTime.fromMillis(1_000));

        Path second = wal.write(List.of(chatAt(base.plusSeconds(10), "second")));
        Files.setLastModifiedTime(second, java.nio.file.attribute.FileTime.fromMillis(2_000));

        List<EventRecord> recovered = wal.recover();
        assertThat(recovered).hasSize(2);
        assertThat(((ChatRecord) recovered.get(0)).message()).isEqualTo("first");
        assertThat(((ChatRecord) recovered.get(1)).message()).isEqualTo("second");
    }

    @Test
    void corruptFileIsSkippedAndDeletedSoRecoveryDoesntStall(@TempDir Path tmp) throws IOException {
        WalDurability wal = new WalDurability(tmp, true, Logger.getLogger("test"));
        // Drop a junk file directly into the pending dir.
        Path pending = tmp.resolve("wal").resolve("pending");
        Files.createDirectories(pending);
        Path junk = pending.resolve("junk-" + UUID.randomUUID() + ".wal");
        Files.write(junk, new byte[]{0, 1, 2, 3, 4});

        // Plus one valid file alongside.
        wal.write(List.of(chatAt(Instant.now(), "valid")));

        List<EventRecord> recovered = wal.recover();
        // The junk file should be skipped; the valid one should round-trip.
        assertThat(recovered).hasSize(1);
        assertThat(((ChatRecord) recovered.get(0)).message()).isEqualTo("valid");

        // And junk file should be cleaned up.
        try (Stream<Path> stream = Files.list(pending)) {
            assertThat(stream).isEmpty();
        }
    }

    private static List<EventRecord> sampleBatch() {
        Instant now = Instant.now();
        BlockLocation loc = new BlockLocation(WORLD, "world", 1, 64, 1);
        Origin origin = Origin.player();
        Source source = Source.player(PLAYER, "Tester");
        BlockSnapshot stone = new BlockSnapshot(
                org.bukkit.Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        return List.of(
                new BlockBreakRecord(UUID.randomUUID(), "break", now, now.plusSeconds(3600),
                        origin, source, loc, "STONE", stone, air),
                new ChatRecord(UUID.randomUUID(), "say", now, now.plusSeconds(3600),
                        origin, source, loc, "Tester", "hi", List.of()));
    }

    private static ChatRecord chatAt(Instant occurred, String message) {
        BlockLocation loc = new BlockLocation(WORLD, "world", 0, 64, 0);
        return new ChatRecord(UUID.randomUUID(), "say", occurred, occurred.plusSeconds(3600),
                Origin.player(), Source.player(PLAYER, "Tester"),
                loc, "Tester", message, List.of());
    }
}

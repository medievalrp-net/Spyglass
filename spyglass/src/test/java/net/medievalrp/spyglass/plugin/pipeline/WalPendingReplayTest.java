package net.medievalrp.spyglass.plugin.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one-release upgrade shim for the removed wal-batched mode (#307):
 * leftover fsynced-but-unacked batch files must reach the recorder once,
 * in original drain order, and the orphaned {@code wal/} tree must not
 * survive the boot.
 */
class WalPendingReplayTest {

    private static JoinRecord sampleRecord(String player) {
        Instant now = Instant.now();
        return new JoinRecord(
                UUID.randomUUID(),
                "join",
                now,
                now.plusSeconds(60),
                Origin.player(),
                Source.player(UUID.randomUUID(), player),
                new BlockLocation(UUID.randomUUID(), "world", 0, 64, 0),
                "test",
                player,
                "127.0.0.1");
    }

    private static Path pendingDir(Path dataFolder) throws IOException {
        Path pending = dataFolder.resolve("wal").resolve("pending");
        Files.createDirectories(pending);
        return pending;
    }

    private static Path writeBatch(Path pending, String name, FileTime stamp,
                                   List<EventRecord> batch) throws IOException {
        Path file = pending.resolve(name);
        Files.write(file, EventBatchCodec.encode(batch));
        Files.setLastModifiedTime(file, stamp);
        return file;
    }

    @Test
    void installWithoutWalDirectoryIsANoOp(@TempDir Path dataFolder) {
        // The default-durability install base: no wal/ tree was ever created.
        int replayed = WalPendingReplay.replay(
                dataFolder, r -> { throw new AssertionError("nothing to replay"); },
                Logger.getLogger("test"));
        assertThat(replayed).isZero();
    }

    @Test
    void replaysBatchesOldestFirstAndRetiresTheTree(@TempDir Path dataFolder) throws IOException {
        Path pending = pendingDir(dataFolder);
        JoinRecord older = sampleRecord("older");
        JoinRecord newer = sampleRecord("newer");
        // Explicit stamps: creation order within one test can share a millisecond.
        writeBatch(pending, UUID.randomUUID() + ".wal",
                FileTime.fromMillis(1_000L), List.of(older));
        writeBatch(pending, UUID.randomUUID() + ".wal",
                FileTime.fromMillis(2_000L), List.of(newer));

        List<EventRecord> sunk = new ArrayList<>();
        int replayed = WalPendingReplay.replay(dataFolder, sunk::add, Logger.getLogger("test"));

        assertThat(replayed).isEqualTo(2);
        assertThat(sunk).extracting(EventRecord::id)
                .containsExactly(older.id(), newer.id());
        // Files consumed and the orphaned tree gone with them.
        assertThat(Files.exists(pending)).isFalse();
        assertThat(Files.exists(dataFolder.resolve("wal"))).isFalse();
    }

    @Test
    void corruptFileIsSkippedAndDeletedWithoutBlockingOthers(@TempDir Path dataFolder)
            throws IOException {
        Path pending = pendingDir(dataFolder);
        Path corrupt = pending.resolve("corrupt.wal");
        Files.write(corrupt, new byte[] {1, 2, 3});
        Files.setLastModifiedTime(corrupt, FileTime.fromMillis(1_000L));
        JoinRecord good = sampleRecord("survivor");
        writeBatch(pending, "good.wal", FileTime.fromMillis(2_000L), List.of(good));

        List<EventRecord> sunk = new ArrayList<>();
        int replayed = WalPendingReplay.replay(dataFolder, sunk::add, Logger.getLogger("test"));

        assertThat(replayed).isEqualTo(1);
        assertThat(sunk).extracting(EventRecord::id).containsExactly(good.id());
        // The corrupt file must not re-log on every boot.
        assertThat(Files.exists(corrupt)).isFalse();
        assertThat(Files.exists(pending)).isFalse();
    }

    @Test
    void strayNonWalFileKeepsTheDirectoryForTheOperator(@TempDir Path dataFolder)
            throws IOException {
        Path pending = pendingDir(dataFolder);
        Files.writeString(pending.resolve("notes.txt"), "operator file");
        writeBatch(pending, "batch.wal", FileTime.fromMillis(1_000L),
                List.of(sampleRecord("only")));

        List<EventRecord> sunk = new ArrayList<>();
        int replayed = WalPendingReplay.replay(dataFolder, sunk::add, Logger.getLogger("test"));

        assertThat(replayed).isEqualTo(1);
        assertThat(sunk).hasSize(1);
        // The .wal is consumed but the stray file - and so the tree - stays.
        assertThat(Files.exists(pending.resolve("batch.wal"))).isFalse();
        assertThat(Files.exists(pending.resolve("notes.txt"))).isTrue();
        assertThat(Files.isDirectory(pending)).isTrue();
    }
}

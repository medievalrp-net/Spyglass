package net.medievalrp.spyglass.plugin.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.EventRecord;
import org.jetbrains.annotations.ApiStatus;

/**
 * One-release upgrade shim for the removed {@code wal-batched} durability
 * mode (#307). A pre-removal jar fsynced each drain batch to
 * {@code <dataFolder>/wal/pending/} and deleted the file on DB ack, so a
 * file that is still there holds records the database never confirmed -
 * typically left by a SIGKILL/power-cut, or a shutdown where the store
 * stayed unreachable through the whole flush window. Without this shim an
 * upgraded jar would never look at that directory again and those fsynced
 * records would be silently lost.
 *
 * <p>{@link #replay} decodes each {@code *.wal} file through the shared
 * {@link EventBatchCodec} (oldest file first, preserving original drain
 * order), hands the records to the recorder, and deletes the file. Both
 * backends collapse a replay of an already-partly-persisted batch on the
 * record id, same as the old recovery path. An install that never ran
 * {@code wal-batched} has no {@code wal/} directory and returns at the
 * first check.
 *
 * <p>Delete this class, its test, and the call in
 * {@code SpyglassPlugin#onEnable} one release after the removal ships.
 */
@ApiStatus.Internal
public final class WalPendingReplay {

    private static final String EXTENSION = ".wal";

    private WalPendingReplay() {
    }

    /** @return how many records were replayed into {@code sink}. */
    public static int replay(Path dataFolder, Consumer<EventRecord> sink, Logger logger) {
        Path pendingDir = dataFolder.resolve("wal").resolve("pending");
        if (!Files.isDirectory(pendingDir)) {
            return 0;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(pendingDir)) {
            files = stream
                    .filter(p -> p.toString().endsWith(EXTENSION))
                    .sorted(Comparator.comparingLong(WalPendingReplay::lastModified))
                    .toList();
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Failed to list leftover WAL dir " + pendingDir
                    + "; its batches were NOT replayed.", ex);
            return 0;
        }
        int replayed = 0;
        int filesReplayed = 0;
        for (Path file : files) {
            try {
                List<EventRecord> batch = EventBatchCodec.decode(Files.readAllBytes(file));
                batch.forEach(sink);
                replayed += batch.size();
                filesReplayed++;
            } catch (RuntimeException | IOException ex) {
                logger.log(Level.WARNING,
                        "Skipping corrupt leftover WAL file " + file
                                + " (deleted; its records are unrecoverable): "
                                + ex.getMessage());
            }
            // Delete even on decode failure - an undecodable file would
            // otherwise re-log on every boot with nothing left to save.
            try {
                Files.deleteIfExists(file);
            } catch (IOException deleteEx) {
                logger.log(Level.WARNING, "Failed to delete replayed WAL file " + file, deleteEx);
            }
        }
        if (filesReplayed > 0) {
            logger.info("Spyglass upgrade: replayed " + replayed + " record(s) from "
                    + filesReplayed + " leftover WAL batch file(s); the wal-batched "
                    + "durability mode itself was removed.");
        }
        // Retire the empty tree so nothing lingers; deleteIfExists on a
        // non-empty directory throws, which is exactly when to keep it.
        try {
            Files.deleteIfExists(pendingDir);
            Files.deleteIfExists(pendingDir.getParent());
        } catch (IOException ignored) {
            // Leftover non-wal files - leave the directory for the operator.
        }
        return replayed;
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }
}

package net.medievalrp.spyglass.plugin.pipeline;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.EventRecord;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Append-only write-ahead log for {@link AsyncRecorder} drain batches.
 *
 * <h2>Crash-recovery contract</h2>
 *
 * <p>One file per drain batch in {@code <dataFolder>/wal/pending/}.
 * The drain thread writes the batch (BSON-encoded list) and calls
 * {@link FileChannel#force(boolean)} once before pushing to the DB;
 * on successful save it {@link #ack(Path) acks} the file (deletes
 * it). If the JVM dies between fsync and DB ack, the file remains
 * pending and {@link #recover()} on next startup decodes it back
 * into an {@code EventRecord} list the recorder can re-save.
 *
 * <p>The risk window narrows from "everything in the in-RAM queue
 * (~250 ms of events at typical drain interval)" to "events between
 * two batch fsyncs" — at MedievalRP's ~600 ev/s, a 512-row batch
 * fsync amortises to one fsync per ~850 ms but only the records
 * inside an in-flight batch are at risk, since records still in the
 * queue haven't crossed the fsync boundary yet either. In practice
 * the at-risk window is the time between drain-poll and fsync
 * completion (single-digit milliseconds).
 *
 * <h2>Idempotency</h2>
 *
 * <p>If the JVM crashes <em>during</em> the DB save (HTTP partial
 * write, network drop, fsync-then-segfault), recovery may replay a
 * batch that's already partly persisted. Every {@link EventRecord}
 * carries a UUID {@code id} and both backends collapse replays:
 *
 * <ul>
 *   <li><b>Mongo</b> — unordered {@code insertMany} swallows
 *       duplicate-key errors on {@code _id} so replays are
 *       immediately consistent.</li>
 *   <li><b>ClickHouse</b> — the {@code event_records} table is a
 *       {@code ReplacingMergeTree} sorted by {@code (event, occurred,
 *       id)} (see {@link
 *       net.medievalrp.spyglass.plugin.storage.ClickHouseSchema}).
 *       Two inserts of the same record share a sort-key tuple and
 *       collapse to one row on the next part merge. There is a
 *       transient window after replay where reads see the duplicate;
 *       use {@code SELECT ... FINAL} or {@code OPTIMIZE TABLE …
 *       DEDUPLICATE} if a forensic query needs strict dedup before
 *       background merges run.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class WalDurability {

    private static final String EXTENSION = ".wal";

    private final boolean enabled;
    private final Path pendingDir;
    private final Logger logger;

    public WalDurability(@Nullable Path dataFolder, boolean enabled, Logger logger) {
        this.enabled = enabled && dataFolder != null;
        this.logger = logger;
        if (this.enabled) {
            this.pendingDir = dataFolder.resolve("wal").resolve("pending");
            try {
                Files.createDirectories(pendingDir);
            } catch (IOException ex) {
                throw new RuntimeException(
                        "Failed to create WAL pending directory " + pendingDir, ex);
            }
        } else {
            this.pendingDir = null;
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Write a batch to disk + fsync. Returns the file path so the
     * caller can {@link #ack(Path)} it after the DB save succeeds.
     * Returns {@code null} when WAL is disabled.
     */
    public @Nullable Path write(List<EventRecord> batch) throws IOException {
        if (!enabled || batch.isEmpty()) {
            return null;
        }
        byte[] bytes = EventBatchCodec.encode(batch);
        Path file = pendingDir.resolve(UUID.randomUUID() + EXTENSION);
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(bytes));
            // force(true): fsync data + metadata so the file survives a
            // power-cut between this call and the DB push.
            channel.force(true);
        }
        return file;
    }

    /** Delete the WAL file after the DB save succeeds. */
    public void ack(@Nullable Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Failed to delete WAL file " + file, ex);
        }
    }

    /**
     * Scan the pending directory for any leftover batch files —
     * batches that were fsynced but the JVM died before the DB
     * acked. Returns the records in chronological order (oldest WAL
     * file first) so the recorder re-feeds them in the order they
     * were originally drained.
     */
    public List<EventRecord> recover() {
        if (!enabled) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(pendingDir)) {
            files = stream
                    .filter(p -> p.toString().endsWith(EXTENSION))
                    .sorted(Comparator.comparingLong(WalDurability::lastModified))
                    .toList();
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Failed to list WAL pending dir " + pendingDir, ex);
            return List.of();
        }
        if (files.isEmpty()) {
            return List.of();
        }
        List<EventRecord> recovered = new ArrayList<>();
        int filesRecovered = 0;
        for (Path file : files) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                List<EventRecord> batch = EventBatchCodec.decode(bytes);
                recovered.addAll(batch);
                filesRecovered++;
            } catch (RuntimeException | IOException ex) {
                logger.log(Level.WARNING,
                        "Skipping corrupt WAL file " + file
                                + " (will be deleted to keep recovery moving): "
                                + ex.getMessage());
            }
            // Delete the file regardless — even on decode failure.
            // A file that won't decode would block recovery forever
            // if we left it; the records inside are unrecoverable
            // anyway.
            try {
                Files.deleteIfExists(file);
            } catch (IOException deleteEx) {
                logger.log(Level.WARNING, "Failed to delete WAL file post-recovery: " + file, deleteEx);
            }
        }
        if (filesRecovered > 0) {
            logger.info("WAL recovery: replayed " + recovered.size() + " records from "
                    + filesRecovered + " pending file(s).");
        }
        return List.copyOf(recovered);
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }
}

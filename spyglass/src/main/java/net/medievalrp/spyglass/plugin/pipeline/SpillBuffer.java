package net.medievalrp.spyglass.plugin.pipeline;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.EventRecord;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * On-disk overflow buffer for {@link AsyncRecorder}. When the in-RAM queue
 * is at its ceiling, the uncappable bulk-edit firehose (vanilla WorldEdit,
 * via {@link AsyncRecorder#recordAll}) writes its overflow batch <em>here</em>
 * instead of holding it in memory — so a multi-million-block paste keeps the
 * heap flat (the overflow lives on disk) while losing nothing: the drain
 * replays spilled segments back to the store.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Pure backpressure (#119) bounds the queue but not vanilla WorldEdit: its
 * {@code setBlock} loop runs on the main thread, which can't be blocked, so the
 * records that don't fit in the capped queue would otherwise pile up in the
 * off-main build threads. Spilling them to disk bounds the RAM of an op the
 * operator can't cap, without dropping records. This works hand-in-hand with
 * the bounded build stage (#121, {@code BoundedAsyncDispatcher}): the dispatcher
 * caps how many record-builds are in flight, and the inline build it falls back
 * to spills here — so Spyglass's own footprint stays bounded for an op of any
 * size (Minecraft's per-block world-edit cost is separate and unbounded by us).
 *
 * <h2>Format &amp; crash-safety</h2>
 *
 * <p>One segment file per spilled batch in {@code <dataFolder>/spill/}, named
 * {@code <seq>.<count>.spill} where {@code seq} is a zero-padded monotonic
 * counter (so lexical order = chronological) and {@code count} is the record
 * count (readable even from a corrupt segment, for metrics). Each segment is
 * fsynced to a {@code .tmp} sibling then {@linkplain StandardCopyOption#ATOMIC_MOVE
 * atomically renamed}, so the drain never reads a half-written file. Segments
 * left behind by a crash are counted on construction and replayed by the drain
 * on the next run; the same BSON layout as the WAL ({@link EventBatchCodec})
 * means a replayed record round-trips identically to the storage path.
 *
 * <p>Producers ({@link #spill}) may run on many threads; {@link #poll}/{@link
 * #ack} run only on the single drain thread.
 */
@ApiStatus.Internal
public final class SpillBuffer {

    private static final String EXTENSION = ".spill";
    private static final String TMP_SUFFIX = EXTENSION + ".tmp";

    @Nullable
    private final Path dir; // null => disabled
    private final Logger logger;
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong pendingRecords = new AtomicLong();
    private final AtomicLong pendingFiles = new AtomicLong();
    private final AtomicLong pendingBytes = new AtomicLong();

    public SpillBuffer(@Nullable Path dataFolder, boolean enabled, Logger logger) {
        this.logger = logger;
        if (!enabled || dataFolder == null) {
            this.dir = null;
            return;
        }
        this.dir = dataFolder.resolve("spill");
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to create spill directory " + dir, ex);
        }
        seedFromExisting();
    }

    boolean enabled() {
        return dir != null;
    }

    boolean hasPending() {
        return pendingFiles.get() > 0;
    }

    /** Approximate count of records currently on disk — used by flush's
     *  high-water mark so read-your-writes waits for spilled records too. */
    long pendingRecordCount() {
        return pendingRecords.get();
    }

    /** Number of overflow segments currently on disk (operator gauge). */
    long pendingSegments() {
        return pendingFiles.get();
    }

    /** Approximate bytes of overflow currently on disk (operator gauge). */
    long pendingByteCount() {
        return pendingBytes.get();
    }

    /**
     * Append a batch to a new on-disk segment (fsync + atomic rename) and
     * return. Off-main producers call this instead of holding the overflow in
     * RAM. Throws on I/O failure (e.g. disk full) so the caller can fall back
     * to in-RAM backpressure rather than drop.
     */
    void spill(List<EventRecord> batch) throws IOException {
        if (dir == null || batch.isEmpty()) {
            return;
        }
        byte[] bytes = EventBatchCodec.encode(batch);
        String base = String.format("%016d.%d", seq.getAndIncrement(), batch.size());
        Path tmp = dir.resolve(base + TMP_SUFFIX);
        Path file = dir.resolve(base + EXTENSION);
        try (FileChannel channel = FileChannel.open(tmp,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(bytes));
            // fsync the bytes before the segment becomes visible to the drain.
            channel.force(true);
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        pendingRecords.addAndGet(batch.size());
        pendingFiles.incrementAndGet();
        pendingBytes.addAndGet(bytes.length);
    }

    /**
     * Read + decode the oldest segment <b>without</b> deleting it (the drain
     * {@link #ack}s only after a successful save, so a crash mid-save leaves
     * the segment for recovery). Returns {@code null} when nothing is spilled.
     */
    @Nullable
    Spilled poll() {
        if (dir == null) {
            return null;
        }
        // Skip past any unreadable segments (drop them) until a good one or none.
        for (Path oldest = oldestSegment(); oldest != null; oldest = oldestSegment()) {
            try {
                byte[] bytes = Files.readAllBytes(oldest);
                return new Spilled(oldest, bytes.length, EventBatchCodec.decode(bytes));
            } catch (IOException | RuntimeException ex) {
                // A segment that won't decode would wedge the drain forever;
                // its records are unrecoverable, so drop it and move to the next.
                logger.log(Level.WARNING, "Spyglass spill: dropping unreadable segment "
                        + oldest.getFileName() + " (" + ex.getMessage() + ")");
                pendingRecords.addAndGet(-recordCountOf(oldest));
                pendingFiles.decrementAndGet();
                pendingBytes.addAndGet(-byteSizeOf(oldest));
                deleteQuietly(oldest);
            }
        }
        return null;
    }

    /** Delete a segment after its records were saved, and update counters. */
    void ack(Spilled spilled) {
        pendingRecords.addAndGet(-spilled.records().size());
        pendingFiles.decrementAndGet();
        pendingBytes.addAndGet(-spilled.bytes());
        deleteQuietly(spilled.file());
    }

    @Nullable
    private Path oldestSegment() {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(EXTENSION))
                    .min(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Spyglass spill: failed to list " + dir, ex);
            return null;
        }
    }

    private void seedFromExisting() {
        List<Path> all;
        try (Stream<Path> stream = Files.list(dir)) {
            all = stream.toList();
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Spyglass spill: failed to scan " + dir, ex);
            return;
        }
        long maxSeq = -1L;
        long files = 0;
        long records = 0;
        long bytes = 0;
        for (Path p : all) {
            String name = p.getFileName().toString();
            if (name.endsWith(TMP_SUFFIX)) {
                deleteQuietly(p); // half-written at the crash; the rename never landed
                continue;
            }
            if (!name.endsWith(EXTENSION)) {
                continue;
            }
            files++;
            records += recordCountOf(p);
            bytes += byteSizeOf(p);
            maxSeq = Math.max(maxSeq, seqOf(p));
        }
        seq.set(maxSeq + 1);
        pendingFiles.set(files);
        pendingRecords.set(records);
        pendingBytes.set(bytes);
        if (files > 0) {
            logger.info("Spyglass spill: " + records + " overflow record(s) in " + files
                    + " segment(s) left from a prior run; the drain will replay them.");
        }
    }

    private static long seqOf(Path p) {
        try {
            return Long.parseLong(p.getFileName().toString().split("\\.")[0]);
        } catch (RuntimeException ex) {
            return -1L;
        }
    }

    private static long recordCountOf(Path p) {
        try {
            return Long.parseLong(p.getFileName().toString().split("\\.")[1]);
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    private static long byteSizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException ex) {
            return 0L;
        }
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Spyglass spill: failed to delete " + p, ex);
        }
    }

    /** A decoded segment paired with its file + on-disk byte size, so the drain
     *  can {@link #ack} it and keep the byte gauge accurate. */
    record Spilled(Path file, long bytes, List<EventRecord> records) {
    }
}

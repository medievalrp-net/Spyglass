package net.medievalrp.spyglass.plugin.command.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Crash-resume backing store for in-flight rollback jobs.
 *
 * <p>When a rollback starts, write a small file naming the job id +
 * raw query + operator + mode + timestamp into the plugin data
 * folder. When the rollback finishes (any terminal state, including
 * cancellation), delete the file. If the JVM crashes mid-rollback,
 * the file persists; on next plugin enable the file is detected,
 * logged, and surfaced via {@code /sg rbqueue} so the operator can
 * resume.
 *
 * <p>Why not store the remaining-effect-list itself: that's millions
 * of {@link net.medievalrp.spyglass.api.rollback.RollbackEffect}s for
 * a big rollback — the file size + write cost is prohibitive on
 * every batch boundary. Storing the original query is enough: the
 * engine's "block changed" precondition skips already-applied cells
 * on a re-run, so the same {@code /sg rollback} is idempotent and
 * picks up where the crash left off.
 *
 * <p>File format: one file per saved rollback, named
 * {@code <shortId>.resume}. Plain-text key=value pairs to keep
 * decode trivial (no JSON dependency on the hot path).
 */
@ApiStatus.Internal
public final class RollbackResumeStore {

    private final Path baseDir;
    private final Logger logger;

    public RollbackResumeStore(Path pluginDataDir, Logger logger) {
        this.baseDir = pluginDataDir.resolve("resume");
        this.logger = logger;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Spyglass: could not create resume dir " + baseDir
                    + " — crash-resume disabled", ex);
        }
    }

    /** Write a marker for a freshly-started rollback. Best-effort —
     *  failure here logs but doesn't abort the rollback. */
    public void markStart(UUID jobId, String operatorName, @Nullable UUID operatorId,
                          String query, RollbackJob.Mode mode) {
        Path file = baseDir.resolve(filename(jobId));
        try {
            Files.writeString(file, serialize(
                    jobId, operatorName, operatorId, query, mode,
                    Instant.now(), null, 0, 0), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Spyglass: failed to write resume marker for "
                    + jobId, ex);
        }
    }

    /**
     * Update the marker with the latest cursor + progress counters
     * after a page completes. On a crash, resume re-queries from
     * this cursor instead of from the start, so a 2M-block rollback
     * that crashed at 75% only re-applies the remaining 25%.
     *
     * <p>Best-effort: failure here logs but doesn't abort the rollback.
     * One file write per page (≤200 writes for a 1M-block rollback);
     * each write is &lt;1 KB so disk overhead is negligible.
     */
    public void markProgress(UUID jobId, String operatorName, @Nullable UUID operatorId,
                             String query, RollbackJob.Mode mode, Instant startedAt,
                             @Nullable Cursor cursor, int appliedSoFar, int skippedSoFar) {
        Path file = baseDir.resolve(filename(jobId));
        try {
            Files.writeString(file, serialize(
                    jobId, operatorName, operatorId, query, mode,
                    startedAt, cursor, appliedSoFar, skippedSoFar),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            logger.log(Level.FINE, "Spyglass: failed to update resume marker for "
                    + jobId + " (page progress)", ex);
        }
    }

    private static String serialize(UUID jobId, String operatorName,
                                    @Nullable UUID operatorId, String query,
                                    RollbackJob.Mode mode, Instant startedAt,
                                    @Nullable Cursor cursor, int appliedSoFar,
                                    int skippedSoFar) {
        StringBuilder sb = new StringBuilder();
        sb.append("id=").append(jobId).append('\n');
        sb.append("operatorName=").append(escape(operatorName)).append('\n');
        if (operatorId != null) sb.append("operatorId=").append(operatorId).append('\n');
        sb.append("mode=").append(mode.name()).append('\n');
        sb.append("query=").append(escape(query)).append('\n');
        sb.append("startedAt=").append(startedAt.toString()).append('\n');
        if (cursor != null) {
            sb.append("cursor.occurred=").append(cursor.occurred().toString()).append('\n');
            sb.append("cursor.id=").append(cursor.id()).append('\n');
        }
        sb.append("appliedSoFar=").append(appliedSoFar).append('\n');
        sb.append("skippedSoFar=").append(skippedSoFar).append('\n');
        return sb.toString();
    }

    /** Cursor structure mirrors {@code QueryPage.Cursor}; held here
     *  to avoid spyglass-api ↔ spyglass-core import noise. */
    public record Cursor(Instant occurred, UUID id) {
    }

    /** Delete the marker for a finished rollback. Idempotent. */
    public void markFinish(UUID jobId) {
        Path file = baseDir.resolve(filename(jobId));
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Spyglass: failed to delete resume marker for "
                    + jobId, ex);
        }
    }

    /** Scan the resume dir for leftover markers. Each entry
     *  represents an in-flight rollback that was interrupted by a
     *  crash, restart, or hard-kill. */
    public List<Saved> listPending() {
        List<Saved> out = new ArrayList<>();
        if (!Files.isDirectory(baseDir)) return out;
        try (var stream = Files.list(baseDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".resume"))
                    .forEach(p -> {
                        Saved s = readOrLog(p);
                        if (s != null) out.add(s);
                    });
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Spyglass: failed to scan resume dir", ex);
        }
        return out;
    }

    private @Nullable Saved readOrLog(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            UUID id = null, operatorId = null;
            String operatorName = null, query = null;
            RollbackJob.Mode mode = RollbackJob.Mode.ROLLBACK;
            Instant startedAt = null;
            Instant cursorOccurred = null;
            UUID cursorId = null;
            int applied = 0, skipped = 0;
            for (String line : text.split("\n")) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq);
                String v = unescape(line.substring(eq + 1));
                switch (k) {
                    case "id" -> id = UUID.fromString(v);
                    case "operatorId" -> operatorId = UUID.fromString(v);
                    case "operatorName" -> operatorName = v;
                    case "query" -> query = v;
                    case "mode" -> mode = RollbackJob.Mode.valueOf(v);
                    case "startedAt" -> startedAt = Instant.parse(v);
                    case "cursor.occurred" -> cursorOccurred = Instant.parse(v);
                    case "cursor.id" -> cursorId = UUID.fromString(v);
                    case "appliedSoFar" -> applied = Integer.parseInt(v);
                    case "skippedSoFar" -> skipped = Integer.parseInt(v);
                    default -> { /* unknown — forward-compat */ }
                }
            }
            if (id == null || query == null || operatorName == null || startedAt == null) {
                logger.warning("Spyglass: incomplete resume marker " + file.getFileName()
                        + " — skipping");
                return null;
            }
            Cursor cursor = (cursorOccurred != null && cursorId != null)
                    ? new Cursor(cursorOccurred, cursorId) : null;
            return new Saved(id, operatorId, operatorName, query, mode, startedAt,
                    cursor, applied, skipped, file);
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Spyglass: failed to read resume marker "
                    + file.getFileName(), ex);
            return null;
        }
    }

    public void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    private static String filename(UUID jobId) {
        return jobId.toString() + ".resume";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        // Tiny: only \\ and \n round-trip
        StringBuilder sb = new StringBuilder(s.length());
        boolean esc = false;
        for (char c : s.toCharArray()) {
            if (esc) {
                sb.append(c == 'n' ? '\n' : c);
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public record Saved(UUID id,
                        @Nullable UUID operatorId,
                        String operatorName,
                        String query,
                        RollbackJob.Mode mode,
                        Instant startedAt,
                        @Nullable Cursor cursor,
                        int appliedSoFar,
                        int skippedSoFar,
                        Path file) {
        public String shortId() {
            return id.toString().substring(0, 8);
        }
    }
}

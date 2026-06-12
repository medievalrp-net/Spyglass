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

// Crash-resume store for in-flight rollback jobs.
//
// On start we write a small key=value file naming the job id, raw
// query, operator, mode, and timestamp. On any terminal state we
// delete it. If the JVM crashes, the file persists and the next
// plugin enable surfaces it through /sg rbqueue.
//
// We store the query, not the remaining effect list: re-running the
// same /sg rollback is idempotent thanks to the engine's "block
// changed" precondition, so the file stays small.
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
                    + "; crash-resume disabled", ex);
        }
    }

    // Best-effort: a write failure here logs but does not abort the
    // rollback. {@code requestBase64} is the RESOLVED query plan
    // (UndoReferenceBson-encoded, #49) — resume replays it verbatim
    // instead of re-parsing {@code query}, whose r:/t: defaults would
    // re-anchor to the resumer's position and clock. Null for jobs
    // that must not be resumed from the marker (undo replays).
    public void markStart(UUID jobId, String operatorName, @Nullable UUID operatorId,
                          String query, RollbackJob.Mode mode,
                          @Nullable String requestBase64) {
        Path file = baseDir.resolve(filename(jobId));
        try {
            Files.writeString(file, serialize(
                    jobId, operatorName, operatorId, query, mode, requestBase64,
                    Instant.now(), null, 0, 0), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Spyglass: failed to write resume marker for "
                    + jobId, ex);
        }
    }

    // Updates the marker with cursor and counters after each page
    // completes so a crash resumes from here rather than the start.
    public void markProgress(UUID jobId, String operatorName, @Nullable UUID operatorId,
                             String query, RollbackJob.Mode mode,
                             @Nullable String requestBase64, Instant startedAt,
                             @Nullable Cursor cursor, int appliedSoFar, int skippedSoFar) {
        Path file = baseDir.resolve(filename(jobId));
        try {
            Files.writeString(file, serialize(
                    jobId, operatorName, operatorId, query, mode, requestBase64,
                    startedAt, cursor, appliedSoFar, skippedSoFar),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            logger.log(Level.FINE, "Spyglass: failed to update resume marker for "
                    + jobId + " (page progress)", ex);
        }
    }

    private static String serialize(UUID jobId, String operatorName,
                                    @Nullable UUID operatorId, String query,
                                    RollbackJob.Mode mode,
                                    @Nullable String requestBase64, Instant startedAt,
                                    @Nullable Cursor cursor, int appliedSoFar,
                                    int skippedSoFar) {
        StringBuilder sb = new StringBuilder();
        sb.append("id=").append(jobId).append('\n');
        sb.append("operatorName=").append(escape(operatorName)).append('\n');
        if (operatorId != null) sb.append("operatorId=").append(operatorId).append('\n');
        sb.append("mode=").append(mode.name()).append('\n');
        sb.append("query=").append(escape(query)).append('\n');
        if (requestBase64 != null) sb.append("request=").append(escape(requestBase64)).append('\n');
        sb.append("startedAt=").append(startedAt.toString()).append('\n');
        if (cursor != null) {
            sb.append("cursor.occurred=").append(cursor.occurred().toString()).append('\n');
            sb.append("cursor.id=").append(cursor.id()).append('\n');
        }
        sb.append("appliedSoFar=").append(appliedSoFar).append('\n');
        sb.append("skippedSoFar=").append(skippedSoFar).append('\n');
        return sb.toString();
    }

    // Mirrors QueryPage.Cursor; kept here to avoid pulling the
    // storage package into this one.
    public record Cursor(Instant occurred, UUID id) {
    }

    public void markFinish(UUID jobId) {
        Path file = baseDir.resolve(filename(jobId));
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Spyglass: failed to delete resume marker for "
                    + jobId, ex);
        }
    }

    // Lists markers left behind by interrupted rollbacks.
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
            String operatorName = null, query = null, requestBase64 = null;
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
                    case "request" -> requestBase64 = v;
                    case "mode" -> mode = RollbackJob.Mode.valueOf(v);
                    case "startedAt" -> startedAt = Instant.parse(v);
                    case "cursor.occurred" -> cursorOccurred = Instant.parse(v);
                    case "cursor.id" -> cursorId = UUID.fromString(v);
                    case "appliedSoFar" -> applied = Integer.parseInt(v);
                    case "skippedSoFar" -> skipped = Integer.parseInt(v);
                    default -> { /* unknown key; forward-compat */ }
                }
            }
            if (id == null || query == null || operatorName == null || startedAt == null) {
                logger.warning("Spyglass: incomplete resume marker " + file.getFileName()
                        + "; skipping");
                return null;
            }
            Cursor cursor = (cursorOccurred != null && cursorId != null)
                    ? new Cursor(cursorOccurred, cursorId) : null;
            return new Saved(id, operatorId, operatorName, query, mode, startedAt,
                    cursor, applied, skipped, requestBase64, file);
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

    /** {@code requestBase64} — the resolved query plan written at
     *  submit time; null on markers from older Spyglass versions and
     *  on undo-replay jobs, both of which refuse to resume (#49). */
    public record Saved(UUID id,
                        @Nullable UUID operatorId,
                        String operatorName,
                        String query,
                        RollbackJob.Mode mode,
                        Instant startedAt,
                        @Nullable Cursor cursor,
                        int appliedSoFar,
                        int skippedSoFar,
                        @Nullable String requestBase64,
                        Path file) {
        public String shortId() {
            return id.toString().substring(0, 8);
        }
    }
}

package net.medievalrp.spyglass.plugin.migrate;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.plugin.command.service.ServiceSupport;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig.Backend;
import net.medievalrp.spyglass.plugin.imports.RecordStoreSink;
import net.medievalrp.spyglass.plugin.storage.ClickHouseRecordStore;
import net.medievalrp.spyglass.plugin.storage.QueryPage;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /spyglass migrate <backend>} - copy every record from the ACTIVE
 * backend into another configured backend, for operators switching storage
 * (SQLite -> ClickHouse when they outgrow a file, MariaDB -> SQLite when
 * they simplify, etc.). The unused backend blocks already live in
 * config.conf; this command is what makes filling one in useful before
 * flipping {@code database.backend}.
 *
 * <p>Flow mirrors {@link net.medievalrp.spyglass.plugin.imports.ImportService}:
 * the public entry point does only the cheap running-check synchronously,
 * then hands the whole job to the async pool. The copy itself is a keyset-
 * paged read from the live store ({@link RecordStore#queryPage}) streamed
 * into a {@link RecordStoreSink} on the target, so heap stays flat at any
 * source size. Records keep their ids, so re-running a migration is
 * dedup-safe on SQLite ({@code INSERT OR REPLACE}), MariaDB
 * ({@code INSERT IGNORE}) and ClickHouse ({@code ReplacingMergeTree}
 * + the finalize OPTIMIZE below); a Mongo target gets plain inserts, so a
 * re-run against a non-empty Mongo target is refused outright rather than
 * silently duplicating.
 *
 * <p>Live ingest continues during the copy; rows written after the scan
 * passes their position are not copied. Run it during a quiet window and
 * flip the backend right after, or accept that the tail lands only in the
 * old backend.
 */
public final class MigrateService {

    public enum Outcome {
        STARTED, ALREADY_RUNNING, IMPORT_RUNNING, INVALID_TARGET, NEEDS_CONFIRM, FAILED, DONE
    }

    /** Opens a store for the validated target backend. Caller closes it. */
    @FunctionalInterface
    public interface TargetStoreFactory {
        RecordStore open(Backend target) throws Exception;
    }

    private static final QueryRequest COPY_ALL = new QueryRequest(
            List.of(), Sort.OLDEST_FIRST, Integer.MAX_VALUE,
            EnumSet.noneOf(Flag.class), false);
    private static final QueryRequest PROBE_ONE = new QueryRequest(
            List.of(), Sort.NEWEST_FIRST, 1,
            EnumSet.noneOf(Flag.class), false);

    private final RecordStore source;
    private final Backend active;
    private final SpyglassConfig.Database databaseConfig;
    private final ServiceSupport support;
    private final TargetStoreFactory targetFactory;
    private final BooleanSupplier importRunning;
    private final int batchSize;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MigrateService(RecordStore source, Backend active,
                          SpyglassConfig.Database databaseConfig, ServiceSupport support,
                          TargetStoreFactory targetFactory, BooleanSupplier importRunning,
                          int batchSize, Logger logger) {
        this.source = source;
        this.active = active;
        this.databaseConfig = databaseConfig;
        this.support = support;
        this.targetFactory = targetFactory;
        this.importRunning = importRunning;
        this.batchSize = batchSize;
        this.logger = logger;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Async entry point: cheap checks inline, the job itself off-thread. */
    public Outcome migrate(CommandSender sender, String targetName, boolean confirm) {
        if (running.get()) {
            tellError(sender, "A migration is already running; try again once it finishes.");
            return Outcome.ALREADY_RUNNING;
        }
        support.onAsyncThread(() -> runMigrate(sender, targetName, confirm));
        return Outcome.STARTED;
    }

    // ===== Synchronous core (test-driven; also the async job body) ====

    Outcome runMigrate(CommandSender sender, String targetName, boolean confirm) {
        Backend target = parseBackend(targetName);
        if (target == null) {
            tellError(sender, "Unknown backend '" + targetName
                    + "' (expected sqlite, mongo, clickhouse, or mariadb).");
            return Outcome.INVALID_TARGET;
        }
        if (target == active) {
            tellError(sender, capitalize(targetName) + " is already the active backend; "
                    + "nothing to migrate.");
            return Outcome.INVALID_TARGET;
        }
        String configError = validateTargetConfig(databaseConfig, target);
        if (configError != null) {
            tellError(sender, "Target backend '" + targetName + "' is not configured: "
                    + configError + " Fill in the block in config.conf first.");
            return Outcome.INVALID_TARGET;
        }
        if (importRunning.getAsBoolean()) {
            tellError(sender, "A CoreProtect import is running; wait for it before migrating.");
            return Outcome.IMPORT_RUNNING;
        }
        if (!running.compareAndSet(false, true)) {
            tellError(sender, "A migration is already running; try again once it finishes.");
            return Outcome.ALREADY_RUNNING;
        }
        long startNanos = System.nanoTime();
        RecordStore targetStore = null;
        try {
            targetStore = targetFactory.open(target);

            if (!confirm && !targetIsEmpty(targetStore)) {
                tellError(sender, "The " + targetName + " target already contains records. "
                        + "Re-run with --confirm to migrate anyway"
                        + (target == Backend.MONGO
                                ? " (NOT recommended on MongoDB - it cannot dedup by id, "
                                        + "so a re-run duplicates rows)."
                                : " (existing rows with the same id are overwritten/deduped)."));
                return Outcome.NEEDS_CONFIRM;
            }

            tell(sender, "Migrating " + active.name().toLowerCase(Locale.ROOT)
                    + " -> " + targetName + "...");
            long copied = copyAll(sender, targetStore);
            finalizeClickHouse(targetStore);

            double secs = (System.nanoTime() - startNanos) / 1e9;
            tellSuccess(sender, String.format(Locale.ROOT,
                    "Migration complete: %,d records copied to %s in %.1fs. "
                            + "Set database.backend = \"%s\" in config.conf and restart "
                            + "to switch. (Undo history / wand state / salvage are "
                            + "operational state and start fresh on the new backend.)",
                    copied, targetName, secs, targetName));
            return Outcome.DONE;
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Spyglass migration to " + targetName + " failed", ex);
            tellError(sender, "Migration failed: " + ex.getMessage());
            return Outcome.FAILED;
        } finally {
            if (targetStore != null) {
                try {
                    targetStore.close();
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "Failed closing the migration target store", ex);
                }
            }
            running.set(false);
        }
    }

    // ===== The copy ====================================================

    private long copyAll(CommandSender sender, RecordStore target) {
        RecordStoreSink sink = new RecordStoreSink(target, batchSize);
        Instant now = Instant.now();
        long alreadyExpired = 0;
        long read = 0;
        QueryPage.Cursor cursor = null;
        while (true) {
            QueryPage page = source.queryPage(COPY_ALL, cursor, batchSize);
            if (page.records().isEmpty()) {
                break;
            }
            for (var record : page.records()) {
                sink.accept(record);
                read++;
                if (record.expiresAt() != null && record.expiresAt().isBefore(now)) {
                    alreadyExpired++;
                }
            }
            if (read % 250_000 < batchSize) {
                tell(sender, String.format(Locale.ROOT, "... %,d records copied", read));
            }
            cursor = page.next();
            if (cursor == null) {
                break;
            }
        }
        sink.flush();
        if (alreadyExpired > 0) {
            tellWarn(sender, String.format(Locale.ROOT,
                    "%,d copied records are already past their retention expiry "
                            + "and will be aged out on the target (immediately on ClickHouse). "
                            + "Raise storage.retention (or set it \"never\") before migrating "
                            + "if you want them kept.", alreadyExpired));
        }
        return sink.written();
    }

    private boolean targetIsEmpty(RecordStore target) {
        try {
            return target.query(PROBE_ONE).records().isEmpty();
        } catch (RuntimeException ex) {
            // A probe failure shouldn't block a fresh migration; the copy
            // itself will surface real connectivity problems immediately.
            logger.log(Level.WARNING, "Target emptiness probe failed; assuming empty", ex);
            return true;
        }
    }

    /**
     * Same finalize idiom as ImportService: ClickHouse save() is
     * fire-and-forget async insert, so flush the server-side buffer and
     * OPTIMIZE ... FINAL so ReplacingMergeTree dedups re-migrated ids and
     * counts read true immediately after the command reports done.
     */
    private void finalizeClickHouse(RecordStore target) throws Exception {
        if (!(target instanceof ClickHouseRecordStore ch)) {
            return;
        }
        try (var ignored = ch.client()
                .execute("SYSTEM FLUSH ASYNC INSERT QUEUE")
                .get(60, TimeUnit.SECONDS)) {
            // ack
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while flushing ClickHouse async inserts", ie);
        }
        ch.optimize();
    }

    // ===== Validation ==================================================

    /** Config-token names, matching SpyglassConfig's backend switch. */
    @Nullable
    public static Backend parseBackend(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "sqlite" -> Backend.SQLITE;
            case "mongo", "mongodb" -> Backend.MONGO;
            case "clickhouse" -> Backend.CLICKHOUSE;
            case "mariadb", "mysql", "maria" -> Backend.MARIADB;
            default -> null;
        };
    }

    /**
     * The config checks: is the target backend's block filled in enough to
     * connect? Returns a human-readable problem, or {@code null} when OK.
     * (SQLite needs no check - its record defaults a blank path to
     * spyglass.db, and the same-backend guard already prevents pointing a
     * migration at the live file.)
     */
    @Nullable
    public static String validateTargetConfig(SpyglassConfig.Database db, Backend target) {
        switch (target) {
            case MONGO -> {
                if (isBlank(db.uri())) {
                    return "database.uri is blank.";
                }
                if (isBlank(db.name()) || isBlank(db.collection())) {
                    return "database.name / database.collection are blank.";
                }
            }
            case CLICKHOUSE -> {
                SpyglassConfig.ClickHouse ch = db.clickhouse();
                if (ch == null || isBlank(ch.host())) {
                    return "database.clickhouse.host is blank.";
                }
                if (isBlank(ch.database()) || isBlank(ch.table())) {
                    return "database.clickhouse.database / .table are blank.";
                }
            }
            case MARIADB -> {
                SpyglassConfig.MariaDb maria = db.mariadb();
                if (maria == null || isBlank(maria.host())) {
                    return "database.mariadb.host is blank.";
                }
                if (isBlank(maria.database()) || isBlank(maria.user())) {
                    return "database.mariadb.database / .user are blank.";
                }
            }
            case SQLITE -> {
                // Sqlite record normalizes blank -> spyglass.db; nothing to check.
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty()
                ? s
                : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // House-styled sender feedback (#252), mirroring ImportService.
    private void tell(CommandSender sender, String message) {
        support.onMainThread(() -> sender.sendMessage(
                net.medievalrp.spyglass.plugin.command.render.Feedback.info(message)));
    }

    private void tellWarn(CommandSender sender, String message) {
        support.onMainThread(() -> sender.sendMessage(
                net.medievalrp.spyglass.plugin.command.render.Feedback.warn(message)));
    }

    private void tellError(CommandSender sender, String message) {
        support.onMainThread(() -> sender.sendMessage(
                net.medievalrp.spyglass.plugin.command.render.Feedback.error(message)));
    }

    private void tellSuccess(CommandSender sender, String message) {
        support.onMainThread(() -> sender.sendMessage(
                net.medievalrp.spyglass.plugin.command.render.Feedback.success(message)));
    }
}

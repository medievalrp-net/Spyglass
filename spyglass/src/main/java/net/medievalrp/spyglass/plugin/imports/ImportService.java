package net.medievalrp.spyglass.plugin.imports;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.medievalrp.spyglass.plugin.command.service.ServiceSupport;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig.Backend;
import net.medievalrp.spyglass.plugin.imports.ImportHistoryStore.ImportRecord;
import net.medievalrp.spyglass.plugin.importer.mapping.CoreProtectMapper;
import net.medievalrp.spyglass.plugin.importer.mapping.MappingContext;
import net.medievalrp.spyglass.plugin.importer.pipeline.ImportPipeline;
import net.medievalrp.spyglass.plugin.importer.pipeline.ImportSummary;
import net.medievalrp.spyglass.plugin.importer.pipeline.ProgressReporter;
import net.medievalrp.spyglass.plugin.importer.source.CoreProtectSource;
import net.medievalrp.spyglass.plugin.importer.source.MysqlSource;
import net.medievalrp.spyglass.plugin.importer.source.SqliteSource;
import net.medievalrp.spyglass.plugin.importer.world.WorldMap;
import net.medievalrp.spyglass.plugin.storage.ClickHouseRecordStore;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import org.bukkit.command.CommandSender;

/**
 * Orchestrates a CoreProtect import end to end: source open, re-import
 * guard (confirm / Mongo-reimport refusal), world resolution, the
 * mapping pipeline, ClickHouse finalize, and history recording.
 *
 * <p>Only one import runs at a time, enforced by {@link #running}. The
 * public entry points ({@link #importSqlite} / {@link #importMysql})
 * do only the cheap {@link #isRunning()} check synchronously so the
 * invoking sender gets instant "already running" feedback, then hand
 * the ENTIRE remaining job — identity hashing, guard evaluation, and
 * the run itself — off to the async pool and return {@code STARTED}.
 * This keeps a full-file SHA-256 hash of a multi-GB CoreProtect dump
 * off the calling thread (typically the Bukkit main thread). The
 * package-visible {@code runImportSqlite} / {@code runImportMysql}
 * are the single source of truth for the mutex + identity + guard +
 * run sequence and message the sender for every outcome, so tests can
 * drive them directly with a synchronous {@link ServiceSupport} and
 * assert on the returned {@link ImportOutcome}.
 */
public final class ImportService {

    public enum ImportOutcome {
        STARTED, ALREADY_RUNNING, NEEDS_CONFIRM, MONGO_REIMPORT_BLOCKED, FAILED, DONE
    }

    private final RecordStore store;
    private final Backend backend;
    private final ServiceSupport support;
    private final Path worldContainer;
    private final String defaultServerName;
    private final Duration retention;
    private final int batchSize;
    private final ImportHistoryStore history;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ImportService(RecordStore store, Backend backend, ServiceSupport support,
                         Path worldContainer, String defaultServerName,
                         Duration retention, int batchSize, ImportHistoryStore history,
                         Logger logger) {
        this.store = store;
        this.backend = backend;
        this.support = support;
        this.worldContainer = worldContainer;
        this.defaultServerName = defaultServerName;
        this.retention = retention;
        this.batchSize = batchSize;
        this.history = history;
        this.logger = logger;
    }

    public boolean isRunning() {
        return running.get();
    }

    // ===== Public entry points ====================================

    public ImportOutcome importSqlite(CommandSender sender, Path dbFile, boolean confirm) {
        if (running.get()) {
            tell(sender, "An import is already running; try again once it finishes.");
            return ImportOutcome.ALREADY_RUNNING;
        }
        support.onAsyncThread(() -> runImportSqlite(sender, dbFile, confirm));
        return ImportOutcome.STARTED;
    }

    public ImportOutcome importMysql(CommandSender sender, String sourceName,
                            ImportConfig.MysqlSourceSpec spec, boolean confirm) {
        if (running.get()) {
            tell(sender, "An import is already running; try again once it finishes.");
            return ImportOutcome.ALREADY_RUNNING;
        }
        support.onAsyncThread(() -> runImportMysql(sender, sourceName, spec, confirm));
        return ImportOutcome.STARTED;
    }

    // ===== Synchronous core (test-driven; also the async job body) ====

    ImportOutcome runImportSqlite(CommandSender sender, Path dbFile, boolean confirm) {
        if (!running.compareAndSet(false, true)) {
            tell(sender, "An import is already running; try again once it finishes.");
            return ImportOutcome.ALREADY_RUNNING;
        }
        String displayName = dbFile.getFileName().toString();
        try {
            String identity = ImportIdentity.ofSqliteFile(dbFile);
            ImportOutcome guard = evaluateGuard(sender, identity, confirm);
            if (guard != null) {
                return guard;
            }
            return doRun(sender, identity, displayName, defaultServerName,
                    () -> SqliteSource.open(dbFile));
        } catch (IOException | RuntimeException ex) {
            logger.log(Level.SEVERE, "Spyglass import failed for " + displayName, ex);
            tell(sender, "Import failed: " + ex.getMessage());
            return ImportOutcome.FAILED;
        } finally {
            running.set(false);
        }
    }

    ImportOutcome runImportMysql(CommandSender sender, String sourceName,
                                 ImportConfig.MysqlSourceSpec spec, boolean confirm) {
        if (!running.compareAndSet(false, true)) {
            tell(sender, "An import is already running; try again once it finishes.");
            return ImportOutcome.ALREADY_RUNNING;
        }
        try {
            String identity = ImportIdentity.ofMysql(spec.host(), spec.port(), spec.database());
            ImportOutcome guard = evaluateGuard(sender, identity, confirm);
            if (guard != null) {
                return guard;
            }
            String serverName = blankToDefault(spec.serverName());
            return doRun(sender, identity, sourceName, serverName,
                    () -> MysqlSource.open(new MysqlSource.ConnectionSpec(
                            spec.host(), spec.port(), spec.database(), spec.user(), spec.password())));
        } catch (IOException | RuntimeException ex) {
            logger.log(Level.SEVERE, "Spyglass import failed for " + sourceName, ex);
            tell(sender, "Import failed: " + ex.getMessage());
            return ImportOutcome.FAILED;
        } finally {
            running.set(false);
        }
    }

    // ===== Shared run body (mutex + identity + guard already resolved) =

    @FunctionalInterface
    private interface SourceOpener {
        CoreProtectSource open() throws IOException;
    }

    private ImportOutcome doRun(CommandSender sender, String identity, String displayName,
                                String serverName, SourceOpener opener) throws IOException {
        String senderName = sender.getName();
        try (CoreProtectSource source = opener.open()) {
            WorldMap worldMap = ImportPipeline.resolveWorlds(source, worldContainer);
            MappingContext ctx = new MappingContext(worldMap, serverName, retention, Instant.now());
            RecordStoreSink sink = new RecordStoreSink(store, batchSize);
            PrintStream senderProgress = new SenderProgress(sender, support);
            ProgressReporter progress = new ProgressReporter(senderProgress, 50_000);
            ImportSummary summary = new ImportPipeline(
                    source, new CoreProtectMapper(ctx), sink, progress, senderProgress).run();

            if (store instanceof ClickHouseRecordStore ch) {
                // ClickHouseRecordStore.save() uses async_insert=1 +
                // wait_for_async_insert=0 (fire-and-forget): the INSERT
                // acks before the server materializes a part. OPTIMIZE
                // ... FINAL only merges parts that already exist, so
                // without this flush the last buffered batch(es) can be
                // invisible to it - undercounting rows rather than
                // leaving duplicates. Same idiom ClickHouseRecordStoreIT
                // uses between save() and a read-your-writes query.
                flushClickHouseAsyncInserts(ch);
                ch.optimize();
            }

            history.record(new ImportRecord(identity, displayName, System.currentTimeMillis(),
                    senderName, summary.totalRead(), summary.totalWritten(), summary.totalSkipped()));

            tell(sender, "Import complete for " + displayName + ": read=" + summary.totalRead()
                    + " written=" + summary.totalWritten() + " skipped=" + summary.totalSkipped());
            return ImportOutcome.DONE;
        }
    }

    // ===== Guard evaluation ========================================

    /**
     * Checks the re-import guard against {@code identity} and, if
     * blocked, messages {@code sender} with the reason. Returns
     * {@code null} when the run may proceed, otherwise the blocked
     * {@link ImportOutcome} to return to the caller.
     */
    private ImportOutcome evaluateGuard(CommandSender sender, String identity, boolean confirm) {
        Optional<ImportRecord> prior = history.find(identity);
        if (prior.isEmpty()) {
            return null;
        }
        if (backend == Backend.MONGO) {
            tell(sender, "This source was already imported and re-importing into MongoDB "
                    + "is not supported; switch backends or restore from a MongoDB backup.");
            return ImportOutcome.MONGO_REIMPORT_BLOCKED;
        }
        if (!confirm) {
            ImportRecord p = prior.get();
            tell(sender, "This source was already imported on " + Instant.ofEpochMilli(p.importedAtEpochMs())
                    + " by " + p.importedBy() + " (read=" + p.read() + ", written=" + p.written()
                    + ", skipped=" + p.skipped() + "). Re-run with --confirm to import again.");
            return ImportOutcome.NEEDS_CONFIRM;
        }
        return null;
    }

    /**
     * Forces ClickHouse to materialize any rows still sitting in its
     * server-side async-insert buffer into queryable parts, so the
     * {@code OPTIMIZE ... FINAL} that follows actually sees (and can
     * merge/dedup) everything this run wrote. Mirrors the
     * {@code SYSTEM FLUSH ASYNC INSERT QUEUE} idiom ClickHouseRecordStoreIT
     * uses between a save and a read-your-writes query.
     */
    private void flushClickHouseAsyncInserts(ClickHouseRecordStore ch) {
        try (com.clickhouse.client.api.command.CommandResponse ignored = ch.client()
                .execute("SYSTEM FLUSH ASYNC INSERT QUEUE")
                .get(60, TimeUnit.SECONDS)) {
            // ack
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while flushing ClickHouse async inserts", ie);
        } catch (Exception ex) {
            throw new RuntimeException("ClickHouse async-insert flush failed: " + ex.getMessage(), ex);
        }
    }

    private String blankToDefault(String serverName) {
        return (serverName == null || serverName.isBlank()) ? defaultServerName : serverName;
    }

    private void tell(CommandSender sender, String message) {
        support.onMainThread(() -> sender.sendMessage("[import] " + message));
    }
}

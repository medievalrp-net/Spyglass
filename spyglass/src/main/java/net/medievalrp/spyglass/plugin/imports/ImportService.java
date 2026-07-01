package net.medievalrp.spyglass.plugin.imports;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
 * do a synchronous, non-mutating guard pre-check so the invoking
 * sender gets instant feedback for the common "already running" /
 * "needs -confirm" / "Mongo re-import blocked" cases, then hand the
 * heavy work off to the async pool. The package-visible
 * {@code runImportSqlite} / {@code runImportMysql} are the single
 * source of truth for the guard + run sequence (including the actual
 * lock acquisition) so tests can drive them directly with a
 * synchronous {@link ServiceSupport}.
 */
public final class ImportService {

    public enum ImportOutcome {
        STARTED, ALREADY_RUNNING, NEEDS_CONFIRM, MONGO_REIMPORT_BLOCKED, FAILED, DONE
    }

    private final RecordStore store;
    private final Backend backend;
    private final ServiceSupport support;
    private final Path dataFolder;
    private final Path worldContainer;
    private final String defaultServerName;
    private final Duration retention;
    private final int batchSize;
    private final ImportHistoryStore history;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ImportService(RecordStore store, Backend backend, ServiceSupport support,
                         Path dataFolder, Path worldContainer, String defaultServerName,
                         Duration retention, int batchSize, ImportHistoryStore history,
                         Logger logger) {
        this.store = store;
        this.backend = backend;
        this.support = support;
        this.dataFolder = dataFolder;
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

    public void importSqlite(CommandSender sender, Path dbFile, boolean confirm) {
        if (running.get()) {
            tell(sender, "An import is already running; try again once it finishes.");
            return;
        }
        String identity;
        try {
            identity = ImportIdentity.ofSqliteFile(dbFile);
        } catch (IOException ex) {
            tell(sender, "Failed to read " + dbFile + ": " + ex.getMessage());
            return;
        }
        GuardCheck guard = evaluateGuard(identity, confirm);
        if (guard.isBlocked()) {
            tellGuardBlocked(sender, guard);
            return;
        }
        support.onAsyncThread(() -> runImportSqlite(sender, dbFile, confirm));
    }

    public void importMysql(CommandSender sender, String sourceName,
                            ImportConfig.MysqlSourceSpec spec, boolean confirm) {
        if (running.get()) {
            tell(sender, "An import is already running; try again once it finishes.");
            return;
        }
        String identity = ImportIdentity.ofMysql(spec.host(), spec.port(), spec.database());
        GuardCheck guard = evaluateGuard(identity, confirm);
        if (guard.isBlocked()) {
            tellGuardBlocked(sender, guard);
            return;
        }
        support.onAsyncThread(() -> runImportMysql(sender, sourceName, spec, confirm));
    }

    // ===== Synchronous core (test-driven) =========================

    ImportOutcome runImportSqlite(CommandSender sender, Path dbFile, boolean confirm) {
        String identity;
        try {
            identity = ImportIdentity.ofSqliteFile(dbFile);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Spyglass import: failed hashing " + dbFile, ex);
            tell(sender, "Failed to read " + dbFile + ": " + ex.getMessage());
            return ImportOutcome.FAILED;
        }
        String displayName = dbFile.getFileName().toString();
        return runImport(sender, identity, displayName, confirm, defaultServerName,
                () -> SqliteSource.open(dbFile));
    }

    ImportOutcome runImportMysql(CommandSender sender, String sourceName,
                                 ImportConfig.MysqlSourceSpec spec, boolean confirm) {
        String identity = ImportIdentity.ofMysql(spec.host(), spec.port(), spec.database());
        String serverName = blankToDefault(spec.serverName());
        return runImport(sender, identity, sourceName, confirm, serverName,
                () -> MysqlSource.open(new MysqlSource.ConnectionSpec(
                        spec.host(), spec.port(), spec.database(), spec.user(), spec.password())));
    }

    // ===== Shared guard + run =======================================

    @FunctionalInterface
    private interface SourceOpener {
        CoreProtectSource open() throws IOException;
    }

    private ImportOutcome runImport(CommandSender sender, String identity, String displayName,
                                    boolean confirm, String serverName, SourceOpener opener) {
        if (!running.compareAndSet(false, true)) {
            tell(sender, "An import is already running; try again once it finishes.");
            return ImportOutcome.ALREADY_RUNNING;
        }
        try {
            GuardCheck guard = evaluateGuard(identity, confirm);
            if (guard.isBlocked()) {
                tellGuardBlocked(sender, guard);
                return guard.blocked();
            }

            String senderName = sender.getName();
            try (CoreProtectSource source = opener.open()) {
                WorldMap worldMap = ImportPipeline.resolveWorlds(source, worldContainer);
                MappingContext ctx = new MappingContext(worldMap, serverName, retention, Instant.now());
                RecordStoreSink sink = new RecordStoreSink(store, batchSize);
                PrintStream senderProgress = new SenderProgress(sender, support);
                ProgressReporter progress = new ProgressReporter(senderProgress, 50_000);
                ImportSummary summary = new ImportPipeline(
                        source, new CoreProtectMapper(ctx), sink, progress, senderProgress).run();

                // TODO(task10): confirm async inserts are flushed before OPTIMIZE
                if (store instanceof ClickHouseRecordStore ch) {
                    ch.optimize();
                }

                history.record(new ImportRecord(identity, displayName, System.currentTimeMillis(),
                        senderName, summary.totalRead(), summary.totalWritten(), summary.totalSkipped()));

                tell(sender, "Import complete for " + displayName + ": read=" + summary.totalRead()
                        + " written=" + summary.totalWritten() + " skipped=" + summary.totalSkipped());
                return ImportOutcome.DONE;
            }
        } catch (IOException | RuntimeException ex) {
            logger.log(Level.SEVERE, "Spyglass import failed for " + displayName, ex);
            tell(sender, "Import failed: " + ex.getMessage());
            return ImportOutcome.FAILED;
        } finally {
            running.set(false);
        }
    }

    // ===== Guard evaluation ========================================

    private record GuardCheck(ImportOutcome blocked, ImportRecord prior) {
        boolean isBlocked() {
            return blocked != null;
        }
    }

    private GuardCheck evaluateGuard(String identity, boolean confirm) {
        Optional<ImportRecord> prior = history.find(identity);
        if (prior.isEmpty()) {
            return new GuardCheck(null, null);
        }
        if (backend == Backend.MONGO) {
            return new GuardCheck(ImportOutcome.MONGO_REIMPORT_BLOCKED, prior.get());
        }
        if (!confirm) {
            return new GuardCheck(ImportOutcome.NEEDS_CONFIRM, prior.get());
        }
        return new GuardCheck(null, prior.get());
    }

    private void tellGuardBlocked(CommandSender sender, GuardCheck guard) {
        if (guard.blocked() == ImportOutcome.MONGO_REIMPORT_BLOCKED) {
            tell(sender, "This source was already imported and re-importing into MongoDB "
                    + "is not supported; switch backends or restore from a MongoDB backup.");
        } else if (guard.blocked() == ImportOutcome.NEEDS_CONFIRM) {
            ImportRecord p = guard.prior();
            tell(sender, "This source was already imported on " + Instant.ofEpochMilli(p.importedAtEpochMs())
                    + " by " + p.importedBy() + " (read=" + p.read() + ", written=" + p.written()
                    + ", skipped=" + p.skipped() + "). Re-run with -confirm to import again.");
        }
    }

    private String blankToDefault(String serverName) {
        return (serverName == null || serverName.isBlank()) ? defaultServerName : serverName;
    }

    private void tell(CommandSender sender, String message) {
        support.onMainThread(() -> sender.sendMessage("[import] " + message));
    }
}

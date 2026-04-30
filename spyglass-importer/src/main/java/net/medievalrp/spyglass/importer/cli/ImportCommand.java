package net.medievalrp.spyglass.importer.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import net.medievalrp.spyglass.importer.mapping.CoreProtectMapper;
import net.medievalrp.spyglass.importer.mapping.MappingContext;
import net.medievalrp.spyglass.importer.pipeline.ImportPipeline;
import net.medievalrp.spyglass.importer.pipeline.ImportSummary;
import net.medievalrp.spyglass.importer.pipeline.ProgressReporter;
import net.medievalrp.spyglass.importer.sink.ClickHouseSink;
import net.medievalrp.spyglass.importer.sink.CountingSink;
import net.medievalrp.spyglass.importer.sink.RecordSink;
import net.medievalrp.spyglass.importer.source.CoreProtectSource;
import net.medievalrp.spyglass.importer.source.MysqlSource;
import net.medievalrp.spyglass.importer.source.SqliteSource;
import net.medievalrp.spyglass.importer.world.WorldMap;
import net.medievalrp.spyglass.plugin.storage.ClickHouseRecordStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "import",
        mixinStandardHelpOptions = true,
        description = "Import a CoreProtect 20+ database into Spyglass ClickHouse.")
public final class ImportCommand implements Callable<Integer> {

    @Option(names = "--source", required = true,
            description = "Source DB. Either a path to a CoreProtect SQLite "
                    + "file, or a MySQL URL of the form "
                    + "mysql://user:password@host:port/database.")
    String source;

    @Option(names = "--worlds-dir", required = true,
            description = "Directory containing world folders with uid.dat. "
                    + "Importer scans <worlds-dir>/<worldname>/uid.dat for every "
                    + "world referenced by any event table; a missing file fails "
                    + "startup.")
    Path worldsDir;

    @Option(names = "--clickhouse-host", defaultValue = "localhost",
            description = "ClickHouse host (default: ${DEFAULT-VALUE}).")
    String clickhouseHost;

    @Option(names = "--clickhouse-port", defaultValue = "8123",
            description = "ClickHouse HTTP port (default: ${DEFAULT-VALUE}).")
    int clickhousePort;

    @Option(names = "--clickhouse-database", defaultValue = "spyglass",
            description = "ClickHouse database (default: ${DEFAULT-VALUE}).")
    String clickhouseDatabase;

    @Option(names = "--clickhouse-table", defaultValue = "event_records",
            description = "ClickHouse target table (default: ${DEFAULT-VALUE}).")
    String clickhouseTable;

    @Option(names = "--clickhouse-user", defaultValue = "default")
    String clickhouseUser;

    @Option(names = "--clickhouse-password", defaultValue = "")
    String clickhousePassword;

    @Option(names = "--clickhouse-ssl", defaultValue = "false")
    boolean clickhouseSsl;

    @Option(names = "--server-name", required = true,
            description = "Stamped onto every imported record's `server` field "
                    + "so srv:<name> queries partition cleanly.")
    String serverName;

    @Option(names = "--retention", defaultValue = "30d",
            description = "How long imported rows stay queryable after the "
                    + "import runs. expires_at = import_time + retention "
                    + "(NOT occurred + retention — CoreProtect data is "
                    + "typically older than any reasonable retention window, "
                    + "so event-relative TTL would evict it instantly). "
                    + "Format <number><unit> with unit in h|d|w "
                    + "(default: ${DEFAULT-VALUE}).")
    String retentionSpec;

    @Option(names = "--batch-size", defaultValue = "10000",
            description = "ClickHouse insert batch size (default: ${DEFAULT-VALUE}).")
    int batchSize;

    @Option(names = "--progress-interval", defaultValue = "50000",
            description = "Print progress every N rows (default: ${DEFAULT-VALUE}).")
    long progressInterval;

    @Option(names = "--dry-run",
            description = "Stream and map every row, but skip ClickHouse "
                    + "entirely. Reports per-event counts at the end. "
                    + "Useful for validating a source DB before a real import.")
    boolean dryRun;

    @Override
    public Integer call() {
        Duration retention;
        try {
            retention = ImportPipeline.parseRetention(retentionSpec);
        } catch (IllegalArgumentException ex) {
            System.err.println("ERROR: " + ex.getMessage());
            return 2;
        }

        long t0 = System.currentTimeMillis();
        System.out.println("Spyglass importer — starting");
        System.out.println("  source:      " + source);
        System.out.println("  worlds-dir:  " + worldsDir);
        System.out.println("  clickhouse:  " + (clickhouseSsl ? "https" : "http")
                + "://" + clickhouseHost + ":" + clickhousePort
                + "/" + clickhouseDatabase + "." + clickhouseTable);
        System.out.println("  server-name: " + serverName);
        System.out.println("  retention:   " + retentionSpec);
        System.out.println();

        try (CoreProtectSource src = openSource(source)) {
            System.out.println("Validating world UUIDs against " + worldsDir + "...");
            WorldMap worldMap = ImportPipeline.resolveWorlds(src, worldsDir);
            for (Map.Entry<String, UUID> entry : worldMap.asMap().entrySet()) {
                System.out.println("  " + entry.getKey() + " -> " + entry.getValue());
            }
            System.out.println();

            MappingContext mctx = new MappingContext(
                    worldMap, serverName, retention, Instant.now());
            CoreProtectMapper mapper = new CoreProtectMapper(mctx);

            RecordSink sink;
            CountingSink countingSink = null;
            if (dryRun) {
                System.out.println("--dry-run: skipping ClickHouse, counting only.");
                countingSink = new CountingSink();
                sink = countingSink;
            } else {
                System.out.println("Connecting to ClickHouse and ensuring schema...");
                ClickHouseRecordStore store = new ClickHouseRecordStore(
                        clickhouseHost, clickhousePort, clickhouseDatabase, clickhouseTable,
                        clickhouseUser, clickhousePassword, clickhouseSsl);
                sink = new ClickHouseSink(store, batchSize);
            }

            ImportSummary summary;
            try (RecordSink owned = sink) {
                ProgressReporter progress = new ProgressReporter(System.out, progressInterval);
                ImportPipeline pipeline = new ImportPipeline(
                        src, mapper, owned, progress, System.err);
                summary = pipeline.run();
            }

            long elapsed = System.currentTimeMillis() - t0;
            System.out.println();
            System.out.println("Done in " + elapsed + " ms");
            System.out.println("  total read:    " + summary.totalRead());
            System.out.println("  total written: " + summary.totalWritten());
            System.out.println("  total skipped: " + summary.totalSkipped());
            System.out.println();
            System.out.println("  per-table breakdown:");
            for (Map.Entry<String, ImportSummary.TableCounts> entry
                    : summary.perTable().entrySet()) {
                ImportSummary.TableCounts t = entry.getValue();
                System.out.printf("    %-15s read=%,d  written=%,d  skipped=%,d%n",
                        entry.getKey(), t.read(), t.written(), t.skippedTotal());
                t.skipped().forEach((reason, count) ->
                        System.out.println("      - " + reason + ": " + count));
                if (!t.unmappedActions().isEmpty()) {
                    System.out.println("      unmapped action codes:");
                    t.unmappedActions().forEach((code, count) ->
                            System.out.println("        action=" + code + ": " + count));
                }
            }
            if (countingSink != null && !countingSink.perEventCounts().isEmpty()) {
                System.out.println();
                System.out.println("  per-event counts (dry-run):");
                countingSink.perEventCounts().entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .forEach(e -> System.out.println(
                                "    " + e.getKey() + ": " + e.getValue()));
            }
            return 0;
        } catch (IOException ex) {
            System.err.println("ERROR: " + ex.getMessage());
            return 1;
        } catch (RuntimeException ex) {
            System.err.println("ERROR: " + ex.getClass().getSimpleName()
                    + ": " + ex.getMessage());
            ex.printStackTrace(System.err);
            return 1;
        }
    }

    private static CoreProtectSource openSource(String spec) throws IOException {
        if (spec.startsWith("mysql://")) {
            return MysqlSource.open(spec);
        }
        return SqliteSource.open(Paths.get(spec));
    }
}

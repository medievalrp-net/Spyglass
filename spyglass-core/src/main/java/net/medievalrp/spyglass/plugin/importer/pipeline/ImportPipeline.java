package net.medievalrp.spyglass.plugin.importer.pipeline;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import net.medievalrp.spyglass.plugin.importer.mapping.CoreProtectMapper;
import net.medievalrp.spyglass.plugin.importer.mapping.CoreProtectMapper.Outcome;
import net.medievalrp.spyglass.plugin.importer.mapping.CoreProtectMapper.SkipReason;
import net.medievalrp.spyglass.plugin.importer.sink.RecordSink;
import net.medievalrp.spyglass.plugin.importer.source.CoreProtectSource;
import net.medievalrp.spyglass.plugin.importer.world.WorldMap;

/**
 * Top-level orchestrator. Validates world UUIDs against the source's
 * declared world set first, then streams every CoreProtect event table
 * through the mapper into the sink, tallying per-table read / written
 * / skipped counts.
 *
 * <p>Failure mode: any {@link IOException} from the source / world
 * resolver bubbles up. Mapper skips never fail the import — they're
 * counted in the summary and logged at the end.
 */
public final class ImportPipeline {

    private final CoreProtectSource source;
    private final CoreProtectMapper mapper;
    private final RecordSink sink;
    private final ProgressReporter progress;
    private final ImportSummary summary;
    private final PrintStream warn;

    public ImportPipeline(CoreProtectSource source,
                          CoreProtectMapper mapper,
                          RecordSink sink,
                          ProgressReporter progress,
                          PrintStream warn) {
        this.source = source;
        this.mapper = mapper;
        this.sink = sink;
        this.progress = progress;
        this.summary = new ImportSummary();
        this.warn = warn;
    }

    public ImportSummary run() throws IOException {
        // Cap MISSING_PLAYER_UUID warns so a 50k-row pre-UUID legacy
        // dataset doesn't drown the log; the breakdown in the final
        // summary is the durable signal.
        long[] warnsEmitted = {0};
        long warnLimit = 50;

        runTable("co_block", warnsEmitted, warnLimit, c ->
                source.streamBlockRows(row -> {
                    summary.countRead("co_block");
                    Outcome out = mapper.mapBlock(row);
                    handleOutcome("co_block", out, row.action(),
                            warnsEmitted, warnLimit,
                            "rowid=" + row.rowid()
                                    + " player='" + row.playerName() + "'");
                }));
        runTable("co_session", warnsEmitted, warnLimit, c ->
                source.streamSessionRows(row -> {
                    summary.countRead("co_session");
                    Outcome out = mapper.mapSession(row);
                    handleOutcome("co_session", out, row.action(),
                            warnsEmitted, warnLimit,
                            "rowid=" + row.rowid() + " player='" + row.playerName() + "'");
                }));
        runTable("co_chat", warnsEmitted, warnLimit, c ->
                source.streamChatRows(row -> {
                    summary.countRead("co_chat");
                    Outcome out = mapper.mapChat(row);
                    handleOutcome("co_chat", out, -1, warnsEmitted, warnLimit,
                            "rowid=" + row.rowid() + " player='" + row.playerName() + "'");
                }));
        runTable("co_command", warnsEmitted, warnLimit, c ->
                source.streamCommandRows(row -> {
                    summary.countRead("co_command");
                    Outcome out = mapper.mapCommand(row);
                    handleOutcome("co_command", out, -1, warnsEmitted, warnLimit,
                            "rowid=" + row.rowid() + " player='" + row.playerName() + "'");
                }));
        runTable("co_container", warnsEmitted, warnLimit, c ->
                source.streamContainerRows(row -> {
                    summary.countRead("co_container");
                    Outcome out = mapper.mapContainer(row);
                    handleOutcome("co_container", out, row.action(),
                            warnsEmitted, warnLimit,
                            "rowid=" + row.rowid() + " player='" + row.playerName() + "'");
                }));
        runTable("co_item", warnsEmitted, warnLimit, c ->
                source.streamItemRows(row -> {
                    summary.countRead("co_item");
                    Outcome out = mapper.mapItem(row);
                    handleOutcome("co_item", out, row.action(),
                            warnsEmitted, warnLimit,
                            "rowid=" + row.rowid() + " player='" + row.playerName() + "'");
                }));

        sink.flush();
        return summary;
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run(Object unused) throws IOException;
    }

    private void runTable(String label, long[] warnsEmitted, long warnLimit,
                          IoRunnable streamer) throws IOException {
        progress.beginTable(label);
        streamer.run(null);
        progress.endTable(label, summary.forTable(label).read());
    }

    private void handleOutcome(String table, Outcome outcome, int action,
                               long[] warnsEmitted, long warnLimit,
                               String rowDesc) {
        if (outcome.record() != null) {
            sink.accept(outcome.record());
            summary.countWritten(table, outcome.provenance());
        } else if (outcome.skipReason() != null) {
            summary.countSkipped(table, outcome.skipReason());
            if (outcome.skipReason() == SkipReason.UNKNOWN_ACTION && action >= 0) {
                summary.countUnmappedAction(table, action);
            }
            if (outcome.skipReason() == SkipReason.MISSING_PLAYER_UUID
                    && warnsEmitted[0] < warnLimit) {
                warn.println("WARN " + table + " " + rowDesc
                        + " has no UUID — skipping");
                warnsEmitted[0]++;
                if (warnsEmitted[0] == warnLimit) {
                    warn.println("WARN ... further MISSING_PLAYER_UUID warns "
                            + "suppressed; see final summary for total");
                }
            }
        }
        progress.onRow(summary.totalRead());
    }

    /**
     * Build a {@link WorldMap} for every world the source references,
     * using {@code worldsDir} as the resolution root. Pulled out as a
     * static helper so the CLI can run validation as a discrete startup
     * step before constructing the rest of the pipeline.
     */
    public static WorldMap resolveWorlds(CoreProtectSource source,
                                         java.nio.file.Path worldsDir) throws IOException {
        List<String> worldNames = source.worldNames();
        if (worldNames.isEmpty()) {
            throw new IOException("Source contains no rows referencing worlds; "
                    + "is the database empty?");
        }
        return WorldMap.resolve(worldsDir, worldNames);
    }

    public static Duration parseRetention(String spec) {
        if (spec == null || spec.isEmpty()) {
            throw new IllegalArgumentException("--retention must be non-empty");
        }
        char unit = spec.charAt(spec.length() - 1);
        long value;
        try {
            value = Long.parseLong(spec.substring(0, spec.length() - 1));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("--retention '" + spec
                    + "' must be <number><unit>, e.g. 30d", ex);
        }
        return switch (unit) {
            case 'h' -> Duration.ofHours(value);
            case 'd' -> Duration.ofDays(value);
            case 'w' -> Duration.ofDays(value * 7);
            default -> throw new IllegalArgumentException(
                    "--retention '" + spec + "': unit must be h, d, or w");
        };
    }
}

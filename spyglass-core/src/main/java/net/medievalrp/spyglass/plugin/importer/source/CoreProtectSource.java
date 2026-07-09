package net.medievalrp.spyglass.plugin.importer.source;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Streams rows from a CoreProtect database (SQLite file or live MySQL).
 * Implementations are responsible for cursor management and lookup-table
 * joins; {@link #worldNames} is called first so the importer can validate
 * world UUIDs before pulling any rows.
 *
 * <p>Each {@code stream*} method emits fully-resolved row records — all
 * lookup-table joins happen in SQL, so the mapper has no DB dependency.
 */
public interface CoreProtectSource extends AutoCloseable {

    /**
     * Distinct world names referenced by any of the event tables. The
     * pipeline uses this to validate every world resolves against
     * {@code uid.dat} before iteration begins.
     */
    List<String> worldNames() throws IOException;

    void streamBlockRows(Consumer<CoreProtectBlockRow> consumer) throws IOException;

    void streamSessionRows(Consumer<CoreProtectSessionRow> consumer) throws IOException;

    void streamChatRows(Consumer<CoreProtectChatRow> consumer) throws IOException;

    void streamCommandRows(Consumer<CoreProtectChatRow> consumer) throws IOException;

    void streamContainerRows(Consumer<CoreProtectContainerRow> consumer) throws IOException;

    void streamItemRows(Consumer<CoreProtectItemRow> consumer) throws IOException;

    /**
     * Pre-flight retention preview over the tables the importer actually
     * reads: the oldest and newest event time (epoch seconds) and how many
     * rows predate {@code cutoffEpochSeconds} — i.e. how many would be aged
     * out by the configured retention right after import. Rows with a
     * non-positive time are ignored. Lets {@code /spyglass import} warn the
     * operator, before doing the heavy streaming pass, that part of their
     * history is older than {@code storage.retention} and will not be kept.
     */
    RetentionPreview retentionPreview(long cutoffEpochSeconds) throws IOException;

    /**
     * Time span + how much of it predates the retention cutoff.
     *
     * @param oldestEpochSeconds  oldest event time across imported tables (0 if none)
     * @param newestEpochSeconds  newest event time across imported tables (0 if none)
     * @param rowsBeforeCutoff    rows older than the cutoff (would be aged out)
     * @param totalRows           total rows across imported tables (with time &gt; 0)
     */
    record RetentionPreview(long oldestEpochSeconds, long newestEpochSeconds,
                            long rowsBeforeCutoff, long totalRows) {

        public boolean hasAgedOutRows() {
            return rowsBeforeCutoff > 0;
        }
    }

    /**
     * <strong>Not currently exposed as a stream.</strong> CoreProtect's
     * {@code co_sign} table records sign edits made in-place (right-click
     * a placed sign and rewrite a line). Spyglass has no sealed
     * {@code SignEditRecord} type in {@link
     * net.medievalrp.spyglass.api.event.EventRecord}'s permits, and
     * sign content can only ride along on {@link
     * net.medievalrp.spyglass.api.event.BlockBreakRecord} / place
     * snapshots — not on a standalone edit event. Importing
     * {@code co_sign} cleanly therefore needs a cross-module change
     * to {@code spyglass-api} first; the importer skips the table
     * entirely until that lands.
     *
     * <p>Note: signs that were broken (placed-then-broken) ARE captured
     * — those rows live in {@code co_block} with action=0 and their
     * content rides on the block-break snapshot.
     */
    @Override
    void close() throws IOException;
}

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

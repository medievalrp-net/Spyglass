package net.medievalrp.spyglass.importer.pipeline;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.medievalrp.spyglass.importer.mapping.CoreProtectMapper.Provenance;
import net.medievalrp.spyglass.importer.mapping.CoreProtectMapper.SkipReason;

/**
 * Running tally of an import session. Mutated by the pipeline as rows
 * are mapped, then logged at the end.
 *
 * <p>Tracks read/written/skipped counts per CoreProtect source table so
 * the operator can see which tables had what density.
 */
public final class ImportSummary {

    public static final class TableCounts {
        long read;
        long written;
        final Map<SkipReason, Long> skipped = new EnumMap<>(SkipReason.class);
        final Map<Provenance, Long> provenance = new EnumMap<>(Provenance.class);
        // Action-code histogram for UNKNOWN_ACTION skips, so the
        // operator can see *which* action codes are dropping rows.
        final Map<Integer, Long> unmappedActions = new java.util.TreeMap<>();

        public long read() { return read; }
        public long written() { return written; }
        public Map<SkipReason, Long> skipped() { return Map.copyOf(skipped); }
        public Map<Provenance, Long> provenance() { return Map.copyOf(provenance); }
        public Map<Integer, Long> unmappedActions() {
            return Map.copyOf(unmappedActions);
        }
        public long skippedTotal() {
            return skipped.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private final Map<String, TableCounts> perTable = new LinkedHashMap<>();

    public TableCounts forTable(String table) {
        return perTable.computeIfAbsent(table, k -> new TableCounts());
    }

    public void countRead(String table) { forTable(table).read++; }

    public void countWritten(String table, Provenance p) {
        TableCounts t = forTable(table);
        t.written++;
        if (p != null) {
            t.provenance.merge(p, 1L, Long::sum);
        }
    }

    public void countSkipped(String table, SkipReason reason) {
        forTable(table).skipped.merge(reason, 1L, Long::sum);
    }

    public void countUnmappedAction(String table, int action) {
        forTable(table).unmappedActions.merge(action, 1L, Long::sum);
    }

    public Map<String, TableCounts> perTable() {
        return Map.copyOf(perTable);
    }

    public long totalRead() {
        return perTable.values().stream().mapToLong(t -> t.read).sum();
    }

    public long totalWritten() {
        return perTable.values().stream().mapToLong(t -> t.written).sum();
    }

    public long totalSkipped() {
        return perTable.values().stream().mapToLong(TableCounts::skippedTotal).sum();
    }
}

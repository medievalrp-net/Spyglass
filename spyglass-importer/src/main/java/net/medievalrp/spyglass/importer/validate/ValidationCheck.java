package net.medievalrp.spyglass.importer.validate;

/**
 * One paired count check. {@code coreprotectSql} returns a single row
 * with a single integer column (the count); {@code spyglassSql} same
 * shape. The {@code expectedDeltaNote} is shown to the operator when
 * the counts differ — it's the "this divergence is expected because…"
 * explanation.
 */
public record ValidationCheck(
        String id,
        String description,
        String coreprotectSql,
        String spyglassSql,
        /** Maximum CP-minus-Spyglass delta we tolerate before flagging. */
        long toleratedDelta,
        /** Human-readable note shown when the counts diverge. */
        String expectedDeltaNote) {
}

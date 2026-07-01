package net.medievalrp.spyglass.importer.bench;

/**
 * Paired query — same conceptual question, expressed in each backend's
 * native dialect. Both queries should return the same row count for
 * the same dataset; the bench checks that as part of correctness.
 *
 * <p>Each backend uses a different schema:
 * <ul>
 *   <li>CoreProtect SQLite has lookup-table joins against
 *       {@code co_user}, {@code co_world}, {@code co_material_map}.</li>
 *   <li>Spyglass ClickHouse stores everything denormalized in
 *       {@code event_records} with secondary indexes.</li>
 * </ul>
 *
 * <p>For fairness we compare {@code COUNT(*)} where appropriate so neither
 * side benefits from per-row materialization differences.
 */
public record QueryCase(
        String id,
        String description,
        String coreprotectSqliteSql,
        String spyglassClickhouseSql) {
}

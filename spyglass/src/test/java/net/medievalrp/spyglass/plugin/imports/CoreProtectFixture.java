package net.medievalrp.spyglass.plugin.imports;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;

/**
 * Builds a minimal CoreProtect-20+ SQLite database in-code: the
 * {@code co_*} lookup tables {@link net.medievalrp.spyglass.plugin.importer.source.SchemaProbe}
 * requires present (even if empty), plus one resolvable world and one
 * {@code co_session} join row (the smallest table
 * {@link net.medievalrp.spyglass.plugin.importer.source.JdbcCoreProtectSource}
 * streams that maps cleanly to a Spyglass {@code JoinRecord} — no
 * material/blockdata joins to fake). The world's {@code uid.dat} is
 * written under {@code dir/<worldName>/uid.dat} so
 * {@code ImportPipeline.resolveWorlds} can resolve it.
 *
 * <p>Shared by {@link ImportServiceTest} (unit-level, {@code FakeStore})
 * and {@link ImportClickHouseIT} (end-to-end, real ClickHouse) so both
 * exercise the exact same source shape.
 */
final class CoreProtectFixture {

    private CoreProtectFixture() {
    }

    static Path sqlite(Path dir) throws Exception {
        Class.forName("org.sqlite.JDBC");
        String worldName = "world";

        Path worldDir = dir.resolve(worldName);
        Files.createDirectories(worldDir);
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(worldDir.resolve("uid.dat")))) {
            out.writeLong(1L);
            out.writeLong(2L);
        }

        Path dbFile = dir.resolve("coreprotect.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
            try (Statement st = conn.createStatement()) {
                // Lookup tables SchemaProbe.requireCoreProtect20Plus checks for
                // (co_block, co_user, co_world, co_material_map, co_blockdata_map),
                // plus co_entity_map so JdbcCoreProtectSource's co_block join
                // resolves cleanly even though co_block stays empty here.
                //
                // co_world/co_material_map/co_blockdata_map/co_entity_map are
                // joined via an explicit ".id" column, so a plain (non-PK) "id"
                // works. co_user/co_session/co_block are queried via ".rowid"
                // (e.g. "SELECT s.rowid ... FROM co_session s") - declaring an
                // INTEGER PRIMARY KEY on those tables makes SQLite alias that
                // column to the rowid, and xerial's JDBC driver then reports the
                // selected "rowid" expression under the ALIAS's column name
                // instead of "rowid", so ResultSet.getLong("rowid") throws "no
                // such column". Avoiding an explicit PK sidesteps that and lets
                // us still pin the implicit rowid via "INSERT ... (rowid, ...)".
                st.executeUpdate("CREATE TABLE co_world(id INTEGER, world TEXT)");
                st.executeUpdate("CREATE TABLE co_user(user TEXT, uuid TEXT, time INTEGER)");
                st.executeUpdate("CREATE TABLE co_material_map(id INTEGER, material TEXT)");
                st.executeUpdate("CREATE TABLE co_blockdata_map(id INTEGER, data TEXT)");
                st.executeUpdate("CREATE TABLE co_entity_map(id INTEGER, entity TEXT)");
                st.executeUpdate("CREATE TABLE co_block(time INTEGER, wid INTEGER, "
                        + "x INTEGER, y INTEGER, z INTEGER, type INTEGER, data INTEGER, "
                        + "blockdata TEXT, action INTEGER, rolled_back INTEGER, user INTEGER)");
                st.executeUpdate("CREATE TABLE co_session(time INTEGER, wid INTEGER, "
                        + "x INTEGER, y INTEGER, z INTEGER, action INTEGER, user INTEGER)");

                st.executeUpdate("INSERT INTO co_world(id, world) VALUES (1, '" + worldName + "')");
                st.executeUpdate("INSERT INTO co_user(rowid, user, uuid, time) VALUES "
                        + "(1, 'Notch', '00000000-0000-0000-0000-000000000001', 0)");
                long epochSeconds = Instant.now().getEpochSecond();
                // action=1 -> co_session login (JoinRecord); the only table this
                // fixture populates, so the summary's totalWritten() comes
                // entirely from this one row.
                st.executeUpdate("INSERT INTO co_session(rowid, time, wid, x, y, z, action, user) VALUES "
                        + "(1, " + epochSeconds + ", 1, 0, 64, 0, 1, 1)");
            }
        }
        return dbFile;
    }
}

package net.medievalrp.spyglass.plugin.importer.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import net.medievalrp.spyglass.plugin.importer.source.CoreProtectSource.RetentionPreview;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link JdbcCoreProtectSource#retentionPreview} aggregates MIN/MAX/COUNT
 * and a before-cutoff tally across every imported event table, ignores
 * non-positive times, and tolerates disabled (missing) tables. Drives the
 * {@code /spyglass import} pre-flight warning that tells operators how much
 * of their history predates {@code storage.retention}.
 */
class RetentionPreviewTest {

    @TempDir
    Path dir;

    private Connection open(String name) throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(name).toAbsolutePath());
    }

    @Test
    void aggregatesAcrossTablesIgnoresZeroTimeAndMissingTables() throws Exception {
        Path db = dir.resolve("cp.db");
        try (Connection c = open("cp.db"); Statement s = c.createStatement()) {
            // Only two of the six imported tables exist; the other four are
            // "disabled" and must be tolerated (no such table -> skipped).
            s.executeUpdate("CREATE TABLE co_block(time INTEGER)");
            s.executeUpdate("CREATE TABLE co_session(time INTEGER)");
            // co_block: 100, 200, 300, plus a 0 that must be ignored.
            s.executeUpdate("INSERT INTO co_block(time) VALUES (100),(200),(300),(0)");
            // co_session: 50 (the overall oldest) and 400 (the overall newest).
            s.executeUpdate("INSERT INTO co_session(time) VALUES (50),(400)");
        }

        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlite:" + db.toAbsolutePath())) {
            JdbcCoreProtectSource source = new JdbcCoreProtectSource(c, "test");
            RetentionPreview p = source.retentionPreview(250);

            assertThat(p.totalRows()).as("time>0 rows across both tables").isEqualTo(5);
            assertThat(p.rowsBeforeCutoff()).as("50,100,200 predate cutoff 250").isEqualTo(3);
            assertThat(p.oldestEpochSeconds()).isEqualTo(50);
            assertThat(p.newestEpochSeconds()).isEqualTo(400);
            assertThat(p.hasAgedOutRows()).isTrue();
        }
    }

    @Test
    void emptySourceReportsNoAgedOutRows() throws Exception {
        try (Connection c = open("empty.db"); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE co_block(time INTEGER)"); // present but empty
        }
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlite:" + dir.resolve("empty.db").toAbsolutePath())) {
            RetentionPreview p = new JdbcCoreProtectSource(c, "test").retentionPreview(250);
            assertThat(p.totalRows()).isZero();
            assertThat(p.hasAgedOutRows()).isFalse();
        }
    }
}

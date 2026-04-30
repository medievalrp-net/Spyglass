package net.medievalrp.spyglass.importer.validate;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import java.io.IOException;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import net.medievalrp.spyglass.importer.source.MysqlSource;
import org.sqlite.SQLiteConfig;

/**
 * Runs the {@link ValidationSuite} against both backends and prints a
 * pass/warn/fail report. Returns 0 if every check is within tolerance,
 * 1 otherwise — so this can be the post-import gate in a script.
 */
public final class ValidationRunner {

    public enum Status {
        OK,    // counts match exactly
        WARN,  // delta within tolerance — explained by expectedDeltaNote
        FAIL   // delta exceeds tolerance — investigate
    }

    public record CheckResult(ValidationCheck check, long cpCount,
                              long spyCount, Status status) {

        public long delta() { return cpCount - spyCount; }
    }

    private final PrintStream out;
    private final String sqliteFile;
    private final String mysqlUrl;
    private final String chHost;
    private final int chPort;
    private final String chDatabase;
    private final String chUser;
    private final String chPassword;
    private final boolean chSsl;
    private final String spyglassServerName;

    public ValidationRunner(PrintStream out,
                            String sqliteFile, String mysqlUrl,
                            String chHost, int chPort, String chDatabase,
                            String chUser, String chPassword, boolean chSsl,
                            String spyglassServerName) {
        this.out = out;
        this.sqliteFile = sqliteFile;
        this.mysqlUrl = mysqlUrl;
        this.chHost = chHost;
        this.chPort = chPort;
        this.chDatabase = chDatabase;
        this.chUser = chUser;
        this.chPassword = chPassword;
        this.chSsl = chSsl;
        this.spyglassServerName = spyglassServerName;
    }

    /** @return true if every check is OK or WARN; false if any FAIL. */
    public boolean run() throws IOException {
        List<ValidationCheck> checks = ValidationSuite.defaultChecks(spyglassServerName);
        out.println("Spyglass post-import validation");
        out.println("  CoreProtect: " + (sqliteFile != null
                ? "sqlite=" + sqliteFile : "mysql=" + mysqlUrl));
        out.println("  Spyglass:    clickhouse=" + chHost + ":" + chPort
                + "/" + chDatabase + " (server='" + spyglassServerName + "')");
        out.println();

        boolean allOk = true;
        try (Connection cpConn = openCoreProtect();
             Client chClient = openClickHouse()) {
            for (ValidationCheck check : checks) {
                CheckResult result = runOne(cpConn, chClient, check);
                printResult(result);
                if (result.status() == Status.FAIL) allOk = false;
            }
        } catch (SQLException ex) {
            throw new IOException("CoreProtect query failure: " + ex.getMessage(), ex);
        }

        out.println();
        if (allOk) {
            out.println("Validation PASSED. All counts within tolerance.");
        } else {
            out.println("Validation FAILED. One or more checks exceeded tolerance.");
            out.println("Review the FAIL lines above and the import summary.");
        }
        return allOk;
    }

    private CheckResult runOne(Connection cpConn, Client chClient,
                               ValidationCheck check)
            throws SQLException, IOException {
        long cp = scalarSqlite(cpConn, check.coreprotectSql());
        long spy = scalarClickhouse(chClient, check.spyglassSql());
        long delta = cp - spy;
        Status status;
        if (delta == 0) status = Status.OK;
        else if (Math.abs(delta) <= check.toleratedDelta()) status = Status.WARN;
        else status = Status.FAIL;
        return new CheckResult(check, cp, spy, status);
    }

    private void printResult(CheckResult r) {
        ValidationCheck c = r.check();
        String tag = switch (r.status()) {
            case OK -> "[OK  ]";
            case WARN -> "[WARN]";
            case FAIL -> "[FAIL]";
        };
        out.printf(Locale.ROOT, "%s %-32s  CP=%,d  Spy=%,d  delta=%+,d%n",
                tag, c.id(), r.cpCount(), r.spyCount(), r.delta());
        if (r.status() != Status.OK) {
            out.println("       " + c.expectedDeltaNote());
        }
    }

    // ===== queries =============================================

    private long scalarSqlite(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private long scalarClickhouse(Client client, String sql) throws IOException {
        try {
            for (GenericRecord rec : client.queryAll(sql)) {
                return rec.getLong(1);
            }
            return 0L;
        } catch (Exception ex) {
            throw new IOException("ClickHouse query failed: " + ex.getMessage()
                    + " (sql: " + sql + ")", ex);
        }
    }

    // ===== connections =========================================

    private Connection openCoreProtect() throws IOException {
        try {
            if (sqliteFile != null) {
                Class.forName("org.sqlite.JDBC");
                SQLiteConfig config = new SQLiteConfig();
                config.setReadOnly(true);
                Connection c = DriverManager.getConnection(
                        "jdbc:sqlite:" + sqliteFile, config.toProperties());
                c.setAutoCommit(false);
                return c;
            } else {
                Class.forName("com.mysql.cj.jdbc.Driver");
                MysqlSource.ConnectionSpec spec = MysqlSource.parse(mysqlUrl);
                java.util.Properties p = new java.util.Properties();
                p.setProperty("user", spec.user());
                p.setProperty("password", spec.password());
                Connection c = DriverManager.getConnection(spec.jdbcUrl(), p);
                c.setAutoCommit(false);
                c.setReadOnly(true);
                return c;
            }
        } catch (ClassNotFoundException | SQLException ex) {
            throw new IOException("Could not open CoreProtect source: "
                    + ex.getMessage(), ex);
        }
    }

    private Client openClickHouse() {
        return new Client.Builder()
                .addEndpoint((chSsl ? "https" : "http") + "://" + chHost + ":" + chPort)
                .setUsername(chUser)
                .setPassword(chPassword == null ? "" : chPassword)
                .setDefaultDatabase(chDatabase)
                .compressClientRequest(true)
                .compressServerResponse(false)
                .build();
    }
}

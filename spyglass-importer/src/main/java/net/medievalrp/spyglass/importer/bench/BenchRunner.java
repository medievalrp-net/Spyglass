package net.medievalrp.spyglass.importer.bench;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.data.ClickHouseFormat;
import java.io.IOException;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.medievalrp.spyglass.plugin.importer.source.MysqlSource;
import org.sqlite.SQLiteConfig;

/**
 * Runs the {@link BenchSuite} against both backends and prints a
 * side-by-side report.
 *
 * <p>Each query is run once for warm-up (so connection pool and OS
 * page cache are settled) before the timed loop. Wall-clock latency is
 * measured per execution; we collect {@code trials} samples per side
 * and report median / p95 / p99.
 */
public final class BenchRunner {

    private final PrintStream out;
    private final int trials;
    private final String sqliteFile;
    private final String mysqlUrl;
    private final String chHost;
    private final int chPort;
    private final String chDatabase;
    private final String chUser;
    private final String chPassword;
    private final boolean chSsl;

    public BenchRunner(PrintStream out, int trials,
                       String sqliteFile, String mysqlUrl,
                       String chHost, int chPort, String chDatabase,
                       String chUser, String chPassword, boolean chSsl) {
        this.out = out;
        this.trials = trials;
        this.sqliteFile = sqliteFile;
        this.mysqlUrl = mysqlUrl;
        this.chHost = chHost;
        this.chPort = chPort;
        this.chDatabase = chDatabase;
        this.chUser = chUser;
        this.chPassword = chPassword;
        this.chSsl = chSsl;
    }

    public void run() throws IOException {
        List<QueryCase> cases = BenchSuite.defaultCases();
        out.println("Spyglass-vs-CoreProtect query bench");
        out.println("  trials per side: " + trials);
        out.println("  CoreProtect: " + (sqliteFile != null
                ? "sqlite=" + sqliteFile : "mysql=" + mysqlUrl));
        out.println("  Spyglass:    clickhouse=" + chHost + ":" + chPort
                + "/" + chDatabase);
        out.println();

        try (Connection cpConn = openCoreProtect();
             Client chClient = openClickHouse()) {

            // Warm-up pass: run every query once on each side so JIT,
            // connection pooling, and OS caches are stable before we
            // start timing.
            out.println("Warming up...");
            for (QueryCase qc : cases) {
                runSqlite(cpConn, qc.coreprotectSqliteSql());
                runClickHouse(chClient, qc.spyglassClickhouseSql());
            }
            out.println();

            Map<String, Result> results = new LinkedHashMap<>();
            for (QueryCase qc : cases) {
                Result r = timeCase(cpConn, chClient, qc);
                results.put(qc.id(), r);
                String winner;
                double ratio = r.speedupAtMedian();
                if (ratio >= 1) {
                    winner = String.format(Locale.ROOT, "Spy %.1fx faster", ratio);
                } else if (ratio > 0) {
                    winner = String.format(Locale.ROOT, "CP %.1fx faster", 1.0 / ratio);
                } else {
                    winner = "n/a";
                }
                out.printf(Locale.ROOT,
                        "%-32s  CP %8.2f ms p50 / %7.2f p95   "
                                + "Spy %7.2f ms p50 / %7.2f p95   %s%n",
                        qc.id(),
                        r.coreprotect.medianMs(), r.coreprotect.p95Ms(),
                        r.spyglass.medianMs(), r.spyglass.p95Ms(), winner);
            }

            out.println();
            out.println("Detailed report:");
            out.println();
            for (QueryCase qc : cases) {
                Result r = results.get(qc.id());
                out.println("== " + qc.id() + " ==");
                out.println("  " + qc.description());
                out.printf(Locale.ROOT,
                        "  CoreProtect  count=%d  p50=%.2fms p95=%.2fms p99=%.2fms "
                                + "min=%.2fms max=%.2fms mean=%.2fms%n",
                        r.coreprotectCount,
                        r.coreprotect.medianMs(), r.coreprotect.p95Ms(),
                        r.coreprotect.p99Ms(), r.coreprotect.minMs(),
                        r.coreprotect.maxMs(), r.coreprotect.meanMs());
                out.printf(Locale.ROOT,
                        "  Spyglass     count=%d  p50=%.2fms p95=%.2fms p99=%.2fms "
                                + "min=%.2fms max=%.2fms mean=%.2fms%n",
                        r.spyglassCount,
                        r.spyglass.medianMs(), r.spyglass.p95Ms(),
                        r.spyglass.p99Ms(), r.spyglass.minMs(),
                        r.spyglass.maxMs(), r.spyglass.meanMs());
                if (r.coreprotectCount != r.spyglassCount) {
                    out.println("  WARN row counts differ — see notes; "
                            + "this can happen when CoreProtect's count "
                            + "uses rolled_back filters that Spyglass doesn't.");
                }
                out.println();
            }
        } catch (SQLException ex) {
            throw new IOException("CoreProtect query failure: " + ex.getMessage(), ex);
        }
    }

    private record Result(Timed coreprotect, long coreprotectCount,
                          Timed spyglass, long spyglassCount) {
        double speedupAtMedian() {
            // CoreProtect time / Spyglass time. >1 means Spyglass faster.
            double cp = coreprotect.medianMs();
            double sp = spyglass.medianMs();
            return sp == 0 ? 0 : cp / sp;
        }
    }

    private Result timeCase(Connection cpConn, Client chClient, QueryCase qc)
            throws SQLException, IOException {
        Timed cp = new Timed(trials);
        long cpCount = -1;
        for (int i = 0; i < trials; i++) {
            long t = System.nanoTime();
            long c = runSqlite(cpConn, qc.coreprotectSqliteSql());
            cp.record(System.nanoTime() - t);
            if (cpCount < 0) cpCount = c;
        }
        Timed sp = new Timed(trials);
        long spCount = -1;
        for (int i = 0; i < trials; i++) {
            long t = System.nanoTime();
            long c = runClickHouse(chClient, qc.spyglassClickhouseSql());
            sp.record(System.nanoTime() - t);
            if (spCount < 0) spCount = c;
        }
        return new Result(cp, cpCount, sp, spCount);
    }

    private long runSqlite(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            long rows = 0;
            while (rs.next()) rows++;
            return rows;
        }
    }

    private long runClickHouse(Client client, String sql) throws IOException {
        try (QueryResponse resp = client.query(sql).get(60, TimeUnit.SECONDS)) {
            // Drain the response body so we count cost end-to-end (not
            // just dispatch). queryAll buffers but we want the time
            // including ReadResponse, not just submit.
            long rows = 0;
            for (GenericRecord ignored : client.queryAll(sql)) {
                rows++;
            }
            return rows;
        } catch (Exception ex) {
            throw new IOException("ClickHouse query failed: " + ex.getMessage()
                    + " (sql: " + sql + ")", ex);
        }
    }

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
            throw new IOException("Could not open CoreProtect source for bench: "
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

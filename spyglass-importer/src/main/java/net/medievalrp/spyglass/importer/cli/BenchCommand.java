package net.medievalrp.spyglass.importer.cli;

import java.io.IOException;
import java.util.concurrent.Callable;
import net.medievalrp.spyglass.importer.bench.BenchRunner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "bench",
        mixinStandardHelpOptions = true,
        description = "Time equivalent queries against CoreProtect (SQLite "
                + "or MySQL) and Spyglass ClickHouse on the same dataset. "
                + "Run after `import` so both backends hold matching data.")
public final class BenchCommand implements Callable<Integer> {

    @Option(names = "--coreprotect-sqlite",
            description = "Path to a CoreProtect SQLite file. Mutually "
                    + "exclusive with --coreprotect-mysql.")
    String coreprotectSqlite;

    @Option(names = "--coreprotect-mysql",
            description = "MySQL URL of the form "
                    + "mysql://user:password@host:port/database. Mutually "
                    + "exclusive with --coreprotect-sqlite.")
    String coreprotectMysql;

    @Option(names = "--clickhouse-host", defaultValue = "localhost")
    String clickhouseHost;

    @Option(names = "--clickhouse-port", defaultValue = "8123")
    int clickhousePort;

    @Option(names = "--clickhouse-database", defaultValue = "spyglass")
    String clickhouseDatabase;

    @Option(names = "--clickhouse-user", defaultValue = "default")
    String clickhouseUser;

    @Option(names = "--clickhouse-password", defaultValue = "")
    String clickhousePassword;

    @Option(names = "--clickhouse-ssl", defaultValue = "false")
    boolean clickhouseSsl;

    @Option(names = "--trials", defaultValue = "20",
            description = "Trials per query per side (default: ${DEFAULT-VALUE}).")
    int trials;

    @Override
    public Integer call() {
        if (coreprotectSqlite == null && coreprotectMysql == null) {
            System.err.println("ERROR: must pass --coreprotect-sqlite "
                    + "or --coreprotect-mysql.");
            return 2;
        }
        if (coreprotectSqlite != null && coreprotectMysql != null) {
            System.err.println("ERROR: --coreprotect-sqlite and "
                    + "--coreprotect-mysql are mutually exclusive.");
            return 2;
        }
        BenchRunner runner = new BenchRunner(System.out, trials,
                coreprotectSqlite, coreprotectMysql,
                clickhouseHost, clickhousePort, clickhouseDatabase,
                clickhouseUser, clickhousePassword, clickhouseSsl);
        try {
            runner.run();
            return 0;
        } catch (IOException ex) {
            System.err.println("ERROR: " + ex.getMessage());
            return 1;
        }
    }
}

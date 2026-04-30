package net.medievalrp.spyglass.importer.cli;

import java.io.IOException;
import java.util.concurrent.Callable;
import net.medievalrp.spyglass.importer.validate.ValidationRunner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "validate",
        mixinStandardHelpOptions = true,
        description = "Compare row counts between a CoreProtect source "
                + "and Spyglass ClickHouse, after an import. Reports "
                + "per-table parity and exits 1 if any check exceeds "
                + "tolerance.")
public final class ValidateCommand implements Callable<Integer> {

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

    @Option(names = "--server-name", required = true,
            description = "Spyglass `server` value used at import time. "
                    + "Validate filters Spyglass counts to this server "
                    + "so multiple imports into one CH instance can be "
                    + "audited independently.")
    String serverName;

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
        ValidationRunner runner = new ValidationRunner(System.out,
                coreprotectSqlite, coreprotectMysql,
                clickhouseHost, clickhousePort, clickhouseDatabase,
                clickhouseUser, clickhousePassword, clickhouseSsl,
                serverName);
        try {
            return runner.run() ? 0 : 1;
        } catch (IOException ex) {
            System.err.println("ERROR: " + ex.getMessage());
            return 1;
        }
    }
}

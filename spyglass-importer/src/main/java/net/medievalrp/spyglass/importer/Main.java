package net.medievalrp.spyglass.importer;

import net.medievalrp.spyglass.importer.cli.BenchCommand;
import net.medievalrp.spyglass.importer.cli.ImportCommand;
import net.medievalrp.spyglass.importer.cli.ValidateCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "spyglass-importer",
        mixinStandardHelpOptions = true,
        version = "spyglass-importer 0.4.0",
        subcommands = {ImportCommand.class, BenchCommand.class, ValidateCommand.class},
        description = "CoreProtect → Spyglass tooling. "
                + "Use `import` to load a CoreProtect database into Spyglass "
                + "ClickHouse, `validate` to audit row counts after import, "
                + "and `bench` to time equivalent queries against CoreProtect "
                + "SQLite vs Spyglass ClickHouse on the same dataset.")
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}

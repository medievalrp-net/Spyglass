package net.medievalrp.spyglass.plugin.migration;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.spyglass.plugin.command.service.ServiceSupport;
import org.bukkit.command.CommandSender;

public final class MigrationCommand {

    private final MigrationService migrationService;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MigrationCommand(MigrationService migrationService, Logger logger) {
        this.migrationService = migrationService;
        this.logger = logger;
    }

    public void execute(CommandSender sender, String rawArgs) {
        if (!sender.hasPermission("spyglass.admin")) {
            sender.sendMessage(ServiceSupport.errorMessage("Missing permission spyglass.admin."));
            return;
        }
        if (!running.compareAndSet(false, true)) {
            sender.sendMessage(ServiceSupport.warnMessage("Migration already running."));
            return;
        }

        MigrationService.MigrationOptions options;
        try {
            options = parseOptions(rawArgs);
        } catch (IllegalArgumentException ex) {
            running.set(false);
            sender.sendMessage(ServiceSupport.errorMessage(ex.getMessage()));
            return;
        }

        sender.sendMessage(header(options));
        Thread.ofVirtual().name("sg-migrate-v1").start(() -> runMigration(sender, options));
    }

    private void runMigration(CommandSender sender, MigrationService.MigrationOptions options) {
        try {
            MigrationService.MigrationReport report = migrationService.migrate(options, update -> {
                logger.info("migration: processed=" + update.processed()
                        + "/" + update.expected()
                        + " translated=" + update.translated()
                        + " deferred=" + update.skippedDeferred()
                        + " skipped=" + update.skippedUnknown()
                        + " failed=" + update.failed());
                sender.sendMessage(Component.text(
                        "migration progress: " + update.processed() + "/" + update.expected()
                                + " translated=" + update.translated()
                                + " deferred=" + update.skippedDeferred(),
                        NamedTextColor.GRAY));
            });
            String summary = String.format(
                    "migration %s: processed=%d translated=%d deferred=%d skipped=%d failed=%d (%s.%s)",
                    options.dryRun() ? "dry-run complete" : "complete",
                    report.processed(), report.translated(),
                    report.skippedDeferred(), report.skippedUnknown(), report.failed(),
                    report.sourceDatabase(), report.sourceCollection());
            logger.info(summary);
            sender.sendMessage(Component.text(summary, NamedTextColor.GREEN));
        } catch (Exception ex) {
            logger.severe("migration aborted: " + ex.getMessage());
            sender.sendMessage(ServiceSupport.errorMessage("Migration aborted: " + ex.getMessage()));
        } finally {
            running.set(false);
        }
    }

    private Component header(MigrationService.MigrationOptions options) {
        return Component.text(
                (options.dryRun() ? "Dry-run: " : "")
                        + "v1 migration starting — source=" + options.sourceDatabase()
                        + "." + options.sourceCollection()
                        + " batch=" + options.batchSize()
                        + (options.resume() ? " (resume)" : ""),
                NamedTextColor.YELLOW);
    }

    private MigrationService.MigrationOptions parseOptions(String rawArgs) {
        MigrationService.MigrationOptions options = MigrationService.MigrationOptions.defaults();
        if (rawArgs == null || rawArgs.isBlank()) {
            return options;
        }
        for (String token : rawArgs.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            String lower = token.toLowerCase();
            if (lower.equals("--dry-run") || lower.equals("--dryrun") || lower.equals("-n")) {
                options = options.withDryRun(true);
            } else if (lower.equals("--resume")) {
                options = options.withResume(true);
            } else if (lower.startsWith("--batch-size=")) {
                int size;
                try {
                    size = Integer.parseInt(lower.substring("--batch-size=".length()));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Invalid --batch-size value: " + token);
                }
                if (size < 1 || size > 100_000) {
                    throw new IllegalArgumentException("--batch-size out of range (1-100000): " + size);
                }
                options = options.withBatchSize(size);
            } else {
                throw new IllegalArgumentException("Unknown migration option: " + token);
            }
        }
        return options;
    }
}

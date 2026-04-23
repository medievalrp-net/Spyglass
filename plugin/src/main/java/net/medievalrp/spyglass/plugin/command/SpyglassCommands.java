package net.medievalrp.spyglass.plugin.command;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.plugin.command.param.QueryStringParser;
import net.medievalrp.spyglass.plugin.command.render.ResultRenderer;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.incendo.cloud.SenderMapper;
import org.bukkit.plugin.java.JavaPlugin;

public final class SpyglassCommands {

    private final JavaPlugin plugin;
    private final SpyglassApi api;
    private final QueryStringParser parser;
    private final ResultRenderer renderer;
    private final PageCache pageCache;
    private final RollbackRunner rollbackRunner;
    private final UndoStack undoStack;
    private final SpyglassConfig config;

    public SpyglassCommands(JavaPlugin plugin, SpyglassApi api, QueryStringParser parser,
                        ResultRenderer renderer, PageCache pageCache,
                        RollbackRunner rollbackRunner, UndoStack undoStack,
                        SpyglassConfig config) {
        this.plugin = plugin;
        this.api = api;
        this.parser = parser;
        this.renderer = renderer;
        this.pageCache = pageCache;
        this.rollbackRunner = rollbackRunner;
        this.undoStack = undoStack;
        this.config = config;
    }

    public CommandManager<CommandSender> register() {
        LegacyPaperCommandManager<CommandSender> manager = LegacyPaperCommandManager.createNative(
                plugin,
                ExecutionCoordinator.simpleCoordinator());
        plugin.getLogger().info("Spyglass: Cloud command manager created (" + manager.getClass().getSimpleName() + ")");

        String[] roots = {"sg", "o2", "spyglass"};
        for (String root : roots) {
            manager.command(manager.commandBuilder(root)
                    .permission("spyglass.use")
                    .handler(ctx -> help(ctx.sender())));

            manager.command(manager.commandBuilder(root)
                    .literal("help")
                    .permission("spyglass.use")
                    .handler(ctx -> help(ctx.sender())));

            manager.command(manager.commandBuilder(root)
                    .literal("events")
                    .permission("spyglass.use")
                    .handler(ctx -> events(ctx.sender())));

            manager.command(manager.commandBuilder(root)
                    .literal("search")
                    .required("params", StringParser.greedyStringParser())
                    .permission("spyglass.search")
                    .handler(ctx -> search(ctx.sender(), ctx.get("params"))));

            manager.command(manager.commandBuilder(root)
                    .literal("rollback")
                    .required("params", StringParser.greedyStringParser())
                    .permission("spyglass.rollback")
                    .handler(ctx -> rollback(ctx.sender(), ctx.get("params"), RollbackRunner.Mode.ROLLBACK)));

            manager.command(manager.commandBuilder(root)
                    .literal("restore")
                    .required("params", StringParser.greedyStringParser())
                    .permission("spyglass.rollback")
                    .handler(ctx -> rollback(ctx.sender(), ctx.get("params"), RollbackRunner.Mode.RESTORE)));

            manager.command(manager.commandBuilder(root)
                    .literal("undo")
                    .permission("spyglass.rollback")
                    .handler(ctx -> undo(ctx.sender())));

            manager.command(manager.commandBuilder(root)
                    .literal("page")
                    .required("number", IntegerParser.integerParser(1))
                    .permission("spyglass.use")
                    .handler(ctx -> page(ctx.sender(), ctx.get("number"))));
        }

        return manager;
    }

    private void help(CommandSender sender) {
        plugin.getLogger().info("Spyglass: help handler fired, sender=" + sender.getClass().getSimpleName());
        sender.sendMessage(Component.text("Spyglass", NamedTextColor.AQUA));
        sender.sendMessage(Component.text(" /sg search <params>", NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" /sg rollback <params>", NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" /sg restore <params>", NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" /sg undo", NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" /sg page <n>", NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" /sg events", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Params: p: a: r: t: b: e: w:", NamedTextColor.DARK_GRAY));
        sender.sendMessage(Component.text("Flags: -ng -g -nc -ex -ord=asc|desc", NamedTextColor.DARK_GRAY));
    }

    private void events(CommandSender sender) {
        plugin.getLogger().info("Spyglass: events handler fired, sender=" + sender.getClass().getSimpleName());
        String joined = api.enabledEvents().stream().sorted().reduce((a, b) -> a + ", " + b).orElse("(none)");
        sender.sendMessage(Component.text("Enabled events: ", NamedTextColor.GRAY)
                .append(Component.text(joined, NamedTextColor.WHITE)));
    }

    private void search(CommandSender sender, String raw) {
        QueryRequest request;
        try {
            request = parser.parse(sender, raw, 0);
        } catch (ParamParseException ex) {
            sender.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("Searching...", NamedTextColor.DARK_GRAY));
        plugin.getLogger().info("Spyglass: search raw='" + raw + "'");
        api.query(request).whenComplete((result, error) -> {
            if (error != null) {
                sender.sendMessage(Component.text("Query failed: " + error.getMessage(), NamedTextColor.RED));
                plugin.getLogger().warning("Query failed: " + error);
                return;
            }
            plugin.getLogger().info("Spyglass: search got " + result.records().size() + " records, "
                    + result.aggregations().size() + " aggregations");
            handleResults(sender, request, result);
        });
    }

    private void handleResults(CommandSender sender, QueryRequest request, QueryResult result) {
        List<Component> lines;
        boolean grouping = request.grouping() && !request.flags().contains(Flag.NO_GROUP) && !result.aggregations().isEmpty();
        if (grouping) {
            lines = result.aggregations().stream().map(renderer::renderAggregation).toList();
        } else {
            lines = result.records().stream().map(renderer::renderSingle).toList();
        }
        if (lines.isEmpty()) {
            sender.sendMessage(Component.text("No matching records.", NamedTextColor.YELLOW));
            pageCache.clear(sender);
            return;
        }
        pageCache.store(sender, lines);
        pageCache.show(sender, 1);
    }

    private void rollback(CommandSender sender, String raw, RollbackRunner.Mode mode) {
        QueryRequest request;
        try {
            request = parser.parse(sender, raw, config.limits().rollbackResult());
        } catch (ParamParseException ex) {
            sender.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text(mode.name() + " running...", NamedTextColor.DARK_GRAY));
        rollbackRunner.run(sender, request, mode).whenComplete((summary, error) -> {
            if (error != null) {
                sender.sendMessage(Component.text(mode.name() + " failed: " + error.getMessage(), NamedTextColor.RED));
                plugin.getLogger().warning(mode.name() + " failed: " + error);
                return;
            }
            sender.sendMessage(RollbackRunner.summaryLine(summary));
        });
    }

    private void undo(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can undo.", NamedTextColor.RED));
            return;
        }
        var popped = undoStack.pop(player.getUniqueId());
        if (popped.isEmpty()) {
            sender.sendMessage(Component.text("Nothing to undo.", NamedTextColor.YELLOW));
            return;
        }
        List<RollbackEffect> effects = popped.get().inverseEffects();
        Bukkit.getScheduler().runTask(plugin, () -> {
            RollbackRunner.Summary summary = rollbackRunner.undo(player, effects);
            player.sendMessage(Component.text("Undo: ", NamedTextColor.GRAY)
                    .append(RollbackRunner.summaryLine(summary)));
        });
    }

    private void page(CommandSender sender, int number) {
        if (!pageCache.show(sender, number)) {
            sender.sendMessage(Component.text("No active search results.", NamedTextColor.YELLOW));
        }
    }
}

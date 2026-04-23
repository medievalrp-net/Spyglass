package net.medievalrp.omniscience2.plugin.command;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.omniscience2.api.Omniscience2Api;
import net.medievalrp.omniscience2.api.event.EventRecord;
import net.medievalrp.omniscience2.api.param.ParamParseException;
import net.medievalrp.omniscience2.api.query.Flag;
import net.medievalrp.omniscience2.api.query.QueryRequest;
import net.medievalrp.omniscience2.api.query.QueryResult;
import net.medievalrp.omniscience2.api.rollback.RollbackEffect;
import net.medievalrp.omniscience2.plugin.command.param.QueryStringParser;
import net.medievalrp.omniscience2.plugin.command.render.ResultRenderer;
import net.medievalrp.omniscience2.plugin.config.Omniscience2Config;
import net.medievalrp.omniscience2.plugin.rollback.UndoStack;
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

public final class OmniCommands {

    private final JavaPlugin plugin;
    private final Omniscience2Api api;
    private final QueryStringParser parser;
    private final ResultRenderer renderer;
    private final PageCache pageCache;
    private final RollbackRunner rollbackRunner;
    private final UndoStack undoStack;
    private final Omniscience2Config config;

    public OmniCommands(JavaPlugin plugin, Omniscience2Api api, QueryStringParser parser,
                        ResultRenderer renderer, PageCache pageCache,
                        RollbackRunner rollbackRunner, UndoStack undoStack,
                        Omniscience2Config config) {
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
        plugin.getLogger().info("Omniscience2: Cloud command manager created (" + manager.getClass().getSimpleName() + ")");

        String[] roots = {"omniv2", "o2", "omniscience2"};
        for (String root : roots) {
            manager.command(manager.commandBuilder(root)
                    .permission("omniscience2.use")
                    .handler(ctx -> help(ctx.sender())));

            manager.command(manager.commandBuilder(root)
                    .literal("help")
                    .permission("omniscience2.use")
                    .handler(ctx -> help(ctx.sender())));

            manager.command(manager.commandBuilder(root)
                    .literal("events")
                    .permission("omniscience2.use")
                    .handler(ctx -> events(ctx.sender())));

            manager.command(manager.commandBuilder(root)
                    .literal("search")
                    .required("params", StringParser.greedyStringParser())
                    .permission("omniscience2.search")
                    .handler(ctx -> search(ctx.sender(), ctx.get("params"))));

            manager.command(manager.commandBuilder(root)
                    .literal("rollback")
                    .required("params", StringParser.greedyStringParser())
                    .permission("omniscience2.rollback")
                    .handler(ctx -> rollback(ctx.sender(), ctx.get("params"), RollbackRunner.Mode.ROLLBACK)));

            manager.command(manager.commandBuilder(root)
                    .literal("restore")
                    .required("params", StringParser.greedyStringParser())
                    .permission("omniscience2.rollback")
                    .handler(ctx -> rollback(ctx.sender(), ctx.get("params"), RollbackRunner.Mode.RESTORE)));

            manager.command(manager.commandBuilder(root)
                    .literal("undo")
                    .permission("omniscience2.rollback")
                    .handler(ctx -> undo(ctx.sender())));

            manager.command(manager.commandBuilder(root)
                    .literal("page")
                    .required("number", IntegerParser.integerParser(1))
                    .permission("omniscience2.use")
                    .handler(ctx -> page(ctx.sender(), ctx.get("number"))));
        }

        return manager;
    }

    private void help(CommandSender sender) {
        plugin.getLogger().info("Omniscience2: help handler fired, sender=" + sender.getClass().getSimpleName());
        sender.sendMessage(Component.text("Omniscience2", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("  /omniv2 search <params>", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  /omniv2 rollback <params>", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  /omniv2 restore <params>", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  /omniv2 undo", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  /omniv2 page <n>", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  /omniv2 events", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Params: p: a: r: t: b: e: w:", NamedTextColor.DARK_GRAY));
        sender.sendMessage(Component.text("Flags: -ng -g -nc -ex -ord=asc|desc", NamedTextColor.DARK_GRAY));
    }

    private void events(CommandSender sender) {
        plugin.getLogger().info("Omniscience2: events handler fired, sender=" + sender.getClass().getSimpleName());
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
        plugin.getLogger().info("Omniscience2: search raw='" + raw + "'");
        api.query(request).whenComplete((result, error) -> {
            if (error != null) {
                sender.sendMessage(Component.text("Query failed: " + error.getMessage(), NamedTextColor.RED));
                plugin.getLogger().warning("Query failed: " + error);
                return;
            }
            plugin.getLogger().info("Omniscience2: search got " + result.records().size() + " records, "
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

package net.medievalrp.spyglass.plugin.command;

import java.util.List;
import net.medievalrp.spyglass.plugin.command.service.EventsService;
import net.medievalrp.spyglass.plugin.command.service.HelpService;
import net.medievalrp.spyglass.plugin.command.service.PageService;
import net.medievalrp.spyglass.plugin.command.service.RollbackMode;
import net.medievalrp.spyglass.plugin.command.service.RollbackService;
import net.medievalrp.spyglass.plugin.command.service.SearchService;
import net.medievalrp.spyglass.plugin.command.service.ToolService;
import net.medievalrp.spyglass.plugin.command.service.UndoService;
import net.medievalrp.spyglass.plugin.migration.MigrationCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.paper.LegacyPaperCommandManager;

public final class SpyglassCommands {

    private static final List<String> ROOT_ALIASES = List.of("sg", "o2", "spyglass");

    private final JavaPlugin plugin;
    private final HelpService help;
    private final EventsService events;
    private final SearchService search;
    private final RollbackService rollback;
    private final UndoService undo;
    private final PageService page;
    private final ToolService tool;
    private final SpyglassSuggestions suggestions;
    private final MigrationCommand migration;

    public SpyglassCommands(JavaPlugin plugin,
                        HelpService help,
                        EventsService events,
                        SearchService search,
                        RollbackService rollback,
                        UndoService undo,
                        PageService page,
                        ToolService tool,
                        SpyglassSuggestions suggestions,
                        MigrationCommand migration) {
        this.plugin = plugin;
        this.help = help;
        this.events = events;
        this.search = search;
        this.rollback = rollback;
        this.undo = undo;
        this.page = page;
        this.tool = tool;
        this.suggestions = suggestions;
        this.migration = migration;
    }

    public CommandManager<CommandSender> register() {
        LegacyPaperCommandManager<CommandSender> manager = LegacyPaperCommandManager.createNative(
                plugin, ExecutionCoordinator.simpleCoordinator());
        for (String root : ROOT_ALIASES) {
            manager.command(manager.commandBuilder(root)
                    .permission("spyglass.use")
                    .handler(ctx -> help.send(ctx.sender())));

            manager.command(manager.commandBuilder(root).literal("help")
                    .permission("spyglass.use")
                    .handler(ctx -> help.send(ctx.sender())));

            manager.command(manager.commandBuilder(root).literal("events")
                    .permission("spyglass.use")
                    .handler(ctx -> events.send(ctx.sender())));

            manager.command(manager.commandBuilder(root).literal("search")
                    .required("params", suggestions.paramsParser(), suggestions.paramsProvider())
                    .permission("spyglass.search")
                    .handler(ctx -> search.execute(ctx.sender(), ctx.get("params"))));

            manager.command(manager.commandBuilder(root).literal("rollback")
                    .required("params", suggestions.paramsParser(), suggestions.paramsProvider())
                    .permission("spyglass.rollback")
                    .handler(ctx -> rollback.execute(ctx.sender(), ctx.get("params"), RollbackMode.ROLLBACK)));

            manager.command(manager.commandBuilder(root).literal("restore")
                    .required("params", suggestions.paramsParser(), suggestions.paramsProvider())
                    .permission("spyglass.rollback")
                    .handler(ctx -> rollback.execute(ctx.sender(), ctx.get("params"), RollbackMode.RESTORE)));

            manager.command(manager.commandBuilder(root).literal("undo")
                    .permission("spyglass.rollback")
                    .handler(ctx -> undo.execute(ctx.sender())));

            manager.command(manager.commandBuilder(root).literal("page")
                    .required("number", IntegerParser.integerParser(1))
                    .permission("spyglass.use")
                    .handler(ctx -> page.show(ctx.sender(), ctx.get("number"))));

            manager.command(manager.commandBuilder(root).literal("tool")
                    .permission("spyglass.tool")
                    .handler(ctx -> tool.toggle(ctx.sender())));

            manager.command(manager.commandBuilder(root).literal("admin").literal("migrate-v1")
                    .permission("spyglass.admin")
                    .handler(ctx -> migration.execute(ctx.sender(), "")));

            manager.command(manager.commandBuilder(root).literal("admin").literal("migrate-v1")
                    .required("options", StringParser.greedyStringParser())
                    .permission("spyglass.admin")
                    .handler(ctx -> migration.execute(ctx.sender(), ctx.get("options"))));
        }
        return manager;
    }
}

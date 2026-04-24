package net.medievalrp.omniscience2.plugin.command;

import java.util.List;
import net.medievalrp.omniscience2.plugin.command.service.EventsService;
import net.medievalrp.omniscience2.plugin.command.service.HelpService;
import net.medievalrp.omniscience2.plugin.command.service.PageService;
import net.medievalrp.omniscience2.plugin.command.service.RollbackMode;
import net.medievalrp.omniscience2.plugin.command.service.RollbackService;
import net.medievalrp.omniscience2.plugin.command.service.SearchService;
import net.medievalrp.omniscience2.plugin.command.service.TeleportService;
import net.medievalrp.omniscience2.plugin.command.service.ToolService;
import net.medievalrp.omniscience2.plugin.command.service.UndoService;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class OmniCommands {

    private static final List<String> ROOT_ALIASES = List.of("omni2", "omniv2", "o2", "omniscience2");

    private final JavaPlugin plugin;
    private final HelpService help;
    private final EventsService events;
    private final SearchService search;
    private final RollbackService rollback;
    private final UndoService undo;
    private final PageService page;
    private final ToolService tool;
    private final TeleportService teleport;
    private final OmniSuggestions suggestions;

    public OmniCommands(JavaPlugin plugin,
                        HelpService help,
                        EventsService events,
                        SearchService search,
                        RollbackService rollback,
                        UndoService undo,
                        PageService page,
                        ToolService tool,
                        TeleportService teleport,
                        OmniSuggestions suggestions) {
        this.plugin = plugin;
        this.help = help;
        this.events = events;
        this.search = search;
        this.rollback = rollback;
        this.undo = undo;
        this.page = page;
        this.tool = tool;
        this.teleport = teleport;
        this.suggestions = suggestions;
    }

    public CommandManager<CommandSender> register() {
        LegacyPaperCommandManager<CommandSender> manager = LegacyPaperCommandManager.createNative(
                plugin, ExecutionCoordinator.simpleCoordinator());
        for (String root : ROOT_ALIASES) {
            manager.command(manager.commandBuilder(root)
                    .permission("omniscience2.use")
                    .handler(ctx -> help.send(ctx.sender())));

            manager.command(manager.commandBuilder(root).literal("help")
                    .permission("omniscience2.use")
                    .handler(ctx -> help.send(ctx.sender())));

            manager.command(manager.commandBuilder(root).literal("events")
                    .permission("omniscience2.use")
                    .handler(ctx -> events.send(ctx.sender())));

            manager.command(manager.commandBuilder(root).literal("search")
                    .required("params", suggestions.paramsParser(), suggestions.paramsProvider())
                    .permission("omniscience2.search")
                    .handler(ctx -> search.execute(ctx.sender(), ctx.get("params"))));

            manager.command(manager.commandBuilder(root).literal("rollback")
                    .required("params", suggestions.paramsParser(), suggestions.paramsProvider())
                    .permission("omniscience2.rollback")
                    .handler(ctx -> rollback.execute(ctx.sender(), ctx.get("params"), RollbackMode.ROLLBACK)));

            manager.command(manager.commandBuilder(root).literal("restore")
                    .required("params", suggestions.paramsParser(), suggestions.paramsProvider())
                    .permission("omniscience2.rollback")
                    .handler(ctx -> rollback.execute(ctx.sender(), ctx.get("params"), RollbackMode.RESTORE)));

            manager.command(manager.commandBuilder(root).literal("undo")
                    .permission("omniscience2.rollback")
                    .handler(ctx -> undo.execute(ctx.sender())));

            manager.command(manager.commandBuilder(root).literal("page")
                    .required("number", IntegerParser.integerParser(1))
                    .permission("omniscience2.use")
                    .handler(ctx -> page.show(ctx.sender(), ctx.get("number"))));

            manager.command(manager.commandBuilder(root).literal("tool")
                    .permission("omniscience2.tool")
                    .handler(ctx -> tool.toggle(ctx.sender())));

            // /omni2 tele <world> <x> <y> <z> — wired to search-result click
            // events so staff can jump to the scene of an incident.
            manager.command(manager.commandBuilder(root).literal("tele")
                    .required("world", StringParser.stringParser())
                    .required("x", StringParser.stringParser())
                    .required("y", StringParser.stringParser())
                    .required("z", StringParser.stringParser())
                    .permission("omniscience2.tele")
                    .handler(ctx -> teleport.execute(ctx.sender(),
                            ctx.get("world"), ctx.get("x"), ctx.get("y"), ctx.get("z"))));
        }
        return manager;
    }
}

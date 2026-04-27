package net.medievalrp.omniscience2.plugin.command;

import java.util.List;
import net.medievalrp.omniscience2.api.Omniscience2Api;
import net.medievalrp.omniscience2.plugin.command.render.Feedback;
import net.medievalrp.omniscience2.plugin.command.service.HelpService;
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
    private final Omniscience2Api api;
    private final HelpService help;
    private final SearchService search;
    private final RollbackService rollback;
    private final UndoService undo;
    private final PageCache pageCache;
    private final ToolService tool;
    private final TeleportService teleport;
    private final OmniSuggestions suggestions;

    public OmniCommands(JavaPlugin plugin,
                        Omniscience2Api api,
                        HelpService help,
                        SearchService search,
                        RollbackService rollback,
                        UndoService undo,
                        PageCache pageCache,
                        ToolService tool,
                        TeleportService teleport,
                        OmniSuggestions suggestions) {
        this.plugin = plugin;
        this.api = api;
        this.help = help;
        this.search = search;
        this.rollback = rollback;
        this.undo = undo;
        this.pageCache = pageCache;
        this.tool = tool;
        this.teleport = teleport;
        this.suggestions = suggestions;
    }

    // v1-compat subcommand aliases. Operators have years of muscle memory
    // around {@code /omni l} and {@code /omni rb}; v2 originally exposed
    // only the long names. Each list is shipped to {@link #subcommand}
    // which registers the same handler under every alias.
    private static final List<String> SEARCH_ALIASES = List.of("search", "s", "sc", "lookup", "l");
    private static final List<String> PAGE_ALIASES = List.of("page", "p", "pg");
    private static final List<String> ROLLBACK_ALIASES = List.of("rollback", "rb", "roll");
    private static final List<String> RESTORE_ALIASES = List.of("restore", "rs", "rst");
    private static final List<String> UNDO_ALIASES = List.of("undo", "u");
    private static final List<String> TOOL_ALIASES = List.of("tool", "t", "inspect");
    private static final List<String> EVENTS_ALIASES = List.of("events", "e");
    private static final List<String> HELP_ALIASES = List.of("help", "h", "?");

    public CommandManager<CommandSender> register() {
        LegacyPaperCommandManager<CommandSender> manager = LegacyPaperCommandManager.createNative(
                plugin, ExecutionCoordinator.simpleCoordinator());
        for (String root : ROOT_ALIASES) {
            manager.command(manager.commandBuilder(root)
                    .permission("omniscience2.use")
                    .handler(ctx -> help.send(ctx.sender())));

            for (String name : HELP_ALIASES) {
                manager.command(manager.commandBuilder(root).literal(name)
                        .permission("omniscience2.use")
                        .handler(ctx -> help.send(ctx.sender())));
            }

            for (String name : EVENTS_ALIASES) {
                manager.command(manager.commandBuilder(root).literal(name)
                        .permission("omniscience2.use")
                        .handler(ctx -> sendEnabledEvents(ctx.sender())));
            }

            for (String name : SEARCH_ALIASES) {
                manager.command(manager.commandBuilder(root).literal(name)
                        .required("params", suggestions.paramsParser(), suggestions.paramsProvider())
                        .permission("omniscience2.search")
                        .handler(ctx -> search.execute(ctx.sender(), ctx.get("params"))));
            }

            for (String name : ROLLBACK_ALIASES) {
                manager.command(manager.commandBuilder(root).literal(name)
                        .required("params", suggestions.paramsParser(), suggestions.paramsProvider())
                        .permission("omniscience2.rollback")
                        .handler(ctx -> rollback.execute(ctx.sender(), ctx.get("params"), RollbackMode.ROLLBACK)));
            }

            for (String name : RESTORE_ALIASES) {
                manager.command(manager.commandBuilder(root).literal(name)
                        .required("params", suggestions.paramsParser(), suggestions.paramsProvider())
                        .permission("omniscience2.rollback")
                        .handler(ctx -> rollback.execute(ctx.sender(), ctx.get("params"), RollbackMode.RESTORE)));
            }

            for (String name : UNDO_ALIASES) {
                manager.command(manager.commandBuilder(root).literal(name)
                        .permission("omniscience2.rollback")
                        .handler(ctx -> undo.execute(ctx.sender())));
            }

            for (String name : PAGE_ALIASES) {
                manager.command(manager.commandBuilder(root).literal(name)
                        .required("number", IntegerParser.integerParser(1))
                        .permission("omniscience2.use")
                        .handler(ctx -> pageCache.show(ctx.sender(), ctx.get("number"))));
            }

            for (String name : TOOL_ALIASES) {
                manager.command(manager.commandBuilder(root).literal(name)
                        .permission("omniscience2.tool")
                        .handler(ctx -> tool.toggle(ctx.sender())));
            }

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

    private void sendEnabledEvents(CommandSender sender) {
        String joined = api.enabledEvents().stream().sorted()
                .reduce((a, b) -> a + ", " + b)
                .orElse("(none)");
        sender.sendMessage(Feedback.success("Enabled Events: "));
        sender.sendMessage(Feedback.bonus(joined));
    }
}

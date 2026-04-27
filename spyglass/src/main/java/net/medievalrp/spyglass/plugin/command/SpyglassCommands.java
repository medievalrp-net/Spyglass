package net.medievalrp.spyglass.plugin.command;

import java.util.List;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.plugin.command.render.Feedback;
import net.medievalrp.spyglass.plugin.command.service.HelpService;
import net.medievalrp.spyglass.plugin.command.service.RollbackMode;
import net.medievalrp.spyglass.plugin.command.service.RollbackService;
import net.medievalrp.spyglass.plugin.command.service.SearchService;
import net.medievalrp.spyglass.plugin.command.service.TeleportService;
import net.medievalrp.spyglass.plugin.command.service.ToolService;
import net.medievalrp.spyglass.plugin.command.service.UndoService;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class SpyglassCommands {

    private static final List<String> ROOT_ALIASES = List.of("sg", "sg", "o2", "spyglass");

    private final JavaPlugin plugin;
    private final SpyglassApi api;
    private final HelpService help;
    private final SearchService search;
    private final RollbackService rollback;
    private final UndoService undo;
    private final PageCache pageCache;
    private final ToolService tool;
    private final TeleportService teleport;
    private final SpyglassSuggestions suggestions;

    public SpyglassCommands(JavaPlugin plugin,
                        SpyglassApi api,
                        HelpService help,
                        SearchService search,
                        RollbackService rollback,
                        UndoService undo,
                        PageCache pageCache,
                        ToolService tool,
                        TeleportService teleport,
                        SpyglassSuggestions suggestions) {
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
    // around {@code /sg l} and {@code /sg rb}; v2 originally exposed
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
                    .permission("spyglass.use")
                    .handler(ctx -> help.send(ctx.sender())));

            for (String name : HELP_ALIASES) {
                manager.command(manager.commandBuilder(root).literal(name)
                        .permission("spyglass.use")
                        .handler(ctx -> help.send(ctx.sender())));
            }

            for (String name : EVENTS_ALIASES) {
                manager.command(manager.commandBuilder(root).literal(name)
                        .permission("spyglass.use")
                        .handler(ctx -> sendEnabledEvents(ctx.sender())));
            }

            for (String name : SEARCH_ALIASES) {
                manager.command(manager.commandBuilder(root).literal(name)
                        .required("params", suggestions.paramsParser(), suggestions.paramsProvider())
                        .permission("spyglass.search")
                        .handler(ctx -> search.execute(ctx.sender(), ctx.get("params"))));
            }

            for (String name : ROLLBACK_ALIASES) {
                manager.command(manager.commandBuilder(root).literal(name)
                        .required("params", suggestions.paramsParser(), suggestions.paramsProvider())
                        .permission("spyglass.rollback")
                        .handler(ctx -> rollback.execute(ctx.sender(), ctx.get("params"), RollbackMode.ROLLBACK)));
            }

            for (String name : RESTORE_ALIASES) {
                manager.command(manager.commandBuilder(root).literal(name)
                        .required("params", suggestions.paramsParser(), suggestions.paramsProvider())
                        .permission("spyglass.rollback")
                        .handler(ctx -> rollback.execute(ctx.sender(), ctx.get("params"), RollbackMode.RESTORE)));
            }

            for (String name : UNDO_ALIASES) {
                manager.command(manager.commandBuilder(root).literal(name)
                        .permission("spyglass.rollback")
                        .handler(ctx -> undo.execute(ctx.sender())));
            }

            for (String name : PAGE_ALIASES) {
                manager.command(manager.commandBuilder(root).literal(name)
                        .required("number", IntegerParser.integerParser(1))
                        .permission("spyglass.use")
                        .handler(ctx -> pageCache.show(ctx.sender(), ctx.get("number"))));
            }

            for (String name : TOOL_ALIASES) {
                manager.command(manager.commandBuilder(root).literal(name)
                        .permission("spyglass.tool")
                        .handler(ctx -> tool.toggle(ctx.sender())));
            }

            // /sg tele <world> <x> <y> <z> — wired to search-result click
            // events so staff can jump to the scene of an incident.
            manager.command(manager.commandBuilder(root).literal("tele")
                    .required("world", StringParser.stringParser())
                    .required("x", StringParser.stringParser())
                    .required("y", StringParser.stringParser())
                    .required("z", StringParser.stringParser())
                    .permission("spyglass.tele")
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

package net.medievalrp.spyglass.plugin.command;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.api.rollback.Rollbackable;
import net.medievalrp.spyglass.plugin.rollback.RollbackEngine;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class RollbackRunner {

    public enum Mode {
        ROLLBACK,
        RESTORE
    }

    private final JavaPlugin plugin;
    private final SpyglassApi api;
    private final RollbackEngine engine;
    private final UndoStack undoStack;

    public RollbackRunner(JavaPlugin plugin, SpyglassApi api, RollbackEngine engine, UndoStack undoStack) {
        this.plugin = plugin;
        this.api = api;
        this.engine = engine;
        this.undoStack = undoStack;
    }

    public CompletionStage<Summary> run(CommandSender sender, QueryRequest request, Mode mode) {
        QueryRequest forced = forceNoGroup(request, mode);
        return api.query(forced).thenApply(result -> apply(sender, result, mode));
    }

    public Summary undo(Player player, List<RollbackEffect> inverseEffects) {
        return applyOnMainThread(player, inverseEffects, Mode.ROLLBACK, false);
    }

    private Summary apply(CommandSender sender, QueryResult result, Mode mode) {
        List<RollbackEffect> effects = new ArrayList<>();
        for (EventRecord record : result.records()) {
            if (record instanceof Rollbackable rollbackable) {
                effects.add(mode == Mode.ROLLBACK
                        ? rollbackable.rollbackEffect()
                        : rollbackable.restoreEffect());
            }
        }
        return applyOnMainThread(sender, effects, mode, true);
    }

    private Summary applyOnMainThread(CommandSender sender, List<RollbackEffect> effects, Mode mode, boolean persistUndo) {
        if (effects.isEmpty()) {
            return new Summary(0, 0, List.of());
        }
        List<RollbackResult> results = runSync(() -> engine.applyAll(effects, sender));
        List<RollbackEffect> inverses = new ArrayList<>();
        int applied = 0;
        int skipped = 0;
        for (RollbackResult result : results) {
            if (result instanceof RollbackResult.Applied appliedResult) {
                applied++;
                inverses.add(appliedResult.inverseEffect());
            } else {
                skipped++;
            }
        }
        if (persistUndo && !inverses.isEmpty() && sender instanceof Player player) {
            undoStack.push(player.getUniqueId(), mode.name(), inverses);
        }
        return new Summary(applied, skipped, results);
    }

    private <T> T runSync(java.util.function.Supplier<T> work) {
        if (Bukkit.isPrimaryThread()) {
            return work.get();
        }
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, work::get).get();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private QueryRequest forceNoGroup(QueryRequest request, Mode mode) {
        EnumSet<Flag> flags = EnumSet.copyOf(request.flags());
        flags.add(Flag.NO_GROUP);
        Sort sort = mode == Mode.ROLLBACK ? Sort.NEWEST_FIRST : Sort.OLDEST_FIRST;
        return new QueryRequest(request.predicates(), sort, request.limit(), flags, false);
    }

    public static Component summaryLine(Summary summary) {
        return Component.text()
                .append(Component.text(summary.applied() + " applied", NamedTextColor.GREEN))
                .append(Component.text(", ", NamedTextColor.GRAY))
                .append(Component.text(summary.skipped() + " skipped", NamedTextColor.YELLOW))
                .build();
    }

    public record Summary(int applied, int skipped, List<RollbackResult> results) {
    }
}

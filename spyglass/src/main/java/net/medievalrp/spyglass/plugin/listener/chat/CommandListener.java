package net.medievalrp.spyglass.plugin.listener.chat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.CommandRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class CommandListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public CommandListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("command");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String line = RecordingSupport.safeText(event.getMessage());
        BlockLocation location = BlockLocations.fromLocation(event.getPlayer().getLocation());
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        String head = extractHead(line);
        recorder.record(CommandRecord.of(ctx, head, line));
    }

    /**
     * Logs commands issued from the console, RCON, or command blocks.
     * Player commands are handled by {@link #onCommand} above.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        CommandSender sender = event.getSender();
        String line = RecordingSupport.safeText(event.getCommand());
        String head = extractHead(line);

        Origin origin;
        Source source;
        BlockLocation location;

        if (sender instanceof BlockCommandSender blockSender) {
            location = BlockLocations.fromLocation(blockSender.getBlock().getLocation());
            origin = Origin.environment("command-block");
            source = Source.commandBlock(location);
        } else {
            location = sentinelLocation();
            origin = Origin.environment("console");
            source = Source.console();
        }

        RecordContext ctx = support.context(origin, source, location);
        recorder.record(CommandRecord.of(ctx, head, line));
    }

    /**
     * Console + RCON commands don't have a world; synthesize a non-null
     * location in the first loaded world so {@link CommandRecord} stays
     * required-non-null and radius queries ({@code r:}) never match it.
     */
    private static BlockLocation sentinelLocation() {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return new BlockLocation(new UUID(0L, 0L), "server", 0, 0, 0);
        }
        World first = worlds.getFirst();
        return new BlockLocation(first.getUID(), first.getName(), 0, 0, 0);
    }

    private static String extractHead(String line) {
        String trimmed = line.startsWith("/") ? line.substring(1) : line;
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }
}

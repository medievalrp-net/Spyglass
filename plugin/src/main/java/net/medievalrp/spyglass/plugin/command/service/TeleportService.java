package net.medievalrp.spyglass.plugin.command.service;

import java.util.UUID;
import net.medievalrp.spyglass.plugin.command.render.Feedback;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * Backs the {@code /sg tele <world> <x> <y> <z>} subcommand that search
 * results wire to via click events. World is resolved by UUID first and
 * then by name, so the renderer can emit either; in practice it emits
 * the UUID because that's stable across server restarts where worlds
 * get renamed.
 *
 * <p>Coordinates land at block-center (+0.5 on x/z) to match v1's
 * {@code /sgtele} behavior — the player stands on the block's top
 * face rather than at the block's corner.
 */
@ApiStatus.Internal
public final class TeleportService {

    public void execute(CommandSender sender, String worldArg, String xArg, String yArg, String zArg) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Feedback.error("This command can only be run by players."));
            return;
        }
        World world = resolveWorld(worldArg);
        if (world == null) {
            sender.sendMessage(Feedback.error("World not found: " + worldArg));
            return;
        }
        double x;
        double y;
        double z;
        try {
            x = Double.parseDouble(xArg) + 0.5D;
            y = Double.parseDouble(yArg);
            z = Double.parseDouble(zArg) + 0.5D;
        } catch (NumberFormatException ex) {
            sender.sendMessage(Feedback.error("Invalid teleport coordinates."));
            return;
        }
        player.teleport(new Location(world, x, y, z));
    }

    private static World resolveWorld(String arg) {
        try {
            World byUuid = Bukkit.getWorld(UUID.fromString(arg));
            if (byUuid != null) {
                return byUuid;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return Bukkit.getWorld(arg);
    }
}

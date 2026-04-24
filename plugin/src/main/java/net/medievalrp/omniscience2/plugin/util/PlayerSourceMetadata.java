package net.medievalrp.omniscience2.plugin.util;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

/**
 * Tags blocks with the UUID of the player who caused them to reach
 * their current state. v1 did this as {@code "player-source"} on the
 * {@link Block}'s metadata so that fire spreading from a player-lit
 * block could still attribute downstream burn events back to the
 * arsonist.
 *
 * <p>Metadata is per-server-session (lives on the in-memory Block, not
 * persisted); that's fine — fire chains resolve within seconds and
 * don't survive restarts anyway. The key is namespaced on the plugin
 * name so we filter to our own entries during lookup.
 *
 * @see net.medievalrp.omniscience2.plugin.listener.environment.BlockIgniteListener
 */
@ApiStatus.Internal
public final class PlayerSourceMetadata {

    public static final String KEY = "omniscience2.player-source";

    private PlayerSourceMetadata() {
    }

    /** Tag {@code block} with the player's UUID. */
    public static void tag(Block block, Player player, Plugin plugin) {
        block.setMetadata(KEY, new FixedMetadataValue(plugin, player.getUniqueId().toString()));
    }

    /**
     * Copy the player-source tag from {@code source} onto {@code target}
     * if one exists. Used on fire-spread ignites so the newly-lit block
     * remains traceable back to the arsonist.
     */
    public static void inherit(Block source, Block target, Plugin plugin) {
        for (MetadataValue value : source.getMetadata(KEY)) {
            if (value.getOwningPlugin() == plugin) {
                target.setMetadata(KEY, value);
                return;
            }
        }
    }

    /**
     * Look up the tagged arsonist for {@code block}, if any. Returns
     * {@link Attribution#EMPTY} if no metadata is present or the stored
     * value doesn't parse as a UUID.
     */
    public static Attribution attributionOf(Block block, Plugin plugin) {
        List<MetadataValue> values = block.getMetadata(KEY);
        for (MetadataValue value : values) {
            if (value.getOwningPlugin() != plugin) {
                continue;
            }
            String asString = value.asString();
            if (asString == null || asString.isEmpty()) {
                continue;
            }
            try {
                UUID id = UUID.fromString(asString);
                OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
                String name = offline.getName();
                return new Attribution(id, name != null ? name : id.toString().substring(0, 8));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return Attribution.EMPTY;
    }

    /** The resolved UUID + name (best-effort) of a tagged arsonist. */
    public record Attribution(UUID id, String name) {

        public static final Attribution EMPTY = new Attribution(null, null);

        public Optional<UUID> uuid() {
            return Optional.ofNullable(id);
        }

        public boolean isPresent() {
            return id != null;
        }
    }
}

package net.medievalrp.spyglass.plugin.worldedit;

import com.sk89q.worldedit.extension.platform.Actor;
import net.medievalrp.spyglass.api.event.Source;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Maps a WorldEdit {@link Actor} to the Spyglass {@link Source} its edits should
 * be attributed to.
 *
 * <p>A player actor carries the player's identity ({@link Source#player}). Every
 * non-player actor — the console, an RCON sender, a command block, or a plugin
 * driving WorldEdit through its API with no live player — is attributed to
 * {@link Source#console()} so the edit is still audited and rollbackable. This
 * mirrors how the rest of Spyglass records non-player block changes (it already
 * tags command-block, console, entity, and environment sources) rather than
 * dropping them.
 *
 * <p>Before #105 the WorldEdit hook recorded player edits only: a console or
 * plugin {@code //set} changed blocks that never appeared in the audit log and
 * could not be rolled back. Pulled out as its own class so the mapping is unit
 * testable without a live WorldEdit platform.
 */
@ApiStatus.Internal
final class WorldEditActors {

    private WorldEditActors() {
    }

    /**
     * @param actor the WorldEdit actor behind an edit session, or {@code null}
     *              for an API edit with no actor
     * @return {@link Source#player} for a live player, else {@link Source#console()}
     */
    static Source resolveSource(@Nullable Actor actor) {
        if (actor != null && actor.isPlayer()) {
            return Source.player(actor.getUniqueId(), actor.getName());
        }
        return Source.console();
    }
}

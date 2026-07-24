package net.medievalrp.spyglass.plugin.snapshot;

import org.bukkit.entity.Player;

/**
 * A way to show a {@link SnapshotSession} to an operator. Same split as
 * {@code SalvageView}: the InvUI implementation exists on 1.21.x, and where
 * there is no GUI the service prints the text listing instead, so callers
 * treat "no view" (null) as command-only.
 */
public interface SnapshotView {

    /** Open the session for the viewer. Called on the main thread. */
    void open(Player viewer, SnapshotSession session);
}

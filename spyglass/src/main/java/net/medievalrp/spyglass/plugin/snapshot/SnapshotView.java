package net.medievalrp.spyglass.plugin.snapshot;

import org.bukkit.entity.Player;

/**
 * A way to show a {@link SnapshotSession} to an operator. Same split as
 * {@code SalvageView}: the InvUI implementation exists on 26.3, and where
 * there is no GUI the service reports an error; no text inventory is exposed.
 */
public interface SnapshotView {

    /** Open the session for the viewer. Called on the main thread. */
    void open(Player viewer, SnapshotSession session);
}

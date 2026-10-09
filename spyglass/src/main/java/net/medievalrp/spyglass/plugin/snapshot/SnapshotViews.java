package net.medievalrp.spyglass.plugin.snapshot;

import java.util.logging.Logger;
import net.medievalrp.spyglass.plugin.gui.Gui;

/** Builds the {@code /sg snapshot} GUI. */
public final class SnapshotViews {

    private SnapshotViews() {
    }

    /** @param takes the GUI take engine (permission, whole-stack fit rule, audit) */
    public static SnapshotView gui(Gui gui, SnapshotTakes takes, SnapshotSessions sessions,
                                   Logger logger) {
        return new SnapshotGuiView(gui, takes, sessions, logger);
    }
}

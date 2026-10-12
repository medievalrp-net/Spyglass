package net.medievalrp.spyglass.plugin.salvage;

import java.util.concurrent.Executor;
import java.util.logging.Logger;
import net.medievalrp.spyglass.plugin.gui.Gui;

/** Builds the {@code /sg inventory} salvage GUI. */
public final class SalvageViews {

    private SalvageViews() {
    }

    public static SalvageView gui(Gui gui, SalvageStore store, Executor storeExecutor,
                                  Executor mainExecutor, SalvageWithdrawals withdrawals,
                                  int rollbackListLimit, Logger logger) {
        return new SalvageGuiView(gui, store, storeExecutor, mainExecutor,
                withdrawals, rollbackListLimit, logger);
    }
}

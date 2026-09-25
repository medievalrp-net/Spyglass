package net.medievalrp.spyglass.plugin.salvage;

import org.bukkit.entity.Player;

/**
 * The interactive {@code /sg inventory} salvage GUI for a player: three
 * paginated, extract-only levels (rollbacks -> containers -> items).
 *
 * <p>Each supported distribution provides its matching InvUI implementation.
 * Missing GUIs report an error; there is no command-based recovery alternative.
 */
public interface SalvageView {

    /** Open the top level (the list of rollbacks with unrecovered salvage). */
    void open(Player player);
}

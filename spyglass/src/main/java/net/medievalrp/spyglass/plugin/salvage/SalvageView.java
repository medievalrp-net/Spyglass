package net.medievalrp.spyglass.plugin.salvage;

import org.bukkit.entity.Player;

/**
 * The interactive {@code /sg inventory} salvage GUI for a player: three
 * paginated, extract-only levels (rollbacks -> containers -> items).
 *
 * <p>Only the InvUI-backed {@link InvUiSalvageView} implements this, and only on
 * Minecraft versions InvUI 1.49 supports (1.x); {@link SalvageViews} returns
 * {@code null} elsewhere. On versions without a GUI, and for console/RCON,
 * salvage is served through the command path instead (see {@code SalvageService}).
 */
public interface SalvageView {

    /** Open the top level (the list of rollbacks with unrecovered salvage). */
    void open(Player player);
}

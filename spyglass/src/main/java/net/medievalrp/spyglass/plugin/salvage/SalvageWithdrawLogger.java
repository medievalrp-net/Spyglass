package net.medievalrp.spyglass.plugin.salvage;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Records an operator taking an item out of a salvage snapshot, so the
 * recovery is auditable ({@code a:salvage-withdraw}). Supplied by the plugin
 * (which owns the recorder); the GUI stays free of recording details.
 */
@FunctionalInterface
public interface SalvageWithdrawLogger {

    void log(Player operator, SalvageSnapshot snapshot, ItemStack taken, int amount);
}

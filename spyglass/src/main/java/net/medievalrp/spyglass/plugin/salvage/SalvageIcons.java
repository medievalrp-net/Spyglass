package net.medievalrp.spyglass.plugin.salvage;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Rendering for the InvUI salvage GUI icons and nav buttons ({@link
 * InvUiSalvageView}). Every icon is a plain Adventure-styled {@link ItemStack}
 * that the view wraps with {@code ItemWrapper}. Kept separate from the view so
 * the icon shapes are easy to eyeball and adjust in one place.
 */
final class SalvageIcons {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private SalvageIcons() {
    }

    static String shortId(UUID id) {
        return "#" + id.toString().substring(0, 8);
    }

    /** Top-level icon for one rollback that still has unrecovered salvage. */
    static ItemStack rollbackIcon(SalvageStore.RollbackGroup group) {
        ItemStack icon = new ItemStack(Material.CHEST_MINECART);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(name("Rollback by " + group.operatorName(), NamedTextColor.YELLOW));
            meta.lore(List.of(
                    line(group.containerCount() + " salvaged container(s)", NamedTextColor.GRAY),
                    line(TIME.format(group.latest()), NamedTextColor.DARK_GRAY),
                    line("Click to open", NamedTextColor.GREEN)));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    /** Container-level icon for one captured container. */
    static ItemStack chestIcon(SalvageSnapshot snap) {
        Material material = Material.matchMaterial(snap.containerType());
        if (material == null || !material.isItem()) {
            material = Material.CHEST;
        }
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(name(snap.containerType() + " " + shortId(snap.id()), NamedTextColor.YELLOW));
            meta.lore(List.of(
                    line(snap.worldName() + " " + snap.x() + ", " + snap.y() + ", " + snap.z(),
                            NamedTextColor.GRAY),
                    line(snap.items().size() + " stack(s)", NamedTextColor.GRAY),
                    line("Click to open", NamedTextColor.GREEN)));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    /** A nav/filler button (no italics, given colour). */
    static ItemStack button(Material material, String label, NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name(label, color == null ? NamedTextColor.DARK_GRAY : color));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static Component name(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color);
    }
}

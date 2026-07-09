package net.medievalrp.spyglass.plugin.command.render;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Prefixed Adventure components for the common sender feedback categories,
 * matching v1's {@code «Spyglass» (Error) ...} house style so an operator
 * moving between plugins sees consistent chat framing.
 */
@ApiStatus.Internal
public final class Feedback {

    /** «Spyglass» — green brackets, aqua name, reused across every message. */
    public static final Component PREFIX = Component.text()
            .append(Component.text("«", NamedTextColor.GREEN))
            .append(Component.text("Spyglass", NamedTextColor.AQUA))
            .append(Component.text("»", NamedTextColor.GREEN))
            .asComponent();

    private Feedback() {
    }

    public static Component error(String message) {
        return Component.text()
                .append(PREFIX)
                .append(Component.text(" (Error) ", NamedTextColor.RED))
                .append(Component.text(message, NamedTextColor.GRAY))
                .asComponent();
    }

    public static Component info(String message) {
        return Component.text()
                .append(PREFIX)
                .append(Component.text(" ", NamedTextColor.WHITE))
                .append(Component.text(message, NamedTextColor.GRAY))
                .asComponent();
    }

    public static Component warn(String message) {
        return Component.text()
                .append(PREFIX)
                .append(Component.text(" ", NamedTextColor.WHITE))
                .append(Component.text(message, NamedTextColor.YELLOW))
                .asComponent();
    }

    /**
     * `«Spyglass» <green>message</green>`. v1's Formatter.success had no
     * space after the prefix - the only category that didn't - which
     * rendered as `«Spyglass»Spyglass version x.y.z` on /sg version (#251).
     * All four categories now frame identically.
     */
    public static Component success(String message) {
        return Component.text()
                .append(PREFIX)
                .append(Component.text(" ", NamedTextColor.WHITE))
                .append(Component.text(message, NamedTextColor.GREEN))
                .asComponent();
    }

    /** Plain gray, no prefix — matches v1's Formatter.bonus (skip reasons, etc). */
    public static Component bonus(String message) {
        return Component.text(message, NamedTextColor.GRAY);
    }

    /** Dark-aqua "Querying records..." — v1's in-progress ping. No prefix. */
    public static Component querying() {
        return Component.text("Querying records...", NamedTextColor.DARK_AQUA);
    }

    /** Plain green tool feedback, no prefix — matches v1's wand chat lines. */
    public static Component toolOk(String message) {
        return Component.text(message, NamedTextColor.GREEN);
    }

    /** `«Spyglass» --- TARGET at x y z ---` — used by the wand before a lookup. */
    public static Component inspectHeader(String target, BlockLocation location) {
        return Component.text()
                .append(PREFIX)
                .append(Component.text(" --- ", NamedTextColor.GREEN))
                .append(Component.text(target, NamedTextColor.AQUA))
                .append(Component.text(" at ", NamedTextColor.WHITE))
                .append(Component.text(
                        location.x() + " " + location.y() + " " + location.z(),
                        NamedTextColor.GREEN))
                .append(Component.text(" ---", NamedTextColor.GREEN))
                .asComponent();
    }
}

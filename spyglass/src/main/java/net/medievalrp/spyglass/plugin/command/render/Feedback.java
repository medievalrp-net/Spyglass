package net.medievalrp.spyglass.plugin.command.render;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Prefixed Adventure components for the common sender feedback categories,
 * matching v1's {@code «v1» (Error) ...} house style so an operator
 * moving between plugins sees consistent chat framing.
 */
@ApiStatus.Internal
public final class Feedback {

    /** «v1» — green brackets, aqua name, reused across every message. */
    public static final Component PREFIX = Component.text()
            .append(Component.text("«", NamedTextColor.GREEN))
            .append(Component.text("v1", NamedTextColor.AQUA))
            .append(Component.text("»", NamedTextColor.GREEN))
            .build();

    private Feedback() {
    }

    public static Component error(String message) {
        return Component.text()
                .append(PREFIX)
                .append(Component.text(" (Error) ", NamedTextColor.RED))
                .append(Component.text(message, NamedTextColor.GRAY))
                .build();
    }

    public static Component info(String message) {
        return Component.text()
                .append(PREFIX)
                .append(Component.text(" ", NamedTextColor.WHITE))
                .append(Component.text(message, NamedTextColor.GRAY))
                .build();
    }

    public static Component warn(String message) {
        return Component.text()
                .append(PREFIX)
                .append(Component.text(" ", NamedTextColor.WHITE))
                .append(Component.text(message, NamedTextColor.YELLOW))
                .build();
    }

    /** `«v1»<green>message</green>` — matches v1's Formatter.success. */
    public static Component success(String message) {
        return Component.text()
                .append(PREFIX)
                .append(Component.text(message, NamedTextColor.GREEN))
                .build();
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

    /** `«v1» --- TARGET at x y z ---` — used by the wand before a lookup. */
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
                .build();
    }
}

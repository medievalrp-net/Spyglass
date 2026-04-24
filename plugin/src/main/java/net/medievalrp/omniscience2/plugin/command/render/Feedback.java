package net.medievalrp.omniscience2.plugin.command.render;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.ApiStatus;

/**
 * Short, un-templated Adventure components for the three common sender
 * feedback categories: error (red), info (dark gray), warn (yellow).
 *
 * <p>Split off from {@code ServiceSupport} so that thread-scheduling and
 * chat-rendering concerns don't share an interface.
 */
@ApiStatus.Internal
public final class Feedback {

    private Feedback() {
    }

    public static Component error(String message) {
        return Component.text(message, NamedTextColor.RED);
    }

    public static Component info(String message) {
        return Component.text(message, NamedTextColor.DARK_GRAY);
    }

    public static Component warn(String message) {
        return Component.text(message, NamedTextColor.YELLOW);
    }
}

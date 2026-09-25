package net.medievalrp.spyglass.plugin.listener;

import java.util.function.Consumer;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/** Isolates API additions so the same listener can load against older supported Paper versions. */
public final class OptionalPaperEvents {
    private OptionalPaperEvents() {}
    public static void register(Plugin plugin, Listener owner, String name, Consumer<Event> handler) {
        try {
            Class<? extends Event> type = Class.forName(name).asSubclass(Event.class);
            plugin.getServer().getPluginManager().registerEvent(type, owner, EventPriority.MONITOR,
                    (listener, event) -> handler.accept(event), plugin, true);
        } catch (ClassNotFoundException absentOnThisVersion) {
            // Capability absent; there cannot be an occurrence of this event on this server.
        }
    }
    public static Object call(Object object, String method) {
        try { return object.getClass().getMethod(method).invoke(object); }
        catch (ReflectiveOperationException ex) { throw new IllegalStateException("Paper API changed: " + method, ex); }
    }
}

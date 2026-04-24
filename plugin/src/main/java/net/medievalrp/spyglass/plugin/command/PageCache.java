package net.medievalrp.spyglass.plugin.command;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class PageCache implements Listener {

    private static final int PAGE_SIZE = 10;
    private static final UUID CONSOLE_ID = new UUID(0L, 0L);
    private static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(15);

    private final ConcurrentHashMap<UUID, CachedPage> pages = new ConcurrentHashMap<>();

    public void store(CommandSender sender, List<Component> lines) {
        pages.put(idOf(sender), new CachedPage(List.copyOf(lines), System.currentTimeMillis()));
    }

    public boolean show(CommandSender sender, int page) {
        UUID id = idOf(sender);
        CachedPage cached = pages.get(id);
        if (cached == null) {
            return false;
        }
        if (System.currentTimeMillis() - cached.storedAt() > TTL_MILLIS) {
            pages.remove(id);
            return false;
        }
        List<Component> lines = cached.lines();
        if (lines.isEmpty()) {
            return false;
        }
        int total = (int) Math.ceil(lines.size() / (double) PAGE_SIZE);
        int pageClamped = Math.max(1, Math.min(page, total));
        int start = (pageClamped - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, lines.size());

        Audience audience = sender;
        audience.sendMessage(Component.text("Page " + pageClamped + "/" + total + " — " + lines.size() + " results", NamedTextColor.GRAY));
        for (int index = start; index < end; index++) {
            audience.sendMessage(lines.get(index));
        }
        return true;
    }

    public void clear(CommandSender sender) {
        pages.remove(idOf(sender));
    }

    public int pageSize() {
        return PAGE_SIZE;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pages.remove(event.getPlayer().getUniqueId());
    }

    private static UUID idOf(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUniqueId();
        }
        if (sender instanceof ConsoleCommandSender) {
            return CONSOLE_ID;
        }
        return UUID.nameUUIDFromBytes(sender.getName().getBytes());
    }

    private record CachedPage(List<Component> lines, long storedAt) {
    }
}

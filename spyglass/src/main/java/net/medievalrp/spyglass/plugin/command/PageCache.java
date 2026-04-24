package net.medievalrp.spyglass.plugin.command;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.plugin.command.render.ResultRenderer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Per-sender paged result cache.
 *
 * <p>Historically {@code store} took a pre-rendered {@code List<Component>},
 * which meant the search hot path materialized every line's Component tree
 * (hover, click, sub-components — ~25 nodes each) before handing results
 * back. On a 1 000-result search that was ~25k Component allocations on
 * the Bukkit main thread before the first 10-line page appeared, and the
 * cached list then held ~4 MB for 15 minutes. v1's architecture caches
 * raw records and renders on page flip; this class now does the same:
 * {@link #store} takes a count and an index -> Component renderer, and
 * {@link #show} only renders the visible window.
 */
@ApiStatus.Internal
public final class PageCache implements Listener {

    private static final int PAGE_SIZE = 10;
    private static final UUID CONSOLE_ID = new UUID(0L, 0L);
    private static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(15);

    private final ConcurrentHashMap<UUID, CachedPage> pages = new ConcurrentHashMap<>();

    /**
     * Cache a paged result as a lazy line source. {@code renderer} is
     * invoked per line as the user pages through; the caller must retain
     * anything the renderer closes over (records, flags, render config).
     */
    public void store(CommandSender sender, int count, IntFunction<Component> renderer) {
        pages.put(idOf(sender), new CachedPage(count, renderer, System.currentTimeMillis()));
    }

    /**
     * Back-compat helper: cache an already-rendered component list. Kept
     * for callers that do tiny eager renders where laziness buys nothing.
     * The search path no longer uses this — see {@link SearchService}.
     */
    public void store(CommandSender sender, List<Component> lines) {
        List<Component> copy = List.copyOf(lines);
        store(sender, copy.size(), copy::get);
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
        int count = cached.count();
        if (count <= 0) {
            return false;
        }
        int total = (int) Math.ceil(count / (double) PAGE_SIZE);
        int pageClamped = Math.max(1, Math.min(page, total));
        int start = (pageClamped - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, count);

        Audience audience = sender;
        audience.sendMessage(ResultRenderer.pageHeader(pageClamped, total, count));
        IntFunction<Component> renderer = cached.renderer();
        for (int index = start; index < end; index++) {
            audience.sendMessage(renderer.apply(index));
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

    private record CachedPage(int count, IntFunction<Component> renderer, long storedAt) {
    }
}

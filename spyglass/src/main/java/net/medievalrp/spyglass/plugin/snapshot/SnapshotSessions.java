package net.medievalrp.spyglass.plugin.snapshot;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Per-sender TTL cache of the last {@code /sg snapshot} a sender resolved
 * (#341), the {@link net.medievalrp.spyglass.plugin.command.PageCache}
 * idiom applied to snapshots: the GUI and the text-fallback take command
 * must act on exactly the state the operator was shown, not a fresh
 * re-read that could have moved on since (a later deposit, another
 * operator's take, a second snapshot the same sender ran in the
 * meantime). A sender holds exactly one live session; resolving a new
 * snapshot replaces it, same as paging replaces the last search.
 *
 * <p>15 minute TTL, matching {@code PageCache}. Cleared on quit so the
 * map does not hold an entry for a player who left for good.
 */
@ApiStatus.Internal
public final class SnapshotSessions implements Listener {

    private static final UUID CONSOLE_ID = new UUID(0L, 0L);
    private static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(15);

    private final ConcurrentHashMap<UUID, Entry> sessions = new ConcurrentHashMap<>();

    /** Cache {@code session} for {@code sender}, replacing any previous entry. */
    public void store(CommandSender sender, SnapshotSession session) {
        sessions.put(idOf(sender), new Entry(session, System.currentTimeMillis()));
    }

    /**
     * The sender's cached session, if any and unexpired - used right after
     * a fresh resolution, before any token has been shown to anyone, so
     * there is nothing to check it against yet.
     */
    public Optional<SnapshotSession> get(CommandSender sender) {
        Entry entry = liveEntry(sender);
        return entry == null ? Optional.empty() : Optional.of(entry.session());
    }

    /**
     * Resolve the sender's cached session for a take, requiring
     * {@code token} to match the one that was cached. A mismatch (a stale
     * link from a render a newer {@code /sg snapshot} superseded, or a
     * hand-typed wrong token) is indistinguishable from outright expiry to
     * the caller - both mean "the state you're pointing at is no longer
     * the live cached one" - so both report through the same empty
     * {@link Optional} and the same friendly error at the call site.
     */
    public Optional<SnapshotSession> resolve(CommandSender sender, UUID token) {
        Entry entry = liveEntry(sender);
        if (entry == null || !entry.session().token().equals(token)) {
            return Optional.empty();
        }
        return Optional.of(entry.session());
    }

    /** Drop the sender's cached session, if any. */
    public void clear(CommandSender sender) {
        sessions.remove(idOf(sender));
    }

    private Entry liveEntry(CommandSender sender) {
        UUID id = idOf(sender);
        Entry entry = sessions.get(id);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.storedAt() > TTL_MILLIS) {
            // Remove only the exact expired entry: a store() that raced in
            // between (a new /sg snapshot on another thread) must not be
            // clobbered by this cleanup.
            sessions.remove(id, entry);
            return null;
        }
        return entry;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
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

    private record Entry(SnapshotSession session, long storedAt) {
    }
}

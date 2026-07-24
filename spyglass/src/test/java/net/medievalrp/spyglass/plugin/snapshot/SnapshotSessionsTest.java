package net.medievalrp.spyglass.plugin.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

/**
 * The {@code PageCache} idiom applied to snapshots: one live session per
 * sender, resolved only by the exact token that was cached for them.
 */
class SnapshotSessionsTest {

    private static SnapshotSession session(UUID token) {
        return new SnapshotSession(token, SnapshotSession.Kind.PLAYER, "Steve", Instant.EPOCH,
                Instant.EPOCH, PlayerSnapshot.CAUSE_JOIN, SnapshotSession.Certainty.CERTAIN,
                List.of(), 6, List.of());
    }

    private static Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Test
    void resolveSucceedsWithTheExactCachedToken() {
        SnapshotSessions sessions = new SnapshotSessions();
        Player sender = playerWithId(UUID.randomUUID());
        UUID token = UUID.randomUUID();
        sessions.store(sender, session(token));

        assertThat(sessions.resolve(sender, token)).isPresent();
    }

    @Test
    void resolveFailsOnATokenMismatch() {
        // A stale link from a render a newer /sg snapshot superseded, or a
        // hand-typed wrong token - both must be refused, not resolve to
        // whatever happens to be cached.
        SnapshotSessions sessions = new SnapshotSessions();
        Player sender = playerWithId(UUID.randomUUID());
        sessions.store(sender, session(UUID.randomUUID()));

        assertThat(sessions.resolve(sender, UUID.randomUUID())).isEmpty();
    }

    @Test
    void resolveFailsWithNothingCached() {
        SnapshotSessions sessions = new SnapshotSessions();
        Player sender = playerWithId(UUID.randomUUID());

        assertThat(sessions.resolve(sender, UUID.randomUUID())).isEmpty();
    }

    @Test
    void getIgnoresTheTokenAndReturnsWhateverIsCached() {
        SnapshotSessions sessions = new SnapshotSessions();
        Player sender = playerWithId(UUID.randomUUID());
        UUID token = UUID.randomUUID();
        sessions.store(sender, session(token));

        assertThat(sessions.get(sender)).isPresent();
        assertThat(sessions.get(sender).get().token()).isEqualTo(token);
    }

    @Test
    void aNewStoreReplacesThePreviousSessionAndItsOldTokenNoLongerResolves() {
        SnapshotSessions sessions = new SnapshotSessions();
        Player sender = playerWithId(UUID.randomUUID());
        UUID firstToken = UUID.randomUUID();
        UUID secondToken = UUID.randomUUID();
        sessions.store(sender, session(firstToken));
        sessions.store(sender, session(secondToken));

        assertThat(sessions.resolve(sender, firstToken)).isEmpty();
        assertThat(sessions.resolve(sender, secondToken)).isPresent();
    }

    @Test
    void clearRemovesTheCachedSession() {
        SnapshotSessions sessions = new SnapshotSessions();
        Player sender = playerWithId(UUID.randomUUID());
        UUID token = UUID.randomUUID();
        sessions.store(sender, session(token));

        sessions.clear(sender);

        assertThat(sessions.get(sender)).isEmpty();
        assertThat(sessions.resolve(sender, token)).isEmpty();
    }

    @Test
    void differentSendersNeverShareASession() {
        SnapshotSessions sessions = new SnapshotSessions();
        Player alice = playerWithId(UUID.randomUUID());
        Player bob = playerWithId(UUID.randomUUID());
        UUID aliceToken = UUID.randomUUID();
        sessions.store(alice, session(aliceToken));

        assertThat(sessions.get(bob)).isEmpty();
        assertThat(sessions.resolve(bob, aliceToken)).isEmpty();
    }

    @Test
    void onQuitClearsThatPlayersSession() {
        SnapshotSessions sessions = new SnapshotSessions();
        UUID playerId = UUID.randomUUID();
        Player player = playerWithId(playerId);
        sessions.store(player, session(UUID.randomUUID()));

        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        sessions.onQuit(quit);

        assertThat(sessions.get(player)).isEmpty();
    }

    @Test
    void namedNonPlayerSendersResolveByNameNotIdentity() {
        // A non-player, non-console sender (e.g. RCON) falls back to a
        // name-derived UUID: two distinct CommandSender instances sharing a
        // name must hit the same cache entry.
        SnapshotSessions sessions = new SnapshotSessions();
        CommandSender firstHandle = mock(CommandSender.class);
        when(firstHandle.getName()).thenReturn("Rcon");
        CommandSender secondHandle = mock(CommandSender.class);
        when(secondHandle.getName()).thenReturn("Rcon");
        UUID token = UUID.randomUUID();

        sessions.store(firstHandle, session(token));

        assertThat(sessions.resolve(secondHandle, token)).isPresent();
    }
}

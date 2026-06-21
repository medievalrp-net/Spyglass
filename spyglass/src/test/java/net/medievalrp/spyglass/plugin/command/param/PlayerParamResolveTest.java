package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Locks the default (live-Bukkit) name resolver to a NON-BLOCKING,
 * cache-only path. Spark caught {@code PlayerParam.parse} stalling the
 * main server thread inside {@code Bukkit.getOfflinePlayer(String)} ->
 * {@code HttpURLConnection} (a Mojang profile lookup on a cache miss).
 *
 * <p>Unlike {@link PlayerParamTest}, which injects a deterministic resolver
 * to exercise the predicate-building fallbacks, these tests drive the real
 * {@code resolveViaBukkit} through a {@code mockStatic(Bukkit)} scope so we
 * can assert which Bukkit calls it makes - and, crucially, which it never
 * makes.
 */
class PlayerParamResolveTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void resolverNeverCallsBlockingMojangOverload() throws Exception {
        // The regression: a cache miss must NOT fall through to the blocking
        // String overload. getPlayerExact + getOfflinePlayerIfCached both miss,
        // so the name resolves to nothing and parse() falls back to a verbatim
        // source.playerName match - all without a network round-trip.
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact(anyString())).thenReturn(null);
            bukkit.when(() -> Bukkit.getOfflinePlayerIfCached(anyString())).thenReturn(null);

            QueryPredicate predicate = new PlayerParam().parse("p", "NeverJoined", ctx());

            assertThat(predicate).isInstanceOf(QueryPredicate.Eq.class);
            QueryPredicate.Eq eq = (QueryPredicate.Eq) predicate;
            assertThat(eq.field()).isEqualTo("source.playerName");
            assertThat(eq.value()).isEqualTo("NeverJoined");

            // The whole point of the fix: the deprecated, blocking
            // String overload is never touched.
            bukkit.verify(() -> Bukkit.getOfflinePlayer(anyString()), never());
        }
    }

    @Test
    void onlinePlayerResolvesToPlayerIdWithoutHittingCache() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            Player online = mock(Player.class);
            when(online.getUniqueId()).thenReturn(ALICE);
            bukkit.when(() -> Bukkit.getPlayerExact("Alice")).thenReturn(online);

            QueryPredicate predicate = new PlayerParam().parse("p", "Alice", ctx());

            QueryPredicate.Eq eq = (QueryPredicate.Eq) predicate;
            assertThat(eq.field()).isEqualTo("source.playerId");
            assertThat(eq.value()).isEqualTo(ALICE);

            // Online match short-circuits: neither offline-cache nor the
            // blocking overload is consulted.
            bukkit.verify(() -> Bukkit.getOfflinePlayerIfCached(anyString()), never());
            bukkit.verify(() -> Bukkit.getOfflinePlayer(anyString()), never());
        }
    }

    @Test
    void cachedOfflinePlayerResolvesToPlayerId() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            OfflinePlayer cached = mock(OfflinePlayer.class);
            when(cached.getUniqueId()).thenReturn(ALICE);
            bukkit.when(() -> Bukkit.getPlayerExact("Alice")).thenReturn(null);
            bukkit.when(() -> Bukkit.getOfflinePlayerIfCached("Alice")).thenReturn(cached);

            QueryPredicate predicate = new PlayerParam().parse("p", "Alice", ctx());

            QueryPredicate.Eq eq = (QueryPredicate.Eq) predicate;
            assertThat(eq.field()).isEqualTo("source.playerId");
            assertThat(eq.value()).isEqualTo(ALICE);

            bukkit.verify(() -> Bukkit.getOfflinePlayer(anyString()), never());
        }
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}

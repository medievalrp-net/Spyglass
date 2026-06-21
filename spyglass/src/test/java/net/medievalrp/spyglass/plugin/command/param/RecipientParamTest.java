package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * {@code rcp:} name resolution must be cache-only and non-blocking, the same
 * hard rule PlayerParam follows: never call {@code Bukkit.getOfflinePlayer(String)},
 * which does a blocking Mojang HTTP lookup on the main command thread.
 *
 * <p>Recipients are stored as bare UUIDs with no name column, so a cache miss
 * surfaces as an "Unknown player" error rather than a name fallback.
 */
class RecipientParamTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void onlineRecipientResolvesToEqWithoutBlockingLookup() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            Player online = mock(Player.class);
            when(online.getUniqueId()).thenReturn(ALICE);
            bukkit.when(() -> Bukkit.getPlayerExact("Alice")).thenReturn(online);

            QueryPredicate predicate = new RecipientParam().parse("rcp", "Alice", ctx());

            QueryPredicate.Eq eq = (QueryPredicate.Eq) predicate;
            assertThat(eq.field()).isEqualTo("recipients");
            assertThat(eq.value()).isEqualTo(ALICE);

            bukkit.verify(() -> Bukkit.getOfflinePlayer(anyString()), never());
        }
    }

    @Test
    void cachedRecipientResolvesToEq() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            OfflinePlayer cached = mock(OfflinePlayer.class);
            when(cached.getUniqueId()).thenReturn(ALICE);
            bukkit.when(() -> Bukkit.getPlayerExact("Alice")).thenReturn(null);
            bukkit.when(() -> Bukkit.getOfflinePlayerIfCached("Alice")).thenReturn(cached);

            QueryPredicate predicate = new RecipientParam().parse("rcp", "Alice", ctx());

            QueryPredicate.Eq eq = (QueryPredicate.Eq) predicate;
            assertThat(eq.field()).isEqualTo("recipients");
            assertThat(eq.value()).isEqualTo(ALICE);

            bukkit.verify(() -> Bukkit.getOfflinePlayer(anyString()), never());
        }
    }

    @Test
    void multipleRecipientsResolveToIn() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            Player alice = mock(Player.class);
            Player bob = mock(Player.class);
            when(alice.getUniqueId()).thenReturn(ALICE);
            when(bob.getUniqueId()).thenReturn(BOB);
            bukkit.when(() -> Bukkit.getPlayerExact("Alice")).thenReturn(alice);
            bukkit.when(() -> Bukkit.getPlayerExact("Bob")).thenReturn(bob);

            QueryPredicate predicate = new RecipientParam().parse("rcp", "Alice,Bob", ctx());

            QueryPredicate.In in = (QueryPredicate.In) predicate;
            assertThat(in.field()).isEqualTo("recipients");
            assertThat(in.values()).hasSize(2);
            assertThat(in.values().get(0)).isEqualTo(ALICE);
            assertThat(in.values().get(1)).isEqualTo(BOB);
        }
    }

    @Test
    void cacheMissThrowsUnknownPlayerNeverBlocks() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact(anyString())).thenReturn(null);
            bukkit.when(() -> Bukkit.getOfflinePlayerIfCached(anyString())).thenReturn(null);

            RecipientParam param = new RecipientParam();
            assertThatThrownBy(() -> param.parse("rcp", "NeverJoined", ctx()))
                    .isInstanceOf(ParamParseException.class)
                    .hasMessageContaining("Unknown player");

            bukkit.verify(() -> Bukkit.getOfflinePlayer(anyString()), never());
        }
    }

    @Test
    void blankValueRejected() {
        RecipientParam param = new RecipientParam();
        assertThatThrownBy(() -> param.parse("rcp", "", ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}

package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.junit.jupiter.api.Test;

class IpParamTest {

    private static final UUID PLAYER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PLAYER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void emptyResolverFallsBackToAddressMatch() throws Exception {
        IpParam param = new IpParam(ip -> List.of());
        QueryPredicate predicate = param.parse("ip", "10.0.0.1", ctx());
        assertThat(predicate).isInstanceOf(QueryPredicate.Eq.class);
        QueryPredicate.Eq eq = (QueryPredicate.Eq) predicate;
        assertThat(eq.field()).isEqualTo("address");
        assertThat(eq.value()).isEqualTo("10.0.0.1");
    }

    @Test
    void resolverReturningPlayersProducesOrPredicate() throws Exception {
        IpParam param = new IpParam(ip -> List.of(PLAYER_A, PLAYER_B));
        QueryPredicate predicate = param.parse("ip", "10.0.0.1", ctx());
        assertThat(predicate).isInstanceOf(QueryPredicate.Or.class);
        QueryPredicate.Or or = (QueryPredicate.Or) predicate;
        assertThat(or.predicates()).hasSize(2);
        // First leg: address match for join records.
        assertThat(or.predicates().get(0)).isInstanceOf(QueryPredicate.Eq.class);
        // Second leg: source.playerId IN (resolved UUIDs) for non-join events.
        assertThat(or.predicates().get(1)).isInstanceOf(QueryPredicate.In.class);
        QueryPredicate.In in = (QueryPredicate.In) or.predicates().get(1);
        assertThat(in.field()).isEqualTo("source.playerId");
        // Cast to a typed collection — In.values() returns Collection<?> so
        // the bounded varargs of containsExactlyInAnyOrder can't infer.
        java.util.Set<UUID> resolved = new java.util.HashSet<>();
        for (Object v : in.values()) {
            resolved.add((UUID) v);
        }
        assertThat(resolved).containsExactlyInAnyOrder(PLAYER_A, PLAYER_B);
    }

    @Test
    void resolverThrowingFallsBackToAddressMatch() throws Exception {
        IpParam param = new IpParam(ip -> { throw new RuntimeException("db down"); });
        QueryPredicate predicate = param.parse("ip", "10.0.0.1", ctx());
        assertThat(predicate).isInstanceOf(QueryPredicate.Eq.class);
    }

    @Test
    void trimsWhitespace() throws Exception {
        IpParam param = new IpParam(ip -> List.of());
        QueryPredicate predicate = param.parse("ip", "  10.0.0.1  ", ctx());
        assertThat(((QueryPredicate.Eq) predicate).value()).isEqualTo("10.0.0.1");
    }

    @Test
    void emptyValueRejected() {
        IpParam param = new IpParam(ip -> List.of());
        assertThatThrownBy(() -> param.parse("ip", "", ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void rejectedWithoutIpPermission() {
        IpParam param = new IpParam(ip -> List.of(PLAYER_A));
        org.bukkit.command.CommandSender sender =
                org.mockito.Mockito.mock(org.bukkit.command.CommandSender.class);
        org.mockito.Mockito.when(sender.hasPermission("spyglass.search.ip")).thenReturn(false);

        assertThatThrownBy(() -> param.parse("ip", "10.0.0.1",
                new ParamContext(sender, null, 100)))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("spyglass.search.ip");
    }

    @Test
    void rejectedForNullSender() {
        IpParam param = new IpParam(ip -> List.of());
        assertThatThrownBy(() -> param.parse("ip", "10.0.0.1",
                new ParamContext(null, null, 100)))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("spyglass.search.ip");
    }

    /** Sender holding spyglass.search.ip — the param's happy path. */
    private static ParamContext ctx() {
        org.bukkit.command.CommandSender sender =
                org.mockito.Mockito.mock(org.bukkit.command.CommandSender.class);
        org.mockito.Mockito.when(sender.hasPermission("spyglass.search.ip")).thenReturn(true);
        return new ParamContext(sender, null, 100);
    }
}

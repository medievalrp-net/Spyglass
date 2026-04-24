package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.junit.jupiter.api.Test;

/**
 * Covers the v1-parity gap: when Bukkit can't resolve a name to a UUID,
 * v2 used to throw "Unknown player:" instead of searching the recorded
 * source.playerName. The tests inject a deterministic resolver so the
 * fallback path is exercised without a live Bukkit server.
 */
class PlayerParamTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static PlayerParam withKnownPlayers(Map<String, UUID> known) {
        return new PlayerParam(known::get);
    }

    @Test
    void uuidLiteralBypassesResolution() throws Exception {
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        QueryPredicate predicate = withKnownPlayers(Map.of()).parse("p", id.toString(), ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.Eq.class);
        QueryPredicate.Eq eq = (QueryPredicate.Eq) predicate;
        assertThat(eq.field()).isEqualTo("source.playerId");
        assertThat(eq.value()).isEqualTo(id);
    }

    @Test
    void knownNameResolvesToPlayerIdEq() throws Exception {
        QueryPredicate predicate = withKnownPlayers(Map.of("Alice", ALICE)).parse("p", "Alice", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.Eq.class);
        QueryPredicate.Eq eq = (QueryPredicate.Eq) predicate;
        assertThat(eq.field()).isEqualTo("source.playerId");
        assertThat(eq.value()).isEqualTo(ALICE);
    }

    @Test
    void unresolvableNameFallsBackToPlayerNameMatch() throws Exception {
        // "NeverJoined" is a syntactically valid name that Bukkit has never
        // seen. v2 used to reject with "Unknown player:"; v1 behavior is to
        // match against the persisted source.playerName. This test locks in
        // the fallback.
        QueryPredicate predicate = withKnownPlayers(Map.of()).parse("p", "NeverJoined", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.Eq.class);
        QueryPredicate.Eq eq = (QueryPredicate.Eq) predicate;
        assertThat(eq.field()).isEqualTo("source.playerName");
        assertThat(eq.value()).isEqualTo("NeverJoined");
    }

    @Test
    void multipleUnresolvableNamesUseIn() throws Exception {
        QueryPredicate predicate = withKnownPlayers(Map.of()).parse("p", "Alice,Bob,Charlie", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.In.class);
        QueryPredicate.In in = (QueryPredicate.In) predicate;
        assertThat(in.field()).isEqualTo("source.playerName");
        assertThat(in.values()).hasSize(3);
        assertThat(in.values().get(0)).isEqualTo("Alice");
        assertThat(in.values().get(1)).isEqualTo("Bob");
        assertThat(in.values().get(2)).isEqualTo("Charlie");
    }

    @Test
    void mixedUuidAndNamesProducesOr() throws Exception {
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        QueryPredicate predicate = withKnownPlayers(Map.of()).parse("p", id + ",Ghost", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.Or.class);
        QueryPredicate.Or or = (QueryPredicate.Or) predicate;
        assertThat(or.predicates()).hasSize(2);

        QueryPredicate byId = or.predicates().get(0);
        assertThat(byId).isInstanceOf(QueryPredicate.Eq.class);
        assertThat(((QueryPredicate.Eq) byId).field()).isEqualTo("source.playerId");

        QueryPredicate byName = or.predicates().get(1);
        assertThat(byName).isInstanceOf(QueryPredicate.Eq.class);
        assertThat(((QueryPredicate.Eq) byName).field()).isEqualTo("source.playerName");
        assertThat(((QueryPredicate.Eq) byName).value()).isEqualTo("Ghost");
    }

    @Test
    void blankValueRejected() {
        PlayerParam param = withKnownPlayers(Map.of());
        assertThatThrownBy(() -> param.parse("p", "", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("p", "   ", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("p", ",", ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void almostUuidFallsBackToNameMatch() throws Exception {
        // 35-char string (missing one char) is not a valid UUID literal.
        // Must fall through to the name path rather than throw.
        QueryPredicate predicate = withKnownPlayers(Map.of())
                .parse("p", "550e8400-e29b-41d4-a716-44665544000", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.Eq.class);
        assertThat(((QueryPredicate.Eq) predicate).field()).isEqualTo("source.playerName");
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}

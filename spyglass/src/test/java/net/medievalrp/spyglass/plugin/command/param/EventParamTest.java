package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.junit.jupiter.api.Test;

/**
 * {@link EventParam} unit tests. The handler is injected with the set of
 * enabled event names (from {@code config.events}), which is the surface
 * we test here — disabled events must be rejected, not silently searched
 * against. This gate matters because the Mongo collection can contain
 * records for events an operator has since disabled, and we don't want
 * those to leak through {@code a:} filtering.
 */
class EventParamTest {

    private static final Set<String> ENABLED = Set.of("break", "place", "say", "death", "deposit");

    @Test
    void singleKnownEventProducesEq() throws Exception {
        QueryPredicate predicate = new EventParam(ENABLED).parse("a", "break", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.Eq.class);
        QueryPredicate.Eq eq = (QueryPredicate.Eq) predicate;
        assertThat(eq.field()).isEqualTo("event");
        assertThat(eq.value()).isEqualTo("break");
    }

    @Test
    void mixedCaseInputNormalisedToLowerCase() throws Exception {
        // ENABLED is lowercase (as is every Spyglass event name).
        // a:Break or a:BREAK must still match.
        QueryPredicate predicate = new EventParam(ENABLED).parse("a", "BREAK", ctx());
        assertThat(((QueryPredicate.Eq) predicate).value()).isEqualTo("break");
    }

    @Test
    void multipleEnabledEventsProduceIn() throws Exception {
        QueryPredicate predicate = new EventParam(ENABLED).parse("a", "break,place,say", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.In.class);
        QueryPredicate.In in = (QueryPredicate.In) predicate;
        assertThat(in.field()).isEqualTo("event");
        assertThat(in.values().toArray()).containsExactly("break", "place", "say");
    }

    @Test
    void disabledEventRejected() {
        // "break" is enabled but "insert" is not — the request must fail,
        // not silently drop the unknown entry.
        assertThatThrownBy(() -> new EventParam(ENABLED).parse("a", "break,insert", ctx()))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("Unknown or disabled event: insert");
    }

    @Test
    void unknownEventRejected() {
        assertThatThrownBy(() -> new EventParam(ENABLED).parse("a", "teleport", ctx()))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("teleport");
    }

    @Test
    void emptyCommaEntriesSkipped() throws Exception {
        // a:break,,place should yield a 2-In, not a 3-In with "".
        QueryPredicate predicate = new EventParam(ENABLED).parse("a", "break,,place", ctx());
        assertThat(((QueryPredicate.In) predicate).values().toArray())
                .containsExactly("break", "place");
    }

    @Test
    void onlyEmptyCommasRejected() {
        assertThatThrownBy(() -> new EventParam(ENABLED).parse("a", ",,,", ctx()))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("at least one name");
    }

    @Test
    void blankValueRejected() {
        EventParam param = new EventParam(ENABLED);
        assertThatThrownBy(() -> param.parse("a", "", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("a", "   ", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("a", null, ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void emptyEnabledSetRejectsEverything() {
        EventParam empty = new EventParam(Set.of());
        assertThatThrownBy(() -> empty.parse("a", "break", ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void aliasesCoverAllThreeSynonyms() {
        assertThat(new EventParam(ENABLED).aliases()).containsExactly("a", "action", "event");
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}

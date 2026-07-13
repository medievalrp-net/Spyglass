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
    // As shipped: the rolled-* audit events are enabled by default, so a:break
    // expands to include the rollback's own rolled-break receipts (#330).
    private static final Set<String> ENABLED_WITH_ROLLED = Set.of(
            "break", "place", "deposit", "withdraw", "say",
            "rolled-break", "rolled-place", "rolled-deposit", "rolled-withdraw");

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

    @Test
    void negatedEventProducesNot() throws Exception {
        QueryPredicate predicate = new EventParam(ENABLED).parse("a", "!place", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.Not.class);
        QueryPredicate inner = ((QueryPredicate.Not) predicate).predicate();
        assertThat(((QueryPredicate.Eq) inner).value()).isEqualTo("place");
    }

    @Test
    void mixedIncludeAndExcludeProducesAndWithNot() throws Exception {
        QueryPredicate predicate = new EventParam(ENABLED).parse("a", "break,!place,!say", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.And.class);
        var clauses = ((QueryPredicate.And) predicate).predicates();
        assertThat(clauses).hasSize(2);
        assertThat(((QueryPredicate.Eq) clauses.get(0)).value()).isEqualTo("break");
        QueryPredicate excluded = ((QueryPredicate.Not) clauses.get(1)).predicate();
        assertThat(((QueryPredicate.In) excluded).values().toArray()).containsExactly("place", "say");
    }

    @Test
    void negatedUnknownEventStillRejected() {
        // The exclusion of an unknown event is as much a typo as an
        // inclusion of one — fail loudly either way.
        assertThatThrownBy(() -> new EventParam(ENABLED).parse("a", "!warp", ctx()))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("warp");
    }

    @Test
    void bareExclamationAloneRejected() {
        assertThatThrownBy(() -> new EventParam(ENABLED).parse("a", "!", ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void liveEnabledSetReflectsRuntimeRegistration() throws Exception {
        // EventParam holds the enabled set by reference (not a copy), so a
        // custom event registered at runtime via SpyglassApi#registerEvent
        // (which adds to this same set) makes a:<name> parse without a restart.
        Set<String> live = java.util.concurrent.ConcurrentHashMap.newKeySet();
        live.add("break");
        EventParam param = new EventParam(live);

        assertThatThrownBy(() -> param.parse("a", "voice", ctx()))
                .isInstanceOf(ParamParseException.class);

        live.add("voice"); // simulate registerEvent("voice", ...)

        QueryPredicate predicate = param.parse("a", "voice", ctx());
        assertThat(((QueryPredicate.Eq) predicate).value()).isEqualTo("voice");
    }

    // ---- #330: a:<event> also matches the rollback's rolled-<event> receipts ----

    @Test
    void breakExpandsToIncludeRolledBreakWhenEnabled() throws Exception {
        // The headline case: a:break must surface the rollback that removed a
        // block (a synthesized rolled-break), not just genuine player breaks.
        QueryPredicate predicate = new EventParam(ENABLED_WITH_ROLLED).parse("a", "break", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.In.class);
        assertThat(((QueryPredicate.In) predicate).values().toArray())
                .containsExactly("break", "rolled-break");
    }

    @Test
    void placeDepositWithdrawEachExpandToTheirRolledCounterpart() throws Exception {
        EventParam param = new EventParam(ENABLED_WITH_ROLLED);
        assertThat(((QueryPredicate.In) param.parse("a", "place", ctx())).values().toArray())
                .containsExactly("place", "rolled-place");
        assertThat(((QueryPredicate.In) param.parse("a", "deposit", ctx())).values().toArray())
                .containsExactly("deposit", "rolled-deposit");
        assertThat(((QueryPredicate.In) param.parse("a", "withdraw", ctx())).values().toArray())
                .containsExactly("withdraw", "rolled-withdraw");
    }

    @Test
    void expansionSkippedWhenRolledEventDisabled() throws Exception {
        // ENABLED has no rolled-* events (operator disabled the audit): a:break
        // stays a bare Eq, preserving the old behavior.
        QueryPredicate predicate = new EventParam(ENABLED).parse("a", "break", ctx());
        assertThat(predicate).isInstanceOf(QueryPredicate.Eq.class);
        assertThat(((QueryPredicate.Eq) predicate).value()).isEqualTo("break");
    }

    @Test
    void eventWithoutARolledCounterpartIsNotExpanded() throws Exception {
        // "say" has no rollback shape, so it stays a bare Eq even with the
        // rolled audit enabled.
        QueryPredicate predicate = new EventParam(ENABLED_WITH_ROLLED).parse("a", "say", ctx());
        assertThat(predicate).isInstanceOf(QueryPredicate.Eq.class);
        assertThat(((QueryPredicate.Eq) predicate).value()).isEqualTo("say");
    }

    @Test
    void explicitRolledEventIsNotDoubleExpanded() throws Exception {
        // rolled-break is not itself a base name, so a:rolled-break stays exact.
        QueryPredicate predicate = new EventParam(ENABLED_WITH_ROLLED).parse("a", "rolled-break", ctx());
        assertThat(predicate).isInstanceOf(QueryPredicate.Eq.class);
        assertThat(((QueryPredicate.Eq) predicate).value()).isEqualTo("rolled-break");
    }

    @Test
    void baseAndRolledTypedTogetherDoNotDuplicate() throws Exception {
        // a:break,rolled-break must not yield [break, rolled-break, rolled-break].
        QueryPredicate predicate = new EventParam(ENABLED_WITH_ROLLED)
                .parse("a", "break,rolled-break", ctx());
        assertThat(((QueryPredicate.In) predicate).values().toArray())
                .containsExactly("break", "rolled-break");
    }

    @Test
    void excludingAnEventAlsoExcludesItsRolledCounterpart() throws Exception {
        // a:!break must hide the rollback-caused breaks too, symmetric with
        // the include side.
        QueryPredicate predicate = new EventParam(ENABLED_WITH_ROLLED).parse("a", "!break", ctx());

        QueryPredicate inner = ((QueryPredicate.Not) predicate).predicate();
        assertThat(((QueryPredicate.In) inner).values().toArray())
                .containsExactly("break", "rolled-break");
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}

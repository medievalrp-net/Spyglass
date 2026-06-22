package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.junit.jupiter.api.Test;

/**
 * {@link BeforeParam} unit tests. Mirrors {@link TimeParamTest} style.
 * Wall-clock tests bracket with +/-2s tolerance (no clock seam needed).
 *
 * <p>Key invariants:
 * <ul>
 *   <li>Emits {@code Range("occurred", null, non-null)} - upper bound only</li>
 *   <li>Does NOT set sawTime (verified indirectly: lowerInclusive is null)</li>
 *   <li>Does not suppress default radius</li>
 * </ul>
 */
class BeforeParamTest {

    @Test
    void sixHourBeforeProducesUpperOnlyRange() throws Exception {
        Instant before = Instant.now();
        QueryPredicate predicate = new BeforeParam().parse("before", "6h", ctx());
        Instant after = Instant.now();

        assertThat(predicate).isInstanceOf(QueryPredicate.Range.class);
        QueryPredicate.Range range = (QueryPredicate.Range) predicate;
        assertThat(range.field()).isEqualTo("occurred");

        // Lower bound must be null - before: is an upper bound only
        assertThat(range.lowerInclusive()).isNull();

        // Upper bound is now-minus-6h
        Instant upper = (Instant) range.upperInclusive();
        assertThat(upper).isBetween(
                before.minusSeconds(3600 * 6 + 2),
                after.minusSeconds(3600 * 6 - 2));
    }

    @Test
    void tenMinuteBeforeProducesUpperBound() throws Exception {
        assertUpperBoundAtRoughly("10m", 600);
    }

    @Test
    void oneDayBeforeProducesUpperBound() throws Exception {
        assertUpperBoundAtRoughly("1d", 86_400);
    }

    @Test
    void oneWeekBeforeProducesUpperBound() throws Exception {
        assertUpperBoundAtRoughly("1w", 7 * 86_400);
    }

    @Test
    void compositeDurationAddsSegments() throws Exception {
        // 1d12h = 86400 + 43200 = 129600
        assertUpperBoundAtRoughly("1d12h", 129_600);
    }

    @Test
    void invalidDurationRejected() {
        assertThatThrownBy(() -> new BeforeParam().parse("before", "huge", ctx()))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("Invalid duration");
        assertThatThrownBy(() -> new BeforeParam().parse("before", "", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> new BeforeParam().parse("before", "10x", ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void overflowingDurationRejected() {
        assertThatThrownBy(() -> new BeforeParam().parse("before", "99999999999999w", ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void doesNotSuppressDefaultRadius() {
        assertThat(new BeforeParam().suppressesDefaultRadius("before")).isFalse();
    }

    @Test
    void aliasIsBeforeOnly() {
        assertThat(new BeforeParam().aliases()).containsExactly("before");
    }

    @Test
    void suggestionsReturnTimeHintsWhenInputEmpty() {
        assertThat(new BeforeParam().suggestions(null, "")).isNotEmpty();
    }

    @Test
    void suggestionsReturnEmptyWhenInputNonEmpty() {
        assertThat(new BeforeParam().suggestions(null, "6")).isEmpty();
    }

    private static void assertUpperBoundAtRoughly(String input, long expectedSeconds) throws Exception {
        Instant before = Instant.now();
        QueryPredicate predicate = new BeforeParam().parse("before", input, ctx());
        Instant after = Instant.now();

        QueryPredicate.Range range = (QueryPredicate.Range) predicate;
        assertThat(range.lowerInclusive()).isNull();

        Instant upper = (Instant) range.upperInclusive();
        assertThat(upper).isBetween(
                before.minusSeconds(expectedSeconds + 2),
                after.minusSeconds(expectedSeconds - 2));
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}

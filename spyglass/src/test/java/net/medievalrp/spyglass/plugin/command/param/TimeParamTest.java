package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.junit.jupiter.api.Test;

/**
 * {@link TimeParam} unit tests. Relies on {@code Instant.now()} internally,
 * which we can't stub without adding a clock seam, so wall-clock tests
 * bracket the call with ±2s tolerance. Covers:
 * <ul>
 *   <li>every unit the Duration parser accepts (s/m/h/d/w)</li>
 *   <li>multi-segment durations ({@code 1d12h})</li>
 *   <li>the Range shape (lower = now-duration, upper = null)</li>
 *   <li>overflow rejection (arithmetic error → ParamParseException)</li>
 * </ul>
 */
class TimeParamTest {

    @Test
    void tenMinuteRetentionProducesRangeWithLowerBound() throws Exception {
        Instant before = Instant.now();
        QueryPredicate predicate = new TimeParam().parse("t", "10m", ctx());
        Instant after = Instant.now();

        assertThat(predicate).isInstanceOf(QueryPredicate.Range.class);
        QueryPredicate.Range range = (QueryPredicate.Range) predicate;
        assertThat(range.field()).isEqualTo("occurred");
        assertThat(range.upperInclusive()).isNull();

        Instant lower = (Instant) range.lowerInclusive();
        assertThat(lower).isBetween(
                before.minusSeconds(600 + 2),
                after.minusSeconds(600 - 2));
    }

    @Test
    void oneHourRetention() throws Exception {
        assertLowerBoundAtRoughly("1h", 3600);
    }

    @Test
    void oneDayRetention() throws Exception {
        assertLowerBoundAtRoughly("1d", 86_400);
    }

    @Test
    void oneWeekRetention() throws Exception {
        assertLowerBoundAtRoughly("1w", 7 * 86_400);
    }

    @Test
    void compositeDurationAddsSegments() throws Exception {
        // 1d12h = 86400 + 43200 = 129600
        assertLowerBoundAtRoughly("1d12h", 129_600);
    }

    @Test
    void secondsSupported() throws Exception {
        assertLowerBoundAtRoughly("30s", 30);
    }

    @Test
    void invalidDurationRejected() {
        assertThatThrownBy(() -> new TimeParam().parse("t", "huge", ctx()))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("Invalid duration");
        assertThatThrownBy(() -> new TimeParam().parse("t", "", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> new TimeParam().parse("t", "10x", ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void overflowingDurationRejected() {
        // Duration.parse throws ArithmeticException on Long overflow; the
        // param must translate that to ParamParseException rather than
        // letting it bubble as a 500-equivalent. Long.MAX_VALUE seconds
        // is ~292e9 years; 1w = 604_800s. We pick a number well above
        // Long.MAX_VALUE / 604_800 (~1.52e13) to guarantee
        // Math.multiplyExact overflows.
        assertThatThrownBy(() -> new TimeParam().parse("t", "99999999999999w", ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void doesNotSuppressDefaultRadius() {
        // Time params don't suppress default radius — this is important
        // because the QueryStringParser tracks time suppression
        // separately via the sawTime flag, not via this method.
        assertThat(new TimeParam().suppressesDefaultRadius("t")).isFalse();
        assertThat(new TimeParam().suppressesDefaultRadius("since")).isFalse();
    }

    private static void assertLowerBoundAtRoughly(String input, long expectedSeconds) throws Exception {
        Instant before = Instant.now();
        QueryPredicate predicate = new TimeParam().parse("t", input, ctx());
        Instant after = Instant.now();

        Instant lower = (Instant) ((QueryPredicate.Range) predicate).lowerInclusive();
        assertThat(lower).isBetween(
                before.minusSeconds(expectedSeconds + 2),
                after.minusSeconds(expectedSeconds - 2));
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}

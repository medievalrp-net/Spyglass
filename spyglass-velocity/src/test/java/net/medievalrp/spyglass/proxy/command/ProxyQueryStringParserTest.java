package net.medievalrp.spyglass.proxy.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import org.junit.jupiter.api.Test;

class ProxyQueryStringParserTest {

    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static ProxyQueryStringParser parser(java.util.function.Function<String, List<UUID>> resolver) {
        return new ProxyQueryStringParser(1000, 3600, resolver);
    }

    @Test
    void ipParamRejectedWithoutIpPermission() {
        AtomicBoolean resolverCalled = new AtomicBoolean(false);
        ProxyQueryStringParser parser = parser(ip -> {
            resolverCalled.set(true);
            return List.of(PLAYER);
        });

        assertThatThrownBy(() -> parser.parse("ip:10.0.0.1", false))
                .isInstanceOf(ProxyQueryStringParser.ParseException.class)
                .hasMessageContaining("spyglass.search.ip");
        // The gate must run before the store-backed resolver: a denied
        // source should not trigger the IP→player lookup at all.
        assertThat(resolverCalled).isFalse();
    }

    @Test
    void ipParamResolvesWithIpPermission() throws Exception {
        ProxyQueryStringParser parser = parser(ip -> List.of(PLAYER));

        QueryRequest request = parser.parse("ip:10.0.0.1", true);

        assertThat(request.predicates())
                .anyMatch(p -> p instanceof QueryPredicate.Or
                        || (p instanceof QueryPredicate.Eq eq && "address".equals(eq.field())));
    }

    @Test
    void nonIpQueryUnaffectedByMissingIpPermission() throws Exception {
        ProxyQueryStringParser parser = parser(ip -> List.of());

        QueryRequest request = parser.parse("p:Alice t:1h", false);

        assertThat(request.predicates()).isNotEmpty();
    }

    // ---- before: upper time bound ----

    /**
     * {@code before:6h} emits a Range with null lower and non-null upper.
     * Because before: does NOT set sawTime, the default lower floor is also
     * added, so the result has two predicates total.
     */
    @Test
    void beforeAloneEmitsUpperOnlyRangeAndPreservesDefaultLower() throws Exception {
        ProxyQueryStringParser parser = parser(ip -> List.of());

        Instant testStart = Instant.now();
        QueryRequest request = parser.parse("before:6h", false);

        // Default lower + explicit upper = two Range predicates
        assertThat(request.predicates()).hasSize(2);

        // The before: predicate: null lower, non-null upper
        QueryPredicate.Range upperRange = request.predicates().stream()
                .filter(p -> p instanceof QueryPredicate.Range r && r.upperInclusive() != null)
                .map(p -> (QueryPredicate.Range) p)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected Range with non-null upper from before:"));
        assertThat(upperRange.field()).isEqualTo("occurred");
        assertThat(upperRange.lowerInclusive()).isNull();
        Instant upper = (Instant) upperRange.upperInclusive();
        assertThat(upper).isBefore(testStart);

        // The injected default lower: non-null lower, null upper
        QueryPredicate.Range lowerRange = request.predicates().stream()
                .filter(p -> p instanceof QueryPredicate.Range r && r.lowerInclusive() != null)
                .map(p -> (QueryPredicate.Range) p)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected default Range with non-null lower"));
        assertThat(lowerRange.upperInclusive()).isNull();
    }

    /**
     * {@code t:12h before:6h} - bounded window. The two ranges must together
     * express lower (12h ago) before upper (6h ago).
     */
    @Test
    void tAndBeforeTogetherProduceBoundedWindow() throws Exception {
        ProxyQueryStringParser parser = parser(ip -> List.of());

        Instant testStart = Instant.now();
        QueryRequest request = parser.parse("t:12h before:6h", false);

        // Exactly two predicates: t: lower + before: upper (sawTime=true, no default added)
        assertThat(request.predicates()).hasSize(2);

        QueryPredicate.Range lowerRange = request.predicates().stream()
                .filter(p -> p instanceof QueryPredicate.Range r && r.lowerInclusive() != null)
                .map(p -> (QueryPredicate.Range) p)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected Range with non-null lower from t:"));

        QueryPredicate.Range upperRange = request.predicates().stream()
                .filter(p -> p instanceof QueryPredicate.Range r && r.upperInclusive() != null)
                .map(p -> (QueryPredicate.Range) p)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected Range with non-null upper from before:"));

        Instant lower = (Instant) lowerRange.lowerInclusive();
        Instant upper = (Instant) upperRange.upperInclusive();

        // lower (12h ago) must be before upper (6h ago)
        assertThat(lower).isBefore(upper);
        assertThat(lower).isBefore(testStart);
        assertThat(upper).isBefore(testStart);
    }
}

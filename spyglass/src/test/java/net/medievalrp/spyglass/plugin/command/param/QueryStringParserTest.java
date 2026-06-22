package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import org.junit.jupiter.api.Test;

/**
 * {@link QueryStringParser} tokenizer + end-to-end tests, pinning the
 * quote-aware tokenization that lets multi-word values survive
 * ({@code iname:"flaming sword"}). Before this, the parser split on raw
 * whitespace, so the second word fell through to the default {@code p:}
 * alias - misparsing the value and, when another bare word was present,
 * throwing a confusing {@code Duplicate parameter: p}.
 */
class QueryStringParserTest {

    // ---- tokenizer: the core of the fix ----

    @Test
    void quotedMultiWordValueStaysOneTokenWithQuotesStripped() throws Exception {
        assertThat(QueryStringParser.tokenize("iname:\"flaming sword\""))
                .containsExactly("iname:flaming sword");
    }

    @Test
    void mixedQuotedAndPlainTokensSplitOnUnquotedWhitespaceOnly() throws Exception {
        assertThat(QueryStringParser.tokenize("a:deposit iname:\"flaming sword\" -g"))
                .containsExactly("a:deposit", "iname:flaming sword", "-g");
    }

    @Test
    void plainQueryTokenizesExactlyLikeTheOldWhitespaceSplit() throws Exception {
        assertThat(QueryStringParser.tokenize("a:place b:stone p:Steve"))
                .containsExactly("a:place", "b:stone", "p:Steve");
    }

    @Test
    void runsOfWhitespaceBetweenTokensCollapse() throws Exception {
        assertThat(QueryStringParser.tokenize("a:place    b:stone"))
                .containsExactly("a:place", "b:stone");
    }

    @Test
    void apostropheIsLiteralNotAQuoteDelimiter() throws Exception {
        // Fantasy item names routinely carry an apostrophe (Maker's Blade).
        // An unquoted one must stay a literal char, never open a span.
        assertThat(QueryStringParser.tokenize("iname:Maker's"))
                .containsExactly("iname:Maker's");
    }

    @Test
    void quotedValueMayContainAColon() throws Exception {
        // Only the first colon delimits key:value; an inner colon is data.
        assertThat(QueryStringParser.tokenize("m:\"hello: there\""))
                .containsExactly("m:hello: there");
    }

    @Test
    void unterminatedQuoteIsRejectedWithAClearMessage() {
        assertThatThrownBy(() -> QueryStringParser.tokenize("iname:\"flaming sword"))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("Unterminated quote");
    }

    // ---- end-to-end parse(): the user-facing behaviour ----

    @Test
    void quotedItemNameParsesToOneInamePredicateThatMatchesTheWholeName() throws Exception {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.queryParam("iname")).thenReturn(Optional.of(new ItemNameParam()));

        QueryRequest request = new QueryStringParser(api, configNoDefaults())
                .parse(null, "iname:\"flaming sword\"", 0);

        // Exactly one predicate - the stray "sword" never became a second token.
        assertThat(request.predicates()).hasSize(1);
        QueryPredicate.Or or = (QueryPredicate.Or) request.predicates().get(0);
        Pattern pattern = (Pattern) ((QueryPredicate.Eq) or.predicates().getFirst()).value();
        // The whole phrase reached the handler, so "Flaming Sword" matches.
        assertThat(pattern.matcher("Flaming Sword").find()).isTrue();
        assertThat(pattern.matcher("flaming").find()).isFalse();
    }

    @Test
    void quotedValueAlongsideAnotherParamDoesNotRaiseDuplicateP() throws Exception {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.queryParam("iname")).thenReturn(Optional.of(new ItemNameParam()));
        when(api.queryParam("p")).thenReturn(Optional.of(
                new PlayerParam(name -> new UUID(0, name.hashCode()))));

        QueryRequest request = new QueryStringParser(api, configNoDefaults())
                .parse(null, "p:Steve iname:\"flaming sword\"", 0);

        // Two clean predicates (p + iname); the old whitespace split would have
        // turned "sword" into a second p token and thrown Duplicate parameter: p.
        assertThat(request.predicates()).hasSize(2);
    }

    // ---- #150: ip: pre-resolution off the main thread ----

    @Test
    void extractIpValuesFindsOnlyIpTokens() {
        QueryStringParser parser = new QueryStringParser(mock(SpyglassApi.class), mock(SpyglassConfig.class));
        assertThat(parser.extractIpValues("a:break ip:10.0.0.1 p:Steve")).containsExactly("10.0.0.1");
        assertThat(parser.extractIpValues("ip:1.1.1.1 ip:2.2.2.2")).containsExactly("1.1.1.1", "2.2.2.2");
        assertThat(parser.extractIpValues("a:break p:Steve")).isEmpty();
        assertThat(parser.extractIpValues(null)).isEmpty();
        assertThat(parser.extractIpValues("")).isEmpty();
    }

    @Test
    void parsePassesPreResolvedIpsToIpParamInsteadOfQuerying() throws Exception {
        UUID resolved = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        // Resolver throws: proves parse() used the pre-resolved map, NOT a
        // (blocking) store query, when building the ip: predicate.
        IpParam ipParam = new IpParam(ip -> {
            throw new IllegalStateException("ip: must not query the store on the parse thread");
        });
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.queryParam("ip")).thenReturn(Optional.of(ipParam));
        org.bukkit.command.CommandSender sender = mock(org.bukkit.command.CommandSender.class);
        when(sender.hasPermission("spyglass.search.ip")).thenReturn(true);

        QueryRequest request = new QueryStringParser(api, configNoDefaults())
                .parse(sender, "ip:10.0.0.1", 0, java.util.Map.of("10.0.0.1", java.util.List.of(resolved)));

        assertThat(request.predicates()).hasSize(1);
        QueryPredicate.Or or = (QueryPredicate.Or) request.predicates().get(0);
        QueryPredicate.In in = (QueryPredicate.In) or.predicates().get(1);
        assertThat(in.field()).isEqualTo("source.playerId");
        assertThat(in.values()).hasSize(1);
        assertThat(in.values().iterator().next()).isEqualTo(resolved);
    }

    // ---- before: upper time bound ----

    /**
     * {@code t:12h before:6h} - the two params AND together into a bounded
     * window: lower from t: (12h ago) and upper from before: (6h ago).
     * Both bounds must be non-null and lower must be earlier than upper.
     */
    @Test
    void tAndBeforeTogetherProduceBoundedWindow() throws Exception {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.queryParam("t")).thenReturn(Optional.of(new TimeParam()));
        when(api.queryParam("before")).thenReturn(Optional.of(new BeforeParam()));

        Instant testStart = Instant.now();
        QueryRequest request = new QueryStringParser(api, configNoDefaults())
                .parse(null, "t:12h before:6h", 0);

        // Two explicit predicates (t: and before:); defaults disabled so nothing extra
        assertThat(request.predicates()).hasSize(2);

        QueryPredicate.Range lowerRange = request.predicates().stream()
                .filter(p -> p instanceof QueryPredicate.Range r && r.lowerInclusive() != null)
                .map(p -> (QueryPredicate.Range) p)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a Range with non-null lower"));

        QueryPredicate.Range upperRange = request.predicates().stream()
                .filter(p -> p instanceof QueryPredicate.Range r && r.upperInclusive() != null)
                .map(p -> (QueryPredicate.Range) p)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a Range with non-null upper"));

        Instant lower = (Instant) lowerRange.lowerInclusive();
        Instant upper = (Instant) upperRange.upperInclusive();

        assertThat(lower).isNotNull();
        assertThat(upper).isNotNull();
        // lower (12h ago) must be before upper (6h ago)
        assertThat(lower).isBefore(upper);
        // sanity: both are in the past
        assertThat(lower).isBefore(testStart);
        assertThat(upper).isBefore(testStart);
    }

    /**
     * {@code before:6h} alone - sawTime must NOT be set by BeforeParam, so
     * the default lower floor is applied by the parser (defaults.enabled=true).
     * The result must have a non-null lower bound (the default) in addition
     * to the non-null upper bound from before:.
     */
    @Test
    void beforeAloneStillGetsDefaultLowerBound() throws Exception {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.queryParam("before")).thenReturn(Optional.of(new BeforeParam()));

        // Defaults enabled with a 4h time floor
        SpyglassConfig config = mock(SpyglassConfig.class);
        when(config.defaults()).thenReturn(new SpyglassConfig.Defaults(true, 0, Duration.parse("4h")));
        when(config.limits()).thenReturn(new SpyglassConfig.Limits(100, 50, 0, 0, null, 0));

        QueryRequest request = new QueryStringParser(api, config)
                .parse(null, "before:6h", 0);

        // Two predicates: the explicit before: upper range + the injected default lower range
        assertThat(request.predicates()).hasSize(2);

        // The explicit before: predicate has null lower, non-null upper
        QueryPredicate.Range beforeRange = request.predicates().stream()
                .filter(p -> p instanceof QueryPredicate.Range r && r.upperInclusive() != null)
                .map(p -> (QueryPredicate.Range) p)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected Range with non-null upper from before:"));
        assertThat(beforeRange.lowerInclusive()).isNull();

        // The injected default has non-null lower, null upper
        QueryPredicate.Range defaultRange = request.predicates().stream()
                .filter(p -> p instanceof QueryPredicate.Range r && r.lowerInclusive() != null)
                .map(p -> (QueryPredicate.Range) p)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected default Range with non-null lower"));
        assertThat(defaultRange.upperInclusive()).isNull();
    }

    /**
     * Config with defaults disabled, so {@code parse()} adds no implicit
     * radius/time predicate and the assertions count only the user's params.
     * {@code limits()} is still read unconditionally to build the context.
     */
    private static SpyglassConfig configNoDefaults() {
        SpyglassConfig config = mock(SpyglassConfig.class);
        when(config.defaults()).thenReturn(new SpyglassConfig.Defaults(false, 0, null));
        when(config.limits()).thenReturn(new SpyglassConfig.Limits(100, 50, 0, 0, null, 0));
        return config;
    }
}

package net.medievalrp.spyglass.proxy.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}

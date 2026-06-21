package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.plugin.command.param.IpParam;
import net.medievalrp.spyglass.plugin.command.param.QueryStringParser;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import org.junit.jupiter.api.Test;

/**
 * #150: {@link IpQueryResolver} keeps the blocking {@code ip:} -> player-UUID
 * store lookup OFF the command thread, while leaving the common no-ip path
 * inline (no thread hop).
 */
class IpQueryResolverTest {

    private static QueryStringParser parser() {
        return new QueryStringParser(mock(SpyglassApi.class), mock(SpyglassConfig.class));
    }

    @Test
    void noIpRunsContinuationInlineWithoutAnAsyncHop() {
        IpParam ipParam = new IpParam(ip -> {
            throw new IllegalStateException("resolver must not run when there is no ip:");
        });
        ServiceTestSupport.RecordingSupport support = new ServiceTestSupport.RecordingSupport();
        IpQueryResolver resolver = new IpQueryResolver(parser(), ipParam, support, Logger.getLogger("ip-test"));

        AtomicReference<Map<String, List<UUID>>> got = new AtomicReference<>();
        resolver.resolve("a:break p:Steve", got::set);

        assertThat(support.async).isEmpty();   // never went off-thread
        assertThat(got.get()).isEmpty();       // ran inline with an empty map
    }

    @Test
    void ipResolvesOffThreadThenContinuesOnMain() {
        UUID id = UUID.randomUUID();
        IpParam ipParam = new IpParam(ip -> List.of(id)); // stands in for the store lookup
        ServiceTestSupport.RecordingSupport support = new ServiceTestSupport.RecordingSupport();
        IpQueryResolver resolver = new IpQueryResolver(parser(), ipParam, support, Logger.getLogger("ip-test"));

        AtomicReference<Map<String, List<UUID>>> got = new AtomicReference<>();
        resolver.resolve("ip:10.0.0.1", got::set);

        // Queued on the async pool; the continuation has NOT run on the caller.
        assertThat(support.async).hasSize(1);
        assertThat(got.get()).isNull();

        support.drain();
        assertThat(got.get()).containsEntry("10.0.0.1", List.of(id));
    }
}

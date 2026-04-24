package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.command.PageCache;
import net.medievalrp.spyglass.plugin.command.param.QueryStringParser;
import net.medievalrp.spyglass.plugin.command.render.ResultRenderer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class SearchServiceTest {

    private static BlockBreakRecord record() {
        Instant now = Instant.now();
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air", List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot stone = new BlockSnapshot(
                org.bukkit.Material.STONE, "minecraft:stone", List.of(), List.of(), List.of(), List.of(), null);
        return new BlockBreakRecord(
                UUID.randomUUID(),
                1,
                "break",
                now,
                now.plusSeconds(60),
                Origin.player(),
                Source.player(UUID.randomUUID(), "Alice"),
                new BlockLocation(UUID.randomUUID(), "world", 0, 64, 0),
                "STONE",
                stone,
                air);
    }

    private static final class TestFixture {
        final SpyglassApi api = mock(SpyglassApi.class);
        final QueryStringParser parser = mock(QueryStringParser.class);
        final ResultRenderer renderer = mock(ResultRenderer.class);
        final PageCache pageCache = mock(PageCache.class);
        final CommandSender sender = mock(CommandSender.class);
        final SearchService subject = new SearchService(
                api, parser, renderer, pageCache,
                ServiceSupport.synchronous(), Logger.getLogger("test"));

        TestFixture() {
            when(renderer.renderSingle(any(EventRecord.class), any())).thenReturn(Component.text("rendered"));
            when(renderer.renderAggregation(any())).thenReturn(Component.text("rendered-agg"));
        }
    }

    @Test
    void reportsParamErrors() throws Exception {
        TestFixture fixture = new TestFixture();
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt()))
                .thenThrow(new ParamParseException("bad param"));
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "p:bogus");

        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("bad param"));
        verify(fixture.api, never()).query(any(QueryRequest.class));
    }

    @Test
    void rendersRecordsAndCachesPages() throws Exception {
        TestFixture fixture = new TestFixture();
        QueryRequest request = new QueryRequest(
                List.of(),
                net.medievalrp.spyglass.api.query.Sort.NEWEST_FIRST,
                100,
                java.util.EnumSet.of(net.medievalrp.spyglass.api.query.Flag.NO_GROUP),
                false);
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt()))
                .thenReturn(request);
        QueryResult result = new QueryResult(List.of(record(), record()), List.of());
        when(fixture.api.query(request)).thenReturn(CompletableFuture.completedFuture(result));
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break -ng");

        // Lazy store: the search service passes a record count + a
        // per-index renderer closure, not a pre-materialised component
        // list. Verify the (sender, count=2, renderer) shape.
        verify(fixture.pageCache).store(
                ArgumentMatchers.eq(fixture.sender),
                ArgumentMatchers.eq(2),
                ArgumentMatchers.any(java.util.function.IntFunction.class));
        verify(fixture.pageCache).show(fixture.sender, 1);
        // "Querying records..." goes out first, then cache-store/show drive the page header.
        assertThat(ServiceTestSupport.plainTexts(messages).get(0)).contains("Querying records...");
    }

    @Test
    void warnsOnEmptyResult() throws Exception {
        TestFixture fixture = new TestFixture();
        QueryRequest request = new QueryRequest(
                List.of(),
                net.medievalrp.spyglass.api.query.Sort.NEWEST_FIRST,
                100,
                java.util.EnumSet.of(net.medievalrp.spyglass.api.query.Flag.NO_GROUP),
                false);
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt()))
                .thenReturn(request);
        when(fixture.api.query(request))
                .thenReturn(CompletableFuture.completedFuture(new QueryResult(List.of(), List.of())));
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break -ng");

        verify(fixture.pageCache).clear(fixture.sender);
        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("No results."));
    }

    @Test
    void reportsAsyncFailure() throws Exception {
        TestFixture fixture = new TestFixture();
        QueryRequest request = new QueryRequest(
                List.of(),
                net.medievalrp.spyglass.api.query.Sort.NEWEST_FIRST,
                100,
                java.util.EnumSet.of(net.medievalrp.spyglass.api.query.Flag.NO_GROUP),
                false);
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt()))
                .thenReturn(request);
        CompletableFuture<QueryResult> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("boom"));
        when(fixture.api.query(request)).thenReturn(failed);
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break");

        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("Query failed") && line.contains("boom"));
    }
}

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
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackReason;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.plugin.command.param.QueryStringParser;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.rollback.RollbackEngine;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class RollbackServiceTest {

    private static BlockBreakRecord record() {
        Instant now = Instant.now();
        BlockSnapshot air = new BlockSnapshot(
                Material.AIR, "minecraft:air", List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot stone = new BlockSnapshot(
                Material.STONE, "minecraft:stone", List.of(), List.of(), List.of(), List.of(), null);
        return new BlockBreakRecord(
                UUID.randomUUID(),
                "break",
                now,
                now.plusSeconds(60),
                Origin.player(),
                Source.player(UUID.randomUUID(), "Alice"),
                new net.medievalrp.spyglass.api.util.BlockLocation(UUID.randomUUID(), "world", 0, 64, 0),
                "test",
                "STONE",
                stone,
                air);
    }

    private static final class TestFixture {
        final SpyglassApi api = mock(SpyglassApi.class);
        final QueryStringParser parser = mock(QueryStringParser.class);
        final RollbackEngine engine = mock(RollbackEngine.class);
        final UndoStack undoStack = mock(UndoStack.class);
        final SpyglassConfig config = sampleConfig();
        final CommandSender sender = mock(CommandSender.class);
        final net.medievalrp.spyglass.plugin.pipeline.Recorder recorder =
                mock(net.medievalrp.spyglass.plugin.pipeline.Recorder.class);
        final net.medievalrp.spyglass.plugin.storage.RecordStore store =
                mock(net.medievalrp.spyglass.plugin.storage.RecordStore.class);
        final RollbackService subject;

        TestFixture() {
            when(recorder.flush(any(net.medievalrp.spyglass.api.util.Duration.class)))
                    .thenReturn(true);
            // Empty page → executes the "no results" branch and returns
            // immediately. Tests that need actual results stub this
            // explicitly per-case.
            when(store.queryPage(any(QueryRequest.class), any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(), null));
            RollbackJobQueue queue = new RollbackJobQueue();
            // Test resume store points at a temp dir — no real
            // persistence is needed for these tests, but the
            // constructor demands one.
            RollbackResumeStore resumeStore;
            try {
                resumeStore = new RollbackResumeStore(
                        java.nio.file.Files.createTempDirectory("spyglass-test-"),
                        Logger.getLogger("test"));
            } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
            }
            subject = new RollbackService(
                    api, parser, config, engine, undoStack,
                    ServiceSupport.synchronous(), recorder, store, Logger.getLogger("test"),
                    queue, resumeStore);
            subject.wireQueue();
        }

        private static SpyglassConfig sampleConfig() {
            SpyglassConfig cfg = mock(SpyglassConfig.class);
            SpyglassConfig.Limits limits = mock(SpyglassConfig.Limits.class);
            when(limits.rollbackResult()).thenReturn(10_000);
            when(limits.rollbackPageSize()).thenReturn(5_000);
            when(limits.rollbackUndoCap()).thenReturn(50_000);
            when(limits.rollbackBatchSize()).thenReturn(200);
            when(limits.rollbackFlushTimeout())
                    .thenReturn(new net.medievalrp.spyglass.api.util.Duration(30L));
            when(cfg.limits()).thenReturn(limits);
            return cfg;
        }
    }

    private static QueryRequest sampleRequest() {
        return new QueryRequest(
                List.of(),
                Sort.NEWEST_FIRST,
                10_000,
                java.util.EnumSet.noneOf(Flag.class),
                true);
    }

    @Test
    void reportsParamErrors() throws Exception {
        TestFixture fixture = new TestFixture();
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt()))
                .thenThrow(new ParamParseException("bad param"));
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "p:bogus", RollbackMode.ROLLBACK);

        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("bad param"));
        verify(fixture.api, never()).query(any(QueryRequest.class));
    }

    @Test
    void applysRollbackable(){
        TestFixture fixture = new TestFixture();
        try {
            when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt()))
                    .thenReturn(sampleRequest());
        } catch (ParamParseException unexpected) {
            throw new RuntimeException(unexpected);
        }
        BlockBreakRecord r = record();
        // Streaming path: first call returns the page, second returns empty
        // (terminator), so the loop exits cleanly after one iteration.
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(r), null));
        RollbackEffect inverse = new RollbackEffect.BlockReplace(r.location(), r.originalBlock(), r.newBlock());
        when(fixture.engine.applyAllChunked(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(
                        List.of(new RollbackResult.Applied(r.rollbackEffect(), inverse))));
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break", RollbackMode.ROLLBACK);

        // Summary message matches v1's " N reversals" format when nothing was skipped.
        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("1 reversals") && !line.contains("skipped"));
    }

    @Test
    void warnsWhenNoRollbackables() throws Exception {
        TestFixture fixture = new TestFixture();
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt()))
                .thenReturn(sampleRequest());
        // Default store mock already returns an empty page with null cursor.
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break", RollbackMode.ROLLBACK);

        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("No results."));
    }

    @Test
    void reportsSkippedEffects() throws Exception {
        TestFixture fixture = new TestFixture();
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt()))
                .thenReturn(sampleRequest());
        BlockBreakRecord r = record();
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(r), null));
        when(fixture.engine.applyAllChunked(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(
                        List.of(new RollbackResult.Skipped(r.rollbackEffect(),
                                new RollbackReason.BlockChanged(r.location(), r.originalBlock(), r.newBlock())))));
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break", RollbackMode.ROLLBACK);

        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("0 reversals") && line.contains("1 skipped"));
    }

    /**
     * Recording {@link UndoStack.UndoWriter}: captures append batches
     * and the seal/abandon outcome so tests can assert the streaming
     * capture contract (per-page appends, seal only on success).
     */
    private static final class FakeWriter implements UndoStack.UndoWriter {
        final List<List<RollbackEffect>> batches =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        volatile boolean sealed = false;
        volatile boolean abandoned = false;
        private long appended = 0;

        @Override
        public void append(List<RollbackEffect> effects) {
            batches.add(List.copyOf(effects));
            appended += effects.size();
        }

        @Override
        public long appended() {
            return appended;
        }

        @Override
        public void seal() {
            sealed = true;
        }

        @Override
        public void abandon() {
            if (!sealed) {
                abandoned = true;
            }
        }

        @Override
        public void close() {
            abandon();
        }
    }

    @Test
    void streamsUndoCapturePerPageAndSeals() throws Exception {
        TestFixture fixture = new TestFixture();
        org.bukkit.entity.Player operator = mock(org.bukkit.entity.Player.class);
        when(operator.getUniqueId()).thenReturn(UUID.randomUUID());
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt()))
                .thenReturn(sampleRequest());
        BlockBreakRecord r = record();
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(r), null));
        RollbackEffect inverse = new RollbackEffect.BlockReplace(r.location(), r.originalBlock(), r.newBlock());
        when(fixture.engine.applyAllChunked(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(
                        List.of(new RollbackResult.Applied(r.rollbackEffect(), inverse))));
        FakeWriter writer = new FakeWriter();
        when(fixture.undoStack.beginPush(any(UUID.class), any(String.class))).thenReturn(writer);
        ServiceTestSupport.captureMessages(operator);

        fixture.subject.execute(operator, "a:break", RollbackMode.ROLLBACK);

        // The seal future is awaited on the rollback thread, so by the
        // time execute() returns (synchronous support) capture is final.
        assertThat(writer.sealed).as("ledger sealed on success").isTrue();
        assertThat(writer.abandoned).isFalse();
        assertThat(writer.batches).hasSize(1);
        assertThat(writer.batches.get(0)).containsExactly(inverse);
    }

    @Test
    void abandonsUndoCaptureWhenApplyFails() throws Exception {
        TestFixture fixture = new TestFixture();
        org.bukkit.entity.Player operator = mock(org.bukkit.entity.Player.class);
        when(operator.getUniqueId()).thenReturn(UUID.randomUUID());
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt()))
                .thenReturn(sampleRequest());
        BlockBreakRecord r = record();
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(r), null));
        when(fixture.engine.applyAllChunked(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("apply boom")));
        FakeWriter writer = new FakeWriter();
        when(fixture.undoStack.beginPush(any(UUID.class), any(String.class))).thenReturn(writer);
        ServiceTestSupport.captureMessages(operator);

        fixture.subject.execute(operator, "a:break", RollbackMode.ROLLBACK);

        // The finally block drains the undo executor before returning,
        // so the abandon is visible here.
        assertThat(writer.sealed).isFalse();
        assertThat(writer.abandoned).as("failed rollback abandons the unsealed ledger").isTrue();
    }
}

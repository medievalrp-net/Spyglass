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
        final SpyglassConfig config;
        final CommandSender sender = mock(CommandSender.class);
        final net.medievalrp.spyglass.plugin.pipeline.Recorder recorder =
                mock(net.medievalrp.spyglass.plugin.pipeline.Recorder.class);
        final net.medievalrp.spyglass.plugin.storage.RecordStore store =
                mock(net.medievalrp.spyglass.plugin.storage.RecordStore.class);
        final RollbackService subject;

        TestFixture() {
            this(false);
        }

        TestFixture(boolean rolledAuditSynthesized) {
            config = sampleConfig(rolledAuditSynthesized);
            when(recorder.flush(any(net.medievalrp.spyglass.api.util.Duration.class)))
                    .thenReturn(true);
            // Empty page → executes the "no results" branch and returns
            // immediately. Tests that need actual results stub this
            // explicitly per-case.
            when(store.queryPage(any(QueryRequest.class), any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(), null));
            // The service reads via the streamRollback default method,
            // which delegates to queryPage — run the real default so the
            // per-test queryPage stubs keep driving the pipeline.
            when(store.streamRollback(any(QueryRequest.class), any(),
                    org.mockito.ArgumentMatchers.anyInt(), any()))
                    .thenCallRealMethod();
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
            return sampleConfig(false);
        }

        private static SpyglassConfig sampleConfig(boolean rolledAuditSynthesized) {
            SpyglassConfig cfg = mock(SpyglassConfig.class);
            SpyglassConfig.Limits limits = mock(SpyglassConfig.Limits.class);
            when(limits.rollbackBatchSize()).thenReturn(200);
            when(limits.rollbackFlushTimeout())
                    .thenReturn(new net.medievalrp.spyglass.api.util.Duration(30L));
            when(cfg.limits()).thenReturn(limits);
            SpyglassConfig.Storage storage = mock(SpyglassConfig.Storage.class);
            when(storage.rolledAuditSynthesized()).thenReturn(rolledAuditSynthesized);
            when(storage.retention())
                    .thenReturn(net.medievalrp.spyglass.api.util.Duration.parse("4w"));
            when(cfg.storage()).thenReturn(storage);
            SpyglassConfig.Server server = mock(SpyglassConfig.Server.class);
            when(server.name()).thenReturn("test");
            when(cfg.server()).thenReturn(server);
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

    // #49: resume must replay the marker's RESOLVED request — never
    // re-parse the raw query with the resumer's location/clock context.
    @Test
    void resumeReplaysStoredRequestWithoutReparsing() throws Exception {
        TestFixture fixture = new TestFixture();
        Instant anchor = Instant.parse("2026-06-01T10:00:00Z");
        QueryRequest original = new QueryRequest(
                List.of(new net.medievalrp.spyglass.api.query.QueryPredicate.Range(
                                "occurred", anchor.minusSeconds(3600), null),
                        new net.medievalrp.spyglass.api.query.QueryPredicate.Range(
                                "location.x", 100, 160)),
                Sort.NEWEST_FIRST, 10_000,
                java.util.EnumSet.noneOf(Flag.class), true);
        String encoded = net.medievalrp.spyglass.plugin.storage.UndoReferenceBson
                .encodeBase64(original, "ROLLBACK", anchor);
        UUID cursorId = UUID.randomUUID();
        RollbackResumeStore.Saved saved = new RollbackResumeStore.Saved(
                UUID.randomUUID(), null, "Alice", "p:Griefer r:30",
                RollbackJob.Mode.ROLLBACK, anchor,
                new RollbackResumeStore.Cursor(anchor.minusSeconds(60), cursorId),
                42, 3, encoded, java.nio.file.Path.of("unused.resume"));

        boolean ok = fixture.subject.resumeFromSaved(saved, fixture.sender);

        assertThat(ok).isTrue();
        // The whole point: no re-parse — the parser (which would anchor
        // r:/t: to THIS sender) is never consulted on resume.
        org.mockito.Mockito.verifyNoInteractions(fixture.parser);
        // The job ran (synchronous support) against the stored filter:
        // identical predicates to the original operation's plan, with
        // the saved cursor continuing against that same filter.
        org.mockito.ArgumentCaptor<QueryRequest> sent =
                org.mockito.ArgumentCaptor.forClass(QueryRequest.class);
        org.mockito.ArgumentCaptor<net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor> cursor =
                org.mockito.ArgumentCaptor.forClass(
                        net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor.class);
        verify(fixture.store, org.mockito.Mockito.atLeastOnce())
                .queryPage(sent.capture(), cursor.capture(), anyInt());
        assertThat(sent.getValue().predicates()).isEqualTo(original.predicates());
        assertThat(cursor.getAllValues().get(0).occurred()).isEqualTo(anchor.minusSeconds(60));
        assertThat(cursor.getAllValues().get(0).id()).isEqualTo(cursorId);
    }

    @Test
    void resumeRefusesLegacyMarkerWithoutStoredRequest() {
        TestFixture fixture = new TestFixture();
        RollbackResumeStore.Saved saved = new RollbackResumeStore.Saved(
                UUID.randomUUID(), null, "Alice", "p:Griefer r:30",
                RollbackJob.Mode.ROLLBACK, Instant.now(), null, 0, 0,
                null, java.nio.file.Path.of("unused.resume"));
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        boolean ok = fixture.subject.resumeFromSaved(saved, fixture.sender);

        assertThat(ok).isFalse();
        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("Re-run the original command"));
        verify(fixture.store, never()).queryPage(any(QueryRequest.class), any(), anyInt());
    }

    @Test
    void resumeRefusesCorruptStoredRequest() {
        TestFixture fixture = new TestFixture();
        RollbackResumeStore.Saved saved = new RollbackResumeStore.Saved(
                UUID.randomUUID(), null, "Alice", "p:Griefer",
                RollbackJob.Mode.ROLLBACK, Instant.now(), null, 0, 0,
                "!!!not-a-reference!!!", java.nio.file.Path.of("unused.resume"));
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        boolean ok = fixture.subject.resumeFromSaved(saved, fixture.sender);

        assertThat(ok).isFalse();
        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("unreadable"));
        verify(fixture.store, never()).queryPage(any(QueryRequest.class), any(), anyInt());
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

    // #59: a rollback must request an UNCAPPED parse so the store returns
    // every matching record. The removed limits.rollback-result cap was
    // applied here as the overrideLimit; with sort=NEWEST_FIRST it left a
    // large grief only partially restored (the newest N) while the op
    // marked the whole query rolled back, so the remainder was unreachable.
    @Test
    void rollbackRequestsAnUncappedParse() throws Exception {
        TestFixture fixture = new TestFixture();
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt()))
                .thenReturn(sampleRequest());

        fixture.subject.execute(fixture.sender, "p:Griefer t:1h", RollbackMode.ROLLBACK);

        verify(fixture.parser).parse(
                ArgumentMatchers.eq(fixture.sender),
                ArgumentMatchers.eq("p:Griefer t:1h"),
                ArgumentMatchers.eq(Integer.MAX_VALUE));
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
    void smallApplyWindowSplitsOnePageIntoMultipleApplies() throws Exception {
        TestFixture fixture = new TestFixture();
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt()))
                .thenReturn(sampleRequest());
        BlockBreakRecord a = record();
        BlockBreakRecord b = record();
        BlockBreakRecord c = record();
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(a, b, c), null));
        when(fixture.engine.applyAllChunked(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    List<RollbackEffect> effects = inv.getArgument(0);
                    // One-effect windows: the apply must never see more than
                    // the configured window's worth of effects at once.
                    assertThat(effects).hasSize(1);
                    RollbackEffect.BlockReplace replace = (RollbackEffect.BlockReplace) effects.get(0);
                    RollbackEffect inverse = new RollbackEffect.BlockReplace(
                            replace.location(), replace.replacement(), replace.expectedCurrent());
                    return CompletableFuture.completedFuture(
                            List.<RollbackResult>of(new RollbackResult.Applied(effects.get(0), inverse)));
                });
        fixture.subject.applyWindowForTests(1);
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break", RollbackMode.ROLLBACK);

        // 3 records folded through 1-effect windows = 3 separate applies,
        // summed into one final tally.
        verify(fixture.engine, org.mockito.Mockito.times(3)).applyAllChunked(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.any());
        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("3 reversals") && !line.contains("skipped"));
    }

    @Test
    void internsRepeatedSnapshotsAcrossFoldedEffects() throws Exception {
        TestFixture fixture = new TestFixture();
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt()))
                .thenReturn(sampleRequest());
        // Distinct records, structurally equal snapshots — the fold
        // must collapse the retained copies to one canonical instance
        // per distinct snapshot (the queued-window live set is what
        // MTT=1 promotes to old gen).
        BlockBreakRecord a = record();
        BlockBreakRecord b = record();
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(a, b), null));
        java.util.concurrent.atomic.AtomicReference<List<RollbackEffect>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(fixture.engine.applyAllChunked(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    captured.set(inv.getArgument(0));
                    return CompletableFuture.completedFuture(List.<RollbackResult>of());
                });
        ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break", RollbackMode.ROLLBACK);

        List<RollbackEffect> effects = captured.get();
        assertThat(effects).hasSize(2);
        RollbackEffect.BlockReplace first = (RollbackEffect.BlockReplace) effects.get(0);
        RollbackEffect.BlockReplace second = (RollbackEffect.BlockReplace) effects.get(1);
        assertThat(first.expectedCurrent()).isSameAs(second.expectedCurrent());
        assertThat(first.replacement()).isSameAs(second.replacement());
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

    @Test
    void synthesizedModeEmitsOneDecodableOpRecord() throws Exception {
        TestFixture fixture = new TestFixture(true);
        org.bukkit.entity.Player operator = mock(org.bukkit.entity.Player.class);
        when(operator.getUniqueId()).thenReturn(UUID.randomUUID());
        when(operator.getName()).thenReturn("Operator");
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
        ServiceTestSupport.captureMessages(operator);

        fixture.subject.execute(operator, "a:break", RollbackMode.ROLLBACK);

        org.mockito.ArgumentCaptor<net.medievalrp.spyglass.api.event.EventRecord> emitted =
                org.mockito.ArgumentCaptor.forClass(net.medievalrp.spyglass.api.event.EventRecord.class);
        verify(fixture.recorder).record(emitted.capture());
        assertThat(emitted.getValue())
                .isInstanceOf(net.medievalrp.spyglass.api.event.RollbackOpRecord.class);
        var op = (net.medievalrp.spyglass.api.event.RollbackOpRecord) emitted.getValue();
        assertThat(op.event()).isEqualTo("rollback-op");
        assertThat(op.mode()).isEqualTo("ROLLBACK");
        var decoded = net.medievalrp.spyglass.plugin.storage.UndoReferenceBson
                .decodeBase64(op.reference());
        assertThat(decoded.applied()).isEqualTo(1);
        assertThat(decoded.boxes()).hasSize(1);
        assertThat(decoded.boxes().get(0).minX()).isEqualTo(r.location().x());
    }

    @Test
    void receiptsModeEmitsNoOpRecord() throws Exception {
        TestFixture fixture = new TestFixture(false);
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
        ServiceTestSupport.captureMessages(operator);

        fixture.subject.execute(operator, "a:break", RollbackMode.ROLLBACK);

        verify(fixture.recorder, org.mockito.Mockito.never())
                .record(any(net.medievalrp.spyglass.api.event.EventRecord.class));
    }

    @Test
    void replayOpsNeverPushAnUndoReference() throws Exception {
        // An undo replay consumes the popped reference; pushing a new one
        // would make repeated /undo ping-pong on the newest op instead of
        // unwinding the stack (#31).
        TestFixture fixture = new TestFixture();
        org.bukkit.entity.Player operator = mock(org.bukkit.entity.Player.class);
        when(operator.getUniqueId()).thenReturn(UUID.randomUUID());
        when(operator.getName()).thenReturn("Operator");
        BlockBreakRecord r = record();
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(r), null));
        RollbackEffect inverse = new RollbackEffect.BlockReplace(r.location(), r.originalBlock(), r.newBlock());
        when(fixture.engine.applyAllChunked(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(
                        List.of(new RollbackResult.Applied(r.rollbackEffect(), inverse))));
        ServiceTestSupport.captureMessages(operator);
        java.util.concurrent.atomic.AtomicBoolean consumed = new java.util.concurrent.atomic.AtomicBoolean();

        fixture.subject.executeReplay(operator, sampleRequest(), RollbackMode.RESTORE,
                "undo of abc123", () -> consumed.set(true));

        assertThat(consumed).as("clean replay completion fires onDone").isTrue();
        verify(fixture.undoStack, org.mockito.Mockito.never())
                .pushReference(any(), any(), any());
    }

    @Test
    void pushesDecodableUndoReferenceOnSuccess() throws Exception {
        TestFixture fixture = new TestFixture();
        org.bukkit.entity.Player operator = mock(org.bukkit.entity.Player.class);
        UUID operatorId = UUID.randomUUID();
        when(operator.getUniqueId()).thenReturn(operatorId);
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
        ServiceTestSupport.captureMessages(operator);

        fixture.subject.execute(operator, "a:break", RollbackMode.ROLLBACK);

        org.mockito.ArgumentCaptor<String> blob = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(fixture.undoStack).pushReference(
                org.mockito.ArgumentMatchers.eq(operatorId),
                org.mockito.ArgumentMatchers.eq("ROLLBACK"),
                blob.capture());
        // The blob must replay-resolve: same limit, ROLLBACK mode, and
        // a ceiling no later than now.
        var decoded = net.medievalrp.spyglass.plugin.storage.UndoReferenceBson
                .decodeBase64(blob.getValue());
        assertThat(decoded.mode()).isEqualTo("ROLLBACK");
        assertThat(decoded.request().limit()).isEqualTo(sampleRequest().limit());
        assertThat(decoded.ceiling()).isBeforeOrEqualTo(java.time.Instant.now());
    }

    @Test
    void noUndoReferenceWhenApplyFails() throws Exception {
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
        ServiceTestSupport.captureMessages(operator);

        fixture.subject.execute(operator, "a:break", RollbackMode.ROLLBACK);

        verify(fixture.undoStack, org.mockito.Mockito.never()).pushReference(
                any(UUID.class), any(String.class), any(String.class));
    }

    @Test
    void noUndoReferenceForConsoleSenders() throws Exception {
        TestFixture fixture = new TestFixture();
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
        ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break", RollbackMode.ROLLBACK);

        verify(fixture.undoStack, org.mockito.Mockito.never()).pushReference(
                any(UUID.class), any(String.class), any(String.class));
    }
}

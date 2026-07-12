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
import net.medievalrp.spyglass.plugin.rollback.BlockColumns;
import net.medievalrp.spyglass.plugin.rollback.RollbackEngine;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class RollbackServiceTest {

    // Fixed world so records fold into one per-world BlockColumns (#67);
    // the columnar accumulator keys columns by world id.
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static BlockBreakRecord record() {
        return recordAt(0, Material.STONE, "minecraft:stone");
    }

    // Break of `beforeMat` at (x, 64, 0): its rollback effect writes the
    // broken block back and expects the air the break left behind.
    private static BlockBreakRecord recordAt(int x, Material beforeMat, String beforeData) {
        Instant now = Instant.now();
        BlockSnapshot air = new BlockSnapshot(
                Material.AIR, "minecraft:air", List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot before = new BlockSnapshot(
                beforeMat, beforeData, List.of(), List.of(), List.of(), List.of(), null);
        return new BlockBreakRecord(
                UUID.randomUUID(),
                "break",
                now,
                now.plusSeconds(60),
                Origin.player(),
                Source.player(UUID.randomUUID(), "Alice"),
                new net.medievalrp.spyglass.api.util.BlockLocation(WORLD, "world", x, 64, 0),
                "test",
                beforeMat.name(),
                before,
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
            config = sampleConfig();
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
            // #67: the rollback path now reads via streamRollbackEffects.
            // Its default delegates to streamRollback (above) → queryPage
            // and resolves each record to its RollbackEffect exactly as a
            // lean backend would, so the per-test queryPage stubs keep
            // driving the pipeline. Run the real default.
            when(store.streamRollbackEffects(any(QueryRequest.class), any(),
                    org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyBoolean(), any()))
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
            IpQueryResolver ipResolver = new IpQueryResolver(
                    parser, new net.medievalrp.spyglass.plugin.command.param.IpParam(ip -> java.util.List.of()),
                    ServiceSupport.synchronous(), Logger.getLogger("test"));
            subject = new RollbackService(
                    api, parser, config, engine, undoStack,
                    ServiceSupport.synchronous(), recorder, store, Logger.getLogger("test"),
                    queue, resumeStore, ipResolver);
            subject.wireQueue();
        }

        private static SpyglassConfig sampleConfig() {
            SpyglassConfig cfg = mock(SpyglassConfig.class);
            SpyglassConfig.Limits limits = mock(SpyglassConfig.Limits.class);
            when(limits.rollbackBatchSize()).thenReturn(200);
            when(limits.rollbackFlushTimeout())
                    .thenReturn(new net.medievalrp.spyglass.api.util.Duration(30L));
            when(cfg.limits()).thenReturn(limits);
            SpyglassConfig.Storage storage = mock(SpyglassConfig.Storage.class);
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

    // Simple block-replaces (the test records) take the columnar engine
    // path (#67): stub it to report every folded cell as applied.
    private static void stubColumnarApplied(TestFixture fixture) {
        when(fixture.engine.applyColumnsChunked(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    BlockColumns cols = inv.getArgument(1);
                    RollbackEngine.ApplyCounts counts = new RollbackEngine.ApplyCounts();
                    counts.applied = cols.count();
                    return CompletableFuture.completedFuture(counts);
                });
    }

    // Stub the columnar path to skip every folded cell as "block changed".
    private static void stubColumnarBlockChanged(TestFixture fixture) {
        when(fixture.engine.applyColumnsChunked(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    BlockColumns cols = inv.getArgument(1);
                    RollbackEngine.ApplyCounts counts = new RollbackEngine.ApplyCounts();
                    counts.blockChanged = cols.count();
                    return CompletableFuture.completedFuture(counts);
                });
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
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt(), any()))
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
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt(), any()))
                .thenReturn(sampleRequest());

        fixture.subject.execute(fixture.sender, "p:Griefer t:1h", RollbackMode.ROLLBACK);

        verify(fixture.parser).parse(
                ArgumentMatchers.eq(fixture.sender),
                ArgumentMatchers.eq("p:Griefer t:1h"),
                ArgumentMatchers.eq(Integer.MAX_VALUE),
                ArgumentMatchers.any());
    }

    @Test
    void applysRollbackable(){
        TestFixture fixture = new TestFixture();
        try {
            when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt(), any()))
                    .thenReturn(sampleRequest());
        } catch (ParamParseException unexpected) {
            throw new RuntimeException(unexpected);
        }
        BlockBreakRecord r = record();
        // Streaming path: first call returns the page, second returns empty
        // (terminator), so the loop exits cleanly after one iteration.
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(r), null));
        stubColumnarApplied(fixture);
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break", RollbackMode.ROLLBACK);

        // Summary message matches v1's " N reversals" format when nothing was skipped.
        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("1 reversals") && !line.contains("skipped"));
    }

    @Test
    void smallApplyWindowSplitsOnePageIntoMultipleApplies() throws Exception {
        TestFixture fixture = new TestFixture();
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt(), any()))
                .thenReturn(sampleRequest());
        BlockBreakRecord a = record();
        BlockBreakRecord b = record();
        BlockBreakRecord c = record();
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(a, b, c), null));
        when(fixture.engine.applyColumnsChunked(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    BlockColumns cols = inv.getArgument(1);
                    // One-effect windows: each apply sees exactly one cell.
                    assertThat(cols.count()).isEqualTo(1);
                    RollbackEngine.ApplyCounts counts = new RollbackEngine.ApplyCounts();
                    counts.applied = cols.count();
                    return CompletableFuture.completedFuture(counts);
                });
        fixture.subject.applyWindowForTests(1);
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break", RollbackMode.ROLLBACK);

        // 3 records folded through 1-effect windows = 3 separate applies,
        // summed into one final tally.
        verify(fixture.engine, org.mockito.Mockito.times(3)).applyColumnsChunked(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyInt(), ArgumentMatchers.any());
        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("3 reversals") && !line.contains("skipped"));
    }

    @Test
    void foldsRepeatedBlockDataIntoDedupedPalette() throws Exception {
        TestFixture fixture = new TestFixture();
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt(), any()))
                .thenReturn(sampleRequest());
        // Two events at the SAME cell: the palette dedupes the repeated
        // block-data (#67) and the cell fold collapses the rows to one
        // net write (#321), so the summary counts the cell once.
        BlockBreakRecord a = record();
        BlockBreakRecord b = record();
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(a, b), null));
        java.util.concurrent.atomic.AtomicReference<BlockColumns> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(fixture.engine.applyColumnsChunked(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    BlockColumns cols = inv.getArgument(1);
                    captured.set(cols);
                    RollbackEngine.ApplyCounts counts = new RollbackEngine.ApplyCounts();
                    counts.applied = cols.count();
                    return CompletableFuture.completedFuture(counts);
                });
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break", RollbackMode.ROLLBACK);

        BlockColumns cols = captured.get();
        assertThat(cols).isNotNull();
        assertThat(cols.count()).isEqualTo(1);
        // Stone + air are still the only palette entries.
        assertThat(cols.paletteSize()).isEqualTo(2);
        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("1 reversals") && !line.contains("skipped"));
    }

    @Test
    void multiEventCellAppliesOneNetWrite() throws Exception {
        TestFixture fixture = new TestFixture();
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt(), any()))
                .thenReturn(sampleRequest());
        // Three logged changes at ONE cell, streamed newest-first (#321):
        // per-event replay would write planks last, so the coalesced row
        // must carry the LAST streamed write - the oldest event's block.
        BlockBreakRecord newest = recordAt(0, Material.STONE, "minecraft:stone");
        BlockBreakRecord middle = recordAt(0, Material.DIRT, "minecraft:dirt");
        BlockBreakRecord oldest = recordAt(0, Material.OAK_PLANKS, "minecraft:oak_planks");
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(
                        List.of(newest, middle, oldest), null));
        java.util.concurrent.atomic.AtomicReference<BlockColumns> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(fixture.engine.applyColumnsChunked(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    BlockColumns cols = inv.getArgument(1);
                    captured.set(cols);
                    RollbackEngine.ApplyCounts counts = new RollbackEngine.ApplyCounts();
                    counts.applied = cols.count();
                    return CompletableFuture.completedFuture(counts);
                });
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break", RollbackMode.ROLLBACK);

        BlockColumns cols = captured.get();
        assertThat(cols).isNotNull();
        assertThat(cols.count()).isEqualTo(1);
        assertThat(cols.replData(0)).isEqualTo("minecraft:oak_planks");
        // First-streamed (newest) event's expected-current is kept.
        assertThat(cols.expData(0)).isEqualTo("minecraft:air");
        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("1 reversals") && !line.contains("skipped"));
    }

    @Test
    void distinctCellsDoNotCoalesce() throws Exception {
        TestFixture fixture = new TestFixture();
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt(), any()))
                .thenReturn(sampleRequest());
        BlockBreakRecord a = recordAt(0, Material.STONE, "minecraft:stone");
        BlockBreakRecord b = recordAt(1, Material.STONE, "minecraft:stone");
        BlockBreakRecord c = recordAt(2, Material.STONE, "minecraft:stone");
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(a, b, c), null));
        stubColumnarApplied(fixture);
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break", RollbackMode.ROLLBACK);

        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("3 reversals") && !line.contains("skipped"));
    }

    @Test
    void warnsWhenNoRollbackables() throws Exception {
        TestFixture fixture = new TestFixture();
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt(), any()))
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
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt(), any()))
                .thenReturn(sampleRequest());
        BlockBreakRecord r = record();
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(r), null));
        stubColumnarBlockChanged(fixture);
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break", RollbackMode.ROLLBACK);

        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("0 reversals") && line.contains("1 skipped"));
    }

    @Test
    void completedRollbackEmitsOneDecodableOpRecord() throws Exception {
        TestFixture fixture = new TestFixture();
        org.bukkit.entity.Player operator = mock(org.bukkit.entity.Player.class);
        when(operator.getUniqueId()).thenReturn(UUID.randomUUID());
        when(operator.getName()).thenReturn("Operator");
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt(), any()))
                .thenReturn(sampleRequest());
        BlockBreakRecord r = record();
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(r), null));
        stubColumnarApplied(fixture);
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
    void rollbackThatAppliesNothingEmitsNoOpRecord() throws Exception {
        // The op record is what searches synthesize rolled-* entries from;
        // a run that changed nothing must not seed a phantom audit trail.
        TestFixture fixture = new TestFixture();
        org.bukkit.entity.Player operator = mock(org.bukkit.entity.Player.class);
        when(operator.getUniqueId()).thenReturn(UUID.randomUUID());
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt(), any()))
                .thenReturn(sampleRequest());
        BlockBreakRecord r = record();
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(r), null));
        stubColumnarBlockChanged(fixture);
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
        stubColumnarApplied(fixture);
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
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt(), any()))
                .thenReturn(sampleRequest());
        BlockBreakRecord r = record();
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(r), null));
        stubColumnarApplied(fixture);
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
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt(), any()))
                .thenReturn(sampleRequest());
        BlockBreakRecord r = record();
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(r), null));
        when(fixture.engine.applyColumnsChunked(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("apply boom")));
        ServiceTestSupport.captureMessages(operator);

        fixture.subject.execute(operator, "a:break", RollbackMode.ROLLBACK);

        verify(fixture.undoStack, org.mockito.Mockito.never()).pushReference(
                any(UUID.class), any(String.class), any(String.class));
    }

    @Test
    void noUndoReferenceForConsoleSenders() throws Exception {
        TestFixture fixture = new TestFixture();
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt(), any()))
                .thenReturn(sampleRequest());
        BlockBreakRecord r = record();
        when(fixture.store.queryPage(any(QueryRequest.class), any(), anyInt()))
                .thenReturn(new net.medievalrp.spyglass.plugin.storage.QueryPage(List.of(r), null));
        stubColumnarApplied(fixture);
        ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break", RollbackMode.ROLLBACK);

        verify(fixture.undoStack, org.mockito.Mockito.never()).pushReference(
                any(UUID.class), any(String.class), any(String.class));
    }

    // #204: when the pre-rollback flush times out, the partial-restore
    // condition must reach the SERVER LOG (it was sender-only before, leaving
    // no trace), and the operator message must say how much is still draining.
    @Test
    void logsAndTellsOperatorWhenRecorderStillDrainingAtRollback() throws Exception {
        TestFixture fixture = new TestFixture();
        when(fixture.parser.parse(any(CommandSender.class), any(String.class), anyInt(), any()))
                .thenReturn(sampleRequest());
        // Flush times out; the recorder reports a spill backlog with a finite
        // drain rate, so an ETA is included (50000 / 500 = 100s).
        when(fixture.recorder.flush(any(net.medievalrp.spyglass.api.util.Duration.class)))
                .thenReturn(false);
        when(fixture.recorder.spillSnapshot()).thenReturn(
                new net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder.SpillSnapshot(
                        true, 3L, 50_000L, 1_000_000L, 500L));

        java.util.List<java.util.logging.LogRecord> logged = new java.util.ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord rec) {
                logged.add(rec);
            }
            @Override public void flush() {
            }
            @Override public void close() {
            }
        };
        Logger testLogger = Logger.getLogger("test");
        testLogger.addHandler(handler);
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);
        try {
            fixture.subject.execute(fixture.sender, "a:break", RollbackMode.ROLLBACK);
        } finally {
            testLogger.removeHandler(handler);
        }

        // The core of #204: a WARNING now records that a rollback ran on
        // incomplete data, with the job context and backlog count.
        assertThat(logged).anyMatch(rec ->
                rec.getLevel() == java.util.logging.Level.WARNING
                        && rec.getMessage().contains("before the recorder drained")
                        && rec.getMessage().contains("50000"));
        // The operator is told the count and the ETA, not just "still draining".
        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("50000") && line.contains("100s"));
    }

    // #204: the drain-detail phrase reports the backlog count and a rate-based
    // ETA, drops the ETA when the drain is unlimited, and degrades cleanly when
    // nothing is spilled (queue-only / slow-store case).
    @Test
    void drainDetailReportsBacklogCountAndEta() {
        var withRate = new net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder.SpillSnapshot(
                true, 2L, 10_000L, 500_000L, 500L);
        assertThat(RollbackService.drainDetail(withRate))
                .contains("10000").contains("20s").contains("500 rec/s");

        var unlimited = new net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder.SpillSnapshot(
                true, 2L, 10_000L, 500_000L, 0L);
        assertThat(RollbackService.drainDetail(unlimited))
                .contains("10000").doesNotContain("to clear");

        var noBacklog = new net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder.SpillSnapshot(
                false, 0L, 0L, 0L, 0L);
        assertThat(RollbackService.drainDetail(noBacklog))
                .isEqualTo("the store has not caught up to the newest events");
    }
}

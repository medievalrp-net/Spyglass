package net.medievalrp.omniscience2.plugin.command.service;

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
import net.medievalrp.omniscience2.api.Omniscience2Api;
import net.medievalrp.omniscience2.api.event.BlockBreakRecord;
import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.event.EventRecord;
import net.medievalrp.omniscience2.api.event.Origin;
import net.medievalrp.omniscience2.api.event.Source;
import net.medievalrp.omniscience2.api.param.ParamParseException;
import net.medievalrp.omniscience2.api.query.Flag;
import net.medievalrp.omniscience2.api.query.QueryRequest;
import net.medievalrp.omniscience2.api.query.QueryResult;
import net.medievalrp.omniscience2.api.query.Sort;
import net.medievalrp.omniscience2.api.rollback.RollbackEffect;
import net.medievalrp.omniscience2.api.rollback.RollbackReason;
import net.medievalrp.omniscience2.api.rollback.RollbackResult;
import net.medievalrp.omniscience2.plugin.command.param.QueryStringParser;
import net.medievalrp.omniscience2.plugin.config.Omniscience2Config;
import net.medievalrp.omniscience2.plugin.rollback.RollbackEngine;
import net.medievalrp.omniscience2.plugin.rollback.UndoStack;
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
                1,
                "break",
                now,
                now.plusSeconds(60),
                Origin.player(),
                Source.player(UUID.randomUUID(), "Alice"),
                new net.medievalrp.omniscience2.api.util.BlockLocation(UUID.randomUUID(), "world", 0, 64, 0),
                "STONE",
                stone,
                air);
    }

    private static final class TestFixture {
        final Omniscience2Api api = mock(Omniscience2Api.class);
        final QueryStringParser parser = mock(QueryStringParser.class);
        final RollbackEngine engine = mock(RollbackEngine.class);
        final UndoStack undoStack = mock(UndoStack.class);
        final Omniscience2Config config = sampleConfig();
        final CommandSender sender = mock(CommandSender.class);
        final RollbackService subject = new RollbackService(
                api, parser, config, engine, undoStack,
                ServiceSupport.synchronous(), Logger.getLogger("test"));

        private static Omniscience2Config sampleConfig() {
            Omniscience2Config cfg = mock(Omniscience2Config.class);
            Omniscience2Config.Limits limits = mock(Omniscience2Config.Limits.class);
            when(limits.rollbackResult()).thenReturn(10_000);
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
        when(fixture.api.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new QueryResult(List.of(r), List.of())));
        RollbackEffect inverse = new RollbackEffect.BlockReplace(r.location(), r.originalBlock(), r.newBlock());
        when(fixture.engine.applyAll(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of(new RollbackResult.Applied(r.rollbackEffect(), inverse)));
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
        when(fixture.api.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new QueryResult(List.of(), List.of())));
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
        when(fixture.api.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new QueryResult(List.of(r), List.of())));
        when(fixture.engine.applyAll(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of(new RollbackResult.Skipped(r.rollbackEffect(),
                        new RollbackReason.BlockChanged(r.location(), r.originalBlock(), r.newBlock()))));
        List<Component> messages = ServiceTestSupport.captureMessages(fixture.sender);

        fixture.subject.execute(fixture.sender, "a:break", RollbackMode.ROLLBACK);

        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("0 reversals") && line.contains("1 skipped"));
    }
}

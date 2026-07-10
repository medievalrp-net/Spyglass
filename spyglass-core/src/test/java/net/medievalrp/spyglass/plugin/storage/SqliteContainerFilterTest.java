package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #263 on the real SQLite store: the container-aware b: predicate cannot
 * push down (containerType lives in the blob), so it must land in the
 * residual filter on the query path AND on the rollback stream - the
 * stream previously threw on any unpushable predicate, which would have
 * made a b: rollback fail outright on SQLite.
 */
class SqliteContainerFilterTest {

    @TempDir
    Path dir;
    private SqliteRecordStore store;

    @BeforeEach
    void open() {
        store = new SqliteRecordStore(dir.resolve("spyglass.db"));
        store.save(List.of(
                ContainerAwareBlockFilterTest.deposit("DIAMOND", "CHEST", 1),
                ContainerAwareBlockFilterTest.deposit("CHEST", "BARREL", 2),
                ContainerAwareBlockFilterTest.place(Material.CHEST, 3),
                ContainerAwareBlockFilterTest.place(Material.DIRT, 4)));
    }

    @AfterEach
    void close() {
        store.close();
    }

    private static QueryRequest request(QueryPredicate predicate) {
        return new QueryRequest(List.of(predicate), Sort.NEWEST_FIRST, 100,
                EnumSet.of(Flag.NO_GROUP), false);
    }

    private List<Integer> xsOf(List<EventRecord> records) {
        return records.stream().map(r -> r.location().x()).sorted().toList();
    }

    @Test
    void queryPathResolvesTheResidualContainerFilter() {
        List<EventRecord> chest = store.query(
                request(ContainerAwareBlockFilterTest.blockShape("CHEST"))).records();
        assertThat(xsOf(chest))
                .as("b:chest = the deposit into the chest + the placed chest block")
                .containsExactly(1, 3);

        List<EventRecord> notChest = store.query(
                request(new QueryPredicate.Not(ContainerAwareBlockFilterTest.blockShape("CHEST")))).records();
        assertThat(xsOf(notChest))
                .as("b:!chest = the barrel transaction + the plain dirt block")
                .containsExactly(2, 4);
    }

    @Test
    void rollbackStreamFallsBackToDecodedScanInsteadOfThrowing() {
        List<Integer> emittedXs = new ArrayList<>();
        RecordStore.RollbackEffectSink sink = new RecordStore.RollbackEffectSink() {
            @Override
            public void block(UUID world, int x, int y, int z, String blockData,
                              String expectedCurrent, Instant occurred, UUID id) {
                emittedXs.add(x);
            }

            @Override
            public void complex(RollbackEffect effect, Instant occurred, UUID id) {
                emittedXs.add(switch (effect) {
                    case RollbackEffect.BlockReplace br -> br.location().x();
                    case RollbackEffect.ContainerSlotWrite csw -> csw.location().x();
                    case RollbackEffect.EntitySpawn es -> es.location().x();
                    case RollbackEffect.EntityRemove er -> er.location().x();
                    case RollbackEffect.Custom c -> c.location() == null ? -999 : c.location().x();
                });
            }

            @Override
            public void skip(Instant occurred, UUID id) {
            }
        };

        store.streamRollbackEffects(
                request(new QueryPredicate.Not(ContainerAwareBlockFilterTest.blockShape("CHEST"))),
                null, 1000, true, sink);

        assertThat(emittedXs.stream().sorted().toList())
                .as("the b:!chest rollback touches ONLY the barrel deposit and the dirt block; "
                        + "the chest's deposit and the chest block never reach the sink")
                .containsExactly(2, 4);
    }
}

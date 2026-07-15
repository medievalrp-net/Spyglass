package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MongoDBContainer;

/**
 * #284 on the two stores that MIRROR the record-to-effect logic instead of
 * calling it (their emitEffect lockstep comments): an environment death
 * must stream as a skip, a player kill as an EntitySpawn - same rule as
 * EntityDeathRecord.resurrectable().
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeathResurrectionPushdownIT {

    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private ClickHouseContainer clickhouse;
    private ClickHouseRecordStore chStore;
    private MongoDBContainer mongo;
    private MongoRecordStore mongoStore;

    @BeforeAll
    void setup() throws Exception {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker not available")
                .isTrue();
        clickhouse = new ClickHouseContainer("clickhouse/clickhouse-server:24.8-alpine");
        clickhouse.start();
        chStore = new ClickHouseRecordStore(
                clickhouse.getHost(), clickhouse.getMappedPort(8123),
                "death_stream", "event_records",
                clickhouse.getUsername(), clickhouse.getPassword(), false);
        mongo = new MongoDBContainer("mongo:7.0");
        mongo.start();
        mongoStore = new MongoRecordStore(mongo.getReplicaSetUrl(), "IT", "EventRecords",
                new IndexManager());

        List<net.medievalrp.spyglass.api.event.EventRecord> fixtures = List.of(
                death("zombie", "FIRE_TICK", 1),
                death("sheep", "player", 2));
        chStore.save(fixtures);
        mongoStore.save(fixtures);
        chStore.client().execute("SYSTEM FLUSH ASYNC INSERT QUEUE")
                .get(30, java.util.concurrent.TimeUnit.SECONDS).close();
    }

    @AfterAll
    void teardown() {
        if (chStore != null) {
            chStore.close();
        }
        if (clickhouse != null) {
            clickhouse.stop();
        }
        if (mongoStore != null) {
            mongoStore.close();
        }
        if (mongo != null) {
            mongo.stop();
        }
    }

    private static EntityDeathRecord death(String entityType, String killer, int x) {
        Instant now = Instant.now();
        return new EntityDeathRecord(UUID.randomUUID(), "death", now, now.plusSeconds(3600),
                Origin.player(), Source.player(UUID.randomUUID(), "Alice"),
                new net.medievalrp.spyglass.api.util.BlockLocation(WORLD, "world", x, 64, 0),
                "srv", entityType.toUpperCase(java.util.Locale.ROOT), entityType,
                UUID.randomUUID(), killer, "ENTITY_ATTACK", null);
    }

    private static final class CapturingSink implements RecordStore.RollbackEffectSink {
        final List<RollbackEffect> complex = new ArrayList<>();
        int skips;

        @Override
        public void block(UUID world, int x, int y, int z, String blockData,
                          String expectedCurrent, Instant occurred, UUID id) {
        }

        @Override
        public void complex(RollbackEffect effect, Instant occurred, UUID id) {
            complex.add(effect);
        }

        @Override
        public void skip(Instant occurred, UUID id) {
            skips++;
        }
    }

    private static void assertOnlyThePlayerKillEmits(RecordStore store) {
        CapturingSink sink = new CapturingSink();
        store.streamRollbackEffects(new QueryRequest(List.of(), Sort.NEWEST_FIRST, 100,
                EnumSet.of(Flag.NO_GROUP), false), null, 100, true, sink);
        assertThat(sink.skips).as("environment death declines").isEqualTo(1);
        assertThat(sink.complex).hasSize(1);
        assertThat(((RollbackEffect.EntitySpawn) sink.complex.get(0)).entityType())
                .isEqualTo("sheep");
    }

    @Test
    void clickHouseMirrorAppliesTheKillerRule() {
        assertOnlyThePlayerKillEmits(chStore);
    }

    @Test
    void mongoMirrorAppliesTheKillerRule() {
        assertOnlyThePlayerKillEmits(mongoStore);
    }
}

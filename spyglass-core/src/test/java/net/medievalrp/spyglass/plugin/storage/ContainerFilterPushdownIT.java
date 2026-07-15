package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.Sort;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MongoDBContainer;

/**
 * #263 pushdown parity: the container-aware b: predicate compiles fully to
 * SQL on ClickHouse (containerType -> container_type, a nullable column)
 * and to BSON on Mongo. The exclude direction is the three-valued-logic
 * trap: without the Exists guards, {@code NOT(...)} evaluates to NULL for
 * every plain block row (container_type IS NULL) and silently drops them
 * all - the guarded shape must keep them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContainerFilterPushdownIT {

    private ClickHouseContainer clickhouse;
    private ClickHouseRecordStore chStore;
    private MongoDBContainer mongo;
    private MongoClient mongoClient;
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
                "container_filter", "event_records",
                clickhouse.getUsername(), clickhouse.getPassword(), false);
        mongo = new MongoDBContainer("mongo:7.0");
        mongo.start();
        String uri = mongo.getReplicaSetUrl();
        mongoClient = MongoClients.create(uri);
        mongoStore = new MongoRecordStore(uri, "IT", "EventRecords", new IndexManager());

        List<EventRecord> fixtures = List.of(
                ContainerAwareBlockFilterTest.deposit("DIAMOND", "CHEST", 1),
                ContainerAwareBlockFilterTest.deposit("CHEST", "BARREL", 2),
                ContainerAwareBlockFilterTest.place(Material.CHEST, 3),
                ContainerAwareBlockFilterTest.place(Material.DIRT, 4));
        chStore.save(fixtures);
        mongoStore.save(fixtures);
        // ClickHouse inserts are async; make them visible to the queries
        // below (same as ClickHouseSynthesisParityIT).
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
        if (mongoClient != null) {
            mongoClient.close();
        }
        if (mongo != null) {
            mongo.stop();
        }
    }

    private static QueryRequest request(QueryPredicate predicate) {
        return new QueryRequest(List.of(predicate), Sort.NEWEST_FIRST, 100,
                EnumSet.of(Flag.NO_GROUP), false);
    }

    private static List<Integer> xsOf(List<EventRecord> records) {
        return records.stream().map(r -> r.location().x()).sorted().toList();
    }

    @Test
    void clickHousePushesTheIncludeAndExcludeDown() {
        assertThat(chStore.query(new QueryRequest(List.of(), Sort.NEWEST_FIRST, 100,
                EnumSet.of(Flag.NO_GROUP), false)).records())
                .as("all four fixtures persisted and readable")
                .hasSize(4);
        assertThat(xsOf(chStore.query(request(
                ContainerAwareBlockFilterTest.blockShape("CHEST"))).records()))
                .containsExactly(1, 3);
        assertThat(xsOf(chStore.query(request(new QueryPredicate.Not(
                ContainerAwareBlockFilterTest.blockShape("CHEST")))).records()))
                .as("the NULL-guarded exclude keeps plain block rows under SQL three-valued logic")
                .containsExactly(2, 4);
    }

    @Test
    void mongoPushesTheIncludeAndExcludeDown() {
        assertThat(xsOf(mongoStore.query(request(
                ContainerAwareBlockFilterTest.blockShape("CHEST"))).records()))
                .containsExactly(1, 3);
        assertThat(xsOf(mongoStore.query(request(new QueryPredicate.Not(
                ContainerAwareBlockFilterTest.blockShape("CHEST")))).records()))
                .containsExactly(2, 4);
    }
}

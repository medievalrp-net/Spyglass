package net.medievalrp.spyglass.plugin.migrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.EventIds;
import net.medievalrp.spyglass.plugin.command.service.ServiceSupport;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig.Backend;
import net.medievalrp.spyglass.plugin.migrate.MigrateService.Outcome;
import net.medievalrp.spyglass.plugin.storage.QueryPage;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * {@link MigrateService} guards + copy loop, driven synchronously through
 * the package-visible {@code runMigrate} with fake stores (same testing
 * seam as {@code ImportServiceTest}).
 */
class MigrateServiceTest {

    private static final UUID WORLD = UUID.randomUUID();

    // ===== fakes =======================================================

    /** In-memory source that pages records by keyset like the real stores. */
    private static final class FakeSource implements RecordStore {
        final List<EventRecord> records = new ArrayList<>();

        @Override public void save(List<EventRecord> batch) {
            throw new UnsupportedOperationException("source is read-only in these tests");
        }

        @Override public QueryResult query(QueryRequest request) {
            List<EventRecord> out = records.subList(0, Math.min(request.limit(), records.size()));
            return new QueryResult(List.copyOf(out), List.of());
        }

        @Override public QueryPage queryPage(QueryRequest request, QueryPage.Cursor cursor, int pageSize) {
            int from = 0;
            if (cursor != null) {
                for (int i = 0; i < records.size(); i++) {
                    if (records.get(i).id().equals(cursor.id())) {
                        from = i + 1;
                        break;
                    }
                }
            }
            int to = Math.min(from + pageSize, records.size());
            List<EventRecord> page = List.copyOf(records.subList(from, to));
            QueryPage.Cursor next = (to < records.size() && !page.isEmpty())
                    ? new QueryPage.Cursor(page.getLast().occurred(), page.getLast().id())
                    : null;
            return new QueryPage(page, next);
        }

        @Override public void close() {}
    }

    /** In-memory target that records everything save()d into it. */
    private static final class FakeTarget implements RecordStore {
        final List<EventRecord> saved = new ArrayList<>();
        List<EventRecord> preExisting = List.of();

        @Override public void save(List<EventRecord> batch) {
            saved.addAll(batch);
        }

        @Override public QueryResult query(QueryRequest request) {
            return new QueryResult(preExisting, List.of());
        }

        @Override public void close() {}
    }

    private static JoinRecord join(Instant occurred) {
        return new JoinRecord(EventIds.newId(), "join", occurred, occurred.plusSeconds(86_400),
                Origin.player(), Source.player(UUID.randomUUID(), "Tester"),
                new BlockLocation(WORLD, "world", 0, 64, 0), "srv", "Tester", null);
    }

    private static SpyglassConfig.Database db(Backend active, String mongoUri, String chHost) {
        return new SpyglassConfig.Database(active,
                new SpyglassConfig.Mongo(mongoUri, "Spyglass", "EventRecords"),
                new SpyglassConfig.ClickHouse(chHost, 8123, "spyglass", "event_records", "default", "", false),
                new SpyglassConfig.Sqlite("spyglass.db"),
                new SpyglassConfig.MariaDb("localhost", 3306, "spyglass", "root", "", false));
    }

    private MigrateService service(FakeSource source, FakeTarget target,
                                   Backend active, SpyglassConfig.Database config,
                                   boolean importRunning) {
        return new MigrateService(source, active, config, ServiceSupport.synchronous(),
                ignored -> target, () -> importRunning, 1_000, Logger.getLogger("migrate-test"));
    }

    // ===== guards ======================================================

    @Test
    void unknownBackendRefused() {
        MigrateService svc = service(new FakeSource(), new FakeTarget(),
                Backend.SQLITE, db(Backend.SQLITE, "mongodb://localhost", "localhost"), false);
        assertThat(svc.runMigrate(mock(CommandSender.class), "postgres", false))
                .isEqualTo(Outcome.INVALID_TARGET);
    }

    @Test
    void activeBackendRefused() {
        MigrateService svc = service(new FakeSource(), new FakeTarget(),
                Backend.SQLITE, db(Backend.SQLITE, "mongodb://localhost", "localhost"), false);
        assertThat(svc.runMigrate(mock(CommandSender.class), "sqlite", false))
                .isEqualTo(Outcome.INVALID_TARGET);
    }

    @Test
    void unconfiguredTargetRefusedByConfigCheck() {
        // Mongo target with a blank uri: the config check must refuse before
        // any store is opened or copied.
        MigrateService svc = service(new FakeSource(), new FakeTarget(),
                Backend.SQLITE, db(Backend.SQLITE, "", "localhost"), false);
        assertThat(svc.runMigrate(mock(CommandSender.class), "mongo", false))
                .isEqualTo(Outcome.INVALID_TARGET);
    }

    @Test
    void runningImportBlocksMigration() {
        MigrateService svc = service(new FakeSource(), new FakeTarget(),
                Backend.SQLITE, db(Backend.SQLITE, "mongodb://localhost", "localhost"), true);
        assertThat(svc.runMigrate(mock(CommandSender.class), "clickhouse", false))
                .isEqualTo(Outcome.IMPORT_RUNNING);
    }

    @Test
    void nonEmptyTargetNeedsConfirm() {
        FakeSource source = new FakeSource();
        source.records.add(join(Instant.now()));
        FakeTarget target = new FakeTarget();
        target.preExisting = List.of(join(Instant.now()));

        MigrateService svc = service(source, target,
                Backend.SQLITE, db(Backend.SQLITE, "mongodb://localhost", "localhost"), false);
        assertThat(svc.runMigrate(mock(CommandSender.class), "clickhouse", false))
                .isEqualTo(Outcome.NEEDS_CONFIRM);
        assertThat(target.saved).as("refused migration must write nothing").isEmpty();

        assertThat(svc.runMigrate(mock(CommandSender.class), "clickhouse", true))
                .isEqualTo(Outcome.DONE);
        assertThat(target.saved).hasSize(1);
    }

    // ===== the copy ====================================================

    @Test
    void copiesEveryRecordAcrossPageBoundaries() {
        FakeSource source = new FakeSource();
        Instant base = Instant.now().minusSeconds(10_000);
        for (int i = 0; i < 2_500; i++) { // 2.5 pages at batch=1000
            source.records.add(join(base.plusSeconds(i)));
        }
        FakeTarget target = new FakeTarget();
        MigrateService svc = service(source, target,
                Backend.SQLITE, db(Backend.SQLITE, "mongodb://localhost", "localhost"), false);

        assertThat(svc.runMigrate(mock(CommandSender.class), "clickhouse", false))
                .isEqualTo(Outcome.DONE);
        assertThat(target.saved).hasSize(2_500);
        assertThat(target.saved.stream().map(EventRecord::id).distinct().count())
                .as("every source record copied exactly once")
                .isEqualTo(2_500);
    }

    // ===== config validation unit checks ===============================

    @Test
    void validateTargetConfigFlagsBlankFields() {
        SpyglassConfig.Database blankMongo = db(Backend.SQLITE, " ", "localhost");
        assertThat(MigrateService.validateTargetConfig(blankMongo, Backend.MONGO))
                .contains("database.mongo.uri");

        SpyglassConfig.Database blankCh = db(Backend.SQLITE, "mongodb://localhost", "");
        assertThat(MigrateService.validateTargetConfig(blankCh, Backend.CLICKHOUSE))
                .contains("clickhouse.host");

        SpyglassConfig.Database ok = db(Backend.SQLITE, "mongodb://localhost", "localhost");
        assertThat(MigrateService.validateTargetConfig(ok, Backend.MONGO)).isNull();
        assertThat(MigrateService.validateTargetConfig(ok, Backend.CLICKHOUSE)).isNull();
        assertThat(MigrateService.validateTargetConfig(ok, Backend.MARIADB)).isNull();
        assertThat(MigrateService.validateTargetConfig(ok, Backend.SQLITE)).isNull();
    }

    @Test
    void parseBackendAcceptsConfigTokens() {
        assertThat(MigrateService.parseBackend("mysql")).isEqualTo(Backend.MARIADB);
        assertThat(MigrateService.parseBackend("MongoDB")).isEqualTo(Backend.MONGO);
        assertThat(MigrateService.parseBackend("ClickHouse")).isEqualTo(Backend.CLICKHOUSE);
        assertThat(MigrateService.parseBackend("nope")).isNull();
    }
}

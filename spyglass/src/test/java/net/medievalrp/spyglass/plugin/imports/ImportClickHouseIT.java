package net.medievalrp.spyglass.plugin.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Logger;
import net.medievalrp.spyglass.plugin.command.service.ServiceSupport;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig.Backend;
import net.medievalrp.spyglass.plugin.storage.ClickHouseRecordStore;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * End-to-end proof that a CoreProtect import through a real ClickHouse
 * backend dedups on re-import: run the same source through
 * {@link ImportService} twice — once fresh, once with {@code --confirm}
 * — and assert the row count is unchanged. Exercises the full seam
 * (SQLite source -> pipeline -> {@link RecordStoreSink} -> ClickHouse
 * async insert -> {@link ImportService}'s finalize {@code OPTIMIZE ...
 * FINAL}) against the real dedup mechanics ClickHouseRecordStoreIT's
 * unit-level {@code replayedRecordCollapsesUnderReplacingMergeTree}
 * test covers in isolation.
 *
 * <p>Container + store bootstrap mirrors {@code ClickHouseRecordStoreIT}
 * (spyglass-core) exactly. The SQLite fixture is
 * {@link CoreProtectFixture}, shared with {@link ImportServiceTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportClickHouseIT {

    private ClickHouseContainer container;
    private ClickHouseRecordStore store;

    @BeforeAll
    void setup() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker not available")
                .isTrue();
        container = new ClickHouseContainer("clickhouse/clickhouse-server:24.8-alpine");
        container.start();

        store = new ClickHouseRecordStore(
                container.getHost(),
                container.getMappedPort(8123),
                "spyglass_it",
                "event_records_it",
                container.getUsername(),
                container.getPassword(),
                false);
    }

    @AfterAll
    void teardown() {
        if (store != null) {
            store.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void reimportWithConfirmCollapsesToSameRowCount(@TempDir Path dir) throws Exception {
        Path db = CoreProtectFixture.sqlite(dir);
        ImportHistoryStore history = new ImportHistoryStore(dir.resolve("history"));
        ImportService svc = new ImportService(store, Backend.CLICKHOUSE, ServiceSupport.synchronous(),
                dir /* worldContainer: uid.dat lives under dir/world/uid.dat */, "test-server",
                Duration.ofDays(30), 1000, history, Logger.getLogger("import-it"));

        ImportService.ImportOutcome first =
                svc.runImportSqlite(mock(CommandSender.class), db, false);
        assertThat(first).isEqualTo(ImportService.ImportOutcome.DONE);
        long n1 = store.count();
        assertThat(n1).as("first import wrote at least one row").isGreaterThanOrEqualTo(1L);

        // The identity is now in history; a bare re-import (no --confirm)
        // would be refused with NEEDS_CONFIRM, so the second run passes
        // confirm=true - the exact re-import path a human operator would
        // take after seeing that refusal.
        ImportService.ImportOutcome second =
                svc.runImportSqlite(mock(CommandSender.class), db, true);
        assertThat(second).isEqualTo(ImportService.ImportOutcome.DONE);
        long n2 = store.count();

        // ImportService's finalize flushes ClickHouse's async-insert
        // buffer and runs OPTIMIZE ... FINAL, so ReplacingMergeTree
        // collapses the re-imported rows onto the same (event, occurred,
        // id) keys the first import wrote - the row count must not grow.
        assertThat(n2)
                .as("re-import with --confirm must dedup to the same row count "
                        + "(n1=" + n1 + ", n2=" + n2 + ")")
                .isEqualTo(n1);
    }
}

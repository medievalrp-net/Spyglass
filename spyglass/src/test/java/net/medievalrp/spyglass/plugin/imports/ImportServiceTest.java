package net.medievalrp.spyglass.plugin.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.plugin.command.service.ServiceSupport;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig.Backend;
import net.medievalrp.spyglass.plugin.imports.ImportHistoryStore.ImportRecord;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportServiceTest {

    private static final class FakeStore implements RecordStore {
        int saved;

        @Override
        public void save(List<EventRecord> records) {
            saved += records.size();
        }

        @Override
        public net.medievalrp.spyglass.api.query.QueryResult query(
                net.medievalrp.spyglass.api.query.QueryRequest r) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}
    }

    @Test
    void reimportWithoutConfirmIsRefused(@TempDir Path dir) throws Exception {
        Path db = CoreProtectFixture.sqlite(dir);
        ImportHistoryStore history = new ImportHistoryStore(dir);
        history.record(new ImportRecord(
                ImportIdentity.ofSqliteFile(db), db.getFileName().toString(),
                1L, "prior", 1, 1, 0));
        ImportService svc = newService(dir, new FakeStore(), Backend.CLICKHOUSE, history);
        ImportService.ImportOutcome outcome =
                svc.runImportSqlite(mock(CommandSender.class), db, false);
        assertThat(outcome).isEqualTo(ImportService.ImportOutcome.NEEDS_CONFIRM);
    }

    @Test
    void mongoReimportIsBlockedEvenWithConfirm(@TempDir Path dir) throws Exception {
        Path db = CoreProtectFixture.sqlite(dir);
        ImportHistoryStore history = new ImportHistoryStore(dir);
        history.record(new ImportRecord(
                ImportIdentity.ofSqliteFile(db), "old", 1L, "p", 1, 1, 0));
        ImportService svc = newService(dir, new FakeStore(), Backend.MONGO, history);
        assertThat(svc.runImportSqlite(mock(CommandSender.class), db, true))
                .isEqualTo(ImportService.ImportOutcome.MONGO_REIMPORT_BLOCKED);
    }

    @Test
    void freshImportRunsAndRecordsHistory(@TempDir Path dir) throws Exception {
        Path db = CoreProtectFixture.sqlite(dir);
        FakeStore store = new FakeStore();
        ImportHistoryStore history = new ImportHistoryStore(dir);
        ImportService svc = newService(dir, store, Backend.CLICKHOUSE, history);
        assertThat(svc.runImportSqlite(mock(CommandSender.class), db, false))
                .isEqualTo(ImportService.ImportOutcome.DONE);
        assertThat(history.find(ImportIdentity.ofSqliteFile(db))).isPresent();
        assertThat(store.saved).isGreaterThanOrEqualTo(1);
    }

    private static ImportService newService(Path dir, RecordStore store, Backend backend,
                                            ImportHistoryStore history) {
        return new ImportService(store, backend, ServiceSupport.synchronous(),
                dir /* worldContainer */, "test-server", Duration.ofDays(30),
                1000, history, Logger.getLogger("test"));
    }

}

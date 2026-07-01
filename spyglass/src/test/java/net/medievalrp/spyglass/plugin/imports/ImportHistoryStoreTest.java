package net.medievalrp.spyglass.plugin.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import net.medievalrp.spyglass.plugin.imports.ImportHistoryStore.ImportRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportHistoryStoreTest {

    @Test
    void recordsAndFindsByIdentity(@TempDir Path dir) {
        ImportHistoryStore store = new ImportHistoryStore(dir);
        assertThat(store.find("id-1")).isEmpty();
        store.record(new ImportRecord("id-1", "old.db", 1000L, "steve", 10, 8, 2));
        assertThat(store.find("id-1")).isPresent();
        assertThat(store.find("id-1").orElseThrow().written()).isEqualTo(8);
    }

    @Test
    void persistsAcrossInstances(@TempDir Path dir) {
        new ImportHistoryStore(dir).record(
                new ImportRecord("id-2", "s.db", 2000L, "alex", 5, 5, 0));
        ImportHistoryStore reopened = new ImportHistoryStore(dir);
        assertThat(reopened.find("id-2")).isPresent();
        assertThat(reopened.find("id-2").orElseThrow().displayName()).isEqualTo("s.db");
    }

    @Test
    void recordReplacesSameIdentity(@TempDir Path dir) {
        ImportHistoryStore store = new ImportHistoryStore(dir);
        store.record(new ImportRecord("id-3", "s.db", 1L, "a", 1, 1, 0));
        store.record(new ImportRecord("id-3", "s.db", 2L, "a", 9, 9, 0));
        assertThat(store.all()).hasSize(1);
        assertThat(store.find("id-3").orElseThrow().written()).isEqualTo(9);
    }
}

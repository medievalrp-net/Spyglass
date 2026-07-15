package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.EventIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SqliteRecordStore#resolvePlayerId}: names of players known only to
 * the store (imported histories) resolve to their UUID off the uuids intern
 * palette, case-insensitively; unknown names resolve to null.
 */
class ResolvePlayerIdTest {

    private static final UUID GRIEFER = UUID.fromString("c1386514-0fd1-4f0c-8591-997d92d74a7a");
    private static final UUID WORLD = UUID.randomUUID();

    @Test
    void resolvesImportedPlayerNameCaseInsensitively(@TempDir Path dir) {
        try (SqliteRecordStore store = new SqliteRecordStore(dir.resolve("t.db"))) {
            Instant now = Instant.now();
            store.save(List.of(new JoinRecord(EventIds.newId(), "join", now,
                    now.plusSeconds(3600), Origin.player(),
                    Source.player(GRIEFER, "ItzSh4rkyz"),
                    new BlockLocation(WORLD, "world", 0, 64, 0), "srv", "ItzSh4rkyz", null)));

            assertThat(store.resolvePlayerId("ItzSh4rkyz")).isEqualTo(GRIEFER);
            assertThat(store.resolvePlayerId("itzsh4rkyz")).isEqualTo(GRIEFER);
            assertThat(store.resolvePlayerId("NeverSeen")).isNull();
        }
    }

    // The live-caught trap: the plugin wraps every store in
    // SynthesizingRecordStore (always, since #312), and
    // a decorator that doesn't forward a default interface method silently
    // serves the default (null) - disabling name resolution in production
    // while unit tests on the bare store stay green.
    @Test
    void synthesizingDecoratorDelegatesResolvePlayerId(@TempDir Path dir) {
        try (SqliteRecordStore store = new SqliteRecordStore(dir.resolve("t2.db"))) {
            Instant now = Instant.now();
            store.save(List.of(new JoinRecord(EventIds.newId(), "join", now,
                    now.plusSeconds(3600), Origin.player(),
                    Source.player(GRIEFER, "ItzSh4rkyz"),
                    new BlockLocation(WORLD, "world", 0, 64, 0), "srv", "ItzSh4rkyz", null)));

            SynthesizingRecordStore wrapped = new SynthesizingRecordStore(store, true);
            assertThat(wrapped.resolvePlayerId("ItzSh4rkyz")).isEqualTo(GRIEFER);
        }
    }
}

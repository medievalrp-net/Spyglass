package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #284 across the record-to-effect emitters: an environment death (killer
 * FIRE_TICK) must stream as a cursor-advancing skip, never as an
 * EntitySpawn; a player kill still resurrects. Covers the default
 * interface stream and the real SQLite store (whose lean path decodes the
 * blob and calls the api the same way MariaDB does).
 */
class DeathResurrectionStreamTest {

    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000ab");

    @TempDir
    Path dir;

    private static EntityDeathRecord death(String entityType, String killer, int x) {
        Instant now = Instant.now();
        return new EntityDeathRecord(UUID.randomUUID(), "death", now, now.plusSeconds(3600),
                Origin.player(), Source.player(UUID.randomUUID(), "Alice"),
                new BlockLocation(WORLD, "world", x, 64, 0),
                "srv", entityType.toUpperCase(java.util.Locale.ROOT), entityType,
                UUID.randomUUID(), killer, "ENTITY_ATTACK", null);
    }

    private static QueryRequest all() {
        return new QueryRequest(List.of(), Sort.NEWEST_FIRST, 100,
                EnumSet.of(Flag.NO_GROUP), false);
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

    @Test
    void defaultStreamSkipsEnvironmentDeathsAndEmitsPlayerKills() {
        List<EventRecord> rows = List.of(
                death("zombie", "FIRE_TICK", 1),
                death("sheep", "player", 2));
        RecordStore store = new RecordStore() {
            @Override
            public void save(List<EventRecord> records) {
            }

            @Override
            public QueryResult query(QueryRequest request) {
                return new QueryResult(rows, List.of());
            }

            @Override
            public void close() {
            }
        };
        CapturingSink sink = new CapturingSink();

        store.streamRollbackEffects(all(), null, 100, true, sink);

        assertThat(sink.skips).as("the fire-tick zombie declines").isEqualTo(1);
        assertThat(sink.complex).hasSize(1);
        assertThat(sink.complex.get(0)).isInstanceOf(RollbackEffect.EntitySpawn.class);
        assertThat(((RollbackEffect.EntitySpawn) sink.complex.get(0)).entityType())
                .isEqualTo("sheep");
    }

    @Test
    void sqliteStreamSkipsEnvironmentDeathsAndEmitsPlayerKills() {
        try (SqliteRecordStore store = new SqliteRecordStore(dir.resolve("spyglass.db"))) {
            store.save(List.of(
                    death("zombie", "FIRE_TICK", 1),
                    death("sheep", "player", 2)));
            CapturingSink sink = new CapturingSink();

            store.streamRollbackEffects(all(), null, 100, true, sink);

            assertThat(sink.skips).isEqualTo(1);
            assertThat(sink.complex).hasSize(1);
            assertThat(((RollbackEffect.EntitySpawn) sink.complex.get(0)).entityType())
                    .isEqualTo("sheep");
        }
    }
}

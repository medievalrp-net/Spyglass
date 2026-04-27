package net.medievalrp.spyglass.plugin.command.service.tool;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.command.CommandResponse;
import com.clickhouse.client.api.query.GenericRecord;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jetbrains.annotations.ApiStatus;

/**
 * ClickHouse-backed {@link ToolStateStore} on the {@code
 * EmbeddedRocksDB} engine — a CH table fronted by an embedded
 * RocksDB key-value store, so {@link #loadActive}, {@link #enable},
 * and {@link #disable} are O(log N) point operations with no granule
 * scan. Designed exactly for "small mutable lookup table next to a
 * MergeTree analytics table".
 */
@ApiStatus.Internal
public final class ClickHouseToolStateStore implements ToolStateStore {

    private static final DateTimeFormatter CH_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Client client;
    private final String table;

    public ClickHouseToolStateStore(Client client, String database) {
        this.client = client;
        this.table = "`" + database + "`.`tool_states`";
    }

    @Override
    public Collection<UUID> loadActive() {
        List<GenericRecord> rows = client.queryAll("SELECT player_id FROM " + table);
        List<UUID> out = new ArrayList<>(rows.size());
        for (GenericRecord row : rows) {
            UUID id = row.getUUID("player_id");
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    @Override
    public void enable(UUID playerId) {
        // EmbeddedRocksDB upsert: insert overwrites on primary-key collision.
        String sql = "INSERT INTO " + table + " (player_id, enabled_at) VALUES ("
                + "toUUID('" + playerId + "'), "
                + chTimestamp(Instant.now()) + ")";
        execute(sql);
    }

    @Override
    public void disable(UUID playerId) {
        String sql = "ALTER TABLE " + table + " DELETE WHERE player_id = toUUID('" + playerId + "')";
        execute(sql);
    }

    private void execute(String sql) {
        try (CommandResponse ignored = client.execute(sql).get(30, TimeUnit.SECONDS)) {
            // ack
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("ToolStateStore command interrupted", ie);
        } catch (Exception ex) {
            throw new RuntimeException("ToolStateStore command failed: " + ex.getMessage(), ex);
        }
    }

    private static String chTimestamp(Instant instant) {
        return "'" + CH_TIMESTAMP.format(instant.atOffset(ZoneOffset.UTC)) + "'";
    }
}

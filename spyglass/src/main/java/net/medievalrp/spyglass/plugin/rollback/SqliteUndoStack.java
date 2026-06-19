package net.medievalrp.spyglass.plugin.rollback;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.medievalrp.spyglass.plugin.storage.SqliteRecordStore;
import org.jetbrains.annotations.ApiStatus;

/**
 * SQLite-backed {@link UndoStack}. One small row per undo operation,
 * stored <b>by reference</b> (the resolved query that replays the
 * original op in the opposite direction — see {@code UndoReferenceBson}),
 * mirroring the reference-only contract the Mongo / ClickHouse stacks
 * settled on (#17).
 *
 * <p>The SQLite backend is new, so there is no legacy chunked-row shape
 * to read — every row is a reference, surfaced as {@link ReplayReference}.
 * A 24h horizon is enforced with an opportunistic delete on each push
 * (SQLite has no native TTL), matching the 1-day window the other
 * backends TTL.
 */
@ApiStatus.Internal
public final class SqliteUndoStack implements UndoStack {

    private static final long TTL_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(1);

    private final SqliteRecordStore store;

    public SqliteUndoStack(SqliteRecordStore store) {
        this.store = store;
        store.withWriteConnection(conn -> {
            try (var st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS undo_history ("
                        + "operation_id TEXT PRIMARY KEY, "
                        + "player_id TEXT NOT NULL, "
                        + "created_at INTEGER NOT NULL, "
                        + "operation_type TEXT NOT NULL, "
                        + "reference TEXT NOT NULL)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_undo_player "
                        + "ON undo_history(player_id, created_at)");
            }
            return null;
        });
    }

    @Override
    public void pushReference(UUID playerId, String operationType, String referenceBase64) {
        UUID operationId = UUID.randomUUID();
        long now = System.currentTimeMillis();
        store.withWriteConnection(conn -> {
            try (var ps = conn.prepareStatement("INSERT INTO undo_history "
                    + "(operation_id, player_id, created_at, operation_type, reference) "
                    + "VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, operationId.toString());
                ps.setString(2, playerId.toString());
                ps.setLong(3, now);
                ps.setString(4, operationType + REF_MARKER);
                ps.setString(5, referenceBase64);
                ps.executeUpdate();
            }
            // Opportunistic TTL: drop anything past the 24h window.
            try (var ps = conn.prepareStatement("DELETE FROM undo_history WHERE created_at < ?")) {
                ps.setLong(1, now - TTL_MS);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<Popped> openLatest(UUID playerId) {
        return store.withReadConnection(conn -> {
            try (var ps = conn.prepareStatement("SELECT operation_id, created_at, "
                    + "operation_type, reference FROM undo_history "
                    + "WHERE player_id = ? AND created_at >= ? "
                    + "ORDER BY created_at DESC LIMIT 1")) {
                ps.setString(1, playerId.toString());
                ps.setLong(2, System.currentTimeMillis() - TTL_MS);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    UUID operationId = UUID.fromString(rs.getString("operation_id"));
                    Instant createdAt = Instant.ofEpochMilli(rs.getLong("created_at"));
                    String storedType = rs.getString("operation_type");
                    String reference = rs.getString("reference");
                    String type = storedType.endsWith(REF_MARKER)
                            ? storedType.substring(0, storedType.length() - REF_MARKER.length())
                            : storedType;
                    return Optional.of(new Reference(operationId, createdAt, type, reference));
                }
            }
        });
    }

    private void tombstone(UUID operationId) {
        store.withWriteConnection(conn -> {
            try (var ps = conn.prepareStatement("DELETE FROM undo_history WHERE operation_id = ?")) {
                ps.setString(1, operationId.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private final class Reference implements ReplayReference {

        private final UUID operationId;
        private final Instant createdAt;
        private final String operationType;
        private String reference;

        private Reference(UUID operationId, Instant createdAt,
                          String operationType, String reference) {
            this.operationId = operationId;
            this.createdAt = createdAt;
            this.operationType = operationType;
            this.reference = reference;
        }

        @Override
        public UUID operationId() {
            return operationId;
        }

        @Override
        public Instant createdAt() {
            return createdAt;
        }

        @Override
        public String operationType() {
            return operationType;
        }

        @Override
        public String referenceBase64() {
            return reference;
        }

        @Override
        public void tombstone() {
            SqliteUndoStack.this.tombstone(operationId);
        }

        @Override
        public void close() {
            reference = null;
        }
    }
}

package net.medievalrp.spyglass.plugin.snapshot;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.plugin.storage.MariaDbRecordStore;
import org.jetbrains.annotations.ApiStatus;

/**
 * MariaDB / MySQL-backed {@link PlayerSnapshotStore}. Three InnoDB tables in
 * the shared Spyglass database, self-created in the constructor:
 * {@code snapshot_items} interns each distinct item payload once
 * (content-addressed by the SHA-256/16 of its raw {@code serializeAsBytes}
 * bytes), {@code player_snapshots} holds one row per capture, and
 * {@code player_snapshot_slots} maps a capture's occupied slots onto interned
 * payloads. Interning is what keeps a week of "same kit every sweep" down to
 * one payload set rather than one per snapshot. Models
 * {@code SqlitePlayerSnapshotStore}.
 *
 * <p>Shares the record store's single write connection and read pool via
 * {@code withWriteConnection} / {@code withReadConnection}. The intern insert
 * MUST be an idempotent upsert - {@code INSERT ... ON DUPLICATE KEY UPDATE
 * hash = hash} - because the recorder-retry path re-interns already-committed
 * payloads; a plain {@code INSERT} throws Duplicate entry on that retry (a bug
 * that only ever showed up live). The snapshot row uses {@code REPLACE INTO}
 * for the same reason.
 */
@ApiStatus.Internal
public final class MariaDbPlayerSnapshotStore implements PlayerSnapshotStore {

    private static final Logger LOGGER = Logger.getLogger(MariaDbPlayerSnapshotStore.class.getName());

    private final MariaDbRecordStore store;

    public MariaDbPlayerSnapshotStore(MariaDbRecordStore store) {
        this.store = store;
        store.withWriteConnection(conn -> {
            try (var st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS snapshot_items ("
                        + "hash BINARY(16) NOT NULL PRIMARY KEY, "
                        + "material VARCHAR(255), "
                        + "data MEDIUMBLOB) "
                        + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                st.execute("CREATE TABLE IF NOT EXISTS player_snapshots ("
                        + "id BINARY(16) NOT NULL PRIMARY KEY, "
                        + "player_uuid BINARY(16) NOT NULL, "
                        + "player_name VARCHAR(255), "
                        + "occurred BIGINT NOT NULL, "
                        + "cause VARCHAR(64), "
                        + "kind TINYINT NOT NULL DEFAULT 0, "
                        + "content_hash BIGINT NOT NULL, "
                        + "KEY idx_player_snapshots_player (player_uuid, occurred)) "
                        + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                st.execute("CREATE TABLE IF NOT EXISTS player_snapshot_slots ("
                        + "snapshot_id BINARY(16) NOT NULL, "
                        + "slot SMALLINT NOT NULL, "
                        + "item_hash BINARY(16) NOT NULL, "
                        + "count SMALLINT NOT NULL, "
                        + "PRIMARY KEY (snapshot_id, slot)) "
                        + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }
            return null;
        });
    }

    @Override
    public void save(PlayerSnapshot snapshot) {
        List<InternedSlot> prepared = prepare(snapshot);
        byte[] id = uuidToBytes(snapshot.id());
        store.withWriteConnection(conn -> {
            boolean priorAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement intern = conn.prepareStatement(
                        "INSERT INTO snapshot_items(hash, material, data) VALUES (?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE hash = hash")) {
                    for (InternedSlot s : prepared) {
                        intern.setBytes(1, s.hash());
                        intern.setString(2, s.material());
                        intern.setBytes(3, s.data());
                        intern.addBatch();
                    }
                    intern.executeBatch();
                }
                try (PreparedStatement snap = conn.prepareStatement(
                        "REPLACE INTO player_snapshots"
                                + "(id, player_uuid, player_name, occurred, cause, kind, content_hash) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    snap.setBytes(1, id);
                    snap.setBytes(2, uuidToBytes(snapshot.player()));
                    snap.setString(3, snapshot.playerName());
                    snap.setLong(4, snapshot.capturedAt().toEpochMilli());
                    snap.setString(5, snapshot.cause());
                    snap.setInt(6, 0); // kind: 0 = keyframe (delta rows reserved)
                    snap.setLong(7, snapshot.contentHash());
                    snap.executeUpdate();
                }
                // Re-derive the slot set from scratch so a re-save of the same id
                // never leaves a stale slot row behind.
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM player_snapshot_slots WHERE snapshot_id = ?")) {
                    del.setBytes(1, id);
                    del.executeUpdate();
                }
                try (PreparedStatement slot = conn.prepareStatement(
                        "INSERT INTO player_snapshot_slots(snapshot_id, slot, item_hash, count) "
                                + "VALUES (?, ?, ?, ?)")) {
                    for (InternedSlot s : prepared) {
                        slot.setBytes(1, id);
                        slot.setInt(2, s.slot());
                        slot.setBytes(3, s.hash());
                        slot.setInt(4, s.count());
                        slot.addBatch();
                    }
                    slot.executeBatch();
                }
                conn.commit();
            } catch (SQLException ex) {
                rollbackQuietly(conn);
                throw ex;
            } finally {
                restoreAutoCommit(conn, priorAutoCommit);
            }
            return null;
        });
    }

    @Override
    public Optional<PlayerSnapshot> latestAtOrBefore(UUID player, Instant instant) {
        Optional<PlayerSnapshot> result = store.withReadConnection(conn -> {
            Header header;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, player_name, occurred, cause, content_hash "
                            + "FROM player_snapshots WHERE player_uuid = ? AND occurred <= ? "
                            + "ORDER BY occurred DESC, id DESC LIMIT 1")) {
                ps.setBytes(1, uuidToBytes(player));
                ps.setLong(2, instant.toEpochMilli());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<PlayerSnapshot>empty();
                    }
                    header = new Header(rs.getBytes("id"), rs.getString("player_name"),
                            rs.getLong("occurred"), rs.getString("cause"), rs.getLong("content_hash"));
                }
            }
            List<SnapshotSlot> slots = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT s.slot, s.count, i.material, i.data "
                            + "FROM player_snapshot_slots s "
                            + "JOIN snapshot_items i ON s.item_hash = i.hash "
                            + "WHERE s.snapshot_id = ? ORDER BY s.slot")) {
                ps.setBytes(1, header.id());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        slots.add(hydrateSlot(rs));
                    }
                }
            }
            return Optional.of(new PlayerSnapshot(
                    bytesToUuid(header.id()), player, header.playerName(),
                    Instant.ofEpochMilli(header.occurred()), header.cause(),
                    header.contentHash(), slots));
        });
        return result;
    }

    @Override
    public OptionalLong lastContentHash(UUID player) {
        return store.withReadConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT content_hash FROM player_snapshots WHERE player_uuid = ? "
                            + "ORDER BY occurred DESC, id DESC LIMIT 1")) {
                ps.setBytes(1, uuidToBytes(player));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
                }
            }
        });
    }

    @Override
    public int prune(Instant cutoff) {
        long cutoffMs = cutoff.toEpochMilli();
        return store.withWriteConnection(conn -> {
            boolean priorAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement delSlots = conn.prepareStatement(
                        "DELETE FROM player_snapshot_slots WHERE snapshot_id IN "
                                + "(SELECT id FROM player_snapshots WHERE occurred < ?)")) {
                    delSlots.setLong(1, cutoffMs);
                    delSlots.executeUpdate();
                }
                int removed;
                try (PreparedStatement delSnaps = conn.prepareStatement(
                        "DELETE FROM player_snapshots WHERE occurred < ?")) {
                    delSnaps.setLong(1, cutoffMs);
                    removed = delSnaps.executeUpdate();
                }
                // Anti-join GC: drop interned payloads no surviving slot references.
                // The tables are small at prune cadence, so the NOT IN scan is fine.
                try (PreparedStatement gc = conn.prepareStatement(
                        "DELETE FROM snapshot_items WHERE hash NOT IN "
                                + "(SELECT DISTINCT item_hash FROM player_snapshot_slots)")) {
                    gc.executeUpdate();
                }
                conn.commit();
                return removed;
            } catch (SQLException ex) {
                rollbackQuietly(conn);
                throw ex;
            } finally {
                restoreAutoCommit(conn, priorAutoCommit);
            }
        });
    }

    private static SnapshotSlot hydrateSlot(ResultSet rs) throws SQLException {
        int slot = rs.getInt("slot");
        int count = rs.getInt("count");
        String material = rs.getString("material");
        byte[] data = rs.getBytes("data");
        StoredItem item = new StoredItem(slot, material,
                data == null ? null : Base64.getEncoder().encodeToString(data));
        return new SnapshotSlot(slot, count, item);
    }

    private List<InternedSlot> prepare(PlayerSnapshot snapshot) {
        List<InternedSlot> out = new ArrayList<>(snapshot.slots().size());
        for (SnapshotSlot slot : snapshot.slots()) {
            StoredItem item = slot.item();
            if (item == null || item.data() == null || item.data().isBlank()) {
                LOGGER.log(Level.WARNING,
                        "Skipping snapshot slot {0} for {1}: no item payload to intern",
                        new Object[]{slot.slot(), snapshot.player()});
                continue;
            }
            byte[] raw = Base64.getDecoder().decode(item.data());
            out.add(new InternedSlot(slot.slot(), slot.count(), item.material(), hash16(raw), raw));
        }
        return out;
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "MariaDB snapshot rollback failed", ex);
        }
    }

    private static void restoreAutoCommit(Connection conn, boolean value) {
        try {
            conn.setAutoCommit(value);
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "MariaDB snapshot autocommit restore failed", ex);
        }
    }

    // SHA-256 truncated to 16 bytes over the raw serializeAsBytes payload. The
    // truncation is the content address; a 128-bit space makes an accidental
    // collision between two distinct item stacks not a practical concern.
    static byte[] hash16(byte[] raw) {
        try {
            byte[] full = MessageDigest.getInstance("SHA-256").digest(raw);
            byte[] out = new byte[16];
            System.arraycopy(full, 0, out, 0, 16);
            return out;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static byte[] uuidToBytes(UUID uuid) {
        byte[] out = new byte[16];
        ByteBuffer.wrap(out).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits());
        return out;
    }

    static UUID bytesToUuid(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        return new UUID(bb.getLong(), bb.getLong());
    }

    /** One captured slot resolved to its interned payload, for the save path. */
    private record InternedSlot(int slot, int count, String material, byte[] hash, byte[] data) {
    }

    /** The {@code player_snapshots} header row, read before its slots. */
    private record Header(byte[] id, String playerName, long occurred, String cause, long contentHash) {
    }
}

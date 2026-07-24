package net.medievalrp.spyglass.plugin.snapshot;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.command.CommandResponse;
import com.clickhouse.client.api.query.GenericRecord;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.EventIds;
import org.jetbrains.annotations.ApiStatus;

/**
 * ClickHouse-backed {@link PlayerSnapshotStore}. Two ReplacingMergeTree tables
 * created by {@code ClickHouseSchema.ensure} (not here): {@code player_snapshots}
 * holds one keyframe row per capture with the slots as parallel arrays of
 * {@code snapshot_items} hash refs, and {@code snapshot_items} interns the item
 * payloads content-addressed by the 16-byte SHA-256/16 of their raw bytes.
 * Modeled on {@code ClickHouseSalvageStore}; shares the record store's
 * {@link Client}.
 *
 * <p>Writes go through {@code client.execute} INSERT statements, which are
 * synchronous and do not enable {@code async_insert}: snapshot volume is tiny,
 * and the interface requires {@link #latestAtOrBefore} right after {@link #save}
 * to see the row, so the row must be queryable the instant the INSERT acks.
 * Hashes cross the SQL boundary as uppercase hex through {@code hex}/{@code unhex}
 * so no binary ever sits inside a statement literal.
 */
@ApiStatus.Internal
public final class ClickHousePlayerSnapshotStore implements PlayerSnapshotStore {

    private static final DateTimeFormatter CH_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private final Client client;
    private final String snapshotsTable;
    private final String itemsTable;

    public ClickHousePlayerSnapshotStore(Client client, String database) {
        this.client = client;
        this.snapshotsTable = "`" + database + "`.`player_snapshots`";
        this.itemsTable = "`" + database + "`.`snapshot_items`";
    }

    @Override
    public void save(PlayerSnapshot snapshot) {
        List<String> slotSlots = new ArrayList<>();
        List<String> slotHashes = new ArrayList<>();
        List<String> slotCounts = new ArrayList<>();
        // Intern once per distinct payload in this capture: a snapshot that
        // stacks the same item across slots would otherwise re-INSERT the
        // identical intern row several times (harmless under the merge, but
        // wasteful to send).
        Set<String> internedThisSave = new HashSet<>();
        for (SnapshotSlot slot : snapshot.slots()) {
            StoredItem item = slot.item();
            if (item == null || item.data() == null) {
                continue;
            }
            String hex = hashHex(item.data());
            if (internedThisSave.add(hex)) {
                execute("INSERT INTO " + itemsTable + " (hash, material, data) VALUES ("
                        + fixedHash(hex) + ", " + escape(item.material()) + ", "
                        + escape(item.data()) + ")");
            }
            slotSlots.add(Integer.toString(slot.slot()));
            slotHashes.add(fixedHash(hex));
            slotCounts.add(Integer.toString(slot.count()));
        }
        execute("INSERT INTO " + snapshotsTable + " (id, player_uuid, player_name, occurred, "
                + "cause, kind, content_hash, slots_slot, slots_hash, slots_count) VALUES ("
                + Long.toUnsignedString(EventIds.sequenceOf(snapshot.id())) + ", "
                + "toUUID('" + snapshot.player() + "'), "
                + escape(snapshot.playerName()) + ", "
                + chTimestamp(snapshot.capturedAt()) + ", "
                + escape(snapshot.cause()) + ", 0, "
                + snapshot.contentHash() + ", "
                + "[" + String.join(", ", slotSlots) + "], "
                + "[" + String.join(", ", slotHashes) + "], "
                + "[" + String.join(", ", slotCounts) + "])");
    }

    @Override
    public Optional<PlayerSnapshot> latestAtOrBefore(UUID player, Instant instant) {
        // FINAL collapses any not-yet-merged duplicate of the same capture
        // id; the ORDER BY + LIMIT 1 then picks the newest row at or before T.
        // The slot/count arrays come back cast to Array(Int32) so the driver
        // yields plain Integers regardless of how it maps UInt8/UInt16; the
        // hash array crosses as uppercase hex strings (hex() of each element).
        List<GenericRecord> rows = client.queryAll(
                "SELECT id, player_name, occurred, cause, content_hash, "
                        + "CAST(slots_slot AS Array(Int32)) AS slots_slot, "
                        + "CAST(slots_count AS Array(Int32)) AS slots_count, "
                        + "arrayMap(x -> hex(x), slots_hash) AS slots_hash_hex "
                        + "FROM " + snapshotsTable + " FINAL "
                        + "WHERE player_uuid = toUUID('" + player + "') "
                        + "AND occurred <= " + chTimestamp(instant) + " "
                        + "ORDER BY occurred DESC, id DESC LIMIT 1");
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        GenericRecord row = rows.get(0);
        List<Integer> slotIndexes = intList(row.getList("slots_slot"));
        List<Integer> slotCounts = intList(row.getList("slots_count"));
        List<String> slotHashes = stringList(row.getList("slots_hash_hex"));
        Map<String, GenericRecord> blobs = fetchBlobs(new LinkedHashSet<>(slotHashes));

        List<SnapshotSlot> slots = new ArrayList<>(slotIndexes.size());
        for (int i = 0; i < slotIndexes.size(); i++) {
            String hex = slotHashes.get(i);
            GenericRecord blob = blobs.get(hex);
            if (blob == null) {
                // The referenced payload is gone (should not happen: prune
                // leaves snapshot_items orphans, never live refs). Skip the
                // slot rather than emit a StoredItem with no data.
                continue;
            }
            StoredItem item = new StoredItem(
                    slotIndexes.get(i), blob.getString("material"),
                    blob.getString("data"), null, null, null, null);
            slots.add(new SnapshotSlot(slotIndexes.get(i), slotCounts.get(i), item));
        }
        return Optional.of(new PlayerSnapshot(
                EventIds.uuidOf(row.getLong("id")),
                player,
                row.getString("player_name"),
                row.getInstant("occurred"),
                row.getString("cause"),
                row.getLong("content_hash"),
                slots));
    }

    @Override
    public OptionalLong lastContentHash(UUID player) {
        List<GenericRecord> rows = client.queryAll(
                "SELECT content_hash FROM " + snapshotsTable + " FINAL "
                        + "WHERE player_uuid = toUUID('" + player + "') "
                        + "ORDER BY occurred DESC, id DESC LIMIT 1");
        if (rows.isEmpty()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(rows.get(0).getLong("content_hash"));
    }

    @Override
    public int prune(Instant cutoff) {
        String bound = chTimestamp(cutoff);
        List<GenericRecord> counted = client.queryAll(
                "SELECT count() AS c FROM " + snapshotsTable + " FINAL WHERE occurred < " + bound);
        int removed = counted.isEmpty() ? 0 : (int) counted.get(0).getLong("c");
        execute("DELETE FROM " + snapshotsTable + " WHERE occurred < " + bound);
        // Orphaned snapshot_items rows are deliberately left behind: they are
        // ZSTD'd, content-addressed crumbs that cost almost nothing to keep,
        // and a later re-capture of the same item reuses the interned row
        // instead of rewriting it. Sweeping them would mean a full anti-join
        // over the intern table on every prune for no measurable disk win.
        return removed;
    }

    private Map<String, GenericRecord> fetchBlobs(Set<String> hexHashes) {
        if (hexHashes.isEmpty()) {
            return Map.of();
        }
        StringBuilder in = new StringBuilder();
        for (String hex : hexHashes) {
            if (in.length() > 0) {
                in.append(", ");
            }
            in.append('\'').append(hex).append('\'');
        }
        Map<String, GenericRecord> blobs = new HashMap<>();
        for (GenericRecord row : client.queryAll(
                "SELECT hex(hash) AS h, material, data FROM " + itemsTable + " FINAL "
                        + "WHERE hex(hash) IN (" + in + ")")) {
            blobs.put(row.getString("h"), row);
        }
        return blobs;
    }

    private void execute(String sql) {
        try (CommandResponse ignored = client.execute(sql).get(30, TimeUnit.SECONDS)) {
            // ack received
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Snapshot command interrupted", ie);
        } catch (Exception ex) {
            throw new RuntimeException("Snapshot command failed: " + ex.getMessage(), ex);
        }
    }

    /** Uppercase hex SHA-256/16 of the raw (base64-decoded) item payload. */
    private static String hashHex(String base64Payload) {
        byte[] raw = Base64.getDecoder().decode(base64Payload);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            return HEX.formatHex(Arrays.copyOf(digest, 16));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is a required JCA algorithm on every JVM.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    // Hash refs travel as uppercase hex and become a FixedString(16) via
    // unhex(); toFixedString pins the width so the array literal types as
    // Array(FixedString(16)) rather than Array(String).
    private static String fixedHash(String hex) {
        return "toFixedString(unhex('" + hex + "'),16)";
    }

    private static List<Integer> intList(List<?> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>(raw.size());
        for (Object element : raw) {
            if (element instanceof Number number) {
                out.add(number.intValue());
            } else if (element != null) {
                out.add(Integer.parseInt(element.toString()));
            }
        }
        return out;
    }

    private static List<String> stringList(List<?> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(raw.size());
        for (Object element : raw) {
            out.add(element == null ? "" : element.toString());
        }
        return out;
    }

    private static String chTimestamp(Instant instant) {
        return "'" + CH_TIMESTAMP.format(instant.atOffset(ZoneOffset.UTC)) + "'";
    }

    private static String escape(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}

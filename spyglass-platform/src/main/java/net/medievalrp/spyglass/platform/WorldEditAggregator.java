package net.medievalrp.spyglass.platform;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.crusalis.platform.sdk.api.JsonWriter;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.util.BlockLocation;

/**
 * Turns a WorldEdit or FAWE operation's thousands of block records into one
 * {@code spyglass.worldedit} event (§6.2).
 *
 * <p>This is not an optimisation. A single {@code //set stone} over a 100,000-block
 * region commits 100,000 Spyglass records in a few seconds; published one-to-one that
 * is one staff action costing more events than an hour of the whole network, and it
 * would be charged against the hot path's ring on the main thread. The acceptance
 * criterion is literally "a 100,000-block FAWE operation produces one event".
 *
 * <p>Those per-block records are still written by Spyglass itself, so the rollback and
 * the inspector wand are unaffected — this only decides what crosses to Crusalis.
 *
 * <h2>How an operation's end is detected</h2>
 *
 * <p>There is no "operation finished" signal in the records themselves, so one is
 * inferred: records are accumulated per (actor, world) and flushed when no record has
 * arrived for {@link #IDLE_FLUSH_MILLIS}, when the block count passes
 * {@link #MAX_BLOCKS_PER_EVENT}, or when the integration shuts down. {@link #tick()}
 * is what notices the idle case; the plugin calls it once a second.
 */
final class WorldEditAggregator {

    /**
     * How long an operation has to be quiet before it counts as finished. A second is
     * long enough to bridge FAWE's chunk-by-chunk commits — which arrive in bursts with
     * gaps between them — and short enough that the event lands while the operator is
     * still standing there, which is when a watching moderator wants it.
     */
    static final long IDLE_FLUSH_MILLIS = 1_500;

    /**
     * A cap so one pending aggregate cannot grow without bound if an operator runs a
     * script for an hour. It splits the operation into several events rather than
     * losing any of it; the count still adds up.
     */
    static final int MAX_BLOCKS_PER_EVENT = 5_000_000;

    private final PlatformPublisher publisher;
    private final Map<String, Pending> pending = new HashMap<>();

    WorldEditAggregator(PlatformPublisher publisher) {
        this.publisher = publisher;
    }

    /** Whether this record belongs to a WorldEdit/FAWE operation rather than a player. */
    static boolean isWorldEdit(EventRecord record) {
        Origin origin = record.origin();
        if (origin == null || origin.kind() == null) {
            return false;
        }
        return Origin.WORLDEDIT.equals(origin.kind()) || Origin.FAWE.equals(origin.kind());
    }

    /**
     * Accumulates one record.
     *
     * @return true always, because the caller must not then publish the record itself
     */
    boolean accumulate(EventRecord record, long nowMillis) {
        BlockLocation location = record.location();
        UUID actor = record.source() == null ? null : record.source().playerId();
        String world = location == null ? "" : String.valueOf(location.worldName());
        String key = (actor == null ? "-" : actor.toString()) + '@' + world;

        Pending p = pending.get(key);
        if (p == null) {
            p = new Pending(actor, world, record.origin() == null ? "" : record.origin().kind());
            pending.put(key, p);
        }
        p.add(location, nowMillis);
        if (p.blocks >= MAX_BLOCKS_PER_EVENT) {
            flush(key, p);
        }
        return true;
    }

    /** Flushes operations that have gone quiet. Called about once a second. */
    void tick() {
        flushIdle(System.currentTimeMillis());
    }

    void flushIdle(long nowMillis) {
        pending.entrySet().removeIf(entry -> {
            Pending p = entry.getValue();
            if (nowMillis - p.lastMillis < IDLE_FLUSH_MILLIS) {
                return false;
            }
            emit(p);
            return true;
        });
    }

    /** Flushes everything, whatever its age. For shutdown. */
    void flushAll() {
        pending.values().forEach(this::emit);
        pending.clear();
    }

    private void flush(String key, Pending p) {
        emit(p);
        pending.remove(key);
    }

    private void emit(Pending p) {
        // Every record in the operation could have had a null location (nothing in the
        // API forbids it), which would leave the box at its sentinels and make the
        // centre arithmetic overflow. Collapse it to the origin instead.
        p.normalizeBox();
        publisher.publishAggregate("spyglass.worldedit", slot -> {
            if (p.actor != null) {
                slot.uuid(p.actor.getMostSignificantBits(), p.actor.getLeastSignificantBits());
            }
            JsonWriter payload = slot.payload();
            payload.str("world", p.world);
            // The region's centre, so the event has a location a map can show. The
            // bounding box is in data, which is what a query actually filters on.
            payload.i32("x", (p.minX + p.maxX) / 2);
            payload.i32("y", (p.minY + p.maxY) / 2);
            payload.i32("z", (p.minZ + p.maxZ) / 2);
            payload.str("cause", "#" + p.originKind);
            payload.str("target", "");
            payload.i32("amount", p.blocks > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) p.blocks);
            // The command is not in the records. Spyglass knows it from its WorldEdit
            // subscriber, not from the commit path, and guessing it would be worse than
            // leaving it empty: the command is in spyglass.command anyway, from the same
            // player a second earlier.
            payload.str("text", "");
            payload.object("data");
            payload.object("min");
            payload.i32("x", p.minX);
            payload.i32("y", p.minY);
            payload.i32("z", p.minZ);
            payload.endObject();
            payload.object("max");
            payload.i32("x", p.maxX);
            payload.i32("y", p.maxY);
            payload.i32("z", p.maxZ);
            payload.endObject();
            payload.i64("blocks", p.blocks);
            payload.i64("duration_ms", Math.max(0, p.lastMillis - p.firstMillis));
            payload.endObject();
        });
    }

    /** Visible for the test. */
    int pendingOperations() {
        return pending.size();
    }

    private static final class Pending {
        private final UUID actor;
        private final String world;
        private final String originKind;
        private long blocks;
        private long firstMillis;
        private long lastMillis;
        private int minX = Integer.MAX_VALUE;
        private int minY = Integer.MAX_VALUE;
        private int minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private int maxZ = Integer.MIN_VALUE;

        Pending(UUID actor, String world, String originKind) {
            this.actor = actor;
            this.world = world;
            this.originKind = originKind == null ? "" : originKind;
        }

        void normalizeBox() {
            if (maxX < minX) {
                minX = maxX = minY = maxY = minZ = maxZ = 0;
            }
        }

        void add(BlockLocation location, long nowMillis) {
            if (blocks == 0) {
                firstMillis = nowMillis;
            }
            blocks++;
            lastMillis = nowMillis;
            if (location == null) {
                return;
            }
            minX = Math.min(minX, location.x());
            minY = Math.min(minY, location.y());
            minZ = Math.min(minZ, location.z());
            maxX = Math.max(maxX, location.x());
            maxY = Math.max(maxY, location.y());
            maxZ = Math.max(maxZ, location.z());
        }
    }
}

package net.medievalrp.spyglass.plugin.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bson.codecs.pojo.annotations.BsonProperty;

/**
 * A player inventory captured at an instant, persisted to the
 * {@link PlayerSnapshotStore}. Snapshots are the only honest source for "what
 * was this player carrying at time T" - the event log does not cover crafting,
 * consuming, trades, or plugin-given items, so no delta chain can reconstruct
 * an inventory (#341).
 *
 * <p>Empty slots are absent from {@code slots}. {@code contentHash} is the
 * capture service's 64-bit digest over the occupied (slot, count, payload)
 * tuples; an unchanged hash means the sweep skips the write entirely.
 */
public record PlayerSnapshot(
        @BsonProperty("_id") UUID id,
        UUID player,
        String playerName,
        Instant capturedAt,
        String cause,
        long contentHash,
        List<SnapshotSlot> slots) {

    /** Capture causes, stored as short strings so new ones need no migration. */
    public static final String CAUSE_JOIN = "join";
    public static final String CAUSE_QUIT = "quit";
    public static final String CAUSE_DEATH = "death";
    public static final String CAUSE_WORLD_CHANGE = "world-change";
    public static final String CAUSE_SWEEP = "sweep";

    public PlayerSnapshot {
        slots = slots == null ? List.of() : List.copyOf(slots);
    }
}

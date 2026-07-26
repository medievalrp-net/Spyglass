package net.medievalrp.spyglass.plugin.util;

import net.medievalrp.spyglass.api.capture.BlockSnapshots;
import net.medievalrp.spyglass.api.capture.BlockSnapshots.RawCapture;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;

/**
 * Plugin-internal fast path over {@link BlockSnapshots#captureRaw}.
 *
 * <p>Not part of the public API on purpose: the learned cache below is
 * main-thread confined and exists purely to shave allocation off the break and
 * place hot paths. Third-party callers want {@link BlockSnapshots#capture}
 * or {@link BlockSnapshots#captureRaw} instead, which are always correct
 * and carry no threading precondition beyond running on the main thread.
 *
 * <p>For the overwhelming majority of break/place volume - plain terrain and
 * building blocks - building a full CraftBlockState (an allocation plus a
 * tile-entity chunk lookup) is wasted: captureRaw reads only the material and
 * the immutable BlockData from it. {@link #captureRawCached} grabs just that
 * BlockData (the irreducible main-thread cost) and skips getState() for any
 * material PROVEN to produce a non-data-bearing state.
 */
public final class BlockCaptureCache {

    private static final byte UNKNOWN = 0;
    private static final byte PLAIN = 1;
    private static final byte DATA_BEARING = 2;

    private BlockCaptureCache() {
    }

    /**
     * Per-{@link Material} plainness verdict, indexed by {@link Material#ordinal()}:
     * {@code UNKNOWN}, {@code PLAIN} (no tile-entity data
     * {@link BlockSnapshots#captureRaw} would extract) or {@code DATA_BEARING}
     * (a Container / Sign / Banner / Jukebox / DecoratedPot).
     *
     * <p>The verdict is learned lazily from the authoritative {@link BlockState}
     * itself: the first event for a material always runs the full
     * {@link BlockSnapshots#captureRaw} path and only then records what that
     * state turned out to be. A material therefore can never be misclassified
     * into the fast path - the fast path is only taken after the plugin has
     * seen, on this exact server, that the material's state is not
     * data-bearing. This is strictly safer than a hand-maintained allowlist
     * (which a new game version could make wrong) while needing zero
     * per-version maintenance.
     *
     * <p>Bukkit block events fire on the main thread and
     * {@link BlockSnapshots#captureRaw} is already documented as
     * main-thread-only, so this array is main-thread confined and needs no
     * synchronization. (A {@code byte} write is atomic regardless; the worst a
     * hypothetical race could do is re-learn the same verdict - never a wrong
     * one, since every learn reads a real state.)
     */
    private static final byte[] PLAINNESS = new byte[Material.values().length];

    /**
     * {@link BlockSnapshots#captureRaw} for callers holding a live
     * {@link Block} (the break and place-after hot paths). For a material
     * proven {@code PLAIN} this skips the {@link Block#getState()}
     * CraftBlockState construction and its tile-entity lookup, grabbing only
     * the immutable {@link BlockData} every snapshot needs. For anything not
     * yet proven plain it falls back to {@code getState()} +
     * {@link BlockSnapshots#captureRaw} - byte-for-byte the original behavior -
     * and learns the verdict for next time.
     *
     * <p><b>Correctness:</b> a misclassified container would silently lose its
     * contents and break rollback for it, so the fast path is gated on the
     * plugin having itself observed a non-data-bearing state for this material.
     * {@link BlockSnapshots#isDataBearing} MUST mirror the tile-entity types
     * special-cased in {@code captureRaw}; see its note.
     */
    public static RawCapture captureRawCached(Block block) {
        // The grab: one chunk read + an immutable, world-detached BlockData copy.
        // This is unavoidable on the main thread and is needed by every snapshot.
        BlockData data = block.getBlockData();
        Material type = data.getMaterial();
        if (PLAINNESS[type.ordinal()] == PLAIN) {
            return BlockSnapshots.plainCapture(type, data);
        }
        // Unknown or known-data-bearing: take the authoritative full path. On the
        // first sighting of a material, learn its verdict from the real state.
        BlockState state = block.getState();
        if (PLAINNESS[type.ordinal()] == UNKNOWN) {
            PLAINNESS[type.ordinal()] =
                    BlockSnapshots.isDataBearing(state) ? DATA_BEARING : PLAIN;
        }
        return BlockSnapshots.captureRaw(state);
    }

    /** Visible for tests: clear the learned plainness verdicts. */
    public static void resetPlainnessCache() {
        java.util.Arrays.fill(PLAINNESS, UNKNOWN);
    }
}

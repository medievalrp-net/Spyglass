package net.medievalrp.spyglass.api.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.StoredItem;
import org.bukkit.Material;
import org.bukkit.block.Banner;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.ItemStack;

/**
 * Builds a {@link BlockSnapshot} from a live Bukkit block.
 *
 * <p>{@link BlockSnapshot} is a required argument to {@code BlockBreakRecord}
 * and {@code BlockPlaceRecord}, so any plugin recording its own block events
 * needs a way to produce one. This is that way. Constructing the record by
 * hand is possible but drops the tile-entity payload (container contents, sign
 * text, banner patterns, jukebox disc, pot sherds), which is exactly what
 * rollback needs to restore the block faithfully.
 *
 * <h2>Threading</h2>
 *
 * {@link #capture} reads live world state and MUST run on the main thread.
 * For bulk work, split it: call {@link #captureRaw} on the main thread and
 * {@link #finishCapture} off it. The two together are equivalent to
 * {@link #capture} and yield an identical snapshot, but the expensive halves
 * (item serialisation, block-data stringification) move off the tick.
 *
 * <pre>{@code
 * // simple case, on the main thread
 * BlockSnapshot before = BlockSnapshots.capture(block.getState());
 * sg.record(BlockBreakRecord.of(ctx, "myplugin-break", target,
 *         before, BlockSnapshots.air()));
 *
 * // bulk case: cheap half on the tick, expensive half on a worker
 * var raw = BlockSnapshots.captureRaw(block.getState());   // main thread
 * CompletableFuture.runAsync(() -> {
 *     BlockSnapshot snap = BlockSnapshots.finishCapture(raw);  // any thread
 *     sg.record(...);
 * });
 * }</pre>
 */
public final class BlockSnapshots {

    private BlockSnapshots() {
    }

    /**
     * Capture a full {@link BlockSnapshot} from a (live or snapshot)
     * {@link BlockState}. MUST run on the main thread: it reads live Bukkit
     * state (the jukebox/decorated-pot upgrade reads the live block) and
     * serializes container contents inline.
     *
     * <p>Equivalent to {@code finishCapture(captureRaw(state))}.
     */
    public static BlockSnapshot capture(BlockState state) {
        return finishCapture(captureRaw(state));
    }

    /**
     * Cheap intermediate from {@link #captureRaw}. Holds everything a
     * {@link BlockSnapshot} needs <em>except</em> the serialized item blobs
     * and the blockdata string:
     * <ul>
     *   <li>The container contents and jukebox disc are kept as <b>cloned</b>
     *       {@link ItemStack}s (detached from the live world).</li>
     *   <li>The block's data is carried as the <b>immutable</b> {@link BlockData}
     *       copy returned by {@link BlockState#getBlockData()} - it is already
     *       world-detached and safe to hand across threads. {@link #finishCapture}
     *       calls {@code getAsString()} on it off the main thread, deferring the
     *       string allocation away from the server tick.</li>
     * </ul>
     * {@link #finishCapture} can therefore run both the expensive
     * {@code serializeAsBytes()} AND {@code getAsString()} off-thread.
     */
    public record RawCapture(
            Material type,
            BlockData blockData,           // immutable copy; getAsString() deferred to finishCapture
            ItemStack[] containerContents, // cloned; null when the state isn't a Container
            List<String> signFront,
            List<String> signBack,
            List<String> bannerPatterns,
            ItemStack jukeboxRecord,       // cloned; null when not a jukebox / empty
            List<String> potSherds) {
    }

    /**
     * Main-thread half of {@link #capture}: does every live-Bukkit read (the
     * jukebox/pot upgrade, sign/banner/sherd reads) and <b>clones</b> the
     * container contents and jukebox disc, but does NOT serialize them. The
     * heavy {@code serializeAsBytes()} is deferred to {@link #finishCapture},
     * which is safe to run off-thread because it only touches the cloned,
     * world-detached stacks in the returned {@link RawCapture}.
     */
    public static RawCapture captureRaw(BlockState state) {
        // For tile-entity types where Paper's snapshot BlockState has
        // a detached BlockEntity (level=null, fields not populated),
        // upgrade to the LIVE state. Affects Jukebox (record disc
        // missing from snapshot) and DecoratedPot (sherds map
        // returns empty). The block is still the original tile-entity
        // type at capture time (we haven't broken it yet), so
        // getState(false) returns a fully-populated live wrapper.
        if (state instanceof Jukebox || state instanceof DecoratedPot) {
            try {
                state = state.getBlock().getState(false);
            } catch (Throwable ignored) {
                // Fall through with the snapshot - at worst we lose
                // the disc / sherds (the original bug), nothing else.
            }
        }
        ItemStack[] containerContents = null;
        if (state instanceof Container container) {
            containerContents = cloneContents(container.getSnapshotInventory().getContents());
        }

        List<String> signFront = List.of();
        List<String> signBack = List.of();
        if (state instanceof Sign sign) {
            signFront = lines(sign, Side.FRONT);
            signBack = lines(sign, Side.BACK);
        }

        List<String> bannerPatterns = List.of();
        if (state instanceof Banner banner) {
            bannerPatterns = banner.getPatterns().stream()
                    .map(pattern -> pattern.getColor().name() + ":" + pattern.getPattern().getIdentifier())
                    .toList();
        }

        ItemStack jukeboxRecord = null;
        if (state instanceof Jukebox jukebox) {
            ItemStack record = jukebox.getSnapshotInventory().getItem(0);
            jukeboxRecord = record == null ? null : record.clone();
        }

        List<String> potSherds = List.of();
        if (state instanceof DecoratedPot pot) {
            // 4 sides in declaration order: BACK, LEFT, RIGHT, FRONT.
            // A blank face stores BRICK; on rollback we restore that
            // exact material. Map.get returning null means "no sherd
            // on this face" - fall back to BRICK so the apply round-
            // trips cleanly.
            Map<DecoratedPot.Side, Material> sherdMap = pot.getSherds();
            DecoratedPot.Side[] sides = DecoratedPot.Side.values();
            List<String> names = new ArrayList<>(sides.length);
            for (DecoratedPot.Side side : sides) {
                Material m = sherdMap == null ? null : sherdMap.get(side);
                names.add((m == null ? Material.BRICK : m).name());
            }
            potSherds = List.copyOf(names);
        }

        // Carry the immutable BlockData copy rather than calling getAsString()
        // here on the main thread. getAsString() builds the "minecraft:stone[...]"
        // string and accounts for ~19% of Spyglass's per-event tick cost.
        // BlockData returned by getBlockData() is an immutable, world-detached
        // snapshot - it is safe to hand to finishCapture() on an off-thread.
        return new RawCapture(
                state.getType(),
                state.getBlockData(),
                containerContents,
                signFront,
                signBack,
                bannerPatterns,
                jukeboxRecord,
                potSherds);
    }

    /**
     * The {@link RawCapture} for a block with no tile-entity payload, identical
     * to what {@link #captureRaw} produces for a plain block (empty item / sign /
     * banner / pot lists, null container contents and jukebox disc), without
     * building a {@link BlockState}.
     *
     * <p>Only sound for a material proven not to be {@link #isDataBearing}.
     */
    public static RawCapture plainCapture(Material type, BlockData data) {
        return new RawCapture(type, data, null, List.of(), List.of(), List.of(), null, List.of());
    }

    /**
     * Whether {@link #captureRaw} would extract tile-entity data from this state.
     * This is the safety predicate behind any fast path that skips
     * {@code getState()}: it MUST list exactly the tile-entity types
     * {@link #captureRaw} special-cases. Adding a new branch to
     * {@code captureRaw} without adding it here would let a proven-plain fast
     * path silently drop the new data.
     */
    public static boolean isDataBearing(BlockState state) {
        return state instanceof Container
                || state instanceof Sign
                || state instanceof Banner
                || state instanceof Jukebox
                || state instanceof DecoratedPot;
    }

    /**
     * Off-thread half of {@link #capture}: calls {@code getAsString()} on the
     * immutable {@link BlockData} carried by {@code raw}, serializes the cloned
     * container contents and jukebox disc, and assembles the final
     * {@link BlockSnapshot}. Everything it touches is world-detached, so it is
     * safe to run off the main thread.
     */
    public static BlockSnapshot finishCapture(RawCapture raw) {
        // getAsString() deferred off the main thread.
        String blockDataString = raw.blockData().getAsString();
        List<StoredItem> items = raw.containerContents() == null
                ? List.of()
                : serializeContents(raw.containerContents());
        String jukeboxRecord = ItemSerialization.encode(raw.jukeboxRecord());
        return new BlockSnapshot(
                raw.type(),
                blockDataString,
                items,
                raw.signFront(),
                raw.signBack(),
                raw.bannerPatterns(),
                jukeboxRecord,
                raw.potSherds());
    }

    /**
     * The "broken to air" after-snapshot is the same immutable value on
     * every break, so it's a shared constant rather than a fresh allocation
     * per record. {@link BlockSnapshot} is an immutable record and nothing
     * identity-checks it, so sharing one instance is observably identical to
     * constructing a new one each call - it just skips the per-break
     * allocation (and the compact constructor's {@code List.copyOf} /
     * {@code simple} recompute).
     */
    private static final BlockSnapshot AIR = new BlockSnapshot(Material.AIR, "minecraft:air",
            List.of(), List.of(), List.of(), List.of(), null);

    /** The shared air snapshot, for the after-state of a break. */
    public static BlockSnapshot air() {
        return AIR;
    }

    /**
     * Plain material + blockData snapshot for callers that don't have a
     * {@link BlockState} handy. Inventory / sign / banner / jukebox lists are
     * empty; pass a {@link BlockState} to {@link #capture} when you want that
     * data.
     */
    public static BlockSnapshot of(Material material, String blockData) {
        return new BlockSnapshot(material, blockData,
                List.of(), List.of(), List.of(), List.of(), null);
    }

    /**
     * Resolves a stored material string (e.g. from a legacy MaterialType field
     * or a WorldEdit blockData string) to a Bukkit {@link Material}. Falls back
     * to {@link Material#AIR} on null/blank/unknown input.
     */
    public static Material matchMaterial(String name) {
        if (name == null || name.isBlank()) {
            return Material.AIR;
        }
        Material direct = Material.matchMaterial(name, false);
        if (direct != null) {
            return direct;
        }
        Material legacy = Material.matchMaterial(name, true);
        return legacy != null ? legacy : Material.AIR;
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] out = new ItemStack[contents.length];
        for (int slot = 0; slot < contents.length; slot++) {
            out[slot] = contents[slot] == null ? null : contents[slot].clone();
        }
        return out;
    }

    private static List<StoredItem> serializeContents(ItemStack[] contents) {
        List<StoredItem> items = new ArrayList<>();
        for (int slot = 0; slot < contents.length; slot++) {
            StoredItem item = ItemSerialization.storedItem(slot, contents[slot]);
            if (item != null) {
                items.add(item);
            }
        }
        return List.copyOf(items);
    }

    private static List<String> lines(Sign sign, Side side) {
        return sign.getSide(side).lines().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .map(CaptureText::safeText)
                .toList();
    }
}

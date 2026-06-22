package net.medievalrp.spyglass.plugin.util;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Sign;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.ItemStack;

public final class BlockSnapshots {

    private BlockSnapshots() {
    }

    /**
     * Capture a full {@link BlockSnapshot} from a (live or snapshot)
     * {@link BlockState}. MUST run on the main thread - it reads live Bukkit
     * state (the jukebox/decorated-pot upgrade reads the live block) and
     * serializes container contents inline.
     *
     * <p>Equivalent to {@code finishCapture(captureRaw(state))}: for the bulk
     * paths (explosions) that want to keep the heavy item serialization off the
     * tick, call {@link #captureRaw} on the main thread and {@link #finishCapture}
     * off it. Both yield an identical snapshot.
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
     *       string allocation away from the server tick (#154).</li>
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

    // ---- stage 2: skip getState() for blocks that carry no tile-entity data ----
    //
    // For the overwhelming majority of break/place volume - plain terrain and
    // building blocks - building a full CraftBlockState (an allocation plus a
    // tile-entity chunk lookup) is wasted: captureRaw reads only the material
    // and the immutable BlockData from it. captureRawCached grabs just that
    // BlockData (the irreducible main-thread cost) and skips getState() for any
    // material PROVEN to produce a non-data-bearing state.

    private static final byte UNKNOWN = 0;
    private static final byte PLAIN = 1;
    private static final byte DATA_BEARING = 2;

    /**
     * Per-{@link Material} plainness verdict, indexed by {@link Material#ordinal()}:
     * {@code UNKNOWN}, {@code PLAIN} (no tile-entity data {@link #captureRaw}
     * would extract) or {@code DATA_BEARING} (a Container / Sign / Banner /
     * Jukebox / DecoratedPot).
     *
     * <p>The verdict is learned lazily from the authoritative {@link BlockState}
     * itself: the first event for a material always runs the full
     * {@link #captureRaw} path and only then records what that state turned out
     * to be. A material therefore can never be misclassified into the fast path -
     * the fast path is only taken after the plugin has seen, on this exact
     * server, that the material's state is not data-bearing. This is strictly
     * safer than a hand-maintained allowlist (which a new game version could make
     * wrong) while needing zero per-version maintenance.
     *
     * <p>Bukkit block events fire on the main thread and {@link #captureRaw} is
     * already documented as main-thread-only, so this array is main-thread
     * confined and needs no synchronization. (A {@code byte} write is atomic
     * regardless; the worst a hypothetical race could do is re-learn the same
     * verdict - never a wrong one, since every learn reads a real state.)
     */
    private static final byte[] PLAINNESS = new byte[Material.values().length];

    /**
     * {@link #captureRaw} for callers holding a live {@link Block} (the break and
     * place-after hot paths). For a material proven {@link #PLAIN} this skips the
     * {@link Block#getState()} CraftBlockState construction and its tile-entity
     * lookup, grabbing only the immutable {@link BlockData} every snapshot needs.
     * For anything not yet proven plain it falls back to {@code getState()} +
     * {@link #captureRaw} - byte-for-byte the original behavior - and learns the
     * verdict for next time.
     *
     * <p><b>Correctness:</b> a misclassified container would silently lose its
     * contents and break rollback for it, so the fast path is gated on the
     * plugin having itself observed a non-data-bearing state for this material.
     * {@link #isDataBearing} MUST mirror the tile-entity types special-cased in
     * {@link #captureRaw}; see its note.
     */
    public static RawCapture captureRawCached(Block block) {
        // The grab: one chunk read + an immutable, world-detached BlockData copy.
        // This is unavoidable on the main thread and is needed by every snapshot.
        BlockData data = block.getBlockData();
        Material type = data.getMaterial();
        if (PLAINNESS[type.ordinal()] == PLAIN) {
            return plainCapture(type, data);
        }
        // Unknown or known-data-bearing: take the authoritative full path. On the
        // first sighting of a material, learn its verdict from the real state.
        BlockState state = block.getState();
        if (PLAINNESS[type.ordinal()] == UNKNOWN) {
            PLAINNESS[type.ordinal()] = isDataBearing(state) ? DATA_BEARING : PLAIN;
        }
        return captureRaw(state);
    }

    /**
     * The {@link RawCapture} for a block with no tile-entity payload - identical
     * to what {@link #captureRaw} produces for a plain block (empty item / sign /
     * banner / pot lists, null container contents and jukebox disc), without
     * building a {@link BlockState}.
     */
    private static RawCapture plainCapture(Material type, BlockData data) {
        return new RawCapture(type, data, null, List.of(), List.of(), List.of(), null, List.of());
    }

    /**
     * Whether {@link #captureRaw} would extract tile-entity data from this state.
     * This is the safety predicate behind {@link #captureRawCached}: it MUST list
     * exactly the tile-entity types {@link #captureRaw} special-cases. Adding a
     * new branch to {@code captureRaw} without adding it here would let the
     * proven-plain fast path silently drop the new data.
     */
    static boolean isDataBearing(BlockState state) {
        return state instanceof Container
                || state instanceof Sign
                || state instanceof Banner
                || state instanceof Jukebox
                || state instanceof DecoratedPot;
    }

    /** Visible for tests: clear the learned plainness verdicts. */
    static void resetPlainnessCache() {
        java.util.Arrays.fill(PLAINNESS, UNKNOWN);
    }

    /**
     * Off-thread half of {@link #capture}: calls {@code getAsString()} on the
     * immutable {@link BlockData} carried by {@code raw}, serializes the cloned
     * container contents and jukebox disc, and assembles the final
     * {@link BlockSnapshot}. Everything it touches is world-detached, so it is
     * safe to run off the main thread.
     *
     * <p>{@code getAsString()} is the deferred call (#154): it was previously
     * called in {@link #captureRaw} on the server tick and accounts for ~19% of
     * Spyglass's per-event main-thread cost for plain break/place events. Moving
     * it here yields the same string with no behavior change - just a different
     * thread.
     */
    public static BlockSnapshot finishCapture(RawCapture raw) {
        // getAsString() deferred off the main thread (#154).
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
     * {@code simple} recompute) across the ~12 break call sites.
     */
    private static final BlockSnapshot AIR = new BlockSnapshot(Material.AIR, "minecraft:air",
            List.of(), List.of(), List.of(), List.of(), null);

    public static BlockSnapshot air() {
        return AIR;
    }

    /**
     * Plain material + blockData snapshot for callers that don't have a
     * {@link BlockState} handy (FAWE chunk diff, brush/vault delayed checks).
     * Inventory / sign / banner / jukebox lists are empty - callers pass a
     * {@link BlockState} to {@link #capture} when they want that data.
     */
    public static BlockSnapshot of(Material material, String blockData) {
        return new BlockSnapshot(material, blockData,
                List.of(), List.of(), List.of(), List.of(), null);
    }

    /**
     * Resolves a stored material string (e.g. from a v1 MaterialType field or
     * a FAWE blockData string) to a Bukkit {@link Material}. Falls back to
     * {@link Material#AIR} on null/blank/unknown input.
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
                .map(RecordingSupport::safeText)
                .toList();
    }
}

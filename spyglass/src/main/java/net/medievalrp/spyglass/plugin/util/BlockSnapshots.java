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
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Sign;
import org.bukkit.block.banner.Pattern;
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
     * {@link BlockSnapshot} needs <em>except</em> the serialized item blobs:
     * the container contents and jukebox disc are kept as <b>cloned</b>
     * {@link ItemStack}s (detached from the live world), so {@link #finishCapture}
     * can run the expensive {@code serializeAsBytes()} off the main thread.
     */
    public record RawCapture(
            Material type,
            String blockData,
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

        return new RawCapture(
                state.getType(),
                state.getBlockData().getAsString(),
                containerContents,
                signFront,
                signBack,
                bannerPatterns,
                jukeboxRecord,
                potSherds);
    }

    /**
     * Off-thread half of {@link #capture}: serialize the cloned container
     * contents and jukebox disc held in {@code raw} into the final
     * {@link BlockSnapshot}. Touches only world-detached cloned stacks, so it
     * is safe off the main thread.
     */
    public static BlockSnapshot finishCapture(RawCapture raw) {
        List<StoredItem> items = raw.containerContents() == null
                ? List.of()
                : serializeContents(raw.containerContents());
        String jukeboxRecord = ItemSerialization.encode(raw.jukeboxRecord());
        return new BlockSnapshot(
                raw.type(),
                raw.blockData(),
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

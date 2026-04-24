package net.medievalrp.spyglass.plugin.listener.modern;

import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.medievalrp.spyglass.api.event.BlockUseRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.ApiStatus;

/**
 * Records right-clicks on CraftBook machine signs — signs whose
 * second line is a bracketed mechanism tag like {@code [Door]},
 * {@code [Lift Up]}, {@code [Area]}. Lets operators audit who
 * triggered which mechanism via {@code a:useSign trg:door}.
 *
 * <p>Only registered when CraftBook itself is enabled on the server,
 * so the listener is a no-op on vanilla deployments. The bracket
 * check filters out ordinary signs, which would otherwise flood the
 * log with every sign-read.
 *
 * <p>The emitted target is {@code "<MATERIAL> <bracket-tag>"} — e.g.
 * {@code "OAK_WALL_SIGN [Door]"} — so the CraftBook tag is
 * grep-searchable without stripping material names from the record.
 */
@ApiStatus.Internal
public final class CraftBookSignListener implements RecordingListener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Recorder recorder;
    private final RecordingSupport support;

    public CraftBookSignListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    /** {@code true} only if CraftBook is present AND enabled right now. */
    public static boolean isCraftBookEnabled() {
        var plugin = Bukkit.getPluginManager().getPlugin("CraftBook");
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public Set<String> events() {
        return Set.of("useSign");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !(block.getState(false) instanceof Sign sign)) {
            return;
        }
        String bracket = firstBracketTag(sign);
        if (bracket == null) {
            return;
        }
        String target = block.getType().name() + " " + bracket;
        BlockLocation location = BlockLocations.fromLocation(block.getLocation());
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        // Canonical constructor because BlockUseRecord.of hard-codes
        // event="use"; we need the event name to be "useSign".
        recorder.record(new BlockUseRecord(
                ctx.id(), ctx.schemaVersion(), "useSign",
                ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(),
                target));
    }

    /**
     * CraftBook machine signs put the mechanism tag on line 2 (index 1)
     * of the front side, wrapped in brackets — {@code [Door]},
     * {@code [Lift Up]}. Return the full bracketed token (with
     * brackets) if present, else {@code null}.
     */
    private static String firstBracketTag(Sign sign) {
        var lines = sign.getSide(Side.FRONT).lines();
        if (lines.size() < 2) {
            return null;
        }
        Component line = lines.get(1);
        if (line == null) {
            return null;
        }
        String plain = PLAIN.serialize(line).trim();
        if (plain.length() < 3) {
            return null;
        }
        if (plain.charAt(0) == '[' && plain.charAt(plain.length() - 1) == ']') {
            return plain;
        }
        return null;
    }
}

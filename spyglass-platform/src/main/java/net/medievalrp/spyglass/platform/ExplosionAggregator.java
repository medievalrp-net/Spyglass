package net.medievalrp.spyglass.platform;

import net.crusalis.platform.sdk.api.JsonWriter;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.List;
import java.util.UUID;

/**
 * One {@code spyglass.explosion} event per explosion (§6.2).
 *
 * <p>Spyglass records an explosion as one break record per destroyed block, which is
 * the right shape for a rollback and the wrong shape for "a creeper went off here and
 * took forty blocks with it". The per-block records still publish as
 * {@code spyglass.block_break} with the cause in the payload — that is what the cause
 * field is for — and this adds the one event that says the explosion happened, with the
 * count and the radius that no individual block record carries.
 *
 * <p>It listens to Bukkit directly rather than to committed records, at
 * {@link EventPriority#MONITOR} and ignoring cancelled events, because the block list
 * is only intact while the event is being processed: afterwards the blocks are air.
 */
final class ExplosionAggregator implements Listener {

    private final PlatformPublisher publisher;

    ExplosionAggregator(PlatformPublisher publisher) {
        this.publisher = publisher;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        // No actor: Bukkit does not say who primed the TNT, and Spyglass's own
        // attribution lives in the per-block records this event sits beside.
        String cause = "#" + (entity == null ? "explosion"
                : entity.getType().name().toLowerCase(java.util.Locale.ROOT));
        publish(event.getLocation(), event.blockList(), cause, null, event.getYield());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        Block block = event.getBlock();
        String cause = "#" + (block == null ? "explosion"
                : block.getType().name().toLowerCase(java.util.Locale.ROOT));
        publish(block == null ? null : block.getLocation(), event.blockList(), cause, null,
                event.getYield());
    }

    private void publish(Location epicentre, List<Block> blocks, String cause, UUID actor,
                         float yield) {
        if (epicentre == null) {
            return;
        }
        final int size = blocks == null ? 0 : blocks.size();
        final int radius = radiusOf(epicentre, blocks);
        final String world = epicentre.getWorld() == null ? "" : epicentre.getWorld().getName();
        final int x = epicentre.getBlockX();
        final int y = epicentre.getBlockY();
        final int z = epicentre.getBlockZ();

        publisher.publishAggregate("spyglass.explosion", slot -> {
            if (actor != null) {
                slot.uuid(actor.getMostSignificantBits(), actor.getLeastSignificantBits());
            }
            JsonWriter payload = slot.payload();
            payload.str("world", world);
            payload.i32("x", x);
            payload.i32("y", y);
            payload.i32("z", z);
            payload.str("cause", cause);
            payload.str("target", "");
            // Blocks destroyed, so a sum over the column is blocks and not explosions.
            payload.i32("amount", size);
            payload.str("text", "");
            payload.object("data");
            // Measured from the block list rather than taken from the event, which does
            // not carry one: Bukkit gives a yield, not a radius, and the distance to the
            // furthest destroyed block is the number a reader of this event wants.
            payload.i32("radius", radius);
            payload.f64("yield", yield);
            payload.endObject();
        });
    }

    /** The Chebyshev distance from the epicentre to the furthest destroyed block. */
    private static int radiusOf(Location epicentre, List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return 0;
        }
        int radius = 0;
        for (Block block : blocks) {
            int dx = Math.abs(block.getX() - epicentre.getBlockX());
            int dy = Math.abs(block.getY() - epicentre.getBlockY());
            int dz = Math.abs(block.getZ() - epicentre.getBlockZ());
            radius = Math.max(radius, Math.max(dx, Math.max(dy, dz)));
        }
        return radius;
    }
}

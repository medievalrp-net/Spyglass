package net.medievalrp.spyglass.plugin.listener.entity;

import java.util.Set;
import net.medievalrp.spyglass.api.event.EntityMountRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDismountEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class EntityDismountListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public EntityDismountListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("dismount");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDismount(EntityDismountEvent event) {
        Entity rider = event.getEntity();
        Entity mount = event.getDismounted();
        BlockLocation location = BlockLocations.fromLocation(mount.getLocation());
        String mountType = mount.getType().getKey().getKey();

        Origin origin;
        Source source;
        if (rider instanceof Player player) {
            origin = support.playerOrigin();
            source = support.playerSource(player);
        } else {
            origin = support.environmentOrigin("dismount:" + rider.getType().getKey().getKey());
            source = support.entitySource(rider.getUniqueId(), rider.getType().getKey().getKey());
        }

        RecordContext ctx = support.context(origin, source, location);
        recorder.record(EntityMountRecord.of(ctx, "dismount", mountType, mountType, mount.getUniqueId(), true));
    }
}

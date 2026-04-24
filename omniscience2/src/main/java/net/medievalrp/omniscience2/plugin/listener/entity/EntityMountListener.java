package net.medievalrp.omniscience2.plugin.listener.entity;

import java.util.Set;
import net.medievalrp.omniscience2.api.event.EntityMountRecord;
import net.medievalrp.omniscience2.api.event.Origin;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.event.Source;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.RecordingSupport;
import net.medievalrp.omniscience2.plugin.listener.RecordingListener;
import net.medievalrp.omniscience2.plugin.pipeline.Recorder;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityMountEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class EntityMountListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public EntityMountListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("mount");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityMount(EntityMountEvent event) {
        Entity rider = event.getEntity();
        Entity mount = event.getMount();
        BlockLocation location = BlockLocations.fromLocation(mount.getLocation());
        String mountType = mount.getType().getKey().getKey();

        Origin origin;
        Source source;
        if (rider instanceof Player player) {
            origin = support.playerOrigin();
            source = support.playerSource(player);
        } else {
            origin = support.environmentOrigin("mount:" + rider.getType().getKey().getKey());
            source = support.entitySource(rider.getUniqueId(), rider.getType().getKey().getKey());
        }

        RecordContext ctx = support.context(origin, source, location);
        recorder.record(EntityMountRecord.of(ctx, "mount", mountType, mountType, mount.getUniqueId(), false));
    }
}

package net.medievalrp.spyglass.plugin.listener.entity;

import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.EntityMountRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDismountEvent;

public final class EntityDismountExtractor implements EventExtractor<EntityDismountEvent, EntityMountRecord> {

    private final ExtractorSupport support;

    public EntityDismountExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<EntityDismountEvent> eventType() {
        return EntityDismountEvent.class;
    }

    @Override
    public Stream<EntityMountRecord> extract(EntityDismountEvent event) {
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
        return Stream.of(EntityMountRecord.of(ctx, "dismount", mountType, mountType, mount.getUniqueId(), true));
    }
}

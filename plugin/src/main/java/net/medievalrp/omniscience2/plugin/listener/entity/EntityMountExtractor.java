package net.medievalrp.omniscience2.plugin.listener.entity;

import java.util.Set;
import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.EntityMountRecord;
import net.medievalrp.omniscience2.api.event.Origin;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.event.Source;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityMountEvent;

public final class EntityMountExtractor implements EventExtractor<EntityMountEvent, EntityMountRecord> {

    private final ExtractorSupport support;

    public EntityMountExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<EntityMountEvent> eventType() {
        return EntityMountEvent.class;
    }

    @Override
    public Set<String> events() {
        return Set.of("mount");
    }

    @Override
    public Stream<EntityMountRecord> extract(EntityMountEvent event) {
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
        return Stream.of(EntityMountRecord.of(ctx, "mount", mountType, mountType, mount.getUniqueId(), false));
    }
}

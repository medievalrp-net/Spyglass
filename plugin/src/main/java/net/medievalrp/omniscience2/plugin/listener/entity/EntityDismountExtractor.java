package net.medievalrp.omniscience2.plugin.listener.entity;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.EntityMountRecord;
import net.medievalrp.omniscience2.api.event.Origin;
import net.medievalrp.omniscience2.api.event.Source;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
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
        Instant occurred = support.now();
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

        return Stream.of(new EntityMountRecord(
                support.newId(),
                1,
                "dismount",
                occurred,
                support.expiresAt(occurred),
                origin,
                source,
                location,
                mountType,
                mountType,
                mount.getUniqueId(),
                true));
    }
}

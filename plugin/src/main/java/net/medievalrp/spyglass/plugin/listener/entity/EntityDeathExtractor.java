package net.medievalrp.spyglass.plugin.listener.entity;

import java.util.Base64;
import java.util.Set;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;

public final class EntityDeathExtractor implements EventExtractor<EntityDeathEvent, EntityDeathRecord> {

    private final ExtractorSupport support;

    public EntityDeathExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<EntityDeathEvent> eventType() {
        return EntityDeathEvent.class;
    }

    @Override
    public Set<String> events() {
        return Set.of("death");
    }

    @Override
    public Stream<EntityDeathRecord> extract(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        BlockLocation location = BlockLocations.fromLocation(victim.getLocation());
        String entityType = victim.getType().getKey().getKey();

        Origin origin;
        Source source;
        String killerType = null;
        if (victim.getKiller() instanceof Player killer) {
            origin = support.playerOrigin();
            source = support.playerSource(killer);
            killerType = "player";
        } else if (victim.getLastDamageCause() != null) {
            String cause = victim.getLastDamageCause().getCause().name();
            origin = support.environmentOrigin("death:" + cause);
            source = support.environmentSource("death:" + cause);
            killerType = cause;
        } else {
            origin = support.environmentOrigin("death");
            source = support.environmentSource("death");
        }

        String damageCause = victim.getLastDamageCause() != null
                ? victim.getLastDamageCause().getCause().name()
                : "UNKNOWN";

        String nbt = serializeEntity(victim);

        RecordContext ctx = support.context(origin, source, location);
        return Stream.of(EntityDeathRecord.of(ctx, entityType, entityType, victim.getUniqueId(),
                killerType, damageCause, nbt));
    }

    private static String serializeEntity(LivingEntity entity) {
        try {
            byte[] bytes = Bukkit.getUnsafe().serializeEntity(entity);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Throwable thrown) {
            return null;
        }
    }
}

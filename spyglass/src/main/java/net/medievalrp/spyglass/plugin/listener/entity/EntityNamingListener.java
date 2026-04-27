package net.medievalrp.spyglass.plugin.listener.entity;

import java.util.Objects;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.medievalrp.spyglass.api.event.EntityNameRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.Material;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.ApiStatus;

/**
 * Logs when a player uses a name tag on an entity. Filters to
 * non-player {@link LivingEntity} targets (same as v1) — player-vs-
 * player name-tag interactions don't work in vanilla anyway.
 *
 * <p>Skips name-tag applications that would be a no-op (the tag's
 * display name equals the entity's current custom name). Captures both
 * the old custom name (may be null) and the new name so the record is
 * roll-back-friendly in principle.
 */
@ApiStatus.Internal
public final class EntityNamingListener implements RecordingListener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Recorder recorder;
    private final RecordingSupport support;

    public EntityNamingListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("named");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        if (!(target instanceof LivingEntity living) || target instanceof Player || target instanceof EnderDragon) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItem(
                event.getHand() == EquipmentSlot.OFF_HAND
                        ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND);
        if (hand == null || hand.getType() != Material.NAME_TAG) {
            return;
        }
        ItemMeta meta = hand.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return;
        }
        String newName = RecordingSupport.safeText(PLAIN.serialize(Objects.requireNonNullElse(
                meta.displayName(), Component.empty())));
        if (newName.isBlank()) {
            return;
        }
        Component currentCustomName = living.customName();
        String oldName = currentCustomName == null
                ? null
                : RecordingSupport.safeText(PLAIN.serialize(currentCustomName));
        if (newName.equals(oldName)) {
            return;
        }
        String entityType = living.getType().getKey().getKey();
        BlockLocation location = BlockLocations.fromLocation(living.getLocation());
        RecordContext ctx = support.playerContext(player, location);
        recorder.record(EntityNameRecord.of(ctx,
                entityType, entityType, living.getUniqueId(), oldName, newName));
    }
}

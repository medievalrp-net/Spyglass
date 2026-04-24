package net.medievalrp.spyglass.api.event;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for event-name -> record-type mapping. The plugin's
 * storage layer uses this to decide which Mongo collection/codec to decode a
 * query result with; the plugin's command layer uses the key set as the
 * authoritative list of event names known to the build.
 *
 * <p>Adding a new event name means: (1) declare it here, (2) register an
 * extractor that emits the matching record type, (3) add a default setting
 * in the plugin's {@code config.conf}. Mirroring two of those three is
 * unavoidable — the config knob, and the extractor that knows which Bukkit
 * event to hook. The name -> record type map no longer needs a third copy
 * inside the storage layer.
 */
public final class EventCatalog {

    private static final Map<String, Class<? extends EventRecord>> TYPES;

    static {
        Map<String, Class<? extends EventRecord>> m = new LinkedHashMap<>();
        m.put("break", BlockBreakRecord.class);
        m.put("place", BlockPlaceRecord.class);
        m.put("say", ChatRecord.class);
        m.put("command", CommandRecord.class);
        m.put("join", JoinRecord.class);
        m.put("quit", QuitRecord.class);
        m.put("deposit", ContainerDepositRecord.class);
        m.put("withdraw", ContainerWithdrawRecord.class);
        m.put("open", ContainerInteractRecord.class);
        m.put("close", ContainerInteractRecord.class);
        m.put("shulker-open", ContainerInteractRecord.class);
        m.put("shulker-close", ContainerInteractRecord.class);
        m.put("use", BlockUseRecord.class);
        m.put("decay", BlockBreakRecord.class);
        m.put("form", BlockPlaceRecord.class);
        m.put("grow", BlockPlaceRecord.class);
        m.put("ignite", BlockPlaceRecord.class);
        m.put("drop", ItemDropRecord.class);
        m.put("pickup", ItemPickupRecord.class);
        m.put("clone", ItemPickupRecord.class);
        m.put("teleport", TeleportRecord.class);
        m.put("death", EntityDeathRecord.class);
        m.put("hit", EntityHitRecord.class);
        m.put("shot", EntityHitRecord.class);
        m.put("mount", EntityMountRecord.class);
        m.put("dismount", EntityMountRecord.class);
        m.put("named", EntityNameRecord.class);
        m.put("entity-deposit", ContainerDepositRecord.class);
        m.put("entity-withdraw", ContainerWithdrawRecord.class);
        m.put("bookshelf-insert", ContainerDepositRecord.class);
        m.put("bookshelf-remove", ContainerWithdrawRecord.class);
        m.put("pot-insert", ContainerDepositRecord.class);
        m.put("pot-remove", ContainerWithdrawRecord.class);
        m.put("shulker-deposit", ContainerDepositRecord.class);
        m.put("shulker-withdraw", ContainerWithdrawRecord.class);
        m.put("bundle-insert", ContainerDepositRecord.class);
        m.put("bundle-extract", ContainerWithdrawRecord.class);
        m.put("brush", BlockBreakRecord.class);
        m.put("sculk", BlockUseRecord.class);
        m.put("crafter", ContainerWithdrawRecord.class);
        m.put("vault", BlockBreakRecord.class);
        TYPES = Map.copyOf(m);
    }

    private EventCatalog() {
    }

    /** The full name -> record class map. */
    public static Map<String, Class<? extends EventRecord>> recordTypes() {
        return TYPES;
    }

    /** Every registered event name, in the declared order. */
    public static Set<String> eventNames() {
        return TYPES.keySet();
    }

    /** The record class that encodes the given event name, or null when unknown. */
    public static Class<? extends EventRecord> recordClassOf(String eventName) {
        return eventName == null ? null : TYPES.get(eventName.toLowerCase(java.util.Locale.ROOT));
    }

    /** Every event name that's stored as the given record class. */
    public static Set<String> eventsStoredAs(Class<? extends EventRecord> type) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Class<? extends EventRecord>> entry : TYPES.entrySet()) {
            if (entry.getValue().equals(type)) {
                out.add(entry.getKey());
            }
        }
        return Set.copyOf(out);
    }

    /** Distinct record classes, preserving declaration order. */
    public static Collection<Class<? extends EventRecord>> recordClasses() {
        return new java.util.LinkedHashSet<>(TYPES.values());
    }
}

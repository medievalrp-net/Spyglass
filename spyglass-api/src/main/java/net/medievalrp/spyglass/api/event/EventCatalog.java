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

    /**
     * Runtime-registered custom event names → display verb (past tense). All
     * custom events map to {@link CustomRecord}. Populated by {@link
     * net.medievalrp.spyglass.api.SpyglassApi#registerEvent} at integrator
     * startup; consulted by the storage layer's {@link #recordClassOf} on
     * every read, so it must be thread-safe.
     */
    private static final Map<String, String> CUSTOM = new java.util.concurrent.ConcurrentHashMap<>();

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
        m.put("useSign", BlockUseRecord.class);
        m.put("decay", BlockBreakRecord.class);
        m.put("form", BlockPlaceRecord.class);
        m.put("grow", BlockPlaceRecord.class);
        m.put("ignite", BlockPlaceRecord.class);
        m.put("drop", ItemDropRecord.class);
        m.put("pickup", ItemPickupRecord.class);
        m.put("clone", ItemPickupRecord.class);
        m.put("teleport", TeleportRecord.class);
        m.put("death", EntityDeathRecord.class);
        // Killer-perspective mirror of a death. Reuses EntityHitRecord
        // (source = killer, victimType/victimId = victim) so both storage
        // backends already persist it — no codec/schema change. "kill" is a
        // player killer; "mob-kill" is a mob killer, split so a:kill stays
        // PvP/player-vs-mob and a:mob-kill is independently toggleable.
        m.put("kill", EntityHitRecord.class);
        m.put("mob-kill", EntityHitRecord.class);
        m.put("hit", EntityHitRecord.class);
        m.put("shot", EntityHitRecord.class);
        m.put("mount", EntityMountRecord.class);
        m.put("dismount", EntityMountRecord.class);
        m.put("named", EntityNameRecord.class);
        m.put("entity-deposit", ContainerDepositRecord.class);
        m.put("entity-withdraw", ContainerWithdrawRecord.class);
        // Emitted when an operator takes an item out of a rollback-salvage
        // snapshot via /sg inventory (#76). Reuses the withdraw record shape,
        // so both storage backends already persist it.
        m.put("salvage-withdraw", ContainerWithdrawRecord.class);
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
        // Synthesized rollback-source records — emitted by RollbackEngine
        // every time it successfully restores a block, so the wand on a
        // rolled-back block reads "ROLLBACK placed STONE" / "ROLLBACK
        // broke STONE". Lightweight: just context + target material, no
        // BlockSnapshot blobs (those would dump megabytes into the
        // recorder queue on big rollbacks).
        m.put("rolled-place", BlockUseRecord.class);
        m.put("rolled-break", BlockUseRecord.class);
        // One per completed rollback/restore/undo operation: the durable
        // identity the per-block rolled-* entries are synthesized from
        // at search time (#22).
        m.put("rollback-op", RollbackOpRecord.class);
        TYPES = Map.copyOf(m);
    }

    private EventCatalog() {
    }

    /** The full name -> record class map. */
    public static Map<String, Class<? extends EventRecord>> recordTypes() {
        return TYPES;
    }

    /** Every known event name — built-ins plus runtime-registered customs. */
    public static Set<String> eventNames() {
        if (CUSTOM.isEmpty()) {
            return TYPES.keySet();
        }
        Set<String> names = new java.util.LinkedHashSet<>(TYPES.keySet());
        names.addAll(CUSTOM.keySet());
        return Set.copyOf(names);
    }

    /**
     * The record class that encodes the given event name, or null when
     * unknown. Built-ins resolve from the static map; a runtime-registered
     * custom name resolves to {@link CustomRecord}.
     */
    public static Class<? extends EventRecord> recordClassOf(String eventName) {
        if (eventName == null) {
            return null;
        }
        String key = eventName.toLowerCase(java.util.Locale.ROOT);
        Class<? extends EventRecord> builtin = TYPES.get(key);
        if (builtin != null) {
            return builtin;
        }
        return CUSTOM.containsKey(key) ? CustomRecord.class : null;
    }

    /**
     * Register a custom event name (idempotent, case-insensitive). All custom
     * events are stored as {@link CustomRecord}. A name that collides with a
     * built-in is ignored — built-ins can't be redefined.
     */
    public static void register(String eventName, String pastTense) {
        if (eventName == null || eventName.isBlank()) {
            return;
        }
        String key = eventName.toLowerCase(java.util.Locale.ROOT);
        if (TYPES.containsKey(key)) {
            return;
        }
        CUSTOM.put(key, pastTense == null || pastTense.isBlank() ? key : pastTense);
    }

    /** True when {@code eventName} is a known event — built-in or registered custom. */
    public static boolean isRegistered(String eventName) {
        return recordClassOf(eventName) != null;
    }

    /** The display verb for a registered custom event, or null for built-ins
     *  / unknown names (built-in verbs come from config). */
    public static String pastTenseOf(String eventName) {
        return eventName == null ? null : CUSTOM.get(eventName.toLowerCase(java.util.Locale.ROOT));
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

    /** Distinct record classes, preserving declaration order. Always includes
     *  {@link CustomRecord} — the backing type for any runtime-registered
     *  custom event — so storage codecs are built for it even before the first
     *  custom event is registered. */
    public static Collection<Class<? extends EventRecord>> recordClasses() {
        java.util.LinkedHashSet<Class<? extends EventRecord>> classes =
                new java.util.LinkedHashSet<>(TYPES.values());
        classes.add(CustomRecord.class);
        return classes;
    }
}

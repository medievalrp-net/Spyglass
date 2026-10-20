package net.medievalrp.spyglass.platform;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Spyglass action key → Crusalis event type.
 *
 * <p>This is the authoritative mapping; the module README prints it, and
 * {@code ActionMapTest} fails if a key appears in {@link
 * net.medievalrp.spyglass.api.event.EventCatalog} that is neither mapped here nor
 * explicitly ignored. That test is the point of this class: Spyglass adds action keys,
 * and an unmapped key would otherwise be silently invisible on the Crusalis side —
 * which is the one failure mode a forensics pipeline cannot have.
 *
 * <p>Crusalis's registry is coarser than Spyglass's catalog on purpose. Spyglass
 * distinguishes {@code break}, {@code decay}, {@code bucket-fill}, {@code brush} and
 * {@code vault} because each rolls back differently; Crusalis stores them as
 * {@code spyglass.block_break} with the cause in the payload, because the question
 * asked of the store is "who removed this block", and the distinction is still there in
 * Spyglass's own native storage, which keeps running unchanged.
 */
public final class ActionMap {

    /** Why a key is deliberately not published. */
    public enum Ignored {
        /** Legacy shapes kept only so historical rows decode; no longer emitted. */
        LEGACY,
        /**
         * Records Spyglass synthesizes while performing a rollback. The rollback itself
         * publishes one {@code spyglass.rollback} event; republishing its per-block
         * effects would double every rolled-back action in the store.
         */
        ROLLBACK_ARTIFACT,
        /**
         * Coalesced hopper traffic with no actor and no slot state. High volume, no
         * forensic value on its own, and not rollbackable in Spyglass either.
         */
        NO_ACTOR,
    }

    private static final Map<String, String> TYPES;
    private static final Map<String, Ignored> IGNORED;

    static {
        Map<String, String> m = new LinkedHashMap<>();

        // Blocks. The cause (#tnt, #creeper, #fire, …) is a payload field, so one type
        // covers a player's pickaxe and a creeper.
        m.put("break", "spyglass.block_break");
        m.put("decay", "spyglass.block_break");
        m.put("bucket-fill", "spyglass.block_break");
        m.put("brush", "spyglass.block_break");
        m.put("vault", "spyglass.block_break");
        m.put("place", "spyglass.block_place");
        m.put("form", "spyglass.block_place");
        m.put("bucket-empty", "spyglass.block_place");
        // Separate types because each is its own volume class and each has its own
        // flag: growth and fire are disabled on prod at launch (§6.2), and they would
        // drown block_place if they shared it.
        m.put("grow", "spyglass.growth");
        m.put("ignite", "spyglass.fire");

        // Block interaction that neither breaks nor places.
        m.put("use", "spyglass.block_use");
        // Lower case, like every key in this table: lookups normalize, and Spyglass
        // spells this one "useSign".
        m.put("usesign", "spyglass.block_use");
        m.put("sculk", "spyglass.block_use");

        // Containers: opening one, and moving items through it.
        m.put("open", "spyglass.container_open");
        m.put("close", "spyglass.container_open");
        m.put("shulker-open", "spyglass.container_open");
        m.put("shulker-close", "spyglass.container_open");
        m.put("deposit", "spyglass.container_add");
        m.put("transfer-deposit", "spyglass.container_add");
        m.put("entity-deposit", "spyglass.container_add");
        m.put("bookshelf-insert", "spyglass.container_add");
        m.put("pot-insert", "spyglass.container_add");
        m.put("shulker-deposit", "spyglass.container_add");
        m.put("bundle-insert", "spyglass.container_add");
        m.put("withdraw", "spyglass.container_remove");
        m.put("transfer-withdraw", "spyglass.container_remove");
        m.put("entity-withdraw", "spyglass.container_remove");
        m.put("salvage-withdraw", "spyglass.container_remove");
        m.put("bookshelf-remove", "spyglass.container_remove");
        m.put("pot-remove", "spyglass.container_remove");
        m.put("shulker-withdraw", "spyglass.container_remove");
        m.put("bundle-extract", "spyglass.container_remove");
        m.put("crafter", "spyglass.container_remove");

        // Items on the ground and in hand.
        m.put("drop", "spyglass.item_drop");
        m.put("pickup", "spyglass.item_pickup");
        m.put("clone", "spyglass.item_pickup");
        m.put("snapshot-take", "spyglass.item_pickup");

        // Entities and combat.
        m.put("kill", "spyglass.entity_kill");
        m.put("mob-kill", "spyglass.entity_kill");
        m.put("death", "spyglass.entity_death");
        m.put("hit", "spyglass.combat_hit");
        m.put("shot", "spyglass.projectile");
        m.put("mount", "spyglass.entity_mount");
        m.put("dismount", "spyglass.entity_mount");
        m.put("named", "spyglass.entity_named");
        // An entity placed or removed as if it were a block — armour stands, item
        // frames, the cushions of MedievalRP's furniture. A Crusalis query asking "what
        // happened at these coordinates" wants them beside the blocks, not in a
        // category of their own.
        m.put("cushion-place", "spyglass.block_place");
        m.put("cushion-break", "spyglass.block_break");

        // Server-specific content registered through Spyglass's extension API. Mapped
        // to the nearest existing type rather than given types of their own: the
        // Crusalis registry would need a schema per key, and "a block was consumed" is
        // a break whatever the block is made of.
        m.put("straw-bed-consume", "spyglass.block_break");
        m.put("sulfur-ignite", "spyglass.fire");
        m.put("sulfur-growth", "spyglass.growth");
        m.put("sulfur-content", "spyglass.block_use");
        m.put("sulfur-bucket", "spyglass.block_use");
        m.put("age-lock", "spyglass.block_use");

        // People.
        m.put("say", "spyglass.chat");
        m.put("command", "spyglass.command");
        m.put("teleport", "spyglass.teleport");
        // Disabled on prod at launch: session.* from crusalis-link is the source of
        // truth for joins and quits, and two sources of truth is none. Registered
        // anyway so a dev server can turn them on and compare.
        m.put("join", "spyglass.session_join");
        m.put("quit", "spyglass.session_quit");

        // Staff.
        m.put("rollback-op", "spyglass.rollback");

        TYPES = Collections.unmodifiableMap(m);

        Map<String, Ignored> ignored = new LinkedHashMap<>();
        ignored.put("transfer-out", Ignored.LEGACY);
        ignored.put("transfer-in", Ignored.LEGACY);
        ignored.put("transfer-uncertain", Ignored.NO_ACTOR);
        ignored.put("rolled-place", Ignored.ROLLBACK_ARTIFACT);
        ignored.put("rolled-break", Ignored.ROLLBACK_ARTIFACT);
        ignored.put("rolled-deposit", Ignored.ROLLBACK_ARTIFACT);
        ignored.put("rolled-withdraw", Ignored.ROLLBACK_ARTIFACT);
        IGNORED = Collections.unmodifiableMap(ignored);
    }

    private ActionMap() {
    }

    /**
     * The Crusalis event type for a Spyglass action key, or null when the key has no
     * mapping.
     *
     * <p>A null is not necessarily a mistake: {@link #ignored(String)} says whether it
     * is a deliberate omission. A key that is in neither table is one Spyglass has
     * gained since this map was written, and is reported once by the publisher and
     * dropped — on prod the Crusalis registry would reject it anyway.
     */
    public static String type(String actionKey) {
        return actionKey == null ? null : TYPES.get(normalize(actionKey));
    }

    /** Why this key is deliberately not published, or null if it is not in that list. */
    public static Ignored ignored(String actionKey) {
        return actionKey == null ? null : IGNORED.get(normalize(actionKey));
    }

    /** Whether this key is known at all, mapped or deliberately ignored. */
    public static boolean known(String actionKey) {
        return type(actionKey) != null || ignored(actionKey) != null;
    }

    /** Every mapped action key. */
    public static Set<String> keys() {
        return TYPES.keySet();
    }

    /** Every Crusalis event type this module can publish, in no particular order. */
    public static Set<String> types() {
        Set<String> types = new LinkedHashSet<>(TYPES.values());
        // The two the aggregators produce. They have no Spyglass action key of their
        // own: an explosion arrives as one break record per block, and a WorldEdit
        // operation as one per changed block, and publishing those one-to-one is the
        // volume mistake §6.2 exists to forbid.
        types.add("spyglass.explosion");
        types.add("spyglass.worldedit");
        return Collections.unmodifiableSet(types);
    }

    /**
     * Keys are matched case-insensitively because Spyglass's own catalog mixes
     * conventions ({@code useSign} beside {@code shulker-open}) and a config file
     * written by hand will not reproduce that faithfully.
     */
    private static String normalize(String actionKey) {
        return actionKey.toLowerCase(Locale.ROOT);
    }
}

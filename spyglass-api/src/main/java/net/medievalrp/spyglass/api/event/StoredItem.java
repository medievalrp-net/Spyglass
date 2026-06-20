package net.medievalrp.spyglass.api.event;

import java.util.List;

/**
 * An item as it lived in a container slot / dropped entity / pickup.
 *
 * <p>{@code data} is the base64-encoded {@code ItemStack.serializeAsBytes()}
 * blob: it's the source of truth for faithful reconstruction (rollback,
 * restoration) but is opaque to queries. {@code name}, {@code lore},
 * {@code enchants} and {@code tags} are extracted plain-text projections of
 * the same stack so operators can search the DB for custom names / lore
 * phrases / enchantments / custom NBT without having to decode NBT on every
 * row.
 *
 * <p>{@code tags} holds the item's {@code minecraft:custom_data} component:
 * the NBT that vanilla {@code /give ...[custom_data={...}]}, datapacks, and
 * plugin {@code PersistentDataContainer}s all write, rendered as a
 * searchable string (e.g. {@code {quest:"deliver_letter"}}), or {@code null}
 * when the item carries none. It backs the {@code itags:} query.
 */
public record StoredItem(
        int slot,
        String material,
        String data,
        String name,
        List<String> lore,
        List<String> enchants,
        String tags) {

    public StoredItem {
        lore = lore == null ? List.of() : List.copyOf(lore);
        enchants = enchants == null ? List.of() : List.copyOf(enchants);
    }

    /** Back-compat constructor for call sites that predate the {@code tags}
     *  custom-data projection. Leaves {@code tags} {@code null}. */
    public StoredItem(int slot, String material, String data, String name,
                      List<String> lore, List<String> enchants) {
        this(slot, material, data, name, lore, enchants, null);
    }

    /** Back-compat constructor for call sites (especially tests) that only
     *  know about slot/material/data. Leaves projections empty. */
    public StoredItem(int slot, String material, String data) {
        this(slot, material, data, null, List.of(), List.of(), null);
    }
}

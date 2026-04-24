package net.medievalrp.spyglass.api.event;

import java.util.List;

/**
 * An item as it lived in a container slot / dropped entity / pickup.
 *
 * <p>{@code data} is the base64-encoded {@code ItemStack.serializeAsBytes()}
 * blob — it's the source of truth for faithful reconstruction (rollback,
 * restoration) but is opaque to queries. {@code name}, {@code lore} and
 * {@code enchants} are extracted plain-text projections of the same stack so
 * operators can search the DB for custom names / lore phrases / enchantments
 * without having to decode NBT on every row.
 */
public record StoredItem(
        int slot,
        String material,
        String data,
        String name,
        List<String> lore,
        List<String> enchants) {

    public StoredItem {
        lore = lore == null ? List.of() : List.copyOf(lore);
        enchants = enchants == null ? List.of() : List.copyOf(enchants);
    }

    /** Back-compat constructor for call sites (especially tests) that only
     *  know about slot/material/data. Leaves projections empty. */
    public StoredItem(int slot, String material, String data) {
        this(slot, material, data, null, List.of(), List.of());
    }
}

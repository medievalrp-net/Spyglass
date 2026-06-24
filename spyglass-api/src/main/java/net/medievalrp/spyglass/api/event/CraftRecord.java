package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;

/**
 * A player crafting an item via {@code CraftItemEvent} — at a crafting table or
 * in the 2x2 inventory grid.
 *
 * <p>{@code source} is the crafting player (so {@code p:} matches the crafter);
 * {@code target} is the output {@link org.bukkit.Material} name. {@code result}
 * is the full searchable projection of the crafted output (so {@code iname:} /
 * {@code ilore:} / {@code ench:} / {@code itags:} match it), and
 * {@code ingredients} are lean projections of the recipe matrix consumed by one
 * craft. {@code amount} is the total produced — for a shift-click it is a
 * best-effort estimate ({@code sets * resultAmount}).
 *
 * <p>The record is deliberately station-agnostic: future production stations
 * (smithing, smelting, …) reuse it under their own event names with no storage
 * change. Not {@code Rollbackable} — crafting is a forensic log, like
 * {@code pickup} / {@code drop}.
 */
public record CraftRecord(
        UUID id,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String server,
        String target,
        int amount,
        StoredItem result,
        List<StoredItem> ingredients) implements EventRecord {

    public CraftRecord {
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
    }

    public static CraftRecord of(RecordContext ctx, String target, int amount,
                                 StoredItem result, List<StoredItem> ingredients) {
        return new CraftRecord(
                ctx.id(), "craft", ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), ctx.server(),
                target, amount, result, ingredients);
    }
}

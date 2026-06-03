package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

/**
 * {@code cu:y} / {@code cu:n} — match records where the captured item has
 * (or lacks) any custom metadata. Restored from v1's
 * {@code CustomItemParameter}; useful for sweeping for plugin-spawned or
 * anvil-renamed items vs. vanilla drops.
 *
 * <p>"Has metadata" means at least one of: non-null display name, non-empty
 * lore, non-empty enchant list. v2 always serializes the full
 * {@link net.medievalrp.spyglass.api.event.StoredItem} record, so we
 * can't simply check for a {@code meta} sub-document the way v1 did.
 *
 * <p>This is a Mongo-only param: ClickHouse stores items as opaque BSON
 * blobs and rejects nested-field predicates, same as
 * {@link ItemNameParam} / {@link ItemLoreParam}.
 */
public final class CustomItemParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("cu", "custom");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("cu requires y or n.");
        }
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        boolean want;
        if (v.equals("y") || v.equals("yes") || v.equals("true") || v.equals("1")) {
            want = true;
        } else if (v.equals("n") || v.equals("no") || v.equals("false") || v.equals("0")) {
            want = false;
        } else {
            throw new ParamParseException("cu must be y or n (got '" + value + "').");
        }
        QueryPredicate hasMeta = ItemFieldParams.hasMetadata();
        return want ? hasMeta : new QueryPredicate.Not(hasMeta);
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        if (input == null || input.isEmpty()) {
            return List.of("y", "n");
        }
        String lower = input.toLowerCase(java.util.Locale.ROOT);
        List<String> all = List.of("y", "yes", "n", "no");
        return all.stream().filter(s -> s.startsWith(lower)).toList();
    }
}

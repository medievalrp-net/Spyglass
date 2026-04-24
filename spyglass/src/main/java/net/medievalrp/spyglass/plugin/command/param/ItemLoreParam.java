package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

/**
 * {@code ilore:primordial} — case-insensitive substring match against any
 * line of a StoredItem's lore. Mongo's regex matches against array elements
 * natively, so the same path works for single-line and multi-line lore.
 */
public final class ItemLoreParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("ilore", "itemlore");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("ilore requires a value.");
        }
        return ItemFieldParams.anyItemField("lore", ItemFieldParams.substringPattern(value.trim()));
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return List.of();
    }
}

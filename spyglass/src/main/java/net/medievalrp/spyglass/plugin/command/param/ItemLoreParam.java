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
        // {@code d} is the v1 alias from {@code ItemDescParameter}; kept so
        // pre-existing operator macros keep working after the v2 cutover.
        return List.of("ilore", "itemlore", "d");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException(alias + " requires a value.");
        }
        String safe = ItemFieldParams.requireSafeTerm(alias, value.trim());
        return ItemFieldParams.anyItemField("lore", ItemFieldParams.substringPattern(safe));
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return List.of();
    }
}

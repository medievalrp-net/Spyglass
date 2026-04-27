package net.medievalrp.omniscience2.plugin.command.param;

import java.util.List;
import net.medievalrp.omniscience2.api.param.ParamParseException;
import net.medievalrp.omniscience2.api.param.QueryParamHandler;
import net.medievalrp.omniscience2.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

/**
 * {@code iname:Excaliblur} — case-insensitive substring match against a
 * StoredItem's custom display name, across every record path that carries
 * items.
 */
public final class ItemNameParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("iname", "itemname");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("iname requires a value.");
        }
        String safe = ItemFieldParams.requireSafeTerm(alias, value.trim());
        return ItemFieldParams.anyItemField("name", ItemFieldParams.substringPattern(safe));
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return List.of();
    }
}

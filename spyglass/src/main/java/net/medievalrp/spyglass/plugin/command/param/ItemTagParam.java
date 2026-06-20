package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

/**
 * {@code itags:quest}: case-insensitive substring match against a
 * StoredItem's {@code tags} projection, i.e. its {@code minecraft:custom_data}
 * compound (the NBT that vanilla {@code /give ...[custom_data={...}]},
 * datapacks, and plugin {@code PersistentDataContainer}s write). Lets staff
 * find a custom/plugin item by any fragment of its custom data (for example
 * {@code itags:"mmoitems:type"} or {@code itags:deliver_letter}) without
 * decoding NBT per row. Same five-path OR expansion as {@code ilore:}.
 */
public final class ItemTagParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("itags", "itag");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException(alias + " requires a value.");
        }
        String safe = ItemFieldParams.requireSafeTerm(alias, value.trim());
        return ItemFieldParams.anyItemField("tags", ItemFieldParams.substringPattern(safe));
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return List.of();
    }
}

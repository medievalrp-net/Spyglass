package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

/**
 * {@code ench:sharpness} (name match) or {@code ench:sharpness=5} (exact
 * name+level). Items store enchantments as {@code "sharpness=5"} strings;
 * the regex is a case-insensitive substring so partial names still hit.
 */
public final class EnchantParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("ench", "enchant", "enchantment");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("ench requires a value.");
        }
        // Allow `ench:sharpness:5` as an alternative spelling of `ench:sharpness=5`.
        String normalised = value.trim().replace(':', '=').toLowerCase(java.util.Locale.ROOT);
        return ItemFieldParams.anyItemField("enchants", ItemFieldParams.substringPattern(normalised));
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return org.bukkit.Registry.ENCHANTMENT.stream()
                .map(ench -> ench.getKey().getKey())
                .filter(key -> key.startsWith(input.toLowerCase(java.util.Locale.ROOT)))
                .sorted()
                .toList();
    }
}

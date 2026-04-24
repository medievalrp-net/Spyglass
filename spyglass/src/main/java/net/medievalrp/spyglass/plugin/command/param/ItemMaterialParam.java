package net.medievalrp.spyglass.plugin.command.param;

import java.util.ArrayList;
import java.util.List;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;

/**
 * {@code i:diamond_sword} or {@code i:diamond,iron_sword} — match
 * records whose {@code target} equals one of the given item materials.
 * Mirrors {@link BlockParam} but filters on {@link Material#isItem()}
 * instead of {@code isBlock()}, so operators can search by item type
 * directly without picking between {@code b:} and {@code trg:}.
 */
public final class ItemMaterialParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("i", "item");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("i requires a material.");
        }
        String[] parts = value.split(",");
        List<String> names = new ArrayList<>();
        for (String raw : parts) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Material material = Material.matchMaterial(trimmed, false);
            if (material == null || !material.isItem()) {
                throw new ParamParseException("Unknown item: " + trimmed);
            }
            names.add(material.name());
        }
        if (names.isEmpty()) {
            throw new ParamParseException("i requires at least one material.");
        }
        if (names.size() == 1) {
            return new QueryPredicate.Eq("target", names.getFirst());
        }
        return new QueryPredicate.In("target", names);
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        String lower = input.toLowerCase();
        return java.util.Arrays.stream(Material.values())
                .filter(Material::isItem)
                .map(m -> m.name().toLowerCase())
                .filter(name -> name.startsWith(lower))
                .limit(25)
                .toList();
    }
}

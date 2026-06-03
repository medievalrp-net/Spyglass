package net.medievalrp.spyglass.plugin.command.param;

import java.util.ArrayList;
import java.util.List;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;

public final class BlockParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("b", "block");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("Block parameter requires a material.");
        }
        String[] parts = value.split(",");
        List<String> names = new ArrayList<>();
        for (String raw : parts) {
            Material material = Material.matchMaterial(raw.trim(), false);
            if (material == null || !material.isBlock()) {
                throw new ParamParseException("Unknown block: " + raw);
            }
            names.add(material.name());
        }
        if (names.size() == 1) {
            return new QueryPredicate.Eq("target", names.getFirst());
        }
        return new QueryPredicate.In("target", names);
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        String lower = input.toLowerCase(java.util.Locale.ROOT);
        return java.util.Arrays.stream(Material.values())
                .filter(Material::isBlock)
                .map(m -> m.name().toLowerCase(java.util.Locale.ROOT))
                .filter(name -> name.startsWith(lower))
                .limit(25)
                .toList();
    }
}

package net.medievalrp.spyglass.plugin.command.param;

import java.util.ArrayList;
import java.util.List;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;

public final class EntityParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("e", "entity");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("Entity parameter requires a type.");
        }
        String[] parts = value.split(",");
        List<String> names = new ArrayList<>();
        for (String raw : parts) {
            String trimmed = raw.trim().toUpperCase(java.util.Locale.ROOT);
            try {
                EntityType type = EntityType.valueOf(trimmed);
                names.add(type.name());
            } catch (IllegalArgumentException ex) {
                throw new ParamParseException("Unknown entity type: " + raw, ex);
            }
        }
        if (names.size() == 1) {
            return new QueryPredicate.Eq("target", names.getFirst());
        }
        return new QueryPredicate.In("target", names);
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        String lower = input.toLowerCase(java.util.Locale.ROOT);
        return java.util.Arrays.stream(EntityType.values())
                .map(e -> e.name().toLowerCase(java.util.Locale.ROOT))
                .filter(name -> name.startsWith(lower))
                .limit(25)
                .toList();
    }
}

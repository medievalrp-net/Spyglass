package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import java.util.Set;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

public final class EventParam implements QueryParamHandler {

    private final Set<String> enabledEvents;

    public EventParam(Set<String> enabledEvents) {
        this.enabledEvents = Set.copyOf(enabledEvents);
    }

    @Override
    public List<String> aliases() {
        return List.of("a", "action", "event");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("Event parameter requires a value.");
        }
        String[] parts = value.split(",");
        List<String> names = new java.util.ArrayList<>();
        for (String raw : parts) {
            String name = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            if (!enabledEvents.contains(name)) {
                throw new ParamParseException("Unknown or disabled event: " + name);
            }
            names.add(name);
        }
        if (names.isEmpty()) {
            throw new ParamParseException("Event parameter requires at least one name.");
        }
        if (names.size() == 1) {
            return new QueryPredicate.Eq("event", names.getFirst());
        }
        return new QueryPredicate.In("event", names);
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return enabledEvents.stream()
                .filter(name -> name.startsWith(input.toLowerCase(java.util.Locale.ROOT)))
                .sorted()
                .toList();
    }
}

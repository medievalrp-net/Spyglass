package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

/**
 * {@code srv:lobby} / {@code server:rp} - exact match against
 * {@link net.medievalrp.spyglass.api.event.EventRecord#server()}.
 *
 * <p>Lets a shared backend (one Mongo / one ClickHouse fronted by many
 * backend servers) be sliced per backend server. The local server's
 * configured name is offered as a tab suggestion so {@code srv:<TAB>}
 * autocompletes the obvious case; cross-server names are free-form
 * text since the local plugin has no view of the cluster topology.
 */
public final class ServerParam implements QueryParamHandler {

    private final String localServerName;

    public ServerParam(String localServerName) {
        this.localServerName = localServerName;
    }

    @Override
    public List<String> aliases() {
        return List.of("srv", "server");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("server requires a name (e.g. srv:lobby).");
        }
        return new QueryPredicate.Eq("server", value.trim());
    }

    @Override
    public boolean suppressesDefaultRadius(String alias) {
        return true;
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        if (localServerName == null || localServerName.isBlank()) {
            return List.of();
        }
        String lower = input.toLowerCase(java.util.Locale.ROOT);
        if (localServerName.toLowerCase(java.util.Locale.ROOT).startsWith(lower)) {
            return List.of(localServerName);
        }
        return List.of();
    }
}

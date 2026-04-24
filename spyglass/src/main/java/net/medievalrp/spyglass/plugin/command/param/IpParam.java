package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

/**
 * {@code ip:192.168.1.100} — exact match against
 * {@link net.medievalrp.spyglass.api.event.JoinRecord#address()}.
 * Since {@code address} only exists on {@link
 * net.medievalrp.spyglass.api.event.JoinRecord}, the per-record-type
 * narrowing in
 * {@link net.medievalrp.spyglass.plugin.storage.MongoRecordStore}
 * naturally scopes the query to join records; no explicit event filter
 * needed.
 *
 * <p>Primary use case: alt-account / ban-evasion investigation, where
 * a banned player returns from a different name but the same IP.
 */
public final class IpParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("ip");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("ip requires an address.");
        }
        return new QueryPredicate.Eq("address", value.trim());
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return List.of();
    }
}

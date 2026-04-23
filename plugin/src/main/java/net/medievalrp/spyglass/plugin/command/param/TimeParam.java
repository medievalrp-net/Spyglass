package net.medievalrp.spyglass.plugin.command.param;

import java.time.Instant;
import java.util.List;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.util.Duration;
import org.bukkit.command.CommandSender;

public final class TimeParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("t", "since");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        Duration duration;
        try {
            duration = Duration.parse(value);
        } catch (IllegalArgumentException | ArithmeticException ex) {
            throw new ParamParseException("Invalid duration: " + value, ex);
        }
        Instant lower = duration.before(Instant.now());
        return new QueryPredicate.Range("occurred", lower, null);
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        if (input.isEmpty()) {
            return List.of("10m", "1h", "6h", "1d", "3d", "1w");
        }
        return List.of();
    }
}

package net.medievalrp.omniscience2.api.param;

import java.util.List;
import net.medievalrp.omniscience2.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

public interface QueryParamHandler {

    List<String> aliases();

    QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException;

    default boolean suppressesDefaultRadius(String alias) {
        return false;
    }

    default List<String> suggestions(CommandSender sender, String input) {
        return List.of();
    }

    record ParamContext(
            CommandSender sender,
            net.medievalrp.omniscience2.api.util.BlockLocation senderLocation,
            int maxRadius) {
    }
}

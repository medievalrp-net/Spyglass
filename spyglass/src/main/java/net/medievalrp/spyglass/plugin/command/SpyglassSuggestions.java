package net.medievalrp.spyglass.plugin.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.extension.FlagHandler;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.parser.ParserDescriptor;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.BlockingSuggestionProvider;
import org.incendo.cloud.suggestion.Suggestion;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class SpyglassSuggestions {

    private static final List<String> FLAGS = List.of(
            "-ng", "-g", "-nc", "-ex", "-we",
            "-ord=asc", "-ord=desc",
            "-nod=r", "-nod=t");

    private final SpyglassApi api;

    public SpyglassSuggestions(SpyglassApi api) {
        this.api = api;
    }

    public ParserDescriptor<CommandSender, String> paramsParser() {
        return StringParser.greedyStringParser();
    }

    public BlockingSuggestionProvider<CommandSender> paramsProvider() {
        return (ctx, input) -> {
            String remaining = input.remainingInput();
            String lastToken = lastToken(remaining);
            return suggestFor(ctx.sender(), lastToken).stream()
                    .map(Suggestion::suggestion)
                    .toList();
        };
    }

    private List<String> suggestFor(CommandSender sender, String token) {
        if (token.startsWith("-")) {
            String lower = token.toLowerCase(java.util.Locale.ROOT);
            // After the dash there may be `=value` — split on first
            // `=` so that completion against a custom flag's value-side
            // works the same as for built-ins.
            int eq = lower.indexOf('=');
            if (eq > 1) {
                String flagName = lower.substring(1, eq);
                String partialValue = lower.substring(eq + 1);
                Optional<FlagHandler> handler = api.flag(flagName);
                if (handler.isPresent()) {
                    List<String> values = handler.get().suggestions(sender, flagName, partialValue);
                    List<String> prefixed = new ArrayList<>(values.size());
                    for (String v : values) {
                        prefixed.add("-" + flagName + "=" + v);
                    }
                    return prefixed;
                }
                // Fall through — built-in flags (`-ord=`, `-nod=`)
                // already appear in FLAGS with their values prefilled.
            }
            List<String> all = new ArrayList<>(FLAGS);
            for (FlagHandler handler : api.flags()) {
                for (String alias : handler.aliases()) {
                    all.add("-" + alias);
                }
            }
            return all.stream().filter(flag -> flag.startsWith(lower)).toList();
        }
        int colon = token.indexOf(':');
        if (colon > 0) {
            String alias = token.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
            String partialValue = token.substring(colon + 1);
            Optional<QueryParamHandler> handler = api.queryParam(alias);
            if (handler.isEmpty()) {
                return List.of();
            }
            List<String> suggestions = handler.get().suggestions(sender, partialValue);
            List<String> prefixed = new ArrayList<>(suggestions.size());
            for (String suggestion : suggestions) {
                prefixed.add(alias + ":" + suggestion);
            }
            return prefixed;
        }
        // No alias yet — suggest every alias with ':' suffix plus the flag starters.
        List<String> out = new ArrayList<>();
        for (QueryParamHandler handler : api.queryParams()) {
            for (String alias : handler.aliases()) {
                out.add(alias + ":");
            }
        }
        out.addAll(FLAGS);
        for (FlagHandler handler : api.flags()) {
            for (String alias : handler.aliases()) {
                out.add("-" + alias);
            }
        }
        out.removeIf(item -> !item.startsWith(token));
        return out;
    }

    private static String lastToken(String remaining) {
        if (remaining == null || remaining.isEmpty()) {
            return "";
        }
        int space = remaining.lastIndexOf(' ');
        return space < 0 ? remaining : remaining.substring(space + 1);
    }
}

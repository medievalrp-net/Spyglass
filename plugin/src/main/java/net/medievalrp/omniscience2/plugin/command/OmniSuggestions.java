package net.medievalrp.omniscience2.plugin.command;

import java.util.Collections;
import java.util.List;
import net.medievalrp.omniscience2.api.Omniscience2Api;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.parser.ParserDescriptor;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.BlockingSuggestionProvider;
import org.incendo.cloud.suggestion.Suggestion;

public final class OmniSuggestions {

    @SuppressWarnings("unused")
    private final Omniscience2Api api;

    public OmniSuggestions(Omniscience2Api api) {
        this.api = api;
    }

    public ParserDescriptor<CommandSender, String> paramsParser() {
        return StringParser.greedyStringParser();
    }

    public BlockingSuggestionProvider<CommandSender> paramsProvider() {
        // Per-parameter suggestion wiring is Block 5 work. Keep the hook in place.
        return (ctx, input) -> emptySuggestions();
    }

    private static List<Suggestion> emptySuggestions() {
        return Collections.emptyList();
    }
}

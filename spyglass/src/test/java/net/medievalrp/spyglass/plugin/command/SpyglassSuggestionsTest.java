package net.medievalrp.spyglass.plugin.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.extension.FlagHandler;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.plugin.imports.ImportConfig;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.incendo.cloud.suggestion.Suggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for #55. The {@code params} argument is a Cloud greedy
 * string, so Cloud hands the provider the whole remaining span and then runs
 * the provider's output through {@code FilteringSuggestionProcessor}, which
 * keeps only suggestions that start with that span. Bare-token suggestions
 * ({@code player:}) failed that filter the instant an earlier param was
 * present, which silently killed completion on every multi-param query.
 *
 * <p>These tests drive the real cloud-core suggestion pipeline end to end
 * (the same {@code suggestImmediately} path the Bukkit tab-completer calls),
 * so the empty-result regression is caught at the source rather than mocked
 * away.
 */
class SpyglassSuggestionsTest {

    private CommandManager<CommandSender> manager;
    private CommandSender sender;

    @BeforeEach
    void setUp() {
        QueryParamHandler action = mock(QueryParamHandler.class);
        QueryParamHandler player = mock(QueryParamHandler.class);
        when(action.aliases()).thenReturn(List.of("action", "a"));
        when(player.aliases()).thenReturn(List.of("player", "p"));
        when(player.suggestions(any(), any())).thenReturn(List.of("Steve", "Alex"));

        SpyglassApi api = mock(SpyglassApi.class);
        when(api.queryParams()).thenReturn(List.of(action, player));
        when(api.flags()).thenReturn(List.<FlagHandler>of());
        when(api.queryParam("player")).thenReturn(Optional.of(player));

        SpyglassSuggestions suggestions = new SpyglassSuggestions(
                api, new ImportConfig(Map.of()), Path.of("import"));
        manager = new TestManager();
        manager.command(manager.commandBuilder("search")
                .required("params", suggestions.paramsParser(), suggestions.paramsProvider()));
        sender = mock(CommandSender.class);
    }

    private List<String> complete(String input) {
        return manager.suggestionFactory().suggestImmediately(sender, input)
                .list().stream().map(Suggestion::suggestion).toList();
    }

    @Test
    void firstTokenStillSuggestsParams() {
        assertThat(complete("search ac")).contains("action:");
    }

    @Test
    void suggestsNextParamAfterAPreviousParam() {
        // Before the fix this returned [] — `player:` does not start with the
        // consumed greedy span `action:break pl`, so Cloud filtered it out.
        List<String> out = complete("search action:break pl");
        assertThat(out).isNotEmpty();
        assertThat(out).contains("action:break player:");
        // Every suggestion must span the whole argument, or the filter drops it.
        assertThat(out).allMatch(s -> s.startsWith("action:break "));
    }

    @Test
    void suggestsParamsAfterTrailingSpace() {
        assertThat(complete("search action:break "))
                .contains("action:break player:", "action:break action:");
    }

    @Test
    void suggestsValuesForALaterParam() {
        assertThat(complete("search action:break player:"))
                .contains("action:break player:Steve", "action:break player:Alex");
    }

    /** Headless cloud-core manager — no Bukkit server, just the suggestion engine. */
    static final class TestManager extends CommandManager<CommandSender> {
        TestManager() {
            super(ExecutionCoordinator.simpleCoordinator(),
                    CommandRegistrationHandler.nullCommandRegistrationHandler());
        }

        @Override
        public boolean hasPermission(CommandSender sender, String permission) {
            return true;
        }
    }
}

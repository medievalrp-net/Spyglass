package net.medievalrp.spyglass.plugin.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.extension.FlagHandler;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.plugin.imports.ImportConfig;
import org.bukkit.command.CommandSender;
import org.mockito.ArgumentCaptor;
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
    private QueryParamHandler action;

    @BeforeEach
    void setUp() {
        action = mock(QueryParamHandler.class);
        QueryParamHandler player = mock(QueryParamHandler.class);
        when(action.aliases()).thenReturn(List.of("action", "a"));
        when(player.aliases()).thenReturn(List.of("player", "p"));
        // Input-sensitive stubs (mirroring the real params' prefix match) so the
        // multi-value tests below prove the handler is asked to complete only the
        // trailing entry, not the whole comma span.
        when(action.suggestions(any(), any()))
                .thenAnswer(inv -> prefixMatch(inv.getArgument(1), "break", "place", "pickup", "drop"));
        when(player.suggestions(any(), any()))
                .thenAnswer(inv -> prefixMatch(inv.getArgument(1), "Steve", "Alex"));

        SpyglassApi api = mock(SpyglassApi.class);
        when(api.queryParams()).thenReturn(List.of(action, player));
        when(api.flags()).thenReturn(List.<FlagHandler>of());
        when(api.queryParam("action")).thenReturn(Optional.of(action));
        when(api.queryParam("player")).thenReturn(Optional.of(player));

        SpyglassSuggestions suggestions = new SpyglassSuggestions(
                api, new ImportConfig(Map.of()), Path.of("import"));
        manager = new TestManager();
        manager.command(manager.commandBuilder("search")
                .required("params", suggestions.paramsParser(), suggestions.paramsProvider()));
        sender = mock(CommandSender.class);
    }

    private static List<String> prefixMatch(String input, String... values) {
        String lower = input.toLowerCase(Locale.ROOT);
        return Stream.of(values)
                .filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
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

    // ── #189: comma-separated list values complete every entry ──────────

    @Test
    void completesSecondActionInACommaList() {
        // Before the fix the handler saw the whole "break,pl" span and matched
        // nothing, so only the first action was completable.
        List<String> out = complete("search action:break,pl");
        assertThat(out).contains("action:break,place");
        // The earlier entry is preserved on every suggestion.
        assertThat(out).allMatch(s -> s.startsWith("action:break,"));
    }

    @Test
    void trailingCommaOffersAllNextActions() {
        assertThat(complete("search action:break,"))
                .contains("action:break,break", "action:break,place",
                        "action:break,pickup", "action:break,drop");
    }

    @Test
    void completesSecondPlayerKeepingTheFirst() {
        List<String> out = complete("search player:Steve,Al");
        assertThat(out).contains("player:Steve,Alex");
        // Only the entry being typed is completed; the first name is not
        // re-offered as the second entry.
        assertThat(out).doesNotContain("player:Steve,Steve");
    }

    @Test
    void completesAcrossAnEarlierParamAndAList() {
        assertThat(complete("search player:Steve action:break,pl"))
                .contains("player:Steve action:break,place");
    }

    @Test
    void completesAnExcludeEntry() {
        // `!`-prefixed excludes (#30) complete too: the `!` is peeled before the
        // handler sees the bare entry, then re-attached.
        assertThat(complete("search action:!pl")).contains("action:!place");
        assertThat(complete("search action:break,!pl")).contains("action:break,!place");
    }

    @Test
    void handlerReceivesOnlyTheBareTrailingEntry() {
        complete("search action:break,!pla");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(action, atLeastOnce()).suggestions(any(), captor.capture());
        // Not "break,!pla" and not "!pla" — the comma prefix and the `!` are
        // stripped so the param only ever completes a single bare entry.
        assertThat(captor.getValue()).isEqualTo("pla");
    }

    // ── #287 rollback opt-in flags complete like the other flags ────────

    @Test
    void dashOffersTheContainerAndEntityFlags() {
        assertThat(complete("search -"))
                .contains("-containers", "-entities");
    }

    @Test
    void partialContainerFlagCompletes() {
        assertThat(complete("search -cont")).contains("-containers");
    }

    @Test
    void flagCompletionKeepsEarlierParams() {
        // The greedy-span rule (#55) applies to flags too: the whole
        // argument must be spanned or the filter drops the suggestion.
        List<String> out = complete("search player:Steve -ent");
        assertThat(out).contains("player:Steve -entities");
        assertThat(out).allMatch(s -> s.startsWith("player:Steve "));
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

    // #258: /sg migrate <backend> completes the storage tokens like every
    // other completed argument.
    @Test
    void migrateBackendProviderSuggestsAllTokens() throws Exception {
        SpyglassSuggestions suggestions = new SpyglassSuggestions(
                mock(SpyglassApi.class), new ImportConfig(Map.of()), Path.of("import"));
        var out = new java.util.ArrayList<String>();
        suggestions.migrateBackendProvider().suggestions(null, null)
                .forEach(s -> out.add(s.suggestion()));
        assertThat(out)
                .containsExactlyInAnyOrder("sqlite", "mongo", "clickhouse", "mariadb", "mysql");
    }
}

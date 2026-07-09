package net.medievalrp.spyglass.plugin.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.extension.FlagHandler;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.plugin.imports.ImportConfig;
import net.medievalrp.spyglass.plugin.imports.ImportPaths;
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
    private final ImportConfig importConfig;
    private final Path importDir;

    public SpyglassSuggestions(SpyglassApi api, ImportConfig importConfig, Path importDir) {
        this.api = api;
        this.importConfig = importConfig;
        this.importDir = importDir;
    }

    /** Tab-completes {@code .db} files in the import directory for {@code /spyglass import <file>}. */
    public BlockingSuggestionProvider<CommandSender> importFileProvider() {
        return (ctx, input) -> ImportPaths.listDbFiles(importDir).stream()
                .map(Suggestion::suggestion).toList();
    }

    /** Tab-completes configured {@code import.conf} source names for {@code /spyglass import mysql <source>}. */
    public BlockingSuggestionProvider<CommandSender> importSourceProvider() {
        return (ctx, input) -> importConfig.sources().keySet().stream()
                .map(Suggestion::suggestion).toList();
    }

    public ParserDescriptor<CommandSender, String> paramsParser() {
        return StringParser.greedyStringParser();
    }

    public BlockingSuggestionProvider<CommandSender> paramsProvider() {
        return (ctx, input) -> {
            String remaining = input.remainingInput();
            String lastToken = lastToken(remaining);
            // The params argument is a greedy string, so Cloud hands us the whole
            // remaining span and then filters our output down to suggestions that
            // start with that span. A bare-token suggestion (`player:`) fails that
            // filter the moment an earlier param is present, which silently killed
            // completion on every multi-param query. Prepend the already-typed
            // prefix so each suggestion spans the entire argument and survives.
            String prefix = remaining.substring(0, remaining.length() - lastToken.length());
            return suggestFor(ctx.sender(), lastToken).stream()
                    .map(suggestion -> prefix + suggestion)
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
            String value = token.substring(colon + 1);
            Optional<QueryParamHandler> handler = api.queryParam(alias);
            if (handler.isEmpty()) {
                return List.of();
            }
            // Most value params accept a comma-separated list (a:break,place /
            // p:Alice,Bob), and `!`-prefixed entries are excludes (#30). Tab
            // completion must complete the entry currently being typed, not the
            // whole value: split off everything up to and including the last
            // comma as an already-committed prefix, peel a leading `!`, and ask
            // the handler to complete only that final bare entry. Without this
            // the handler saw the whole `break,pl` span and prefix-matched
            // nothing past the first entry, so only the first action in a list
            // was ever completable (#189). Each suggestion is reassembled as the
            // entire argument value (alias:committed[!]entry) so it survives
            // Cloud's greedy-span filter (#55) and keeps the earlier entries the
            // user already typed. Single-value params never see a comma here, and
            // params where a comma is literal (m:, content) return no
            // suggestions, so this is a no-op for them.
            int lastComma = value.lastIndexOf(',');
            String committed = value.substring(0, lastComma + 1);   // "" when no comma
            String entry = value.substring(lastComma + 1);
            boolean negated = entry.startsWith("!");
            String bare = negated ? entry.substring(1) : entry;
            String entryPrefix = negated ? "!" : "";
            List<String> suggestions = handler.get().suggestions(sender, bare);
            List<String> prefixed = new ArrayList<>(suggestions.size());
            for (String suggestion : suggestions) {
                prefixed.add(alias + ":" + committed + entryPrefix + suggestion);
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

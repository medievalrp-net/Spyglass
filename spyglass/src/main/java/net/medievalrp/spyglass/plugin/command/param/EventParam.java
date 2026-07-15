package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

public final class EventParam implements QueryParamHandler {

    // A rollback never persists per-block receipts; searches synthesize
    // rolled-<event> entries from the rollback-op record (#22, RolledSynthesis).
    // Those carry the rolled-* event names, so a bare a:break would filter out
    // the rollback that BROKE (removed) a block - the operator sees the original
    // but not its reversal (#330). Expanding a:break to also match rolled-break
    // (and place/deposit/withdraw likewise) surfaces both. The In(event, [...])
    // this produces flows through the synthesis gate and post-filter unchanged.
    private static final Map<String, String> ROLLED_COUNTERPART = Map.of(
            "break", "rolled-break",
            "place", "rolled-place",
            "deposit", "rolled-deposit",
            "withdraw", "rolled-withdraw");

    private final Set<String> enabledEvents;

    public EventParam(Set<String> enabledEvents) {
        // Live reference, NOT a copy: SpyglassApi#registerEvent adds custom
        // event names to this same set at runtime so a:<name> parses for them.
        this.enabledEvents = enabledEvents;
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
        // `!`-prefixed names are excludes, same syntax as c: (#30).
        List<String> includes = new java.util.ArrayList<>();
        List<String> excludes = new java.util.ArrayList<>();
        for (String raw : value.split(",")) {
            String token = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (token.isEmpty() || token.equals("!")) {
                continue;
            }
            boolean negated = token.startsWith("!");
            String name = negated ? token.substring(1) : token;
            if (!enabledEvents.contains(name)) {
                throw new ParamParseException("Unknown or disabled event: " + name);
            }
            addWithRolledCounterpart(negated ? excludes : includes, name);
        }
        if (includes.isEmpty() && excludes.isEmpty()) {
            throw new ParamParseException("Event parameter requires at least one name.");
        }
        List<QueryPredicate> clauses = new java.util.ArrayList<>(2);
        if (!includes.isEmpty()) {
            clauses.add(membership(includes));
        }
        if (!excludes.isEmpty()) {
            clauses.add(new QueryPredicate.Not(membership(excludes)));
        }
        return clauses.size() == 1 ? clauses.getFirst() : new QueryPredicate.And(clauses);
    }

    /**
     * Add {@code name} to the bucket, plus its rollback counterpart when that
     * rolled event is enabled (#330). Dedupes so {@code a:break,rolled-break}
     * or a base/rolled pair typed together stays a clean membership. Gating on
     * {@link #enabledEvents} means an operator who disabled the rolled audit
     * keeps the old bare-name behavior.
     */
    private void addWithRolledCounterpart(List<String> bucket, String name) {
        if (!bucket.contains(name)) {
            bucket.add(name);
        }
        String rolled = ROLLED_COUNTERPART.get(name);
        if (rolled != null && enabledEvents.contains(rolled) && !bucket.contains(rolled)) {
            bucket.add(rolled);
        }
    }

    private static QueryPredicate membership(List<String> names) {
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

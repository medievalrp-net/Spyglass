package net.medievalrp.spyglass.proxy.command;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.Sort;

/**
 * Minimal query-string parser for the proxy /spyglass search command.
 *
 * <p>Subset of the Paper-side parser: only the params that make sense
 * without world / block / WorldEdit context. Recognises:
 *
 * <ul>
 *   <li>{@code srv:} / {@code server:} - server tag (the headline filter)
 *   <li>{@code p:} / {@code player:} - player name (case-insensitive)
 *   <li>{@code a:} / {@code action:} - event name
 *   <li>{@code t:} / {@code time:} - time window, e.g. {@code t:1d}, {@code t:30m}
 *   <li>{@code m:} / {@code message:} - chat / command line substring
 *   <li>{@code target:} / {@code trg:} - target string
 *   <li>{@code ip:} - join IP
 *   <li>{@code -ng}, {@code -nc}, {@code -ex}, {@code -ord:asc|desc}
 * </ul>
 *
 * Bare tokens (no colon) default to a player filter, mirroring the
 * Paper-side ergonomic shortcut.
 */
public final class ProxyQueryStringParser {

    private final int searchLimit;
    private final long defaultTimeSeconds;
    private final Function<String, List<UUID>> ipToPlayerIds;

    public ProxyQueryStringParser(int searchLimit, long defaultTimeSeconds,
                                  Function<String, List<UUID>> ipToPlayerIds) {
        this.searchLimit = searchLimit;
        this.defaultTimeSeconds = defaultTimeSeconds;
        this.ipToPlayerIds = ipToPlayerIds;
    }

    /**
     * @param allowIp whether the invoking source holds
     *     {@code spyglass.search.ip}; the {@code ip:} param (IP→player
     *     correlation, PII) is rejected without it (#48)
     */
    public QueryRequest parse(String raw, boolean allowIp) throws ParseException {
        List<QueryPredicate> predicates = new ArrayList<>();
        EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
        Sort sort = Sort.NEWEST_FIRST;
        boolean sawTime = false;

        if (raw != null && !raw.isBlank()) {
            for (String token : raw.trim().split("\\s+")) {
                if (token.isEmpty()) {
                    continue;
                }
                if (token.startsWith("-")) {
                    String name = token.substring(1).toLowerCase(Locale.ROOT);
                    String flagValue = null;
                    int eq = name.indexOf('=');
                    if (eq < 0) {
                        eq = name.indexOf(':');
                    }
                    if (eq >= 0) {
                        flagValue = name.substring(eq + 1);
                        name = name.substring(0, eq);
                    }
                    sort = applyFlag(name, flagValue, flags, sort);
                    continue;
                }

                String alias;
                String value;
                int colon = token.indexOf(':');
                if (colon < 0) {
                    alias = "p";
                    value = token;
                } else {
                    alias = token.substring(0, colon).toLowerCase(Locale.ROOT);
                    value = token.substring(colon + 1);
                }

                if (value.isBlank()) {
                    throw new ParseException(alias + " requires a value.");
                }

                switch (alias) {
                    case "srv", "server" -> predicates.add(new QueryPredicate.Eq("server", value));
                    case "p", "player" -> predicates.add(
                            new QueryPredicate.Eq("source.playerName",
                                    java.util.regex.Pattern.compile(
                                            "^" + java.util.regex.Pattern.quote(value) + "$",
                                            java.util.regex.Pattern.CASE_INSENSITIVE)));
                    case "a", "action" -> predicates.add(new QueryPredicate.Eq("event", value));
                    case "t", "time", "since" -> {
                        Instant lower = parseDuration(value);
                        predicates.add(new QueryPredicate.Range("occurred", lower, null));
                        sawTime = true;
                    }
                    case "m", "message" -> predicates.add(
                            new QueryPredicate.Eq("message",
                                    java.util.regex.Pattern.compile(
                                            ".*" + java.util.regex.Pattern.quote(value) + ".*",
                                            java.util.regex.Pattern.CASE_INSENSITIVE)));
                    case "target", "trg" -> predicates.add(new QueryPredicate.Eq("target", value));
                    case "ip" -> {
                        if (!allowIp) {
                            throw new ParseException("Missing permission spyglass.search.ip.");
                        }
                        predicates.add(resolveIpPredicate(value));
                    }
                    case "w", "world" -> predicates.add(new QueryPredicate.Eq("location.worldName", value));
                    default -> throw new ParseException("Unknown parameter: " + alias);
                }
            }
        }

        if (!sawTime) {
            // Config-driven default window (defaults.time, parsed at config
            // load into seconds). No 0-disable: an unbounded query against
            // a long-lived store can pull tens of millions of rows, and
            // the proxy has no spatial constraint to lean on instead.
            Instant lower = Instant.now().minusSeconds(defaultTimeSeconds);
            predicates.add(new QueryPredicate.Range("occurred", lower, null));
        }

        boolean grouping = !flags.contains(Flag.NO_GROUP);
        return new QueryRequest(predicates, sort, searchLimit, flags, grouping);
    }

    /**
     * {@code ip:1.2.3.4} on the proxy mirrors the Paper-side semantics:
     * resolve the IP to player UUIDs via recent join records, then OR an
     * exact address match (so join rows still match precisely) with a
     * source.playerId IN (...) match (so non-join events by those players
     * come through). When no players are known to have joined from the
     * given IP within the lookup window, falls back to address-only.
     */
    private QueryPredicate resolveIpPredicate(String ip) {
        QueryPredicate addressMatch = new QueryPredicate.Eq("address", ip);
        if (ipToPlayerIds == null) {
            return addressMatch;
        }
        List<UUID> playerIds;
        try {
            playerIds = ipToPlayerIds.apply(ip);
        } catch (RuntimeException ex) {
            return addressMatch;
        }
        if (playerIds == null || playerIds.isEmpty()) {
            return addressMatch;
        }
        return new QueryPredicate.Or(List.of(
                addressMatch,
                new QueryPredicate.In("source.playerId", playerIds)));
    }

    private static Sort applyFlag(String name, String value, EnumSet<Flag> flags, Sort sort)
            throws ParseException {
        return switch (name) {
            case "ng", "nogroup" -> {
                flags.add(Flag.NO_GROUP);
                yield sort;
            }
            case "nc", "nochat" -> {
                flags.add(Flag.NO_CHAT);
                yield sort;
            }
            case "ex", "extended" -> {
                flags.add(Flag.EXTENDED);
                yield sort;
            }
            case "ord", "order" -> {
                if (value == null) {
                    throw new ParseException("Flag ord requires asc / desc.");
                }
                yield switch (value) {
                    case "asc", "old", "oldest" -> Sort.OLDEST_FIRST;
                    case "desc", "new", "newest" -> Sort.NEWEST_FIRST;
                    default -> throw new ParseException("Unknown order: " + value);
                };
            }
            // Built-in flags from the Paper parser that don't apply
            // here are silently ignored rather than raising — operators
            // copying queries from a Paper /spyglass session shouldn't
            // get an error for a harmless extra flag like -g.
            case "g", "global", "we", "worldedit", "nod", "nodefault" -> sort;
            default -> throw new ParseException("Unknown flag: " + name);
        };
    }

    private static Instant parseDuration(String value) throws ParseException {
        if (value == null || value.isEmpty()) {
            throw new ParseException("time requires a value (e.g. 1d, 6h, 30m).");
        }
        char unit = value.charAt(value.length() - 1);
        long n;
        try {
            n = Long.parseLong(value.substring(0, value.length() - 1));
        } catch (NumberFormatException ex) {
            throw new ParseException("time value must be <number><unit>: " + value);
        }
        ChronoUnit chronoUnit = switch (unit) {
            case 's' -> ChronoUnit.SECONDS;
            case 'm' -> ChronoUnit.MINUTES;
            case 'h' -> ChronoUnit.HOURS;
            case 'd' -> ChronoUnit.DAYS;
            case 'w' -> ChronoUnit.WEEKS;
            default -> throw new ParseException("Unknown time unit: " + unit + " (use s/m/h/d/w).");
        };
        return Instant.now().minus(n, chronoUnit);
    }

    public static final class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }
}

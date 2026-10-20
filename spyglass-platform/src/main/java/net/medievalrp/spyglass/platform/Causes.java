package net.medievalrp.spyglass.platform;

import java.util.Locale;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;

/**
 * The payload's {@code cause} field: empty when a player did it, otherwise the
 * non-player cause, {@code #}-prefixed (§6.2).
 *
 * <p>The prefix is Spyglass's own convention, kept so a Crusalis query and a
 * {@code /sg} query say the same thing — {@code c:tnt} in the wand and
 * {@code cause = '#tnt'} in ClickHouse are the same search.
 */
final class Causes {

    private Causes() {
    }

    static String of(EventRecord record) {
        Source source = record.source();
        if (source == null) {
            return fromOrigin(record.origin());
        }
        String kind = source.kind() == null ? "" : source.kind();
        switch (kind) {
            case Source.PLAYER:
                // A player is the envelope's uuid, never a payload field, so there is
                // nothing to say here: an empty cause IS "a player did it".
                return "";
            case Source.ENTITY:
                return hash(firstNonBlank(source.entityType(), "entity"));
            case Source.PLUGIN:
                return hash(firstNonBlank(source.pluginName(), "plugin"));
            case Source.CONSOLE:
                return "#console";
            case Source.COMMAND_BLOCK:
                return "#command-block";
            case Source.ENVIRONMENT:
                return hash(firstNonBlank(source.description(), originDetail(record.origin()), "environment"));
            default:
                return hash(firstNonBlank(source.description(), kind));
        }
    }

    private static String fromOrigin(Origin origin) {
        if (origin == null) {
            return "";
        }
        if (Origin.PLAYER.equals(origin.kind())) {
            return "";
        }
        return hash(firstNonBlank(origin.detail(), origin.kind()));
    }

    private static String originDetail(Origin origin) {
        return origin == null ? null : origin.detail();
    }

    private static String hash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cause = value.strip().toLowerCase(Locale.ROOT);
        return cause.startsWith("#") ? cause : "#" + cause;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

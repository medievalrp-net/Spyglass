package net.medievalrp.spyglass.plugin.listener.chat;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * Masks the arguments of secret-bearing commands before they are
 * recorded (#47). {@code /login hunter2} becomes {@code /login ***}
 * so auth-plugin credentials never reach the WAL, either backend, or
 * a search hover. Matching is on the command head only — the head
 * itself still records verbatim, so {@code a:command} searches and
 * grouping keep working.
 *
 * <p>Heads are compared case-insensitively, with the leading slash
 * and any {@code plugin:} namespace prefix ignored, so
 * {@code /LOGIN}, {@code login} (console form) and
 * {@code /authme:login} all match a configured {@code login}.
 *
 * <p>Redaction happens at the recording site, not at render time:
 * once a secret is stored it is already in the database, the WAL
 * file, and every backup — masking on display would be theater.
 */
@ApiStatus.Internal
public final class CommandRedaction {

    static final String MASK = "***";

    private final Set<String> heads;

    public CommandRedaction(Collection<String> configuredHeads) {
        Set<String> normalized = new HashSet<>(configuredHeads.size());
        for (String head : configuredHeads) {
            if (head == null) {
                continue;
            }
            String clean = simpleName(stripSlash(head.trim()))
                    .toLowerCase(Locale.ROOT);
            if (!clean.isEmpty()) {
                normalized.add(clean);
            }
        }
        this.heads = Set.copyOf(normalized);
    }

    /**
     * Returns the line to record: the original line when the head is
     * not on the redact list (or the line carries no arguments), or
     * the head portion followed by a single {@code ***} otherwise.
     * The head's original spelling — case, slash, namespace — is
     * preserved in the output; only the arguments are replaced.
     */
    public String apply(String head, String line) {
        if (heads.isEmpty() || head == null || line == null) {
            return line;
        }
        if (!heads.contains(simpleName(head).toLowerCase(Locale.ROOT))) {
            return line;
        }
        int space = line.indexOf(' ');
        if (space < 0 || line.substring(space + 1).isBlank()) {
            // No arguments — nothing secret to mask.
            return line;
        }
        return line.substring(0, space) + " " + MASK;
    }

    private static String stripSlash(String head) {
        return head.startsWith("/") ? head.substring(1) : head;
    }

    /** {@code authme:login} → {@code login}; plain heads pass through. */
    private static String simpleName(String head) {
        int colon = head.lastIndexOf(':');
        return colon < 0 ? head : head.substring(colon + 1);
    }
}

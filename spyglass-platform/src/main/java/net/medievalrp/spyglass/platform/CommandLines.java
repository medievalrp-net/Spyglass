package net.medievalrp.spyglass.platform;

import java.util.Locale;
import java.util.Set;

/**
 * Command-line handling for {@code spyglass.command}: redaction and the private flag.
 *
 * <p>Both rules exist because a command log is the one place a password reliably shows
 * up in plain text. {@code /login hunter2} is typed by every player on a server with
 * authentication, several times a day, and a store that keeps it for ninety days is a
 * credential dump with a retention policy.
 */
public final class CommandLines {

    /** What a redacted argument list is replaced with. */
    public static final String REDACTED = "<redacted>";

    private CommandLines() {
    }

    /**
     * The command's name, lower case, without the leading slash and without arguments.
     * Empty when the line has no command at all.
     */
    public static String name(String commandLine) {
        if (commandLine == null) {
            return "";
        }
        String line = commandLine.strip();
        if (line.startsWith("/")) {
            line = line.substring(1);
        }
        int space = line.indexOf(' ');
        String name = space < 0 ? line : line.substring(0, space);
        // A plugin-qualified command — /essentials:msg — is the same command, and a
        // redaction list that misses it is a redaction list that does not work.
        int colon = name.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < name.length()) {
            name = name.substring(colon + 1);
        }
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * The line with its arguments replaced by {@value #REDACTED} when the command is in
     * {@code redactCommands}, and unchanged otherwise.
     *
     * <p>The command name is kept: "this player ran /login at 03:12" is exactly the fact
     * an investigation needs, and it carries no secret.
     */
    public static String redact(String commandLine, Set<String> redactCommands) {
        return redact(commandLine, name(commandLine), redactCommands);
    }

    /**
     * The same, with the command's name already resolved.
     *
     * <p>Resolving it costs a few strings, and the mapper needs it three times — for
     * the payload's {@code target}, for this, and for the private check. The benchmark
     * showed that adding up: once is enough.
     */
    public static String redact(String commandLine, String name, Set<String> redactCommands) {
        if (commandLine == null) {
            return "";
        }
        if (redactCommands == null || redactCommands.isEmpty()) {
            return commandLine;
        }
        if (!redactCommands.contains(name)) {
            return commandLine;
        }
        String line = commandLine.strip();
        int space = line.indexOf(' ');
        if (space < 0) {
            // No arguments to redact. Still returned stripped, so the two paths agree.
            return line;
        }
        return line.substring(0, space) + " " + REDACTED;
    }

    /**
     * Whether this command's content is private — a direct message — and so belongs in
     * a row only a moderator can read.
     *
     * <p>The text is still published. Dropping it would make the store useless for the
     * harassment reports that are the reason these commands are logged at all; the row
     * policy on the ClickHouse side is what keeps it away from everyone else.
     */
    public static boolean isPrivate(String commandLine, Set<String> privateCommands) {
        return isPrivateName(name(commandLine), privateCommands);
    }

    /**
     * The same, taking the command's name instead of the whole line.
     *
     * <p>A separate name rather than an overload: the two differ only in what the
     * String means, which erasure would hide and a reader would misread.
     */
    public static boolean isPrivateName(String name, Set<String> privateCommands) {
        return privateCommands != null && !privateCommands.isEmpty()
                && privateCommands.contains(name);
    }
}

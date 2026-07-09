package net.medievalrp.spyglass.plugin.imports;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persistent record of prior CoreProtect imports, keyed by source identity
 * (see the importer's {@code ImportIdentity}). Backs the re-import guard: a
 * matching identity means "already imported" and the command refuses
 * without --confirm.
 *
 * <p>Mirrors {@code RollbackResumeStore}'s data-folder persistence idiom
 * (hand-rolled serialization via {@code Files.writeString}/{@code
 * readString}, {@code Files.createDirectories} for the parent, best-effort
 * writes that log rather than throw, and tolerating a missing/corrupt file
 * by starting empty) rather than pulling in a JSON library - this module
 * has none as a dependency.
 */
public final class ImportHistoryStore {

    public record ImportRecord(String identity, String displayName,
                               long importedAtEpochMs, String importedBy,
                               long read, long written, long skipped) {
    }

    private static final Logger LOGGER = Logger.getLogger(ImportHistoryStore.class.getName());

    // Matches one flat JSON object (no nested braces) within the array.
    private static final Pattern OBJECT_PATTERN = Pattern.compile("\\{([^{}]*)}");
    // Matches "key":"value" (string) fields within an object body.
    private static final Pattern STRING_FIELD =
            Pattern.compile("\"(\\w+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    // Matches "key":123 (numeric) fields within an object body.
    private static final Pattern NUMBER_FIELD =
            Pattern.compile("\"(\\w+)\"\\s*:\\s*(-?\\d+)");

    private final Path file;
    private final Map<String, ImportRecord> byIdentity = new LinkedHashMap<>();

    public ImportHistoryStore(Path dataFolder) {
        this.file = dataFolder.resolve("import-history.json");
        load();
    }

    public synchronized Optional<ImportRecord> find(String identity) {
        return Optional.ofNullable(byIdentity.get(identity));
    }

    public synchronized List<ImportRecord> all() {
        return new ArrayList<>(byIdentity.values());
    }

    public synchronized void record(ImportRecord entry) {
        byIdentity.put(entry.identity(), entry);
        save();
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher objects = OBJECT_PATTERN.matcher(text);
            while (objects.find()) {
                ImportRecord record = parseRecord(objects.group(1));
                if (record != null) {
                    byIdentity.put(record.identity(), record);
                }
            }
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Spyglass: failed to read import history " + file, ex);
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "Spyglass: corrupt import history " + file
                    + "; starting empty", ex);
            byIdentity.clear();
        }
    }

    private static ImportRecord parseRecord(String body) {
        Map<String, String> strings = new LinkedHashMap<>();
        Matcher sm = STRING_FIELD.matcher(body);
        while (sm.find()) {
            strings.put(sm.group(1), unescape(sm.group(2)));
        }
        Map<String, Long> numbers = new LinkedHashMap<>();
        Matcher nm = NUMBER_FIELD.matcher(body);
        while (nm.find()) {
            numbers.put(nm.group(1), Long.parseLong(nm.group(2)));
        }

        String identity = strings.get("identity");
        String displayName = strings.get("displayName");
        String importedBy = strings.get("importedBy");
        Long importedAtEpochMs = numbers.get("importedAtEpochMs");
        Long read = numbers.get("read");
        Long written = numbers.get("written");
        Long skipped = numbers.get("skipped");
        if (identity == null || displayName == null || importedBy == null
                || importedAtEpochMs == null || read == null || written == null
                || skipped == null) {
            LOGGER.warning("Spyglass: incomplete import history entry; skipping");
            return null;
        }
        return new ImportRecord(identity, displayName, importedAtEpochMs, importedBy,
                read, written, skipped);
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Spyglass: could not create import history dir "
                    + file.getParent() + "; history not saved", ex);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        boolean first = true;
        for (ImportRecord r : byIdentity.values()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("  {")
                    .append("\"identity\":\"").append(escape(r.identity())).append("\",")
                    .append("\"displayName\":\"").append(escape(r.displayName())).append("\",")
                    .append("\"importedAtEpochMs\":").append(r.importedAtEpochMs()).append(',')
                    .append("\"importedBy\":\"").append(escape(r.importedBy())).append("\",")
                    .append("\"read\":").append(r.read()).append(',')
                    .append("\"written\":").append(r.written()).append(',')
                    .append("\"skipped\":").append(r.skipped())
                    .append('}');
        }
        sb.append("\n]");
        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Spyglass: failed to write import history " + file, ex);
        }
    }

    // Braces are escaped as "{"/"}" (not "\{"/"\}") so that the
    // escaped text never contains a literal '{' or '}' - OBJECT_PATTERN's
    // "[^{}]*" body match would otherwise be corrupted by an embedded brace
    // even though it is logically escaped, silently dropping the record.
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '{' -> sb.append("\\u007b");
                case '}' -> sb.append("\\u007d");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> {
                        sb.append('\n');
                        i += 2;
                    }
                    case 'r' -> {
                        sb.append('\r');
                        i += 2;
                    }
                    case 't' -> {
                        sb.append('\t');
                        i += 2;
                    }
                    case '"' -> {
                        sb.append('"');
                        i += 2;
                    }
                    case '\\' -> {
                        sb.append('\\');
                        i += 2;
                    }
                    case 'u' -> {
                        String hex = s.substring(i + 2, i + 6);
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 6;
                    }
                    default -> {
                        sb.append(next);
                        i += 2;
                    }
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}

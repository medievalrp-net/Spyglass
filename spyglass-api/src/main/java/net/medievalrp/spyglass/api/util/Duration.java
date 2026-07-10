package net.medievalrp.spyglass.api.util;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record Duration(long seconds) {

    // "mo" must precede the single-char class or "1mo" would match as
    // "1m" (one minute) and then fail the trailing-character check (#271).
    private static final Pattern TOKEN_PATTERN = Pattern.compile("(\\d+)(mo|[smhdw])");

    public static Duration parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Duration cannot be blank.");
        }

        Matcher matcher = TOKEN_PATTERN.matcher(input);
        long total = 0L;
        int offset = 0;
        while (matcher.find()) {
            if (matcher.start() != offset) {
                throw new IllegalArgumentException("Invalid duration: " + input);
            }
            long value = Long.parseLong(matcher.group(1));
            long multiplier = switch (matcher.group(2)) {
                case "s" -> 1L;
                case "m" -> 60L;
                case "h" -> 60L * 60L;
                case "d" -> 60L * 60L * 24L;
                case "w" -> 60L * 60L * 24L * 7L;
                case "mo" -> 60L * 60L * 24L * 30L;
                default -> throw new IllegalArgumentException("Unsupported duration unit.");
            };
            total = Math.addExact(total, Math.multiplyExact(value, multiplier));
            offset = matcher.end();
        }

        if (offset != input.length()) {
            throw new IllegalArgumentException("Invalid duration: " + input);
        }

        return new Duration(total);
    }

    public Instant before(Instant base) {
        return base.minusSeconds(seconds);
    }

    public Instant after(Instant base) {
        return base.plusSeconds(seconds);
    }

    /**
     * Shortest {@code w/d/h/m/s} rendering, e.g. {@code 26w}, {@code 4h30m}.
     * Operator-facing (the wand's inspect header shows its window with it).
     * Months are an input convenience only and render as weeks/days.
     */
    public String compact() {
        if (seconds <= 0) {
            return "0s";
        }
        StringBuilder out = new StringBuilder();
        long rest = seconds;
        long[] sizes = {60L * 60L * 24L * 7L, 60L * 60L * 24L, 60L * 60L, 60L, 1L};
        String[] units = {"w", "d", "h", "m", "s"};
        for (int i = 0; i < sizes.length; i++) {
            long count = rest / sizes[i];
            if (count > 0) {
                out.append(count).append(units[i]);
                rest -= count * sizes[i];
            }
        }
        return out.toString();
    }
}

package net.medievalrp.spyglass.api.util;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record Duration(long seconds) {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("(\\d+)([smhdw])");

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
}

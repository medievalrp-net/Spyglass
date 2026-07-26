package net.medievalrp.spyglass.api.capture;

import java.nio.charset.StandardCharsets;

/**
 * Text sanitisation shared by every capture path.
 *
 * <p>Anything lifted off a live world (sign lines, item display names, lore,
 * NBT projections) is player-controlled, so it gets normalised before it
 * reaches a record: bad surrogate pairs are replaced during the UTF-8 round
 * trip, and length is capped so a pathological blob can't bloat a row or the
 * index behind it.
 */
public final class CaptureText {

    /**
     * Longest text a captured field may carry. Anything past this is cut.
     * Well past any legitimate sign line or item name, low enough that a
     * hand-crafted NBT payload cannot blow up a stored row.
     */
    public static final int MAX_TEXT_LEN = 32_768;

    private CaptureText() {
    }

    /**
     * Normalise and length-cap player-controlled text. Null and empty pass
     * through unchanged so callers can distinguish "absent" from "empty".
     */
    public static String safeText(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String roundTripped = new String(input.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        if (roundTripped.length() > MAX_TEXT_LEN) {
            return roundTripped.substring(0, MAX_TEXT_LEN);
        }
        return roundTripped;
    }
}

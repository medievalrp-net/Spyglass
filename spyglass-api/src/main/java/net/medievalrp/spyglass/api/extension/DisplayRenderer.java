package net.medievalrp.spyglass.api.extension;

import java.util.EnumSet;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.query.Flag;

/**
 * Customises how a specific event type renders in search results and
 * inspection-wand hovers. Register via {@link
 * net.medievalrp.spyglass.api.SpyglassApi#registerDisplayRenderer}.
 *
 * <h2>Threading</h2>
 *
 * <p>Both methods are invoked on the <b>main server thread</b> — page
 * rendering happens on the player's tick. Implementations must not
 * block, perform I/O, or call into other plugins that schedule sync
 * work. Building Adventure {@link Component}s and reading from
 * pre-fetched {@link EventRecord} fields is the intended workload.
 *
 * <h2>Error handling</h2>
 *
 * <p>If either method throws a {@link RuntimeException}, the
 * Spyglass renderer falls back to the default rendering for that
 * record — your custom output is dropped silently for that line.
 * Returning {@code null} from {@link #renderTarget} is treated the
 * same as throwing.
 *
 * <h2>Lifetime</h2>
 *
 * <p>A registered renderer is held for the lifetime of the
 * Spyglass plugin instance. There is no unregister path; reload
 * the plugin (or replace the registration with a no-op renderer) if
 * you need to undo it.
 */
public interface DisplayRenderer {

    /**
     * Render the "target" span of the result line — the part shown
     * after the actor + verb (e.g. the trailing {@code STONE} in
     * "Alice broke STONE").
     *
     * @param record the event being rendered; never {@code null}
     * @param defaultTarget what Spyglass would have rendered
     *     without this extension; safe to return as-is to opt out
     * @param flags query flags in effect for this result page (read
     *     only — do not mutate)
     * @return the replacement target component; returning {@code
     *     defaultTarget} or throwing falls back to the default
     */
    default Component renderTarget(EventRecord record, Component defaultTarget, EnumSet<Flag> flags) {
        return defaultTarget;
    }

    /**
     * Extra lines appended to the inspection-wand hover for this
     * record. Each list entry becomes a separate hover line. Return
     * an empty list (the default) to add nothing.
     *
     * <p>Hover lines are rendered eagerly when the wand result is
     * built, so keep the line count small (single digits).
     */
    default List<Component> hoverLines(EventRecord record) {
        return List.of();
    }

    /**
     * Leading tags rendered at the front of the result line — immediately
     * after the origin tag (e.g. {@code [WhisperNet]}) and before the actor
     * name. Each entry becomes its own bracket-style label, so a hook that
     * parks context in a record field (a chat channel, a faction, a world
     * shard) can surface it up front:
     * "{@code [WhisperNet] [OOC] Alice said: hi}".
     *
     * <p>Return fully-formed components (including any brackets and colour);
     * Spyglass appends each followed by a single space and does not wrap
     * them. Return an empty list (the default) to add nothing. Same threading
     * and error-handling contract as {@link #renderTarget}: main-thread only,
     * and a {@code null}/throwing result falls back to no leading tags.
     */
    default List<Component> leadingTags(EventRecord record) {
        return List.of();
    }
}

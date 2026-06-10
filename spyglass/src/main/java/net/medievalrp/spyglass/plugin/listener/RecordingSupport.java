package net.medievalrp.spyglass.plugin.listener;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.Duration;
import org.bukkit.entity.Player;

public final class RecordingSupport {

    private final Duration retention;
    private final String serverName;

    public RecordingSupport(Duration retention, String serverName) {
        this.retention = retention;
        this.serverName = serverName;
    }

    public String serverName() {
        return serverName;
    }

    /**
     * Hard ceiling on stored player-typed strings. Vanilla server limits
     * are well under this (chat 256, anvil rename 50, signed-book title
     * 32, command line ~32 700) so legitimate input is never truncated.
     * The cap is here strictly to defang a modded/spoofed client that
     * sends a megabyte chat line — without it one rogue packet bloats a
     * single record by ~1 MB and wedges any operator running a wide
     * search across that row.
     */
    public static final int MAX_TEXT_LEN = 32_768;

    /**
     * Mandatory passthrough for ANY player-typed string before it lands
     * in a record field that's stored as a plain BSON string or
     * ClickHouse {@code String} column. Handles three classes of abuse
     * in one pass:
     *
     * <ol>
     *   <li><b>UTF-16 oddities</b> — round-trip via UTF-8 drops unpaired
     *       surrogates that the BSON encoder rejects, preventing one bad
     *       message from sending {@link
     *       net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder}
     *       into an infinite retry on a poison-pill batch. (v1's regex
     *       sanitiser missed unpaired surrogates; this is strictly more
     *       robust.)
     *   <li><b>DoS by length</b> — caps at {@link #MAX_TEXT_LEN}
     *       characters to defang a modded client that bypasses vanilla's
     *       256-char chat cap and ships a megabyte payload.
     *   <li><b>Empty-input fast path</b> — null and empty strings pass
     *       straight through with no allocation.
     * </ol>
     *
     * <p><b>Contract for new listeners:</b> any field that stores
     * player-typed text (display names, sign lines, lore, chat messages,
     * command lines) MUST be wrapped in {@code safeText} at the recording
     * site. Item NBT and entity NBT stored as base64 blobs via
     * {@code serializeAsBytes()} are binary-safe and don't need this.
     *
     * <p>Static so static utilities ({@link
     * net.medievalrp.spyglass.plugin.util.ItemSerialization},
     * {@link net.medievalrp.spyglass.plugin.util.BlockSnapshots})
     * that capture player text without holding a {@code RecordingSupport}
     * can sanitize at the source instead of forcing every caller to wrap.
     *
     * <p>Cost: typically microseconds per chat line — one {@code byte[]}
     * and one {@code String} allocation; truncation is a {@code substring}.
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

    public Instant now() {
        return Instant.now();
    }

    public Instant expiresAt(Instant occurred) {
        return retention.after(occurred);
    }

    public Source playerSource(Player player) {
        return Source.player(player.getUniqueId(), player.getName());
    }

    public Origin playerOrigin() {
        return Origin.player();
    }

    public Source environmentSource(String description) {
        return Source.environment(description);
    }

    public Origin environmentOrigin(String description) {
        return Origin.environment(description);
    }

    public Source entitySource(java.util.UUID entityId, String entityType) {
        return Source.entity(entityId, entityType);
    }

    public UUID newId() {
        return fastRandomUUID();
    }

    /**
     * Time-ordered v7 UUID via {@link
     * net.medievalrp.spyglass.api.util.EventIds}. Same rationale this
     * method has always had — ThreadLocalRandom instead of {@link
     * UUID#randomUUID}'s SecureRandom, because record ids need
     * uniqueness, not unguessability, and SecureRandom's JNI hit showed
     * up on TNT-burst profiles — plus the storage reason that moved it
     * to v7: random v4 bytes are incompressible, and the id column was
     * the single largest consumer of store disk (#21).
     */
    public static UUID fastRandomUUID() {
        return net.medievalrp.spyglass.api.util.EventIds.newId();
    }

    /**
     * One-shot builder for the 7-field {@link RecordContext} header shared by
     * every record. Callers hand the result to a record's static {@code of()}
     * factory along with the type-specific fields.
     */
    public RecordContext context(Origin origin, Source source, BlockLocation location) {
        return context(Instant.now(), origin, source, location);
    }

    /**
     * Same as {@link #context(Origin, Source, BlockLocation)} but with an
     * explicit {@code occurred} timestamp — so a listener iterating blocks
     * from one event can share a single wall-clock across every record it
     * emits, even though each record gets its own UUID.
     */
    public RecordContext context(Instant occurred, Origin origin, Source source, BlockLocation location) {
        return RecordContext.fresh(occurred, retention.after(occurred), origin, source, location, serverName);
    }

    /** Shortcut: player-origin, player-source context for a located event. */
    public RecordContext playerContext(Player player, BlockLocation location) {
        return context(playerOrigin(), playerSource(player), location);
    }

    /** Shortcut: environment-origin, environment-source context. */
    public RecordContext environmentContext(String description, BlockLocation location) {
        return context(environmentOrigin(description), environmentSource(description), location);
    }
}

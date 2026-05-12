package net.medievalrp.spyglass.api.rollback;

/**
 * Extension point for restoring or reversing state that Spyglass's
 * built-in rollback effects can't model: faction territory, custom-
 * block bridges, plugin-managed NPCs, and so on. Pair with
 * {@link RollbackEffect.Custom} payloads on your event records.
 *
 * <h2>Registration</h2>
 *
 * <pre>{@code
 * api.registerRollbackEffectHandler(new MyEffectHandler());
 * }</pre>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #apply} runs on the main server thread alongside the
 * built-in effects. World mutations are safe; long-running I/O is
 * not. Keep the work bounded per call.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The {@link RollbackResult.Applied#inverseEffect()} you return is
 * persisted to the undo ledger and replayed when the operator runs
 * {@code /spyglass undo}. Build the inverse from the state you just
 * changed; the engine will hand it back to your handler.
 *
 * <h2>Versioning your payload</h2>
 *
 * <p>The payload is opaque to Spyglass. Embed your own version byte
 * or schema marker so future handler revisions can decode older
 * persisted entries.
 */
public interface RollbackEffectHandler {

    /**
     * The handler key. Must match {@link RollbackEffect.Custom#type()}
     * of effects this handler owns. Lowercased on registration;
     * case-insensitive at dispatch.
     */
    String type();

    /**
     * Apply a custom effect.
     *
     * @param effect the effect to apply; {@code effect.type()}
     *     equals {@link #type()}
     * @return {@link RollbackResult.Applied} on success (carrying
     *     the inverse effect for the undo ledger), or
     *     {@link RollbackResult.Skipped} with a reason on failure;
     *     never {@code null}
     */
    RollbackResult apply(RollbackEffect.Custom effect);
}

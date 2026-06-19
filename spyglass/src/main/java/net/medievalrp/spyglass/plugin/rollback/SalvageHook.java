package net.medievalrp.spyglass.plugin.rollback;

import org.bukkit.World;

/**
 * Lets the {@link RollbackEngine} hand off container inventories that a
 * rollback is about to destroy, so they can be salvaged instead of lost. The
 * engine knows nothing about storage or Bukkit inventories — it just signals
 * each chunk's lifecycle and the implementation does the capture.
 *
 * <p>All methods run on the main server thread. The hook is {@code null} in
 * unit tests and when salvage is disabled — the engine treats that as a no-op,
 * so this never affects the headless engine tests.
 *
 * <p>Capture-then-verify: {@link #onChunkResolved} snapshots a chunk's live
 * containers <em>before</em> its blocks are overwritten; {@link #onChunkWritten}
 * runs <em>after</em> and keeps only the ones the rollback actually destroyed
 * (discarding survivors), so nothing that still exists in the world is ever
 * salvaged — no item duplication.
 */
public interface SalvageHook {

    /** Mark the start of one rollback/restore: captures are attributed to the
     *  operator and grouped under {@code rollbackId}. */
    void begin(String operatorName, java.util.UUID rollbackId);

    /** Before a chunk's blocks are overwritten: snapshot its live containers. */
    void onChunkResolved(World world, int chunkX, int chunkZ);

    /** After a chunk's blocks are written: persist the containers that were
     *  actually destroyed; discard the ones that survived unchanged. */
    void onChunkWritten(World world, int chunkX, int chunkZ);

    /** End of the operation — drop any per-run state. */
    void end();
}

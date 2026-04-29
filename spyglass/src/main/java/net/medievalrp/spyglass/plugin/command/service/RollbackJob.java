package net.medievalrp.spyglass.plugin.command.service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * One rollback or restore job in the queue. Carries the operator
 * identity, the query that defined the op, mutable progress counters
 * the engine updates as it walks through chunks, and a cancellation
 * flag the engine checks at chunk boundaries.
 *
 * <p>Lives in {@link RollbackJobQueue}; created when a player or the
 * console submits {@code /sg rollback ...}. The job's lifecycle:
 * {@code PENDING} → {@code RUNNING} → {@code DONE} | {@code CANCELLED}
 * | {@code FAILED}.
 */
@ApiStatus.Internal
public final class RollbackJob {

    public enum State { PENDING, RUNNING, DONE, CANCELLED, FAILED }
    public enum Mode { ROLLBACK, RESTORE }

    public final UUID id;
    public final UUID operatorId;       // null for console
    public final String operatorName;
    public final String query;          // raw query string for display
    public final Mode mode;
    public final Instant submitTime;
    public final CommandSender sender;  // weak-ish — may go offline mid-job

    public final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    public final AtomicInteger appliedCount = new AtomicInteger(0);
    public final AtomicInteger skippedCount = new AtomicInteger(0);

    public volatile State state = State.PENDING;
    public volatile Instant startTime;
    public volatile Instant endTime;
    public volatile @Nullable String failureMessage;

    public RollbackJob(UUID id, @Nullable UUID operatorId, String operatorName,
                       String query, Mode mode, Instant submitTime,
                       CommandSender sender) {
        this.id = id;
        this.operatorId = operatorId;
        this.operatorName = operatorName;
        this.query = query;
        this.mode = mode;
        this.submitTime = submitTime;
        this.sender = sender;
    }

    /** Short-id for display: the first 8 hex chars of the UUID. */
    public String shortId() {
        return id.toString().substring(0, 8);
    }
}

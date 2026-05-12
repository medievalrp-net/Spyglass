package net.medievalrp.spyglass.plugin.command.service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

// One rollback or restore job. Lifecycle:
// PENDING -> RUNNING -> DONE / CANCELLED / FAILED.
@ApiStatus.Internal
public final class RollbackJob {

    public enum State { PENDING, RUNNING, DONE, CANCELLED, FAILED }
    public enum Mode { ROLLBACK, RESTORE }

    public final UUID id;
    public final UUID operatorId;       // null for console
    public final String operatorName;
    public final String query;
    public final Mode mode;
    public final Instant submitTime;
    public final CommandSender sender;  // may go offline mid-job

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

    public String shortId() {
        return id.toString().substring(0, 8);
    }
}

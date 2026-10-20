package net.medievalrp.spyglass.platform;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.crusalis.platform.sdk.api.EventSlot;
import net.crusalis.platform.sdk.api.EventType;
import net.crusalis.platform.sdk.api.Platform;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;

/**
 * Publishes committed Spyglass records to the Crusalis fabric.
 *
 * <p>Everything about this class is written around one rule: <b>Spyglass must not break
 * because the platform is having a bad day.</b> Spyglass is the forensic record of
 * record; the Crusalis copy is a convenience. So every publish is wrapped, every
 * failure is counted rather than thrown, and a storm of failures turns the integration
 * off instead of filling the console for an hour.
 *
 * <p>Thread confinement: {@link #publish(EventRecord)} is called from whatever thread
 * committed the record, which is usually the main thread and sometimes Spyglass's
 * ingest drain. The type-handle cache is therefore synchronized on first use per type
 * and read without a lock afterwards; the SDK's own publish path is lock-free.
 */
final class PlatformPublisher {

    /**
     * Errors per minute that turn the integration off. Round, and the spec's number.
     * The reasoning behind the shape: one bad record type produces an error per event,
     * so the threshold has to be well above a normal tick's event count — and once it
     * is clearly a systematic failure rather than a bad record, continuing to try costs
     * the main thread something per event for no benefit.
     */
    static final int ERROR_BUDGET_PER_MINUTE = 1_000;

    private final Platform platform;
    private final PlatformSettings settings;
    private final Consumer<String> warn;
    private final Map<String, EventType> handles = new HashMap<>();
    private final AtomicBoolean disabled = new AtomicBoolean();

    private volatile long windowStartNanos = System.nanoTime();
    private volatile int errorsThisWindow;

    private long published;
    private long dropped;
    private long unmapped;

    PlatformPublisher(Platform platform, PlatformSettings settings, Consumer<String> warn) {
        this.platform = platform;
        this.settings = settings;
        this.warn = warn;
    }

    /**
     * Publishes one record, or decides not to.
     *
     * @return true when an event was handed to the SDK
     */
    boolean publish(EventRecord record) {
        if (disabled.get() || record == null) {
            return false;
        }
        try {
            return publishChecked(record);
        } catch (Throwable t) {
            // Per record, deliberately broad: a NoSuchMethodError from a version skew
            // between this module and the SDK plugin would otherwise propagate into
            // Spyglass's own commit path and take a record with it.
            countError(t);
            return false;
        }
    }

    private boolean publishChecked(EventRecord record) {
        String key = record.event();
        if (!settings.publishes(key)) {
            return false;
        }

        // A rollback's own synthesized records are dropped whatever the include list
        // says: the rollback job publishes one event of its own, and these would
        // double every action it reversed.
        if (record.origin() != null && Origin.ROLLBACK.equals(record.origin().kind())
                && !"rollback-op".equalsIgnoreCase(key)) {
            return false;
        }
        if ("rollback-op".equalsIgnoreCase(key) && !settings.publishRollbacks()) {
            return false;
        }

        String type = ActionMap.type(key);
        if (type == null) {
            if (ActionMap.ignored(key) == null) {
                // Spyglass has gained an action key since this map was written. Said
                // once, because the alternative is a log line per event.
                unmapped++;
                if (unmapped == 1) {
                    warn.accept("Spyglass action '" + key + "' has no Crusalis event type;"
                            + " it will not be published. Add it to ActionMap and register"
                            + " a schema. Further unmapped actions will not be logged.");
                }
            }
            return false;
        }

        EventType handle = handle(type);
        if (!handle.shouldPublish()) {
            // Disabled, sampled out or rate limited by the registry's flags. Already
            // counted on the SDK's side; nothing to do here.
            return false;
        }
        try (EventSlot slot = platform.claim(handle)) {
            if (slot == null) {
                dropped++;
                return false;
            }
            RecordMapper.write(record, slot, settings);
            published++;
            return true;
        }
    }

    /** Publishes a payload the aggregators build themselves, with no source record. */
    boolean publishAggregate(String type, Consumer<EventSlot> fill) {
        if (disabled.get()) {
            return false;
        }
        try {
            EventType handle = handle(type);
            if (!handle.shouldPublish()) {
                return false;
            }
            try (EventSlot slot = platform.claim(handle)) {
                if (slot == null) {
                    dropped++;
                    return false;
                }
                fill.accept(slot);
                published++;
                return true;
            }
        } catch (Throwable t) {
            countError(t);
            return false;
        }
    }

    private EventType handle(String type) {
        EventType cached = handles.get(type);
        if (cached != null) {
            return cached;
        }
        synchronized (handles) {
            return handles.computeIfAbsent(type, platform::type);
        }
    }

    private void countError(Throwable t) {
        long now = System.nanoTime();
        if (now - windowStartNanos > 60_000_000_000L) {
            windowStartNanos = now;
            errorsThisWindow = 0;
        }
        int errors = ++errorsThisWindow;
        if (errors == 1) {
            warn.accept("Crusalis platform publish failed: " + t);
        }
        if (errors >= ERROR_BUDGET_PER_MINUTE && disabled.compareAndSet(false, true)) {
            warn.accept("Crusalis platform integration disabled after "
                    + ERROR_BUDGET_PER_MINUTE + " publish failures in a minute; the last was "
                    + t + ". Spyglass's own storage is unaffected."
                    + " Fix the cause and restart the server, or /spyglass reload.");
        }
    }

    /** Whether the integration has turned itself off. */
    boolean isDisabled() {
        return disabled.get();
    }

    /** For the test: pretend the error window has elapsed. */
    void resetWindowForTest() {
        windowStartNanos = System.nanoTime() - 61_000_000_000L;
    }

    long published() {
        return published;
    }

    long dropped() {
        return dropped;
    }

    long unmapped() {
        return unmapped;
    }
}

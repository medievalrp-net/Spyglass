package net.medievalrp.spyglass.api.rollback;

import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.util.BlockLocation;

public sealed interface RollbackReason permits
        RollbackReason.InvalidLocation,
        RollbackReason.BlockChanged,
        RollbackReason.MissingData,
        RollbackReason.NotSupported,
        RollbackReason.Guarded,
        RollbackReason.Error {

    String message();

    record InvalidLocation(BlockLocation location) implements RollbackReason {
        @Override
        public String message() {
            return "invalid location";
        }
    }

    record BlockChanged(BlockLocation location, BlockSnapshot expected, BlockSnapshot actual) implements RollbackReason {
        @Override
        public String message() {
            return "block changed";
        }
    }

    record MissingData(String field) implements RollbackReason {
        @Override
        public String message() {
            return "missing data: " + field;
        }
    }

    record NotSupported(String detail) implements RollbackReason {
        @Override
        public String message() {
            return detail;
        }
    }

    /**
     * A safety guard declined the write on purpose - e.g. the container
     * slot drifted from the recorded state, so applying would clobber
     * live changes. Benign by definition: this is the guard working,
     * not a failure, and summaries must not count it as an error (#306).
     */
    record Guarded(String detail) implements RollbackReason {
        @Override
        public String message() {
            return detail;
        }
    }

    record Error(String detail) implements RollbackReason {
        @Override
        public String message() {
            return detail;
        }
    }
}

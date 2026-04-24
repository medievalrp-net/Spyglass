package net.medievalrp.omniscience2.api.rollback;

import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.util.BlockLocation;

public sealed interface RollbackReason permits
        RollbackReason.InvalidLocation,
        RollbackReason.BlockChanged,
        RollbackReason.MissingData,
        RollbackReason.NotSupported,
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

    record Error(String detail) implements RollbackReason {
        @Override
        public String message() {
            return detail;
        }
    }
}

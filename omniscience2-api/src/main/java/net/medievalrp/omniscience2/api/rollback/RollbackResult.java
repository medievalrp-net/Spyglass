package net.medievalrp.omniscience2.api.rollback;

public sealed interface RollbackResult permits RollbackResult.Applied, RollbackResult.Skipped {

    record Applied(RollbackEffect effect, RollbackEffect inverseEffect) implements RollbackResult {
    }

    record Skipped(RollbackEffect effect, RollbackReason reason) implements RollbackResult {
    }
}

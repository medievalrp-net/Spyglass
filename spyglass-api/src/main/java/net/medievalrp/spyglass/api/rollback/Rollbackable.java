package net.medievalrp.spyglass.api.rollback;

public interface Rollbackable {

    RollbackEffect rollbackEffect();

    RollbackEffect restoreEffect();
}

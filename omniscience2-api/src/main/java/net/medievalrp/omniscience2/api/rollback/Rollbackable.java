package net.medievalrp.omniscience2.api.rollback;

public interface Rollbackable {

    RollbackEffect rollbackEffect();

    RollbackEffect restoreEffect();
}

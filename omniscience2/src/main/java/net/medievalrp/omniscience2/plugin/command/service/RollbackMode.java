package net.medievalrp.omniscience2.plugin.command.service;

public enum RollbackMode {
    ROLLBACK,
    RESTORE;

    public String label() {
        return name().toLowerCase();
    }
}

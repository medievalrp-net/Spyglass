package net.medievalrp.spyglass.plugin.command.service;

public enum RollbackMode {
    ROLLBACK,
    RESTORE;

    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}

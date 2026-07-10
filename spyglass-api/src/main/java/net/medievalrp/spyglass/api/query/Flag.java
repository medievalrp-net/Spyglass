package net.medievalrp.spyglass.api.query;

public enum Flag {
    NO_GROUP,
    GLOBAL,
    NO_CHAT,
    EXTENDED,
    /**
     * Rollback/restore also touches container blocks and contents (#287).
     * Absent (the default), a rollback never places a container, never
     * overwrites a live one, and never reverts container transactions.
     */
    INCLUDE_CONTAINERS,
    /**
     * Rollback/restore also spawns/removes entities (#287). Absent (the
     * default), death records produce no resurrections or removals.
     */
    INCLUDE_ENTITIES
}

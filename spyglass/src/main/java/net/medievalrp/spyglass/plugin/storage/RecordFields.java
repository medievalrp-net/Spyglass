package net.medievalrp.spyglass.plugin.storage;

final class RecordFields {

    static final String EVENT = "event";
    static final String OCCURRED = "occurred";
    static final String EXPIRES_AT = "expiresAt";
    static final String TARGET = "target";
    static final String MESSAGE = "message";
    static final String SOURCE_PLAYER_ID = "source.playerId";
    static final String LOCATION_WORLD_ID = "location.worldId";
    static final String LOCATION_X = "location.x";
    static final String LOCATION_Y = "location.y";
    static final String LOCATION_Z = "location.z";

    // Heavy snapshot fields, dropped by the summary projection.
    static final String ORIGINAL_BLOCK = "originalBlock";
    static final String NEW_BLOCK = "newBlock";
    static final String BEFORE_ITEM = "beforeItem";
    static final String AFTER_ITEM = "afterItem";
    static final String ITEM = "item";

    private RecordFields() {
    }
}

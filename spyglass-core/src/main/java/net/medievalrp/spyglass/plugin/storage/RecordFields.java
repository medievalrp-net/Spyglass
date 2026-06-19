package net.medievalrp.spyglass.plugin.storage;

final class RecordFields {

    static final String ID = "id";
    static final String EVENT = "event";
    static final String OCCURRED = "occurred";
    static final String EXPIRES_AT = "expiresAt";
    static final String TARGET = "target";
    static final String MESSAGE = "message";
    static final String SOURCE_PLAYER_ID = "source.playerId";
    static final String LOCATION = "location";
    static final String LOCATION_WORLD_ID = "location.worldId";
    static final String LOCATION_X = "location.x";
    static final String LOCATION_Y = "location.y";
    static final String LOCATION_Z = "location.z";

    // Sub-fields of the embedded location document, read directly by the
    // lean rollback path (which reads raw BSON, not "location.x" dot paths).
    static final String WORLD_ID = "worldId";
    static final String WORLD_NAME = "worldName";
    static final String X = "x";
    static final String Y = "y";
    static final String Z = "z";

    // Heavy snapshot fields, dropped by the summary projection.
    static final String ORIGINAL_BLOCK = "originalBlock";
    static final String NEW_BLOCK = "newBlock";
    static final String BEFORE_ITEM = "beforeItem";
    static final String AFTER_ITEM = "afterItem";
    static final String ITEM = "item";

    // BlockSnapshot sub-fields the lean rollback path reads without
    // materializing the whole snapshot for simple (no tile-entity) blocks.
    static final String BLOCK_DATA = "blockData";
    static final String SIMPLE = "simple";

    // Container / entity-death record fields the lean rollback path reads.
    static final String SLOT = "slot";
    static final String ENTITY_TYPE = "entityType";
    static final String ENTITY_ID = "entityId";
    static final String ENTITY_NBT = "entityNbt";

    private RecordFields() {
    }
}

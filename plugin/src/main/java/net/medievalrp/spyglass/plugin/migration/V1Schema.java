package net.medievalrp.spyglass.plugin.migration;

import java.util.Set;

public final class V1Schema {

    public static final String DEFAULT_DATABASE = "v1";
    public static final String DEFAULT_COLLECTION = "DataEntry";
    public static final String PROGRESS_COLLECTION = "MigrationProgress";
    public static final String PROGRESS_ID = "v1-migration";

    public static final String F_EVENT = "Event";
    public static final String F_CREATED = "Created";
    public static final String F_EXPIRES = "Expires";
    public static final String F_PLAYER = "Player";
    public static final String F_CAUSE = "Cause";
    public static final String F_TARGET = "Target";
    public static final String F_LOCATION = "Location";
    public static final String F_ORIGINAL_BLOCK = "OriginalBlock";
    public static final String F_NEW_BLOCK = "NewBlock";
    public static final String F_MATERIAL_TYPE = "MaterialType";
    public static final String F_BLOCK_DATA = "BlockData";
    public static final String F_INVENTORY = "Inventory";
    public static final String F_SIGN_TEXT = "SignText";
    public static final String F_BANNER_PATTERNS = "BannerPatterns";
    public static final String F_RECORD = "Record";
    public static final String F_MESSAGE = "Message";
    public static final String F_RECIPIENT = "Recipient";
    public static final String F_IP_ADDRESS = "IpAddress";
    public static final String F_SLOT = "ItemSlot";
    public static final String F_QUANTITY = "Quantity";
    public static final String F_BEFORE = "Before";
    public static final String F_AFTER = "After";
    public static final String F_ITEMSTACK = "ItemStack";
    public static final String F_HOLDER = "Holder";
    public static final String F_DISPLAY_METHOD = "DisplayMethod";
    public static final String F_ORIGIN = "Origin";
    public static final String F_RECORD_VERSION = "_v";

    public static final String F_LOC_X = "X";
    public static final String F_LOC_Y = "Y";
    public static final String F_LOC_Z = "Z";
    public static final String F_LOC_WORLD = "World";

    public static final String F_DAMAGE_CAUSE = "DamageCause";
    public static final String F_FROM = "From";
    public static final String F_TO = "To";
    public static final String F_CAUSE_TYPE = "CauseType";

    public static final String F_ENTITY_TYPE = "EntityType";
    public static final String F_ENTITY_ID = "EntityId";
    public static final String F_VICTIM_TYPE = "VictimType";
    public static final String F_VICTIM_ID = "VictimId";
    public static final String F_DAMAGE = "Damage";
    public static final String F_PROJECTILE = "Projectile";
    public static final String F_MOUNT_TYPE = "MountType";
    public static final String F_MOUNT_ID = "MountId";

    public static final Set<String> DEFERRED_EVENTS = Set.of(
            "bookshelf-insert", "bookshelf-remove",
            "pot-insert", "pot-remove",
            "brush", "sculk", "crafter", "vault",
            "shulker-open", "shulker-close", "shulker-deposit", "shulker-withdraw",
            "bundle-insert", "bundle-extract",
            "entity-deposit", "entity-withdraw",
            "usesign", "named", "craft", "clone", "close", "open", "use");

    private V1Schema() {
    }
}

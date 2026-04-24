package net.medievalrp.spyglass.plugin.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.CommandRecord;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EntityHitRecord;
import net.medievalrp.spyglass.api.event.EntityMountRecord;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.QuitRecord;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.event.TeleportRecord;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class V1ToV2TranslatorTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final Date CREATED = new Date(1_700_000_000_000L);
    private static final Date EXPIRES = new Date(1_700_000_000_000L + 604_800_000L);

    private final V1ItemDecoder stubDecoder = new StubItemDecoder();
    private final V1ToV2Translator translator = new V1ToV2Translator(
            stubDecoder, WorldNameLookup.usingUuid(), Logger.getLogger("test"));

    @Test
    void translatesBreak() {
        Document doc = baseDoc("break")
                .append("Target", "STONE")
                .append("OriginalBlock", new Document("MaterialType", "STONE")
                        .append("BlockData", "minecraft:stone"))
                .append("NewBlock", new Document("MaterialType", "AIR")
                        .append("BlockData", "minecraft:air"));

        V1ToV2Translator.Result result = translator.translate(doc);

        assertThat(result.kind()).isEqualTo(V1ToV2Translator.Result.Kind.TRANSLATED);
        BlockBreakRecord record = (BlockBreakRecord) result.record();
        assertThat(record.event()).isEqualTo("break");
        assertThat(record.target()).isEqualTo("STONE");
        assertThat(record.source().playerId()).isEqualTo(ALICE);
        assertThat(record.location().worldId()).isEqualTo(WORLD);
        assertThat(record.location().x()).isEqualTo(10);
        assertThat(record.originalBlock().blockData()).isEqualTo("minecraft:stone");
    }

    @Test
    void translatesPlace() {
        Document doc = baseDoc("place")
                .append("Target", "GLASS")
                .append("OriginalBlock", new Document("MaterialType", "AIR")
                        .append("BlockData", "minecraft:air"))
                .append("NewBlock", new Document("MaterialType", "GLASS")
                        .append("BlockData", "minecraft:glass"));

        V1ToV2Translator.Result result = translator.translate(doc);
        BlockPlaceRecord record = (BlockPlaceRecord) result.record();
        assertThat(record.event()).isEqualTo("place");
        assertThat(record.newBlock().blockData()).isEqualTo("minecraft:glass");
    }

    @Test
    void translatesEnvironmentDecayAsBlockBreak() {
        Document doc = baseDoc("decay")
                .append("Player", null)
                .append("Cause", "environment")
                .append("Target", "OAK_LEAVES")
                .append("OriginalBlock", new Document("MaterialType", "OAK_LEAVES")
                        .append("BlockData", "minecraft:oak_leaves"));
        V1ToV2Translator.Result result = translator.translate(doc);
        BlockBreakRecord record = (BlockBreakRecord) result.record();
        assertThat(record.event()).isEqualTo("decay");
        assertThat(record.source().kind()).isEqualTo(Source.ENVIRONMENT);
        assertThat(record.origin().kind()).isEqualTo(Origin.ENVIRONMENT);
    }

    @Test
    void translatesSay() {
        Document doc = baseDoc("say")
                .append("Message", "hello regression")
                .append("Recipient", ALICE.toString() + "," + UUID.randomUUID());
        V1ToV2Translator.Result result = translator.translate(doc);
        ChatRecord record = (ChatRecord) result.record();
        assertThat(record.message()).isEqualTo("hello regression");
        assertThat(record.recipients()).hasSize(2);
    }

    @Test
    void translatesCommand() {
        Document doc = baseDoc("command")
                .append("Message", "/tp Alice 0 64 0");
        V1ToV2Translator.Result result = translator.translate(doc);
        CommandRecord record = (CommandRecord) result.record();
        assertThat(record.commandLine()).isEqualTo("/tp Alice 0 64 0");
    }

    @Test
    void translatesJoin() {
        Document doc = baseDoc("join").append("IpAddress", "127.0.0.1");
        V1ToV2Translator.Result result = translator.translate(doc);
        JoinRecord record = (JoinRecord) result.record();
        assertThat(record.address()).isEqualTo("127.0.0.1");
    }

    @Test
    void translatesQuit() {
        Document doc = baseDoc("quit");
        V1ToV2Translator.Result result = translator.translate(doc);
        assertThat(result.record()).isInstanceOf(QuitRecord.class);
    }

    @Test
    void translatesDeposit() {
        Document doc = baseDoc("deposit")
                .append("Target", "CHEST")
                .append("Holder", "CHEST")
                .append("ItemSlot", 3)
                .append("Quantity", 5)
                .append("Inventory", new Document("Before", null)
                        .append("After", new Document("ItemStack",
                                new Document("type", "DIAMOND").append("amount", 5))));
        V1ToV2Translator.Result result = translator.translate(doc);
        ContainerDepositRecord record = (ContainerDepositRecord) result.record();
        assertThat(record.slot()).isEqualTo(3);
        assertThat(record.afterItem()).isNotNull();
        assertThat(record.afterItem().material()).isEqualTo("DIAMOND");
    }

    @Test
    void translatesWithdraw() {
        Document doc = baseDoc("withdraw")
                .append("Target", "CHEST")
                .append("Holder", "CHEST")
                .append("ItemSlot", 0)
                .append("Quantity", 1)
                .append("Inventory", new Document("Before", new Document("ItemStack",
                        new Document("type", "STONE").append("amount", 1))).append("After", null));
        V1ToV2Translator.Result result = translator.translate(doc);
        ContainerWithdrawRecord record = (ContainerWithdrawRecord) result.record();
        assertThat(record.containerType()).isEqualTo("CHEST");
        assertThat(record.beforeItem()).isNotNull();
    }

    @Test
    void translatesDrop() {
        Document doc = baseDoc("drop")
                .append("Target", "STONE")
                .append("Quantity", 3)
                .append("ItemStack", new Document("type", "STONE").append("amount", 3));
        V1ToV2Translator.Result result = translator.translate(doc);
        ItemDropRecord record = (ItemDropRecord) result.record();
        assertThat(record.amount()).isEqualTo(3);
        assertThat(record.item().material()).isEqualTo("STONE");
    }

    @Test
    void translatesPickup() {
        Document doc = baseDoc("pickup")
                .append("Target", "DIAMOND")
                .append("Quantity", 1)
                .append("ItemStack", new Document("type", "DIAMOND").append("amount", 1));
        V1ToV2Translator.Result result = translator.translate(doc);
        ItemPickupRecord record = (ItemPickupRecord) result.record();
        assertThat(record.item().material()).isEqualTo("DIAMOND");
    }

    @Test
    void translatesTeleport() {
        Document doc = baseDoc("teleport")
                .append("Target", "Alice")
                .append("CauseType", "COMMAND")
                .append("From", new Document("X", 10).append("Y", 64).append("Z", 10).append("World", WORLD.toString()))
                .append("To", new Document("X", 500).append("Y", 70).append("Z", 500).append("World", WORLD.toString()));
        V1ToV2Translator.Result result = translator.translate(doc);
        TeleportRecord record = (TeleportRecord) result.record();
        assertThat(record.cause()).isEqualTo("COMMAND");
        assertThat(record.to().x()).isEqualTo(500);
    }

    @Test
    void translatesDeath() {
        Document doc = baseDoc("death")
                .append("Target", "ZOMBIE")
                .append("EntityType", "ZOMBIE")
                .append("EntityId", UUID.randomUUID().toString())
                .append("CauseType", "player")
                .append("DamageCause", "ENTITY_ATTACK");
        V1ToV2Translator.Result result = translator.translate(doc);
        EntityDeathRecord record = (EntityDeathRecord) result.record();
        assertThat(record.entityType()).isEqualTo("ZOMBIE");
        assertThat(record.damageCause()).isEqualTo("ENTITY_ATTACK");
    }

    @Test
    void translatesHit() {
        Document doc = baseDoc("hit")
                .append("Target", "ZOMBIE")
                .append("VictimType", "ZOMBIE")
                .append("VictimId", UUID.randomUUID().toString())
                .append("Damage", 5.0);
        EntityHitRecord record = (EntityHitRecord) translator.translate(doc).record();
        assertThat(record.projectile()).isFalse();
        assertThat(record.damage()).isEqualTo(5.0);
    }

    @Test
    void translatesShotAsProjectileHit() {
        Document doc = baseDoc("shot")
                .append("Target", "SKELETON")
                .append("VictimType", "SKELETON")
                .append("VictimId", UUID.randomUUID().toString())
                .append("Damage", 2.0)
                .append("Projectile", "arrow");
        EntityHitRecord record = (EntityHitRecord) translator.translate(doc).record();
        assertThat(record.projectile()).isTrue();
        assertThat(record.projectileType()).isEqualTo("arrow");
    }

    @Test
    void translatesMount() {
        Document doc = baseDoc("mount")
                .append("Target", "HORSE")
                .append("MountType", "HORSE")
                .append("MountId", UUID.randomUUID().toString());
        EntityMountRecord record = (EntityMountRecord) translator.translate(doc).record();
        assertThat(record.dismount()).isFalse();
        assertThat(record.mountType()).isEqualTo("HORSE");
    }

    @Test
    void translatesDismount() {
        Document doc = baseDoc("dismount")
                .append("Target", "HORSE")
                .append("MountType", "HORSE")
                .append("MountId", UUID.randomUUID().toString());
        EntityMountRecord record = (EntityMountRecord) translator.translate(doc).record();
        assertThat(record.dismount()).isTrue();
    }

    @Test
    void deferredEventsSkippedNotFailed() {
        for (String event : List.of("bookshelf-insert", "pot-remove", "brush", "sculk",
                "crafter", "vault", "shulker-open", "bundle-insert",
                "entity-deposit", "entity-withdraw", "useSign", "named", "craft",
                "clone", "close", "open", "use")) {
            Document doc = baseDoc(event);
            V1ToV2Translator.Result result = translator.translate(doc);
            assertThat(result.kind())
                    .as("event %s", event)
                    .isEqualTo(V1ToV2Translator.Result.Kind.SKIPPED_DEFERRED);
        }
    }

    @Test
    void unknownEventTypeSkipped() {
        Document doc = baseDoc("teapot");
        V1ToV2Translator.Result result = translator.translate(doc);
        assertThat(result.kind()).isEqualTo(V1ToV2Translator.Result.Kind.SKIPPED_UNKNOWN);
    }

    @Test
    void missingEventSkipped() {
        Document doc = new Document("Player", ALICE.toString());
        V1ToV2Translator.Result result = translator.translate(doc);
        assertThat(result.kind()).isEqualTo(V1ToV2Translator.Result.Kind.SKIPPED_UNKNOWN);
    }

    @Test
    void pluginSourceParsed() {
        Document doc = baseDoc("break")
                .append("Player", null)
                .append("Cause", "pl@MyPlugin")
                .append("OriginalBlock", new Document("MaterialType", "STONE").append("BlockData", "minecraft:stone"));
        Source source = ((BlockBreakRecord) translator.translate(doc).record()).source();
        assertThat(source.kind()).isEqualTo(Source.PLUGIN);
        assertThat(source.pluginName()).isEqualTo("MyPlugin");
    }

    @Test
    void originFaweTagPreserved() {
        Document doc = baseDoc("break")
                .append("Origin", "fawe")
                .append("OriginalBlock", new Document("MaterialType", "STONE").append("BlockData", "minecraft:stone"));
        Origin origin = ((BlockBreakRecord) translator.translate(doc).record()).origin();
        assertThat(origin.kind()).isEqualTo(Origin.FAWE);
    }

    @Test
    void failureKindSet() {
        // Missing Location will throw for events requiring location
        Document doc = new Document("Event", "break")
                .append("Created", CREATED)
                .append("Expires", EXPIRES)
                .append("Player", ALICE.toString());
        V1ToV2Translator.Result result = translator.translate(doc);
        assertThat(result.kind()).isEqualTo(V1ToV2Translator.Result.Kind.FAILED);
    }

    private Document baseDoc(String event) {
        return new Document("Event", event)
                .append("Created", CREATED)
                .append("Expires", EXPIRES)
                .append("Player", ALICE.toString())
                .append("Location", new Document("X", 10).append("Y", 64).append("Z", 10)
                        .append("World", WORLD.toString()));
    }

    private static final class StubItemDecoder implements V1ItemDecoder {
        @Override
        public Optional<StoredItem> decode(int slot, Map<String, Object> itemDoc) {
            if (itemDoc == null) {
                return Optional.empty();
            }
            Object type = itemDoc.get("type");
            if (type == null) {
                return Optional.empty();
            }
            return Optional.of(new StoredItem(slot, String.valueOf(type), null));
        }
    }
}

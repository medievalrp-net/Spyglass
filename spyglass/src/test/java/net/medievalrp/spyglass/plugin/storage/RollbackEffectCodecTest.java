package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.MongoClientSettings;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.BsonString;
import org.bson.UuidRepresentation;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.UuidCodecProvider;
import org.bson.codecs.configuration.CodecConfigurationException;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.jsr310.Jsr310CodecProvider;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.codecs.record.RecordCodecProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/**
 * Encode/decode contract for {@link RollbackEffectCodec}. The codec writes
 * a {@code _type} discriminator on every record and requires it on read —
 * we deleted the no-discriminator field-shape fallback once UndoStack's
 * 24h TTL guaranteed no pre-discriminator documents could persist past
 * deploy + 1d. These tests pin that contract.
 */
class RollbackEffectCodecTest {

    private static final CodecRegistry REGISTRY = CodecRegistries.fromRegistries(
            CodecRegistries.fromProviders(new UuidCodecProvider(UuidRepresentation.STANDARD)),
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(
                    new Jsr310CodecProvider(),
                    new RecordCodecProvider(),
                    RollbackEffectCodec.provider(),
                    PojoCodecProvider.builder().automatic(true).build()));

    private static final Codec<RollbackEffect> CODEC = REGISTRY.get(RollbackEffect.class);

    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private static BsonDocument encode(RollbackEffect effect) {
        BsonDocument doc = new BsonDocument();
        try (BsonDocumentWriter writer = new BsonDocumentWriter(doc)) {
            CODEC.encode(writer, effect, EncoderContext.builder().build());
        }
        return doc;
    }

    private static RollbackEffect decode(BsonDocument doc) {
        try (BsonDocumentReader reader = new BsonDocumentReader(doc)) {
            return CODEC.decode(reader, DecoderContext.builder().build());
        }
    }

    private static RollbackEffect.BlockReplace blockReplace() {
        BlockSnapshot stone = new BlockSnapshot(
                org.bukkit.Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        return new RollbackEffect.BlockReplace(
                new BlockLocation(WORLD, "world", 1, 64, 1), stone, air);
    }

    @Test
    void roundTripsBlockReplace() {
        RollbackEffect.BlockReplace original = blockReplace();

        RollbackEffect decoded = decode(encode(original));

        assertThat(decoded).isInstanceOf(RollbackEffect.BlockReplace.class).isEqualTo(original);
    }

    @Test
    void roundTripsContainerSlotWrite() {
        StoredItem before = new StoredItem(0, "DIAMOND", "<base64>", "Diamond", List.of(), List.of());
        StoredItem after = new StoredItem(0, "AIR", null, null, List.of(), List.of());
        RollbackEffect.ContainerSlotWrite original = new RollbackEffect.ContainerSlotWrite(
                new BlockLocation(WORLD, "world", 0, 0, 0), 3, before, after);

        RollbackEffect decoded = decode(encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void roundTripsCustom() {
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        RollbackEffect.Custom original = new RollbackEffect.Custom(
                "faction-territory",
                new BlockLocation(WORLD, "world", 9, 70, -3),
                payload);

        RollbackEffect decoded = decode(encode(original));

        assertThat(decoded).isInstanceOf(RollbackEffect.Custom.class);
        RollbackEffect.Custom roundTripped = (RollbackEffect.Custom) decoded;
        assertThat(roundTripped.type()).isEqualTo("faction-territory");
        assertThat(roundTripped.location()).isEqualTo(original.location());
        assertThat(roundTripped.payload()).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void roundTripsEntitySpawnAndRemove() {
        RollbackEffect.EntitySpawn spawn = new RollbackEffect.EntitySpawn(
                new BlockLocation(WORLD, "world", 5, 64, 5), "ZOMBIE", "<nbt-bytes>");
        RollbackEffect.EntityRemove remove = new RollbackEffect.EntityRemove(
                new BlockLocation(WORLD, "world", 5, 64, 5), "ZOMBIE",
                "00000000-0000-0000-0000-00000000abcd");

        assertThat(decode(encode(spawn))).isEqualTo(spawn);
        assertThat(decode(encode(remove))).isEqualTo(remove);
    }

    @Test
    void encodeStampsTypeDiscriminator() {
        BsonDocument doc = encode(blockReplace());

        assertThat(doc.getString("_type").getValue()).isEqualTo("BlockReplace");
    }

    @Test
    void decodeRejectsDocumentWithoutDiscriminator() {
        BsonDocument doc = encode(blockReplace());
        doc.remove("_type");

        assertThatThrownBy(() -> decode(doc))
                .isInstanceOf(CodecConfigurationException.class)
                .hasMessageContaining("_type");
    }

    @Test
    void decodeRejectsUnknownDiscriminator() {
        BsonDocument doc = encode(blockReplace());
        doc.put("_type", new BsonString("MysteryEffect"));

        assertThatThrownBy(() -> decode(doc))
                .isInstanceOf(CodecConfigurationException.class)
                .hasMessageContaining("Unknown")
                .hasMessageContaining("MysteryEffect");
    }
}

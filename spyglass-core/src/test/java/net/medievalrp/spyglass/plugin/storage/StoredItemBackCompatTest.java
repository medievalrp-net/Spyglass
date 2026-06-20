package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;
import net.medievalrp.spyglass.api.event.StoredItem;
import org.bson.BsonBinaryReader;
import org.bson.BsonBinaryWriter;
import org.bson.BsonDocument;
import org.bson.ByteBufNIO;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.bson.io.ByteBufferBsonInput;
import org.junit.jupiter.api.Test;

/**
 * Back-compat guard for the {@code tags} custom-data projection (#140):
 * records written before the field existed have no {@code tags} key in their
 * BSON, and the auto-generated {@link StoredItem} record codec must decode
 * that absence as {@code null} rather than throwing, otherwise every
 * pre-#140 deposit/withdraw/container-snapshot row stored on Mongo, the
 * ClickHouse item columns, or the SQLite per-event blob would fail to read.
 */
class StoredItemBackCompatTest {

    @Test
    void decodesLegacyDocumentWithoutTagsKeyAsNull() {
        // Encode a current item, then strip the tags key from its BSON to
        // mimic a record serialized before the projection was added.
        StoredItem full = new StoredItem(2, "DIAMOND_SWORD", "blob",
                "Storm Caller", List.of("Forged deep"), List.of("sharpness=5"),
                "{quest:\"x\"}");
        BsonDocument wrapper = readBson(Base64.getDecoder().decode(BsonBlobs.encodeStoredItem(full)));
        wrapper.getDocument("v").remove("tags");
        String legacy = Base64.getEncoder().encodeToString(writeBson(wrapper));

        StoredItem decoded = BsonBlobs.decodeStoredItem(legacy);

        assertThat(decoded).isNotNull();
        assertThat(decoded.tags()).as("absent tags key must decode to null").isNull();
        // The rest of the record must still round-trip unharmed.
        assertThat(decoded.slot()).isEqualTo(2);
        assertThat(decoded.material()).isEqualTo("DIAMOND_SWORD");
        assertThat(decoded.name()).isEqualTo("Storm Caller");
        assertThat(decoded.lore()).containsExactly("Forged deep");
        assertThat(decoded.enchants()).containsExactly("sharpness=5");
    }

    @Test
    void currentItemRoundTripsTags() {
        StoredItem item = new StoredItem(0, "PAPER", "blob", null, List.of(), List.of(),
                "{PublicBukkitValues:{\"mmoitems:type\":\"SWORD\"}}");
        StoredItem decoded = BsonBlobs.decodeStoredItem(BsonBlobs.encodeStoredItem(item));
        assertThat(decoded).isNotNull();
        assertThat(decoded.tags()).isEqualTo("{PublicBukkitValues:{\"mmoitems:type\":\"SWORD\"}}");
    }

    private static BsonDocument readBson(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try (BsonBinaryReader reader = new BsonBinaryReader(
                new ByteBufferBsonInput(new ByteBufNIO(buf)))) {
            return new BsonDocumentCodec().decode(reader, DecoderContext.builder().build());
        }
    }

    private static byte[] writeBson(BsonDocument document) {
        BasicOutputBuffer buffer = new BasicOutputBuffer();
        try (BsonBinaryWriter writer = new BsonBinaryWriter(buffer)) {
            new BsonDocumentCodec().encode(writer, document, EncoderContext.builder().build());
        }
        return buffer.toByteArray();
    }
}

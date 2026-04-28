package net.medievalrp.spyglass.plugin.storage;

import com.mongodb.MongoClientSettings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import org.bson.BsonArray;
import org.bson.BsonBinaryReader;
import org.bson.BsonBinaryWriter;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.BsonValue;
import org.bson.UuidRepresentation;
import org.bson.codecs.BsonValueCodecProvider;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.UuidCodec;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.jsr310.Jsr310CodecProvider;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.codecs.record.RecordCodecProvider;
import org.bson.io.BasicOutputBuffer;
import org.bson.io.ByteBufferBsonInput;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * BSON encode / decode for the nested record fields that don't
 * decompose into ClickHouse columns cleanly:
 * {@link BlockSnapshot}, {@link StoredItem}, {@link RollbackEffect}.
 *
 * <p>The Mongo codec hierarchy already knows how to serialize these
 * shapes (BlockSnapshot has nested container items + sign text +
 * banner patterns; StoredItem has lore + enchant arrays; RollbackEffect
 * is sealed). Reusing those codecs here means the CH backend never
 * has to ship a parallel Java↔CH serializer for nested types.
 *
 * <p>Output format is raw BSON bytes (the same on-wire shape Mongo
 * stores), wrapped one layer deep in a {@code {"v": <value>}}
 * BsonDocument so codecs that emit non-document top-level values
 * (e.g., the StoredItem record codec writes a document but keeping
 * the wrapper makes the read side uniform).
 */
@ApiStatus.Internal
public final class BsonBlobs {

    private static final String VALUE = "v";

    private static final CodecRegistry REGISTRY = CodecRegistries.fromRegistries(
            CodecRegistries.fromCodecs(new UuidCodec(UuidRepresentation.STANDARD)),
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(
                    new BsonValueCodecProvider(),
                    new Jsr310CodecProvider(),
                    new RecordCodecProvider(),
                    RollbackEffectCodec.provider(),
                    PojoCodecProvider.builder().automatic(true).build()));

    private static final Codec<BlockSnapshot> BLOCK_SNAPSHOT_CODEC =
            REGISTRY.get(BlockSnapshot.class);
    private static final Codec<StoredItem> STORED_ITEM_CODEC =
            REGISTRY.get(StoredItem.class);
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final Codec<RollbackEffect> ROLLBACK_EFFECT_CODEC =
            (Codec) REGISTRY.get(RollbackEffect.class);

    private BsonBlobs() {
    }

    public static @Nullable String encodeBlockSnapshot(@Nullable BlockSnapshot value) {
        return value == null ? null : toBase64(encode(BLOCK_SNAPSHOT_CODEC, value));
    }

    public static @Nullable BlockSnapshot decodeBlockSnapshot(@Nullable String base64) {
        byte[] bytes = fromBase64(base64);
        return bytes == null ? null : decode(BLOCK_SNAPSHOT_CODEC, bytes);
    }

    public static @Nullable String encodeStoredItem(@Nullable StoredItem value) {
        return value == null ? null : toBase64(encode(STORED_ITEM_CODEC, value));
    }

    public static @Nullable StoredItem decodeStoredItem(@Nullable String base64) {
        byte[] bytes = fromBase64(base64);
        return bytes == null ? null : decode(STORED_ITEM_CODEC, bytes);
    }

    /**
     * Encode a list of polymorphic {@link RollbackEffect}s as a single
     * BSON array. RollbackEffectCodec handles the sealed-hierarchy
     * dispatch; we just wrap it in a one-key document so the on-disk
     * shape is symmetric with the single-value blobs.
     */
    public static String encodeRollbackEffectsBase64(List<RollbackEffect> effects) {
        return toBase64(encodeRollbackEffects(effects));
    }

    public static List<RollbackEffect> decodeRollbackEffectsBase64(@Nullable String base64) {
        byte[] bytes = fromBase64(base64);
        return bytes == null ? List.of() : decodeRollbackEffects(bytes);
    }

    static byte[] encodeRollbackEffects(List<RollbackEffect> effects) {
        BsonArray array = new BsonArray();
        for (RollbackEffect effect : effects) {
            BsonDocument wrapper = new BsonDocument();
            try (BsonDocumentWriter writer = new BsonDocumentWriter(wrapper)) {
                writer.writeStartDocument();
                writer.writeName(VALUE);
                ROLLBACK_EFFECT_CODEC.encode(writer, effect, EncoderContext.builder().build());
                writer.writeEndDocument();
            }
            array.add(wrapper.get(VALUE));
        }
        BsonDocument document = new BsonDocument().append("effects", array);
        return documentToBytes(document);
    }

    static List<RollbackEffect> decodeRollbackEffects(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        BsonDocument document = bytesToDocument(bytes);
        BsonArray array = document.getArray("effects");
        List<RollbackEffect> out = new java.util.ArrayList<>(array.size());
        for (BsonValue value : array) {
            BsonDocument wrapper = new BsonDocument().append(VALUE, value);
            try (BsonDocumentReader reader = new BsonDocumentReader(wrapper)) {
                reader.readStartDocument();
                reader.readName();
                out.add(ROLLBACK_EFFECT_CODEC.decode(reader, DecoderContext.builder().build()));
                reader.readEndDocument();
            }
        }
        return out;
    }

    private static <T> byte[] encode(Codec<T> codec, T value) {
        BasicOutputBuffer buffer = new BasicOutputBuffer();
        try (BsonBinaryWriter writer = new BsonBinaryWriter(buffer)) {
            writer.writeStartDocument();
            writer.writeName(VALUE);
            codec.encode(writer, value, EncoderContext.builder().build());
            writer.writeEndDocument();
        }
        return buffer.toByteArray();
    }

    private static <T> T decode(Codec<T> codec, byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try (BsonBinaryReader reader = new BsonBinaryReader(new ByteBufferBsonInput(
                new org.bson.ByteBufNIO(buf)))) {
            reader.readStartDocument();
            reader.readName();
            T result = codec.decode(reader, DecoderContext.builder().build());
            reader.readEndDocument();
            return result;
        }
    }

    private static byte[] documentToBytes(BsonDocument document) {
        BasicOutputBuffer buffer = new BasicOutputBuffer();
        try (BsonBinaryWriter writer = new BsonBinaryWriter(buffer)) {
            REGISTRY.get(BsonDocument.class).encode(writer, document, EncoderContext.builder().build());
        }
        return buffer.toByteArray();
    }

    private static BsonDocument bytesToDocument(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try (BsonBinaryReader reader = new BsonBinaryReader(new ByteBufferBsonInput(
                new org.bson.ByteBufNIO(buf)))) {
            return REGISTRY.get(BsonDocument.class).decode(reader, DecoderContext.builder().build());
        }
    }

    private static String toBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte @Nullable [] fromBase64(@Nullable String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        return Base64.getDecoder().decode(base64);
    }

    @SuppressWarnings("unused")
    static ByteArrayInputStream wrap(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    @SuppressWarnings("unused")
    static ByteArrayOutputStream newBuffer() {
        return new ByteArrayOutputStream();
    }
}

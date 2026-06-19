package net.medievalrp.spyglass.plugin.storage;

import com.mongodb.MongoClientSettings;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.EventRecord;
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
                    // Dispatches the sealed EventRecord hierarchy on the
                    // `event` field, so a whole record round-trips as one
                    // BSON document — the SQLite backend's per-event blob.
                    EventRecordCodec.provider(),
                    RollbackEffectCodec.provider(),
                    PojoCodecProvider.builder().automatic(true).build()));

    private static final Codec<BlockSnapshot> BLOCK_SNAPSHOT_CODEC =
            REGISTRY.get(BlockSnapshot.class);
    private static final Codec<StoredItem> STORED_ITEM_CODEC =
            REGISTRY.get(StoredItem.class);
    private static final Codec<EventRecord> EVENT_RECORD_CODEC =
            REGISTRY.get(EventRecord.class);
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

    /**
     * Serialize only the tile-entity portion of {@code value} —
     * containerItems, sign front/back, banner patterns, jukebox record,
     * decorated-pot sherds. Returns {@code null} when every field is
     * empty/absent (the 99% case for plain blocks like stone, dirt,
     * air), so the CH column stays NULL and costs ~1 byte/row instead
     * of a 200-500 byte BSON BlockSnapshot blob. Material + blockData
     * are stored in their own LowCardinality columns and explicitly
     * NOT encoded here.
     */
    public static @Nullable String encodeBlockExtras(@Nullable BlockSnapshot value) {
        if (value == null || hasNoExtras(value)) {
            return null;
        }
        BsonDocument document = new BsonDocument();
        if (!value.containerItems().isEmpty()) {
            BsonArray items = new BsonArray();
            for (StoredItem item : value.containerItems()) {
                BsonDocument wrapper = new BsonDocument();
                try (BsonDocumentWriter writer = new BsonDocumentWriter(wrapper)) {
                    writer.writeStartDocument();
                    writer.writeName(VALUE);
                    STORED_ITEM_CODEC.encode(writer, item, EncoderContext.builder().build());
                    writer.writeEndDocument();
                }
                items.add(wrapper.get(VALUE));
            }
            document.append("containerItems", items);
        }
        if (!value.signFront().isEmpty()) {
            document.append("signFront", stringArray(value.signFront()));
        }
        if (!value.signBack().isEmpty()) {
            document.append("signBack", stringArray(value.signBack()));
        }
        if (!value.bannerPatterns().isEmpty()) {
            document.append("bannerPatterns", stringArray(value.bannerPatterns()));
        }
        if (value.jukeboxRecord() != null) {
            document.append("jukeboxRecord", new org.bson.BsonString(value.jukeboxRecord()));
        }
        if (!value.potSherds().isEmpty()) {
            document.append("potSherds", stringArray(value.potSherds()));
        }
        return toBase64(documentToBytes(document));
    }

    /**
     * Reconstruct a {@link BlockSnapshot} from a {@code material} +
     * {@code blockData} pair (read from their own LowCardinality
     * columns) plus an optional {@code extrasBase64} blob produced by
     * {@link #encodeBlockExtras}. When {@code extrasBase64} is null
     * the snapshot has all-empty tile-entity fields — fast path for
     * the common case.
     */
    public static @Nullable BlockSnapshot decodeBlockSnapshotFromColumns(
            @Nullable String material, @Nullable String blockData, @Nullable String extrasBase64) {
        if (material == null || blockData == null) {
            return null;
        }
        org.bukkit.Material mat;
        try {
            mat = org.bukkit.Material.valueOf(material);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (extrasBase64 == null || extrasBase64.isEmpty()) {
            return new BlockSnapshot(mat, blockData,
                    List.of(), List.of(), List.of(), List.of(), null, List.of());
        }
        byte[] bytes = fromBase64(extrasBase64);
        if (bytes == null) {
            return new BlockSnapshot(mat, blockData,
                    List.of(), List.of(), List.of(), List.of(), null, List.of());
        }
        BsonDocument document = bytesToDocument(bytes);
        List<StoredItem> containerItems = List.of();
        if (document.containsKey("containerItems")) {
            BsonArray array = document.getArray("containerItems");
            List<StoredItem> out = new java.util.ArrayList<>(array.size());
            for (BsonValue value : array) {
                BsonDocument wrapper = new BsonDocument().append(VALUE, value);
                try (BsonDocumentReader reader = new BsonDocumentReader(wrapper)) {
                    reader.readStartDocument();
                    reader.readName();
                    out.add(STORED_ITEM_CODEC.decode(reader, DecoderContext.builder().build()));
                    reader.readEndDocument();
                }
            }
            containerItems = out;
        }
        List<String> signFront = readStringArray(document, "signFront");
        List<String> signBack = readStringArray(document, "signBack");
        List<String> bannerPatterns = readStringArray(document, "bannerPatterns");
        String jukeboxRecord = document.containsKey("jukeboxRecord")
                ? document.getString("jukeboxRecord").getValue() : null;
        List<String> potSherds = readStringArray(document, "potSherds");
        return new BlockSnapshot(mat, blockData,
                containerItems, signFront, signBack, bannerPatterns, jukeboxRecord, potSherds);
    }

    private static boolean hasNoExtras(BlockSnapshot value) {
        return value.containerItems().isEmpty()
                && value.signFront().isEmpty()
                && value.signBack().isEmpty()
                && value.bannerPatterns().isEmpty()
                && value.jukeboxRecord() == null
                && value.potSherds().isEmpty();
    }

    private static BsonArray stringArray(List<String> values) {
        BsonArray array = new BsonArray(values.size());
        for (String value : values) {
            array.add(new org.bson.BsonString(value));
        }
        return array;
    }

    private static List<String> readStringArray(BsonDocument document, String key) {
        if (!document.containsKey(key)) {
            return List.of();
        }
        BsonArray array = document.getArray(key);
        List<String> out = new java.util.ArrayList<>(array.size());
        for (BsonValue value : array) {
            out.add(value.asString().getValue());
        }
        return out;
    }

    public static @Nullable String encodeStoredItem(@Nullable StoredItem value) {
        return value == null ? null : toBase64(encode(STORED_ITEM_CODEC, value));
    }

    public static @Nullable StoredItem decodeStoredItem(@Nullable String base64) {
        byte[] bytes = fromBase64(base64);
        return bytes == null ? null : decode(STORED_ITEM_CODEC, bytes);
    }

    /**
     * Serialize a whole {@link EventRecord} to BSON bytes (no base64) for
     * the SQLite backend's per-event blob. The concrete record codec
     * writes the {@code event} field, so {@link #decodeRecordBytes}
     * dispatches the sealed hierarchy on it — the same self-describing
     * shape Mongo stores, minus the document wrapper. Callers compress the
     * returned bytes before persisting them.
     */
    public static byte[] encodeRecordBytes(EventRecord record) {
        BasicOutputBuffer buffer = new BasicOutputBuffer();
        try (BsonBinaryWriter writer = new BsonBinaryWriter(buffer)) {
            EVENT_RECORD_CODEC.encode(writer, record, EncoderContext.builder().build());
        }
        return buffer.toByteArray();
    }

    /** Inverse of {@link #encodeRecordBytes}. */
    public static EventRecord decodeRecordBytes(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try (BsonBinaryReader reader = new BsonBinaryReader(new ByteBufferBsonInput(
                new org.bson.ByteBufNIO(buf)))) {
            return EVENT_RECORD_CODEC.decode(reader, DecoderContext.builder().build());
        }
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
        // Stream the array straight through one binary writer. The
        // previous shape built a BsonDocument TREE first (one boxed
        // BsonValue node per field, ~25K wrapper documents per undo
        // chunk) and then serialized it — measured at ~12µs/effect of
        // mostly allocation, it dominated large-rollback undo capture
        // and didn't parallelize (the encode threads just contended in
        // the allocator). The bytes are identical: same document shape,
        // same codec, same field order.
        BasicOutputBuffer buffer = new BasicOutputBuffer();
        try (BsonBinaryWriter writer = new BsonBinaryWriter(buffer)) {
            writer.writeStartDocument();
            writer.writeStartArray("effects");
            EncoderContext context = EncoderContext.builder().build();
            for (RollbackEffect effect : effects) {
                ROLLBACK_EFFECT_CODEC.encode(writer, effect, context);
            }
            writer.writeEndArray();
            writer.writeEndDocument();
        }
        return buffer.toByteArray();
    }

    static List<RollbackEffect> decodeRollbackEffects(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        List<RollbackEffect> out = new java.util.ArrayList<>();
        try (BsonBinaryReader reader = new BsonBinaryReader(new ByteBufferBsonInput(
                new org.bson.ByteBufNIO(buf)))) {
            reader.readStartDocument();
            reader.readName(); // "effects"
            reader.readStartArray();
            DecoderContext context = DecoderContext.builder().build();
            while (reader.readBsonType() != org.bson.BsonType.END_OF_DOCUMENT) {
                out.add(ROLLBACK_EFFECT_CODEC.decode(reader, context));
            }
            reader.readEndArray();
            reader.readEndDocument();
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
}

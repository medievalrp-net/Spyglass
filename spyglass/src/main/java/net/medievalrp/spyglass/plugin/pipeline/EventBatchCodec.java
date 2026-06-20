package net.medievalrp.spyglass.plugin.pipeline;

import com.mongodb.MongoClientSettings;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.plugin.storage.EventRecordCodec;
import net.medievalrp.spyglass.plugin.storage.RollbackEffectCodec;
import org.bson.BsonArray;
import org.bson.BsonBinaryReader;
import org.bson.BsonBinaryWriter;
import org.bson.BsonDocument;
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

/**
 * BSON encode/decode for an {@link EventRecord} batch as a single
 * self-describing byte block. Shared by the on-disk pipeline paths —
 * {@link WalDurability} (drain-side write-ahead) and {@link SpillBuffer}
 * (producer-side overflow) — so both round-trip records through the exact
 * same polymorphic {@link EventRecordCodec} the storage backends use.
 *
 * <p>Layout: one BSON document {@code { r: [ <record>, <record>, ... ] }};
 * each element is written via a one-key {@code { v: <record-doc> }} wrapper
 * so the sealed-record codec encodes/decodes identically to the storage path.
 */
@ApiStatus.Internal
final class EventBatchCodec {

    private static final String RECORDS_KEY = "r";
    private static final String VALUE_KEY = "v";

    private static final CodecRegistry REGISTRY = CodecRegistries.fromRegistries(
            CodecRegistries.fromCodecs(new UuidCodec(UuidRepresentation.STANDARD)),
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(
                    new BsonValueCodecProvider(),
                    new Jsr310CodecProvider(),
                    new RecordCodecProvider(),
                    EventRecordCodec.provider(),
                    RollbackEffectCodec.provider(),
                    PojoCodecProvider.builder().automatic(true).build()));

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final Codec<EventRecord> EVENT_RECORD_CODEC =
            (Codec) REGISTRY.get(EventRecord.class);

    private EventBatchCodec() {
    }

    /**
     * Encode a batch as a single BSON document {@code { r: [ <record>, ... ] }}.
     *
     * <p>Streams each record straight through the {@link BsonBinaryWriter} into
     * the output buffer — it does NOT first build a {@code BsonArray} of
     * {@code BsonDocument}s. The materialized-tree approach allocated ~3x the
     * batch as a `LinkedHashMap`-backed BSON object graph, which dominated the
     * heap while several batches spilled in parallel (#125); streaming drops the
     * transient to roughly the serialized buffer. The bytes are identical, so
     * {@link #decode} and on-disk WAL/spill segments are unchanged.
     */
    static byte[] encode(List<EventRecord> batch) {
        EncoderContext ctx = EncoderContext.builder().build();
        BasicOutputBuffer buffer = new BasicOutputBuffer();
        try (BsonBinaryWriter writer = new BsonBinaryWriter(buffer)) {
            writer.writeStartDocument();
            writer.writeStartArray(RECORDS_KEY);
            for (EventRecord record : batch) {
                // Codec writes a document per record directly as an array
                // element — same shape the old wrap-then-unwrap produced.
                EVENT_RECORD_CODEC.encode(writer, record, ctx);
            }
            writer.writeEndArray();
            writer.writeEndDocument();
        }
        return buffer.toByteArray();
    }

    /** Decode a batch previously produced by {@link #encode}. */
    static List<EventRecord> decode(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        BsonDocument document;
        try (BsonBinaryReader reader = new BsonBinaryReader(new ByteBufferBsonInput(
                new org.bson.ByteBufNIO(buf)))) {
            document = REGISTRY.get(BsonDocument.class)
                    .decode(reader, DecoderContext.builder().build());
        }
        BsonArray array = document.getArray(RECORDS_KEY);
        List<EventRecord> out = new ArrayList<>(array.size());
        for (BsonValue value : array) {
            BsonDocument wrapper = new BsonDocument().append(VALUE_KEY, value);
            try (org.bson.BsonDocumentReader reader = new org.bson.BsonDocumentReader(wrapper)) {
                reader.readStartDocument();
                reader.readName();
                out.add(EVENT_RECORD_CODEC.decode(reader, DecoderContext.builder().build()));
                reader.readEndDocument();
            }
        }
        return out;
    }
}

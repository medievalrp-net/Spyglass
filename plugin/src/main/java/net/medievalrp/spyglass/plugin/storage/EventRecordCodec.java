package net.medievalrp.spyglass.plugin.storage;

import java.util.HashMap;
import java.util.Map;
import net.medievalrp.spyglass.api.event.EventCatalog;
import net.medievalrp.spyglass.api.event.EventRecord;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.BsonReader;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.BsonWriter;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecConfigurationException;
import org.bson.codecs.configuration.CodecProvider;
import org.bson.codecs.configuration.CodecRegistry;
import org.jetbrains.annotations.ApiStatus;

/**
 * Polymorphic codec for the sealed {@link EventRecord} hierarchy. The
 * POJO / record codec alone can't decode a sealed interface because
 * the stored document doesn't name which permits to instantiate; this
 * codec stamps a {@code _class} discriminator on write and dispatches
 * to the concrete record codec via the registry on read.
 *
 * <p>Lets {@link MongoRecordStore} hand the driver a single
 * {@code MongoCollection<EventRecord>} and round-trip all 17 permits
 * through it — one query, one sort, no in-JVM merge of per-type
 * result sets.
 *
 * <p>Documents written before the discriminator existed (v1.0.0 and
 * prior wave-7 test data) fall back to event-name lookup via
 * {@link EventCatalog#recordClassOf(String)}, so upgrading in place
 * keeps old rows readable. Mirrors the same shape as
 * {@link RollbackEffectCodec}, which did this for the sealed
 * RollbackEffect hierarchy first.
 */
@ApiStatus.Internal
final class EventRecordCodec implements Codec<EventRecord> {

    private static final String TYPE_FIELD = "_class";
    private static final BsonDocumentCodec BSON_DOC_CODEC = new BsonDocumentCodec();

    /**
     * Simple-name → record class, precomputed from EventCatalog at
     * load so we don't reflect on every decode.
     */
    private static final Map<String, Class<? extends EventRecord>> BY_SIMPLE_NAME;

    static {
        Map<String, Class<? extends EventRecord>> m = new HashMap<>();
        for (Class<? extends EventRecord> clazz : EventCatalog.recordClasses()) {
            m.put(clazz.getSimpleName(), clazz);
        }
        BY_SIMPLE_NAME = Map.copyOf(m);
    }

    private final CodecRegistry registry;

    EventRecordCodec(CodecRegistry registry) {
        this.registry = registry;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static CodecProvider provider() {
        return new CodecProvider() {
            @Override
            public <T> Codec<T> get(Class<T> clazz, CodecRegistry registry) {
                if (EventRecord.class.equals(clazz)) {
                    return (Codec<T>) (Codec) new EventRecordCodec(registry);
                }
                return null;
            }
        };
    }

    @Override
    public Class<EventRecord> getEncoderClass() {
        return EventRecord.class;
    }

    @Override
    public void encode(BsonWriter writer, EventRecord value, EncoderContext ctx) {
        BsonDocument buffer = new BsonDocument();
        try (BsonDocumentWriter bufWriter = new BsonDocumentWriter(buffer)) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Codec<EventRecord> concrete = (Codec) registry.get(value.getClass());
            concrete.encode(bufWriter, value, ctx);
        }
        buffer.put(TYPE_FIELD, new BsonString(value.getClass().getSimpleName()));
        BSON_DOC_CODEC.encode(writer, buffer, ctx);
    }

    @Override
    public EventRecord decode(BsonReader reader, DecoderContext ctx) {
        BsonDocument doc = BSON_DOC_CODEC.decode(reader, ctx);
        Class<? extends EventRecord> target = pickType(doc);
        if (target == null) {
            throw new CodecConfigurationException(
                    "Cannot determine EventRecord subtype for stored document:"
                            + " _class=" + doc.get(TYPE_FIELD)
                            + " event=" + doc.get("event"));
        }
        doc.remove(TYPE_FIELD);
        try (BsonDocumentReader docReader = new BsonDocumentReader(doc)) {
            return registry.get(target).decode(docReader, ctx);
        }
    }

    private static Class<? extends EventRecord> pickType(BsonDocument doc) {
        BsonValue discriminator = doc.get(TYPE_FIELD);
        if (discriminator != null && discriminator.isString()) {
            Class<? extends EventRecord> t = BY_SIMPLE_NAME.get(discriminator.asString().getValue());
            if (t != null) {
                return t;
            }
        }
        // Fallback for pre-discriminator documents: the event-name field
        // maps to a single concrete record class via EventCatalog.
        BsonValue event = doc.get("event");
        if (event != null && event.isString()) {
            return EventCatalog.recordClassOf(event.asString().getValue());
        }
        return null;
    }
}

package net.medievalrp.spyglass.plugin.storage;

import java.util.HashMap;
import java.util.Map;
import net.medievalrp.spyglass.api.event.EventCatalog;
import net.medievalrp.spyglass.api.event.EventRecord;
import org.bson.BsonReader;
import org.bson.BsonReaderMark;
import org.bson.BsonType;
import org.bson.BsonWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecConfigurationException;
import org.bson.codecs.configuration.CodecProvider;
import org.bson.codecs.configuration.CodecRegistry;
import org.jetbrains.annotations.ApiStatus;

/**
 * Polymorphic codec for the sealed {@link EventRecord} hierarchy.
 *
 * <p>The driver's record codec alone can't decode a sealed interface: the
 * stored document doesn't tell the codec which permit to instantiate. This
 * codec dispatches to the matching concrete record codec by reading the
 * {@code event} field from the BSON stream — {@link EventCatalog} gives us
 * a total name -> record-class map, so {@code event} is a complete
 * discriminator on its own.
 *
 * <p>Earlier revisions stamped an additional {@code _class} field to
 * simplify dispatch, but that required a full in-memory BsonDocument
 * detour on every encode <em>and</em> decode — the intermediate tree was
 * the largest per-record allocation in the search hot path. We no longer
 * write {@code _class}; the driver's RecordCodec silently skips unknown
 * fields, so any pre-existing {@code _class} entries still decode.
 *
 * <p>Mirror code lives in {@link RollbackEffectCodec} for the sealed
 * RollbackEffect hierarchy.
 */
@ApiStatus.Internal
final class EventRecordCodec implements Codec<EventRecord> {

    private static final String LEGACY_TYPE_FIELD = "_class";
    private static final String EVENT_FIELD = "event";

    /**
     * Simple-name -> record class, used only to read legacy documents that
     * were written with a {@code _class} discriminator.
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
        // Delegate straight to the concrete codec — no intermediate
        // BsonDocument buffer, no discriminator rewrite. The concrete codec
        // writes {@code event} itself as a record component, so decode can
        // dispatch on it without any extra field.
        @SuppressWarnings({"rawtypes", "unchecked"})
        Codec<EventRecord> concrete = (Codec) registry.get(value.getClass());
        concrete.encode(writer, value, ctx);
    }

    @Override
    public EventRecord decode(BsonReader reader, DecoderContext ctx) {
        // Mark -> scan for discriminator -> reset -> hand raw stream to the
        // concrete codec. No BsonDocument tree is ever allocated. Scan cost
        // is a handful of skipValue calls on fields we don't care about;
        // the whole doc is already in the driver's buffer.
        BsonReaderMark mark = reader.getMark();
        String eventName = null;
        String legacyClass = null;
        reader.readStartDocument();
        while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
            String fieldName = reader.readName();
            switch (fieldName) {
                case EVENT_FIELD -> eventName = reader.readString();
                case LEGACY_TYPE_FIELD -> legacyClass = reader.readString();
                default -> reader.skipValue();
            }
            // Prefer `event`; it's authoritative. Stop scanning as soon as
            // we have it — no need to walk to END_OF_DOCUMENT.
            if (eventName != null) {
                break;
            }
        }
        mark.reset();

        Class<? extends EventRecord> target = null;
        if (eventName != null) {
            target = EventCatalog.recordClassOf(eventName);
        }
        if (target == null && legacyClass != null) {
            // Pre-EventCatalog docs without an event field fall back to the
            // simple-class-name lookup. Keeps the very oldest fixtures
            // decodable.
            target = BY_SIMPLE_NAME.get(legacyClass);
        }
        if (target == null) {
            throw new CodecConfigurationException(
                    "Cannot determine EventRecord subtype for stored document:"
                            + " event=" + eventName + " _class=" + legacyClass);
        }
        return registry.get(target).decode(reader, ctx);
    }
}

package net.medievalrp.spyglass.plugin.storage;

import net.medievalrp.spyglass.api.rollback.RollbackEffect;
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
 * Polymorphic codec for {@link RollbackEffect}. The POJO codec alone can't
 * decode a sealed interface because the stored document doesn't name which
 * implementation to instantiate; this codec adds a {@code _type} discriminator
 * on write and dispatches on it during read. Documents written before the
 * discriminator existed are still readable via a field-shape fallback so old
 * {@code UndoHistory} rows don't brick the undo stack.
 */
@ApiStatus.Internal
final class RollbackEffectCodec implements Codec<RollbackEffect> {

    private static final String TYPE_FIELD = "_type";
    private static final BsonDocumentCodec BSON_DOC_CODEC = new BsonDocumentCodec();

    private final CodecRegistry registry;

    RollbackEffectCodec(CodecRegistry registry) {
        this.registry = registry;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static CodecProvider provider() {
        return new CodecProvider() {
            @Override
            public <T> Codec<T> get(Class<T> clazz, CodecRegistry registry) {
                if (RollbackEffect.class.equals(clazz)) {
                    return (Codec<T>) (Codec) new RollbackEffectCodec(registry);
                }
                return null;
            }
        };
    }

    @Override
    public Class<RollbackEffect> getEncoderClass() {
        return RollbackEffect.class;
    }

    @Override
    public void encode(BsonWriter writer, RollbackEffect value, EncoderContext ctx) {
        BsonDocument buffer = new BsonDocument();
        try (BsonDocumentWriter bufWriter = new BsonDocumentWriter(buffer)) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Codec<RollbackEffect> concrete = (Codec) registry.get(value.getClass());
            concrete.encode(bufWriter, value, ctx);
        }
        buffer.put(TYPE_FIELD, new BsonString(value.getClass().getSimpleName()));
        BSON_DOC_CODEC.encode(writer, buffer, ctx);
    }

    @Override
    public RollbackEffect decode(BsonReader reader, DecoderContext ctx) {
        BsonDocument doc = BSON_DOC_CODEC.decode(reader, ctx);
        Class<? extends RollbackEffect> target = pickType(doc);
        if (target == null) {
            throw new CodecConfigurationException(
                    "Cannot determine RollbackEffect subtype for stored document: fields=" + doc.keySet());
        }
        doc.remove(TYPE_FIELD);
        try (BsonDocumentReader docReader = new BsonDocumentReader(doc)) {
            return registry.get(target).decode(docReader, ctx);
        }
    }

    private static Class<? extends RollbackEffect> pickType(BsonDocument doc) {
        BsonValue discriminator = doc.get(TYPE_FIELD);
        if (discriminator != null && discriminator.isString()) {
            return resolve(discriminator.asString().getValue());
        }
        // Fallback for documents written before the discriminator was added.
        if (doc.containsKey("slot")) {
            return RollbackEffect.ContainerSlotWrite.class;
        }
        if (doc.containsKey("serializedEntity")) {
            return RollbackEffect.EntitySpawn.class;
        }
        if (doc.containsKey("entityId")) {
            return RollbackEffect.EntityRemove.class;
        }
        if (doc.containsKey("expectedCurrent")) {
            return RollbackEffect.BlockReplace.class;
        }
        return null;
    }

    private static Class<? extends RollbackEffect> resolve(String name) {
        return switch (name) {
            case "BlockReplace" -> RollbackEffect.BlockReplace.class;
            case "ContainerSlotWrite" -> RollbackEffect.ContainerSlotWrite.class;
            case "EntitySpawn" -> RollbackEffect.EntitySpawn.class;
            case "EntityRemove" -> RollbackEffect.EntityRemove.class;
            default -> null;
        };
    }
}

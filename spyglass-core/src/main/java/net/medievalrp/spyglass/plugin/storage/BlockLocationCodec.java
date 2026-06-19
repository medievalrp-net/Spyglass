package net.medievalrp.spyglass.plugin.storage;

import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bson.BsonReader;
import org.bson.BsonType;
import org.bson.BsonWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecProvider;
import org.bson.codecs.configuration.CodecRegistry;
import org.jetbrains.annotations.ApiStatus;

/**
 * Storage codec for {@link BlockLocation} that writes two extra,
 * read-ignored fields: {@code cx = x >> 4} and {@code cz = z >> 4} — the
 * chunk coordinates. They exist only to back the chunk-bucketed rollback
 * location index {@code (worldId, cx, cz, occurred, id)}.
 *
 * <p>Chunk coordinates have a sixteenth of the range of block coordinates,
 * so consecutive records share a {@code (cx, cz)} far more often than a
 * raw {@code (x, z)}; the index prefix-compresses accordingly. On a 2M
 * rollback the location index dropped from ~96 MiB to ~25 MiB, which is
 * what brings the MongoDB footprint below both CoreProtect backends. The
 * exact {@code x}/{@code z}/{@code y} bounds still filter within the
 * seeked chunks (see {@link PredicateToBson}), so results are unchanged.
 *
 * <p>Decode ignores {@code cx}/{@code cz} and reconstructs them on the next
 * write, so the {@link BlockLocation} model stays free of storage-derived
 * fields and records written before these fields existed still decode. A
 * one-time backfill (see {@link MongoRecordStore}) populates them on
 * existing collections so the chunk index covers old data too.
 */
@ApiStatus.Internal
public final class BlockLocationCodec implements Codec<BlockLocation> {

    private final Codec<UUID> uuidCodec;

    BlockLocationCodec(CodecRegistry registry) {
        this.uuidCodec = registry.get(UUID.class);
    }

    public static CodecProvider provider() {
        return new CodecProvider() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Codec<T> get(Class<T> clazz, CodecRegistry registry) {
                if (BlockLocation.class.equals(clazz)) {
                    return (Codec<T>) new BlockLocationCodec(registry);
                }
                return null;
            }
        };
    }

    @Override
    public Class<BlockLocation> getEncoderClass() {
        return BlockLocation.class;
    }

    @Override
    public void encode(BsonWriter writer, BlockLocation value, EncoderContext ctx) {
        writer.writeStartDocument();
        writer.writeName(RecordFields.WORLD_ID);
        uuidCodec.encode(writer, value.worldId(), ctx);
        if (value.worldName() != null) {
            writer.writeString(RecordFields.WORLD_NAME, value.worldName());
        } else {
            writer.writeNull(RecordFields.WORLD_NAME);
        }
        writer.writeInt32(RecordFields.X, value.x());
        writer.writeInt32(RecordFields.Y, value.y());
        writer.writeInt32(RecordFields.Z, value.z());
        // Chunk bucket — index-only, ignored on decode. >> 4 matches the
        // backfill's floor(coord / 16) for negative coordinates too.
        writer.writeInt32(RecordFields.CX, value.x() >> 4);
        writer.writeInt32(RecordFields.CZ, value.z() >> 4);
        writer.writeEndDocument();
    }

    @Override
    public BlockLocation decode(BsonReader reader, DecoderContext ctx) {
        UUID worldId = null;
        String worldName = null;
        int x = 0;
        int y = 0;
        int z = 0;
        reader.readStartDocument();
        while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
            switch (reader.readName()) {
                case RecordFields.WORLD_ID -> worldId = uuidCodec.decode(reader, ctx);
                case RecordFields.WORLD_NAME -> {
                    if (reader.getCurrentBsonType() == BsonType.NULL) {
                        reader.readNull();
                    } else {
                        worldName = reader.readString();
                    }
                }
                case RecordFields.X -> x = reader.readInt32();
                case RecordFields.Y -> y = reader.readInt32();
                case RecordFields.Z -> z = reader.readInt32();
                default -> reader.skipValue(); // cx, cz, and any future field
            }
        }
        reader.readEndDocument();
        return new BlockLocation(worldId, worldName, x, y, z);
    }
}

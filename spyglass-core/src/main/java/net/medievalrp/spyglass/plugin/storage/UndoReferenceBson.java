package net.medievalrp.spyglass.plugin.storage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.Sort;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonBinarySubType;
import org.bson.BsonBoolean;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonBinaryReader;
import org.bson.BsonBinaryWriter;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.UuidRepresentation;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.bson.io.ByteBufferBsonInput;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Round-trip serialization of an undo <em>reference</em>: instead of a
 * ledger of inverse effects, an undo operation stores the resolved
 * {@link QueryRequest} it would take to replay the original operation
 * in the opposite direction, plus the operation mode and a time
 * ceiling. The event records themselves are the ledger.
 *
 * <p>The request must be the <b>resolved</b> one (relative times like
 * {@code t:30m} already anchored at the original parse) — re-parsing
 * raw query text at undo time would shift the window.
 *
 * <p>Versioned ({@code v:1}) so the blob can evolve. Predicate values
 * round-trip for the closed set of types the parser produces: String,
 * Integer, Long, Double, Boolean, UUID, Instant, null.
 */
@ApiStatus.Internal
public final class UndoReferenceBson {

    /**
     * Decoded reference: what to replay, within which time bound, and —
     * since v2 — where the operation landed (per-world bounding boxes)
     * and how big it was. v1 blobs decode with empty boxes and zero
     * counts.
     */
    public record Reference(QueryRequest request, String mode, Instant ceiling,
                            List<WorldBox> boxes, int applied, int skipped) {
        public Reference {
            boxes = List.copyOf(boxes);
        }
    }

    /** Inclusive block-coordinate bounding box of one world's writes. */
    public record WorldBox(UUID worldId, int minX, int minY, int minZ,
                           int maxX, int maxY, int maxZ) {
    }

    private UndoReferenceBson() {
    }

    /** v1-shaped convenience: no boxes, no counts (small ops, tests). */
    public static String encodeBase64(QueryRequest request, String mode, Instant ceiling) {
        return encodeBase64(request, mode, ceiling, List.of(), 0, 0);
    }

    public static String encodeBase64(QueryRequest request, String mode, Instant ceiling,
                                      List<WorldBox> boxes, int applied, int skipped) {
        BsonDocument doc = new BsonDocument();
        doc.append("v", new BsonInt32(2));
        doc.append("mode", new BsonString(mode));
        doc.append("ceiling", new BsonDateTime(ceiling.toEpochMilli()));
        doc.append("applied", new BsonInt32(applied));
        doc.append("skipped", new BsonInt32(skipped));
        BsonArray boxArray = new BsonArray();
        for (WorldBox box : boxes) {
            BsonDocument b = new BsonDocument();
            b.append("w", new BsonBinary(box.worldId(), UuidRepresentation.STANDARD));
            BsonArray bounds = new BsonArray();
            for (int bound : new int[]{box.minX(), box.minY(), box.minZ(),
                    box.maxX(), box.maxY(), box.maxZ()}) {
                bounds.add(new BsonInt32(bound));
            }
            b.append("b", bounds);
            boxArray.add(b);
        }
        doc.append("boxes", boxArray);
        doc.append("sort", new BsonString(request.sort().name()));
        doc.append("limit", new BsonInt32(request.limit()));
        doc.append("grouping", new BsonBoolean(request.grouping()));
        BsonArray flags = new BsonArray();
        for (Flag flag : request.flags()) {
            flags.add(new BsonString(flag.name()));
        }
        doc.append("flags", flags);
        BsonArray predicates = new BsonArray();
        for (QueryPredicate predicate : request.predicates()) {
            predicates.add(predicateToBson(predicate));
        }
        doc.append("predicates", predicates);
        BasicOutputBuffer buffer = new BasicOutputBuffer();
        try (BsonBinaryWriter writer = new BsonBinaryWriter(buffer)) {
            new BsonDocumentCodec().encode(writer, doc, EncoderContext.builder().build());
        }
        return Base64.getEncoder().encodeToString(buffer.toByteArray());
    }

    public static Reference decodeBase64(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        BsonDocument doc;
        try (BsonBinaryReader reader = new BsonBinaryReader(new ByteBufferBsonInput(
                new org.bson.ByteBufNIO(java.nio.ByteBuffer.wrap(bytes)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN))))) {
            doc = new BsonDocumentCodec().decode(reader, DecoderContext.builder().build());
        }
        int version = doc.getInt32("v").getValue();
        if (version != 1 && version != 2) {
            throw new IllegalStateException("Unknown undo reference version " + version);
        }
        String mode = doc.getString("mode").getValue();
        Instant ceiling = Instant.ofEpochMilli(doc.getDateTime("ceiling").getValue());
        Sort sort = Sort.valueOf(doc.getString("sort").getValue());
        int limit = doc.getInt32("limit").getValue();
        boolean grouping = doc.getBoolean("grouping").getValue();
        EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
        for (BsonValue value : doc.getArray("flags")) {
            flags.add(Flag.valueOf(value.asString().getValue()));
        }
        List<QueryPredicate> predicates = new ArrayList<>();
        for (BsonValue value : doc.getArray("predicates")) {
            predicates.add(predicateFromBson(value.asDocument()));
        }
        int applied = version >= 2 ? doc.getInt32("applied").getValue() : 0;
        int skipped = version >= 2 ? doc.getInt32("skipped").getValue() : 0;
        List<WorldBox> boxes = new ArrayList<>();
        if (version >= 2) {
            for (BsonValue value : doc.getArray("boxes")) {
                BsonDocument b = value.asDocument();
                List<BsonValue> bounds = b.getArray("b").getValues();
                boxes.add(new WorldBox(
                        b.getBinary("w").asUuid(UuidRepresentation.STANDARD),
                        bounds.get(0).asInt32().getValue(),
                        bounds.get(1).asInt32().getValue(),
                        bounds.get(2).asInt32().getValue(),
                        bounds.get(3).asInt32().getValue(),
                        bounds.get(4).asInt32().getValue(),
                        bounds.get(5).asInt32().getValue()));
            }
        }
        return new Reference(new QueryRequest(predicates, sort, limit, flags, grouping),
                mode, ceiling, boxes, applied, skipped);
    }

    private static BsonDocument predicateToBson(QueryPredicate predicate) {
        BsonDocument doc = new BsonDocument();
        switch (predicate) {
            case QueryPredicate.Eq eq -> {
                doc.append("t", new BsonString("eq"));
                doc.append("f", new BsonString(eq.field()));
                doc.append("v", valueToBson(eq.value()));
            }
            case QueryPredicate.In in -> {
                doc.append("t", new BsonString("in"));
                doc.append("f", new BsonString(in.field()));
                BsonArray values = new BsonArray();
                for (Object value : in.values()) {
                    values.add(valueToBson(value));
                }
                doc.append("vs", values);
            }
            case QueryPredicate.Range range -> {
                doc.append("t", new BsonString("range"));
                doc.append("f", new BsonString(range.field()));
                doc.append("lo", valueToBson(range.lowerInclusive()));
                doc.append("hi", valueToBson(range.upperInclusive()));
            }
            case QueryPredicate.Exists exists -> {
                doc.append("t", new BsonString("exists"));
                doc.append("f", new BsonString(exists.field()));
                doc.append("e", new BsonBoolean(exists.expected()));
            }
            case QueryPredicate.Not not -> {
                doc.append("t", new BsonString("not"));
                doc.append("p", predicateToBson(not.predicate()));
            }
            case QueryPredicate.And and -> {
                doc.append("t", new BsonString("and"));
                doc.append("ps", predicateArray(and.predicates()));
            }
            case QueryPredicate.Or or -> {
                doc.append("t", new BsonString("or"));
                doc.append("ps", predicateArray(or.predicates()));
            }
        }
        return doc;
    }

    private static BsonArray predicateArray(List<QueryPredicate> predicates) {
        BsonArray array = new BsonArray();
        for (QueryPredicate predicate : predicates) {
            array.add(predicateToBson(predicate));
        }
        return array;
    }

    private static QueryPredicate predicateFromBson(BsonDocument doc) {
        String type = doc.getString("t").getValue();
        return switch (type) {
            case "eq" -> new QueryPredicate.Eq(
                    doc.getString("f").getValue(), valueFromBson(doc.get("v")));
            case "in" -> {
                List<Object> values = new ArrayList<>();
                for (BsonValue value : doc.getArray("vs")) {
                    values.add(valueFromBson(value));
                }
                yield new QueryPredicate.In(doc.getString("f").getValue(), values);
            }
            case "range" -> new QueryPredicate.Range(
                    doc.getString("f").getValue(),
                    valueFromBson(doc.get("lo")),
                    valueFromBson(doc.get("hi")));
            case "exists" -> new QueryPredicate.Exists(
                    doc.getString("f").getValue(), doc.getBoolean("e").getValue());
            case "not" -> new QueryPredicate.Not(predicateFromBson(doc.getDocument("p")));
            case "and" -> new QueryPredicate.And(predicatesFrom(doc));
            case "or" -> new QueryPredicate.Or(predicatesFrom(doc));
            default -> throw new IllegalStateException("Unknown predicate type " + type);
        };
    }

    private static List<QueryPredicate> predicatesFrom(BsonDocument doc) {
        List<QueryPredicate> out = new ArrayList<>();
        for (BsonValue value : doc.getArray("ps")) {
            out.add(predicateFromBson(value.asDocument()));
        }
        return out;
    }

    // Typed envelope per value so decode restores the exact Java type
    // the parser produced (the store builders dispatch on it).
    private static BsonValue valueToBson(@Nullable Object value) {
        return switch (value) {
            case null -> BsonNull.VALUE;
            case String s -> new BsonDocument("s", new BsonString(s));
            case Integer i -> new BsonDocument("i", new BsonInt32(i));
            case Long l -> new BsonDocument("l", new BsonInt64(l));
            case Double d -> new BsonDocument("d", new BsonDouble(d));
            case Boolean b -> new BsonDocument("b", new BsonBoolean(b));
            case UUID u -> new BsonDocument("u", new BsonBinary(u, UuidRepresentation.STANDARD));
            case Instant t -> new BsonDocument("ts", new BsonDateTime(t.toEpochMilli()));
            default -> throw new IllegalStateException(
                    "Unsupported predicate value type: " + value.getClass().getName());
        };
    }

    private static @Nullable Object valueFromBson(BsonValue value) {
        if (value == null || value.isNull()) {
            return null;
        }
        BsonDocument doc = value.asDocument();
        String key = doc.getFirstKey();
        return switch (key) {
            case "s" -> doc.getString("s").getValue();
            case "i" -> doc.getInt32("i").getValue();
            case "l" -> doc.getInt64("l").getValue();
            case "d" -> doc.getDouble("d").getValue();
            case "b" -> doc.getBoolean("b").getValue();
            case "u" -> doc.getBinary("u").asUuid(UuidRepresentation.STANDARD);
            case "ts" -> Instant.ofEpochMilli(doc.getDateTime("ts").getValue());
            default -> throw new IllegalStateException("Unknown value envelope: " + key);
        };
    }
}

package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.MongoClientSettings;
import java.util.List;
import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bson.BsonDocument;
import org.bson.codecs.configuration.CodecRegistry;
import org.junit.jupiter.api.Test;

class PredicateToBsonTest {

    private static final CodecRegistry REGISTRY = MongoClientSettings.getDefaultCodecRegistry();
    private final PredicateToBson translator = new PredicateToBson();

    private BsonDocument asBson(Object filter) {
        return ((org.bson.conversions.Bson) filter)
                .toBsonDocument(BsonDocument.class, REGISTRY);
    }

    @Test
    void translatesEqToEquality() {
        BsonDocument doc = asBson(translator.translate(new QueryPredicate.Eq("event", "break")));
        assertThat(doc.toJson()).contains("\"event\"").contains("\"break\"");
    }

    @Test
    void translatesPatternEqToRegex() {
        BsonDocument doc = asBson(translator.translate(new QueryPredicate.Eq("target", Pattern.compile("STONE"))));
        assertThat(doc.toJson()).contains("$regularExpression");
    }

    @Test
    void translatesInToDollarIn() {
        BsonDocument doc = asBson(translator.translate(new QueryPredicate.In("event", List.of("break", "place"))));
        assertThat(doc.toJson()).contains("$in");
    }

    @Test
    void translatesExists() {
        BsonDocument trueDoc = asBson(translator.translate(new QueryPredicate.Exists("message", true)));
        BsonDocument falseDoc = asBson(translator.translate(new QueryPredicate.Exists("message", false)));
        assertThat(trueDoc.toJson()).contains("$exists").contains("true");
        assertThat(falseDoc.toJson()).contains("$exists").contains("false");
    }

    @Test
    void translatesRangeBothBounds() {
        BsonDocument doc = asBson(translator.translate(new QueryPredicate.Range("location.x", 0, 10)));
        assertThat(doc.toJson()).contains("$gte").contains("$lte");
    }

    @Test
    void translatesRangeOnlyLower() {
        BsonDocument doc = asBson(translator.translate(new QueryPredicate.Range("occurred", 5L, null)));
        String json = doc.toJson();
        assertThat(json).contains("$gte");
        assertThat(json).doesNotContain("$lte");
    }

    @Test
    void rangeOnLocationXAlsoBoundsChunkBucketCx() {
        // A block-x range adds a floor-divided cx bound so the chunk-bucketed
        // location index is seekable. floor(100/16)=6, floor(130/16)=8.
        String json = asBson(translator.translate(
                new QueryPredicate.Range("location.x", 100, 130))).toJson();
        assertThat(json).contains("location.cx").contains("6").contains("8");
    }

    @Test
    void rangeOnLocationZBoundsChunkBucketCzWithNegativeFloor() {
        // floor(-20/16) = -2 (not -1) — negative coordinates floor toward
        // negative infinity, matching BlockLocationCodec's >> 4.
        String json = asBson(translator.translate(
                new QueryPredicate.Range("location.z", -20, 40))).toJson();
        assertThat(json).contains("location.cz").contains("-2");
    }

    @Test
    void rangeOnNonHorizontalFieldHasNoChunkBucket() {
        // y is not bucketed (the chunk index is 2D), and neither is occurred.
        assertThat(asBson(translator.translate(
                new QueryPredicate.Range("location.y", 0, 256))).toJson())
                .doesNotContain("location.cx").doesNotContain("location.cz");
        assertThat(asBson(translator.translate(
                new QueryPredicate.Range("occurred", 5L, 9L))).toJson())
                .doesNotContain("location.c");
    }

    @Test
    void translatesNotWrapping() {
        BsonDocument doc = asBson(translator.translate(new QueryPredicate.Not(new QueryPredicate.Eq("event", "break"))));
        assertThat(doc.toJson()).contains("$not");
    }

    @Test
    void translatesAndOr() {
        BsonDocument and = asBson(translator.translate(new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("event", "break"),
                new QueryPredicate.Eq("source.playerId", "uuid-x")))));
        assertThat(and.toJson()).contains("$and");

        BsonDocument or = asBson(translator.translate(new QueryPredicate.Or(List.of(
                new QueryPredicate.Eq("event", "break"),
                new QueryPredicate.Eq("event", "place")))));
        assertThat(or.toJson()).contains("$or");
    }

    @Test
    void translatesListOfPredicatesAsImplicitAnd() {
        BsonDocument doc = asBson(translator.translate(List.of(
                new QueryPredicate.Eq("event", "break"),
                new QueryPredicate.Range("location.y", 0, 256))));
        assertThat(doc.toJson()).contains("$and");
    }
}

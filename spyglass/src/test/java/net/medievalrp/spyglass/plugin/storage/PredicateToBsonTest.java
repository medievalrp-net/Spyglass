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

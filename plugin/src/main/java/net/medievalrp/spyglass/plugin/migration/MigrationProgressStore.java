package net.medievalrp.spyglass.plugin.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.Nullable;

public final class MigrationProgressStore {

    private final MongoCollection<Document> collection;

    public MigrationProgressStore(MongoDatabase database) {
        this.collection = database.getCollection(V1Schema.PROGRESS_COLLECTION);
    }

    @Nullable
    public MigrationProgress load() {
        Document doc = collection.find(Filters.eq("_id", V1Schema.PROGRESS_ID)).first();
        if (doc == null) {
            return null;
        }
        return new MigrationProgress(
                doc.getObjectId("lastId"),
                longValue(doc, "processed"),
                longValue(doc, "translated"),
                longValue(doc, "skippedUnknown"),
                longValue(doc, "skippedDeferred"),
                longValue(doc, "failed"),
                instantValue(doc, "updatedAt"));
    }

    public void save(MigrationProgress progress) {
        Document doc = new Document("_id", V1Schema.PROGRESS_ID)
                .append("lastId", progress.lastId())
                .append("processed", progress.processed())
                .append("translated", progress.translated())
                .append("skippedUnknown", progress.skippedUnknown())
                .append("skippedDeferred", progress.skippedDeferred())
                .append("failed", progress.failed())
                .append("updatedAt", Date.from(progress.updatedAt()));
        collection.replaceOne(Filters.eq("_id", V1Schema.PROGRESS_ID), doc,
                new ReplaceOptions().upsert(true));
    }

    public void clear() {
        collection.deleteOne(Filters.eq("_id", V1Schema.PROGRESS_ID));
    }

    private static long longValue(Document doc, String key) {
        Object value = doc.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private static Instant instantValue(Document doc, String key) {
        Object value = doc.get(key);
        if (value instanceof Date date) {
            return date.toInstant();
        }
        return Instant.EPOCH;
    }

    public record MigrationProgress(
            ObjectId lastId,
            long processed,
            long translated,
            long skippedUnknown,
            long skippedDeferred,
            long failed,
            Instant updatedAt) {
    }
}

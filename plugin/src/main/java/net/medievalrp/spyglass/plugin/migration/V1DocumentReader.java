package net.medievalrp.spyglass.plugin.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import java.util.Iterator;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.Nullable;

public final class V1DocumentReader {

    private final MongoCollection<Document> collection;
    private final int batchSize;
    @Nullable
    private final ObjectId startAfter;

    public V1DocumentReader(MongoCollection<Document> collection, int batchSize, @Nullable ObjectId startAfter) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.collection = collection;
        this.batchSize = batchSize;
        this.startAfter = startAfter;
    }

    public long count() {
        Bson filter = startAfter == null
                ? new Document()
                : Filters.gt("_id", startAfter);
        return collection.countDocuments(filter);
    }

    public Iterator<Document> iterator() {
        Bson filter = startAfter == null
                ? new Document()
                : Filters.gt("_id", startAfter);
        MongoCursor<Document> cursor = collection.find(filter)
                .sort(Sorts.ascending("_id"))
                .batchSize(batchSize)
                .iterator();
        return new CursorIterator(cursor);
    }

    private static final class CursorIterator implements Iterator<Document>, AutoCloseable {
        private final MongoCursor<Document> cursor;

        CursorIterator(MongoCursor<Document> cursor) {
            this.cursor = cursor;
        }

        @Override
        public boolean hasNext() {
            boolean hasNext = cursor.hasNext();
            if (!hasNext) {
                cursor.close();
            }
            return hasNext;
        }

        @Override
        public Document next() {
            return cursor.next();
        }

        @Override
        public void close() {
            cursor.close();
        }
    }
}

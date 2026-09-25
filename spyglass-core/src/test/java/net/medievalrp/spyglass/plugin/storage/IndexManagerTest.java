package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.IndexOptions;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IndexManagerTest {
    @Test void createsExactBlockIndexWithOrderedTimeAndPreservesOperatorIndexes() {
        MongoCollection<BsonDocument> collection = mock(MongoCollection.class);
        ListIndexesIterable<BsonDocument> indexes = mock(ListIndexesIterable.class);
        MongoCursor<BsonDocument> cursor = mock(MongoCursor.class);
        when(collection.listIndexes(BsonDocument.class)).thenReturn(indexes);
        when(indexes.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(BsonDocument.parse("{name:'operator_custom'}"));
        new IndexManager().ensureRecordIndexes(collection);
        ArgumentCaptor<Bson> keys = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<IndexOptions> options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(collection, times(2)).createIndex(keys.capture(), options.capture());
        int index = java.util.stream.IntStream.range(0, options.getAllValues().size())
                .filter(i -> "spyglass_exact_location_v1".equals(options.getAllValues().get(i).getName())).findFirst().orElseThrow();
        assertThat(keys.getAllValues().get(index).toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()))
                .isEqualTo(BsonDocument.parse("{'location.worldId':1,'location.x':1,'location.y':1,'location.z':1,occurred:-1,id:-1}"));
        verify(collection, never()).dropIndex("operator_custom");
    }
}

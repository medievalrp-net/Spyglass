package net.medievalrp.spyglass.plugin.storage;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import org.bson.BsonDocument;
import org.bson.UuidRepresentation;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.jsr310.Jsr310CodecProvider;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.codecs.record.RecordCodecProvider;
import org.bson.conversions.Bson;
import org.jetbrains.annotations.ApiStatus;

/**
 * Single-collection Mongo-backed store for {@link EventRecord}.
 *
 * <p>Save and query both flow through one polymorphic collection
 * ({@code MongoCollection<EventRecord>}) whose codec is
 * {@link EventRecordCodec}: on decode it stream-scans the BSON for the
 * {@code event} field and dispatches to the matching concrete record
 * codec via {@link net.medievalrp.spyglass.api.event.EventCatalog}.
 * No extra discriminator field is written.
 *
 * <p>Earlier versions ran one Mongo query per record type when no
 * event filter was present — up to 13 round trips for a plain
 * {@code /spyglass search t:1h -g}. The event-dispatch codec replaces that
 * with a single query the driver sorts and limits server-side.
 */
@ApiStatus.Internal
public final class MongoRecordStore implements RecordStore {

    private final PredicateToBson predicateToBson = new PredicateToBson();
    private final MongoClient client;
    private final MongoDatabase database;
    private final MongoCollection<BsonDocument> rawCollection;
    private final MongoCollection<EventRecord> polymorphicCollection;
    private final CodecRegistry codecRegistry;

    public MongoRecordStore(String uri, String databaseName, String collectionName,
                            IndexManager indexManager) {
        this.codecRegistry = CodecRegistries.fromRegistries(
                MongoClientSettings.getDefaultCodecRegistry(),
                CodecRegistries.fromProviders(
                        new Jsr310CodecProvider(),
                        new RecordCodecProvider(),
                        EventRecordCodec.provider(),
                        RollbackEffectCodec.provider(),
                        PojoCodecProvider.builder().automatic(true).build()));
        MongoClientSettings settings = MongoClientSettings.builder()
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .codecRegistry(codecRegistry)
                .applyConnectionString(new com.mongodb.ConnectionString(uri))
                .build();
        this.client = MongoClients.create(settings);
        this.database = client.getDatabase(databaseName).withCodecRegistry(codecRegistry);
        this.rawCollection = database.getCollection(collectionName, BsonDocument.class);
        this.polymorphicCollection = database.getCollection(collectionName, EventRecord.class);
        indexManager.ensureRecordIndexes(rawCollection);
    }

    public MongoDatabase database() {
        return database;
    }

    public MongoClient client() {
        return client;
    }

    public CodecRegistry codecRegistry() {
        return codecRegistry;
    }

    @Override
    public void save(List<EventRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        polymorphicCollection.insertMany(records);
    }

    @Override
    public QueryResult query(QueryRequest request) {
        return runQuery(request, null);
    }

    @Override
    public QueryPage queryPage(QueryRequest request, QueryPage.Cursor cursor, int pageSize) {
        // Keyset pagination on (occurred, id). Memory is O(pageSize)
        // per call rather than O(matchSet) — same goal as the CH path.
        // Used by the rollback engine to stream million-row result sets
        // a page at a time instead of allocating one huge list.
        Bson baseFilter = buildFilter(request);
        boolean newestFirst = request.sort() != Sort.OLDEST_FIRST;
        Bson sort = newestFirst
                ? Sorts.orderBy(Sorts.descending(RecordFields.OCCURRED), Sorts.descending(RecordFields.ID))
                : Sorts.orderBy(Sorts.ascending(RecordFields.OCCURRED), Sorts.ascending(RecordFields.ID));

        Bson filter = baseFilter;
        if (cursor != null) {
            // Tuple compare expanded to OR-of-AND so Mongo can index it.
            // For NEWEST_FIRST: occurred < co OR (occurred == co AND id < ci).
            // High-level Filters builder handles the UUID/Instant codec
            // wiring so we don't have to round-trip through BsonString.
            Bson occLess = newestFirst
                    ? com.mongodb.client.model.Filters.lt(RecordFields.OCCURRED, cursor.occurred())
                    : com.mongodb.client.model.Filters.gt(RecordFields.OCCURRED, cursor.occurred());
            Bson tieBreak = com.mongodb.client.model.Filters.and(
                    com.mongodb.client.model.Filters.eq(RecordFields.OCCURRED, cursor.occurred()),
                    newestFirst
                            ? com.mongodb.client.model.Filters.lt(RecordFields.ID, cursor.id())
                            : com.mongodb.client.model.Filters.gt(RecordFields.ID, cursor.id()));
            Bson keysetClause = com.mongodb.client.model.Filters.or(occLess, tieBreak);
            filter = baseFilter == null
                    ? keysetClause
                    : com.mongodb.client.model.Filters.and(baseFilter, keysetClause);
        }

        var iter = polymorphicCollection.find(filter).sort(sort).limit(pageSize);
        List<EventRecord> records = iter.into(new ArrayList<>(pageSize));
        QueryPage.Cursor next = null;
        if (records.size() == pageSize) {
            EventRecord last = records.get(records.size() - 1);
            if (last.occurred() != null && last.id() != null) {
                next = new QueryPage.Cursor(last.occurred(), last.id());
            }
        }
        return new QueryPage(records, next);
    }

    /**
     * Summary projection: drops the deeply-nested snapshot fields before
     * the cursor streams docs back. The block events in particular carry
     * two {@code BlockSnapshot} payloads each — with nested container
     * items, sign text, banner patterns — that the search renderer never
     * looks at. On a 1 000-result page those snapshots dominate both the
     * wire transfer and the per-record allocation cost, and they're the
     * only reason tail latency spikes when the match set grows large.
     *
     * <p>Filtering still runs server-side against the full document, so
     * predicates on item name / lore / enchant etc. still hit their
     * targets; the projection only changes what the driver materializes
     * on the way back.
     */
    @Override
    public QueryResult querySummary(QueryRequest request) {
        return runQuery(request, SUMMARY_PROJECTION);
    }

    private static final Bson SUMMARY_PROJECTION = Projections.exclude(
            RecordFields.ORIGINAL_BLOCK,
            RecordFields.NEW_BLOCK,
            RecordFields.BEFORE_ITEM,
            RecordFields.AFTER_ITEM,
            RecordFields.ITEM);

    private QueryResult runQuery(QueryRequest request, Bson projection) {
        Bson baseFilter = buildFilter(request);
        Bson sort = request.sort() == Sort.OLDEST_FIRST
                ? Sorts.ascending(RecordFields.OCCURRED)
                : Sorts.descending(RecordFields.OCCURRED);

        var cursor = polymorphicCollection
                .find(baseFilter)
                .sort(sort)
                .limit(request.limit());
        if (projection != null) {
            cursor = cursor.projection(projection);
        }
        List<EventRecord> records = cursor.into(new ArrayList<>());

        List<QueryResult.RecordAggregation> aggregations =
                request.grouping() && !request.flags().contains(Flag.NO_GROUP)
                        ? aggregate(records)
                        : List.of();
        return new QueryResult(records, aggregations);
    }

    @Override
    public void close() {
        client.close();
    }

    private Bson buildFilter(QueryRequest request) {
        List<QueryPredicate> predicates = new ArrayList<>(request.predicates());
        if (request.flags().contains(Flag.NO_CHAT)) {
            predicates.add(new QueryPredicate.Exists(RecordFields.MESSAGE, false));
        }
        return predicateToBson.translate(predicates);
    }

    private List<QueryResult.RecordAggregation> aggregate(List<EventRecord> records) {
        Map<String, Long> counts = new HashMap<>();
        Map<String, EventRecord> sample = new HashMap<>();
        for (EventRecord record : records) {
            String key = record.event() + "|" + record.sourceName() + "|" + record.target() + "|"
                    + record.occurred().atZone(java.time.ZoneOffset.UTC).toLocalDate();
            counts.merge(key, 1L, Long::sum);
            sample.putIfAbsent(key, record);
        }
        return counts.entrySet().stream()
                .map(entry -> new QueryResult.RecordAggregation(sample.get(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparing((QueryResult.RecordAggregation aggregation)
                        -> aggregation.sample().occurred()).reversed())
                .toList();
    }
}

package net.medievalrp.spyglass.plugin.storage;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EventCatalog;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bson.BsonArray;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.UuidRepresentation;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
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

    private static final Logger LOGGER = Logger.getLogger(MongoRecordStore.class.getName());

    private final PredicateToBson predicateToBson = new PredicateToBson();
    private final MongoClient client;
    private final MongoDatabase database;
    private final MongoCollection<BsonDocument> rawCollection;
    private final MongoCollection<EventRecord> polymorphicCollection;
    private final CodecRegistry codecRegistry;
    // Per-event-type retention (#181). Null = serialize the record's own
    // expiresAt via the typed insert (legacy global behaviour, kept for
    // tests/ITs); set = encode + stamp expires_at per event type before insert.
    private final RetentionPolicy retentionPolicy;
    private final Codec<EventRecord> eventCodec;

    /**
     * Opens a store that performs write-side setup on startup: collection
     * creation (zstd), the chunk-bucket backfill, and index creation. Use
     * this from the primary backend plugin.
     */
    public MongoRecordStore(String uri, String databaseName, String collectionName,
                            IndexManager indexManager) {
        this(uri, databaseName, collectionName, indexManager, true, null);
    }

    /** Primary-backend opener with per-event retention (#181). */
    public MongoRecordStore(String uri, String databaseName, String collectionName,
                            IndexManager indexManager, RetentionPolicy retentionPolicy) {
        this(uri, databaseName, collectionName, indexManager, true, retentionPolicy);
    }

    public MongoRecordStore(String uri, String databaseName, String collectionName,
                            IndexManager indexManager, boolean performSetup) {
        this(uri, databaseName, collectionName, indexManager, performSetup, null);
    }

    /**
     * @param performSetup when {@code false}, the store skips all write-side
     *     setup — collection creation, the cx/cz backfill, and index creation
     *     — and only reads. The read-only Velocity companion passes
     *     {@code false}: it shares a database with the backend plugin, which
     *     owns schema, indexes, and migrations, so the proxy must not create
     *     the collection or rewrite records (the backfill is an
     *     {@code updateMany}) on startup.
     */
    public MongoRecordStore(String uri, String databaseName, String collectionName,
                            IndexManager indexManager, boolean performSetup,
                            RetentionPolicy retentionPolicy) {
        this.retentionPolicy = retentionPolicy;
        this.codecRegistry = CodecRegistries.fromRegistries(
                // First, so it wins for BlockLocation over both the default
                // registry's record support and RecordCodecProvider below — it
                // adds the chunk-bucket (cx/cz) storage fields the location
                // index seeks on. (It only claims BlockLocation; everything
                // else falls through.)
                CodecRegistries.fromProviders(BlockLocationCodec.provider()),
                MongoClientSettings.getDefaultCodecRegistry(),
                CodecRegistries.fromProviders(
                        new Jsr310CodecProvider(),
                        new RecordCodecProvider(),
                        EventRecordCodec.provider(),
                        RollbackEffectCodec.provider(),
                        PojoCodecProvider.builder().automatic(true).build()));
        // Cached for the per-event-retention write path (#181): encodes a record
        // to BSON so we can stamp expires_at per type before insert. The UUID
        // representation lives on the client settings, not the registry, so a
        // raw encode would throw on the record's UUID fields - prepend a STANDARD
        // UuidCodec so the manual encode matches what insertMany() writes.
        this.eventCodec = CodecRegistries.fromRegistries(
                CodecRegistries.fromCodecs(
                        new org.bson.codecs.UuidCodec(UuidRepresentation.STANDARD)),
                codecRegistry).get(EventRecord.class);
        MongoClientSettings settings = MongoClientSettings.builder()
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .codecRegistry(codecRegistry)
                .applyConnectionString(new com.mongodb.ConnectionString(uri))
                .build();
        this.client = MongoClients.create(settings);
        this.database = client.getDatabase(databaseName).withCodecRegistry(codecRegistry);
        if (performSetup) {
            ensureZstdCollection(database, collectionName);
        }
        this.rawCollection = database.getCollection(collectionName, BsonDocument.class);
        this.polymorphicCollection = database.getCollection(collectionName, EventRecord.class);
        if (performSetup) {
            backfillChunkBuckets(rawCollection);
            indexManager.ensureRecordIndexes(rawCollection);
        }
    }

    /**
     * Create the record collection with WiredTiger's zstd block compressor
     * when it doesn't yet exist. The records are a row store of highly
     * repetitive forensic events — the same world id, event names, and block
     * data strings recur across millions of rows — and zstd roughly thirds
     * the on-disk data versus the snappy default: a measured 2M-block
     * footprint dropped from ~136 MiB to ~46 MiB with no schema or query
     * change.
     *
     * <p>WiredTiger fixes the block compressor at creation time. It can't be
     * flipped on an existing collection, and neither collMod nor compact
     * rewrites it, so a deployment that predates this keeps snappy until the
     * collection is recreated or resynced; fresh installs get zstd for free.
     * We only create the collection (first write would otherwise auto-create
     * it with the server default), so existing data is never touched.
     *
     * <p>Create-and-ignore-exists rather than check-then-create: it needs
     * only the createCollection privilege the index setup already requires
     * (not listCollections), and it is race-safe if another node — say the
     * read-only Velocity companion — creates the collection concurrently.
     */
    private static void ensureZstdCollection(MongoDatabase database, String collectionName) {
        try {
            database.createCollection(collectionName, new CreateCollectionOptions()
                    .storageEngineOptions(new BsonDocument("wiredTiger",
                            new BsonDocument("configString", new BsonString("block_compressor=zstd")))));
        } catch (MongoCommandException e) {
            // NamespaceExists (48): the collection is already there and keeps
            // whatever compressor it was made with. Anything else (bad perms,
            // rejected option) is a real fault and must surface.
            if (e.getErrorCode() != 48) {
                throw e;
            }
        }
    }

    /**
     * Backfill the chunk-bucket fields ({@code location.cx} / {@code cz}) on
     * records that predate them, so the chunk-bucketed location index
     * covers old data too. {@link BlockLocationCodec} writes these on every
     * new record, so this is gated on {@code location.cx} being absent: a
     * no-op (instant) on fresh collections, and self-healing if a prior run
     * was interrupted. A 2M backfill measured ~15 s — a one-time cost paid
     * only when upgrading an existing collection. The server-side
     * {@code floor(coord / 16)} matches the codec's {@code coord >> 4} for
     * negative coordinates as well.
     */
    private static void backfillChunkBuckets(MongoCollection<BsonDocument> collection) {
        BsonDocument set = new BsonDocument()
                .append(RecordFields.LOCATION_CX, chunkExpr(RecordFields.LOCATION_X))
                .append(RecordFields.LOCATION_CZ, chunkExpr(RecordFields.LOCATION_Z));
        UpdateResult result = collection.updateMany(
                Filters.exists(RecordFields.LOCATION_CX, false),
                List.of(new BsonDocument("$set", set)));
        if (result.getModifiedCount() > 0) {
            LOGGER.info(() -> "Backfilled chunk buckets on " + result.getModifiedCount()
                    + " record(s) for the location index.");
        }
    }

    private static BsonDocument chunkExpr(String coordField) {
        // $toInt so the backfilled cx/cz are int32, matching the int32 the
        // BlockLocationCodec writes ($divide/$floor would yield a double and
        // split the index across two numeric types). $floor before $toInt
        // keeps floor-toward-negative-infinity ($toInt alone truncates).
        BsonDocument floored = new BsonDocument("$floor", new BsonDocument("$divide",
                new BsonArray(List.of(new BsonString("$" + coordField), new BsonInt32(16)))));
        return new BsonDocument("$toInt", floored);
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
        if (retentionPolicy == null) {
            polymorphicCollection.insertMany(records);
            return;
        }
        // #181: per-event-type expiry. The TTL index is on expiresAt, so encode
        // each record and overwrite that field per type before insert. Same BSON
        // as the typed path (same codec), just a per-type expiresAt - so the
        // chunk-bucket fields, _id mapping, and zstd compression are unchanged.
        List<BsonDocument> docs = new ArrayList<>(records.size());
        for (EventRecord record : records) {
            BsonDocument doc = new BsonDocument();
            eventCodec.encode(new BsonDocumentWriter(doc), record, EncoderContext.builder().build());
            doc.put(RecordFields.EXPIRES_AT, new BsonDateTime(
                    retentionPolicy.expiresAt(record.occurred(), record.event()).toEpochMilli()));
            docs.add(doc);
        }
        rawCollection.insertMany(docs);
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
        boolean newestFirst = request.sort() != Sort.OLDEST_FIRST;
        Bson filter = keysetFilter(buildFilter(request), cursor, newestFirst);

        var iter = polymorphicCollection.find(filter).sort(keysetSort(newestFirst)).limit(pageSize);
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

    private static Bson keysetSort(boolean newestFirst) {
        return newestFirst
                ? Sorts.orderBy(Sorts.descending(RecordFields.OCCURRED), Sorts.descending(RecordFields.ID))
                : Sorts.orderBy(Sorts.ascending(RecordFields.OCCURRED), Sorts.ascending(RecordFields.ID));
    }

    // Tuple compare on (occurred, id) expanded to OR-of-AND so Mongo can index
    // it. For NEWEST_FIRST: occurred < co OR (occurred == co AND id < ci). The
    // high-level Filters builder handles the UUID/Instant codec wiring so we
    // don't round-trip through BsonString.
    private static Bson keysetFilter(Bson baseFilter, QueryPage.Cursor cursor, boolean newestFirst) {
        if (cursor == null) {
            return baseFilter;
        }
        Bson occCmp = newestFirst
                ? Filters.lt(RecordFields.OCCURRED, cursor.occurred())
                : Filters.gt(RecordFields.OCCURRED, cursor.occurred());
        Bson tieBreak = Filters.and(
                Filters.eq(RecordFields.OCCURRED, cursor.occurred()),
                newestFirst
                        ? Filters.lt(RecordFields.ID, cursor.id())
                        : Filters.gt(RecordFields.ID, cursor.id()));
        Bson keyset = Filters.or(occCmp, tieBreak);
        return baseFilter == null ? keyset : Filters.and(baseFilter, keyset);
    }

    // Direction-specific lean rollback read — the Mongo mirror of the
    // ClickHouse #67/#83 path. The default streamRollbackEffects rides
    // queryPage(), which decodes the FULL EventRecord graph per row (both
    // block snapshots, origin, source, server, target) only to keep the one
    // replacement side; on a million-row rollback that record churn is what
    // fills eden and forces a young GC mid-op. Here we read raw BSON projected
    // to just the fields a RollbackEffect needs, emit the simple-block hot
    // path straight to sink.block() primitives (no record, snapshot, or effect
    // object), and build a snapshot/item only for the rare tile-entity /
    // container / entity rows. force-overwrite (#69) never reads the expected
    // side, so the projection omits it: a rollback reads originalBlock, a
    // restore reads newBlock.
    @Override
    public QueryPage.Cursor streamRollbackEffects(QueryRequest request, QueryPage.Cursor cursor,
                                                  int windowLimit, boolean rollback,
                                                  RollbackEffectSink sink) {
        boolean newestFirst = request.sort() != Sort.OLDEST_FIRST;
        Bson filter = keysetFilter(buildFilter(request), cursor, newestFirst);
        String replacementSide = rollback ? RecordFields.ORIGINAL_BLOCK : RecordFields.NEW_BLOCK;
        Bson projection = Projections.fields(
                Projections.include(RecordFields.ID, RecordFields.EVENT, RecordFields.OCCURRED,
                        RecordFields.LOCATION, replacementSide,
                        RecordFields.SLOT, RecordFields.BEFORE_ITEM, RecordFields.AFTER_ITEM,
                        RecordFields.ENTITY_TYPE, RecordFields.ENTITY_ID, RecordFields.ENTITY_NBT),
                Projections.excludeId());

        Instant lastOccurred = null;
        UUID lastId = null;
        int count = 0;
        try (MongoCursor<BsonDocument> iterator = rawCollection.find(filter)
                .projection(projection)
                .sort(keysetSort(newestFirst))
                .limit(windowLimit)
                .iterator()) {
            while (iterator.hasNext()) {
                BsonDocument doc = iterator.next();
                UUID id = readUuid(doc, RecordFields.ID);
                Instant occurred = readInstant(doc, RecordFields.OCCURRED);
                lastOccurred = occurred;
                lastId = id;
                count++;
                emitEffect(doc, rollback, occurred, id, sink);
            }
        }
        // Match queryPage's cursor contract: a full window means more rows
        // may follow, so hand back the last (occurred, id); a short window is
        // the end of the stream.
        if (count == windowLimit && lastOccurred != null && lastId != null) {
            return new QueryPage.Cursor(lastOccurred, lastId);
        }
        return null;
    }

    // Resolve one projected BSON row to a rollback effect in the requested
    // direction. Mirrors the per-record-type Rollbackable.rollbackEffect() /
    // restoreEffect() logic (and the ClickHouse emitEffect) exactly — keep the
    // three in lockstep.
    private void emitEffect(BsonDocument doc, boolean rollback, Instant occurred, UUID id,
                            RollbackEffectSink sink) {
        String event = doc.containsKey(RecordFields.EVENT) && doc.get(RecordFields.EVENT).isString()
                ? doc.getString(RecordFields.EVENT).getValue() : null;
        Class<? extends EventRecord> clazz = event == null ? null : EventCatalog.recordClassOf(event);

        if (clazz == BlockBreakRecord.class || clazz == BlockPlaceRecord.class) {
            // rollback writes the before-state (originalBlock), restore the
            // after-state (newBlock); the projection only fetched that side.
            String sideKey = rollback ? RecordFields.ORIGINAL_BLOCK : RecordFields.NEW_BLOCK;
            BsonDocument side = doc.containsKey(sideKey) && doc.get(sideKey).isDocument()
                    ? doc.getDocument(sideKey) : null;
            if (side == null) {
                sink.skip(occurred, id);
                return;
            }
            boolean simple = side.containsKey(RecordFields.SIMPLE)
                    && side.get(RecordFields.SIMPLE).isBoolean()
                    && side.getBoolean(RecordFields.SIMPLE).getValue();
            String blockData = side.containsKey(RecordFields.BLOCK_DATA)
                    && side.get(RecordFields.BLOCK_DATA).isString()
                    ? side.getString(RecordFields.BLOCK_DATA).getValue() : null;
            if (simple && blockData != null) {
                // Hot path: no snapshot / effect object. expectedData is unused
                // under force-overwrite (#69), so pass null.
                BsonDocument loc = doc.getDocument(RecordFields.LOCATION);
                sink.block(readUuid(loc, RecordFields.WORLD_ID),
                        loc.getInt32(RecordFields.X).getValue(),
                        loc.getInt32(RecordFields.Y).getValue(),
                        loc.getInt32(RecordFields.Z).getValue(),
                        blockData, null, occurred, id);
                return;
            }
            // Tile-entity payload (or malformed block-data): build the snapshot.
            // expectedCurrent is null — force-overwrite ignores it.
            sink.complex(new RollbackEffect.BlockReplace(readLocation(doc), null,
                    decodeSnapshot(side)), occurred, id);
            return;
        }

        if (clazz == ContainerDepositRecord.class || clazz == ContainerWithdrawRecord.class) {
            BlockLocation location = readLocation(doc);
            int slot = doc.containsKey(RecordFields.SLOT) && doc.get(RecordFields.SLOT).isInt32()
                    ? doc.getInt32(RecordFields.SLOT).getValue() : 0;
            StoredItem before = decodeItem(doc, RecordFields.BEFORE_ITEM);
            StoredItem after = decodeItem(doc, RecordFields.AFTER_ITEM);
            sink.complex(rollback
                    ? new RollbackEffect.ContainerSlotWrite(location, slot, after, before)
                    : new RollbackEffect.ContainerSlotWrite(location, slot, before, after), occurred, id);
            return;
        }

        if (clazz == EntityDeathRecord.class) {
            BlockLocation location = readLocation(doc);
            String entityType = doc.containsKey(RecordFields.ENTITY_TYPE)
                    && doc.get(RecordFields.ENTITY_TYPE).isString()
                    ? doc.getString(RecordFields.ENTITY_TYPE).getValue() : null;
            if (rollback) {
                String nbt = doc.containsKey(RecordFields.ENTITY_NBT)
                        && doc.get(RecordFields.ENTITY_NBT).isString()
                        ? doc.getString(RecordFields.ENTITY_NBT).getValue() : null;
                sink.complex(new RollbackEffect.EntitySpawn(location, entityType, nbt), occurred, id);
            } else {
                UUID entityId = doc.containsKey(RecordFields.ENTITY_ID)
                        && doc.get(RecordFields.ENTITY_ID).isBinary()
                        ? readUuid(doc, RecordFields.ENTITY_ID) : null;
                sink.complex(new RollbackEffect.EntityRemove(location, entityType,
                        entityId == null ? null : entityId.toString()), occurred, id);
            }
            return;
        }

        // Matched but not rollbackable — advance the cursor only.
        sink.skip(occurred, id);
    }

    private BlockLocation readLocation(BsonDocument doc) {
        BsonDocument loc = doc.getDocument(RecordFields.LOCATION);
        String worldName = loc.containsKey(RecordFields.WORLD_NAME)
                && loc.get(RecordFields.WORLD_NAME).isString()
                ? loc.getString(RecordFields.WORLD_NAME).getValue() : null;
        return new BlockLocation(readUuid(loc, RecordFields.WORLD_ID), worldName,
                loc.getInt32(RecordFields.X).getValue(),
                loc.getInt32(RecordFields.Y).getValue(),
                loc.getInt32(RecordFields.Z).getValue());
    }

    private BlockSnapshot decodeSnapshot(BsonDocument side) {
        return codecRegistry.get(BlockSnapshot.class)
                .decode(new BsonDocumentReader(side), DecoderContext.builder().build());
    }

    private StoredItem decodeItem(BsonDocument doc, String key) {
        if (!doc.containsKey(key) || !doc.get(key).isDocument()) {
            return null;
        }
        return codecRegistry.get(StoredItem.class)
                .decode(new BsonDocumentReader(doc.getDocument(key)), DecoderContext.builder().build());
    }

    private static UUID readUuid(BsonDocument doc, String key) {
        return doc.getBinary(key).asUuid();
    }

    private static Instant readInstant(BsonDocument doc, String key) {
        return Instant.ofEpochMilli(doc.getDateTime(key).getValue());
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

    // Drops only the two bulky BlockSnapshot payloads (the wire/allocation
    // hot-spot on large pages). The much smaller item blobs are kept so the
    // search hover can show an item's custom name / lore / enchants.
    private static final Bson SUMMARY_PROJECTION = Projections.exclude(
            RecordFields.ORIGINAL_BLOCK,
            RecordFields.NEW_BLOCK);

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

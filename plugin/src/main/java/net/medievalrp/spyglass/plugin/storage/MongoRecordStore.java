package net.medievalrp.spyglass.plugin.storage;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.CommandRecord;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EntityHitRecord;
import net.medievalrp.spyglass.api.event.EntityMountRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.QuitRecord;
import net.medievalrp.spyglass.api.event.TeleportRecord;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import org.bson.BsonDocument;
import org.bson.UuidRepresentation;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.jsr310.Jsr310CodecProvider;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.codecs.record.RecordCodecProvider;
import org.bson.conversions.Bson;

public final class MongoRecordStore implements RecordStore {

    private static final Map<String, Class<? extends EventRecord>> EVENT_TYPES = Map.ofEntries(
            Map.entry("break", BlockBreakRecord.class),
            Map.entry("place", BlockPlaceRecord.class),
            Map.entry("say", ChatRecord.class),
            Map.entry("command", CommandRecord.class),
            Map.entry("join", JoinRecord.class),
            Map.entry("quit", QuitRecord.class),
            Map.entry("deposit", ContainerDepositRecord.class),
            Map.entry("withdraw", ContainerWithdrawRecord.class),
            Map.entry("decay", BlockBreakRecord.class),
            Map.entry("form", BlockPlaceRecord.class),
            Map.entry("grow", BlockPlaceRecord.class),
            Map.entry("ignite", BlockPlaceRecord.class),
            Map.entry("drop", ItemDropRecord.class),
            Map.entry("pickup", ItemPickupRecord.class),
            Map.entry("teleport", TeleportRecord.class),
            Map.entry("death", EntityDeathRecord.class),
            Map.entry("hit", EntityHitRecord.class),
            Map.entry("shot", EntityHitRecord.class),
            Map.entry("mount", EntityMountRecord.class),
            Map.entry("dismount", EntityMountRecord.class),
            Map.entry("entity-deposit", ContainerDepositRecord.class),
            Map.entry("entity-withdraw", ContainerWithdrawRecord.class));

    private final PredicateToBson predicateToBson = new PredicateToBson();
    private final MongoClient client;
    private final MongoDatabase database;
    private final MongoCollection<BsonDocument> rawCollection;
    private final String collectionName;
    private final CodecRegistry codecRegistry;

    public MongoRecordStore(SpyglassConfig.Database config, IndexManager indexManager) {
        this.codecRegistry = CodecRegistries.fromRegistries(
                MongoClientSettings.getDefaultCodecRegistry(),
                CodecRegistries.fromProviders(
                        new Jsr310CodecProvider(),
                        new RecordCodecProvider(),
                        PojoCodecProvider.builder().automatic(true).build()));
        MongoClientSettings settings = MongoClientSettings.builder()
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .codecRegistry(codecRegistry)
                .applyConnectionString(new com.mongodb.ConnectionString(config.uri()))
                .build();
        this.client = MongoClients.create(settings);
        this.database = client.getDatabase(config.name()).withCodecRegistry(codecRegistry);
        this.collectionName = config.collection();
        this.rawCollection = database.getCollection(collectionName, BsonDocument.class);
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
        Map<Class<? extends EventRecord>, List<EventRecord>> grouped = records.stream()
                .collect(Collectors.groupingBy(EventRecord::getClass));
        grouped.forEach((type, batch) -> collection(type).insertMany(cast(type, batch)));
    }

    @Override
    public QueryResult query(QueryRequest request) {
        Bson baseFilter = buildFilter(request);
        Bson sort = request.sort() == Sort.OLDEST_FIRST
                ? Sorts.ascending(RecordFields.OCCURRED)
                : Sorts.descending(RecordFields.OCCURRED);

        List<EventRecord> records = new ArrayList<>();
        for (Class<? extends EventRecord> type : candidateTypes(request.predicates())) {
            Set<String> typeEvents = eventNamesForType(type);
            Bson typeFilter = typeEvents.isEmpty()
                    ? baseFilter
                    : Filters.and(baseFilter, Filters.in(RecordFields.EVENT, typeEvents));
            FindIterable<? extends EventRecord> iterable = collection(type)
                    .find(typeFilter)
                    .sort(sort)
                    .limit(request.limit());
            iterable.into(records);
        }

        Comparator<EventRecord> comparator = Comparator.comparing(EventRecord::occurred);
        if (request.sort() == Sort.NEWEST_FIRST) {
            comparator = comparator.reversed();
        }
        records.sort(comparator);
        if (records.size() > request.limit()) {
            records = new ArrayList<>(records.subList(0, request.limit()));
        }

        List<QueryResult.RecordAggregation> aggregations = request.grouping() && !request.flags().contains(Flag.NO_GROUP)
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
            String key = record.event() + "|" + record.sourceName() + "|" + record.target() + "|" + record.occurred().atZone(java.time.ZoneOffset.UTC).toLocalDate();
            counts.merge(key, 1L, Long::sum);
            sample.putIfAbsent(key, record);
        }
        return counts.entrySet().stream()
                .map(entry -> new QueryResult.RecordAggregation(sample.get(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparing((QueryResult.RecordAggregation aggregation) -> aggregation.sample().occurred()).reversed())
                .toList();
    }

    private Set<Class<? extends EventRecord>> candidateTypes(List<QueryPredicate> predicates) {
        for (QueryPredicate predicate : predicates) {
            if (predicate instanceof QueryPredicate.Eq eq
                    && RecordFields.EVENT.equals(eq.field())
                    && eq.value() instanceof String event) {
                Class<? extends EventRecord> type = EVENT_TYPES.get(event.toLowerCase());
                if (type != null) {
                    return Set.of(type);
                }
            }
            if (predicate instanceof QueryPredicate.In in
                    && RecordFields.EVENT.equals(in.field())) {
                return in.values().stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .map(String::toLowerCase)
                        .map(EVENT_TYPES::get)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet());
            }
        }
        return Set.copyOf(EVENT_TYPES.values());
    }

    @SuppressWarnings("unchecked")
    private <T extends EventRecord> MongoCollection<T> collection(Class<?> type) {
        return database.getCollection(collectionName, (Class<T>) type);
    }

    private static Set<String> eventNamesForType(Class<? extends EventRecord> type) {
        Set<String> names = new java.util.HashSet<>();
        for (Map.Entry<String, Class<? extends EventRecord>> entry : EVENT_TYPES.entrySet()) {
            if (entry.getValue().equals(type)) {
                names.add(entry.getKey());
            }
        }
        return Set.copyOf(names);
    }

    @SuppressWarnings("unchecked")
    private <T extends EventRecord> List<T> cast(Class<T> type, List<EventRecord> batch) {
        return batch.stream().map(type::cast).toList();
    }
}

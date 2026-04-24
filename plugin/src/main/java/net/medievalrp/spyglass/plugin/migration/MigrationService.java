package net.medievalrp.spyglass.plugin.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.storage.MongoRecordStore;
import org.bson.Document;
import org.bson.types.ObjectId;

public final class MigrationService {

    private final MongoRecordStore recordStore;
    private final SpyglassConfig config;
    private final V1ToV2Translator translator;
    private final Logger logger;

    public MigrationService(MongoRecordStore recordStore, SpyglassConfig config,
                            V1ToV2Translator translator, Logger logger) {
        this.recordStore = recordStore;
        this.config = config;
        this.translator = translator;
        this.logger = logger;
    }

    public MigrationReport migrate(MigrationOptions options, Consumer<ProgressUpdate> reporter) {
        SpyglassConfig.Database target = config.database();
        String sourceDb = options.sourceDatabase();
        String sourceCollection = options.sourceCollection();

        if (options.sourceUri() == null || options.sourceUri().equals(target.uri())) {
            MongoDatabase database = recordStore.client().getDatabase(sourceDb);
            return migrateInternal(database, sourceDb, sourceCollection, options, reporter);
        }
        try (MongoClient client = MongoClients.create(options.sourceUri())) {
            MongoDatabase database = client.getDatabase(sourceDb);
            return migrateInternal(database, sourceDb, sourceCollection, options, reporter);
        }
    }

    private MigrationReport migrateInternal(MongoDatabase sourceDatabase, String sourceDb, String sourceCollection,
                                            MigrationOptions options, Consumer<ProgressUpdate> reporter) {
        MongoCollection<Document> collection = sourceDatabase.getCollection(sourceCollection);
        MongoDatabase progressDb = recordStore.database();
        MigrationProgressStore progressStore = new MigrationProgressStore(progressDb);

        ObjectId startAfter = null;
        MigrationProgressStore.MigrationProgress resumeFrom = null;
        if (options.resume()) {
            resumeFrom = progressStore.load();
            if (resumeFrom != null) {
                startAfter = resumeFrom.lastId();
            }
        } else if (!options.dryRun()) {
            progressStore.clear();
        }

        V1DocumentReader reader = new V1DocumentReader(collection, options.batchSize(), startAfter);
        long expected = reader.count();
        Iterator<Document> iterator = reader.iterator();

        long processed = resumeFrom != null ? resumeFrom.processed() : 0;
        long translated = resumeFrom != null ? resumeFrom.translated() : 0;
        long skippedUnknown = resumeFrom != null ? resumeFrom.skippedUnknown() : 0;
        long skippedDeferred = resumeFrom != null ? resumeFrom.skippedDeferred() : 0;
        long failed = resumeFrom != null ? resumeFrom.failed() : 0;

        List<EventRecord> batch = new ArrayList<>(options.batchSize());
        ObjectId lastId = startAfter;
        long batchProcessed = 0;

        while (iterator.hasNext()) {
            Document doc = iterator.next();
            processed++;
            batchProcessed++;
            Object rawId = doc.get("_id");
            if (rawId instanceof ObjectId oid) {
                lastId = oid;
            }
            V1ToV2Translator.Result result = translator.translate(doc);
            switch (result.kind()) {
                case TRANSLATED -> {
                    batch.add(result.record());
                    translated++;
                }
                case SKIPPED_DEFERRED -> skippedDeferred++;
                case SKIPPED_UNKNOWN -> skippedUnknown++;
                case FAILED -> {
                    failed++;
                    logger.warning("migration: failed to translate doc "
                            + (lastId == null ? "?" : lastId.toHexString())
                            + ": " + result.reason());
                }
            }
            if (batch.size() >= options.batchSize()) {
                flush(batch, options.dryRun());
                if (!options.dryRun() && lastId != null) {
                    progressStore.save(new MigrationProgressStore.MigrationProgress(
                            lastId, processed, translated, skippedUnknown,
                            skippedDeferred, failed, Instant.now()));
                }
            }
            if (batchProcessed >= 10_000) {
                reporter.accept(new ProgressUpdate(processed, translated, skippedUnknown,
                        skippedDeferred, failed, expected));
                batchProcessed = 0;
            }
        }
        flush(batch, options.dryRun());
        if (!options.dryRun() && lastId != null) {
            progressStore.save(new MigrationProgressStore.MigrationProgress(
                    lastId, processed, translated, skippedUnknown,
                    skippedDeferred, failed, Instant.now()));
        }
        reporter.accept(new ProgressUpdate(processed, translated, skippedUnknown,
                skippedDeferred, failed, expected));

        return new MigrationReport(sourceDb, sourceCollection,
                processed, translated, skippedUnknown, skippedDeferred, failed,
                options.dryRun());
    }

    private void flush(List<EventRecord> batch, boolean dryRun) {
        if (batch.isEmpty()) {
            return;
        }
        if (!dryRun) {
            recordStore.save(List.copyOf(batch));
        }
        batch.clear();
    }

    public record MigrationOptions(String sourceUri, String sourceDatabase, String sourceCollection,
                                   int batchSize, boolean dryRun, boolean resume) {

        public static MigrationOptions defaults() {
            return new MigrationOptions(null, V1Schema.DEFAULT_DATABASE, V1Schema.DEFAULT_COLLECTION,
                    1000, false, false);
        }

        public MigrationOptions withBatchSize(int size) {
            return new MigrationOptions(sourceUri, sourceDatabase, sourceCollection, size, dryRun, resume);
        }

        public MigrationOptions withDryRun(boolean dryRun) {
            return new MigrationOptions(sourceUri, sourceDatabase, sourceCollection, batchSize, dryRun, resume);
        }

        public MigrationOptions withResume(boolean resume) {
            return new MigrationOptions(sourceUri, sourceDatabase, sourceCollection, batchSize, dryRun, resume);
        }
    }

    public record ProgressUpdate(long processed, long translated, long skippedUnknown,
                                 long skippedDeferred, long failed, long expected) {
    }

    public record MigrationReport(String sourceDatabase, String sourceCollection,
                                  long processed, long translated,
                                  long skippedUnknown, long skippedDeferred,
                                  long failed, boolean dryRun) {
    }
}

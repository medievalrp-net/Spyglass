package net.medievalrp.spyglass.plugin.tool;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bson.Document;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ToolStateStore {

    public static final String COLLECTION = "Tools";
    private static final String FIELD_ENABLED_AT = "enabledAt";

    private final MongoCollection<Document> collection;
    private final Logger logger;

    public ToolStateStore(MongoDatabase database, Logger logger) {
        this.collection = database.getCollection(COLLECTION);
        this.logger = logger;
    }

    public Collection<UUID> loadActive() {
        Set<UUID> active = new HashSet<>();
        for (Document doc : collection.find()) {
            Object id = doc.get("_id");
            UUID parsed = parseId(id);
            if (parsed != null) {
                active.add(parsed);
            }
        }
        return active;
    }

    public void enable(UUID playerId) {
        Document doc = new Document("_id", playerId.toString())
                .append(FIELD_ENABLED_AT, Date.from(Instant.now()));
        collection.replaceOne(
                Filters.eq("_id", playerId.toString()),
                doc,
                new ReplaceOptions().upsert(true));
    }

    public void disable(UUID playerId) {
        collection.deleteOne(Filters.eq("_id", playerId.toString()));
    }

    private UUID parseId(Object raw) {
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        if (raw instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ex) {
                logger.warning("Tool state: invalid UUID " + text);
                return null;
            }
        }
        return null;
    }
}

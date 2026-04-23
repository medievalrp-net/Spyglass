package net.medievalrp.omniscience2.api.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.omniscience2.api.util.BlockLocation;



public record ChatRecord(
        UUID id,
        int schemaVersion,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String target,
        String message,
        List<UUID> recipients) implements EventRecord {

    public ChatRecord {
        recipients = List.copyOf(recipients);
    }
}

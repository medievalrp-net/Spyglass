package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;

public sealed interface EventRecord permits
        BlockBreakRecord,
        BlockPlaceRecord,
        ChatRecord,
        CommandRecord,
        JoinRecord,
        QuitRecord,
        ContainerDepositRecord,
        ContainerWithdrawRecord,
        ContainerInteractRecord,
        BlockUseRecord,
        ItemDropRecord,
        ItemPickupRecord,
        TeleportRecord,
        EntityDeathRecord,
        EntityHitRecord,
        EntityMountRecord,
        EntityNameRecord,
        RollbackOpRecord {

    UUID id();

    String event();

    Instant occurred();

    Instant expiresAt();

    Origin origin();

    Source source();

    BlockLocation location();

    String target();

    /**
     * Identifier of the Spyglass instance that recorded this event. Sourced
     * from {@code server.name} in the recording instance's config and stamped
     * onto every record at write time, so a shared backend (one Mongo / one
     * ClickHouse) can hold logs from many backend servers in a single store
     * and {@code srv:lobby} cleanly partitions them.
     *
     * <p>May be empty for legacy rows written before the field existed; the
     * search and hover paths skip empty values.
     */
    String server();

    default String sourceName() {
        return source().displayName();
    }
}

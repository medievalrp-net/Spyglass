package net.medievalrp.omniscience2.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.omniscience2.api.util.BlockLocation;

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
        EntityMountRecord {

    UUID id();

    int schemaVersion();

    String event();

    Instant occurred();

    Instant expiresAt();

    Origin origin();

    Source source();

    BlockLocation location();

    String target();

    default String sourceName() {
        return source().displayName();
    }
}

package net.medievalrp.spyglass.api.rollback;

import org.jetbrains.annotations.Nullable;

/**
 * A record whose effects a rollback or restore can apply.
 *
 * <p>Either method may return {@code null}: the record declines to
 * produce an effect in that direction and the row is skipped (it stays
 * fully searchable - only the apply is withheld). The first user is
 * {@link net.medievalrp.spyglass.api.event.EntityDeathRecord}, which
 * only resurrects player kills (#284): environment deaths (a zombie
 * burning at dawn, a bat in lava) must not come back on a generic area
 * rollback.
 */
public interface Rollbackable {

    @Nullable RollbackEffect rollbackEffect();

    @Nullable RollbackEffect restoreEffect();
}

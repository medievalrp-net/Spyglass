package net.medievalrp.omniscience2.api;

import net.medievalrp.omniscience2.api.util.Duration;

/**
 * Read-only snapshot of operator-set query and storage limits, for
 * extensions that want to align their own bounds with Omniscience2's
 * (e.g. cap a custom radius param at {@code maxRadius}, or set TTLs
 * on records they push to match the global {@code retention}).
 *
 * <p>Snapshot at the time {@link Omniscience2Api#limits()} is called;
 * does not update if the operator reloads config — re-fetch when you
 * need fresh values.
 *
 * @param maxRadius hard upper bound on radius-style query params
 * @param defaultRadius radius applied when the user supplies none
 *     and no spatial constraint is in play
 * @param defaultTimeWindow how far back the default {@code time=}
 *     constraint reaches when none is supplied
 * @param retention how long records live before TTL cleanup; useful
 *     when computing {@code expiresAt} on records you push via
 *     {@link Omniscience2Api#record}
 */
public record OmniscienceLimits(
        int maxRadius,
        int defaultRadius,
        Duration defaultTimeWindow,
        Duration retention) {
}

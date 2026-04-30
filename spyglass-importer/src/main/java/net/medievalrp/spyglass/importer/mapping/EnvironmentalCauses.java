package net.medievalrp.spyglass.importer.mapping;

import org.jetbrains.annotations.Nullable;

/**
 * CoreProtect attributes non-player block changes to pseudo-player names
 * prefixed with {@code #} — e.g. {@code #water}, {@code #decay},
 * {@code #fire}, {@code #piston}. These rows have no UUID in
 * {@code co_user.uuid} (they're not real accounts), so we'd otherwise
 * skip them as MISSING_PLAYER_UUID.
 *
 * <p>Spyglass models these as {@link
 * net.medievalrp.spyglass.api.event.Source#environment} +
 * {@link net.medievalrp.spyglass.api.event.Origin#environment} — same
 * shape native environmental events use, so {@code c:water} predicates
 * filter imported and native rows uniformly.
 *
 * <p>This recogniser is permissive: any {@code #}-prefixed name maps to
 * the suffix as the kind. The list below is the documented set; new
 * CoreProtect causes get the same treatment automatically without
 * needing a code change.
 *
 * <p>Documented set as of CoreProtect 22.x:
 * <ul>
 *   <li>{@code #water}, {@code #lava} — fluid flow</li>
 *   <li>{@code #fire} — fire spread</li>
 *   <li>{@code #explosion} — block destruction by explosion</li>
 *   <li>{@code #piston} — piston push/pull</li>
 *   <li>{@code #decay} — leaf decay</li>
 *   <li>{@code #vine}, {@code #crop}, {@code #tree}, {@code #mushroom} — growth</li>
 *   <li>{@code #snow}, {@code #ice}, {@code #concrete} — phase change</li>
 *   <li>{@code #tnt} — pre-detonation TNT placement</li>
 *   <li>{@code #sculk} — sculk spread</li>
 *   <li>{@code #unknown} — fallback when CoreProtect couldn't attribute</li>
 * </ul>
 */
public final class EnvironmentalCauses {

    private EnvironmentalCauses() {}

    /**
     * Returns the kind string (without leading {@code #}) when this is
     * an environmental pseudo-player name, or {@code null} if it's a
     * regular player name.
     */
    @Nullable
    public static String kindOf(@Nullable String coreProtectPlayerName) {
        if (coreProtectPlayerName == null
                || coreProtectPlayerName.length() < 2
                || coreProtectPlayerName.charAt(0) != '#') {
            return null;
        }
        return coreProtectPlayerName.substring(1);
    }
}

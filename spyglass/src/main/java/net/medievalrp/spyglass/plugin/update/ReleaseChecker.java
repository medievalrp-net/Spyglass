package net.medievalrp.spyglass.plugin.update;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.bson.Document;

/** Pure release selection: never offer an artifact for a different Minecraft target. */
public final class ReleaseChecker {
    private static final Pattern VERSION = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)$");
    public record Release(String version, String url) {}
    private ReleaseChecker() {}

    public static Optional<Release> newest(List<Document> releases, String minecraft) {
        return releases.stream()
                .filter(r -> !r.getBoolean("draft", false) && !r.getBoolean("prerelease", false))
                .filter(r -> VERSION.matcher(r.getString("tag_name") == null ? "" : r.getString("tag_name")).matches())
                .filter(r -> compatible(r, minecraft))
                .map(r -> new Release(r.getString("tag_name").replaceFirst("^v", ""),
                        "https://github.com/medievalrp-net/Spyglass/releases/tag/" + r.getString("tag_name")))
                .max((a, b) -> compare(a.version(), b.version()));
    }

    static boolean compatible(Document release, String minecraft) {
        String version = release.getString("tag_name").replaceFirst("^v", "");
        for (Document asset : release.getList("assets", Document.class, List.of())) {
            String name = asset.getString("name");
            if (("Spyglass-" + version + "-mc" + minecraft + ".jar").equals(name)
                    || ("Spyglass-" + version + "-mc" + minecraft + "-shaded.jar").equals(name)) return true;
            // Existing 1.x releases used unqualified names; do not treat a 2.x jar as legacy-compatible.
            if (minecraft.startsWith("1.21.") && version.startsWith("1.")
                    && ("Spyglass.jar".equals(name) || "Spyglass-shaded.jar".equals(name))) return true;
        }
        return false;
    }

    public static boolean newer(String release, String installed) {
        String base = installed.replaceFirst("-mc.*$", "").replaceFirst("-SNAPSHOT$", "");
        if (!VERSION.matcher(base).matches()) return false;
        int comparison = compare(release, base);
        return comparison > 0 || comparison == 0 && installed.endsWith("-SNAPSHOT");
    }

    static int compare(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int result = new java.math.BigInteger(left[i]).compareTo(new java.math.BigInteger(right[i]));
            if (result != 0) return result;
        }
        return 0;
    }
}

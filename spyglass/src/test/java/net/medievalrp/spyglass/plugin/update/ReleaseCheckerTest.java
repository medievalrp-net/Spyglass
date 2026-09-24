package net.medievalrp.spyglass.plugin.update;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class ReleaseCheckerTest {
    private Document release(String version, String... assets) {
        return new Document("tag_name", "v" + version).append("assets",
                java.util.Arrays.stream(assets).map(name -> new Document("name", name)).toList());
    }

    @Test void selectsMatchingAssetEvenWhenAnotherMinecraftReleaseIsNewer() {
        var releases = List.of(release("2.1.0", "Spyglass-2.1.0-mc26.3.jar"),
                release("2.0.9", "Spyglass-2.0.9-mc26.2-shaded.jar"),
                release("2.0.10", "Spyglass-2.0.10-mc26.2.jar"));
        assertThat(ReleaseChecker.newest(releases, "26.2").orElseThrow().version()).isEqualTo("2.0.10");
        assertThat(ReleaseChecker.newest(releases, "26.1.2")).isEmpty();
    }

    @Test void skipsDraftsPrereleasesAndOtherPlatforms() {
        assertThat(ReleaseChecker.newest(List.of(
                release("2.0.0", "Spyglass-2.0.0-mc26.3.jar").append("draft", true),
                release("2.0.1", "Spyglass-2.0.1-mc26.3.jar").append("prerelease", true),
                release("2.0.2", "spyglass-api-2.0.2-mc26.3.jar")), "26.3")).isEmpty();
    }

    @Test void legacyNeverSelectsAnUnqualifiedModernJar() {
        var releases = List.of(release("2.0.0", "Spyglass.jar"), release("1.0.13", "Spyglass-shaded.jar"));
        assertThat(ReleaseChecker.newest(releases, "1.21.11").orElseThrow().version()).isEqualTo("1.0.13");
        assertThat(ReleaseChecker.newest(releases, "26.3")).isEmpty();
    }

    @Test void comparesNumericVersionsAndSnapshotsWithoutDowngrading() {
        assertThat(ReleaseChecker.newer("2.0.10", "2.0.9-mc26.2")).isTrue();
        assertThat(ReleaseChecker.newer("2.0.0", "2.0.0-mc26.3-SNAPSHOT")).isTrue();
        assertThat(ReleaseChecker.newer("2.0.0", "2.0.0-mc26.3")).isFalse();
        assertThat(ReleaseChecker.newer("1.0.12", "1.0.13")).isFalse();
        assertThat(ReleaseChecker.newer("2.0.0", "unknown")).isFalse();
    }
}

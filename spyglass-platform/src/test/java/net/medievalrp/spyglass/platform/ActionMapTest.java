package net.medievalrp.spyglass.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.medievalrp.spyglass.api.event.EventCatalog;
import org.junit.jupiter.api.Test;

class ActionMapTest {

    /**
     * The reconciliation test, and the reason {@link ActionMap} is a class of its own.
     *
     * <p>Spyglass gains action keys — six of them arrived with one release of the
     * MedievalRP content pack. A key that is in the catalog and in neither of
     * ActionMap's tables is an action that silently never reaches the Crusalis store,
     * which is the one failure a forensics pipeline cannot have, because nothing looks
     * wrong until somebody needs the record that is not there.
     *
     * <p>If this fails, the fix is one of two lines in ActionMap: a mapping to an event
     * type that has a registered schema, or an entry in the ignored table with the
     * reason why.
     */
    @Test
    void everyCatalogActionIsMappedOrDeliberatelyIgnored() {
        List<String> unaccounted = new ArrayList<>();
        for (String key : EventCatalog.eventNames()) {
            if (!ActionMap.known(key)) {
                unaccounted.add(key);
            }
        }
        assertThat(unaccounted)
                .as("Spyglass action keys with no Crusalis mapping and no stated reason")
                .isEmpty();
    }

    @Test
    void keysAreMatchedCaseInsensitively() {
        // Spyglass's own catalog mixes conventions: useSign beside shulker-open.
        assertThat(ActionMap.type("useSign")).isEqualTo("spyglass.block_use");
        assertThat(ActionMap.type("USESIGN")).isEqualTo("spyglass.block_use");
        assertThat(ActionMap.type("usesign")).isEqualTo("spyglass.block_use");
    }

    @Test
    void everyMappedTypeIsASpyglassType() {
        assertThat(ActionMap.types()).allSatisfy(type ->
                assertThat(type).startsWith("spyglass."));
    }

    @Test
    void theAggregatedTypesAreIncludedEvenThoughNoKeyMapsToThem() {
        // An explosion arrives as one break record per block and a WorldEdit operation
        // as one per changed block; both are aggregated, so neither has an action key.
        assertThat(ActionMap.types()).contains("spyglass.explosion", "spyglass.worldedit");
        assertThat(ActionMap.keys()).doesNotContain("explosion", "worldedit");
    }

    @Test
    void rollbackArtifactsAreIgnoredWithAReason() {
        for (String key : List.of("rolled-place", "rolled-break", "rolled-deposit",
                "rolled-withdraw")) {
            assertThat(ActionMap.type(key)).as(key).isNull();
            assertThat(ActionMap.ignored(key)).as(key)
                    .isEqualTo(ActionMap.Ignored.ROLLBACK_ARTIFACT);
        }
    }

    @Test
    void anActionKeyNobodyHasSeenIsNotKnown() {
        assertThat(ActionMap.known("something-spyglass-adds-in-2027")).isFalse();
        assertThat(ActionMap.type(null)).isNull();
        assertThat(ActionMap.known(null)).isFalse();
    }

    @Test
    void tableKeysAreAllLowerCaseSoLookupsCanNormalize() {
        assertThat(ActionMap.keys()).allSatisfy(key ->
                assertThat(key).isEqualTo(key.toLowerCase(Locale.ROOT)));
    }
}

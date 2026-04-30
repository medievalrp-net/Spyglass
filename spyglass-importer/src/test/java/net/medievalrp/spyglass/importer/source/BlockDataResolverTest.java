package net.medievalrp.spyglass.importer.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockDataResolverTest {

    private static final Map<Integer, String> MAP = Map.of(
            1, "axis=y",
            2, "axis=x",
            3, "facing=north",
            4, "waterlogged=false",
            5, "powered=true");

    @Test
    void returns_bare_material_when_blockdata_is_null() {
        String resolved = BlockDataResolver.resolve(
                "minecraft:stone", null, MAP);
        assertThat(resolved).isEqualTo("minecraft:stone");
    }

    @Test
    void returns_bare_material_when_blockdata_is_empty() {
        String resolved = BlockDataResolver.resolve(
                "minecraft:stone", "", MAP);
        assertThat(resolved).isEqualTo("minecraft:stone");
    }

    @Test
    void returns_bare_material_when_blockdata_is_zero_string() {
        // CoreProtect occasionally writes "0" for unset state.
        String resolved = BlockDataResolver.resolve(
                "minecraft:stone", "0", MAP);
        assertThat(resolved).isEqualTo("minecraft:stone");
    }

    @Test
    void resolves_single_token() {
        String resolved = BlockDataResolver.resolve(
                "minecraft:oak_log", "1", MAP);
        assertThat(resolved).isEqualTo("minecraft:oak_log[axis=y]");
    }

    @Test
    void resolves_multi_token_in_listed_order() {
        String resolved = BlockDataResolver.resolve(
                "minecraft:oak_log", "1,3,4", MAP);
        assertThat(resolved).isEqualTo(
                "minecraft:oak_log[axis=y,facing=north,waterlogged=false]");
    }

    @Test
    void skips_unknown_token_ids_without_failing_the_row() {
        // Token id 99 isn't in the map. We drop it and keep going.
        String resolved = BlockDataResolver.resolve(
                "minecraft:oak_log", "1,99,3", MAP);
        assertThat(resolved).isEqualTo("minecraft:oak_log[axis=y,facing=north]");
    }

    @Test
    void falls_back_to_bare_material_on_non_numeric_token() {
        // Corrupt rows shouldn't throw — they fall back to the
        // safe bare material name.
        String resolved = BlockDataResolver.resolve(
                "minecraft:oak_log", "1,not-a-number,3", MAP);
        assertThat(resolved).isEqualTo("minecraft:oak_log");
    }

    @Test
    void returns_null_when_material_is_missing() {
        // A row with no resolvable material isn't a block we can place
        // back; the mapper handles the null itself.
        String resolved = BlockDataResolver.resolve(null, "1,2", MAP);
        assertThat(resolved).isNull();
    }

    @Test
    void handles_whitespace_around_ids() {
        // CoreProtect doesn't emit whitespace, but a hand-edited DB
        // might. Be lenient.
        String resolved = BlockDataResolver.resolve(
                "minecraft:oak_log", " 1 , 3 ", MAP);
        assertThat(resolved).isEqualTo("minecraft:oak_log[axis=y,facing=north]");
    }
}

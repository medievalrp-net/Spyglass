package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import org.junit.jupiter.api.Test;

/**
 * {@link BlockParam} unit tests — limited to the paths that do not
 * invoke {@link org.bukkit.Material#isBlock()}.
 *
 * <p><b>Why the coverage gap:</b> Paper 1.21.8's {@code Material.isBlock()}
 * dispatches through {@code asBlockType()} → {@code RegistryAccess},
 * which only initializes once a real server is running. The parse-path
 * tests ({@code b:stone}, multi-material {@code In}, {@code isBlock}
 * rejection) therefore require the live RP_Server test harness — they
 * live in the integration-test layer, not here. The blank/null
 * rejection and alias contract are still pure logic, so we can pin
 * those here as fast guards.
 */
class BlockParamTest {

    @Test
    void blankValueRejected() {
        // value==null and value.isBlank() are checked before matchMaterial,
        // so these run cleanly without a server bootstrap.
        BlockParam param = new BlockParam();
        assertThatThrownBy(() -> param.parse("b", "", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("b", "   ", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("b", null, ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void errorMessageMentionsBlockForBlank() {
        // Pin the exact error wording — operator-facing and cited in docs.
        assertThatThrownBy(() -> new BlockParam().parse("b", null, ctx()))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("Block parameter requires a material");
    }

    @Test
    void aliasesReturnBothShortAndLong() {
        assertThat(new BlockParam().aliases()).containsExactly("b", "block");
    }

    @Test
    void aliasesAreImmutable() {
        // Regression guard: aliases() must return an unmodifiable list so
        // the QueryStringParser can use it as a cache key without
        // defensive copies.
        assertThatThrownBy(() -> new BlockParam().aliases().add("xxx"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void doesNotSuppressDefaultRadius() {
        // The block param doesn't imply a spatial scope, so default
        // radius injection should still fire.
        BlockParam param = new BlockParam();
        assertThat(param.suppressesDefaultRadius("b")).isFalse();
        assertThat(param.suppressesDefaultRadius("block")).isFalse();
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}

package net.medievalrp.omniscience2.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.medievalrp.omniscience2.api.param.ParamParseException;
import net.medievalrp.omniscience2.api.param.QueryParamHandler.ParamContext;
import org.junit.jupiter.api.Test;

/**
 * {@link ItemMaterialParam} unit tests — same coverage shape as
 * {@link BlockParamTest}. Parse-path tests that invoke
 * {@link org.bukkit.Material#isItem()} require Paper's
 * {@code RegistryAccess} bootstrap and therefore live in the
 * integration-test layer (the RP_Server-backed IT suite).
 */
class ItemMaterialParamTest {

    @Test
    void blankValueRejected() {
        ItemMaterialParam param = new ItemMaterialParam();
        assertThatThrownBy(() -> param.parse("i", "", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("i", "   ", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("i", null, ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void errorMessageMentionsMaterialForBlank() {
        assertThatThrownBy(() -> new ItemMaterialParam().parse("i", null, ctx()))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("i requires a material");
    }

    @Test
    void aliasesReturnBothShortAndLong() {
        assertThat(new ItemMaterialParam().aliases()).containsExactly("i", "item");
    }

    @Test
    void aliasesAreImmutable() {
        assertThatThrownBy(() -> new ItemMaterialParam().aliases().add("xxx"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void doesNotSuppressDefaultRadius() {
        ItemMaterialParam param = new ItemMaterialParam();
        assertThat(param.suppressesDefaultRadius("i")).isFalse();
        assertThat(param.suppressesDefaultRadius("item")).isFalse();
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}

package net.medievalrp.spyglass.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.api.event.StoredItem;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

class ItemSerializationTest {

    // Note: enchantment extraction is deliberately not unit-tested here.
    // Bukkit's Enchantment class can't be initialized in a plain JVM because
    // it resolves against the server registry on first touch, so Mockito
    // rejects it with "Cannot instrument ... supertypes could not be
    // initialized". The live integration test covers that branch.

    @Test
    void extractsNameAndLore() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.DIAMOND_SWORD);
        when(stack.serializeAsBytes()).thenReturn(new byte[]{1, 2, 3});

        ItemMeta meta = mock(ItemMeta.class);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.hasDisplayName()).thenReturn(true);
        when(meta.displayName()).thenReturn(Component.text("Excaliblur"));
        when(meta.hasLore()).thenReturn(true);
        when(meta.lore()).thenReturn(List.of(
                Component.text("Forged in fire"),
                Component.text("Blessed by saints")));
        when(meta.hasEnchants()).thenReturn(false);

        StoredItem item = ItemSerialization.storedItem(0, stack);

        assertThat(item).isNotNull();
        assertThat(item.material()).isEqualTo("DIAMOND_SWORD");
        assertThat(item.name()).isEqualTo("Excaliblur");
        assertThat(item.lore()).containsExactly("Forged in fire", "Blessed by saints");
        assertThat(item.enchants()).isEmpty();
        assertThat(item.data()).isNotBlank();
    }

    @Test
    void emptyForAir() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.AIR);
        StoredItem item = ItemSerialization.storedItem(0, stack);
        assertThat(item).isNull();
    }

    @Test
    void leavesFieldsBlankWhenNoMeta() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.STONE);
        when(stack.serializeAsBytes()).thenReturn(new byte[]{0});
        when(stack.getItemMeta()).thenReturn(null);

        StoredItem item = ItemSerialization.storedItem(0, stack);

        assertThat(item).isNotNull();
        assertThat(item.material()).isEqualTo("STONE");
        assertThat(item.name()).isNull();
        assertThat(item.lore()).isEmpty();
        assertThat(item.enchants()).isEmpty();
    }

    // ── projection-only path (#103): forensic records skip the blob ──

    @Test
    void projectionPopulatesFieldsButLeavesDataNullWithoutSerializing() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.DIAMOND_SWORD);
        // Deliberately do NOT stub serializeAsBytes(): the projection must
        // never touch it. (If it did, the unstubbed mock returns null and
        // Base64 encode would NPE — a second guard beyond the verify below.)
        ItemMeta meta = mock(ItemMeta.class);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.hasDisplayName()).thenReturn(true);
        when(meta.displayName()).thenReturn(Component.text("Excaliblur"));
        when(meta.hasLore()).thenReturn(true);
        when(meta.lore()).thenReturn(List.of(
                Component.text("Forged in fire"),
                Component.text("Blessed by saints")));
        when(meta.hasEnchants()).thenReturn(false);

        StoredItem item = ItemSerialization.storedItemProjection(0, stack);

        assertThat(item).isNotNull();
        assertThat(item.data()).as("projection must not carry the NBT blob").isNull();
        assertThat(item.material()).isEqualTo("DIAMOND_SWORD");
        assertThat(item.name()).isEqualTo("Excaliblur");
        assertThat(item.lore()).containsExactly("Forged in fire", "Blessed by saints");
        assertThat(item.enchants()).isEmpty();
        verify(stack, never()).serializeAsBytes();
    }

    @Test
    void projectionEmptyForAir() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.AIR);
        assertThat(ItemSerialization.storedItemProjection(0, stack)).isNull();
        verify(stack, never()).serializeAsBytes();
    }

    @Test
    void projectionWithoutMetaStillHasMaterialAndNullData() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.STONE);
        when(stack.getItemMeta()).thenReturn(null);

        StoredItem item = ItemSerialization.storedItemProjection(7, stack);

        assertThat(item).isNotNull();
        assertThat(item.slot()).isEqualTo(7);
        assertThat(item.material()).isEqualTo("STONE");
        assertThat(item.data()).isNull();
        assertThat(item.name()).isNull();
        assertThat(item.lore()).isEmpty();
        assertThat(item.enchants()).isEmpty();
        verify(stack, never()).serializeAsBytes();
    }
}

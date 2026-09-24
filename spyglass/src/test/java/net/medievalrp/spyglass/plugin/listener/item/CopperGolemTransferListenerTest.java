package net.medievalrp.spyglass.plugin.listener.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class CopperGolemTransferListenerTest {
    @Test void exactWithdrawalAndSplitDepositMatch() {
        assertThat(CopperGolemTransferListener.matches(new ItemStack[] {item(32, "a")},
                new ItemStack[] {item(16, "a")}, item(16, "a"), -16)).isTrue();
        assertThat(CopperGolemTransferListener.matches(new ItemStack[] {item(60, "a"), null},
                new ItemStack[] {item(64, "a"), item(12, "a")}, item(16, "a"), 16)).isTrue();
    }
    @Test void concurrentOrDifferentMetadataChangesAreNotAssignedToGolem() {
        assertThat(CopperGolemTransferListener.matches(new ItemStack[] {item(32, "a")},
                new ItemStack[] {item(15, "a")}, item(16, "a"), -16)).isFalse();
        assertThat(CopperGolemTransferListener.matches(new ItemStack[] {item(32, "b")},
                new ItemStack[] {item(16, "b")}, item(16, "a"), -16)).isFalse();
        assertThat(CopperGolemTransferListener.matches(new ItemStack[] {null},
                new ItemStack[] {null}, item(16, "a"), 16)).isFalse();
    }
    private static ItemStack item(int count, String identity) {
        ItemStack item = mock(ItemStack.class, identity);
        when(item.getType()).thenReturn(Material.DIAMOND);
        when(item.getAmount()).thenReturn(count);
        when(item.isSimilar(any())).thenAnswer(invocation -> {
            ItemStack other = invocation.getArgument(0);
            return other != null && mockingDetails(other).getMockCreationSettings().getMockName().toString().equals(identity);
        });
        return item;
    }
}

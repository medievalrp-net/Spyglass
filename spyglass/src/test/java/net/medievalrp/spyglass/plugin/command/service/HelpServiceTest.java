package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class HelpServiceTest {

    @Test
    void sendsTitleCommandsAndFlags() {
        CommandSender sender = mock(CommandSender.class);
        List<Component> captured = ServiceTestSupport.captureMessages(sender);

        new HelpService().send(sender);

        List<String> lines = ServiceTestSupport.plainTexts(captured);
        assertThat(lines).isNotEmpty();
        assertThat(lines.get(0)).contains("Spyglass");
        String combined = String.join("\n", lines);
        assertThat(combined)
                .contains("/spyglass search")
                .contains("/spyglass rollback")
                .contains("/spyglass restore")
                .contains("/spyglass undo")
                .contains("/spyglass page")
                .contains("/spyglass tool")
                .contains("/spyglass events");
    }

    // ===== #249/#281: every operator command discoverable, across pages ===

    @Test
    void everyRegisteredCommandAppearsAcrossThePages() {
        CommandSender sender = mock(CommandSender.class);
        List<Component> captured = ServiceTestSupport.captureMessages(sender);

        HelpService help = new HelpService();
        help.send(sender, 1);
        help.send(sender, 2);

        String combined = String.join("\n", ServiceTestSupport.plainTexts(captured));
        assertThat(combined)
                .contains("/spyglass help")
                .contains("/spyglass events")
                .contains("/spyglass rbqueue")
                .contains("/spyglass inventory")
                .contains("/spyglass stats")
                .contains("/spyglass import")
                .contains("/spyglass migrate")
                .contains("/spyglass version");
        // #281: mysql is an option on the single import entry, not its own
        // command; tele is internal (backs clickable rows) and stays out of
        // the operator-facing help.
        assertThat(combined)
                .as("mysql shown as an import mode")
                .contains("mysql <source>");
        assertThat(combined)
                .as("no standalone import-mysql entry, no tele entry")
                .doesNotContain("/spyglass import mysql")
                .doesNotContain("/spyglass tele");
    }

    @Test
    void paginatesWithHeaderCountAndClampsOutOfRangePages() {
        CommandSender sender = mock(CommandSender.class);
        List<Component> captured = ServiceTestSupport.captureMessages(sender);

        new HelpService().send(sender, 1);
        List<String> pageOne = ServiceTestSupport.plainTexts(captured);
        // Header carries the red clickable next-page arrow (#260), same
        // control as the search-result header.
        assertThat(pageOne.get(0)).contains("(1/2)").contains("[→]").doesNotContain("[←]");
        // header + tagline + 8 entries
        assertThat(pageOne).hasSize(10);

        captured.clear();
        new HelpService().send(sender, 99);
        List<String> clamped = ServiceTestSupport.plainTexts(captured);
        assertThat(clamped.get(0)).as("out-of-range page clamps to the last page")
                .contains("(2/2)").contains("[←]").doesNotContain("[→]");

        captured.clear();
        new HelpService().send(sender, -5);
        assertThat(ServiceTestSupport.plainTexts(captured).get(0)).contains("(1/2)");
    }
}

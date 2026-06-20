package net.medievalrp.spyglass.plugin.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class SpyglassCommandsTest {

    private static String plain(List<Component> lines) {
        return lines.stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c))
                .collect(Collectors.joining(" | "));
    }

    @Test
    void versionLinesShowVersionAuthorsAndServer() {
        String out = plain(SpyglassCommands.versionLines("1.0.0", List.of("MedievalRP"), "1.21.8"));
        assertThat(out)
                .contains("Spyglass v1.0.0")
                .contains("by MedievalRP")
                .contains("on Paper 1.21.8");
    }

    @Test
    void versionLinesOmitByWhenNoAuthors() {
        String out = plain(SpyglassCommands.versionLines("2.3.4", List.of(), "26.2"));
        assertThat(out)
                .contains("Spyglass v2.3.4")
                .contains("on Paper 26.2")
                .doesNotContain("by ");
    }
}

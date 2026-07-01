package net.medievalrp.spyglass.plugin.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportPathsTest {

    @TempDir
    Path importDir;

    @BeforeEach
    void setUp() throws IOException {
        // Create some test files
        Files.writeString(importDir.resolve("test1.db"), "content1");
        Files.writeString(importDir.resolve("test2.db"), "content2");
        Files.writeString(importDir.resolve("other.txt"), "not a db");
        Files.writeString(importDir.resolve("archive.db"), "content3");
    }

    @Test
    void resolvesValidInDirectoryFile() throws IOException {
        Path result = ImportPaths.resolveInside(importDir, "test1.db");
        assertThat(result).isEqualTo(importDir.resolve("test1.db"));
        assertThat(Files.isRegularFile(result)).isTrue();
    }

    @Test
    void rejectsPathTraversalWithDotDot() {
        assertThatThrownBy(() -> ImportPaths.resolveInside(importDir, "../escape.db"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsAbsolutePathEscape() {
        Path absolutePath = Path.of("/etc/passwd");
        assertThatThrownBy(() -> ImportPaths.resolveInside(importDir, absolutePath.toString()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsNonRegularFiles() throws IOException {
        Path subDir = importDir.resolve("subdir");
        Files.createDirectory(subDir);

        assertThatThrownBy(() -> ImportPaths.resolveInside(importDir, "subdir"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void listsDbFilesInSortedOrder() {
        var files = ImportPaths.listDbFiles(importDir);
        assertThat(files)
                .contains("archive.db", "test1.db", "test2.db")
                .doesNotContain("other.txt")
                .isSorted();
    }

    @Test
    void returnsEmptyListForMissingDirectory() {
        Path nonexistent = importDir.resolve("doesnotexist");
        var files = ImportPaths.listDbFiles(nonexistent);
        assertThat(files).isEmpty();
    }
}

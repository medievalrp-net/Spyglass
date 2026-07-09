package net.medievalrp.spyglass.plugin.imports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Utility for safely resolving import file paths and listing available database files.
 */
public final class ImportPaths {

    private ImportPaths() {
        // Utility class, no instances.
    }

    /**
     * Resolves a user-provided path within the import directory, preventing path traversal attacks.
     *
     * @param importDir the root directory for imports
     * @param userInput the user-provided path string
     * @return the resolved path, guaranteed to be within importDir and a regular file
     * @throws IOException if the resolved path escapes importDir, doesn't exist, or isn't a regular file
     */
    public static Path resolveInside(Path importDir, String userInput) throws IOException {
        // Normalize the import directory to absolute, canonical form
        Path normalizedImportDir = importDir.toRealPath();

        // Create a candidate path and normalize it
        Path candidate = normalizedImportDir.resolve(userInput).normalize();

        // Verify the candidate is still within importDir by checking that it starts with the
        // importDir path. This prevents both .. traversal and absolute path escapes.
        if (!candidate.startsWith(normalizedImportDir)) {
            throw new IOException("Path traversal detected: " + userInput);
        }

        // Verify the file exists and is a regular file
        if (!Files.isRegularFile(candidate)) {
            throw new IOException("File not found or is not a regular file: " + userInput);
        }

        // Re-check containment on the real (symlink-resolved) path: the lexical
        // check above only catches ".." / absolute-path escapes. A symlink that
        // lives inside importDir but points outside it would pass the lexical
        // check yet still resolve to a file outside importDir.
        Path realBase = normalizedImportDir.toRealPath();
        Path real = candidate.toRealPath();
        if (!real.startsWith(realBase)) {
            throw new IOException("Path traversal detected: " + userInput);
        }

        return candidate;
    }

    /**
     * Lists all database files (.db) in the import directory in sorted order.
     *
     * @param importDir the directory to list files from
     * @return sorted list of .db filenames; empty list if directory doesn't exist or is not a directory
     */
    public static List<String> listDbFiles(Path importDir) {
        // If directory doesn't exist, return empty list
        if (!Files.isDirectory(importDir)) {
            return Collections.emptyList();
        }

        List<String> dbFiles = new ArrayList<>();

        try (Stream<Path> paths = Files.list(importDir)) {
            paths.filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.endsWith(".db"))
                    .forEach(dbFiles::add);
        } catch (IOException e) {
            // If reading the directory fails, return empty list
            return Collections.emptyList();
        }

        // Sort before returning
        Collections.sort(dbFiles);
        return dbFiles;
    }
}

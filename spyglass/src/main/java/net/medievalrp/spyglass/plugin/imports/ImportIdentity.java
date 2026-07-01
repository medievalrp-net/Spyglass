package net.medievalrp.spyglass.plugin.imports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Credential-free identity for import sources.
 *
 * Used as the re-import cache key to identify whether a data source has been imported before,
 * without storing any credentials.
 */
public class ImportIdentity {

	private ImportIdentity() {
		// Utility class, no instances
	}

	/**
	 * Generate identity for a SQLite file based on its content hash.
	 *
	 * @param path path to the SQLite database file
	 * @return identity string in format "sqlite-sha256:&lt;hex&gt;"
	 * @throws IOException if the file cannot be read
	 */
	public static String ofSqliteFile(Path path) throws IOException {
		byte[] fileContent = Files.readAllBytes(path);
		String hash = computeSha256(fileContent);
		return "sqlite-sha256:" + hash;
	}

	/**
	 * Generate identity for a MySQL database connection.
	 *
	 * The identity contains no credentials and only the normalized connection parameters.
	 *
	 * @param host database host (will be lowercased)
	 * @param port database port
	 * @param database database name
	 * @return identity string in format "mysql://&lt;host&gt;:&lt;port&gt;/&lt;database&gt;"
	 */
	public static String ofMysql(String host, int port, String database) {
		String lowercasedHost = host.toLowerCase();
		return String.format("mysql://%s:%d/%s", lowercasedHost, port, database);
	}

	/**
	 * Compute SHA-256 hash of the given bytes.
	 *
	 * @param data bytes to hash
	 * @return hexadecimal representation of the SHA-256 hash
	 */
	private static String computeSha256(byte[] data) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(data);
			return bytesToHex(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-256 algorithm not available", e);
		}
	}

	/**
	 * Convert bytes to hexadecimal string representation.
	 *
	 * @param bytes bytes to convert
	 * @return hexadecimal representation
	 */
	private static String bytesToHex(byte[] bytes) {
		StringBuilder hex = new StringBuilder();
		for (byte b : bytes) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}
}

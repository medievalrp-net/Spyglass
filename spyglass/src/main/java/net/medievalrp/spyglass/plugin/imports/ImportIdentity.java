package net.medievalrp.spyglass.plugin.imports;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

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
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
		byte[] buffer = new byte[1 << 16];
		try (InputStream in = Files.newInputStream(path)) {
			int read;
			while ((read = in.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
		}
		String hash = bytesToHex(digest.digest());
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
		String lowercasedHost = host.toLowerCase(Locale.ROOT);
		return String.format("mysql://%s:%d/%s", lowercasedHost, port, database);
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

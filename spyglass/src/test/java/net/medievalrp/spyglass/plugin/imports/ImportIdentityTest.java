package net.medievalrp.spyglass.plugin.imports;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ImportIdentityTest {

	@Test
	void testOfSqliteFileWithKnownContent(@TempDir Path tempDir) throws IOException {
		// Create a test file with known content
		Path testFile = tempDir.resolve("test.db");
		byte[] testContent = {1, 2, 3, 4};
		Files.write(testFile, testContent);

		// SHA-256 of bytes {1,2,3,4}
		String identity = ImportIdentity.ofSqliteFile(testFile);

		// Verify format
		assertTrue(identity.startsWith("sqlite-sha256:"), "Identity should start with 'sqlite-sha256:'");

		// Verify the hash is the expected SHA-256 of {1,2,3,4}
		// SHA-256(0x01 0x02 0x03 0x04) = 9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a
		String expectedHash = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a";
		assertEquals("sqlite-sha256:" + expectedHash, identity);
	}

	@Test
	void testOfSqliteFileContentBased(@TempDir Path tempDir) throws IOException {
		// Create two files with same content but different names
		Path file1 = tempDir.resolve("file1.db");
		Path file2 = tempDir.resolve("file2.db");
		byte[] content = "test content".getBytes();

		Files.write(file1, content);
		Files.write(file2, content);

		String identity1 = ImportIdentity.ofSqliteFile(file1);
		String identity2 = ImportIdentity.ofSqliteFile(file2);

		// Should be identical since content is the same
		assertEquals(identity1, identity2, "Identical content should produce identical identity");
	}

	@Test
	void testOfMysqlBasic() {
		String identity = ImportIdentity.ofMysql("localhost", 3306, "mydatabase");
		assertEquals("mysql://localhost:3306/mydatabase", identity);
	}

	@Test
	void testOfMysqlLowercasesHost() {
		String identity = ImportIdentity.ofMysql("MyHost.Example.COM", 3306, "mydb");
		assertEquals("mysql://myhost.example.com:3306/mydb", identity);
	}

	@Test
	void testOfMysqlDifferentPort() {
		String identity = ImportIdentity.ofMysql("db.example.com", 3307, "production");
		assertEquals("mysql://db.example.com:3307/production", identity);
	}

	@Test
	void testOfMysqlDifferentDatabase() {
		String identity = ImportIdentity.ofMysql("host", 3306, "CustomDB");
		assertEquals("mysql://host:3306/CustomDB", identity);
	}
}

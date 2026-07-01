package net.medievalrp.spyglass.plugin.importer.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MysqlSourceTest {

    @Test
    void parses_canonical_url_with_port() {
        MysqlSource.ConnectionSpec spec = MysqlSource.parse(
                "mysql://coreprotect:hunter2@db.example.com:3306/coreprotect");
        assertThat(spec.user()).isEqualTo("coreprotect");
        assertThat(spec.password()).isEqualTo("hunter2");
        assertThat(spec.host()).isEqualTo("db.example.com");
        assertThat(spec.port()).isEqualTo(3306);
        assertThat(spec.database()).isEqualTo("coreprotect");
    }

    @Test
    void defaults_port_to_3306_when_omitted() {
        MysqlSource.ConnectionSpec spec = MysqlSource.parse(
                "mysql://user:pw@host/db");
        assertThat(spec.port()).isEqualTo(3306);
    }

    @Test
    void parses_password_containing_at_sign() {
        // Password with @ in it requires the LAST @ to be the separator
        // (we use lastIndexOf, not indexOf).
        MysqlSource.ConnectionSpec spec = MysqlSource.parse(
                "mysql://u:p@ssw0rd@h:3306/d");
        assertThat(spec.user()).isEqualTo("u");
        assertThat(spec.password()).isEqualTo("p@ssw0rd");
        assertThat(spec.host()).isEqualTo("h");
    }

    @Test
    void emits_jdbc_url_with_cursor_fetch_flags() {
        MysqlSource.ConnectionSpec spec = MysqlSource.parse(
                "mysql://u:p@h:3306/d");
        String jdbc = spec.jdbcUrl();
        assertThat(jdbc).startsWith("jdbc:mysql://h:3306/d?");
        // Without useCursorFetch=true, MySQL Connector/J ignores
        // setFetchSize and OOMs on large reads. Required for streaming.
        assertThat(jdbc).contains("useCursorFetch=true");
        // characterEncoding shouldn't be set — Connector/J wants Java
        // charset names there, not MySQL collation names. Past
        // regression: passing 'utf8mb4' here threw "Unsupported
        // character encoding" at connect time.
        assertThat(jdbc).doesNotContain("characterEncoding");
    }

    @Test
    void rejects_url_without_scheme() {
        assertThatThrownBy(() -> MysqlSource.parse("user:pw@host/db"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mysql://");
    }

    @Test
    void rejects_url_without_credentials() {
        assertThatThrownBy(() -> MysqlSource.parse("mysql://host/db"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user:password");
    }

    @Test
    void rejects_url_without_database() {
        assertThatThrownBy(() -> MysqlSource.parse("mysql://u:p@host"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("database");
    }

    @Test
    void rejects_non_numeric_port() {
        assertThatThrownBy(() -> MysqlSource.parse("mysql://u:p@host:abc/db"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }
}

package net.medievalrp.spyglass.plugin.importer.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MysqlSourceTest {

    @Test
    void jdbcUrlUsesMariaDbSchemeWithCursorFetch() {
        MysqlSource.ConnectionSpec spec =
                new MysqlSource.ConnectionSpec("db.host", 3307, "coreprotect", "reader", "pw");
        String url = spec.jdbcUrl();
        assertThat(url).startsWith("jdbc:mariadb://db.host:3307/coreprotect");
        assertThat(url).contains("useCursorFetch=true");
    }

    @Test
    void parseAcceptsUserFacingMysqlUrl() {
        MysqlSource.ConnectionSpec spec =
                MysqlSource.parse("mysql://reader:pw@db.host:3307/coreprotect");
        assertThat(spec.host()).isEqualTo("db.host");
        assertThat(spec.port()).isEqualTo(3307);
        assertThat(spec.database()).isEqualTo("coreprotect");
        assertThat(spec.user()).isEqualTo("reader");
        assertThat(spec.password()).isEqualTo("pw");
    }

    @Test
    void parseRejectsNonMysqlScheme() {
        assertThatThrownBy(() -> MysqlSource.parse("http://x/y"))
                .isInstanceOf(IllegalArgumentException.class);
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

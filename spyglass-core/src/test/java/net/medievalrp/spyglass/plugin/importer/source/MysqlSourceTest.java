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
}

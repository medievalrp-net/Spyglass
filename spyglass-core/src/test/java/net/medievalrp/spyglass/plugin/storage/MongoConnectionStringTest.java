package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MongoConnectionStringTest {

    @Test
    void overrideIsUsedVerbatimWhenSet() {
        String override = "mongodb+srv://u:p@cluster.example.mongodb.net/?retryWrites=true";
        assertThat(MongoConnectionString.resolve(override, "ignored", 1, "ignored", "ignored", true))
                .isEqualTo(override);
    }

    @Test
    void blankOverrideAssemblesFromHostAndPort() {
        assertThat(MongoConnectionString.resolve("", "db.internal", 27018, "", "", false))
                .isEqualTo("mongodb://db.internal:27018");
    }

    @Test
    void credentialsAreIncludedAndPercentEncoded() {
        // '@' ':' '/' in the password would break the URI unless escaped.
        assertThat(MongoConnectionString.resolve(null, "localhost", 27017, "admin", "p@ss:w/rd", false))
                .isEqualTo("mongodb://admin:p%40ss%3Aw%2Frd@localhost:27017");
    }

    @Test
    void userWithoutPasswordStillAuthenticates() {
        assertThat(MongoConnectionString.resolve("", "localhost", 27017, "reader", "", false))
                .isEqualTo("mongodb://reader@localhost:27017");
    }

    @Test
    void sslAddsTheTlsOption() {
        assertThat(MongoConnectionString.resolve("", "localhost", 27017, "", "", true))
                .isEqualTo("mongodb://localhost:27017/?tls=true");
    }
}

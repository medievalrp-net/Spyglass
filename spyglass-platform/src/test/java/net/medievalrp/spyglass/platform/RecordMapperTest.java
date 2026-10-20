package net.medievalrp.spyglass.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import org.junit.jupiter.api.Test;

class RecordMapperTest {

    private final FakePlatform platform = new FakePlatform();
    private final PlatformSettings settings = new PlatformSettings(true, Set.of("*"),
            Set.of(), Set.of("login"), Set.of("msg"), true);
    private final PlatformPublisher publisher =
            new PlatformPublisher(platform, settings, message -> { });

    private FakePlatform.Published publish(net.medievalrp.spyglass.api.event.EventRecord r) {
        assertThat(publisher.publish(r)).as("the record was not published").isTrue();
        return platform.published.get(platform.published.size() - 1);
    }

    @Test
    void aPlayerBlockBreakCarriesTheBaseFieldsAndAnEmptyCause() {
        FakePlatform.Published event =
                publish(Records.blockBreak("break", Records.player(), Origin.player()));

        assertThat(event.type()).isEqualTo("spyglass.block_break");
        assertThat(event.uuid()).isEqualTo(Records.PLAYER);
        assertThat(event.json()).isEqualTo("{\"world\":\"world\",\"x\":100,\"y\":64,"
                + "\"z\":-200,\"cause\":\"\",\"target\":\"minecraft:stone\",\"amount\":1,"
                + "\"text\":\"\",\"data\":{\"was\":\"minecraft:stone\","
                + "\"was_data\":\"minecraft:stone\",\"now\":\"minecraft:air\","
                + "\"now_data\":\"minecraft:air\"}}");
    }

    @Test
    void aCreeperBlockBreakHasNoUuidAndAHashedCause() {
        FakePlatform.Published event = publish(Records.blockBreak("break",
                Source.entity(java.util.UUID.randomUUID(), "CREEPER"),
                Origin.environment("entity-explode:creeper")));

        // The envelope's uuid is the acting PLAYER, and there is not one here. A
        // creeper's own entity id in that field would make it look like an account.
        assertThat(event.uuid()).isNull();
        assertThat(event.json()).contains("\"cause\":\"#creeper\"");
    }

    @Test
    void chatPublishesTheMessageTheRecipientCountAndItsExtensions() {
        FakePlatform.Published event = publish(Records.chat("hello town"));

        assertThat(event.type()).isEqualTo("spyglass.chat");
        assertThat(event.json()).contains("\"text\":\"hello town\"")
                // The count, not 300 uuids in a 2 KiB payload.
                .contains("\"recipients\":2")
                .contains("\"ext\":{\"channel\":\"town\"}");
    }

    @Test
    void aRedactedCommandLosesItsArgumentsAndKeepsItsName() {
        FakePlatform.Published event = publish(Records.command("/login hunter2"));

        assertThat(event.json()).contains("\"target\":\"login\"")
                .contains("\"text\":\"/login <redacted>\"")
                .doesNotContain("hunter2");
    }

    @Test
    void aPrivateCommandIsFlaggedAndStillCarriesItsText() {
        FakePlatform.Published event = publish(Records.command("/msg Someone meet me at spawn"));

        assertThat(event.json()).contains("\"private\":true")
                // Dropping it would make the store useless for the harassment reports
                // that are the reason these are logged.
                .contains("meet me at spawn");
    }

    @Test
    void anOrdinaryCommandHasNoPrivateFlag() {
        assertThat(publish(Records.command("/home town")).json())
                .contains("\"data\":{}")
                .doesNotContain("private");
    }

    @Test
    void aWithdrawCarriesTheAmountTheContainerAndTheItem() {
        FakePlatform.Published event = publish(Records.withdraw(17));

        assertThat(event.type()).isEqualTo("spyglass.container_remove");
        assertThat(event.json()).contains("\"amount\":17")
                .contains("\"container\":\"minecraft:chest\"")
                .contains("\"slot\":3")
                .contains("\"item\":{\"material\":\"minecraft:diamond\",\"name\":\"Shiny\","
                        + "\"enchants\":1}");
    }

    @Test
    void aHitCarriesTheDamage() {
        assertThat(publish(Records.hit(6.5)).json())
                .contains("\"damage\":6.5")
                .contains("\"projectile\":false");
    }

    @Test
    void aRollbackJobPublishesItsModeAndJobId() {
        FakePlatform.Published event = publish(Records.rollbackOp());

        assertThat(event.type()).isEqualTo("spyglass.rollback");
        assertThat(event.json()).contains("\"kind\":\"rollback\"")
                .contains("\"job_id\":\"job-7f3a\"")
                .contains("\"text\":\"job-7f3a\"");
    }

    /** D12, asserted over every fixture rather than over the one record that has an IP. */
    @Test
    void noPublishedEventContainsAnythingThatLooksLikeAnIp() {
        List<net.medievalrp.spyglass.api.event.EventRecord> fixtures = List.of(
                Records.blockBreak("break", Records.player(), Origin.player()),
                Records.chat("my server is 192.168.1.1, join me"),
                Records.command("/login 10.0.0.5"),
                Records.withdraw(1),
                Records.hit(1.0),
                Records.join("203.0.113.47"),
                Records.rollbackOp());
        fixtures.forEach(publisher::publish);

        assertThat(platform.published).isNotEmpty();
        for (FakePlatform.Published event : platform.published) {
            if (event.type().equals("spyglass.chat") || event.type().equals("spyglass.command")) {
                // A player who types an address into chat published it themselves; the
                // rule is about fields Spyglass collected, not about what was said.
                continue;
            }
            assertThat(Ips.looksLikeIp(event.json()))
                    .as("%s published something IP-shaped: %s", event.type(), event.json())
                    .isFalse();
        }
    }

    @Test
    void aJoinNeverPublishesTheAddressItWasRecordedWith() {
        FakePlatform.Published event = publish(Records.join("203.0.113.47"));

        assertThat(event.type()).isEqualTo("spyglass.session_join");
        assertThat(event.json()).doesNotContain("203.0.113.47");
        assertThat(event.json()).contains("\"data\":{}");
    }

    @Test
    void anExtensionValueThatLooksLikeAnAddressIsDropped() {
        // An integrator's own key/value, checked rather than trusted.
        var record = new net.medievalrp.spyglass.api.event.ChatRecord(
                java.util.UUID.randomUUID(), "say", Records.WHEN, Records.EXPIRES,
                Origin.player(), Records.player(), Records.AT, "srv:test", "", "hello",
                List.of(), java.util.Map.of("channel", "town", "origin-host", "198.51.100.9"));

        assertThat(publish(record).json())
                .contains("\"channel\":\"town\"")
                .doesNotContain("198.51.100.9");
    }
}

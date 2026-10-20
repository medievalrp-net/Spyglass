package net.medievalrp.spyglass.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.medievalrp.spyglass.api.event.Origin;
import org.junit.jupiter.api.Test;

class PlatformPublisherTest {

    private final FakePlatform platform = new FakePlatform();
    private final List<String> warnings = new ArrayList<>();

    private PlatformPublisher publisher(PlatformSettings settings) {
        return new PlatformPublisher(platform, settings, warnings::add);
    }

    private PlatformSettings settings() {
        return new PlatformSettings(true, Set.of("*"), Set.of(), Set.of("login"),
                Set.of("msg"), true);
    }

    @Test
    void excludeWinsOverInclude() {
        PlatformPublisher publisher = publisher(new PlatformSettings(true, Set.of("*"),
                Set.of("break"), Set.of(), Set.of(), true));

        assertThat(publisher.publish(
                Records.blockBreak("break", Records.player(), Origin.player()))).isFalse();
        assertThat(platform.published).isEmpty();
    }

    @Test
    void anIncludeListPublishesOnlyWhatItNames() {
        PlatformPublisher publisher = publisher(new PlatformSettings(true, Set.of("say"),
                Set.of(), Set.of(), Set.of(), true));

        assertThat(publisher.publish(Records.chat("hello"))).isTrue();
        assertThat(publisher.publish(
                Records.blockBreak("break", Records.player(), Origin.player()))).isFalse();
    }

    @Test
    void aDisabledTypePublishesNothingAndIsNotAnError() {
        platform.disabled.add("spyglass.block_break");
        PlatformPublisher publisher = publisher(settings());

        assertThat(publisher.publish(
                Records.blockBreak("break", Records.player(), Origin.player()))).isFalse();
        assertThat(warnings).isEmpty();
        assertThat(publisher.isDisabled()).isFalse();
    }

    @Test
    void aFullRingCountsADropRatherThanFailing() {
        platform.ringFull = true;
        PlatformPublisher publisher = publisher(settings());

        assertThat(publisher.publish(Records.chat("hello"))).isFalse();
        assertThat(publisher.dropped()).isEqualTo(1);
        assertThat(warnings).isEmpty();
    }

    @Test
    void aRollbacksOwnBlockRecordsAreNeverPublished() {
        PlatformPublisher publisher = publisher(settings());

        // Origin ROLLBACK: Spyglass synthesized this while reversing something. The
        // rollback publishes one event of its own; this would double every action it
        // reversed.
        assertThat(publisher.publish(Records.blockBreak("rolled-break", Records.player(),
                Origin.rollback("TheOperator")))).isFalse();
        assertThat(platform.published).isEmpty();
    }

    @Test
    void theRollbackJobItselfIsPublishedUnlessTurnedOff() {
        assertThat(publisher(settings()).publish(Records.rollbackOp())).isTrue();

        platform.published.clear();
        PlatformPublisher off = publisher(new PlatformSettings(true, Set.of("*"), Set.of(),
                Set.of(), Set.of(), false));
        assertThat(off.publish(Records.rollbackOp())).isFalse();
    }

    @Test
    void anUnmappedActionWarnsOnceAndThenStaysQuiet() {
        PlatformPublisher publisher = publisher(settings());
        var record = Records.blockBreak("something-spyglass-adds-in-2027", Records.player(),
                Origin.player());

        for (int i = 0; i < 50; i++) {
            assertThat(publisher.publish(record)).isFalse();
        }

        assertThat(publisher.unmapped()).isEqualTo(50);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("something-spyglass-adds-in-2027")
                .contains("ActionMap");
    }

    @Test
    void anIgnoredActionDoesNotWarnAtAll() {
        PlatformPublisher publisher = publisher(settings());

        assertThat(publisher.publish(
                Records.blockBreak("transfer-out", Records.player(), Origin.player()))).isFalse();
        assertThat(warnings).isEmpty();
    }

    /**
     * The self-disable, which is the rule the whole class is written around: Spyglass
     * must not break because the platform is having a bad day.
     */
    @Test
    void aStormOfFailuresDisablesTheIntegrationInsteadOfFillingTheConsole() {
        platform.claimFailure = new IllegalStateException("the SDK changed under us");
        PlatformPublisher publisher = publisher(settings());

        for (int i = 0; i < PlatformPublisher.ERROR_BUDGET_PER_MINUTE; i++) {
            assertThat(publisher.publish(Records.chat("hello"))).isFalse();
        }

        assertThat(publisher.isDisabled()).isTrue();
        // One line for the first failure and one for the disable, not a thousand.
        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(1)).contains("disabled").contains("Spyglass's own storage");

        // And it stays off, cheaply: no further claim is attempted.
        platform.claimFailure = null;
        assertThat(publisher.publish(Records.chat("hello"))).isFalse();
        assertThat(platform.published).isEmpty();
    }

    @Test
    void failuresSpreadOverTimeDoNotDisableIt() {
        platform.claimFailure = new IllegalStateException("one bad record");
        PlatformPublisher publisher = publisher(settings());

        for (int window = 0; window < 3; window++) {
            for (int i = 0; i < PlatformPublisher.ERROR_BUDGET_PER_MINUTE - 1; i++) {
                publisher.publish(Records.chat("hello"));
            }
            // A minute passes with no further failure.
            publisher.resetWindowForTest();
        }

        assertThat(publisher.isDisabled()).isFalse();
    }

    @Test
    void anErrorThrownByTheMapperDoesNotEscapeIntoSpyglass() {
        // A record whose location is null is not forbidden by the API, and the mapper
        // has to survive it: an exception here would propagate into the commit path.
        var broken = new net.medievalrp.spyglass.api.event.ChatRecord(
                java.util.UUID.randomUUID(), "say", Records.WHEN, Records.EXPIRES,
                Origin.player(), Records.player(), null, "srv:test", "", null,
                List.of(), java.util.Map.of());
        PlatformPublisher publisher = publisher(settings());

        assertThat(publisher.publish(broken)).isTrue();
        assertThat(platform.published.get(0).json()).contains("\"world\":\"\"");
    }
}

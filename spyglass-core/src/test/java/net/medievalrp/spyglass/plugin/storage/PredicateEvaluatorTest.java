package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.CommandRecord;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.junit.jupiter.api.Test;

/**
 * {@link PredicateEvaluator} resolution of the item {@code tags} projection
 * (#140). This is the in-memory post-filter ClickHouse and SQLite fall back
 * to for the {@code itags:} query, since custom_data lives inside an opaque
 * item blob on those backends rather than an indexable column.
 */
class PredicateEvaluatorTest {

    private static EventRecord deposit(StoredItem afterItem) {
        Instant now = Instant.now();
        return new ContainerDepositRecord(
                UUID.randomUUID(), "deposit", now, now.plusSeconds(60),
                Origin.player(), Source.player(UUID.randomUUID(), "Alice"),
                new BlockLocation(UUID.randomUUID(), "world", 1, 2, 3),
                "srv", "PAPER", "CHEST", 0, 1, null, afterItem);
    }

    private static QueryPredicate tagsMatch(String term) {
        return new QueryPredicate.Eq("afterItem.tags",
                Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE));
    }

    @Test
    void matchesCustomDataSubstringCaseInsensitively() {
        EventRecord record = deposit(new StoredItem(
                0, "PAPER", null, null, List.of(), List.of(),
                "{quest:\"deliver_letter\"}"));

        assertThat(PredicateEvaluator.matchesAll(List.of(tagsMatch("deliver_letter")), record)).isTrue();
        assertThat(PredicateEvaluator.matchesAll(List.of(tagsMatch("DELIVER")), record)).isTrue();
        assertThat(PredicateEvaluator.matchesAll(List.of(tagsMatch("slay_dragon")), record)).isFalse();
    }

    @Test
    void resolvesJoinAddressForResidualIpFilter() {
        // The ip: resolver runs Eq(event=join) AND Eq(address, X) through the
        // store; on SQLite (address folded into the blob) that lands here as a
        // residual filter. Without the "address" field case it resolved to
        // null and ip: matched nobody. Regression-locks that the decoded
        // JoinRecord's address is what the residual filter compares.
        Instant now = Instant.now();
        EventRecord join = new JoinRecord(
                UUID.randomUUID(), "join", now, now.plusSeconds(60),
                Origin.player(), Source.player(UUID.randomUUID(), "Alice"),
                new BlockLocation(UUID.randomUUID(), "world", 1, 2, 3),
                "srv", "Alice", "203.0.113.7");

        QueryPredicate match = new QueryPredicate.Eq("address", "203.0.113.7");
        QueryPredicate miss = new QueryPredicate.Eq("address", "198.51.100.1");
        assertThat(PredicateEvaluator.matchesAll(List.of(match), join)).isTrue();
        assertThat(PredicateEvaluator.matchesAll(List.of(miss), join)).isFalse();
        // A non-join record has no address -> never matches an address filter.
        assertThat(PredicateEvaluator.matchesAll(List.of(match), deposit(null))).isFalse();
    }

    @Test
    void doesNotMatchWhenItemHasNoCustomData() {
        // tags == null (vanilla item / no custom_data) -> itags: never matches
        EventRecord record = deposit(new StoredItem(0, "PAPER", null));
        assertThat(PredicateEvaluator.matchesAll(List.of(tagsMatch("anything")), record)).isFalse();
    }

    @Test
    void doesNotMatchWhenItemAbsent() {
        EventRecord record = deposit(null);
        assertThat(PredicateEvaluator.matchesAll(List.of(tagsMatch("anything")), record)).isFalse();
    }

    @Test
    void resolvesChatMessageForResidualMessageFilter() {
        // m: builds Or(Eq("message", pattern), Eq("commandLine", pattern)); on
        // SQLite both fields live in the blob, so the filter lands here. Without
        // the "message" case it resolved to null and m: returned empty (#156).
        EventRecord chat = chat("hello there, friend", List.of());
        QueryPredicate match = new QueryPredicate.Eq("message",
                Pattern.compile(Pattern.quote("there"), Pattern.CASE_INSENSITIVE));
        QueryPredicate miss = new QueryPredicate.Eq("message",
                Pattern.compile(Pattern.quote("absent"), Pattern.CASE_INSENSITIVE));
        assertThat(PredicateEvaluator.matchesAll(List.of(match), chat)).isTrue();
        assertThat(PredicateEvaluator.matchesAll(List.of(miss), chat)).isFalse();
        // A non-chat record carries no message -> never matches.
        assertThat(PredicateEvaluator.matchesAll(List.of(match), deposit(null))).isFalse();
    }

    @Test
    void resolvesCommandLineForResidualMessageFilter() {
        // The commandLine half of m:'s Or — a CommandRecord, blob-folded on SQLite.
        EventRecord command = command("/tp Alice Bob");
        QueryPredicate match = new QueryPredicate.Eq("commandLine",
                Pattern.compile(Pattern.quote("Alice"), Pattern.CASE_INSENSITIVE));
        QueryPredicate miss = new QueryPredicate.Eq("commandLine",
                Pattern.compile(Pattern.quote("Charlie"), Pattern.CASE_INSENSITIVE));
        assertThat(PredicateEvaluator.matchesAll(List.of(match), command)).isTrue();
        assertThat(PredicateEvaluator.matchesAll(List.of(miss), command)).isFalse();
    }

    @Test
    void resolvesRecipientsForResidualRcpFilter() {
        // rcp: builds Eq/In over "recipients" (a List<UUID>); equalsValue's
        // list-containment matches when any recipient equals the queried id.
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();
        EventRecord chat = chat("psst", List.of(alice, bob));
        assertThat(PredicateEvaluator.matchesAll(
                List.of(new QueryPredicate.Eq("recipients", alice)), chat)).isTrue();
        assertThat(PredicateEvaluator.matchesAll(
                List.of(new QueryPredicate.In("recipients", List.of(carol, bob))), chat)).isTrue();
        assertThat(PredicateEvaluator.matchesAll(
                List.of(new QueryPredicate.Eq("recipients", carol)), chat)).isFalse();
    }

    private static EventRecord chat(String message, List<UUID> recipients) {
        Instant now = Instant.now();
        return new ChatRecord(
                UUID.randomUUID(), "say", now, now.plusSeconds(60),
                Origin.player(), Source.player(UUID.randomUUID(), "Alice"),
                new BlockLocation(UUID.randomUUID(), "world", 1, 2, 3),
                "srv", "GLOBAL", message, recipients, java.util.Map.of());
    }

    private static EventRecord command(String commandLine) {
        Instant now = Instant.now();
        return new CommandRecord(
                UUID.randomUUID(), "command", now, now.plusSeconds(60),
                Origin.player(), Source.player(UUID.randomUUID(), "Alice"),
                new BlockLocation(UUID.randomUUID(), "world", 1, 2, 3),
                "srv", "Alice", commandLine);
    }
}

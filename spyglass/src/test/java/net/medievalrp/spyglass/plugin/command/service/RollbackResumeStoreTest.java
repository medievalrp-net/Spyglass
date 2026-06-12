package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RollbackResumeStoreTest {

    private static final UUID JOB = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OPERATOR = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @TempDir
    Path dir;

    private RollbackResumeStore store;

    @BeforeEach
    void setUp() {
        store = new RollbackResumeStore(dir, Logger.getLogger("test"));
    }

    @Test
    void roundTripsStoredRequestPlan() {
        store.markStart(JOB, "Alice", OPERATOR, "p:Griefer r:30",
                RollbackJob.Mode.ROLLBACK, "c29tZS1iYXNlNjQ=");

        var pending = store.listPending();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).requestBase64()).isEqualTo("c29tZS1iYXNlNjQ=");
        assertThat(pending.get(0).query()).isEqualTo("p:Griefer r:30");
    }

    @Test
    void progressRewritePreservesRequestPlan() {
        store.markStart(JOB, "Alice", OPERATOR, "p:Griefer",
                RollbackJob.Mode.ROLLBACK, "c29tZS1iYXNlNjQ=");
        store.markProgress(JOB, "Alice", OPERATOR, "p:Griefer",
                RollbackJob.Mode.ROLLBACK, "c29tZS1iYXNlNjQ=", Instant.now(),
                new RollbackResumeStore.Cursor(Instant.now(), UUID.randomUUID()), 500, 3);

        var pending = store.listPending();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).requestBase64()).isEqualTo("c29tZS1iYXNlNjQ=");
        assertThat(pending.get(0).appliedSoFar()).isEqualTo(500);
        assertThat(pending.get(0).cursor()).isNotNull();
    }

    @Test
    void nullRequestPlanWritesNoLineAndReadsBackNull() {
        store.markStart(JOB, "Alice", OPERATOR, "undo rollback ab12cd34",
                RollbackJob.Mode.ROLLBACK, null);

        var pending = store.listPending();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).requestBase64()).isNull();
    }

    // A marker written by a pre-#49 Spyglass: same key=value shape,
    // no request line. Must read cleanly with a null plan instead of
    // failing the whole listPending scan.
    @Test
    void legacyMarkerFileReadsWithNullRequestPlan() throws Exception {
        String legacy = "id=" + JOB + "\n"
                + "operatorName=Alice\n"
                + "operatorId=" + OPERATOR + "\n"
                + "mode=ROLLBACK\n"
                + "query=p:Griefer r:30\n"
                + "startedAt=2026-06-01T10:00:00Z\n"
                + "appliedSoFar=120\n"
                + "skippedSoFar=4\n";
        // The store keys markers under <dataDir>/resume/.
        Files.writeString(dir.resolve("resume").resolve(JOB + ".resume"), legacy,
                StandardCharsets.UTF_8);

        var pending = store.listPending();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).requestBase64()).isNull();
        assertThat(pending.get(0).appliedSoFar()).isEqualTo(120);
    }
}

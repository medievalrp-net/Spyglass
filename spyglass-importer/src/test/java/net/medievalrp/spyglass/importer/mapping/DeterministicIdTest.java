package net.medievalrp.spyglass.importer.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeterministicIdTest {

    @Test
    void same_table_and_rowid_produces_identical_uuid_across_calls() {
        UUID first = DeterministicId.forRow("co_block", 12345L);
        UUID second = DeterministicId.forRow("co_block", 12345L);
        // Re-import dedup via ReplacingMergeTree depends on this being
        // bit-for-bit stable. If this regresses, every re-run of the
        // importer will double-count rows.
        assertThat(first).isEqualTo(second);
    }

    @Test
    void different_rowids_produce_different_uuids() {
        UUID a = DeterministicId.forRow("co_block", 1L);
        UUID b = DeterministicId.forRow("co_block", 2L);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void different_tables_with_same_rowid_produce_different_uuids() {
        UUID block = DeterministicId.forRow("co_block", 1L);
        UUID session = DeterministicId.forRow("co_session", 1L);
        // Each CoreProtect table has its own rowid space, so we
        // disambiguate by including the table name in the UUID input.
        assertThat(block).isNotEqualTo(session);
    }

    @Test
    void uuids_have_v3_version_and_rfc_4122_variant() {
        UUID uuid = DeterministicId.forRow("co_block", 999L);
        assertThat(uuid.version()).isEqualTo(3);
        assertThat(uuid.variant()).isEqualTo(2); // RFC 4122 variant
    }

    @Test
    void no_collisions_across_a_million_rowids() {
        // Birthday-bound collision sanity check. v3 over 1M items
        // should virtually never collide; if it does, the namespace
        // bits are wrong.
        Set<UUID> seen = new HashSet<>(1_000_000);
        for (long i = 0; i < 1_000_000; i++) {
            seen.add(DeterministicId.forRow("co_block", i));
        }
        assertThat(seen).hasSize(1_000_000);
    }
}

package net.medievalrp.spyglass.platform;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.crusalis.platform.sdk.api.EventSlot;
import net.crusalis.platform.sdk.api.JsonWriter;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.CommandRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bukkit.Material;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The §6.1 acceptance criterion: <b>≤ 1 µs mean and 0 B/op steady state on the calling
 * thread, per action.</b>
 *
 * <p>What is measured is this module's own work — mapping a Spyglass record into a slot
 * — and not the SDK's publish, which has its own benchmarks and budgets in the Crusalis
 * repository. The slot here writes into a reused byte array and allocates nothing, so a
 * non-zero {@code gc.alloc.rate.norm} in these results is this module's allocation and
 * nobody else's. That is the number the criterion is about: the mapping runs on the
 * server's main thread, once per logged action, thousands of times a second on a war
 * Saturday.
 *
 * <pre>
 * ./gradlew :spyglass-platform:jmh
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 4)
@Fork(value = 1, jvmArgsAppend = {"-XX:+UseZGC", "-Xms512m", "-Xmx512m"})
@State(Scope.Thread)
public class MappingBenchmark {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final BlockLocation AT =
            new BlockLocation(UUID.randomUUID(), "world", 100, 64, -200);
    private static final Instant WHEN = Instant.parse("2026-10-20T12:00:00Z");

    private PlatformSettings settings;
    private ArraySlot slot;
    private EventRecord blockBreak;
    private EventRecord chat;
    private EventRecord command;
    private EventRecord withdraw;
    private EventRecord chatWithoutExtensions;

    @Setup(Level.Trial)
    public void setUp() {
        settings = new PlatformSettings(true, Set.of("*"), Set.of(),
                Set.of("login", "register"), Set.of("msg", "tell"), true);
        slot = new ArraySlot();

        Source source = Source.player(PLAYER, "TheOperator");
        blockBreak = new BlockBreakRecord(UUID.randomUUID(), "break", WHEN, WHEN,
                Origin.player(), source, AT, "srv:bench", "minecraft:stone",
                new BlockSnapshot(Material.STONE, "minecraft:stone", List.of(), List.of(),
                        List.of(), List.of(), null, List.of(), true),
                new BlockSnapshot(Material.AIR, "minecraft:air", List.of(), List.of(),
                        List.of(), List.of(), null, List.of(), true));
        chat = new ChatRecord(UUID.randomUUID(), "say", WHEN, WHEN, Origin.player(), source,
                AT, "srv:bench", "", "meet me at the north gate in five",
                List.of(UUID.randomUUID(), UUID.randomUUID()), Map.of("channel", "town"));
        chatWithoutExtensions = new ChatRecord(UUID.randomUUID(), "say", WHEN, WHEN,
                Origin.player(), source, AT, "srv:bench", "",
                "meet me at the north gate in five", List.of(), Map.of());
        command = new CommandRecord(UUID.randomUUID(), "command", WHEN, WHEN, Origin.player(),
                source, AT, "srv:bench", "", "/login hunter2");
        withdraw = new ContainerWithdrawRecord(UUID.randomUUID(), "withdraw", WHEN, WHEN,
                Origin.player(), source, AT, "srv:bench", "minecraft:diamond",
                "minecraft:chest", 3, 17,
                new StoredItem(3, "minecraft:diamond", null, "Shiny", List.of("lore"),
                        List.of("minecraft:sharpness:5"), null), null);
    }

    /** The highest-volume action on the network, and the one the budget is about. */
    @Benchmark
    public void mapBlockBreak() {
        slot.reset();
        RecordMapper.write(blockBreak, slot, settings);
    }

    /** A string payload: the escaping path. */
    @Benchmark
    public void mapChat() {
        slot.reset();
        RecordMapper.write(chat, slot, settings);
    }

    /**
     * The same action without the integrator's extension map, to attribute that map's
     * iteration cost rather than guess at it.
     */
    @Benchmark
    public void mapChatWithoutExtensions() {
        slot.reset();
        RecordMapper.write(chatWithoutExtensions, slot, settings);
    }

    /** Redaction: the one action that inspects its own text before writing it. */
    @Benchmark
    public void mapRedactedCommand() {
        slot.reset();
        RecordMapper.write(command, slot, settings);
    }

    /** Nested objects and an item summary. */
    @Benchmark
    public void mapContainerWithdraw() {
        slot.reset();
        RecordMapper.write(withdraw, slot, settings);
    }

    /** A slot that writes into one reused array and allocates nothing per event. */
    private static final class ArraySlot implements EventSlot, JsonWriter {

        private final byte[] buffer = new byte[4096];
        private int length;

        void reset() {
            length = 0;
        }

        private void put(char c) {
            if (length < buffer.length) {
                buffer[length++] = (byte) c;
            }
        }

        private void put(CharSequence value) {
            for (int i = 0; i < value.length(); i++) {
                put(value.charAt(i));
            }
        }

        private void putLong(long value) {
            if (value < 0) {
                put('-');
                value = -value;
            }
            if (value >= 10) {
                putLong(value / 10);
            }
            put((char) ('0' + (value % 10)));
        }

        @Override
        public EventSlot uuid(UUID uuid) {
            return this;
        }

        @Override
        public EventSlot uuid(long high, long low) {
            return this;
        }

        @Override
        public EventSlot crusalisId(CharSequence value) {
            return this;
        }

        @Override
        public EventSlot actorCid(CharSequence value) {
            return this;
        }

        @Override
        public EventSlot correlationId(CharSequence value) {
            return this;
        }

        @Override
        public JsonWriter payload() {
            return this;
        }

        @Override
        public void close() {
        }

        @Override
        public JsonWriter str(String key, CharSequence value) {
            if (value == null) {
                return this;
            }
            put(key);
            put(value);
            return this;
        }

        @Override
        public JsonWriter i32(String key, int value) {
            put(key);
            putLong(value);
            return this;
        }

        @Override
        public JsonWriter i64(String key, long value) {
            put(key);
            putLong(value);
            return this;
        }

        @Override
        public JsonWriter f64(String key, double value) {
            put(key);
            // Not Double.toString: it allocates, and this slot exists to allocate
            // nothing so the benchmark attributes allocation to the mapper.
            putLong((long) value);
            return this;
        }

        @Override
        public JsonWriter bool(String key, boolean value) {
            put(key);
            put(value ? 't' : 'f');
            return this;
        }

        @Override
        public JsonWriter nul(String key) {
            put(key);
            return this;
        }

        @Override
        public JsonWriter raw(String key, CharSequence json) {
            put(key);
            put(json);
            return this;
        }

        @Override
        public JsonWriter object(String key) {
            put(key);
            put('{');
            return this;
        }

        @Override
        public JsonWriter endObject() {
            put('}');
            return this;
        }

        @Override
        public JsonWriter array(String key) {
            put(key);
            put('[');
            return this;
        }

        @Override
        public JsonWriter endArray() {
            put(']');
            return this;
        }

        @Override
        public JsonWriter item(CharSequence value) {
            put(value);
            return this;
        }

        @Override
        public JsonWriter item(long value) {
            putLong(value);
            return this;
        }

        @Override
        public JsonWriter itemObject() {
            put('{');
            return this;
        }

        @Override
        public JsonWriter any(String key, Object value) {
            return value == null ? nul(key) : str(key, String.valueOf(value));
        }

        @Override
        public boolean overflowed() {
            return length >= buffer.length;
        }
    }
}

package net.medievalrp.spyglass.platform;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.crusalis.platform.sdk.api.Event;
import net.crusalis.platform.sdk.api.EventSlot;
import net.crusalis.platform.sdk.api.EventType;
import net.crusalis.platform.sdk.api.JsonWriter;
import net.crusalis.platform.sdk.api.Platform;
import net.crusalis.platform.sdk.api.Priority;
import net.crusalis.platform.sdk.api.SdkStats;

/**
 * A {@link Platform} that records what was published, as the JSON the real writer would
 * produce.
 *
 * <p>Assembled with a {@link StringBuilder} rather than the SDK's own writer because
 * the SDK's implementation is not on this module's classpath — it is a plugin jar — and
 * because a test that asserted against the real writer would be testing the writer.
 * What this has to be faithful about is the <em>shape</em>: key order, nesting, and
 * which fields a mapper wrote at all.
 */
final class FakePlatform implements Platform {

    final List<Published> published = new ArrayList<>();

    /** Types whose shouldPublish() returns false, as a disabled flag would. */
    final List<String> disabled = new ArrayList<>();

    /** When true, claim() returns null, as a full ring does. */
    boolean ringFull;

    /** When set, claim() throws it, standing in for a version skew with the SDK. */
    RuntimeException claimFailure;

    record Published(String type, UUID uuid, String json) {
    }

    @Override
    public String env() {
        return "dev";
    }

    @Override
    public String serverId() {
        return "test-1";
    }

    @Override
    public void publish(String subject, Object payload) {
        throw new UnsupportedOperationException("the integration uses the hot path only");
    }

    @Override
    public EventType type(String name) {
        return new EventType() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Priority priority() {
                return Priority.BULK;
            }

            @Override
            public boolean shouldPublish() {
                return !disabled.contains(name);
            }
        };
    }

    @Override
    public boolean publish(EventType type, Event event) {
        throw new UnsupportedOperationException("the integration uses the hot path only");
    }

    @Override
    public EventSlot claim(EventType type) {
        if (claimFailure != null) {
            throw claimFailure;
        }
        if (ringFull) {
            return null;
        }
        return new RecordingSlot(type.name());
    }

    @Override
    public SdkStats stats() {
        return null;
    }

    /** Every string value any slot has been given, for the IP assertion. */
    List<String> allStrings() {
        List<String> values = new ArrayList<>();
        for (Published p : published) {
            values.add(p.json());
        }
        return values;
    }

    private final class RecordingSlot implements EventSlot {

        private final String type;
        private final StringBuilder json = new StringBuilder("{");
        private final JsonWriter writer = new StringWriter(json);
        private UUID uuid;

        RecordingSlot(String type) {
            this.type = type;
        }

        @Override
        public EventSlot uuid(UUID value) {
            this.uuid = value;
            return this;
        }

        @Override
        public EventSlot uuid(long high, long low) {
            this.uuid = new UUID(high, low);
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
            return writer;
        }

        @Override
        public void close() {
            json.append('}');
            published.add(new Published(type, uuid, json.toString()));
        }
    }

    /** The smallest JSON writer that can hold the mapper to its contract. */
    private static final class StringWriter implements JsonWriter {

        private final StringBuilder out;
        private boolean first = true;

        StringWriter(StringBuilder out) {
            this.out = out;
        }

        private void key(String key) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(key).append("\":");
        }

        @Override
        public JsonWriter str(String key, CharSequence value) {
            if (value == null) {
                return this;
            }
            key(key);
            out.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"' || c == '\\') {
                    out.append('\\').append(c);
                } else {
                    out.append(c);
                }
            }
            out.append('"');
            return this;
        }

        @Override
        public JsonWriter i32(String key, int value) {
            key(key);
            out.append(value);
            return this;
        }

        @Override
        public JsonWriter i64(String key, long value) {
            key(key);
            out.append(value);
            return this;
        }

        @Override
        public JsonWriter f64(String key, double value) {
            key(key);
            out.append(Double.isFinite(value) ? String.valueOf(value) : "null");
            return this;
        }

        @Override
        public JsonWriter bool(String key, boolean value) {
            key(key);
            out.append(value);
            return this;
        }

        @Override
        public JsonWriter nul(String key) {
            key(key);
            out.append("null");
            return this;
        }

        @Override
        public JsonWriter raw(String key, CharSequence json) {
            key(key);
            out.append(json);
            return this;
        }

        @Override
        public JsonWriter object(String key) {
            key(key);
            out.append('{');
            first = true;
            return this;
        }

        @Override
        public JsonWriter endObject() {
            out.append('}');
            first = false;
            return this;
        }

        @Override
        public JsonWriter array(String key) {
            key(key);
            out.append('[');
            first = true;
            return this;
        }

        @Override
        public JsonWriter endArray() {
            out.append(']');
            first = false;
            return this;
        }

        @Override
        public JsonWriter item(CharSequence value) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(value).append('"');
            return this;
        }

        @Override
        public JsonWriter item(long value) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(value);
            return this;
        }

        @Override
        public JsonWriter itemObject() {
            if (!first) {
                out.append(',');
            }
            first = true;
            out.append('{');
            return this;
        }

        @Override
        public JsonWriter any(String key, Object value) {
            return value == null ? nul(key) : str(key, String.valueOf(value));
        }

        @Override
        public boolean overflowed() {
            return out.length() > 2048;
        }

        @Override
        public String toString() {
            return new String(out.toString().getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
        }
    }
}

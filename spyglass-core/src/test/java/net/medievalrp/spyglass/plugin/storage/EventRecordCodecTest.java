package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.MongoClientSettings;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.BsonString;
import org.bson.UuidRepresentation;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.UuidCodecProvider;
import org.bson.codecs.configuration.CodecConfigurationException;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.jsr310.Jsr310CodecProvider;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.codecs.record.RecordCodecProvider;
import org.junit.jupiter.api.Test;

/**
 * Schema-evolution contract tests for {@link EventRecordCodec}.
 *
 * <p>The on-disk document shape is a migration hazard: we can't force a
 * reseed in production. Anything the plugin ever wrote has to round-trip
 * back into an {@link EventRecord} subtype on the current build. This
 * suite exercises every path the codec's legacy-fallback branch is
 * supposed to cover so a refactor can't silently strand old data.
 *
 * <p>The tests operate on in-memory {@link BsonDocument}s via
 * {@link BsonDocumentWriter} / {@link BsonDocumentReader} and reuse the
 * same codec registry {@code MongoRecordStore} builds at runtime
 * (Jsr310 + Record + our polymorphic codecs + automatic POJO). No Mongo
 * container required.
 */
class EventRecordCodecTest {

    // Matches MongoRecordStore's own registry, but with an explicit
    // UuidCodecProvider(STANDARD) in front so BsonDocumentWriter /
    // BsonDocumentReader (which don't receive a uuidRepresentation via
    // MongoClientSettings like the real driver does) can still encode
    // the UUID id/source.playerId components.
    private static final CodecRegistry REGISTRY = CodecRegistries.fromRegistries(
            CodecRegistries.fromProviders(new UuidCodecProvider(UuidRepresentation.STANDARD)),
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(
                    new Jsr310CodecProvider(),
                    new RecordCodecProvider(),
                    EventRecordCodec.provider(),
                    RollbackEffectCodec.provider(),
                    PojoCodecProvider.builder().automatic(true).build()));

    private static final Codec<EventRecord> CODEC = REGISTRY.get(EventRecord.class);

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final Instant WHEN = Instant.parse("2026-04-23T12:00:00Z");

    private static BlockBreakRecord sampleBreak() {
        BlockSnapshot stone = new BlockSnapshot(
                org.bukkit.Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        return new BlockBreakRecord(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "break", WHEN, WHEN.plusSeconds(3600),
                Origin.player(), Source.player(ALICE, "Alice"),
                new BlockLocation(WORLD, "world", 10, 64, 20),
                "test", "STONE", stone, air);
    }

    private static BsonDocument encode(EventRecord record) {
        BsonDocument doc = new BsonDocument();
        try (BsonDocumentWriter writer = new BsonDocumentWriter(doc)) {
            CODEC.encode(writer, record, EncoderContext.builder().build());
        }
        return doc;
    }

    private static EventRecord decode(BsonDocument doc) {
        try (BsonDocumentReader reader = new BsonDocumentReader(doc)) {
            return CODEC.decode(reader, DecoderContext.builder().build());
        }
    }

    @Test
    void roundTripsCurrentFormatRecord() {
        BlockBreakRecord original = sampleBreak();

        EventRecord decoded = decode(encode(original));

        assertThat(decoded).isInstanceOf(BlockBreakRecord.class).isEqualTo(original);
    }

    @Test
    void encodeDoesNotWriteLegacyClassField() {
        BsonDocument doc = encode(sampleBreak());

        // The _class discriminator is the allocation we deleted in the
        // tail-latency fix. A regression would silently reintroduce the
        // per-record BsonDocument detour, so assert on the field directly.
        assertThat(doc.containsKey("_class"))
                .as("modern encode must not write _class discriminator")
                .isFalse();
        // event stays authoritative: it's what decode dispatches on.
        assertThat(doc.getString("event").getValue()).isEqualTo("break");
    }

    @Test
    void decodesDocumentWithBothEventAndLegacyClassField() {
        // A rare shape: an old writer stamped both fields. Current decode
        // must still work — event wins, _class is a harmless extra that
        // the concrete record codec will skip.
        BsonDocument doc = encode(sampleBreak());
        doc.put("_class", new BsonString("BlockBreakRecord"));

        EventRecord decoded = decode(doc);

        assertThat(decoded).isInstanceOf(BlockBreakRecord.class);
        assertThat(((BlockBreakRecord) decoded).target()).isEqualTo("STONE");
    }

    @Test
    void eventDiscriminatorTakesPrecedenceOverStaleLegacyClass() {
        // event=break + _class=ChatRecord. A type-smeared doc like this
        // shouldn't exist in practice, but if a bad migration wrote one
        // we must not silently decode it as ChatRecord. event wins,
        // stale _class is ignored.
        BsonDocument doc = encode(sampleBreak());
        doc.put("_class", new BsonString("ChatRecord"));

        EventRecord decoded = decode(doc);

        assertThat(decoded).isInstanceOf(BlockBreakRecord.class);
    }

    @Test
    void decodesLegacyDocumentWithOnlyLegacyClassField() {
        // Pre-EventCatalog shape: a doc where the event dispatch field
        // doesn't exist at all (or is stored under a key the scanner
        // doesn't know). The fallback scans BY_SIMPLE_NAME via _class.
        //
        // We simulate this by encoding a modern doc and then renaming
        // the event key to something the scanner can't see. After the
        // rename, the only dispatch signal left is _class.
        BsonDocument doc = encode(sampleBreak());
        doc.remove("event");
        doc.put("_class", new BsonString("BlockBreakRecord"));

        EventRecord decoded = decode(doc);

        assertThat(decoded).isInstanceOf(BlockBreakRecord.class);
        // The inner RecordCodec fills missing components with null; we
        // only assert the discriminator path picked the right subtype.
        assertThat(((BlockBreakRecord) decoded).target()).isEqualTo("STONE");
    }

    @Test
    void decodesRegardlessOfFieldOrderInDocument() {
        // LinkedHashMap preserves insertion order in BSON docs; reorder
        // so _class appears before event. Scanner must still find and
        // prefer event even when it's not the first key.
        BsonDocument encoded = encode(sampleBreak());
        BsonDocument reordered = new BsonDocument();
        reordered.put("_class", new BsonString("BlockBreakRecord"));
        reordered.put("some_unknown_extra", new BsonString("ignored"));
        encoded.forEach(reordered::put);

        EventRecord decoded = decode(reordered);

        assertThat(decoded).isInstanceOf(BlockBreakRecord.class);
    }

    @Test
    void ignoresUnknownExtraFieldsInDocument() {
        // Forward compat: an older cluster running an older schema might
        // have extra fields the current build doesn't know about (a
        // deleted feature, a v1 holdover, debug crumbs). Decode must
        // tolerate them without crashing — the scanner skipValue()s
        // unknown fields and the RecordCodec ignores unknown inputs.
        BsonDocument doc = encode(sampleBreak());
        doc.put("deprecatedField1", new BsonString("junk"));
        doc.put("deprecatedField2", new org.bson.BsonInt32(42));
        doc.put("deprecatedNested", new BsonDocument()
                .append("x", new BsonString("y")));

        EventRecord decoded = decode(doc);

        assertThat(decoded).isInstanceOf(BlockBreakRecord.class).isEqualTo(sampleBreak());
    }

    @Test
    void rejectsDocumentWithUnknownEventName() {
        // event field is present but names an event the catalog doesn't
        // know (typo, renamed event, plugin downgrade). No _class to
        // fall back to. Must fail loudly instead of silently dropping.
        BsonDocument doc = encode(sampleBreak());
        doc.put("event", new BsonString("definitely-not-a-real-event"));
        doc.remove("_class");

        assertThatThrownBy(() -> decode(doc))
                .isInstanceOf(CodecConfigurationException.class)
                .hasMessageContaining("definitely-not-a-real-event");
    }

    @Test
    void rejectsDocumentWithBothDiscriminatorsUnknown() {
        // Scanner contract: `_class` is only captured if seen before the
        // `event` field during the stream scan, because the scanner
        // short-circuits as soon as it finds an `event`. To exercise
        // the both-unknown branch we rebuild the document with `_class`
        // placed first, then append the original fields (with event
        // overridden).
        BsonDocument encoded = encode(sampleBreak());
        BsonDocument ordered = new BsonDocument();
        ordered.put("_class", new BsonString("AlsoBogus"));
        encoded.forEach((k, v) -> {
            if ("event".equals(k)) {
                ordered.put("event", new BsonString("bogus"));
            } else {
                ordered.put(k, v);
            }
        });

        assertThatThrownBy(() -> decode(ordered))
                .isInstanceOf(CodecConfigurationException.class)
                .hasMessageContaining("bogus")
                .hasMessageContaining("AlsoBogus");
    }

    @Test
    void eventScannerShortCircuitsWhenEventPrecedesLegacyClass() {
        // Scanner contract (the flip side): when `event` appears first,
        // the loop breaks before `_class` is reached, so a trailing
        // `_class` is never read. An unknown event must fail fast
        // instead of silently rescuing via a stale `_class`.
        //
        // The encoded document has event at record-component position
        // (position 3), with any trailing key we append coming after.
        BsonDocument doc = encode(sampleBreak());
        doc.put("event", new BsonString("bogus"));
        doc.put("_class", new BsonString("BlockBreakRecord")); // would resolve, but never read

        assertThatThrownBy(() -> decode(doc))
                .isInstanceOf(CodecConfigurationException.class)
                .hasMessageContaining("event=bogus")
                .hasMessageContaining("_class=null"); // scanner never saw it
    }

    @Test
    void rejectsDocumentMissingBothDiscriminators() {
        // Neither event nor _class — a truly alien document shape. The
        // scanner exhausts the doc without finding a dispatch target.
        BsonDocument doc = encode(sampleBreak());
        doc.remove("event");
        // _class was never written on the modern encode path; explicit
        // remove defends against a future encode regression that might
        // reintroduce it.
        doc.remove("_class");

        assertThatThrownBy(() -> decode(doc))
                .isInstanceOf(CodecConfigurationException.class)
                .hasMessageContaining("event=null")
                .hasMessageContaining("_class=null");
    }

    @Test
    void roundTripsEveryEventAlias() {
        // The catalog maps many event strings onto the same record class
        // (e.g. both "break" and "decay" store as BlockBreakRecord).
        // Decode has to honor the stored event string exactly — no
        // silent normalization to a canonical alias.
        BlockSnapshot stone = new BlockSnapshot(
                org.bukkit.Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockLocation loc = new BlockLocation(WORLD, "world", 0, 64, 0);

        for (String alias : List.of("break", "decay", "brush", "vault")) {
            BlockBreakRecord original = new BlockBreakRecord(
                    UUID.randomUUID(), alias, WHEN, WHEN.plusSeconds(3600),
                    Origin.player(), Source.player(ALICE, "Alice"),
                    loc, "test", "STONE", stone, air);

            EventRecord decoded = decode(encode(original));

            assertThat(decoded).isInstanceOf(BlockBreakRecord.class);
            assertThat(((BlockBreakRecord) decoded).event()).isEqualTo(alias);
        }
    }

    @Test
    void roundTripsItemRecordWithNullDataProjection() {
        // #103: pickups/drops store only the searchable projection — the
        // base64 NBT blob (StoredItem.data) is null. The Mongo write path
        // must encode a null record component and read it back without
        // throwing, and null must stay null on the way out.
        StoredItem projection = new StoredItem(
                0, "DIAMOND_SWORD", null,
                "Excaliblur",
                List.of("Forged in fire"),
                List.of("sharpness=5"));
        ItemPickupRecord original = new ItemPickupRecord(
                UUID.randomUUID(), "pickup", WHEN, WHEN.plusSeconds(3600),
                Origin.player(), Source.player(ALICE, "Alice"),
                new BlockLocation(WORLD, "world", 1, 64, 2),
                "test", "DIAMOND_SWORD", 1, projection);

        EventRecord decoded = decode(encode(original));

        assertThat(decoded).isInstanceOf(ItemPickupRecord.class).isEqualTo(original);
        StoredItem item = ((ItemPickupRecord) decoded).item();
        assertThat(item.data()).as("null blob must stay null through the codec").isNull();
        assertThat(item.material()).isEqualTo("DIAMOND_SWORD");
        assertThat(item.name()).isEqualTo("Excaliblur");
        assertThat(item.lore()).containsExactly("Forged in fire");
        assertThat(item.enchants()).containsExactly("sharpness=5");
    }

    @Test
    void roundTripsAcrossDistinctRecordTypes() {
        // Belt-and-braces: encode records of different concrete types in
        // sequence and make sure the dispatch picks the right subtype
        // for each. Catches any accidental state leak between decodes
        // (cached last-type, etc).
        ChatRecord chat = new ChatRecord(
                UUID.randomUUID(), "say", WHEN, WHEN.plusSeconds(3600),
                Origin.player(), Source.player(ALICE, "Alice"),
                new BlockLocation(WORLD, "world", 5, 64, 5),
                "test", "Alice", "hello world", List.of(), java.util.Map.of("channel", "#OOC"));
        BlockBreakRecord brk = sampleBreak();

        assertThat(decode(encode(chat))).isInstanceOf(ChatRecord.class).isEqualTo(chat);
        assertThat(decode(encode(brk))).isInstanceOf(BlockBreakRecord.class).isEqualTo(brk);
        assertThat(decode(encode(chat))).isInstanceOf(ChatRecord.class).isEqualTo(chat);
    }
}

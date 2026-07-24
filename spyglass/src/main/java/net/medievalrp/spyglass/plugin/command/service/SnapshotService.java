package net.medievalrp.spyglass.plugin.command.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.command.render.Feedback;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.snapshot.PlayerSnapshot;
import net.medievalrp.spyglass.plugin.snapshot.PlayerSnapshotStore;
import net.medievalrp.spyglass.plugin.snapshot.Reconstruction;
import net.medievalrp.spyglass.plugin.snapshot.SnapshotReconstructor;
import net.medievalrp.spyglass.plugin.snapshot.SnapshotSession;
import net.medievalrp.spyglass.plugin.snapshot.SnapshotSessions;
import net.medievalrp.spyglass.plugin.snapshot.SnapshotSlot;
import net.medievalrp.spyglass.plugin.snapshot.SnapshotTakes;
import net.medievalrp.spyglass.plugin.snapshot.SnapshotView;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Campfire;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Furnace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Backs {@code /sg snapshot} (#341): view a player inventory or container
 * as of a past instant, and take copies out of what is shown.
 *
 * <p>Two modes, switched on whether {@code p:} is present:
 *
 * <ul>
 *   <li><b>Player mode</b> ({@code p:<name>}) reads the subject's newest
 *       {@code PlayerSnapshot} at or before T straight from the
 *       {@link PlayerSnapshotStore} - always {@link SnapshotSession.Certainty#CERTAIN},
 *       since a capture is exact (only {@code capturedAt} tells the
 *       operator how close to T it actually landed).</li>
 *   <li><b>Container mode</b> (no {@code p:}) resolves a target block -
 *       {@code trg:x,y,z} or the block the sending player is looking at -
 *       and reconstructs it via {@link SnapshotReconstructor} against the
 *       deposit/withdraw/shulker/crafter log in {@code [T, now]}.</li>
 * </ul>
 *
 * <p>This class does not open a GUI itself; it hands a resolved
 * {@link SnapshotSession} to the injected {@link SnapshotView} when one is
 * present and the sender is a player, and falls back to a text listing
 * otherwise (26.x, console/RCON, or no GUI wired) - the same split
 * {@code SalvageService} draws for {@code /sg inventory}.
 *
 * <p>Every store/query call and every {@link Block#getState()} /
 * {@link org.bukkit.inventory.Inventory} read is thread-sensitive: parsing,
 * target resolution, and live-content cloning run on the calling (main)
 * thread; the store reads, the recorder flush, and the reconstruction
 * itself run via {@link ServiceSupport#onAsyncThread}; the session is
 * built and handed to the view back on {@link ServiceSupport#onMainThread}.
 */
@ApiStatus.Internal
public final class SnapshotService {

    /** Reach for the look-at fallback when {@code trg:} is omitted - matches
     *  the inspection wand's short-range use (a player standing at a
     *  container, not sniping one across a room). */
    private static final int LOOK_REACH = 6;

    /** Defensive cap on how many container-family records one snapshot
     *  query pulls back. A single container's deposit/withdraw history
     *  inside one snapshot window is not expected to approach this; it
     *  exists to bound a pathological case (a hopper farm's output chest)
     *  rather than to reflect a real limit an operator would hit. */
    private static final int MAX_CONTAINER_RECORDS = 50_000;

    /** Slot bound used when the live block at {@code trg:} is no longer a
     *  container at all (destroyed or replaced since T): big enough to
     *  cover a double chest's worth of slots so a legitimate record slot
     *  is never dropped as out-of-range just because the original size is
     *  unknown. */
    private static final int MAX_UNKNOWN_SIZE = 54;

    private static final int PLAYER_CONTAINER_ROWS = 6;

    /** Container-family events {@link SnapshotReconstructor} can reason
     *  about - the deposit/withdraw log, plus its shulker and crafter
     *  aliases (same event names {@code ContainerTransactionListener} and
     *  friends emit; see {@code EventCatalog}). */
    private static final List<String> CONTAINER_EVENTS =
            List.of("deposit", "withdraw", "shulker-deposit", "shulker-withdraw", "crafter");

    private static final Pattern COORDS =
            Pattern.compile("^(-?\\d{1,8})\\s*,\\s*(-?\\d{1,8})\\s*,\\s*(-?\\d{1,8})$");

    private final PlayerSnapshotStore playerStore;
    private final RecordStore recordStore;
    private final Recorder recorder;
    private final Duration flushTimeout;
    private final ServiceSupport support;
    private final SnapshotSessions sessions;
    private final SnapshotTakes takes;
    @Nullable
    private final SnapshotView view;
    private final Logger logger;

    public SnapshotService(PlayerSnapshotStore playerStore,
                           RecordStore recordStore,
                           Recorder recorder,
                           SpyglassConfig config,
                           ServiceSupport support,
                           SnapshotSessions sessions,
                           SnapshotTakes takes,
                           @Nullable SnapshotView view,
                           Logger logger) {
        this.playerStore = playerStore;
        this.recordStore = recordStore;
        this.recorder = recorder;
        // Reused rather than adding a dedicated snapshot.* flush-timeout
        // knob: same "close the read-your-writes gap before a point-in-time
        // read" need the rollback path already solved (#205).
        this.flushTimeout = config.limits().rollbackFlushTimeout();
        this.support = support;
        this.sessions = sessions;
        this.takes = takes;
        this.view = view;
        this.logger = logger;
    }

    // ---- /sg snapshot <params> ------------------------------------------

    public void execute(CommandSender sender, String rawParams) {
        ParsedQuery parsed;
        try {
            parsed = parse(rawParams, Instant.now());
        } catch (ParamParseException ex) {
            sender.sendMessage(Feedback.error(ex.getMessage()));
            return;
        }
        if (parsed.playerMode()) {
            executePlayerMode(sender, parsed);
        } else {
            executeContainerMode(sender, parsed);
        }
    }

    // ---- /sg snapshot take <token> <slot> --------------------------------

    public void take(CommandSender sender, String rawToken, int slot) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Feedback.error("Take items in-game as a player."));
            return;
        }
        UUID token;
        try {
            token = UUID.fromString(rawToken.trim());
        } catch (IllegalArgumentException ex) {
            player.sendMessage(Feedback.error("That snapshot has expired - re-run /sg snapshot."));
            return;
        }
        Optional<SnapshotSession> session = sessions.resolve(player, token);
        if (session.isEmpty()) {
            player.sendMessage(Feedback.error("That snapshot has expired - re-run /sg snapshot."));
            return;
        }
        SnapshotTakes.Result result = takes.take(player, session.get(), slot);
        player.sendMessage(describeTake(result));
    }

    private static Component describeTake(SnapshotTakes.Result result) {
        return switch (result) {
            case TAKEN -> Feedback.success("Took a copy.");
            case INVENTORY_FULL -> Feedback.warn("That whole stack won't fit in your inventory - nothing taken.");
            case NO_PERMISSION -> Feedback.error("You don't have permission to take from snapshots.");
            case SLOT_EMPTY -> Feedback.error("Nothing to take in that slot.");
        };
    }

    // ---- player mode ------------------------------------------------------

    private void executePlayerMode(CommandSender sender, ParsedQuery parsed) {
        String name = parsed.playerName();
        UUID viaBukkit = resolveViaBukkit(name);
        support.onAsyncThread(() -> {
            try {
                UUID uuid = viaBukkit != null ? viaBukkit : recordStore.resolvePlayerId(name);
                if (uuid == null) {
                    support.onMainThread(() ->
                            sender.sendMessage(Feedback.error("Unknown player: " + name)));
                    return;
                }
                Optional<PlayerSnapshot> found = playerStore.latestAtOrBefore(uuid, parsed.asOf());
                support.onMainThread(() -> {
                    if (found.isEmpty()) {
                        sender.sendMessage(Feedback.error("No snapshot for " + name + " at or before "
                                + formatInstant(parsed.asOf()) + "."));
                        return;
                    }
                    PlayerSnapshot snapshot = found.get();
                    SnapshotSession session = new SnapshotSession(
                            UUID.randomUUID(), SnapshotSession.Kind.PLAYER, snapshot.playerName(),
                            parsed.asOf(), snapshot.capturedAt(), snapshot.cause(),
                            SnapshotSession.Certainty.CERTAIN, List.of(), PLAYER_CONTAINER_ROWS,
                            snapshot.slots());
                    sessions.store(sender, session);
                    openOrList(sender, session);
                });
            } catch (RuntimeException ex) {
                logger.warning("Spyglass snapshot player lookup failed for " + name + ": " + ex.getMessage());
                support.onMainThread(() ->
                        sender.sendMessage(Feedback.error("Snapshot lookup failed - see the console.")));
            }
        });
    }

    /**
     * Bukkit-cache-first resolution, deliberately the same shape as
     * {@code PlayerParam.resolveViaBukkit}: an online exact match, then the
     * local offline-player cache. NEVER {@code Bukkit.getOfflinePlayer(String)}
     * - that overload does a blocking Mojang HTTP round-trip on a cache
     * miss, and this runs on the command (main) thread.
     */
    private static @Nullable UUID resolveViaBukkit(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        var cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    // ---- container mode -----------------------------------------------

    private void executeContainerMode(CommandSender sender, ParsedQuery parsed) {
        ResolvedContainer target = resolveContainerTarget(sender, parsed);
        if (target == null) {
            return; // resolveContainerTarget already sent the error
        }
        Instant t = parsed.asOf();
        support.onAsyncThread(() -> {
            try {
                recorder.flush(flushTimeout);
                recordStore.flushPendingWrites();
                Reconstruction result = reconstructTarget(target, t);
                support.onMainThread(() -> {
                    SnapshotSession session = buildContainerSession(target, t, result);
                    sessions.store(sender, session);
                    openOrList(sender, session);
                });
            } catch (RuntimeException ex) {
                logger.warning("Spyglass snapshot container reconstruction failed at "
                        + target.label() + ": " + ex.getMessage());
                support.onMainThread(() ->
                        sender.sendMessage(Feedback.error("Snapshot reconstruction failed - see the console.")));
            }
        });
    }

    /** MAIN THREAD: resolve the target block and clone its live contents. */
    private @Nullable ResolvedContainer resolveContainerTarget(CommandSender sender, ParsedQuery parsed) {
        Block block;
        boolean viaTrg = parsed.hasTrg();
        if (viaTrg) {
            World world = resolveTrgWorld(sender, parsed.worldName());
            if (world == null) {
                return null; // error already sent
            }
            block = world.getBlockAt(parsed.x(), parsed.y(), parsed.z());
        } else {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Feedback.error(
                        "Container snapshot needs a player (to look at a block), or trg:x,y,z."));
                return null;
            }
            Block targeted = player.getTargetBlockExact(LOOK_REACH);
            if (targeted == null) {
                player.sendMessage(Feedback.error("No block in reach - look at a container, or use trg:x,y,z."));
                return null;
            }
            block = targeted;
        }
        return describeTarget(sender, block, viaTrg);
    }

    private @Nullable World resolveTrgWorld(CommandSender sender, @Nullable String worldName) {
        if (worldName != null) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                sender.sendMessage(Feedback.error("Unknown world: " + worldName));
            }
            return world;
        }
        if (sender instanceof Player player) {
            return player.getWorld();
        }
        sender.sendMessage(Feedback.error("trg: needs w: when run from the console."));
        return null;
    }

    /** MAIN THREAD: identify what kind of container (if any) stands at
     *  {@code block} and clone its live contents. {@code allowMissing} is
     *  true only for the explicit {@code trg:} path, where the operator is
     *  asserting a container used to be here even if it is gone now. */
    private @Nullable ResolvedContainer describeTarget(CommandSender sender, Block block, boolean allowMissing) {
        BlockState state = block.getState();
        boolean selfMutating = state instanceof Furnace
                || state instanceof BrewingStand
                || state instanceof Campfire;
        String label = block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ()
                + " " + state.getType().name();

        if (state instanceof Chest chest) {
            InventoryHolder topHolder = chest.getInventory().getHolder();
            if (topHolder instanceof DoubleChest doubleChest
                    && doubleChest.getLeftSide() instanceof Chest left
                    && doubleChest.getRightSide() instanceof Chest right) {
                Half leftHalf = buildHalf(left.getBlock(), left.getBlockInventory(), left.getBlock().getType().name());
                Half rightHalf = buildHalf(right.getBlock(), right.getBlockInventory(), right.getBlock().getType().name());
                return new ResolvedContainer(leftHalf, rightHalf, selfMutating, label + " (double)");
            }
            return new ResolvedContainer(buildHalf(block, chest.getBlockInventory(), state.getType().name()),
                    null, selfMutating, label);
        }
        if (state instanceof Container container) {
            return new ResolvedContainer(buildHalf(block, container.getInventory(), state.getType().name()),
                    null, selfMutating, label);
        }
        if (state instanceof Campfire campfire) {
            int size = campfire.getSize();
            ItemStack[] contents = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                contents[i] = campfire.getItem(i);
            }
            Half half = new Half(BlockLocations.fromBlock(block), state.getType().name(),
                    cloneContents(contents), size, true);
            return new ResolvedContainer(half, null, true, label);
        }
        if (allowMissing) {
            // trg: named an exact cell but nothing there is currently a
            // container - it may have been broken or replaced since T.
            // Reconstruct anyway from whatever the log shows, against an
            // empty base (#341 point 6): MAX_UNKNOWN_SIZE covers up to a
            // double chest's worth of slots since the original size is
            // unknown.
            Half half = new Half(BlockLocations.fromBlock(block), state.getType().name(),
                    new ItemStack[MAX_UNKNOWN_SIZE], MAX_UNKNOWN_SIZE, false);
            return new ResolvedContainer(half, null, false,
                    block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ()
                            + " (container no longer present)");
        }
        sender.sendMessage(Feedback.error("Not a container: " + state.getType().name()));
        return null;
    }

    private static Half buildHalf(Block block, Inventory inventory, String containerType) {
        return new Half(BlockLocations.fromBlock(block), containerType,
                cloneContents(inventory.getContents()), inventory.getSize(), true);
    }

    private static ItemStack[] cloneContents(ItemStack[] live) {
        ItemStack[] out = new ItemStack[live.length];
        for (int i = 0; i < live.length; i++) {
            out[i] = live[i] == null ? null : live[i].clone();
        }
        return out;
    }

    /** OFF MAIN: query + reconstruct one or both halves. */
    private Reconstruction reconstructTarget(ResolvedContainer target, Instant t) {
        Reconstruction primary = reconstructHalf(target.primary(), t, target.selfMutating());
        if (!target.doubleChest()) {
            return primary;
        }
        Reconstruction secondary = reconstructHalf(target.secondary(), t, target.selfMutating());
        return mergeHalves(primary, target.primary().size(), secondary);
    }

    private Reconstruction reconstructHalf(Half half, Instant t, boolean selfMutating) {
        QueryRequest request = buildRequest(half.location(), t);
        QueryResult queried = recordStore.query(request);
        StoredItem[] storedLive = new StoredItem[half.liveContents().length];
        for (int i = 0; i < storedLive.length; i++) {
            storedLive[i] = ItemSerialization.storedItem(i, half.liveContents()[i]);
        }
        return SnapshotReconstructor.reconstruct(queried.records(), storedLive, half.size(), t,
                half.containerPresent(), selfMutating);
    }

    private static QueryRequest buildRequest(BlockLocation location, Instant t) {
        List<QueryPredicate> predicates = List.of(
                new QueryPredicate.Eq("location.worldId", location.worldId()),
                new QueryPredicate.Range("location.x", location.x(), location.x()),
                new QueryPredicate.Range("location.y", location.y(), location.y()),
                new QueryPredicate.Range("location.z", location.z(), location.z()),
                new QueryPredicate.Range("occurred", t, Instant.now()),
                new QueryPredicate.In("event", CONTAINER_EVENTS));
        return new QueryRequest(predicates, Sort.NEWEST_FIRST, MAX_CONTAINER_RECORDS,
                EnumSet.noneOf(Flag.class), false);
    }

    /**
     * Merge two half-reconstructions into one view: the second half's
     * slots (and mismatch indices) are offset by {@code leftSize} - the
     * same 0-26 / 27-53 re-basing {@code ContainerTransactionListener}'s
     * {@code resolveSlotTarget} performs in reverse for a double chest's
     * combined inventory. Certainty is the worst of the two halves; notes
     * are kept, each prefixed with which half it came from since a bare
     * "slot 5" in a half's note means a local (pre-offset) slot index.
     *
     * <p>Package-private static and pure (no Bukkit types) so it is
     * directly unit-testable - extracted for exactly that reason.
     */
    static Reconstruction mergeHalves(Reconstruction left, int leftSize, Reconstruction right) {
        List<SnapshotSlot> slots = new ArrayList<>(left.slots().size() + right.slots().size());
        slots.addAll(left.slots());
        for (SnapshotSlot slot : right.slots()) {
            slots.add(new SnapshotSlot(slot.slot() + leftSize, slot.count(), slot.item()));
        }
        List<String> notes = new ArrayList<>(left.notes().size() + right.notes().size());
        for (String note : left.notes()) {
            notes.add("left half: " + note);
        }
        for (String note : right.notes()) {
            notes.add("right half: " + note);
        }
        List<Reconstruction.Mismatch> mismatches =
                new ArrayList<>(left.mismatches().size() + right.mismatches().size());
        mismatches.addAll(left.mismatches());
        for (Reconstruction.Mismatch mismatch : right.mismatches()) {
            mismatches.add(new Reconstruction.Mismatch(
                    mismatch.slot() + leftSize, mismatch.kind(), mismatch.expected(), mismatch.actual()));
        }
        SnapshotSession.Certainty certainty = (left.certain() && right.certain())
                ? SnapshotSession.Certainty.CERTAIN
                : SnapshotSession.Certainty.UNCERTAIN;
        return new Reconstruction(slots, certainty, notes, mismatches);
    }

    private static SnapshotSession buildContainerSession(ResolvedContainer target, Instant t, Reconstruction result) {
        int size = target.doubleChest()
                ? target.primary().size() + target.secondary().size()
                : target.primary().size();
        int rows = Math.max(1, (int) Math.ceil(size / 9.0));
        return new SnapshotSession(UUID.randomUUID(), SnapshotSession.Kind.CONTAINER, target.label(), t, t,
                null, result.certainty(), result.notes(), rows, result.slots());
    }

    private record Half(BlockLocation location, String containerType, ItemStack[] liveContents, int size,
                        boolean containerPresent) {
    }

    private record ResolvedContainer(Half primary, @Nullable Half secondary, boolean selfMutating, String label) {
        boolean doubleChest() {
            return secondary != null;
        }
    }

    // ---- opening the result ---------------------------------------------

    private void openOrList(CommandSender sender, SnapshotSession session) {
        if (view != null && sender instanceof Player player) {
            view.open(player, session);
            return;
        }
        renderListing(sender, session);
    }

    // ---- text listing ----------------------------------------------------

    private void renderListing(CommandSender sender, SnapshotSession session) {
        sender.sendMessage(header(session));
        for (String note : session.notes()) {
            sender.sendMessage(Feedback.bonus("- " + note));
        }
        if (session.slots().isEmpty()) {
            sender.sendMessage(Feedback.bonus("Nothing in it."));
            return;
        }
        boolean canTake = sender.hasPermission(SnapshotTakes.PERMISSION);
        for (SnapshotSlot slot : session.slots()) {
            sender.sendMessage(slotLine(session, slot, canTake));
        }
    }

    private static Component header(SnapshotSession session) {
        long elapsedSeconds = Math.max(0L,
                Instant.now().getEpochSecond() - session.asOf().getEpochSecond());
        String ago = new Duration(elapsedSeconds).compact();
        Component line = Feedback.info(session.subjectLabel() + " as of " + ago + " ago");
        if (session.certainty() == SnapshotSession.Certainty.UNCERTAIN) {
            line = line.append(Component.text(" (uncertain)", NamedTextColor.YELLOW));
        }
        return line;
    }

    private static Component slotLine(SnapshotSession session, SnapshotSlot slot, boolean canTake) {
        StoredItem item = slot.item();
        int count = displayCount(session, slot);
        var builder = Component.text()
                .append(Component.text("[" + slot.slot() + "] ", NamedTextColor.GRAY))
                .append(Component.text(item.material() + " x" + count, NamedTextColor.AQUA));
        if (item.name() != null && !item.name().isBlank()) {
            builder.hoverEvent(HoverEvent.showText(Component.text(item.name(), NamedTextColor.GRAY)));
        }
        if (canTake) {
            builder.append(Component.text(" [take]", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand(
                            "/spyglass snapshot take " + session.token() + " " + slot.slot()))
                    .hoverEvent(HoverEvent.showText(Component.text("Take a copy", NamedTextColor.GRAY))));
        }
        return builder.asComponent();
    }

    private static int displayCount(SnapshotSession session, SnapshotSlot slot) {
        if (session.kind() == SnapshotSession.Kind.PLAYER) {
            return slot.count();
        }
        // Container-mode slots leave the real amount inside the blob
        // (SnapshotReconstructor.COUNT_IN_BLOB) - decode to read it.
        ItemStack decoded = ItemSerialization.decode(slot.item().data());
        return decoded == null ? 0 : decoded.getAmount();
    }

    private static String formatInstant(Instant instant) {
        return instant.atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    // ---- parsing ----------------------------------------------------------

    /**
     * The restricted {@code /sg snapshot} key set: {@code t:} (required),
     * {@code p:}, {@code trg:}, {@code w:}. No default radius or time is
     * ever injected, and any other key or flag is rejected outright - this
     * is deliberately NOT {@code QueryStringParser}: that parser injects
     * search's defaults and accepts the full query language, neither of
     * which belongs on a point-in-time snapshot.
     */
    record ParsedQuery(Instant asOf, @Nullable String playerName, @Nullable Integer x, @Nullable Integer y,
                       @Nullable Integer z, @Nullable String worldName) {
        boolean playerMode() {
            return playerName != null;
        }

        boolean hasTrg() {
            return x != null;
        }
    }

    static ParsedQuery parse(String raw, Instant now) throws ParamParseException {
        Duration since = null;
        String playerName = null;
        Integer x = null;
        Integer y = null;
        Integer z = null;
        String worldName = null;

        if (raw != null && !raw.isBlank()) {
            for (String token : raw.trim().split("\\s+")) {
                if (token.isEmpty()) {
                    continue;
                }
                int colon = token.indexOf(':');
                if (colon < 0) {
                    throw new ParamParseException("not a snapshot key: " + token);
                }
                String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
                String value = token.substring(colon + 1);
                switch (key) {
                    case "t", "since" -> {
                        if (since != null) {
                            throw new ParamParseException("Duplicate parameter: " + key);
                        }
                        since = parseDuration(value);
                    }
                    case "p", "player" -> {
                        if (playerName != null) {
                            throw new ParamParseException("Duplicate parameter: " + key);
                        }
                        playerName = parsePlayerName(value);
                    }
                    case "trg", "target" -> {
                        if (x != null) {
                            throw new ParamParseException("Duplicate parameter: " + key);
                        }
                        int[] coords = parseCoords(value);
                        x = coords[0];
                        y = coords[1];
                        z = coords[2];
                    }
                    case "w", "world" -> {
                        if (worldName != null) {
                            throw new ParamParseException("Duplicate parameter: " + key);
                        }
                        if (value.isBlank()) {
                            throw new ParamParseException("w: requires a world name.");
                        }
                        worldName = value;
                    }
                    default -> throw new ParamParseException("not a snapshot key: " + key);
                }
            }
        }

        if (since == null) {
            throw new ParamParseException("t: is required, e.g. t:1h (snapshot as of 1 hour ago).");
        }
        if (worldName != null && x == null) {
            throw new ParamParseException("w: requires trg:x,y,z.");
        }
        if (playerName != null && x != null) {
            throw new ParamParseException(
                    "p: and trg: cannot be combined - snapshot is either a player or a container.");
        }

        return new ParsedQuery(since.before(now), playerName, x, y, z, worldName);
    }

    private static Duration parseDuration(String value) throws ParamParseException {
        try {
            return Duration.parse(value);
        } catch (IllegalArgumentException | ArithmeticException ex) {
            throw new ParamParseException("Invalid duration: " + value, ex);
        }
    }

    /** {@code p:} takes exactly one bare name - no {@code PlayerParam}
     *  comma lists and no {@code !} excludes; a snapshot has exactly one
     *  subject. */
    private static String parsePlayerName(String value) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("p: requires a player name.");
        }
        if (value.indexOf(',') >= 0 || value.indexOf('!') >= 0) {
            throw new ParamParseException("p: takes exactly one player name - no commas or !.");
        }
        return value.trim();
    }

    private static int[] parseCoords(String value) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("trg: requires x,y,z coordinates, e.g. trg:100,64,200.");
        }
        Matcher matcher = COORDS.matcher(value.trim());
        if (!matcher.matches()) {
            throw new ParamParseException("trg: requires x,y,z coordinates, e.g. trg:100,64,200.");
        }
        return new int[] {
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))
        };
    }
}

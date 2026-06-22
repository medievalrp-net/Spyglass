package net.medievalrp.spyglass.plugin.storage;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.EventCatalog;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.Rollbackable;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.EventIds;
import org.bukkit.Material;
import org.jetbrains.annotations.ApiStatus;

/**
 * MariaDB / MySQL {@link RecordStore} - the client-server SQL backend
 * (#169) for operators who already run a MariaDB or MySQL server.
 *
 * <h2>Same density design as SQLite (#106), one server engine apart</h2>
 *
 * This store is a deliberate fork of {@link SqliteRecordStore}: the dense
 * schema is what beats CoreProtect, and there is no reason to invent a
 * second one. Every lever carries over:
 *
 * <ol>
 *   <li><b>Palette interning.</b> Block-data strings, event / server names
 *       live once in a {@code dict} table; world and player UUIDs (with
 *       display names) live once in a {@code uuids} table. Each
 *       {@code records} row holds small integer references.</li>
 *   <li><b>Hybrid schema.</b> A clean, player-sourced, no-tile-entity block
 *       edit lives entirely in columns - no payload. Everything else carries
 *       one {@code deflate(BSON(record))} blob ({@link BsonBlobs}) in the
 *       {@code payload} column, decoded only when that row is read. The lean
 *       rollback never touches it.</li>
 *   <li><b>seq IS the sort key.</b> The {@link EventIds} sequence is the
 *       {@code BIGINT} primary key, co-monotonic with {@code occurred}, so
 *       the store sorts / keysets on {@code seq} alone - no time index.</li>
 *   <li><b>Derived / folded columns.</b> Material recovered from block-data,
 *       names from the uuids palette, {@code source.kind} always
 *       {@code player}, {@code expiresAt = occurred + retention}. The chunk
 *       bucket is two <em>virtual generated</em> columns ({@code cx =
 *       FLOOR(x/16)}, {@code cz = FLOOR(z/16)}) - no row storage, only the
 *       location index materializes them - because MariaDB / MySQL can't
 *       portably index an inline expression the way SQLite does.</li>
 * </ol>
 *
 * <h2>Engine</h2>
 *
 * InnoDB. One batched writer (the recorder, off the main thread) commits a
 * drain under a single transaction, with {@code rewriteBatchedStatements}
 * folding the batch into one multi-row INSERT; a small pool of read
 * connections serves search / rollback. Retention is a periodic
 * {@code DELETE ... WHERE occurred < now - retention} since neither engine
 * has a native row TTL.
 *
 * <p>One driver - MariaDB Connector/J - speaks both the MariaDB and MySQL
 * wire protocols, so this single store backs {@code backend = "mariadb"} and
 * {@code "mysql"} alike.
 */
@ApiStatus.Internal
public final class MariaDbRecordStore implements RecordStore {

    private static final Logger LOGGER = Logger.getLogger(MariaDbRecordStore.class.getName());

    // The MariaDB JDBC driver self-registers with DriverManager only when its
    // class is loaded. In the lean (library-loaded) jar the driver lives in
    // Paper's isolated library classloader, which DriverManager's one-time
    // system-classloader ServiceLoader scan never sees - so a bare
    // getConnection() would throw "No suitable driver". Force the class load
    // here, from a classloader this store can reach, so the driver registers.
    // Harmless in the shaded / test jars (the driver is already on this
    // classloader); this just makes the load order explicit.
    static {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(
                    "MariaDB JDBC driver (org.mariadb.jdbc.Driver) is not on the classpath; "
                            + "the mariadb-java-client library failed to load");
        }
    }

    private static final int DEFAULT_READ_POOL = 4;
    private static final long DEFAULT_RETENTION_SECONDS = 28L * 24 * 3600; // 4 weeks
    private static final long RETENTION_SWEEP_MINUTES = 60L;
    private static final int POST_FILTER_SCAN_CAP = 20_000;

    // 14 columns, in bind order. Notably absent (folded / derived / generated,
    // see the class doc): expires_at, source_kind, player_name, world_name,
    // origin_detail, cx / cz, and per-side block materials.
    private static final String INSERT_SQL = "INSERT IGNORE INTO records ("
            + "seq, event, occurred, origin_kind, player, world, x, y, z, "
            + "server, target, orig_block, new_block, payload) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String DECODE_COLUMNS =
            "seq, event, occurred, origin_kind, player, world, x, y, z, "
            + "server, target, orig_block, new_block, payload";

    private final String baseUrl;       // jdbc:mariadb://host:port/db?params
    private final String bootstrapUrl;   // jdbc:mariadb://host:port/?params (no schema)
    private final String database;
    private final String user;
    private final String password;
    private final boolean readOnly;
    private final long retentionSeconds;

    private final Connection writeConn;
    private final Object writeLock = new Object();
    private final PreparedStatement insertStatement;
    private final PreparedStatement dictInsert;
    private final PreparedStatement uuidInsert;

    private final BlockingQueue<Connection> readPool;
    private final List<Connection> allConnections = new ArrayList<>();
    // Dedicated connection for palette reverse-lookups on a cache miss -
    // separate from the query read pool so a miss inside a decode loop that
    // already holds a pooled connection can't deadlock it.
    private final Connection lookupConn;
    private final Object lookupLock = new Object();

    // Palette caches - forward (intern) and reverse (resolve). Loaded whole on
    // open; the single writer keeps them current.
    private final ConcurrentHashMap<String, Integer> dictForward = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> dictReverse = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> uuidForward = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, UUID> uuidReverse = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> uuidName = new ConcurrentHashMap<>();

    private final Set<String> rollbackableEvents;
    private final Set<String> chatEvents;

    private final MariaDbPredicateToSql.Palette palette = new MariaDbPredicateToSql.Palette() {
        @Override
        public Integer dictId(String value) {
            return resolveDictId(value);
        }

        @Override
        public Integer uuidId(UUID value) {
            return resolveUuidId(value);
        }
    };
    private final MariaDbPredicateToSql predicateToSql = new MariaDbPredicateToSql(palette);

    private final ScheduledExecutorService retention;

    public MariaDbRecordStore(String host, int port, String database, String user,
                              String password, boolean ssl, long retentionSeconds) {
        this(host, port, database, user, password, ssl, false, DEFAULT_READ_POOL, retentionSeconds);
    }

    public MariaDbRecordStore(String host, int port, String database, String user,
                              String password, boolean ssl, boolean readOnly) {
        this(host, port, database, user, password, ssl, readOnly, DEFAULT_READ_POOL,
                DEFAULT_RETENTION_SECONDS);
    }

    public MariaDbRecordStore(String host, int port, String database, String user,
                              String password, boolean ssl, boolean readOnly,
                              int readPoolSize, long retentionSeconds) {
        this.database = validateIdentifier(database);
        this.user = user;
        this.password = password;
        this.readOnly = readOnly;
        this.retentionSeconds = retentionSeconds > 0 ? retentionSeconds : DEFAULT_RETENTION_SECONDS;
        // rewriteBatchedStatements folds the drain's addBatch into one
        // multi-row INSERT (the throughput lever); sslMode=trust encrypts an
        // ssl=true connection without demanding a verified CA (operators on a
        // private network), sslMode=disable is plain.
        String params = "?rewriteBatchedStatements=true&connectTimeout=10000&socketTimeout=0&sslMode="
                + (ssl ? "trust" : "disable");
        this.bootstrapUrl = "jdbc:mariadb://" + host + ":" + port + "/" + params;
        this.baseUrl = "jdbc:mariadb://" + host + ":" + port + "/" + this.database + params;
        this.rollbackableEvents = rollbackableEventNames();
        this.chatEvents = EventCatalog.eventsStoredAs(ChatRecord.class);
        try {
            if (!readOnly) {
                createDatabaseIfMissing();
            }
            this.writeConn = open(false);
            if (readOnly) {
                this.insertStatement = null;
                this.dictInsert = null;
                this.uuidInsert = null;
            } else {
                ensureSchema(writeConn);
                this.insertStatement = writeConn.prepareStatement(INSERT_SQL);
                // Idempotent insert-or-get: a value already in the palette
                // table (e.g. re-interned on the recorder's retry-after-failure
                // path, or by a value present from a prior run) resolves to its
                // existing id via LAST_INSERT_ID instead of throwing a
                // duplicate-key. Without this, a transient save failure + retry
                // would re-insert an already-committed palette value and fail
                // on UNIQUE(val) / UNIQUE(hi,lo). getGeneratedKeys returns the
                // id whether the row was inserted or matched.
                this.dictInsert = writeConn.prepareStatement(
                        "INSERT INTO dict(val) VALUES (?) ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)",
                        Statement.RETURN_GENERATED_KEYS);
                this.uuidInsert = writeConn.prepareStatement(
                        "INSERT INTO uuids(hi, lo, name) VALUES (?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)",
                        Statement.RETURN_GENERATED_KEYS);
            }

            int poolSize = Math.max(1, readPoolSize);
            this.readPool = new ArrayBlockingQueue<>(poolSize);
            for (int i = 0; i < poolSize; i++) {
                Connection read = open(true);
                allConnections.add(read);
                readPool.add(read);
            }
            this.lookupConn = open(true);
            allConnections.add(lookupConn);
            loadPalette();
        } catch (SQLException ex) {
            throw new RuntimeException("Failed to open MariaDB store at " + baseUrl
                    + ": " + ex.getMessage(), ex);
        }

        if (readOnly) {
            this.retention = null;
        } else {
            this.retention = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "spyglass-mariadb-retention");
                t.setDaemon(true);
                return t;
            });
            this.retention.scheduleWithFixedDelay(this::pruneExpiredQuietly,
                    RETENTION_SWEEP_MINUTES, RETENTION_SWEEP_MINUTES, TimeUnit.MINUTES);
            pruneExpiredQuietly();
        }
    }

    // ===== Connection / schema =================================

    private Connection open(boolean asReadOnly) throws SQLException {
        Properties props = new Properties();
        if (user != null) {
            props.setProperty("user", user);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        Connection conn = DriverManager.getConnection(baseUrl, props);
        if (asReadOnly) {
            conn.setReadOnly(true);
        }
        return conn;
    }

    /**
     * Best-effort create of the target schema. A privileged user (the common
     * case) gets it auto-created on first start; a least-privilege user
     * without CREATE rights pre-creates it and lands here with an
     * access-denied that we log and move past - the real gate is {@link
     * #open}, which fails loudly with "Unknown database" if it truly doesn't
     * exist. Either way we never mask a genuine missing-database error.
     */
    private void createDatabaseIfMissing() {
        Properties props = new Properties();
        if (user != null) {
            props.setProperty("user", user);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        try (Connection conn = DriverManager.getConnection(bootstrapUrl, props);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + database
                    + "` DEFAULT CHARACTER SET utf8mb4");
        } catch (SQLException ex) {
            LOGGER.log(Level.FINE, () -> "Could not auto-create MariaDB database '" + database
                    + "' (continuing; pre-create it if it does not exist): " + ex.getMessage());
        }
    }

    private static void ensureSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS dict ("
                    + "id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                    + "val VARCHAR(512) NOT NULL, "
                    + "UNIQUE KEY uq_dict_val (val)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            // name rides on the uuid palette so a simple block needn't carry
            // player_name / world_name columns - they fold to the ref.
            st.execute("CREATE TABLE IF NOT EXISTS uuids ("
                    + "id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                    + "hi BIGINT NOT NULL, lo BIGINT NOT NULL, name VARCHAR(255), "
                    + "UNIQUE KEY uq_uuids_hilo (hi, lo)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            // The chunk bucket is two VIRTUAL generated columns - no row
            // storage, the location index materializes them. FLOOR(coord/16)
            // is exact-decimal floor division, matching Math.floorDiv the
            // predicate translator emits for the bounds (negatives included),
            // and the >>4 the other backends bucket on.
            String recordsBody = "CREATE TABLE IF NOT EXISTS records ("
                    + "seq BIGINT NOT NULL PRIMARY KEY, "   // EventIds sequence == sort key
                    + "event INT NOT NULL, "                // dict ref
                    + "occurred BIGINT NOT NULL, "          // epoch SECONDS
                    + "origin_kind INT NULL, "              // dict ref
                    + "player INT NULL, "                   // uuids ref (source.playerId)
                    + "world INT NULL, "                    // uuids ref (location.worldId)
                    + "x INT NULL, y INT NULL, z INT NULL, "
                    + "server INT NULL, target INT NULL, "          // dict refs
                    + "orig_block INT NULL, new_block INT NULL, "    // dict refs (simple-block data)
                    + "payload MEDIUMBLOB NULL, "           // deflate(BSON(record)); NULL for simple blocks
                    + "cx INT AS (FLOOR(x / 16)) VIRTUAL, "
                    + "cz INT AS (FLOOR(z / 16)) VIRTUAL, "
                    + "KEY idx_player (player), "
                    + "KEY idx_loc (world, cx, cz)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
            // Compressed row format roughly halves the footprint (measured
            // 259 -> 124 MiB at 2M block edits, under CoreProtect·MySQL's
            // ~180) for a ~5% rollback-read cost, all off the main thread. It
            // needs innodb_file_per_table=ON (the default); a host without it
            // rejects the clause, so fall back to the default row format
            // rather than refusing to start.
            try {
                st.execute(recordsBody + " ROW_FORMAT=COMPRESSED KEY_BLOCK_SIZE=8");
            } catch (SQLException compressedUnsupported) {
                LOGGER.log(Level.INFO, () -> "MariaDB compressed row format unavailable "
                        + "(innodb_file_per_table off?); using the default row format. "
                        + "On-disk footprint will be larger. Cause: "
                        + compressedUnsupported.getMessage());
                st.execute(recordsBody);
            }
        }
    }

    private void loadPalette() throws SQLException {
        try (Statement st = writeConn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT id, val FROM dict")) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    String val = rs.getString(2);
                    dictForward.put(val, id);
                    dictReverse.put(id, val);
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT id, hi, lo, name FROM uuids")) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    UUID uuid = new UUID(rs.getLong(2), rs.getLong(3));
                    uuidForward.put(uuid, id);
                    uuidReverse.put(id, uuid);
                    String name = rs.getString(4);
                    if (name != null) {
                        uuidName.put(id, name);
                    }
                }
            }
        }
    }

    private static Set<String> rollbackableEventNames() {
        Set<String> names = new HashSet<>();
        for (String name : EventCatalog.eventNames()) {
            Class<? extends EventRecord> clazz = EventCatalog.recordClassOf(name);
            if (clazz != null && Rollbackable.class.isAssignableFrom(clazz)) {
                names.add(name);
            }
        }
        return names;
    }

    /** Reject a non-identifier schema name so it can't escape the backticks. */
    private static String validateIdentifier(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException(
                    "Illegal MariaDB database name '" + name + "' (allowed: letters, digits, underscore)");
        }
        return name;
    }

    // ===== Save ================================================

    @Override
    public void save(List<EventRecord> records) {
        if (records.isEmpty() || readOnly) {
            return;
        }
        synchronized (writeLock) {
            Map<String, Integer> pendingDict = new HashMap<>();
            Map<UUID, Integer> pendingUuid = new HashMap<>();
            Map<Integer, String> pendingNames = new HashMap<>();
            try {
                writeConn.setAutoCommit(false);
                for (EventRecord record : records) {
                    bindRecord(record, pendingDict, pendingUuid, pendingNames);
                    insertStatement.addBatch();
                }
                insertStatement.executeBatch();
                writeConn.commit();
                // Promote interns to the shared cache only after a durable
                // commit, so a rolled-back batch never leaves phantom ids.
                pendingDict.forEach((val, id) -> {
                    dictForward.put(val, id);
                    dictReverse.put(id, val);
                });
                pendingUuid.forEach((uuid, id) -> {
                    uuidForward.put(uuid, id);
                    uuidReverse.put(id, uuid);
                });
                uuidName.putAll(pendingNames);
            } catch (SQLException ex) {
                rollbackQuietly();
                throw new RuntimeException("MariaDB save failed: " + ex.getMessage(), ex);
            } finally {
                restoreAutoCommit();
            }
        }
    }

    private void bindRecord(EventRecord record, Map<String, Integer> pendingDict,
                            Map<UUID, Integer> pendingUuid, Map<Integer, String> pendingNames)
            throws SQLException {
        PreparedStatement ps = insertStatement;
        ps.setLong(1, EventIds.sequenceOf(record.id()));
        ps.setInt(2, internDict(record.event(), pendingDict));
        ps.setLong(3, record.occurred().getEpochSecond());

        Origin origin = record.origin();
        bindDictRef(ps, 4, origin == null ? null : origin.kind(), pendingDict);

        Source source = record.source();
        bindUuidRef(ps, 5, source == null ? null : source.playerId(),
                source == null ? null : source.playerName(), pendingUuid, pendingNames);

        BlockLocation location = record.location();
        UUID worldId = location == null ? null : location.worldId();
        bindUuidRef(ps, 6, worldId, location == null ? null : location.worldName(),
                pendingUuid, pendingNames);
        if (location == null) {
            ps.setNull(7, Types.INTEGER);
            ps.setNull(8, Types.INTEGER);
            ps.setNull(9, Types.INTEGER);
        } else {
            ps.setInt(7, location.x());
            ps.setInt(8, location.y());
            ps.setInt(9, location.z());
        }

        bindDictRef(ps, 10, record.server(), pendingDict);
        bindDictRef(ps, 11, record.target(), pendingDict);

        if (columnStorable(record)) {
            BlockSnapshot before = beforeBlock(record);
            BlockSnapshot after = afterBlock(record);
            ps.setInt(12, internDict(before.blockData(), pendingDict));
            ps.setInt(13, internDict(after.blockData(), pendingDict));
            ps.setNull(14, Types.BLOB);
        } else {
            ps.setNull(12, Types.INTEGER);
            ps.setNull(13, Types.INTEGER);
            ps.setBytes(14, deflate(BsonBlobs.encodeRecordBytes(record)));
        }
    }

    /**
     * {@code true} when a record reduces, losslessly, to columns alone - no
     * payload. That's a block break / place whose two snapshots are simple
     * and whose material is recoverable from the block-data string, with a
     * plain player source and a default origin. Anything the lean columns
     * can't carry forces a payload blob.
     */
    private static boolean columnStorable(EventRecord record) {
        BlockSnapshot before = beforeBlock(record);
        BlockSnapshot after = afterBlock(record);
        if (before == null || after == null || !before.simple() || !after.simple()) {
            return false;
        }
        if (!record.extensions().isEmpty()) {
            return false;
        }
        // material must round-trip from the block-data string alone.
        if (!materialMatches(before) || !materialMatches(after)) {
            return false;
        }
        Origin origin = record.origin();
        if (origin != null && origin.detail() != null) {
            return false;
        }
        Source source = record.source();
        // Columns carry only a plain player source (kind is reconstructed as
        // "player"); the UUID / name fold to the palette.
        return source != null
                && "player".equals(source.kind())
                && source.entityId() == null && source.entityType() == null
                && source.pluginName() == null && source.commandBlockLocation() == null
                && source.description() == null;
    }

    private static boolean materialMatches(BlockSnapshot snapshot) {
        Material derived = materialFromBlockData(snapshot.blockData());
        return derived != null && derived == snapshot.material();
    }

    private static Material materialFromBlockData(String blockData) {
        if (blockData == null || !blockData.startsWith("minecraft:")) {
            return null;
        }
        String key = blockData.substring("minecraft:".length());
        int bracket = key.indexOf('[');
        if (bracket >= 0) {
            key = key.substring(0, bracket);
        }
        try {
            return Material.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static BlockSnapshot beforeBlock(EventRecord record) {
        if (record instanceof BlockBreakRecord b) {
            return b.originalBlock();
        }
        if (record instanceof BlockPlaceRecord p) {
            return p.originalBlock();
        }
        return null;
    }

    private static BlockSnapshot afterBlock(EventRecord record) {
        if (record instanceof BlockBreakRecord b) {
            return b.newBlock();
        }
        if (record instanceof BlockPlaceRecord p) {
            return p.newBlock();
        }
        return null;
    }

    // ===== Query ===============================================

    @Override
    public QueryResult query(QueryRequest request) {
        return runQuery(request, true);
    }

    @Override
    public QueryResult querySummary(QueryRequest request) {
        return runQuery(request, false);
    }

    private QueryResult runQuery(QueryRequest request, boolean includeHeavy) {
        List<QueryPredicate> predicates = new ArrayList<>(request.predicates());
        String where;
        List<QueryPredicate> residual = List.of();
        try {
            where = predicateToSql.translate(predicates);
        } catch (MariaDbPredicateToSql.UnsupportedPredicateException unsupported) {
            List<QueryPredicate> pushable = new ArrayList<>();
            List<QueryPredicate> inMemory = new ArrayList<>();
            for (QueryPredicate predicate : predicates) {
                try {
                    predicateToSql.translate(List.of(predicate));
                    pushable.add(predicate);
                } catch (MariaDbPredicateToSql.UnsupportedPredicateException ex) {
                    inMemory.add(predicate);
                }
            }
            residual = inMemory;
            where = predicateToSql.translate(pushable);
        }
        boolean postFilter = !residual.isEmpty();
        boolean hydrate = includeHeavy || postFilter;
        where = appendNoChat(where, request);

        boolean newestFirst = request.sort() != Sort.OLDEST_FIRST;
        String sql = "SELECT " + DECODE_COLUMNS + " FROM records"
                + (where.isEmpty() ? "" : " WHERE " + where)
                + " ORDER BY seq " + (newestFirst ? "DESC" : "ASC")
                + " LIMIT " + (postFilter ? POST_FILTER_SCAN_CAP : request.limit());

        List<EventRecord> records = new ArrayList<>(postFilter ? 64 : Math.min(request.limit(), 4096));
        Connection conn = borrow();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                EventRecord record = decodeRow(rs, hydrate);
                if (record == null) {
                    continue;
                }
                if (postFilter && !PredicateEvaluator.matchesAll(residual, record)) {
                    continue;
                }
                records.add(record);
                if (postFilter && records.size() >= request.limit()) {
                    break;
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException("MariaDB query failed: " + ex.getMessage(), ex);
        } finally {
            giveBack(conn);
        }

        List<QueryResult.RecordAggregation> aggregations =
                request.grouping() && !request.flags().contains(Flag.NO_GROUP)
                        ? aggregate(records)
                        : List.of();
        return new QueryResult(records, aggregations);
    }

    @Override
    public QueryPage queryPage(QueryRequest request, QueryPage.Cursor cursor, int pageSize) {
        // Generic keyset page over seq - all event types, full decode. The
        // lean, rollbackable-filtered path is streamRollbackEffects below.
        boolean newestFirst = request.sort() != Sort.OLDEST_FIRST;
        String where = appendNoChat(predicateToSql.translate(new ArrayList<>(request.predicates())), request);
        where = appendKeyset(where, cursor, newestFirst);

        String sql = "SELECT " + DECODE_COLUMNS + " FROM records"
                + (where.isEmpty() ? "" : " WHERE " + where)
                + " ORDER BY seq " + (newestFirst ? "DESC" : "ASC")
                + " LIMIT " + pageSize;

        List<EventRecord> records = new ArrayList<>(Math.min(pageSize, 4096));
        Connection conn = borrow();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                EventRecord record = decodeRow(rs, true);
                if (record != null) {
                    records.add(record);
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException("MariaDB paged query failed: " + ex.getMessage(), ex);
        } finally {
            giveBack(conn);
        }
        QueryPage.Cursor next = null;
        if (records.size() == pageSize) {
            EventRecord last = records.get(records.size() - 1);
            if (last.occurred() != null && last.id() != null) {
                next = new QueryPage.Cursor(last.occurred(), last.id());
            }
        }
        return new QueryPage(records, next);
    }

    @Override
    public QueryPage.Cursor streamRollbackEffects(QueryRequest request, QueryPage.Cursor cursor,
                                                  int windowLimit, boolean rollback,
                                                  RollbackEffectSink sink) {
        // Allocation-lean rollback read (mirrors the SQLite #67/#83 path): a
        // direction-specific, rollbackable-only keyset scan that resolves the
        // one block-data side the apply engine writes straight from the
        // in-memory palette - the cached String is the SAME reference for
        // every stone / air row, so the simple-block hot path allocates
        // nothing per row. Only a payload row (container / entity / complex
        // block) decodes.
        String blockColumn = rollback ? "orig_block" : "new_block";
        boolean newestFirst = request.sort() != Sort.OLDEST_FIRST;
        String where = predicateToSql.translate(new ArrayList<>(request.predicates()));
        where = appendAnd(where, rollbackableFilter());
        where = appendKeyset(where, cursor, newestFirst);

        String sql = "SELECT seq, occurred, world, x, y, z, "
                + blockColumn + " AS blk, payload, event FROM records"
                + (where.isEmpty() ? "" : " WHERE " + where)
                + " ORDER BY seq " + (newestFirst ? "DESC" : "ASC")
                + " LIMIT " + windowLimit;

        Instant lastOccurred = null;
        UUID lastId = null;
        int count = 0;
        Connection conn = borrow();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                count++;
                long seq = rs.getLong("seq");
                UUID id = EventIds.uuidOf(seq);
                Instant occurred = Instant.ofEpochSecond(rs.getLong("occurred"));
                lastOccurred = occurred;
                lastId = id;
                emitEffect(rs, rollback, occurred, id, sink);
            }
        } catch (SQLException ex) {
            throw new RuntimeException("MariaDB rollback stream failed: " + ex.getMessage(), ex);
        } finally {
            giveBack(conn);
        }
        return (count == windowLimit && lastOccurred != null && lastId != null)
                ? new QueryPage.Cursor(lastOccurred, lastId)
                : null;
    }

    private void emitEffect(ResultSet rs, boolean rollback, Instant occurred, UUID id,
                            RollbackEffectSink sink) throws SQLException {
        byte[] blob = rs.getBytes("payload");
        if (blob == null) {
            // Simple-block fast path: resolve the one side's block-data from
            // the palette and hand primitives straight to the sink - no
            // record, snapshot, or effect object. expectedCurrent is unused
            // under force-overwrite (#69), so pass null.
            int blockRef = rs.getInt("blk");
            if (rs.wasNull()) {
                sink.skip(occurred, id);
                return;
            }
            UUID world = uuidValue(rs.getInt("world"));
            sink.block(world, rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                    dictValue(blockRef), null, occurred, id);
            return;
        }
        EventRecord record = decodeBlob(blob);
        if (!(record instanceof Rollbackable rollbackable)) {
            sink.skip(occurred, id);
            return;
        }
        RollbackEffect effect = rollback ? rollbackable.rollbackEffect() : rollbackable.restoreEffect();
        if (effect instanceof RollbackEffect.BlockReplace br
                && br.replacement() != null && br.replacement().simple()) {
            BlockLocation loc = br.location();
            sink.block(loc.worldId(), loc.x(), loc.y(), loc.z(),
                    br.replacement().blockData(), null, occurred, id);
        } else {
            sink.complex(effect, occurred, id);
        }
    }

    // ===== Row decode ==========================================

    private EventRecord decodeRow(ResultSet rs, boolean includeHeavy) throws SQLException {
        byte[] blob = rs.getBytes("payload");
        if (blob != null) {
            return decodeBlob(blob); // authoritative; columns were only for pushdown
        }
        // Column-stored: a clean player-sourced simple block.
        String event = dictValue(rs.getInt("event"));
        Class<? extends EventRecord> clazz = EventCatalog.recordClassOf(event);
        if (clazz == null) {
            return null;
        }
        UUID id = EventIds.uuidOf(rs.getLong("seq"));
        Instant occurred = Instant.ofEpochSecond(rs.getLong("occurred"));
        Instant expiresAt = occurred.plusSeconds(retentionSeconds);
        Origin origin = new Origin(dictValueOrNull(rs, "origin_kind"), null);
        int playerRef = rs.getInt("player");
        boolean hasPlayer = !rs.wasNull();
        Source source = new Source("player",
                hasPlayer ? uuidValue(playerRef) : null,
                hasPlayer ? uuidName.get(playerRef) : null,
                null, null, null, null, null);
        int worldRef = rs.getInt("world");
        boolean hasWorld = !rs.wasNull();
        BlockLocation location = new BlockLocation(
                hasWorld ? uuidValue(worldRef) : null,
                hasWorld ? uuidName.get(worldRef) : null,
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
        String server = dictValueOrNull(rs, "server");
        String target = dictValueOrNull(rs, "target");
        BlockSnapshot before = includeHeavy ? snapshot(rs, "orig_block") : null;
        BlockSnapshot after = includeHeavy ? snapshot(rs, "new_block") : null;

        if (clazz == BlockBreakRecord.class) {
            return new BlockBreakRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target, before, after);
        }
        if (clazz == BlockPlaceRecord.class) {
            return new BlockPlaceRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target, before, after);
        }
        return null; // only block break / place are ever column-stored
    }

    private BlockSnapshot snapshot(ResultSet rs, String blockColumn) throws SQLException {
        String blockData = dictValueOrNull(rs, blockColumn);
        if (blockData == null) {
            return null;
        }
        Material material = materialFromBlockData(blockData);
        if (material == null) {
            return null; // column storage guaranteed derivability, but stay defensive
        }
        return new BlockSnapshot(material, blockData,
                List.of(), List.of(), List.of(), List.of(), null, List.of());
    }

    private EventRecord decodeBlob(byte[] blob) {
        return BsonBlobs.decodeRecordBytes(inflate(blob));
    }

    // ===== Predicate / SQL helpers =============================

    private String appendNoChat(String where, QueryRequest request) {
        if (!request.flags().contains(Flag.NO_CHAT)) {
            return where;
        }
        List<Integer> ids = new ArrayList<>(chatEvents.size());
        for (String name : chatEvents) {
            Integer id = dictForward.get(name);
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return where;
        }
        return appendAnd(where, "event NOT IN (" + joinInts(ids) + ")");
    }

    private static String appendAnd(String where, String clause) {
        if (clause == null || clause.isEmpty()) {
            return where;
        }
        return where.isEmpty() ? clause : where + " AND " + clause;
    }

    private static String appendKeyset(String where, QueryPage.Cursor cursor, boolean newestFirst) {
        if (cursor == null) {
            return where;
        }
        // Sort / keyset is on seq alone (co-monotonic with occurred), so the
        // cursor is a single-column compare on the primary key.
        String op = newestFirst ? "<" : ">";
        return appendAnd(where, "seq " + op + " " + EventIds.sequenceOf(cursor.id()));
    }

    private String rollbackableFilter() {
        List<Integer> ids = new ArrayList<>(rollbackableEvents.size());
        for (String name : rollbackableEvents) {
            Integer id = dictForward.get(name);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids.isEmpty() ? "0" : "event IN (" + joinInts(ids) + ")";
    }

    private static String joinInts(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    // ===== Palette resolution ==================================

    private int internDict(String value, Map<String, Integer> pending) throws SQLException {
        Integer id = dictForward.get(value);
        if (id != null) {
            return id;
        }
        id = pending.get(value);
        if (id != null) {
            return id;
        }
        dictInsert.setString(1, value);
        dictInsert.executeUpdate();
        int newId = generatedKey(dictInsert);
        pending.put(value, newId);
        return newId;
    }

    private int internUuid(UUID value, String name, Map<UUID, Integer> pending,
                           Map<Integer, String> pendingNames) throws SQLException {
        Integer id = uuidForward.get(value);
        if (id != null) {
            return id;
        }
        id = pending.get(value);
        if (id != null) {
            return id;
        }
        uuidInsert.setLong(1, value.getMostSignificantBits());
        uuidInsert.setLong(2, value.getLeastSignificantBits());
        if (name == null) {
            uuidInsert.setNull(3, Types.VARCHAR);
        } else {
            uuidInsert.setString(3, name);
        }
        uuidInsert.executeUpdate();
        int newId = generatedKey(uuidInsert);
        pending.put(value, newId);
        if (name != null) {
            pendingNames.put(newId, name);
        }
        return newId;
    }

    private void bindDictRef(PreparedStatement ps, int index, String value,
                             Map<String, Integer> pending) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, internDict(value, pending));
        }
    }

    private void bindUuidRef(PreparedStatement ps, int index, UUID value, String name,
                             Map<UUID, Integer> pending, Map<Integer, String> pendingNames)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, internUuid(value, name, pending, pendingNames));
        }
    }

    private static int generatedKey(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (keys.next()) {
                return keys.getInt(1);
            }
        }
        throw new SQLException("INSERT returned no generated key");
    }

    private String dictValue(int id) {
        String val = dictReverse.get(id);
        if (val != null) {
            return val;
        }
        val = lookupDict(id);
        if (val != null) {
            dictReverse.put(id, val);
            dictForward.putIfAbsent(val, id);
        }
        return val;
    }

    private UUID uuidValue(int id) {
        UUID val = uuidReverse.get(id);
        if (val != null) {
            return val;
        }
        val = lookupUuid(id);
        if (val != null) {
            uuidReverse.put(id, val);
            uuidForward.putIfAbsent(val, id);
        }
        return val;
    }

    private String dictValueOrNull(ResultSet rs, String column) throws SQLException {
        int id = rs.getInt(column);
        return rs.wasNull() ? null : dictValue(id);
    }

    private Integer resolveDictId(String value) {
        Integer id = dictForward.get(value);
        if (id != null) {
            return id;
        }
        synchronized (lookupLock) {
            try (PreparedStatement ps = lookupConn.prepareStatement("SELECT id FROM dict WHERE val = ?")) {
                ps.setString(1, value);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int found = rs.getInt(1);
                        dictForward.putIfAbsent(value, found);
                        dictReverse.putIfAbsent(found, value);
                        return found;
                    }
                }
            } catch (SQLException ex) {
                throw new RuntimeException("MariaDB dict lookup failed: " + ex.getMessage(), ex);
            }
        }
        return null;
    }

    private Integer resolveUuidId(UUID value) {
        Integer id = uuidForward.get(value);
        if (id != null) {
            return id;
        }
        synchronized (lookupLock) {
            try (PreparedStatement ps = lookupConn.prepareStatement(
                    "SELECT id FROM uuids WHERE hi = ? AND lo = ?")) {
                ps.setLong(1, value.getMostSignificantBits());
                ps.setLong(2, value.getLeastSignificantBits());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int found = rs.getInt(1);
                        uuidForward.putIfAbsent(value, found);
                        uuidReverse.putIfAbsent(found, value);
                        return found;
                    }
                }
            } catch (SQLException ex) {
                throw new RuntimeException("MariaDB uuid lookup failed: " + ex.getMessage(), ex);
            }
        }
        return null;
    }

    private String lookupDict(int id) {
        synchronized (lookupLock) {
            try (PreparedStatement ps = lookupConn.prepareStatement("SELECT val FROM dict WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            } catch (SQLException ex) {
                throw new RuntimeException("MariaDB dict reverse lookup failed: " + ex.getMessage(), ex);
            }
        }
    }

    private UUID lookupUuid(int id) {
        synchronized (lookupLock) {
            try (PreparedStatement ps = lookupConn.prepareStatement(
                    "SELECT hi, lo, name FROM uuids WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    UUID uuid = new UUID(rs.getLong(1), rs.getLong(2));
                    String name = rs.getString(3);
                    if (name != null) {
                        uuidName.putIfAbsent(id, name);
                    }
                    return uuid;
                }
            } catch (SQLException ex) {
                throw new RuntimeException("MariaDB uuid reverse lookup failed: " + ex.getMessage(), ex);
            }
        }
    }

    // ===== Read pool ===========================================

    private Connection borrow() {
        try {
            Connection conn = readPool.take();
            // Server-side idle timeout (wait_timeout) can kill a long-idle
            // read connection. Validate on borrow and transparently reopen so
            // a search after a quiet night doesn't surface a stale-socket
            // error. The writer stays hot (Spyglass logs continuously) so it
            // doesn't need the same dance.
            if (!isValidQuietly(conn)) {
                replaceReadConnection(conn);
                return borrow();
            }
            return conn;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted borrowing a MariaDB read connection", ex);
        }
    }

    private void giveBack(Connection conn) {
        readPool.offer(conn);
    }

    private static boolean isValidQuietly(Connection conn) {
        try {
            return conn.isValid(2);
        } catch (SQLException ex) {
            return false;
        }
    }

    private void replaceReadConnection(Connection dead) {
        synchronized (allConnections) {
            allConnections.remove(dead);
        }
        closeQuietly(dead);
        try {
            Connection fresh = open(true);
            synchronized (allConnections) {
                allConnections.add(fresh);
            }
            readPool.offer(fresh);
        } catch (SQLException ex) {
            // Couldn't reopen now (server down?); surface on the next borrow
            // rather than spin. Re-offer nothing so the pool shrinks by one.
            LOGGER.log(Level.WARNING, "MariaDB read connection could not be reopened", ex);
        }
    }

    /** Work that runs against a borrowed connection and may throw {@link SQLException}. */
    @FunctionalInterface
    public interface ConnectionWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /**
     * Run {@code work} on a pooled read connection. The auxiliary MariaDB
     * stores (undo / tool-state / salvage) share this store's read pool
     * rather than opening their own, so connection count stays bounded.
     */
    public <T> T withReadConnection(ConnectionWork<T> work) {
        Connection conn = borrow();
        try {
            return work.apply(conn);
        } catch (SQLException ex) {
            throw new RuntimeException("MariaDB read failed: " + ex.getMessage(), ex);
        } finally {
            giveBack(conn);
        }
    }

    /**
     * Run {@code work} on the single write connection under the write lock,
     * so every writer (records + aux) serialises on one connection.
     */
    public <T> T withWriteConnection(ConnectionWork<T> work) {
        synchronized (writeLock) {
            try {
                return work.apply(writeConn);
            } catch (SQLException ex) {
                throw new RuntimeException("MariaDB write failed: " + ex.getMessage(), ex);
            }
        }
    }

    // ===== Retention / maintenance =============================

    private void pruneExpiredQuietly() {
        try {
            pruneExpired();
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "MariaDB retention sweep failed", ex);
        }
    }

    /** Drop records past the retention horizon; the TTL analogue. */
    public long pruneExpired() {
        if (readOnly) {
            return 0L;
        }
        long thresholdSec = (System.currentTimeMillis() / 1000L) - retentionSeconds;
        synchronized (writeLock) {
            try (PreparedStatement ps = writeConn.prepareStatement(
                    "DELETE FROM records WHERE occurred < ?")) {
                ps.setLong(1, thresholdSec);
                return ps.executeUpdate();
            } catch (SQLException ex) {
                throw new RuntimeException("MariaDB retention prune failed: " + ex.getMessage(), ex);
            }
        }
    }

    /** Total record rows - test / benchmark helper. */
    public long count() {
        Connection conn = borrow();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM records")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException ex) {
            throw new RuntimeException("MariaDB count failed: " + ex.getMessage(), ex);
        } finally {
            giveBack(conn);
        }
    }

    public String databaseName() {
        return database;
    }

    // ===== Aggregate (mirrors Mongo / ClickHouse / SQLite) =====

    private List<QueryResult.RecordAggregation> aggregate(List<EventRecord> records) {
        Map<String, Long> counts = new HashMap<>();
        Map<String, EventRecord> sample = new HashMap<>();
        for (EventRecord record : records) {
            String key = record.event() + "|" + record.sourceName() + "|" + record.target() + "|"
                    + record.occurred().atZone(java.time.ZoneOffset.UTC).toLocalDate();
            counts.merge(key, 1L, Long::sum);
            sample.putIfAbsent(key, record);
        }
        return counts.entrySet().stream()
                .map(entry -> new QueryResult.RecordAggregation(sample.get(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparing((QueryResult.RecordAggregation aggregation)
                        -> aggregation.sample().occurred()).reversed())
                .toList();
    }

    // ===== Compression (per-event payload) =====================

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, input.length / 2));
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] input) {
        Inflater inflater = new Inflater();
        inflater.setInput(input);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, input.length * 2));
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && inflater.needsInput()) {
                    break;
                }
                out.write(buffer, 0, n);
            }
        } catch (java.util.zip.DataFormatException ex) {
            throw new RuntimeException("MariaDB payload inflate failed: " + ex.getMessage(), ex);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    // ===== Lifecycle ===========================================

    private void rollbackQuietly() {
        try {
            writeConn.rollback();
        } catch (SQLException ignored) {
            // best-effort; the original failure is what we surface
        }
    }

    private void restoreAutoCommit() {
        try {
            writeConn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // best-effort
        }
    }

    @Override
    public void close() {
        if (retention != null) {
            retention.shutdownNow();
        }
        closeQuietly(insertStatement);
        closeQuietly(dictInsert);
        closeQuietly(uuidInsert);
        closeQuietly(writeConn);
        synchronized (allConnections) {
            for (Connection conn : allConnections) {
                closeQuietly(conn);
            }
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // shutdown best-effort
        }
    }
}

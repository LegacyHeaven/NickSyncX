package dev.legacyheaven.nicksync.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;
import net.kyori.adventure.text.Component;

import com.google.inject.Inject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

@Plugin(id = "nicksyncx", name = "NickSyncX", version = "1.4.0",
    description = "Synchronizes only EssentialsX nicknames.", authors = {"LegacyHeaven"})
public final class LegacyNickSyncVelocity {
    private static final int PAGE_SIZE = 10;
    private static final long HISTORY_WINDOW_MILLIS = TimeUnit.HOURS.toMillis(24);
    private static final String NICKNAME_TABLE = "nicksyncx";
    private static final String SYNC_EVENTS_TABLE = "nicksyncx_sync_events";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Map<UUID, Record> records = new ConcurrentHashMap<>();
    private final Path directory = Path.of("plugins", "NickSyncX");
    private final Path legacyDirectory = Path.of("plugins", "LegacyNickSync");
    private final ExecutorService databaseWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NickSyncX-Database");
        t.setDaemon(true);
        return t;
    });

    private MinecraftChannelIdentifier channel;
    private Map<String, String> config = Map.of();
    private String brandName = "NickSyncX";
    private Connection connection;
    private String storageType;
    private String adminPermission = "nicksyncx.admin";
    private Path sqliteFile;
    private boolean debug;
    private volatile boolean databaseReady;

    @Inject
    public LegacyNickSyncVelocity(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void initialize(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(directory);
            migrateLegacyDirectory();
            Path configFile = directory.resolve("config.yml");
            if (!Files.exists(configFile)) {
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in == null) throw new IOException("Bundled config.yml is missing");
                    Files.copy(in, configFile);
                }
            }
            config = readSimpleYaml(configFile);
            brandName = value("branding.display-name", "NickSyncX");
            if (brandName == null || brandName.isBlank()) brandName = "NickSyncX";
            String channelName = value("network.channel", "legacyheaven:nicksync");
            String[] parts = channelName.split(":", 2);
            if (parts.length != 2) throw new IOException("network.channel must be namespace:key");
            channel = MinecraftChannelIdentifier.create(parts[0], parts[1]);
            storageType = value("storage.type", "mariadb").toLowerCase(Locale.ROOT);
            adminPermission = value("commands.admin-permission", "nicksyncx.admin");
            if ("legacynicksync.admin".equalsIgnoreCase(adminPermission)) {
                adminPermission = "nicksyncx.admin";
                logger.info("The legacy default admin permission is now nicksyncx.admin.");
            }
            debug = Boolean.parseBoolean(value("logging.debug", "false"));
            sqliteFile = directory.resolve(value("storage.sqlite.file", "nicknames.sqlite")).normalize();
            if (!sqliteFile.startsWith(directory.normalize())) {
                throw new IOException("storage.sqlite.file must remain inside plugins/NickSyncX");
            }
            proxy.getChannelRegistrar().register(channel);
            initializeDatabase();
            loadRecords();
            importLegacyFileIfNeeded();
            databaseReady = true;
            registerAdminCommand();
            logger.info("{} loaded {} nickname records using {} storage.", brandName, records.size(), storageType);
        } catch (Exception e) {
            logger.error("{} could not initialize. Nickname updates will be rejected until restart.", brandName, e);
        }
    }

    @Subscribe
    public void onMessage(PluginMessageEvent event) {
        if (channel == null || !channel.equals(event.getIdentifier())) return;
        if (!(event.getSource() instanceof ServerConnection)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!databaseReady) {
            logger.warn("Ignored nickname message because the database is not ready.");
            return;
        }

        try {
            Message m = decode(event.getData());
            if (!Set.of("SET", "MIGRATE", "SYNC", "FORCE").contains(m.action)) return;

            ServerConnection sourceConnection = (ServerConnection) event.getSource();
            String actualSource = sourceConnection.getServer().getServerInfo().getName();
            if (!actualSource.equalsIgnoreCase(m.source)) {
                logger.warn("Rejected nickname update claiming source '{}' from actual backend '{}'.",
                        m.source, actualSource);
                return;
            }

            databaseWorker.execute(() -> {
                try {
                    Record old = records.get(m.uuid);
                    boolean forced = m.action.equals("FORCE");
                    boolean wins = forced || old == null || m.weight > old.weight
                            || (m.weight == old.weight && m.timestamp >= old.timestamp);
                    if (!wins) {
                        if (old != null && m.weight < old.weight) {
                            sendNotice(m.uuid, "Your nickname was not synced because " + old.source
                                    + " currently has higher priority. Change it there, or use /nicksync force to override network priority.");
                        }
                        if (m.action.equals("SYNC") || debug) logger.info("Rejected nickname update for {} from {} (weight {}); authority is {} (weight {}).",
                                m.uuid, m.source, m.weight, old.source, old.weight);
                        return;
                    }

                    Record updated = new Record(m.uuid, m.nickname, m.source, m.weight, m.timestamp);
                    persistAcceptedUpdate(updated, m, System.currentTimeMillis());
                    records.put(updated.uuid, updated);
                    broadcast(m.uuid, m.nickname);
                    if (m.action.equals("SYNC") || debug) logger.info("Saved nickname record for {} from {} (weight {}).", updated.uuid, updated.source, updated.weight);
                    if (debug) logger.info("Accepted {} nickname update for {} from {} at weight {}.", m.action, m.uuid, m.source, m.weight);
                } catch (SQLException e) {
                    logger.error("Database write failed for nickname {}. The update was not applied.", m.uuid, e);
                }
            });
        } catch (Exception e) {
            logger.warn("{} rejected a malformed plugin message.", brandName, e);
        }
    }

    @Subscribe
    public void onConnect(ServerPostConnectEvent event) {
        Record record = records.get(event.getPlayer().getUniqueId());
        if (record != null) sendApply(event.getPlayer(), record.uuid, record.nickname);
    }

    @Subscribe
    public void shutdown(ProxyShutdownEvent event) {
        databaseReady = false;
        databaseWorker.shutdown();
        try {
            if (!databaseWorker.awaitTermination(5, TimeUnit.SECONDS)) databaseWorker.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            databaseWorker.shutdownNow();
        }
        closeConnection();
        if (channel != null) proxy.getChannelRegistrar().unregister(channel);
    }

    private void broadcast(UUID uuid, String nickname) {
        for (RegisteredServer backend : proxy.getAllServers()) {
            backend.getPlayersConnected().stream().findFirst()
                    .ifPresent(carrier -> sendApply(carrier, uuid, nickname));
        }
    }

    private void sendApply(Player carrier, UUID uuid, String nickname) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF("APPLY");
                out.writeUTF(uuid.toString());
                out.writeUTF(nickname == null ? "" : nickname);
            }
            carrier.getCurrentServer().ifPresent(connection -> connection.sendPluginMessage(channel, bytes.toByteArray()));
        } catch (IOException e) {
            logger.warn("Could not send nickname to backend.", e);
        }
    }

    private void sendNotice(UUID uuid, String message) {
        proxy.getPlayer(uuid).ifPresent(player -> {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream out = new DataOutputStream(bytes)) {
                    out.writeUTF("NOTICE");
                    out.writeUTF(message);
                }
                player.getCurrentServer().ifPresent(connection -> connection.sendPluginMessage(channel, bytes.toByteArray()));
            } catch (IOException e) {
                logger.warn("Could not notify player {} about a rejected nickname update.", uuid, e);
            }
        });
    }

    private Message decode(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String action = in.readUTF();
            UUID uuid = UUID.fromString(in.readUTF());
            String source = in.readUTF();
            int weight = in.readInt();
            long timestamp = in.readLong();
            String nickname = in.readUTF();
            return new Message(action, uuid, source, weight, timestamp, nickname);
        }
    }

    private void initializeDatabase() throws SQLException, IOException {
        String driverClass = switch (storageType) {
            case "sqlite" -> "org.sqlite.JDBC";
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            case "mariadb" -> "org.mariadb.jdbc.Driver";
            default -> throw new IOException("Unsupported storage.type '" + storageType + "'. Use sqlite, mysql, or mariadb.");
        };
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC driver is missing for storage.type '" + storageType + "': " + driverClass, e);
        }
        if (storageType.equals("sqlite")) {
            Files.createDirectories(sqliteFile.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath());
        } else if (storageType.equals("mysql") || storageType.equals("mariadb")) {
            String host = value("storage.mysql.host", "127.0.0.1");
            int port = Integer.parseInt(value("storage.mysql.port", "3306"));
            String database = value("storage.mysql.database", "legacy_nicksync");
            String user = value("storage.mysql.username", "legacy_nicksync");
            String password = value("storage.mysql.password", "CHANGE_ME");
            String params = value("storage.mysql.parameters", "useSSL=false&serverTimezone=UTC");
            boolean mariaDb = storageType.equals("mariadb");
            String jdbcScheme = mariaDb ? "jdbc:mariadb://" : "jdbc:mysql://";
            String url = jdbcScheme + host + ":" + port + "/" + database
                    + (params.isBlank() ? "" : (params.startsWith("?") ? params : "?" + params));
            connection = DriverManager.getConnection(url, user, password);
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + NICKNAME_TABLE + " ("
                    + "uuid VARCHAR(36) NOT NULL PRIMARY KEY, nickname TEXT NOT NULL, source VARCHAR(128) NOT NULL, "
                    + "weight INTEGER NOT NULL, updated_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + SYNC_EVENTS_TABLE + " ("
                    + "event_id VARCHAR(36) NOT NULL PRIMARY KEY, uuid VARCHAR(36) NOT NULL, action VARCHAR(16) NOT NULL, "
                    + "nickname TEXT NOT NULL, source VARCHAR(128) NOT NULL, weight INTEGER NOT NULL, occurred_at BIGINT NOT NULL)");
        }
        migrateLegacyTable("legacynicksync_nicknames", NICKNAME_TABLE,
                Set.of("uuid", "nickname", "source", "weight", "updated_at"));
        migrateLegacyTable("legacynick", NICKNAME_TABLE,
                Set.of("uuid", "nickname", "source", "weight", "updated_at"));
        migrateLegacyTable("legacynicksync_sync_events", SYNC_EVENTS_TABLE,
                Set.of("event_id", "uuid", "action", "nickname", "source", "weight", "occurred_at"));
        pruneHistory(System.currentTimeMillis() - HISTORY_WINDOW_MILLIS);
    }

    private void migrateLegacyTable(String sourceTable, String targetTable, Set<String> requiredColumns) throws SQLException {
        if (!tableExists(sourceTable)) return;
        Set<String> columns = new HashSet<>();
        try (ResultSet rs = connection.getMetaData().getColumns(connection.getCatalog(), null, sourceTable, null)) {
            while (rs.next()) {
                if (sourceTable.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    columns.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        if (!columns.containsAll(requiredColumns)) {
            logger.warn("Found legacy database table '{}' with an unsupported schema; leaving it untouched.", sourceTable);
            return;
        }
        String verb = storageType.equals("sqlite") ? "INSERT OR IGNORE" : "INSERT IGNORE";
        String columnList = String.join(",", requiredColumns.stream().sorted().toList());
        try (Statement statement = connection.createStatement()) {
            int copied = statement.executeUpdate(verb + " INTO " + targetTable + "(" + columnList + ") SELECT "
                    + columnList + " FROM " + sourceTable);
            if (copied > 0) logger.info("Copied {} records from legacy table '{}' into '{}'.", copied, sourceTable, targetTable);
        }
    }

    private boolean tableExists(String tableName) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getTables(connection.getCatalog(), null, tableName, new String[] {"TABLE"})) {
            while (rs.next()) {
                if (tableName.equalsIgnoreCase(rs.getString("TABLE_NAME"))) return true;
            }
        }
        return false;
    }

    private void persistAcceptedUpdate(Record record, Message message, long occurredAt) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            upsert(record);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + SYNC_EVENTS_TABLE + "(event_id,uuid,action,nickname,source,weight,occurred_at) VALUES(?,?,?,?,?,?,?)")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, message.uuid.toString());
                ps.setString(3, message.action);
                ps.setString(4, message.nickname);
                ps.setString(5, message.source);
                ps.setInt(6, message.weight);
                ps.setLong(7, occurredAt);
                ps.executeUpdate();
            }
            pruneHistory(occurredAt - HISTORY_WINDOW_MILLIS);
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void pruneHistory(long cutoff) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + SYNC_EVENTS_TABLE + " WHERE occurred_at < ?")) {
            ps.setLong(1, cutoff);
            ps.executeUpdate();
        }
    }

    private void registerAdminCommand() {
        proxy.getCommandManager().register(
            proxy.getCommandManager().metaBuilder("nicksyncx").plugin(this).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        CommandSource source = invocation.source();
                        if (!source.hasPermission(adminPermission)) {
                            source.sendMessage(Component.text("You do not have permission to use this command."));
                            return;
                        }
                        String[] args = invocation.arguments();
                        if (args.length == 0) {
                            source.sendMessage(Component.text("Usage: /nicksyncx current [page] | /nicksyncx history [page]"));
                            return;
                        }
                        int page;
                        try {
                            long requestedPage = args.length > 1 ? Long.parseLong(args[1]) : 1;
                            if (requestedPage < 1 || requestedPage > Integer.MAX_VALUE / PAGE_SIZE) {
                                throw new NumberFormatException();
                            }
                            page = (int) requestedPage;
                        } catch (NumberFormatException e) {
                            source.sendMessage(Component.text("Page must be a positive number."));
                            return;
                        }
                        if (args[0].equalsIgnoreCase("current")) {
                            showCurrent(source, page);
                        } else if (args[0].equalsIgnoreCase("history")) {
                            showHistory(source, page);
                        } else {
                            source.sendMessage(Component.text("Usage: /nicksyncx current [page] | /nicksyncx history [page]"));
                        }
                    }
                });
    }

    private void showCurrent(CommandSource source, int page) {
        List<Record> current = records.values().stream()
                .sorted(Comparator.comparingLong(Record::timestamp).reversed())
                .toList();
        int pages = Math.max(1, (current.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int start = (page - 1) * PAGE_SIZE;
        if (start >= current.size() && !current.isEmpty()) {
            source.sendMessage(Component.text("Page " + page + " is out of range; there are " + pages + " pages."));
            return;
        }
        source.sendMessage(Component.text("Current " + brandName + " records (" + current.size() + "), page " + page + "/" + pages + ":"));
        current.stream().skip(start).limit(PAGE_SIZE).forEach(record -> {
            String playerName = proxy.getPlayer(record.uuid).map(Player::getUsername).orElse(record.uuid.toString());
            source.sendMessage(Component.text(formatRecord(playerName, record.nickname, record.source,
                    record.weight, record.timestamp)));
        });
    }

    private void showHistory(CommandSource source, int page) {
        if (!databaseReady) {
            source.sendMessage(Component.text(brandName + " database is not ready."));
            return;
        }
        databaseWorker.execute(() -> {
            long cutoff = System.currentTimeMillis() - HISTORY_WINDOW_MILLIS;
            try {
                List<SyncEvent> events = new ArrayList<>();
                long total;
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM " + SYNC_EVENTS_TABLE + " WHERE occurred_at >= ?")) {
                    count.setLong(1, cutoff);
                    try (ResultSet rs = count.executeQuery()) {
                        total = rs.next() ? rs.getLong(1) : 0;
                    }
                }
                int offset = (page - 1) * PAGE_SIZE;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT uuid,action,nickname,source,weight,occurred_at FROM " + SYNC_EVENTS_TABLE + " "
                                + "WHERE occurred_at >= ? ORDER BY occurred_at DESC LIMIT ? OFFSET ?")) {
                    query.setLong(1, cutoff);
                    query.setInt(2, PAGE_SIZE);
                    query.setInt(3, offset);
                    try (ResultSet rs = query.executeQuery()) {
                        while (rs.next()) {
                            events.add(new SyncEvent(UUID.fromString(rs.getString("uuid")), rs.getString("action"),
                                    rs.getString("nickname"), rs.getString("source"), rs.getInt("weight"),
                                    rs.getLong("occurred_at")));
                        }
                    }
                }
                long pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
                if (page > pages) {
                    source.sendMessage(Component.text("Page " + page + " is out of range; there are " + pages + " pages."));
                    return;
                }
                source.sendMessage(Component.text("Accepted syncs in the last 24 hours (" + total + "), page " + page + "/" + pages + ":"));
                for (SyncEvent event : events) {
                    String playerName = proxy.getPlayer(event.uuid).map(Player::getUsername).orElse(event.uuid.toString());
                    source.sendMessage(Component.text(formatRecord(playerName, event.nickname, event.source,
                            event.weight, event.occurredAt) + " action=" + event.action));
                }
            } catch (SQLException | IllegalArgumentException e) {
                logger.error("Could not load nickname sync history.", e);
                source.sendMessage(Component.text("Could not load sync history. Check the proxy log for details."));
            }
        });
    }

    private String formatRecord(String player, String nickname, String source, int weight, long timestamp) {
        String safeNickname = nickname.replace('\n', ' ').replace('\r', ' ');
        if (safeNickname.isEmpty()) safeNickname = "(no nickname)";
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.ofEpochMilli(timestamp))
                + " player=" + player + " nickname=" + safeNickname + " source=" + source + " weight=" + weight;
    }

    private void loadRecords() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT uuid,nickname,source,weight,updated_at FROM " + NICKNAME_TABLE)) {
            while (rs.next()) {
                try {
                    Record r = new Record(UUID.fromString(rs.getString("uuid")), rs.getString("nickname"),
                            rs.getString("source"), rs.getInt("weight"), rs.getLong("updated_at"));
                    records.put(r.uuid, r);
                } catch (IllegalArgumentException e) {
                    logger.warn("Skipping invalid UUID in nickname database.");
                }
            }
        }
    }

    private void migrateLegacyDirectory() throws IOException {
        if (!Files.isDirectory(legacyDirectory)) return;
        int copied = 0;
        try (var paths = Files.walk(legacyDirectory)) {
            for (Path source : paths.toList()) {
                if (source.equals(legacyDirectory) || Files.isSymbolicLink(source)) continue;
                Path target = directory.resolve(legacyDirectory.relativize(source)).normalize();
                if (!target.startsWith(directory.normalize())) {
                    throw new IOException("Legacy data path escapes plugins/NickSyncX");
                }
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(source) && !Files.exists(target)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                    copied++;
                }
            }
        }
        if (copied > 0) {
            logger.info("Copied {} files from plugins/LegacyNickSync to plugins/NickSyncX; the originals were kept.", copied);
        }
    }

    private void upsert(Record r) throws SQLException {
        String sql = storageType.equals("sqlite")
                ? "INSERT INTO " + NICKNAME_TABLE + "(uuid,nickname,source,weight,updated_at) VALUES(?,?,?,?,?) "
                  + "ON CONFLICT(uuid) DO UPDATE SET nickname=excluded.nickname,source=excluded.source,weight=excluded.weight,updated_at=excluded.updated_at"
                : "INSERT INTO " + NICKNAME_TABLE + "(uuid,nickname,source,weight,updated_at) VALUES(?,?,?,?,?) "
                  + "ON DUPLICATE KEY UPDATE nickname=VALUES(nickname),source=VALUES(source),weight=VALUES(weight),updated_at=VALUES(updated_at)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, r.uuid.toString());
            ps.setString(2, r.nickname);
            ps.setString(3, r.source);
            ps.setInt(4, r.weight);
            ps.setLong(5, r.timestamp);
            ps.executeUpdate();
        }
    }

    private void importLegacyFileIfNeeded() throws IOException, SQLException {
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + NICKNAME_TABLE)) {
            if (rs.next() && rs.getLong(1) > 0) return;
        }
        Path legacy = directory.resolve("nicknames.db");
        if (!Files.isRegularFile(legacy)) return;
        int imported = 0;
        for (String line : Files.readAllLines(legacy, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\\t", -1);
            if (p.length != 5) continue;
            try {
                Record r = new Record(UUID.fromString(p[0]), new String(Base64.getDecoder().decode(p[1]), StandardCharsets.UTF_8),
                        p[2], Integer.parseInt(p[3]), Long.parseLong(p[4]));
                upsert(r);
                records.put(r.uuid, r);
                imported++;
            } catch (RuntimeException ignored) { }
        }
        if (imported > 0) logger.info("Imported {} nickname records from the legacy nicknames.db file.", imported);
    }

    private Map<String, String> readSimpleYaml(Path path) throws IOException {
        Map<String, String> values = new HashMap<>();
        Deque<String> parents = new ArrayDeque<>();
        Deque<Integer> indents = new ArrayDeque<>();
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.stripTrailing();
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains(":")) continue;
            int indent = line.length() - line.stripLeading().length();
            String key = trimmed.substring(0, trimmed.indexOf(':')).trim();
            String val = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            while (!indents.isEmpty() && indent <= indents.peek()) { indents.pop(); parents.pop(); }
            if (val.isEmpty()) {
                parents.push(key);
                indents.push(indent);
            } else {
                String full = String.join(".", reversed(parents)) + (parents.isEmpty() ? "" : ".") + key;
                if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) val = val.substring(1, val.length() - 1);
                values.put(full, val);
            }
        }
        return values;
    }

    private List<String> reversed(Deque<String> deque) {
        List<String> result = new ArrayList<>(deque);
        Collections.reverse(result);
        return result;
    }

    private String value(String key, String fallback) { return config.getOrDefault(key, fallback); }

    private void closeConnection() {
        try { if (connection != null && !connection.isClosed()) connection.close(); }
        catch (SQLException e) { logger.warn("Error closing nickname database connection.", e); }
    }

    private record Record(UUID uuid, String nickname, String source, int weight, long timestamp) { }
    private record Message(String action, UUID uuid, String source, int weight, long timestamp, String nickname) { }
    private record SyncEvent(UUID uuid, String action, String nickname, String source, int weight, long occurredAt) { }
}

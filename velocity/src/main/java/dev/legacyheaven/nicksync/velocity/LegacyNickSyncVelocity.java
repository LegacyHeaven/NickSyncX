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

@Plugin(id = "nicksyncx", name = "NickSyncX", version = "1.5.0",
    description = "Synchronizes nicknames across Paper and Velocity servers.", authors = {"LegacyHeaven"},
    url = "https://legacyheaven.dev")
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
    private Path backupFile;
    private boolean debug;
    private boolean automaticBackupEnabled;
    private long backupIntervalMinutes;
    private volatile long lastBackupAt;
    private ScheduledFuture<?> automaticBackupTask;
    private final ScheduledExecutorService backupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "NickSyncX-Backup-Scheduler");
        thread.setDaemon(true);
        return thread;
    });
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
            String channelName = value("network.channel", "nicksync:sync");
            String[] parts = channelName.split(":", 2);
            if (parts.length != 2) throw new IOException("network.channel must be namespace:key");
            channel = MinecraftChannelIdentifier.create(parts[0], parts[1]);
            storageType = value("storage.type", "sqlite").toLowerCase(Locale.ROOT);
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
            backupFile = directory.resolve(value("storage.backup.file", "backups/nicksyncx.backup")).normalize();
            if (!backupFile.startsWith(directory.normalize())) {
                throw new IOException("storage.backup.file must remain inside plugins/NickSyncX");
            }
            automaticBackupEnabled = Boolean.parseBoolean(value("storage.backup.automatic", "true"));
            backupIntervalMinutes = Long.parseLong(value("storage.backup.interval-minutes", "60"));
            if (automaticBackupEnabled && backupIntervalMinutes < 1) {
                throw new IOException("storage.backup.interval-minutes must be at least 1 when automatic backups are enabled");
            }
            proxy.getChannelRegistrar().register(channel);
            initializeDatabase();
            loadRecords();
            importLegacyFileIfNeeded();
            if (Files.isRegularFile(backupFile)) {
                try {
                    lastBackupAt = Files.getLastModifiedTime(backupFile).toMillis();
                } catch (IOException e) {
                    logger.warn("Could not read the backup file timestamp.", e);
                }
            }
            databaseReady = true;
            registerAdminCommand();
            scheduleAutomaticBackups();
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
                            m.uuid, m.source, m.weight, old == null ? "none" : old.source, old == null ? "none" : old.weight);
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
        if (automaticBackupTask != null) automaticBackupTask.cancel(false);
        backupScheduler.shutdownNow();
        if (automaticBackupEnabled && connection != null) {
            try {
                databaseWorker.execute(() -> {
                    try {
                        writeBackup(backupFile, false);
                    } catch (IOException | SQLException e) {
                        logger.error("Could not create shutdown database backup.", e);
                    }
                });
            } catch (RejectedExecutionException e) {
                logger.warn("Could not queue the shutdown database backup.", e);
            }
        }
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
            String database = value("storage.mysql.database", "nicksync");
            String user = value("storage.mysql.username", "nicksync");
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
                            source.sendMessage(Component.text("Usage: /nicksyncx <current|history|health|backup|restore confirm> [page]"));
                            return;
                        }
                        if (args[0].equalsIgnoreCase("health")) {
                            queueHealth(source);
                            return;
                        }
                        if (args[0].equalsIgnoreCase("backup")) {
                            if (args.length != 1) {
                                source.sendMessage(Component.text("Usage: /nicksyncx backup"));
                                return;
                            }
                            queueBackup(source);
                            return;
                        }
                        if (args[0].equalsIgnoreCase("restore")) {
                            if (args.length != 2 || !args[1].equalsIgnoreCase("confirm")) {
                                source.sendMessage(Component.text("This replaces the active nickname data. Run /nicksyncx restore confirm to continue."));
                                return;
                            }
                            queueRestore(source);
                            return;
                        }
                        if (!args[0].equalsIgnoreCase("current") && !args[0].equalsIgnoreCase("history")) {
                            source.sendMessage(Component.text("Usage: /nicksyncx <current|history|health|backup|restore confirm> [page]"));
                            return;
                        }
                        if (args.length > 2) {
                            source.sendMessage(Component.text("Usage: /nicksyncx " + args[0].toLowerCase(Locale.ROOT) + " [page]"));
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
                        } else {
                            showHistory(source, page);
                        }
                    }
                });
    }

    private void showCurrent(CommandSource source, int page) {
        List<Record> current = records.values().stream()
            .sorted((first, second) -> Long.compare(second.timestamp(), first.timestamp()))
                .toList();
        int pages = Math.max(1, (current.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int start = (page - 1) * PAGE_SIZE;
        if (start >= current.size() && !current.isEmpty()) {
            source.sendMessage(Component.text("Page " + page + " is out of range; there are " + pages + " pages."));
            return;
        }
        source.sendMessage(Component.text("Current " + brandName + " records (" + current.size() + "), page " + page + "/" + pages + ":"));
        current.stream().skip(start).limit(PAGE_SIZE).forEach(record -> {
            String playerName = proxy.getPlayer(record.uuid).map(player -> player.getUsername()).orElse(record.uuid.toString());
            source.sendMessage(Component.text(formatRecord(playerName, record.nickname, record.source,
                    record.weight, record.timestamp)));
        });
    }

    private void scheduleAutomaticBackups() {
        if (!automaticBackupEnabled) return;
        automaticBackupTask = backupScheduler.scheduleWithFixedDelay(() -> {
            try {
                databaseWorker.execute(() -> {
                    try {
                        writeBackup(backupFile, true);
                    } catch (IOException | SQLException e) {
                        logger.error("Automatic database backup failed.", e);
                    }
                });
            } catch (RejectedExecutionException e) {
                if (databaseReady) logger.warn("Could not queue the automatic database backup.", e);
            }
        }, backupIntervalMinutes, backupIntervalMinutes, TimeUnit.MINUTES);
    }

    private void queueBackup(CommandSource source) {
        if (!databaseReady) {
            source.sendMessage(Component.text(brandName + " database is not ready."));
            return;
        }
        source.sendMessage(Component.text("Creating a database backup at " + backupFile + "..."));
        try {
            databaseWorker.execute(() -> {
                try {
                    writeBackup(backupFile, false);
                    source.sendMessage(Component.text("Database backup created: " + backupFile));
                } catch (IOException | SQLException e) {
                    logger.error("Could not create database backup.", e);
                    source.sendMessage(Component.text("Backup failed. Check the proxy log for details."));
                }
            });
        } catch (RejectedExecutionException e) {
            source.sendMessage(Component.text("Database worker is stopping; backup was not queued."));
        }
    }

    private void queueRestore(CommandSource source) {
        if (!databaseReady) {
            source.sendMessage(Component.text(brandName + " database is not ready."));
            return;
        }
        source.sendMessage(Component.text("Restoring " + backupFile + ". A pre-restore snapshot will be saved first."));
        try {
            databaseWorker.execute(() -> {
                try {
                    if (!Files.isRegularFile(backupFile)) throw new IOException("Configured backup file does not exist");
                    Path safetyBackup = backupFile.resolveSibling(backupFile.getFileName()
                            + ".pre-restore-" + System.currentTimeMillis());
                    writeBackup(safetyBackup, false);
                    restoreBackup(backupFile);
                    records.clear();
                    loadRecords();
                    for (Player player : proxy.getAllPlayers()) {
                        Record record = records.get(player.getUniqueId());
                        sendApply(player, player.getUniqueId(), record == null ? "" : record.nickname);
                    }
                    source.sendMessage(Component.text("Restore completed. Pre-restore snapshot: " + safetyBackup));
                } catch (IOException | SQLException e) {
                    logger.error("Could not restore database backup.", e);
                    source.sendMessage(Component.text("Restore failed. Check the proxy log; the current database was rolled back."));
                }
            });
        } catch (RejectedExecutionException e) {
            source.sendMessage(Component.text("Database worker is stopping; restore was not queued."));
        }
    }

    private void queueHealth(CommandSource source) {
        if (!databaseReady) {
            source.sendMessage(Component.text(brandName + " database is not ready."));
            return;
        }
        try {
            databaseWorker.execute(() -> {
                try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                        "SELECT (SELECT COUNT(*) FROM " + NICKNAME_TABLE + "), (SELECT COUNT(*) FROM " + SYNC_EVENTS_TABLE + ")")) {
                    if (!result.next()) throw new SQLException("Health query returned no result");
                    String backupState = Files.isRegularFile(backupFile)
                            ? "present, " + Files.size(backupFile) + " bytes"
                            : "missing";
                    String backupTime = lastBackupAt == 0 ? "not yet created"
                            : java.time.format.DateTimeFormatter.ISO_INSTANT.format(
                                    java.time.Instant.ofEpochMilli(lastBackupAt));
                    source.sendMessage(Component.text(brandName + " database health: OK"));
                    source.sendMessage(Component.text("Storage: " + storageType + "; nickname rows: "
                            + result.getLong(1) + "; history rows: " + result.getLong(2)));
                    source.sendMessage(Component.text("Backup: " + backupState + "; last completed: " + backupTime));
                    source.sendMessage(Component.text("Automatic backup: " + (automaticBackupEnabled
                            ? "every " + backupIntervalMinutes + " minutes" : "disabled")));
                } catch (IOException | SQLException e) {
                    logger.error("Database health check failed.", e);
                    source.sendMessage(Component.text(brandName + " database health: FAILED. Check the proxy log."));
                }
            });
        } catch (RejectedExecutionException e) {
            source.sendMessage(Component.text("Database worker is stopping; health check was not queued."));
        }
    }

    private void writeBackup(Path target, boolean automatic) throws IOException, SQLException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                writer.write("NICKSYNCX_BACKUP\t1");
                writer.newLine();
                writer.write("META\t" + System.currentTimeMillis() + "\t" + storageType);
                writer.newLine();
                try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                        "SELECT uuid,nickname,source,weight,updated_at FROM " + NICKNAME_TABLE)) {
                    while (rows.next()) {
                        writer.write(String.join("\t", "N", rows.getString("uuid"), encode(rows.getString("nickname")),
                                encode(rows.getString("source")), Integer.toString(rows.getInt("weight")),
                                Long.toString(rows.getLong("updated_at"))));
                        writer.newLine();
                    }
                }
                try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                        "SELECT event_id,uuid,action,nickname,source,weight,occurred_at FROM " + SYNC_EVENTS_TABLE)) {
                    while (rows.next()) {
                        writer.write(String.join("\t", "E", rows.getString("event_id"), rows.getString("uuid"),
                                encode(rows.getString("action")), encode(rows.getString("nickname")),
                                encode(rows.getString("source")), Integer.toString(rows.getInt("weight")),
                                Long.toString(rows.getLong("occurred_at"))));
                        writer.newLine();
                    }
                }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (target.equals(backupFile)) lastBackupAt = System.currentTimeMillis();
            if (automatic) logger.info("Automatic database backup completed: {}", target);
        } catch (IOException | SQLException e) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw e;
        }
    }

    private void restoreBackup(Path backup) throws IOException, SQLException {
        long backupTimestamp = Files.getLastModifiedTime(backup).toMillis();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (BufferedReader reader = Files.newBufferedReader(backup, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (!"NICKSYNCX_BACKUP\t1".equals(header)) throw new IOException("Unsupported backup format");
            String metadata = reader.readLine();
            if (metadata == null || !metadata.startsWith("META\t")) throw new IOException("Backup metadata is missing");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM " + NICKNAME_TABLE);
                statement.executeUpdate("DELETE FROM " + SYNC_EVENTS_TABLE);
            }
            try (PreparedStatement nicknameInsert = connection.prepareStatement(
                    "INSERT INTO " + NICKNAME_TABLE + "(uuid,nickname,source,weight,updated_at) VALUES(?,?,?,?,?)");
                 PreparedStatement eventInsert = connection.prepareStatement(
                    "INSERT INTO " + SYNC_EVENTS_TABLE + "(event_id,uuid,action,nickname,source,weight,occurred_at) VALUES(?,?,?,?,?,?,?)")) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.split("\\t", -1);
                    if (fields[0].equals("N") && fields.length == 6) {
                        UUID.fromString(fields[1]);
                        nicknameInsert.setString(1, fields[1]);
                        nicknameInsert.setString(2, decode(fields[2]));
                        nicknameInsert.setString(3, decode(fields[3]));
                        nicknameInsert.setInt(4, Integer.parseInt(fields[4]));
                        nicknameInsert.setLong(5, Long.parseLong(fields[5]));
                        nicknameInsert.executeUpdate();
                    } else if (fields[0].equals("E") && fields.length == 8) {
                        UUID.fromString(fields[1]);
                        UUID.fromString(fields[2]);
                        eventInsert.setString(1, fields[1]);
                        eventInsert.setString(2, fields[2]);
                        eventInsert.setString(3, decode(fields[3]));
                        eventInsert.setString(4, decode(fields[4]));
                        eventInsert.setString(5, decode(fields[5]));
                        eventInsert.setInt(6, Integer.parseInt(fields[6]));
                        eventInsert.setLong(7, Long.parseLong(fields[7]));
                        eventInsert.executeUpdate();
                    } else {
                        throw new IOException("Backup contains an invalid row");
                    }
                }
            }
            connection.commit();
            lastBackupAt = backupTimestamp;
        } catch (RuntimeException | IOException | SQLException e) {
            connection.rollback();
            if (e instanceof IOException ioException) throw ioException;
            if (e instanceof SQLException sqlException) throw sqlException;
            throw new IOException("Backup contains invalid data", e);
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
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
                    String playerName = proxy.getPlayer(event.uuid).map(player -> player.getUsername()).orElse(event.uuid.toString());
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

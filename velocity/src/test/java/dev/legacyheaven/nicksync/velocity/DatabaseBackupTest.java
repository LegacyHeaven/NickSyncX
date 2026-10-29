package dev.legacyheaven.nicksync.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DatabaseBackupTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void backupRestoresNicknameAndHistoryTextExactly() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("nicksync.sqlite");
        Path backupFile = temporaryDirectory.resolve("nicksync.backup");
        UUID playerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String nickname = "Line one\tLine two\nLine three";
        String source = "hub\tmain";

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE nicksyncx (uuid VARCHAR(36) PRIMARY KEY, nickname TEXT NOT NULL, source VARCHAR(128) NOT NULL, weight INTEGER NOT NULL, updated_at BIGINT NOT NULL)");
                statement.executeUpdate("CREATE TABLE nicksyncx_sync_events (event_id VARCHAR(36) PRIMARY KEY, uuid VARCHAR(36) NOT NULL, action VARCHAR(16) NOT NULL, nickname TEXT NOT NULL, source VARCHAR(128) NOT NULL, weight INTEGER NOT NULL, occurred_at BIGINT NOT NULL)");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO nicksyncx(uuid,nickname,source,weight,updated_at) VALUES(?,?,?,?,?)")) {
                insert.setString(1, playerId.toString());
                insert.setString(2, nickname);
                insert.setString(3, source);
                insert.setInt(4, 7);
                insert.setLong(5, 1000L);
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO nicksyncx_sync_events(event_id,uuid,action,nickname,source,weight,occurred_at) VALUES(?,?,?,?,?,?,?)")) {
                insert.setString(1, eventId.toString());
                insert.setString(2, playerId.toString());
                insert.setString(3, "SYNC");
                insert.setString(4, nickname);
                insert.setString(5, source);
                insert.setInt(6, 7);
                insert.setLong(7, 1000L);
                insert.executeUpdate();
            }

            LegacyNickSyncVelocity plugin = new LegacyNickSyncVelocity(null, null);
            setField(plugin, "connection", connection);
            setField(plugin, "storageType", "sqlite");
            setField(plugin, "backupFile", backupFile);

            Method writeBackup = LegacyNickSyncVelocity.class.getDeclaredMethod("writeBackup", Path.class, boolean.class);
            writeBackup.setAccessible(true);
            writeBackup.invoke(plugin, backupFile, false);

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM nicksyncx");
                statement.executeUpdate("DELETE FROM nicksyncx_sync_events");
            }

            Method restoreBackup = LegacyNickSyncVelocity.class.getDeclaredMethod("restoreBackup", Path.class);
            restoreBackup.setAccessible(true);
            restoreBackup.invoke(plugin, backupFile);

            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT nickname,source,weight,updated_at FROM nicksyncx WHERE uuid='" + playerId + "'")) {
                result.next();
                assertEquals(nickname, result.getString("nickname"));
                assertEquals(source, result.getString("source"));
                assertEquals(7, result.getInt("weight"));
                assertEquals(1000L, result.getLong("updated_at"));
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM nicksyncx_sync_events WHERE event_id='" + eventId + "'")) {
                result.next();
                assertEquals(1L, result.getLong(1));
            }
        }
    }

    @Test
    void invalidBackupLeavesCurrentDataUntouched() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("nicksync.sqlite");
        Path backupFile = temporaryDirectory.resolve("invalid.backup");
        UUID playerId = UUID.randomUUID();
        Files.writeString(backupFile, "not a NickSyncX backup");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile)) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE nicksyncx (uuid VARCHAR(36) PRIMARY KEY, nickname TEXT NOT NULL, source VARCHAR(128) NOT NULL, weight INTEGER NOT NULL, updated_at BIGINT NOT NULL)");
                statement.executeUpdate("CREATE TABLE nicksyncx_sync_events (event_id VARCHAR(36) PRIMARY KEY, uuid VARCHAR(36) NOT NULL, action VARCHAR(16) NOT NULL, nickname TEXT NOT NULL, source VARCHAR(128) NOT NULL, weight INTEGER NOT NULL, occurred_at BIGINT NOT NULL)");
                statement.executeUpdate("INSERT INTO nicksyncx VALUES ('" + playerId + "', 'keep me', 'hub', 2, 1000)");
            }
            LegacyNickSyncVelocity plugin = new LegacyNickSyncVelocity(null, null);
            setField(plugin, "connection", connection);
            Method restoreBackup = LegacyNickSyncVelocity.class.getDeclaredMethod("restoreBackup", Path.class);
            restoreBackup.setAccessible(true);

            assertThrows(InvocationTargetException.class, () -> restoreBackup.invoke(plugin, backupFile));

            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT nickname FROM nicksyncx WHERE uuid='" + playerId + "'")) {
                result.next();
                assertEquals("keep me", result.getString(1));
            }
        }
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
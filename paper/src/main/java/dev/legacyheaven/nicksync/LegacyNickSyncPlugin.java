package dev.legacyheaven.nicksync;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class LegacyNickSyncPlugin extends JavaPlugin implements Listener, PluginMessageListener {
    private final Map<UUID, String> lastSeen = new ConcurrentHashMap<>();
    private final Map<UUID, String> suppress = new ConcurrentHashMap<>();
    private final Map<UUID, String> migrationQueue = new LinkedHashMap<>();

    private String channel;
    private String brandName = "NickSyncX";
    private String serverName;
    private int weight;
    private boolean migrationEnabled;
    private boolean debug;
    private boolean essentialsHookEnabled;
    private long pollIntervalTicks;
    private final Set<String> warningsIssued = ConcurrentHashMap.newKeySet();

    private Plugin essentials;

    @Override
    public void onEnable() {
        Path oldConfig = getDataFolder().toPath().resolveSibling("LegacyNickSync").resolve("config.yml");
        Path newConfig = getDataFolder().toPath().resolve("config.yml");
        try {
            if (!Files.exists(newConfig) && Files.isRegularFile(oldConfig)) {
                Files.createDirectories(getDataFolder().toPath());
                Files.copy(oldConfig, newConfig);
                getLogger().info("Migrated config.yml from the LegacyNickSync data folder.");
            }
        } catch (IOException e) {
            getLogger().severe("Could not migrate the previous LegacyNickSync config: " + e.getMessage());
        }
        saveDefaultConfig();
        String configuredPermission = getConfig().getString("commands.permission", "nicksyncx.sync");
        if ("legacynicksync.sync".equalsIgnoreCase(configuredPermission)) {
            getConfig().set("commands.permission", "nicksyncx.sync");
            saveConfig();
            getLogger().info("Updated the default permission to nicksyncx.sync.");
        }
        brandName = getConfig().getString("branding.display-name", "NickSyncX");
        if (brandName == null || brandName.isBlank()) brandName = "NickSyncX";
        channel = getConfig().getString("network.channel", "legacyheaven:nicksync");
        serverName = getConfig().getString("server.name", "change-me");
        weight = getConfig().getInt("server.weight", 0);
        migrationEnabled = getConfig().getBoolean("migration.enabled", false);
        debug = getConfig().getBoolean("logging.debug", false);
        essentialsHookEnabled = getConfig().getBoolean("essentialsx.hook", true);
        long configuredPollInterval = getConfig().getLong("performance.poll-interval-ticks", 6000L);
        if (configuredPollInterval == 20L) {
            configuredPollInterval = 6000L;
            getConfig().set("performance.poll-interval-ticks", configuredPollInterval);
            saveConfig();
            getLogger().info("Updated the default nickname polling interval to 6000 ticks (5 minutes).");
        }
        pollIntervalTicks = Math.max(1L, configuredPollInterval);

        Bukkit.getMessenger().registerOutgoingPluginChannel(this, channel);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, channel, this);
        Bukkit.getPluginManager().registerEvents(this, this);
        hookEssentials();
        Objects.requireNonNull(getCommand("nicksync"), "nicksync command missing from plugin.yml").setExecutor(this::onCommand);

        Bukkit.getScheduler().runTaskTimer(this, this::poll, pollIntervalTicks, pollIntervalTicks);
        if (migrationEnabled) Bukkit.getScheduler().runTaskLater(this, this::migrateAtStartup, 40L);

        getLogger().info(brandName + " enabled on " + serverName + " with weight " + weight + ".");
    }

    private void hookEssentials() {
        if (!essentialsHookEnabled) {
            essentials = null;
            return;
        }
        Plugin p = Bukkit.getPluginManager().getPlugin("Essentials");
        essentials = p != null && p.isEnabled() ? p : null;
    }

    private Object user(Player player) {
        hookEssentials();
        if (essentials == null) return null;
        try {
            return essentials.getClass().getMethod("getUser", Player.class).invoke(essentials, player);
        } catch (ReflectiveOperationException e) {
            warnOnce("EssentialsX getUser API unavailable: " + e.getMessage());
            return null;
        }
    }

    private String nickname(Player player) {
        Object user = user(player);
        if (user == null) return null;
        try {
            Object value = user.getClass().getMethod("getNickname").invoke(user);
            return value == null ? "" : value.toString();
        } catch (ReflectiveOperationException e) {
            warnOnce("EssentialsX getNickname API unavailable: " + e.getMessage());
            return null;
        }
    }

    private boolean applyNickname(Player player, String value) {
        Object user = user(player);
        if (user == null) return false;
        try {
            user.getClass().getMethod("setNickname", String.class)
                    .invoke(user, value.isEmpty() ? null : value);
            return true;
        } catch (ReflectiveOperationException e) {
            warnOnce("EssentialsX setNickname API unavailable: " + e.getMessage());
            return false;
        }
    }

    private void warnOnce(String message) {
        if (warningsIssued.add(message)) {
            getLogger().warning(message);
        }
    }

    private void poll() {
        if (essentials == null) hookEssentials();
        if (essentials == null) return;

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            String current = nickname(p);
            if (current == null) continue;

            String expected = suppress.get(id);
            if (expected != null) {
                if (expected.equals(current)) {
                    lastSeen.put(id, current);
                    suppress.remove(id);
                    continue;
                }
                suppress.remove(id);
            }

            String previous = lastSeen.put(id, current);
            if (previous != null && !previous.equals(current)) sendSet(p, id, current);
        }
    }


    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean force = args.length > 0 && args[0].equalsIgnoreCase("force");
        if (args.length > 0 && !args[0].equalsIgnoreCase("sync") && !force) {
            sender.sendMessage("§d" + brandName + " §7| §f/nicksync [sync [player]|force]");
            return true;
        }
        if ((force && args.length != 1) || (!force && args.length > 2)) {
            sender.sendMessage("§cUsage: /nicksync [sync [player]|force]");
            return true;
        }
        String permission = getConfig().getString("commands.permission", "nicksyncx.sync");
        if (!sender.hasPermission(permission)) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }
        Player target;
        if (force && sender instanceof Player player) {
            target = player;
        } else if (force) {
            sender.sendMessage("§cOnly a player can force their own nickname.");
            return true;
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("sync")) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§cThat player must be online on this server.");
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage("§cUsage: /nicksync sync <player>");
            return true;
        }
        String current = nickname(target);
        if (current == null) {
            sender.sendMessage("§cCould not read the current nickname. Is EssentialsX enabled and compatible?");
            return true;
        }
        String action = force ? "FORCE" : "SYNC";
        sendMessage(target, action, target.getUniqueId(), current);
        lastSeen.put(target.getUniqueId(), current);
        sender.sendMessage("§d" + brandName + " §7| §f" + (force ? "Force request sent" : "Sync request sent")
            + " for §d" + target.getName() + "§f. Nickname: §d" + (current.isEmpty() ? "(no nickname)" : current)
            + (force ? "§f. This overrides the current network priority." : "§f. The proxy saves it only if this server has sufficient priority."));
        return true;
    }

    private void sendSet(Player carrier, UUID id, String nick) {
        sendMessage(carrier, "SET", id, nick);
    }

    private void sendMessage(Player carrier, String action, UUID id, String nick) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(action);
            out.writeUTF(id.toString());
            out.writeUTF(serverName);
            out.writeInt(weight);
            out.writeLong(System.currentTimeMillis());
            out.writeUTF(nick == null ? "" : nick);
            carrier.sendPluginMessage(this, channel, bytes.toByteArray());
        } catch (IOException e) {
            getLogger().warning("Could not send " + action + " message: " + e.getMessage());
        }
    }

    @Override
    public void onPluginMessageReceived(String incoming, Player carrier, byte[] bytes) {
        if (!channel.equals(incoming)) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String action = in.readUTF();
            if ("NOTICE".equals(action)) {
                carrier.sendMessage("§c" + in.readUTF());
                return;
            }
            if (!"APPLY".equals(action)) return;
            UUID id = UUID.fromString(in.readUTF());
            String nick = in.readUTF();
            Player target = Bukkit.getPlayer(id);
            if (target == null) return;
            if (applyNickname(target, nick)) {
                suppress.put(id, nick);
                lastSeen.put(id, nick);
                if (debug) getLogger().info("Applied network nickname to " + id);
            }
        } catch (Exception e) {
            getLogger().warning("Ignored malformed nickname message: " + e.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String nick = nickname(player);
        if (nick != null) lastSeen.put(player.getUniqueId(), nick);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) syncIfNicknameChanged(player);
        }, 20L);
        Bukkit.getScheduler().runTaskLater(this, this::flushMigration, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        syncIfNicknameChanged(player);
        UUID id = player.getUniqueId();
        lastSeen.remove(id);
        suppress.remove(id);
    }

    private void syncIfNicknameChanged(Player player) {
        UUID id = player.getUniqueId();
        String current = nickname(player);
        if (current == null) return;
        String previous = lastSeen.put(id, current);
        if (previous == null || !previous.equals(current)) sendSet(player, id, current);
    }

    private Path userdataDirectory() {
        hookEssentials();
        if (essentials == null) return null;
        try {
            File folder = (File) essentials.getClass().getMethod("getDataFolder").invoke(essentials);
            return folder.toPath().resolve("userdata");
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private void migrateAtStartup() {
        Path dir = userdataDirectory();
        if (dir == null || !Files.isDirectory(dir)) {
            getLogger().warning("Startup migration skipped: EssentialsX userdata directory not found.");
            return;
        }

        int scanned = 0, found = 0;
        try (var paths = Files.list(dir)) {
            for (Path file : paths.filter(p -> p.getFileName().toString().endsWith(".yml")).toList()) {
                scanned++;
                String filename = file.getFileName().toString();
                UUID id;
                try {
                    id = UUID.fromString(filename.substring(0, filename.length() - 4));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                String nick = readNicknameLine(file);
                if (nick == null || nick.isBlank()) continue;
                synchronized (migrationQueue) { migrationQueue.put(id, nick); }
                found++;
            }
        } catch (IOException e) {
            getLogger().warning("Migration scan failed: " + e.getMessage());
            return;
        }
        getLogger().info("Startup migration scanned " + scanned + " files; found " + found + " nicknames.");
        flushMigration();
    }

    private String readNicknameLine(Path file) {
        try {
            for (String line : Files.readAllLines(file)) {
                String s = line.trim();
                if (!s.startsWith("nickname:")) continue;
                String value = s.substring("nickname:".length()).trim();
                if (value.equals("~") || value.equalsIgnoreCase("null") || value.isEmpty()) return null;
                if (value.length() >= 2 &&
                    ((value.startsWith("'") && value.endsWith("'")) ||
                     (value.startsWith("\"") && value.endsWith("\"")))) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        } catch (IOException e) {
            if (debug) getLogger().warning("Could not read " + file + ": " + e.getMessage());
        }
        return null;
    }

    private void flushMigration() {
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (carrier == null) return;
        Map<UUID, String> copy;
        synchronized (migrationQueue) {
            if (migrationQueue.isEmpty()) return;
            copy = new LinkedHashMap<>(migrationQueue);
            migrationQueue.clear();
        }
        copy.forEach((id, nick) -> sendMessage(carrier, "MIGRATE", id, nick));
    }

    @Override
    public void onDisable() {
        if (channel != null) {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(this, channel, this);
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(this, channel);
        }
    }
}

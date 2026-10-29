package dev.legacyheaven.nicksync;

import dev.legacyheaven.nicksync.api.NickSyncNicknameProvider;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

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
    private long pollIntervalTicks;
    private final Set<String> warningsIssued = ConcurrentHashMap.newKeySet();
    private final List<HookSpec> hookSpecs = new ArrayList<>();
    private Object placeholderExpansion;

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
        channel = getConfig().getString("network.channel", "nicksync:sync");
        if (channel == null || channel.isBlank()) channel = "nicksync:sync";
        serverName = getConfig().getString("server.name", "server-1");
        if (serverName == null || serverName.isBlank()) serverName = "server-1";
        weight = getConfig().getInt("server.weight", 0);
        migrationEnabled = getConfig().getBoolean("migration.enabled", false);
        debug = getConfig().getBoolean("logging.debug", false);
        loadHookSpecs();
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
        org.bukkit.command.PluginCommand command = getCommand("nicksync");
        if (command != null) {
            command.setExecutor(this::onCommand);
            command.setTabCompleter((sender, registeredCommand, alias, args) -> commandSuggestions(args));
        }
        else if (!registerPaperCommand()) getLogger().severe("Could not register /nicksync. Check Paper command support and the plugin descriptor.");
        registerPlaceholderExpansion();

        Bukkit.getScheduler().runTaskTimer(this, this::poll, pollIntervalTicks, pollIntervalTicks);
        if (migrationEnabled) Bukkit.getScheduler().runTaskLater(this, this::migrateAtStartup, 40L);

        logStartup();
    }

    private void loadHookSpecs() {
        boolean essentialsEnabled = getConfig().getBoolean("hooks.essentialsx.enabled",
                getConfig().getBoolean("essentialsx.hook", true));
        hookSpecs.clear();
        hookSpecs.add(new HookSpec("EssentialsX", essentialsEnabled,
                getConfig().getString("hooks.essentialsx.plugin-name", "Essentials"),
                "getUser", "getNickname", "setNickname", null));
        hookSpecs.add(new HookSpec("CMI", getConfig().getBoolean("hooks.cmi.enabled", false),
                getConfig().getString("hooks.cmi.plugin-name", "CMI"),
                "getUser", "getNickName", "setNickName", "com.Zrips.CMI.Containers.CMIUser"));
        hookSpecs.add(new HookSpec("Custom", getConfig().getBoolean("hooks.custom.enabled", false),
                getConfig().getString("hooks.custom.plugin-name", ""),
                getConfig().getString("hooks.custom.user-lookup-method", "getUser"),
                getConfig().getString("hooks.custom.nickname-getter", "getNickname"),
                getConfig().getString("hooks.custom.nickname-setter", "setNickname"),
                getConfig().getString("hooks.custom.user-class", "")));
    }

    private HookAccess hook(Player player) {
        if (getConfig().getBoolean("hooks.api.enabled", true)) {
            RegisteredServiceProvider<NickSyncNicknameProvider> registration =
                    Bukkit.getServicesManager().getRegistration(NickSyncNicknameProvider.class);
            if (registration != null && registration.getPlugin().isEnabled()) {
                HookSpec apiSpec = new HookSpec("API: " + registration.getPlugin().getName(), true,
                        registration.getPlugin().getName(), "", "", "", "");
                return new HookAccess(apiSpec, registration.getProvider());
            }
        }
        for (HookSpec spec : hookSpecs) {
            if (!spec.enabled || spec.pluginName == null || spec.pluginName.isBlank()) continue;
            Plugin plugin = Bukkit.getPluginManager().getPlugin(spec.pluginName);
            if (plugin == null || !plugin.isEnabled()) continue;
            try {
                if (spec.userClass != null && !spec.userClass.isBlank()) {
                    Class<?> userType = Class.forName(spec.userClass, true, plugin.getClass().getClassLoader());
                    Method lookup = userType.getMethod("getUser", Player.class);
                    if (java.lang.reflect.Modifier.isStatic(lookup.getModifiers())) {
                        Object result = lookup.invoke(null, player);
                        if (supportsNicknameApi(result, spec)) return new HookAccess(spec, result);
                        continue;
                    }
                }
                Object result = plugin.getClass().getMethod(spec.lookupMethod, Player.class).invoke(plugin, player);
                if (supportsNicknameApi(result, spec)) return new HookAccess(spec, result);
            } catch (ReflectiveOperationException | LinkageError e) {
                warnOnce(spec.name + " nickname API unavailable: " + e.getMessage());
            }
        }
        return null;
    }

    private boolean supportsNicknameApi(Object user, HookSpec spec) {
        if (user == null) return false;
        try {
            user.getClass().getMethod(spec.nicknameGetter);
            try {
                user.getClass().getMethod(spec.nicknameSetter, String.class, boolean.class);
            } catch (NoSuchMethodException ignored) {
                user.getClass().getMethod(spec.nicknameSetter, String.class);
            }
            return true;
        } catch (ReflectiveOperationException e) {
            warnOnce(spec.name + " does not expose the configured nickname methods.");
            return false;
        }
    }

    private String nickname(Player player) {
        HookAccess access = hook(player);
        if (access == null) return null;
        try {
            if (access.user instanceof NickSyncNicknameProvider provider) {
                String value = provider.getNickname(player);
                return value == null ? "" : value;
            }
            Object value = access.user.getClass().getMethod(access.spec.nicknameGetter).invoke(access.user);
            return value == null ? "" : value.toString();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnOnce(access.spec.name + " nickname getter unavailable: " + e.getMessage());
            return null;
        }
    }

    private boolean applyNickname(Player player, String value) {
        HookAccess access = hook(player);
        if (access == null) return false;
        try {
            if (access.user instanceof NickSyncNicknameProvider provider) {
                provider.setNickname(player, value.isEmpty() ? null : value);
                return true;
            }
            Object nickname = value.isEmpty() ? null : value;
            try {
                access.user.getClass().getMethod(access.spec.nicknameSetter, String.class, boolean.class)
                        .invoke(access.user, nickname, true);
            } catch (NoSuchMethodException ignored) {
                access.user.getClass().getMethod(access.spec.nicknameSetter, String.class)
                        .invoke(access.user, nickname);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnOnce(access.spec.name + " nickname setter unavailable: " + e.getMessage());
            return false;
        }
    }

    private void registerPlaceholderExpansion() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        try {
            Class<?> expansionClass = Class.forName(getClass().getPackageName() + ".NickSyncPlaceholderExpansion");
            Object expansion = expansionClass.getConstructor(LegacyNickSyncPlugin.class).newInstance(this);
            if (Boolean.TRUE.equals(expansionClass.getMethod("register").invoke(expansion))) {
                placeholderExpansion = expansion;
            } else {
                warnOnce("PlaceholderAPI expansion registration failed.");
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            warnOnce("Could not register the PlaceholderAPI expansion: " + e.getMessage());
        }
    }

    public String placeholderName(OfflinePlayer player) {
        if (player == null) return "";
        Player online = Bukkit.getPlayer(player.getUniqueId());
        String current = online == null ? null : nickname(online);
        return current == null || current.isBlank() ? Objects.toString(player.getName(), "") : current;
    }

    private void logStartup() {
        String hooks = hookSpecs.stream().filter(spec -> spec.enabled)
            .map(spec -> spec.name + (spec.pluginName != null
                && Bukkit.getPluginManager().getPlugin(spec.pluginName) != null ? " on" : " waiting"))
            .collect(java.util.stream.Collectors.joining(", "));
        if (hooks.isEmpty()) hooks = "none enabled";
        getLogger().info("+------------------------------------------+");
        getLogger().info("| NickSyncX  |  Network nickname sync      |");
        getLogger().info("| Backend: " + serverName + "  |  Priority: " + weight);
        getLogger().info("| Nickname hooks: " + hooks);
        getLogger().info("| Website: https://legacyheaven.dev");
        getLogger().info("+------------------------------------------+");
    }

    private boolean registerPaperCommand() {
        try {
            Object manager = getClass().getMethod("getLifecycleManager").invoke(this);
            ClassLoader loader = getClass().getClassLoader();
            Class<?> events = Class.forName("io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents", true, loader);
            Object commandEvent = events.getField("COMMANDS").get(null);
                Class<?> lifecycleEventType = Class.forName(
                    "io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventType", true, loader);
            Class<?> eventHandlerType = Class.forName(
                    "io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler", true, loader);
            Object eventHandler = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {eventHandlerType},
                    (proxy, method, args) -> {
                        if (method.getName().equals("run")) registerPaperBasicCommand(args[0]);
                        return null;
                    });
            Class<?> managerType = Class.forName("io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager", true, loader);
            Method register = managerType.getMethod("registerEventHandler", lifecycleEventType, eventHandlerType);
            register.invoke(manager, commandEvent, eventHandler);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            warnOnce("Paper command API is unavailable: " + e.getMessage());
            return false;
        }
    }

    private void registerPaperBasicCommand(Object event) {
        try {
            ClassLoader loader = getClass().getClassLoader();
            Class<?> eventType = Class.forName(
                    "io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent", true, loader);
            Object registrar = eventType.getMethod("registrar").invoke(event);
            Class<?> basicCommandType = Class.forName("io.papermc.paper.command.brigadier.BasicCommand", true, loader);
            Class<?> commandSourceType = Class.forName("io.papermc.paper.command.brigadier.CommandSourceStack", true, loader);
            Object basicCommand = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {basicCommandType},
                    (proxy, method, args) -> {
                        if (method.getName().equals("execute")) {
                            CommandSender sender = (CommandSender) commandSourceType.getMethod("getSender").invoke(args[0]);
                            onCommand(sender, null, "nicksync", (String[]) args[1]);
                        } else if (method.getName().equals("suggest")) {
                            return commandSuggestions((String[]) args[1]);
                        } else if (method.getName().equals("toString")) {
                            return "NickSyncX BasicCommand";
                        } else if (method.getName().equals("hashCode")) {
                            return System.identityHashCode(proxy);
                        } else if (method.getName().equals("equals")) {
                            return proxy == args[0];
                        }
                        return null;
                    });
            Class<?> commandsType = Class.forName("io.papermc.paper.command.brigadier.Commands", true, loader);
            Method register = commandsType.getMethod("register", String.class, basicCommandType);
            register.invoke(registrar, "nicksync", basicCommand);
            register.invoke(registrar, "lns", basicCommand);
        } catch (ReflectiveOperationException | LinkageError e) {
            warnOnce("Could not register the Paper /nicksync command: " + e.getMessage());
        }
    }

    private List<String> commandSuggestions(String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("sync", "force", "status").stream()
                    .filter(option -> option.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sync")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(player -> player.getName())
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
        }
        return List.of();
    }

    private void warnOnce(String message) {
        if (warningsIssued.add(message)) {
            getLogger().warning(message);
        }
    }

    private void poll() {
        if (hookSpecs.stream().noneMatch(spec -> spec.enabled)) return;

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
        String permission = getConfig().getString("commands.permission", "nicksyncx.sync");
        if (!sender.hasPermission(permission)) {
            reply(sender, "no-permission");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            reply(sender, "status-header");
            String status = providerStatus();
            for (String line : status.split("\\n")) sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&7" + line));
            return true;
        }
        boolean force = args.length > 0 && args[0].equalsIgnoreCase("force");
        if (args.length > 0 && !args[0].equalsIgnoreCase("sync") && !force) {
            reply(sender, "usage");
            return true;
        }
        if ((force && args.length != 1) || (!force && args.length > 2)) {
            reply(sender, "usage");
            return true;
        }
        Player target;
        if (force && sender instanceof Player player) {
            target = player;
        } else if (force) {
            reply(sender, "player-only");
            return true;
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("sync")) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                reply(sender, "player-not-online");
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            reply(sender, "usage-console");
            return true;
        }
        String current = nickname(target);
        if (current == null) {
            reply(sender, "nickname-unavailable");
            return true;
        }
        String action = force ? "FORCE" : "SYNC";
        sendMessage(target, action, target.getUniqueId(), current);
        lastSeen.put(target.getUniqueId(), current);
        reply(sender, force ? "force-sent" : "sync-sent", Map.of(
                "player", target.getName(),
                "nickname", current.isEmpty() ? getConfig().getString("messages.no-nickname", "(no nickname)") : current));
        return true;
    }

    private String providerStatus() {
        List<String> statuses = new ArrayList<>();
        String selectedProvider = null;
        if (getConfig().getBoolean("hooks.api.enabled", true)) {
            RegisteredServiceProvider<NickSyncNicknameProvider> registration =
                    Bukkit.getServicesManager().getRegistration(NickSyncNicknameProvider.class);
            if (registration != null && registration.getPlugin().isEnabled()) {
                selectedProvider = "API: " + registration.getPlugin().getName();
                statuses.add(selectedProvider + " (selected)");
            } else {
                statuses.add("NickSync API (no provider registered)");
            }
        } else {
            statuses.add("NickSync API (disabled)");
        }
        for (HookSpec spec : hookSpecs) {
            if (!spec.enabled) continue;
            Plugin plugin = spec.pluginName == null ? null : Bukkit.getPluginManager().getPlugin(spec.pluginName);
            String state;
            if (plugin == null) state = "not installed";
            else if (!plugin.isEnabled()) state = "disabled";
            else if (!providerApiAvailable(spec, plugin)) state = "API not compatible";
            else {
                state = selectedProvider == null ? "selected" : "available fallback";
                if (selectedProvider == null) selectedProvider = spec.name;
            }
            statuses.add(spec.name + " (" + state + ")");
        }
        if (statuses.isEmpty()) statuses.add("No nickname providers are enabled.");
        statuses.add("Selected provider: " + Objects.toString(selectedProvider, "none"));
        statuses.add("PlaceholderAPI: " + (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null ? "available" : "not installed"));
        statuses.add("Channel: " + channel);
        return String.join("\n", statuses);
    }

    private boolean providerApiAvailable(HookSpec spec, Plugin plugin) {
        try {
            Class<?> userType;
            if (spec.userClass != null && !spec.userClass.isBlank()) {
                Class<?> configuredType = Class.forName(spec.userClass, true, plugin.getClass().getClassLoader());
                Method lookup = configuredType.getMethod("getUser", Player.class);
                if (!java.lang.reflect.Modifier.isStatic(lookup.getModifiers())) return false;
                userType = lookup.getReturnType();
            } else {
                userType = plugin.getClass().getMethod(spec.lookupMethod, Player.class).getReturnType();
            }
            userType.getMethod(spec.nicknameGetter);
            try {
                userType.getMethod(spec.nicknameSetter, String.class, boolean.class);
            } catch (NoSuchMethodException ignored) {
                userType.getMethod(spec.nicknameSetter, String.class);
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private void reply(CommandSender sender, String key) {
        reply(sender, key, Map.of());
    }

    private void reply(CommandSender sender, String key, Map<String, String> placeholders) {
        String prefix = getConfig().getString("messages.prefix", "&d" + brandName + " &8| &f");
        String content = getConfig().getString("messages." + key, "&cMessage not configured: " + key);
        String result = prefix + content;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(result));
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
                String prefix = getConfig().getString("messages.notice-prefix", "&c");
                carrier.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + in.readUTF()));
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
        HookSpec essentials = hookSpecs.stream().filter(spec -> spec.name.equals("EssentialsX") && spec.enabled)
                .findFirst().orElse(null);
        Plugin plugin = essentials == null || essentials.pluginName == null
                ? null : Bukkit.getPluginManager().getPlugin(essentials.pluginName);
        return plugin == null || !plugin.isEnabled() ? null : plugin.getDataFolder().toPath().resolve("userdata");
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
        if (placeholderExpansion != null) {
            try {
                placeholderExpansion.getClass().getMethod("unregister").invoke(placeholderExpansion);
            } catch (ReflectiveOperationException e) {
                getLogger().warning("Could not unregister the PlaceholderAPI expansion: " + e.getMessage());
            }
        }
        if (channel != null) {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(this, channel, this);
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(this, channel);
        }
    }

    private record HookSpec(String name, boolean enabled, String pluginName, String lookupMethod,
                            String nicknameGetter, String nicknameSetter, String userClass) { }
    private record HookAccess(HookSpec spec, Object user) { }
}

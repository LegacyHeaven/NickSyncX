# NickSyncX

NickSyncX keeps a player's nickname consistent as they move between Paper servers. Each backend reads and applies nicknames through a configured nickname plugin; Velocity decides which update wins and stores the network's current value.

Project site: [legacyheaven.dev](https://legacyheaven.dev)

## Downloads

Run **Build NickSyncX** from the repository's GitHub Actions tab and download the completed workflow artifact. It contains the Paper and Velocity plugins plus the API JAR for developers. Install the Paper and Velocity JARs on their respective servers; the API JAR is a compile-time dependency, not a plugin to install.

## Compatibility

- Paper 1.20.x, 1.21.x, 26.x, and later releases using the Bukkit/Paper APIs used by the plugin.
- Paper uses `paper-plugin.yml` where Paper's plugin loader is available; `plugin.yml` remains in the JAR for older Paper and Bukkit-style loaders.
- Velocity 3.4 or newer. NickSyncX does not pin itself to a particular Minecraft protocol version; use a Velocity release that supports your network's clients and backends.
- Use the Java version required by your server. Building locally requires JDK 21 or newer.

Paper's newer plugin loader is experimental. The dual descriptor lets older Paper releases load the Bukkit-compatible entry point instead.

## Install

1. Put the Paper JAR in the `plugins` folder on every backend.
2. Put the Velocity JAR in the proxy's `plugins` folder.
3. Start each server once to create its configuration.
4. On each backend, set `server.name` to the exact name used in Velocity's server configuration. Set `server.weight` to control which backend has priority.
5. Keep `network.channel` the same on every backend and on Velocity.
6. Restart the network and test a nickname change, a cleared nickname, and a server switch.

Velocity owns the nickname database. SQLite is the default and needs no separate service. MariaDB and MySQL are also available in the Velocity config.

## Nickname Providers

Paper supports a developer API, EssentialsX, CMI, and a configurable adapter for other plugins. A registered API provider takes priority, followed by EssentialsX, CMI, then Custom. Disable `hooks.api.enabled` if you want to ignore registered service providers.

EssentialsX is enabled by default. To use CMI instead, disable `hooks.essentialsx.enabled` and enable `hooks.cmi.enabled`. For another plugin, enable `hooks.custom`, set its exact plugin name, and provide methods matching the plugin API. The default adapter expects `plugin.getUser(Player)`, then `user.getNickname()` and `user.setNickname(String)`. A setter with `(String, boolean)` is also supported and is called with `true`. If its lookup is static, set `user-class` to the fully qualified user class exposing `getUser(Player)`.

```yaml
hooks:
  api:
    enabled: true
  essentialsx:
    enabled: true
    plugin-name: "Essentials"
  cmi:
    enabled: false
    plugin-name: "CMI"
  custom:
    enabled: false
    plugin-name: "YourNicknamePlugin"
    user-class: ""
    user-lookup-method: "getUser"
    nickname-getter: "getNickname"
    nickname-setter: "setNickname"
```

Plugin APIs differ, so a custom adapter can only work when the configured methods follow that shape. Check your nickname plugin's API before enabling it. Hook changes take effect after a server restart.

### Developer API

The Paper JAR includes `dev.legacyheaven.nicksync.api.NickSyncNicknameProvider`. A bridge plugin can implement it and register the service. `getNickname` should return `null` or an empty string when the player has no nickname. NickSyncX passes `null` to `setNickname` to clear one. Provider methods run on the Paper server thread and should not perform blocking I/O.

```java
import dev.legacyheaven.nicksync.api.NickSyncNicknameProvider;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

public final class NicknameBridge implements NickSyncNicknameProvider {
  private final YourPlugin plugin;

  public NicknameBridge(YourPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public String getNickname(Player player) {
    return plugin.getNicknameManager().getNickname(player);
  }

  @Override
  public void setNickname(Player player, String nickname) {
    plugin.getNicknameManager().setNickname(player, nickname);
  }
}
```

Register it during your plugin's enable method and unregister it during disable:

```java
getServer().getServicesManager().register(
    NickSyncNicknameProvider.class, bridge, this, ServicePriority.Normal);

getServer().getServicesManager().unregister(NickSyncNicknameProvider.class, bridge);
```

Use the `NickSyncX-API-1.5.0.jar` from the build artifact as a compile-only dependency; do not bundle a second copy of the interface. In Gradle, for example:

```kotlin
dependencies {
  compileOnly(files("libs/NickSyncX-API-1.5.0.jar"))
}
```

With Paper's plugin loader, declare NickSyncX as a required server dependency with `load: AFTER` and `join-classpath: true` in your `paper-plugin.yml`. For `plugin.yml`, declare `depend: [NickSyncX]`. Register only one nickname provider per backend unless you intentionally want Bukkit service priority to choose between them.

## PlaceholderAPI

Install PlaceholderAPI on the Paper backend to register `%nicksyncname%`. In a PlaceholderAPI context it returns that player's nickname, or their regular account name when no nickname is set. PlaceholderAPI is optional.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/nicksync` | Sync your current nickname | `nicksyncx.sync` |
| `/nicksync sync <player>` | Sync an online player on this backend | `nicksyncx.sync` |
| `/nicksync force` | Ask the proxy to accept your nickname regardless of backend priority | `nicksyncx.sync` |
| `/nicksync status` | Show configured nickname providers and network channel | `nicksyncx.sync` |
| `/nicksyncx current [page]` | Show stored network nicknames | `nicksyncx.admin` |
| `/nicksyncx history [page]` | Show accepted updates from the last 24 hours | `nicksyncx.admin` |
| `/nicksyncx health` | Check database connection, row counts, and backup status | `nicksyncx.admin` |
| `/nicksyncx backup` | Create a backup now | `nicksyncx.admin` |
| `/nicksyncx restore confirm` | Restore the configured backup file | `nicksyncx.admin` |

Paper command replies and colors can be changed under `messages` in the Paper config. The Velocity admin permission is configurable with `commands.admin-permission`.

Automatic database backups are enabled by default, run hourly, and are also attempted during a clean proxy shutdown. They include current nicknames and sync history, and replace the configured backup file atomically. Restore replaces both tables and first creates a timestamped pre-restore snapshot next to the backup file. Backups contain player UUIDs and nicknames and are not encrypted, so restrict filesystem access and keep a separate copy of the `plugins/NickSyncX/backups` folder for disaster recovery.

```yaml
storage:
  backup:
    automatic: true
    interval-minutes: 60
    file: "backups/nicksyncx.backup"
```

The backup path must stay inside `plugins/NickSyncX`. Use `/nicksyncx health` to check database access and backup status before relying on a backup. Only grant `nicksyncx.admin` to trusted operators; restore is intentionally explicit because it replaces active data.

## Backend Settings

The Paper config contains backend-specific settings. Migration is off by default; enable it only on the backend whose EssentialsX userdata should be imported.

```yaml
server:
  name: "server-1"
  weight: 0

migration:
  enabled: false

network:
  channel: "nicksync:sync"

commands:
  permission: "nicksyncx.sync"
```

Higher weights have priority. Use `/nicksync force` only when you intend to replace the current network value. Keep the channel and server names consistent with your proxy configuration.

## Local Build

Install JDK 21 or newer and Gradle 9.8, then run `build.bat` on Windows or `bash build.sh` on Linux. Both scripts run the clean build, save `build-log.txt`, and copy the API, Paper, and shaded Velocity JARs into `dist`. You can also run `gradle --no-daemon clean build` directly; Gradle writes artifacts to `api/build/libs`, `paper/build/libs`, and `velocity/build/libs`.

## License

NickSyncX is available under the [NickSyncX Community Noncommercial License 1.0](LICENSE). Read the license before redistributing the project. Third-party dependencies retain their own licenses.
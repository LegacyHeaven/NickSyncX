# NickSyncX

### EssentialsX nickname synchronization for Paper, Purpur, and Velocity

NickSyncX keeps player nicknames aligned across a multi-server network. Paper/Purpur backends read and apply EssentialsX nicknames, while Velocity arbitrates updates and owns persistent storage.

## Features

- Configurable backend names and nickname priority.
- EssentialsX nickname reads and writes on Paper/Purpur.
- Five-minute polling by default, plus join and leave change checks.
- Red feedback when a lower-priority update is rejected.
- A player force command for overriding backend priority.
- Velocity-owned MariaDB, MySQL, or SQLite storage.
- Optional EssentialsX userdata and legacy text-file imports.
- Admin views of current records and the last 24 hours of accepted syncs.
- Configurable display name for supported messages and logs.

## Release Downloads

| Platform | Artifact | Install on |
| --- | --- | --- |
| Paper / Purpur | `NickSyncX-Paper-1.4.0.jar` | Every backend server |
| Velocity | `NickSyncX-Velocity-1.4.0.jar` | The proxy |

Install the matching artifact on each platform. The Velocity release is shaded with its JDBC drivers. Do not install a `-plain.jar`.

## Requirements

- Java 25
- Paper/Purpur 26.2-compatible backends
- Velocity 3.4-compatible proxy
- EssentialsX on the Paper/Purpur backends
- MariaDB, MySQL, or SQLite configured on Velocity

## Quick Start

1. Put the Paper JAR in each backend's `plugins` folder.
2. Put the Velocity JAR in the proxy's `plugins` folder.
3. Start each server once to create its config.
4. Configure each backend's `server.name` to match its Velocity registration and set its weight.
5. Configure storage on Velocity and keep `network.channel` identical across all servers.
6. Restart and test the network before deploying to production.

Velocity owns the authoritative database. Paper servers do not need SQL credentials or a direct database connection.

## Display Name

Set this in **both** the Paper and Velocity configs:

```yaml
branding:
  display-name: "Your Brand"
```

This changes supported messages and log lines. The plugin identity remains `NickSyncX`; the display name does not change commands, permissions, storage paths, SQL table names, or the plugin-message channel.

## Paper/Purpur backend config

Configure this separately on Hub, SMP, and CMP. Paper intentionally has no database credentials: Velocity is the authoritative database owner, so backend servers do not open independent database connections.

```yaml
branding:
  display-name: "NickSyncX"

server:
  name: "hub"
  weight: 10

migration:
  enabled: true

network:
  channel: "legacyheaven:nicksync"

essentialsx:
  hook: true

commands:
  permission: "nicksyncx.sync"

performance:
  poll-interval-ticks: 6000

logging:
  debug: false
```

On SMP and CMP, set `server.name` to the exact Velocity backend name, set `weight: 0`, and `migration.enabled: false`. Keep `network.channel` identical across all backends and Velocity. Enable migration only on the backend that should import existing EssentialsX nicknames.

## Commands and Permissions

| Command | Description | Permission |
| --- | --- | --- |
| `/nicksync` | Sync your current nickname | `nicksyncx.sync` |
| `/nicksync sync` | Sync your current nickname | `nicksyncx.sync` |
| `/nicksync sync <player>` | Sync an online player on this backend | `nicksyncx.sync` |
| `/nicksync force` | Override backend priority for your nickname | `nicksyncx.sync` |
| `/nicksyncx current [page]` | View the current stored nickname records | `nicksyncx.admin` |
| `/nicksyncx history [page]` | View accepted syncs from the previous 24 hours | `nicksyncx.admin` |

The Velocity admin permission can be changed with `commands.admin-permission`. Move existing LuckPerms grants from `nicksyncx.sync` or `nicksyncx.admin` to the corresponding `nicksyncx.*` node.

## Velocity proxy config

Database configuration belongs on Velocity in `plugins/NickSyncX/config.yml`:

```yaml
branding:
  display-name: "NickSyncX"

network:
  channel: "legacyheaven:nicksync"

storage:
  type: "mariadb"
  sqlite:
    file: "nicknames.sqlite"
  mysql:
    host: "127.0.0.1"
    port: 3306
    database: "legacy_nicksync"
    username: "legacy_nicksync"
    password: "CHANGE_ME"
    parameters: "useSSL=false&serverTimezone=UTC"

logging:
  debug: false

commands:
  admin-permission: "nicksyncx.admin"
```

MariaDB is the default for new installs. For MySQL, set `storage type: "mysql"`; for file-based SQLite, set `storage type: "sqlite"`. SQLite files are stored under `plugins/NickSyncX/`. Protect the Velocity config because it contains database credentials. For remote SQL servers, enable TLS and certificate verification in the JDBC parameters.

The `nicksyncx_sync_events` table stores accepted history, and entries older than 24 hours are automatically pruned.

## Build from Source

Requirements: JDK 25 and a compatible Gradle 9.x installation. This source archive does not include a Gradle wrapper. Run `build.bat` from the project root on Windows.

The release artifacts are:
- `dist/NickSyncX-Paper-1.4.0.jar`
- `dist/NickSyncX-Velocity-1.4.0.jar`

## Upgrade notes

Keep `legacyheaven:nicksync` identical across all backends and the proxy unless you intentionally change it everywhere. Test nickname changes, clearing nicknames, force sync, server switching, and restart persistence on a staging network before production.

## License

NickSyncX is available under the [NickSyncX Community Noncommercial License 1.0](LICENSE). You may freely use, fork, modify, and redistribute it, provided redistributions credit **NickSyncX by LegacyHeaven**, include the license, identify modifications, and retain these terms. Selling the software or modified versions, or including them in paid software bundles, is not permitted.

This is a custom noncommercial license, not an OSI-approved Open Source license. Third-party dependencies bundled with the plugin remain under their respective licenses.

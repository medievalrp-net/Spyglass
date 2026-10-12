# Installation

## Requirements

- **Paper** 26.1.2, 26.2 or 26.3
- **Java 25**
- Optional: **WorldEdit** 7.3+ or **FastAsyncWorldEdit** 2.15+, to log WorldEdit edits and to search inside a selection

You don't need a database server. Spyglass stores everything in a local SQLite file by default. See [Choosing a database](databases.md) if you want something bigger.

!!! tip "Minecraft 1.21.11"

    Use [Spyglass 1.0.13](https://github.com/medievalrp-net/Spyglass/releases/tag/v1.0.13) (Java 21). 1.1 and later only run on Minecraft 26.x.

## Download

Get the [latest release](https://github.com/medievalrp-net/Spyglass/releases/latest). It has two jars. Pick **one**:

| Jar | When to use it |
| --- | --- |
| `Spyglass-<version>.jar` | **Recommended.** Small download. On first start, Paper downloads the database drivers and other libraries from Maven Central and caches them in `<server>/libraries/`. |
| `Spyglass-<version>-shaded.jar` | Everything bundled in one large jar. Use it if your host blocks outgoing connections at startup, or the library download fails. |

Both behave the same. One jar works on every supported Minecraft version.

## Install

1. Stop the server.
2. Put the jar in your `plugins/` folder.
3. Start the server. Spyglass creates `plugins/Spyglass/config.conf` and starts logging right away, using SQLite (`plugins/Spyglass/spyglass.db`).
4. Check it's running: `/sg version`.

That's it. Every command defaults to server operators. See [Permissions](../reference/permissions.md) to open them up to staff.

## Updating

Stop the server, replace the old Spyglass jar with the new one, and start the server again. Your config and data carry over.

Spyglass checks GitHub for new releases every six hours and tells operators in chat when one is out. It never downloads anything by itself. See [Reloading and updates](maintenance.md).

## Next steps

- [Quick start](quick-start.md): your first lookup, search and rollback.
- [Configuration](../reference/configuration.md): retention, default search radius, the wand item and more.
- [Importing from CoreProtect](coreprotect.md): bring your old history with you.

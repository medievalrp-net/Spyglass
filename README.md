<p align="center">
  <img src=".github/assets/logo.jpg" alt="Spyglass" width="128">
</p>

<h1 align="center">Spyglass</h1>

<p align="center">
  Logging and rollback for Paper 26.1.2, 26.2 and 26.3.<br>
  See who changed what, search it in seconds, and roll back griefing without lagging the server.
</p>

<p align="center">
  <a href="https://github.com/medievalrp-net/Spyglass/releases/latest"><img alt="Download" src="https://img.shields.io/github/v/release/medievalrp-net/Spyglass?style=for-the-badge&label=Download&color=2ea44f"></a>
  <a href="https://github.com/medievalrp-net/Spyglass/releases/tag/v1.0.13"><img alt="Spyglass legacy for Minecraft 1.21.11" src="https://img.shields.io/badge/Legacy-1.21.11-6e7681?style=for-the-badge"></a>
  <a href="https://medievalrp-net.github.io/Spyglass/"><img alt="Documentation" src="https://img.shields.io/badge/Docs-Read-4b6bfb?style=for-the-badge&logo=materialformkdocs&logoColor=white"></a>
  <a href="https://discord.gg/7kxDxMYKyW"><img alt="Discord" src="https://img.shields.io/badge/Discord-Join-5865F2?style=for-the-badge&logo=discord&logoColor=white"></a>
</p>

<p align="center">
  <img alt="Paper 26.1.2 to 26.3" src="https://img.shields.io/badge/Paper-26.1.2%20%E2%80%93%2026.3-f0f0f0?style=flat-square">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-f0f0f0?style=flat-square">
  <a href="https://central.sonatype.com/artifact/net.medievalrp/spyglass-api"><img alt="Maven Central" src="https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fnet%2Fmedievalrp%2Fspyglass-api%2Fmaven-metadata.xml&label=API&style=flat-square"></a>
  <a href="https://github.com/medievalrp-net/Spyglass/actions/workflows/verify.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/medievalrp-net/Spyglass/verify.yml?branch=main&style=flat-square&label=build"></a>
</p>

Spyglass records blocks, containers, items, chat, commands, logins and combat, lets staff search all of it with short queries, and rolls back griefing off the main thread. It runs on ClickHouse (recommended), SQLite, MariaDB/MySQL or MongoDB.

Installing, commands, configuration and the developer API are all in the **[documentation](https://medievalrp-net.github.io/Spyglass/)**.

## Sponsors

Proudly sponsored by and running on:

<p>
  <a href="https://crusalis.net"><img src=".github/assets/crusalis.webp" height="90" alt="Crusalis: Glory of Rome"></a>
  &nbsp;&nbsp;
  <a href="https://apply.istoria.events/"><img src=".github/assets/istoria.jpg" height="90" alt="Istoria"></a>
</p>

## Modules

- `spyglass/`: the Paper plugin.
- `spyglass-api/`: the public API other plugins build against, on Maven Central as `net.medievalrp:spyglass-api`. See the [developer docs](https://medievalrp-net.github.io/Spyglass/reference/api/).
- `spyglass-core/`: shared internals, like storage and codecs.
- `spyglass-velocity/`: an optional, read-only Velocity companion for cross-server search.
- `spyglass-importer/`: a command-line CoreProtect importer.

## License

The plugin (`spyglass`, `spyglass-core`, `spyglass-velocity`) is licensed under [GPL-3.0](LICENSE). The API (`spyglass-api`) is [Apache-2.0](spyglass-api/LICENSE), so other plugins can depend on it freely. See [licensing](.github/LICENSING.md) for details; contributions are covered by the [CLA](.github/CLA.md).

The CoreProtect importer is a clean-room implementation, written from paraphrased schema notes rather than CoreProtect's GPL source.

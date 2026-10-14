# Troubleshooting

## A search finds nothing

Searches only look **250 blocks around you** and **4 hours back** unless you say otherwise. Spyglass prints a grey hint when the radius default kicks in.

- Add `-g` to search everywhere, or a bigger `r:`.
- Add `t:` to look further back, like `t:1w`.

## The wand shows nothing for a block

The wand looks back 26 weeks. For older history: `/sg search trg:x,y,z t:52w`. If the block is gone, right-click the face of the block next to where it was.

## `/s` runs another plugin's command

Another plugin owns `/s`. Use `/sg` or `/spyglass`, or turn off the alias:

```hocon
commands {
  s-alias = false
}
```

## The server can't download libraries on startup

Some hosts block outgoing connections. Use `Spyglass-<version>-shaded.jar` instead, which has everything built in.

## Spyglass refuses to start

- **"Unsupported API version":** your Paper is older than 26.1.2. Use the [1.0.13 release](https://github.com/medievalrp-net/Spyglass/releases/tag/v1.0.13) for 1.21.11.
- **A Minecraft version error:** Spyglass only starts on the versions it was built for (26.1.2, 26.2 and 26.3). Check for a newer release.
- **A database error:** check the connection details for your backend in `config.conf`. See [Choosing a database](databases.md).

## "In-game only" errors

`/sg inventory`, `/sg snapshot` and `/sg undo` need a player. They don't work from the console or RCON.

## Player snapshots say they're turned off

Player inventory snapshots are off by default. Set `snapshot.players.enabled = true` and restart. See [Inventory snapshots](snapshots.md).

## IP addresses show as "(ip hidden)"

Seeing IPs needs the `spyglass.search.ip` permission, separate from `spyglass.search`.

## Still stuck?

Ask on [Discord](https://discord.gg/7kxDxMYKyW) or [open an issue](https://github.com/medievalrp-net/Spyglass/issues). Include your Spyglass version (`/sg version`), Paper version, database backend and any errors from the console.

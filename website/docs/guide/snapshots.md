# Inventory snapshots

See what a player was carrying, or what a chest held, at a moment in the past.

```
/sg snapshot p:Steve t:1h          # Steve's inventory 1 hour ago
/sg snapshot t:30m                 # the container you're looking at, 30 minutes ago
/sg snapshot t:6h trg:100,64,200   # a container by its coordinates
```

Alias: `/sg snap`. Needs the `spyglass.snapshot` permission. It's view-only: looking at a snapshot changes nothing.

The snapshot opens in a menu. **Click an item to take a copy** of it. That needs a second permission, `spyglass.snapshot.take`, so you can give staff view-only access. Copies never touch the live player or container, and a copy that doesn't fit in your inventory is refused rather than dropped. Every copy is logged as a `snapshot-take` event.

Snapshots only work in-game.

## Player inventories

!!! warning "Off by default"

    Player snapshots need this in `config.conf`, followed by a restart:

    ```hocon
    snapshot {
      players {
        enabled = true
      }
    }
    ```

Once on, Spyglass saves each player's inventory when they join, leave, die or change worlds, plus every minute while they're online. It only writes when the inventory changed, so idle players cost nothing.

A snapshot shows the newest save at or before the time you asked for, and says when that save was taken. Saves are kept for 30 days.

It's off by default because it's the only feature that records on a timer, so it uses some disk whether or not anyone looks. You can change the interval and how long saves are kept in [Configuration](../reference/configuration.md#inventory-snapshots).

## Containers

Container snapshots need no setup and store nothing extra. Spyglass rebuilds the container from its logged deposits and withdrawals, then checks the result against what's in it now.

If the log can't fully explain the contents, the snapshot lists what it couldn't account for. That can happen with furnaces, brewing stands and campfires (they change their own contents), very old records, or a container whose shape changed. A double chest shows both halves as one 54-slot view.

## Keys

`/sg snapshot` only accepts these keys:

| Key | Meaning |
| --- | --- |
| `t:` | **Required.** How long ago, like `t:1h`. |
| `p:` | One player name. Shows that player's inventory. Leave it out for a container. |
| `trg:` | Container coordinates, `x,y,z`. Leave it out to use the block you're looking at. Can't be used with `p:`. |
| `w:` | World for `trg:`. Defaults to the world you're in. |

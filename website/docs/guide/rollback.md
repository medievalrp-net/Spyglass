# Rollback, restore and undo

| Command | What it does |
| --- | --- |
| `/sg rollback <keys>` | Puts things back the way they were before the matching changes |
| `/sg restore <keys>` | Re-applies changes that were rolled back |
| `/sg undo` | Reverses your last rollback or restore |
| `/sg rbqueue` | Shows and manages running rollbacks |

All need the `spyglass.rollback` permission. Rollback and restore use the same keys as [search](searching.md).

## Always search first

A rollback changes the world, so check what it matches before you run it:

```
/sg search p:griefer t:6h r:100      # look at what matched
/sg rollback p:griefer t:6h r:100    # then roll it back
```

The [same defaults](searching.md#where-and-when-it-looks) apply: 250 blocks around you and the last 4 hours, unless you say otherwise. There's no size limit. Rollbacks run off the main thread, so the server keeps its TPS even for millions of blocks.

## What gets rolled back

By default a rollback restores **blocks**. Two flags add more:

| Flag | Adds |
| --- | --- |
| `--containers` | Chests and other containers: placing back destroyed ones, overwriting live ones, and reverting items put in or taken out |
| `--entities` | Animals and mobs players killed come back to life |

Without `--containers`, a rollback never places, replaces or empties a container, so chest contents are left alone.

Chat, commands, logins and other records that don't change the world are never touched.

## Rollback overwrites what's there now

Each block is set back to its recorded state, whatever is there now. That's what you want for griefing: if water, lava or fire spread into the area afterwards, the rollback still puts the original blocks back.

The catch: if another player legitimately built in those same spots afterwards, the rollback overwrites their work too. Keep rollbacks tight with `p:`, `r:` and `t:`.

If a rollback destroys a container that had items in it, those items aren't lost. They go to [salvage](salvage.md).

Water and lava aren't replayed block by block. After a rollback, they flow again naturally from the restored terrain.

## Undo

```
/sg undo
```

Reverses your most recent rollback or restore. Run it again to keep going back, newest first.

- Undo works on **your own** operations from the **last 24 hours**. It's in-game only; rollbacks run from the console can't be undone with it.
- There's no redo. To re-apply something you undid, run `/sg restore` with the original keys.
- Items a rollback destroyed come back through [salvage](salvage.md), not undo.

## Restore

`/sg restore <keys>` re-applies changes a rollback reverted, oldest first. It takes the same keys and flags as rollback.

## The rollback queue

Rollbacks run one at a time; others wait their turn.

```
/sg rbqueue              # running, waiting and resumable jobs
/sg rbqueue stop         # stop the running job after its current batch
/sg rbqueue cancel <id>  # cancel a waiting or running job
/sg rbqueue resume <id>  # finish a job a crash or restart interrupted
```

If the server crashes during a rollback, the job shows up as resumable after the restart. `resume` picks up where it stopped and ends with the same result.

## Examples

```
# Undo everything a griefer did nearby in the last 6 hours
/sg rollback p:griefer t:6h r:100

# TNT crater: TNT a player lit counts as that player
/sg rollback p:griefer t:1h r:30

# Creeper damage in the last hour
/sg rollback c:creeper t:1h r:50

# Everything a player placed or broke, anywhere, in the last 2 hours
/sg rollback p:Bob a:place,break t:2h -g

# Include chest contents
/sg rollback p:thief t:1d r:50 --containers

# Restore what was broken inside a WorldEdit selection
/sg restore a:break t:1h -we
```

!!! tip "Who gets blamed for explosions?"

    TNT a player lit is logged as that player, so `p:<name>` covers the crater. TNT set off by redstone, dispensers or chain reactions, and mob explosions, are logged under their cause. Use `c:tnt` or `c:creeper`.

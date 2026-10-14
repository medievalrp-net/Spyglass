# Common situations

Ready-made commands for the problems staff run into most. Replace the names, times and radius with your own. Always run the search first, and only run the rollback once the results look right.

## Clean up a griefed base

Stand in the base and check what the griefer did:

```
/sg search p:griefer t:6h r:100
```

Roll it back, including the chests they broke or emptied:

```
/sg rollback p:griefer t:6h r:100 --containers
```

If the rollback overwrote a chest someone filled afterwards, those items are in `/sg inventory`. If the result looks wrong, `/sg undo` reverses the whole rollback.

## Find out who broke or placed a block

Run `/sg tool` and left-click the block. If the block is gone, right-click the face of the block next to where it was. Run `/sg tool` again to put the wand away.

## Items stolen from a chest

Stand next to the chest and see who took what:

```
/sg search a:withdraw r:3 t:1d
```

Put the items back in the chest:

```
/sg rollback a:withdraw p:thief r:3 t:1d --containers
```

This refills the chest. It doesn't take the items away from the thief.

## Check a player for x-ray

```
/sg search p:Steve a:break b:diamond_ore,deepslate_diamond_ore,ancient_debris t:1w -g
```

Compare it with how much ordinary stone they mined in the same week. Someone using x-ray digs straight to ores and breaks very little stone:

```
/sg search p:Steve a:break b:stone,deepslate t:1w -g
```

## Explosions

TNT a player lit counts as that player, so a normal rollback covers the crater:

```
/sg rollback p:griefer t:1h r:30
```

Creepers, and TNT set off by redstone or dispensers, are logged under their cause:

```
/sg rollback c:creeper t:1h r:50
/sg rollback c:tnt t:1h r:50
```

## Fire

See who started it:

```
/sg search a:ignite t:2h r:50
```

Blocks burned by a fire a player lit count as that player, so roll back their name:

```
/sg rollback p:arsonist t:2h r:50
```

Fires nobody lit, like lightning, are logged as `fire`:

```
/sg rollback c:fire t:2h r:50
```

## Who killed a pet

```
/sg search a:death e:wolf,cat,horse t:1d r:50
```

If a player killed it, bring it back:

```
/sg rollback a:death e:wolf t:1d r:50 --entities
```

## Read a player's chat

```
/sg search p:Steve a:say t:1d -g
```

Add their commands with `a:say,command`. To find a word in everyone's chat:

```
/sg search a:say m:word t:1d -g
```

## Check for alt accounts

Seeing IP addresses needs the `spyglass.search.ip` permission. First find the player's IP from their logins:

```
/sg search p:Steve a:join t:4w -g
```

Then list every account that joined from that IP:

```
/sg search ip:1.2.3.4 a:join t:4w -g
```

## Retrace what a player did

Everything they did in the last two hours, anywhere:

```
/sg search p:Steve t:2h -g
```

For an earlier window, add an end point. This is "between 3 and 2 hours ago":

```
/sg search p:Steve t:3h before:2h -g
```

## Undo a WorldEdit mistake

WorldEdit's own `//undo` is the quickest fix. Once its history is gone, for example after a restart, select the area and roll back the builder's edits inside it:

```
/sg rollback p:Builder t:1h -we
```

## Undo a rollback that went wrong

```
/sg undo
```

This reverses your most recent rollback, however big it was. Run it again to go back further. It only works on your own rollbacks from the last 24 hours. For anything older, see [Rollback, restore and undo](rollback.md).

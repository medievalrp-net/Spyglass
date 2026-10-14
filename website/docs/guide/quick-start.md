# Quick start

All commands start with `/spyglass`, or the shorter `/sg` (or `/s`).

## 1. Check a block with the wand

```
/sg tool
```

You get the inspection wand. **Left-click** a block to see who placed, broke or changed it. **Right-click** a block's face to check the space in front of it, which is how you look up a block that's gone. Run `/sg tool` again to put the wand away.

## 2. Search the log

```
/sg search p:Steve t:1d
```

Shows everything Steve did in the last day within 250 blocks of you. Add more keys to narrow it down:

```
/sg search p:Steve a:break b:diamond_ore,deepslate_diamond_ore t:1d r:50
```

Each key filters the results: `p:` player, `a:` action, `b:` block, `t:` how far back, `r:` radius. Use `/sg page 2` to see more results. Everything is listed in [Search keys and flags](../reference/query.md).

## 3. Roll back griefing

Search first to check what will change, then swap `search` for `rollback`:

```
/sg search p:griefer t:6h r:100
/sg rollback p:griefer t:6h r:100 --containers
```

`--containers` also puts back chests and other containers the griefer broke, and items they took out of them. Without it, a rollback only restores blocks and leaves every container alone, so broken chests stay missing.

## 4. Changed your mind? Undo it

```
/sg undo
```

Undo reverses your last rollback, however big it was.

## Good to know

- **Searches are local by default.** Without `r:`, Spyglass only looks 250 blocks around you. Add `-g` to search the whole server.
- **Searches only go back 4 hours by default.** Add `t:` to look further, like `t:1w`.
- **Rollbacks leave animals alone unless you ask.** Add `--entities` to bring back animals and mobs that players killed.
- **Items are never lost.** If a rollback destroys a chest that had items in it, open `/sg inventory` to get them back.

Next: [Common situations](situations.md) has ready-made commands for the problems staff run into most. [Searching](searching.md) and [Rollback, restore and undo](rollback.md) explain each step in depth.

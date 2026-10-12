# Inspecting blocks

The inspection wand is the fastest way to answer "who did this?" for one block.

```
/sg tool
```

This gives you the wand (a redstone lamp by default) and turns on inspection. While you hold it:

| Action | Shows |
| --- | --- |
| **Left-click** a block | That block's history, including changes a rollback made. |
| **Right-click** a block's face | The history of the space in front of that face. Use it to look up a block that's gone (now air), like a torch someone broke. |

The wand never places or breaks anything, so you can click freely. Run `/sg tool` again to turn inspection off and remove the wand. Throwing it away also turns inspection off.

Needs the `spyglass.tool` permission.

## How far back it looks

The wand shows the last **26 weeks** of history, and the header always says so, for example `STONE at 100 64 200 (last 26w)`. An empty result means nothing happened in that window.

For older history, or more results than the wand shows, search that exact block instead:

```
/sg search trg:100,64,200 t:52w
```

## Wand settings

In `config.conf`:

```hocon
tool {
  material = "REDSTONE_LAMP"   # the item used as the wand
  lookback = "26w"             # how far back a click looks
}
```

Both can be changed with [`/sg reload`](./maintenance) without a restart. Wands players already have keep working after you change the material.

# Recovering items (salvage)

A rollback overwrites whatever is in its way. Sometimes that's a chest someone filled *after* the griefing, now turned back into stone. Spyglass saves the contents of every container a rollback destroys, so nothing is lost.

```
/sg inventory
```

Aliases: `/sg inv`, `/sg salvage`. Needs the `spyglass.salvage` permission, which is separate from `spyglass.rollback`. You can let staff recover items without letting them run rollbacks, and the other way round.

## Using the menu

The menu has three levels:

1. **Rollbacks** that destroyed containers, with who ran them, when, and how many containers.
2. **Containers** from the rollback you picked, with their type and coordinates.
3. **Items** in the container you picked. Click an item to take it.

Use the Back, Previous and Next buttons on the bottom row. Every level has pages, so a rollback that wiped 100+ chests is still easy to browse.

- You can only **take** items out, never put any in.
- A container disappears from the menu once it's empty.
- If two staff take from the same container at once, each item can still only be taken once.

## Good to know

- Only containers a rollback **actually destroyed** are saved. Chests the rollback left alone aren't copied, so items are never duplicated.
- Salvage is kept for **30 days**.
- The menu only works in-game. From the console you get an error.
- Every item taken is logged as a `salvage-withdraw` event: `/sg search a:salvage-withdraw t:1w -g`.

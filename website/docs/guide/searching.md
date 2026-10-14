# Searching

```
/sg search <keys and flags>
```

A search is a list of `key:value` filters. A result has to match **all** of them.

```
/sg search p:Steve a:break b:diamond_ore,deepslate_diamond_ore t:1d r:50
```

"Diamond ore Steve broke in the last day, within 50 blocks of me."

Needs the `spyglass.search` permission. Aliases: `/sg s`, `/sg lookup`, `/sg l`.

## The keys you'll use most

| Key | Meaning | Example |
| --- | --- | --- |
| `p:` | Player | `p:Steve` · `p:Steve,Alex` |
| `a:` | Action (event type) | `a:break` · `a:place,break` |
| `b:` | Block (exact name, no wildcards) | `b:diamond_ore` · `b:chest,barrel` |
| `i:` | Item | `i:netherite_sword` |
| `t:` | How far back | `t:30m` · `t:1d` · `t:2w` |
| `r:` | Radius in blocks around you | `r:50` |
| `w:` | World | `w:world_nether` |

The full list, including item names, lore, enchantments, chat text, IPs and more, is in [Search keys and flags](../reference/query.md).

## Combining values

- **Several values = any of them.** `p:Steve,Alex` matches either player. `a:place,break` matches either action.
- **`!` excludes.** `a:!place` is every action except placing. `b:!chest` skips chests. Works on `p:`, `a:`, `b:` and `c:`.
- **Quotes for spaces or colons.** `iname:"Storm Caller"`, `itags:"mmoitems:type"`.

## Where and when it looks

Searches have two defaults, so a bare search stays fast:

- **Radius:** 250 blocks around you. Use `r:<blocks>` to change it, `cr:<chunks>` to search whole chunks top to bottom, or `-g` to search everywhere.
- **Time:** the last 4 hours. Use `t:` to look further back, and `before:` for an end point. `t:12h before:6h` means "between 12 and 6 hours ago".

Time units: `s`, `m`, `h`, `d`, `w`, `mo`. You can combine them: `t:1d12h`.

The console has no position, so its searches cover the whole server; narrow them with `w:` or `trg:`. Server owners can change both defaults in [`config.conf`](../reference/configuration.md#search-defaults).

## Reading the results

Each line shows who did what, where and how long ago, for example `Steve broke DIAMOND_ORE`. Results are newest first.

- **Click a line** to teleport there (needs `spyglass.tele`).
- **Hover** for details like coordinates, item names and enchantments.
- **Identical lines in a row are merged** with a count (`x72`). Add `-ng` to list each one.
- **More pages:** `/sg page <n>`.
- Changes undone by a rollback show greyed out and struck through.

## Useful flags

| Flag | Effect |
| --- | --- |
| `-g` | Search the whole server, not just around you |
| `-we` | Search inside your WorldEdit selection |
| `-ng` | Don't merge identical lines |
| `-nc` | Leave chat messages out of the results |
| `-ex` | Show extra detail in each line |
| `-ord:asc` | Oldest first |
| `-nod:r,t` | Drop the default radius and time limit |

## Examples

```
# Who broke blocks around here in the last day?
/sg search a:break t:1d r:10

# Everything Bob did in the last 2 hours, anywhere
/sg search p:Bob t:2h -g

# Who picked up the sword named Excalibur?
/sg search a:pickup iname:Excalibur t:1w -g

# Every command a player ran today
/sg search p:Steve a:command t:1d -g

# Chat containing a word, as a pattern
/sg search content:/(word1|word2)/ t:1d -g

# Who opened this chest? (look at it, use its coordinates)
/sg search a:open trg:100,64,200 t:1w
```

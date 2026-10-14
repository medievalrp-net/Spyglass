# Search keys and flags

`/sg search`, `/sg rollback` and `/sg restore` all use these keys. A result has to match every key you give.

```
/sg search p:Steve b:diamond_ore t:1d r:50
```

- **Commas mean "any of":** `p:Steve,Alex`
- **`!` excludes:** `a:!place`, `b:!chest`. Works on `p:`, `a:`, `b:` and `c:`.
- **Quotes** for values with spaces or colons: `iname:"Storm Caller"`

## Who and what

| Key | Also | Example | Matches |
| --- | --- | --- | --- |
| `p:` | `player:` | `p:Steve,Alex` · `p:!Steve` | The player who did it |
| `a:` | `action:`, `event:` | `a:break,place` · `a:!place` | The [event type](events.md) |
| `b:` | `block:` | `b:diamond_ore,deepslate_diamond_ore` · `b:!chest` | The block. Exact names only, no wildcards |
| `e:` | `entity:` | `e:creeper` | The entity involved |
| `c:` | `cause:` | `c:tnt,!creeper` | What caused it, for changes that weren't a player |
| `ip:` | | `ip:192.168.1.10` | The player's IP. Needs `spyglass.search.ip` |

## Items

| Key | Also | Example | Matches |
| --- | --- | --- | --- |
| `i:` | `item:` | `i:netherite_sword` | Item type (drops, pickups, containers...) |
| `iname:` | `itemname:` | `iname:"Storm Caller"` | Part of the item's name |
| `ilore:` | `itemlore:`, `d:` | `ilore:cursed` | Part of a lore line |
| `ench:` | `enchant:`, `enchantment:` | `ench:sharpness=5` | An enchantment |
| `itags:` | `itag:` | `itags:"mmoitems:type"` | Part of the item's custom data (vanilla, datapack or plugin) |
| `cu:` | `custom:` | `cu:y` · `cu:n` | Items with (or without) a custom name, lore, enchantments or custom data |

## Messages

| Key | Also | Example | Matches |
| --- | --- | --- | --- |
| `m:` | `message:` | `m:hello` | Part of a chat message, sign or book |
| `content:` | | `content:hello` · `content:/(spam\|scam)/` | Chat and command text. Wrap in `/.../` for a pattern (regular expression) |
| `rcp:` | `recipient:` | `rcp:Steve` | Who a private message was sent to |

## Where

| Key | Also | Example | Matches |
| --- | --- | --- | --- |
| `r:` | `radius:` | `r:50` | Blocks around you. Default 250, max 500 |
| `cr:` | `chunkradius:` | `cr:1` · `cr:2` | Whole chunks, top to bottom. `cr:1` is your chunk, `cr:2` is 3×3 |
| `w:` | `world:` | `w:world_nether` | One world |
| `trg:` | `target:` | `trg:100,64,200` · `trg:chest` | Coordinates `x,y,z` for exactly one block, or text in the record's target |
| `srv:` | `server:` | `srv:survival` | One server, when several share a database |

## When

| Key | Also | Example | Matches |
| --- | --- | --- | --- |
| `t:` | `since:` | `t:30m` · `t:1d12h` | From this long ago until now. Default 4 hours |
| `before:` | | `t:12h before:6h` | Ending this long ago. This example is "between 12 and 6 hours ago" |

Units: `s` seconds, `m` minutes, `h` hours, `d` days, `w` weeks, `mo` months. Combine them: `t:1d12h`.

## Flags

| Flag | Also | Effect |
| --- | --- | --- |
| `-g` | `-global` | Search everywhere, not just around you |
| `-we` | `-worldedit` | Use your WorldEdit selection as the area. Needs `spyglass.worldedit` |
| `-ng` | `-nogroup` | List identical lines separately instead of merging them |
| `-nc` | `-nochat` | Leave chat messages out of the results |
| `-ex` | `-extended` | More detail in each result line |
| `-ord:asc` | `-order:` | Oldest first (search only; newest first is the default) |
| `-nod:r,t` | `-nodefault:` | Drop the default radius (`r`), time limit (`t`) or both |
| `--containers` | `-containers` | Rollback and restore also change containers and their contents |
| `--entities` | `-entities` | Rollback and restore also bring back (or remove) entities players killed |

## Snapshot keys

`/sg snapshot` takes its own short list: `t:` (required), `p:`, `trg:` and `w:`. See [Inventory snapshots](../guide/snapshots.md#keys).

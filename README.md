# Villager Trade Scanner

A Fabric mod that shows what villagers are selling **without opening their trade GUI**. Set up filters, and matching villagers get a coloured glow and a floating label you
can read from across a village — so you can find that Mending book by walking past a trading hall
instead of clicking every librarian.

---

## Supported versions

There is a jar named for every supported version, so download the one matching
yours exactly.

| Minecraft | Jar | Java |
| --- | --- | --- |
| 26.2 | `villager-trade-scanner-1.0.0+mc26.2.jar` | 25 |
| 26.1.2 | `villager-trade-scanner-1.0.0+mc26.1.2.jar` | 25 |
| 26.1.1 | `villager-trade-scanner-1.0.0+mc26.1.1.jar` | 25 |
| 26.1 | `villager-trade-scanner-1.0.0+mc26.1.jar` | 25 |
| 1.21.11 | `villager-trade-scanner-1.0.0+mc1.21.11.jar` | 21 |
| 1.21.10 | `villager-trade-scanner-1.0.0+mc1.21.10.jar` | 21 |
| 1.21.9 | `villager-trade-scanner-1.0.0+mc1.21.9.jar` | 21 |
| 1.21.8 | `villager-trade-scanner-1.0.0+mc1.21.8.jar` | 21 |
| 1.21.7 | `villager-trade-scanner-1.0.0+mc1.21.7.jar` | 21 |
| 1.21.6 | `villager-trade-scanner-1.0.0+mc1.21.6.jar` | 21 |

Minecraft changed its APIs in a handful of places across that range, so the mod
is built once per group that shares them — 26.1-26.1.2, 1.21.9-1.21.10 and
1.21.6-1.21.8 each build once. The jars within a group are the same build under
different names, which is why picking a neighbouring one also works.

1.21.5 and below are not supported. They build, but the entity rendering there
is different enough that labels either stick to the wrong villagers or do not
draw at all, and shipping that would be worse than not shipping it.

Pick the jar matching your Minecraft version. Java is whatever that Minecraft
version already requires, so if the game runs, the mod runs.

### One difference on 1.21.x

The item picker offers the same short, accurate per-profession list on every
supported version, but it gets there two different ways.

From 26 on the server reads the trade registry directly, so the list is exactly
right even for datapacks and mods that add or change trades. Minecraft before 26
has no such registry -- trades are code that has to be *run* to produce anything,
not data that can be inspected -- so there the mod ships a catalogue generated
ahead of time by running every vanilla trade once and recording what it gave.

The practical differences on 1.21.x:

- **Modded and datapack professions are not in the bundled catalogue.** They still
  work; their trades appear in the picker once you have opened one of those
  villagers, through the same fallback the mod uses against a vanilla server.
- **Cartographer explorer maps are missing from it.** Producing one runs a
  structure search that takes a minute of real time, which is far too much to
  bake into a menu. Opening a cartographer once fills it in.

Nothing else differs: filters, labels, glow and alerts behave identically.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.3 or newer.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api).
3. Drop the jar for your Minecraft version into your `mods/` folder.

## Languages

English, Turkish, Azerbaijani, Russian, Spanish and Polish.

Translations live in `src/main/resources/assets/villager_trade_scanner/lang/`.
To add one, copy `en_us.json` to your locale code and translate the values --
keep every key, and keep the same number of `%s` placeholders per line (use
`%1$s`, `%2$s` positional forms if your language needs a different word order).
`./gradlew checkLocales` verifies both and runs as part of every build.

**Installing on the server is optional but strongly recommended** — see below.

## Keybinds

Both are rebindable under **Options → Controls → Villager Trade Scanner**.

| Key | Action |
| --- | --- |
| **V** | Toggle the preview on/off entirely |
| **U** | Open the filter menu |

When toggled off the mod does no scanning, no matching and no drawing.

---

## The server-side limitation — please read this

**In vanilla Minecraft, the client is never told a villager's trades until you open its trade
screen.** The server only sends the offer list when the merchant menu opens. This is not a
limitation of the mod; it is how the protocol works, and no client-only mod can get around it.

Concretely, `AbstractVillager.getOffers()` throws `IllegalStateException("Cannot load Villager
offers on the client")` — the data simply is not there.

So the mod behaves differently depending on where it is installed:

| Setup | What you get |
| --- | --- |
| **Singleplayer / LAN host** | Full preview. Everything works. |
| **Server also runs the mod** | Full preview. The server sends offers for villagers you are already tracking. |
| **Vanilla / unmodded server** | **Limited.** See below. |

### On a vanilla server

The mod degrades gracefully rather than breaking:

- **Profession, trade level and "is this villager locked in" still work with no server support**,
  because Minecraft already syncs those to every client. Profession-based filters are fully
  functional.
- **Offer-based filters** (item, price, enchantment, stock) only work for villagers whose trade
  screen you have opened at least once. Those offers are remembered and survive relogging.
- Villagers with no offer data show in the neutral "unknown" colour.
- The mod says nothing in chat about any of this. Falling back is normal operation, not a problem
  worth interrupting you over.

### Why villager colours may be grey on a vanilla server

Vanilla only re-rolls a villager's profession if it has **never been traded with** — specifically
`villagerXp == 0 && level <= 1`. The trade level is synced to clients; the XP is not. So:

- Level 2+ → definitely traded → **green (locked)**
- Level 1 with the mod on the server → exact answer → **green or yellow**
- Level 1 on a vanilla server → genuinely unknowable → **grey (unknown)**

Opening that villager's trade screen once resolves it permanently, because the trade packet
includes the XP.

---

## Filters

Open with **U**. Rules are grouped under the **Filters** tab.

A rule can match on:

- **Profession** — librarian, farmer, cleric, …, plus wandering traders, or *any*. Wandering
  traders are always tracked; pick them in the profession list like any villager
- **Result item** — normally just the item you get. Trades that pay *you* emeralds are listed one
  per thing the villager buys, shown as `(wheat) -> (emerald)`, since a farmer paying emeralds for
  four crops would otherwise be four identical rows
- **Enchantment**, at *any* level or a minimum one — the librarian fast path, with a searchable
  picker listing only enchantments villagers can actually trade, so nothing in it matches nothing
- **Maximum price**, labelled with the currency the trade actually uses
- **Only in stock** — hide the villager once that trade is exhausted until it restocks

Enchantment and level controls appear only once **Enchanted Book** is the chosen item, since no
other trade carries a stored enchantment. An enchantment with a single level (Mending, say) offers
just that level rather than a pointless choice between *Any* and *1*.

Every rule has a **label** — that is the text drawn above matching villagers, so write things like
`MENDING CHEAP` and spot it from a distance.

**Rule order is priority.** Rules are evaluated top to bottom, and the first rule to claim a trade
owns it, so use the ▲/▼ buttons to decide the order. A villager matching several rules gets one row
per match, stacked with your top rule nearest its head.

### What a label looks like

Each row reads *what you pay* on the left and *what you get* on the right, with the real item icons
drawn inline:

```
(emerald) 24              -  Protection IV (book)   enchanted book, named by its enchantment
(paper) 24                -  1 (emerald)            a villager buying paper from you
(emerald) 5               -  4 Glass (glass)        anything else, by name, with its stack size
(gravel) 10 + (emerald) 1 -  10 Flint (flint)       a trade that charges two different items
```

Trades charging **two** items show both, each with its own icon. A fletcher wanting gravel *and*
emeralds for flint used to read as though the gravel were free; now the whole price is on the row.

When a trade is discounted or inflated by demand, the price it started at is drawn struck through
just before the price you actually pay — so a book down from 10 to 8 reads `(emerald) 10 8`.

Labels are drawn see-through, icons included, so a villager you can see is always fully readable —
even penned into a single block. Icons are drawn as flat sprites rather than real item models, which
is what lets them ignore depth the same way the text does. A full block is drawn as a shaded
isometric cube from that sprite, so gravel reads as gravel instead of an anonymous grey square,
while still never hiding behind terrain. Blocks that are not full cubes — saplings, torches —
stay flat, because a cube would misrepresent them.

Discounts show **without opening the villager**, as long as the server runs the mod (which includes
singleplayer and LAN). Vanilla only works a player's discount out when the trade screen opens, so on
an unmodded server you will still only see it after clicking.

Villagers behind terrain are never highlighted. The outline pass draws through walls, so
highlighting an occluded villager would turn the mod into an X-ray for finding hidden bases. This is
not configurable, by design. A villager you *can* see is always labelled though, including one
penned into a single block — the block directly above it will not clip the text.

### Colours

| Colour | Meaning |
| --- | --- |
| 🟡 Yellow | Matched, **never traded** — profession can still be re-rolled by breaking the job site |
| 🟢 Green | Matched, **already traded** — profession is permanent |
| ⚪ Grey | Matched, but offers or lock state are unknown |

Alerts (sound and chat on a new match) are **off** by default — the glow already says a match is
nearby. Both are under **General**.

All three are configurable as `#AARRGGBB` hex under **Appearance**.

---

## Configuration

Saved to `config/villager_trade_scanner.json` when you close the menu (not on every keystroke).

Notable options:

| Option | Default | Notes |
| --- | --- | --- |
| `scanRadius` | 48 | Blocks |
| `scanIntervalTicks` | 8 | Ticks idled between scans; a cycle also takes 3 more, so 8 is ~11 ticks |
| `maxHighlighted` | 32 | Nearest N get labels; the rest still glow |
| `maxRenderDistance` | 48 | Blocks; never exceeds `scanRadius` |

The file has a `schemaVersion`. Older configs upgrade automatically; a config written by a *newer*
version is backed up to `.bak` before anything is overwritten.

## Performance

- One broad-phase entity query per cycle, then per-villager work split across 4 ticks.
- Squared-distance culling only — no `sqrt` in the hot loop.
- Match results cached per villager, invalidated only when the offers change or the filters are
  edited (a single `int` comparison).
- Labels are converted and measured during the scan; the render path only positions and submits.
- Collections and label objects are pooled; a steady-state cycle allocates nothing.
- Glow is applied by setting the render state's outline colour, so nothing is written back to the
  world and nothing can desync from the server.

## Building

The project is a [Stonecutter](https://stonecutter.kikugie.dev/) multi-version
build: one shared source tree in `src/`, one target per Minecraft API generation.
Targets and their Fabric API versions live in `stonecutter.properties.toml`.

```
./gradlew buildAll      # every target -> build/libs/<mod version>/
./gradlew :26.2:build   # one target
./gradlew checkLocales  # locale key and format-specifier parity
```

Version-specific code uses Stonecutter comments, so the shared source stays
compilable in an IDE:

```java
//? if >=26.2 {
minecraft.gui.hud.setOverlayMessage(message, false);
//?} else {
/*minecraft.gui.setOverlayMessage(message, false);
*///?}
```

Run **Reset active project** before committing so the tree is always stored in
the same state and diffs stay readable.

## Licence

[MIT](LICENSE.txt).

# Villager Trade Scanner

**See what villagers are selling without opening a single trade screen.**

Tell it what you are after — *Mending, under 20 emeralds* — and every villager offering it lights up
with a glow and a floating label you can read from across the village.

![Three villagers labelled with the trades they offer](https://raw.githubusercontent.com/Mehmetyll/Villager-Trade-Scanner/master/branding/screenshots/labelled-villagers.png)

## How it works

Press **U** and make a rule — a profession, an item, a maximum price.

![Editing a filter rule: librarian, enchanted book, Efficiency, max 20 emeralds](https://raw.githubusercontent.com/Mehmetyll/Villager-Trade-Scanner/master/branding/screenshots/filter-rule.png)

Rules stack up, grouped by profession. Order is priority, and each rule carries the label that gets
drawn above the villagers it matches.

![The rule list, grouped by profession](https://raw.githubusercontent.com/Mehmetyll/Villager-Trade-Scanner/master/branding/screenshots/filter-list.png)

Then just walk. Matching villagers glow, labelled with the trade that matched — what you pay on the
left, what you get on the right, item icons drawn inline. Discounts show with the original price
struck through:

![A discounted enchanted book, its original price struck through](https://raw.githubusercontent.com/Mehmetyll/Villager-Trade-Scanner/master/branding/screenshots/discounted-trade.png)

## Filters can match on

- **Profession** — including wandering traders, or *any*
- **Result item** — from a searchable picker
- **Enchantment** — at any level, or a minimum one
- **Maximum price**
- **Only in stock** — hide the villager once that trade is used up

## Colours

| Colour | Meaning |
| --- | --- |
| 🟡 Yellow | Matched, **never traded** — the profession can still be re-rolled |
| 🟢 Green | Matched, **already traded** — the profession is permanent |
| ⚪ Grey | Matched, but the offers or lock state are unknown |

## Controls

**V** toggles the preview, **U** opens the filter menu. Both rebindable.

## Please read: full function needs the mod on the server too

**In vanilla Minecraft the client is never told a villager's trades until you open its trade
screen.** That is how the protocol works, and no client-only mod can get around it.

- **Singleplayer, LAN, or a server running the mod** — everything works.
- **Unmodded server** — profession filters still work fully, and so does any villager whose trade
  screen you have opened at least once (those are remembered, and survive relogging). Everything
  else shows in the grey "unknown" colour.

## Versions

Fabric, Minecraft **1.21.6 – 1.21.11** and **26.1 – 26.2**. Requires
[Fabric API](https://modrinth.com/mod/fabric-api).

There is a jar named for every one of those versions, so download the one matching yours exactly.

On 1.21.x the item picker reads from a bundled trade catalogue, so modded professions and
cartographer explorer maps only show up in it after you have opened one of those villagers once.
Everything else behaves identically on every version.

## Languages

English, Turkish, Azerbaijani, Russian, Spanish and Polish.

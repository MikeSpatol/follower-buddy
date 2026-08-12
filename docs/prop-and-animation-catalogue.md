# Props, poses and particles — the catalogue

Written 2026-08-12. What the follower can hold, play and emit, with ids from
sources that can be re-checked rather than remembered.

Two facts shape everything here:

- **Animations and graphics have no names in the cache.** There is nothing to
  search. The names below come from either RuneLite's curated API constants
  (extracted from the exact jar we compile against) or from watching the
  running game do the thing (`::follower sniffanims`).
- **An NPC animation id cannot play on a player skeleton.** The follower is a
  player model, so the Varrock scribe's desk-writing is forever out of reach.
  Only things a *player* can do are candidates.

## Verified in game — 2026-08-12

Harvested live with `sniffanims`. These are measured, not sourced from any
list, and RuneLite's `AnimationID` names none of them.

| id | what it is | notes |
|---|---|---|
| 1350 | reading a book | arms mime a held book; nothing drawn in hand |
| 3140 | reading a book | pairs with 3141; god-book props sit visibly OFF under it |
| 3141 | turning the page | the two chain: read, turn, read |
| 4549 | reading a note, shaking head | disapproval included |
| 5354 | reading a scroll | **SHIPPED**: pairs with item 10485, no offset needed |

**The winning pair, verified 2026-08-12:** pose 5354 with the plain Scroll
(10485, weapon slot) sits between the hands with no vertex nudge at all. It
ships as the `document` errand - the follower stops where it stands, takes the
scroll out, writes something up, and puts it away. The book animations remain
unshipped: every book prop tried sat where a *wielded* book sits, not where
3140's hands meet, so a book pairing needs `propoffset` tuning or the
synthetic-model fallback before it is usable.

All four mime holding something that is not there. In the real game the item
in your hands comes from your equipment — so the prop is an item, overlaid on
the follower's outfit transiently (`::follower prop <id>`, never persisted).

## Props — what the follower can hold

From `equipment-models.json` (our own cache dump: 6,333 wearable items with
names and slots). Hand slots only; the full table is
[data/wearable-items.tsv](data/wearable-items.tsv). 55 items match book/tome/
scroll words; the useful spread:

| id | item | slot | reads as |
|---|---|---|---|
| 10485 | Scroll | weapon | a plain scroll — the natural pair for 5354 |
| 20249 | Clueless scroll | weapon | scroll with a question mark |
| 3844 | Book of balance | shield | a plain-looking holy book |
| 12612 | Book of darkness | shield | dark cover, if the follower's outfit suits |
| 20714 | Tome of fire | shield | red book, glows |
| 25574 | Tome of water | shield | blue book |
| 25818 | Book of the dead | shield | teal, Kourend |
| 26551 | Arcane grimoire | shield | purple, wizardly |
| 4817 | Book of portraiture | shield | plain brown — the most notebook-like |
| 13681 | Cruciferous codex | shield | green codex |

Slot matters: shield-slot books sit in the left hand, weapon-slot scrolls in
the right. Which pairing lines up with which animation's arms is a thing to
LOOK at, not derive — `prop` then `pose`, and judge.

## Poses — the named animation table

RuneLite's `AnimationID` (1.12.35): 329 named player animations, extracted to
[data/animation-ids.tsv](data/animation-ids.tsv). Broad strokes of what it
covers: every skilling animation by tool tier (woodcutting/mining/fishing/
smithing/cooking/herblore/crafting/fletching/runecraft), magic casts and the
five-stage `BOOK_HOME_TELEPORT_1..5` chain the mirror rules already use,
`BURYING_BONES` (827), consuming, farming, construction, and a scatter of
NPC one-offs (unusable on the follower — see the skeleton rule).

What it does NOT cover: the reading/writing family (harvested above), most
emotes the follower already mirrors live, and anything added to the game
recently. For those, `sniffanims` remains the only honest source; add finds
to the verified table above as they are harvested.

## Particles — spotanims

RuneLite's `GraphicID`: only 35 named, extracted to
[data/graphic-ids.tsv](data/graphic-ids.tsv). Our own
`spotanims.json` dump has every id's definition but no names. Spotanims
anchor to the actor (head/over/under), not to hands — a "particle of a book"
is not achievable this way, which is why the prop mechanism uses equipment.
The follower already uses spotanims for the thrall shield and teleport
flourishes (ids live in config defaults, all previously verified in game).

## The full data files

| file | rows | source |
|---|---|---|
| [data/animation-ids.tsv](data/animation-ids.tsv) | 329 | `net.runelite.api.AnimationID`, jar 1.12.35 |
| [data/graphic-ids.tsv](data/graphic-ids.tsv) | 35 | `net.runelite.api.GraphicID`, jar 1.12.35 |
| [data/item-ids.tsv](data/item-ids.tsv) | 16,608 | `net.runelite.api.ItemID`, jar 1.12.35 |
| [data/wearable-items.tsv](data/wearable-items.tsv) | 6,333 | `equipment-models.json`, our cache dump |

Regenerate after a RuneLite bump with the extraction scripts (javap over the
gradle-cache jar; the wearable table from the dump). The jar version is
stamped in each file's header so staleness is checkable.

## The workflow, when hunting a new combination

1. `::follower sniffanims` — do the thing, or stand near someone doing it;
   every player animation prints with its id.
2. `::follower finditem <name>` — wearable items by name, with slot.
3. `::follower prop <itemId>` — hold it, transiently.
4. `::follower pose <animId>` — play the candidate on the follower.
5. `::follower animinfo` — frameStep decides how a rule must play it:
   `>= 0` is an authored loop (`mirrorPose` / pose), `-1` is a one-shot
   (`animation` / `mirrorAnimation`).
6. Judge the pair by looking at it. Record what survives in the verified
   table above, with the date.

## Where this is heading

The pairing that reads best becomes, in order of payoff: a writing/reading
fidget beside think/yawn/shrug; body language on the lines that already claim
the notebook ("That's going in the book.", the `markHere` filings,
`kill-tally`); the `first-page` arrival beat played over an open book; and a
reading variant for the `recall-*` lines. The rule format already carries
`animation`, `animations` chains and `holdStill` — the only new machinery a
rule needs is the prop field, wired once the winning pair is chosen.

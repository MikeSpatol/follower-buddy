# Follower Buddy - development notes

*The engineering document: how it works, why it works that way, and the full
history of decisions. For what the plugin does and how to use it, see the
[README](../README.md).*

Repository: <https://github.com/MikeSpatol/follower-buddy>

A RuneLite plugin that spawns a client-side follower rendered as a **player model**,
dressable in any gear, that speaks configurable phrases driven by game state.

Nobody else sees it. It is a local rendering only - no packets are sent, nothing is
automated, and the follower cannot be interacted with.

---

## Feature overview

- **Authentic follower** - pet-style following on the game's own mechanics, learned
  weapon stances, game-exact dialog boxes with chatheads, right-click menu, shift-hover
  and click cross exactly as a real NPC.
- **Speech system** - 349 bundled rules across editable groups (bosses, player
  statuses, locations, item equips, quest NPCs, thrall, errands, combat,
  mimicry, reactions, memory, souvenirs, predictions, the clock, misc), hot-reloaded
  from `phrases.json` within a second. The Messages window, under Talking in
  the side panel, edits them one category per group; the Phrases section of
  the settings has a toggle per group.
- **Alive between the events** - copies your emotes, joins you when you eat, and
  has a go at whatever you have been mining or chopping or fishing for the last
  half minute; fidgets when you stand about, sits down to rest after five minutes
  idle, writes big drops up in its scroll, waves at cats and passing pets, and remembers this
  session's death spot and the places you keep coming back to.
- **Somebody who was there** - keeps ONE incident in mind and brings it up
  unprompted ("careful, I have not forgotten how close that was"), picks up a
  rock and carries it about until it loses it, calls what the next drop is worth
  before it lands and has to live with being wrong, knows what time it is where
  you actually are, and keeps a running score of the outings you did and did not
  take it on. Ask it how today went and it will tell you.
- **Games** - right-click Talk-to while it is waiting for an answer and the
  everyday script is replaced by whatever it wanted: an outing, or a hand to
  guess. The reveal lands overhead a beat after the box closes, and it is a real
  coin flip.
- **Outfit profiles** - named outfits in the OUTFIT PROFILES dropdown under
  Outfit in the side panel (Save as new..., Delete profile; edits save back to
  the selected profile) and a `::follower outfit <name>` command.
  Melee/Ranged/Magic style profiles are seeded.
- **Thrall mode** - summoning an Arceuus thrall replaces it with the follower:
  combat-proof varbit detection, smooth possession movement, per-style outfits, the
  authentic summoning circle carved from the thrall's own model, spawn/exit vfx, and
  survival across chunk reloads. The real thrall is hidden client-side.
- **Errands** - every so often the follower visits a nearby bank, prays at an altar,
  warms its hands at a fire (usually), attends to cats, and returns by foot or by
  teleport. All lines editable; frequency and per-errand toggles in config.
- **Skilling company** - while you work a tree, a rock, a spot, a fire, an anvil
  or a patch it takes a seat off your flank facing the work, borrows the tool
  (or the hat of a full skilling outfit) for the session and gives it back with a
  bow, and keeps the small habits of somebody keeping you company: a tally on
  the scroll, a nod, a sit for the long haul. Outside a session it claps a level,
  goes to see a new pet, and peers at a stranger who turns up for you. Two
  settings under Skilling; see [Skilling company](#skilling-company).

---

## How it actually works

The follower is a `RuneLiteObject` (`Client#createRuneLiteObject`) carrying a model
composed from equipment and body-kit parts, positioned each frame along a trail of
tiles you've already walked.

**Render mode matters: `RENDERMODE_SORTED_NO_DEPTH`.** Player parts genuinely
interpenetrate - the torso pokes through the cape, hair through the hat - and the
game always hides it by drawing actors with the 12-class face-priority sort and NO
depth testing, trusting the sort. A RuneLiteObject's DEFAULT mode takes the
cheaper depth-buffered route, which faithfully displays the raw interpenetration
as clipping. The model's priority data is not the issue (`::follower priorities`
proved it identical to the client's own model index-for-index, along with alpha,
face order, and geometry); the DRAW must be asked to sort with it, and
`SORTED_NO_DEPTH` is the only mode that reaches the GPU plugin's
`uploadSortedModel` with `prioritySort=true` - plain `SORTED` is not special-cased
there at all. The mirrored spotanim graphics use the same mode, since the real
client composites a spotanim INTO the actor model and sorts them together.

### The gear problem

The runtime RuneLite API exposes only `ItemComposition#getInventoryModel()`. The
**worn** model ids (`maleModel0/1/2`, `femaleModel0/1/2`) and the body kit models
live in the cache definitions and are not reachable from a plugin at runtime. So
`tools/cache-dumper` reads your OSRS cache once and writes `equipment-models.json`,
and the plugin rebuilds the model with `loadModelData` + `mergeModels` + `light` - 
any item works, and the model is unposed so animations work.

(A capture path also exists in code - overwrite your own `PlayerComposition`,
steal the rendered model, restore - but captured models are already posed and
cannot animate, so it is no longer selectable; the dump is simply required.)

### How gear avoids clipping through the body

Merging every equipped piece plus a full set of body kits draws parts that are
meant to be mutually exclusive, and they intersect. The game does not solve this
geometrically - each item declares which slots it covers, in three cache fields
(`ItemDefinition` opcodes 13, 14, 27):

| Field | Meaning |
|---|---|
| `wearPos1` | the slot the item occupies |
| `wearPos2`, `wearPos3` | further slots the item **hides** (`-1` = none) |

Both index the same 12-slot array as `KitType`. So an iron platebody is
`wp1=4 (torso), wp2=6 (arms)`; an iron full helm is `wp1=0, wp2=8 (hair),
wp3=11 (jaw)`; a bronze med helm hides hair but keeps the beard; a two-handed
weapon hides the shield slot. Roughly 2,200 of the 6,300 wearable items hide
something.

`AppearanceComposer.hiddenSlots()` reads these and drops the covered slots before
merging. **Do not approximate this by slot type.** An earlier version used the
rule "a torso item hides the arms", which is wrong for chainbodies and aprons
(`wp2=-1`, arms should show) and did nothing at all for helmets, which is why
hair rendered through full helms.

### Composition slot encoding - the item offset is 2048

`PlayerComposition#getEquipmentIds()` packs each of the 12 slots as:

```
0            empty
256 … 2047   kit    (value - 256)
2048+        item   (value - 2048)
```

**The item offset is 2048, not 512.** Getting this wrong corrupts every kit id above
255 - hair and jaw kits run past 300 - in both directions: kit 305 encodes to 561,
which reads back as "item 49", and real items decode to the wrong id entirely
(a Black cape, item 1019, read back as a nonexistent item 2555 and was silently
dropped). Symptoms were a follower missing gear in `Copy my gear` mode, a capture
path that swapped visual garbage onto the player, and `equippedItem` speech rules
firing on hairstyles.

Take these from `PlayerComposition.KIT_OFFSET` / `ITEM_OFFSET` rather than writing
literals, so a future change to the scheme follows automatically.

Verified: composing from a live `PlayerComposition` now produces a model identical
to the client's own - same face count, same distribution across priority buckets:

```
composed: 928 faces: p0x77 p1x166 p2x81 p3x135 p5x86 p6x211 p10x172
client:   928 faces: p0x77 p1x166 p2x81 p3x135 p5x86 p6x211 p10x172
```

`::follower priorities` runs that comparison on demand and then samples both
models' pose animation and frame for ~36 seconds, logging to the client log.

A second, independent source of clipping is vertical placement: `maleOffset` /
`femaleOffset` shift a worn model along Y before merging. 1,709 items have a
non-zero offset; `ModelRepository.Entry.offset(gender)` applies it.

Note that some interpenetration is genuine in OSRS - capes really do pass into
shoulders on the live client. The test for a bug here is whether the follower
clips **more** than your own character wearing the same gear.

**Cape collar "clipping": investigated and closed (2026-08-02).** The cape collar
visible over the shoulders from the front is the game's own rendering, verified
end to end: composed model identical to the client's build (face count and
priority-bucket histogram both exact), same idle animation at the same frames
(tested with loop trim disabled), and finally matched front-view screenshots of
the follower and a real player in the same gear showing the identical collar.
It reads as a follower-only bug because the camera orbits the player - you see
the follower from the front constantly and your own character almost never.
Before re-opening this, reproduce a *difference*: same gear, same camera angle,
follower and player side by side.

---

## Setup

### 0. Prerequisites

**JDK 17** (or 11+) with `JAVA_HOME` pointing at it. A Java 8 JRE will not build this.
Gradle itself is *not* needed - both this project and the dumper have wrappers checked
in, so `gradlew` bootstraps Gradle 9.6.1 on first run.

### 1. Build and install the plugin

```bash
gradlew build
```

Produces `build/libs/follower-buddy-1.0.0.jar`, and `gradlew runClient` launches a
RuneLite client with the plugin loaded as a builtin.

Note the jar on its own is **not installable**. RuneLite has no sideloading: the
client loads external plugins only from the Plugin Hub, checked against its manifest,
so a jar dropped into `.runelite` is ignored. Until this is published, running from
source is the way to use it - see [INSTALL.md](../INSTALL.md), written for someone who
just wants to try it rather than work on it.

Add `-Plint` to surface deprecation and unchecked warnings.

**Running the tests while a client is open.** `runClient` runs the client
straight out of `build/classes`, so a plain `gradlew test` recompiles the very
directory that client is loading from. Java loads classes lazily, so one it has
not touched yet - the phrase editor, say - would be read back changed
underneath it. Build somewhere else instead:

```bash
gradlew test -PaltBuild=build-test
```

Verified doing exactly that: 300 tests ran, `build/classes` and
`build/resources` came out byte-for-byte identical across all 136 files, and
the client carried on.

### 2. Generate the model data (OPTIONAL - development only)

**As of 2026-08-06 the plugin needs no dumps at all.** Item, kit and spotanim
definitions are parsed straight from the client's own loaded cache at runtime
(`LiveCacheParser`), and the game fonts ship inside the jar. The runtime
parsers are validated byte-exact against this dumper's output with
`::follower cachecheck`. Dump files, when present in `~/.runelite/follower`,
override the live parse - useful for pinning a cache revision during
development, and as the `cachecheck` baseline.

```bash
cd tools/cache-dumper
gradlew run
```

Defaults to reading `~/.runelite/jagexcache/oldschool/LIVE` and writing
`~/.runelite/follower/equipment-models.json`. Pass both explicitly if your cache
lives elsewhere:

```bash
gradlew run --args="C:/Users/you/jagexcache/oldschool/LIVE C:/Users/you/.runelite/follower/equipment-models.json"
```

Only wearable items are emitted (~4,000 of ~30,000 definitions). Re-run after a game
update that adds gear you want to use.

### 3. Dress the follower

The outfit is dressed under **Outfit** in the side panel. What the panel
writes is one string in the config (key `customOutfit`, a hidden item; the
text field it once had is gone), one entry per line:

```
gender=male
HEAD=item:11826
TORSO=item:11828
LEGS=item:11832
WEAPON=item:4151
CAPE=item:6570
AMULET=item:6585
HANDS=item:7462
BOOTS=item:11840
```

Slots are `HEAD, CAPE, AMULET, WEAPON, TORSO, SHIELD, ARMS, LEGS, HAIR, HANDS, BOOTS, JAW`.
Values are `item:<id>` or `kit:<id>`. Empty body slots are filled with default kits, so
you never get a floating helmet.

Faster alternative: `::follower copy` (or the panel's **Copy my gear** button)
writes your current gear straight into the follower's outfit.

---

## Running it in game

```bash
gradlew runClient
```

This starts a real RuneLite client with the plugin loaded as a built-in, via
`ExternalPluginManager.loadBuiltin` in `src/test/java/com/follower/FollowerPluginTest.java`.
You can also run that class's `main` directly from an IDE. It's in the *test* source
set deliberately - it depends on the RuneLite client at runtime, which the published
plugin jar must not.

The task passes `--developer-mode`, which enables RuneLite's Dev Tools. That's how you
find NPC ids, region ids and varbits for writing rules.

Once the client is up: log in, open the plugin panel, enable **Follower Buddy**. Then
`::follower status` confirms the model dump and rules loaded.

### Jagex accounts

A migrated (Jagex account) login needs credentials that the **Jagex Launcher** injects
as `JX_*` environment variables. The dev client is started by Gradle, not the launcher,
so it doesn't get them.

RuneLite's own client jar has no Jagex auth code in it at all - the login is performed
by the injected vanilla game client, which reads those variables straight from its
process environment. So the fix is simply to put them in the dev client's environment.

Copy `jx.env.example` to `jx.env`, fill in the values, and `runClient` picks them up.
`jx.env` is gitignored - the values are live session tokens, treat them like a password.
The file explains where to read them from; they expire, so expect to refresh them.

Shell environment variables are deliberately *not* used here: the Gradle daemon retains
whatever environment it was first started with, so exporting them in your shell works
only sometimes. The file is read at task configuration time and always wins.

### Why not just sideload into the real client?

There's no sideload path. `~/.runelite/plugins` looks like one but is the plugin-hub jar
cache - `ExternalPluginManager` only loads jars named in the manifest it downloaded, keyed
by hash. Installing permanently means the plugin-hub submission route, which needs a
public repo and review.

## Writing phrases

Rules live in `~/.runelite/follower/phrases.json`, written from a starter file on first
run. **Save the file and it reloads within a second** - no client restart.

```json
{
  "id": "low-prayer",
  "group": "health",
  "priority": 95,
  "cooldownMs": 10000,
  "output": "both",
  "when": {
    "type": "prayerBelow",
    "percent": 20,
    "requirePrayerActive": true
  },
  "say": [
    "Prayer's nearly out - {prayer} points.",
    "Sip a restore, you're on {prayer} prayer."
  ]
}
```

### Firing model

Every rule is evaluated against every event, including a synthetic per-tick event, and
fires on the **rising edge** of its `when` block - the moment it becomes true having
been false. `healthBelow: 40` therefore speaks once when you cross the threshold, not
every tick you spend below it. When several rules fire at once, the highest `priority`
wins.

`cooldownMs` is per rule; **Chattiness** (Follower section, and the same dropdown
under Talking in the side panel) is a global floor across all rules, which the
mood stretches or shortens on top.

### Condition types

| Type | Fields |
|---|---|
| `all` / `any` / `none` | `conditions` - nest freely |
| `npcSpawn` / `npcDespawn` | `names` (case-insensitive, `*` wildcards) or `ids` |
| `npcNearby` | `names`/`ids`, `within` (tiles); `minimum`/`maximum` combat level, which on their own match anything in that bracket |
| `playersNearby` | `minimum` other players, `within` (tiles) |
| `healthBelow` / `healthAbove` | `percent` |
| `prayerBelow` / `prayerAbove` | `percent`, `requirePrayerActive` |
| `inRegion` / `regionEnter` | `regions`, `anyLoadedRegion` |
| `inArea` | `x1`, `y1`, `x2`, `y2`, `plane` |
| `chatMessage` | `contains` or `regex`; `from` (`player`/`others`) and `is` (a `ChatMessageType` name, exactly as `::follower chatwatch` prints it); optional `every` - the nth matching message, counted for life |
| `answered` | `is` - the token on the branch the player picked, in a rule marked `asks`. Any plain word: `yes`/`no` for a question, `left`/`right` for a game. Optional `of` names the tree, so a yes to the nickname is not a yes to the outing |
| `asking` | - a question is already waiting for an answer; optional `is` names the tree. **Every `asks` rule needs this inside a `none`**, or opening a second question silently withdraws the first |
| `varbitEquals` / `varbitChanged` | `varbit`, `value` |
| `animationSelf` | `ids`; optional `every` - the nth time one of them plays, counted for life |
| `levelUp` | `names` (skill names, `*` ok) |
| `damageTaken` | `minimum` |
| `itemEquipped` | `ids` |
| `idle` | `ticks` |
| `idleBelow` | `ticks` - the other bound, for fidgets that should stop once resting |
| `playerDeath` | - the moment of death |
| `nearDeathSpot` | `within` - standing near the session's last death, 2+ minutes after it |
| `lootWorth` | `minimum` gp - fires on loot; `{item}` and `{value}` placeholders |
| `returnVisit` | `minimum` - entering a region already visited that many times this session |
| `combat` | - true throughout a fight, including the gaps between targets |
| `bossFight` | `minimum` - target's combat level, default 100 |
| `combatStart` / `combatEnd` | `names`, `{npc}` placeholder |
| `npcKill` | `minimum` / `maximum` combat level, `names`, `ids`; `{npc}` and `{level}` placeholders |
| `mood` | `is` - a band (`low`, `down`, `even`, `good`, `high`) - or `minimum`/`maximum` over 0–100 |
| `repeating` | `ticks` - how long the same animation has been running; optional `ids` |
| `awayFor` | `minimum`/`maximum` minutes since the follower last saw you; login only |
| `tally` | `of` (counter name), `minimum`/`maximum` - a **lifetime** count, kept between sessions |
| `personalBest` | optional `names`; fires the moment a record is beaten. `{record}`, `{value}`, `{previous}` |
| `sessionCount` | `minimum`/`maximum`/`every` - how many times the follower has been out with you; login only |
| `inventoryFree` | `maximum` free slots - meant as a warning while there is still room |
| `hovered` | `ticks` - the mouse resting on the follower |
| `examined` | - somebody looked it up |
| `wanting` | - a want is open; normally used inside a `none` |
| `wantFulfilled` / `wantExpired` | - `{want}` names the place it asked for |
| `feelsAbout` | `is` - `liked` or `disliked`, about the region you are standing in |
| `remembers` | the incident on its mind; optional `is` names it, `minimum` how many times it has happened. `{memory}` says it out loud |
| `carrying` | - it is holding a souvenir. `{souvenir}` names it |
| `souvenirLost` | - the thing it was carrying is gone |
| `betting` | - a prediction is outstanding; normally used inside a `none` |
| `betWon` / `betLost` | - the prediction came good, or did not |
| `timeOfDay` | `minimum`/`maximum` hour on **your** clock, 0–23. Wraps past midnight, so `23`–`5` is five hours |
| `sessionMinutes` | `minimum`/`maximum` - how long today has run |
| `placeScore` | `minimum`/`maximum` - how strongly it feels about here, as a number. Negative is the bad direction |
| `happenedHere` | - this place holds a memory; `{here}` says it |
| `heeded` / `ignored` | - the player took the last piece of advice, or the window shut. `is` narrows to one; `{advice}` names it |
| `advising` | - advice is outstanding; normally used inside a `none` |
| `daysKnown` | `minimum`/`maximum` days since the first meeting. `{days}` |
| `anniversary` | - today is the same day of the year as the first meeting |
| `outgrew` | `minimum` - times better dressed than the day you met |
| `nicknamed` | - a name has been AGREED to through the nickname question; `{nickname}` says it. Optional `is` |
| `nicknameEarned` | - the tallies have produced a name the player has not been asked about yet; `{candidate}` says it. The `ask-nickname` rule turns it into a question |
| `challenging` / `challengeMet` / `challengeFailed` | - a wager on the player. `{challenge}`, `{left}` |
| `unattended` | `ticks` since the camera last moved. **Not** `idle`: a player at a furnace is idle and present |
| `underfoot` | - you clicked the tile the follower was standing on |
| `dateIs` | `month`/`day` on **your** calendar; optional `untilMonth`/`untilDay` range wraps past new year. `day` alone means that day of every month |
| `worldHop` | - a world hop landed. `{world}` is the new world number |
| `gameUpdated` | - first login on a new game build (the weekly update) |
| `notesEdited` | - phrases.json changed on disk and hot-reloaded |
| `musicUnlock` | - a music track unlocked. `{track}` names it; tally `music` counts them |
| `musicTrack` | optional `names` (wildcards ok) - the playing track changed. `{track}` |
| `collectionAdd` | - a new collection log entry (needs the game's own log notification on). `{item}`; tally `collections` |
| `questComplete` | - the quest reward scroll appeared. `{quest}` names it; tally `quests` |
| `geOffer` | `is` `bought` or `sold` - a Grand Exchange offer finished. `{item}`, `{value}` |
| `channelJoined` / `channelLeft` | - somebody arrived in or left your friends chat or clan channel. `{name}` |
| `npcShout` | `names` (wildcards ok) and/or `contains` - a named NPC said something overhead. `{npc}`, `{message}` |
| `examinedThing` | optional `names` (wildcards ok) - the player examined something; the follower also turns to look. `{target}` |
| `attendedAgain` | - the player is back from a long absence the follower came round to check on |
| `minutesTaken` | - the follower finished minuting one of the player's NPC dialogs |
| `working` | optional `is` (a skill, lowercased as the game names it: `woodcutting`) and `contains` (part of the target's name) - a work session is on; see [Skilling company](#skilling-company) |
| `xpDrop` | optional `names` (skill names, `*` ok) and `every` - fires on the tick an XP drop lands; `every: 10` on the tenth drop of the session and every tenth after, and never outside a session. `{skill}`, `{count}` |
| `workEnded` | optional `names` (activities) - the tick a work session ends, once. `{activity}`, `{target}` |
| `targetDepleted` | - the session's target changed id or went: the stump, the empty rock, the spot that moved. `{target}` |
| `bagFull` / `bagHalf` | - the last inventory slot went, or fourteen were left; once per fill |
| `milestone` | `is` - `halfway` (level 92, half the experience to 99), `round` (a multiple of ten from `minimum` up, 50 by default), `mastery` (99; `minimum`/`maximum` on how many skills that makes, this one included), `total` (the total level crossing a multiple of `every`, 100 by default, from `minimum` up) or `base` (every skill now at `minimum` or better, the level that made it so). On the level-up event; `{total}`, `{base}`, `{nines}`, `{ninesOrdinal}` |
| `levelGap` | `minimum`/`maximum` minutes since the last level in that skill - the follower stamps every level it sees and keeps the stamps between sessions. Never true for a skill with no stamp yet. `{sinceLast}` and `{sinceAny}` say the gaps in words ("eleven days") |
| `killCount` | the game's own count after a boss ("Your Vorkath kill count is: 41"), read out of chat. Optional `names`, `minimum`/`maximum`, `every`; `is: round` fires on the counts a person says out loud (1, 5, 10, 25, 50, 75, the hundreds, then every five hundred). `{boss}`, `{count}`, `{ordinal}` ("forty-first"), `{lastCount}` |
| `grateful` | - the half hour after the player did what the follower asked: went where it wanted, brought what it wished for |
| `lastTime` | - login only: the follower remembers what the two of you were doing when the last session ended. `{lastTime}` says it: "the woodcutting", "fighting Vorkath", "getting Fishing to 70", "finishing Dragon Slayer" |
| `farewell` | - the logout tab opened. The body waves on its own; this is the rare word |
| `noticed` | `is` - `valuable`, `nest`, `impling`, `star` or `landmark`; optional `contains` on the thing's name. The follower noticed it and set off to look. `{kind}`, `{thing}` |
| `hesitated` | `is` - `wilderness` or `monster`: a Send the follower had misgivings about; it has stopped at the edge and turned to the player. `{misgiving}`, `{thing}` |
| `steppedAside` | - the player walked straight at the parked follower and it stepped aside at the last tick |
| `addressed` | - the player is within a tile of the follower and turned toward it; what makes an emote a reply rather than an echo |
| `login`, `always` | - |
| `chance` | `percent`, rolled each evaluation |

`anyLoadedRegion: true` checks the loaded map regions rather than the tile you're
standing on, which is what you want for instanced dungeons and raids.

### Placeholders

`{npc}`, `{npcId}`, `{message}`, `{skill}`, `{level}`, `{damage}`, `{value}`, `{region}`,
`{hp}`, `{maxHp}`, `{hpPercent}`, `{prayer}`, `{maxPrayer}`, `{prayerPercent}`, `{player}`,
`{item}` (the most valuable item in a loot drop; `{value}` on loot events is the drop's
total, written the way a player says it - `1.2M`, `214K`), `{speaker}` (who said it, on
chat events), `{record}` / `{previous}` (on `personalBest`), `{want}` (on `wantFulfilled`
and `wantExpired`), `{memory}` (the incident on its mind - guard the rule with
`remembers` or it prints nothing), `{souvenir}` (what it is carrying, or has
just lost - guard with `carrying` or `souvenirLost`), `{here}` (guard with
`happenedHere`), `{nickname}` (guard with `nicknamed`), `{days}` (guard with
`daysKnown`), `{advice}` and `{challenge}`. On a level-up: `{total}`, `{base}`,
`{nines}` and `{ninesOrdinal}` (how many skills are at 99 now, and "third"), and
`{sinceLast}` / `{sinceAny}` (guard with `levelGap`: they are empty until the
follower has a stamp to measure from). On `killCount`: `{boss}`, `{count}`,
`{ordinal}` and `{lastCount}`. `{lastTime}` (guard with `lastTime`). `{candidate}`
(guard with `nicknameEarned`, or an `answered` of the nickname tree).

### Other rule fields

- `output` - `overhead`, `chatbox`, or `both`. Falls back to the config default.
- `group` - used by the **Phrases** config section to silence whole categories.
  The built-in toggles cover `boss`, `health`, `area`, `idle`, `gear`, `quest`,
  `combat`, `mimic`, `memory`, `souvenir`, `bet` and `clock`; anything else goes
  in the *Silence groups or phrase ids by name* box under **Advanced:
  animations and graphics** (rule ids work there too, and the Messages window
  shows each rule's id beside its title).
- `animation` - optional emote animation id played alongside the line.
- `mirrorAnimation` - `true` to replay the PLAYER'S animation on the follower
  instead of a fixed id. Needs a trigger that says WHICH animation: either
  `animationSelf`, whose event carries the id, or `repeating`, which is how the
  follower joins in with skilling. The bundled `mirror-teleport` rule uses it to
  copy whichever teleport you cast, and `mime-oneshots` to have a go at whatever
  you have been doing for the last thirty ticks.
  Spell graphics ride along automatically: for 12 ticks after a mirror rule
  fires, any spotanim shown on the player is copied onto the follower too - a
  transient `RuneLiteObject` carrying the spotanim's model (recoloured, scaled
  and lit per its cache definition from `spotanims.json`), despawning when its
  one-shot animation ends. Graphics attach to the follower on every axis - fine
  position, height and yaw, resynced each frame - because the client composites
  an actor's spotanim into the actor's own model and draws it at the actor's
  rotation; a graphic parked where it spawned sits askew as the follower turns.
  The server also sends a HEIGHT with every cast (`Actor.getGraphicHeight()`) - 
  a teleport swirl wraps the body, a rune circle sits at the feet - and the
  client raises the spotanim model by it BEFORE resizing (`translate(-height)`
  then `resize`, from `ClientPlayer.getModel`), so the mirrored graphic applies
  the live height in that same order.
  Re-run tools/cache-dumper if `spotanims.json` is missing; the follower logs
  `player animation N` / `player graphic N` lines, so a teleport that fails to
  mirror can be diagnosed from the log.
- `animations` + `graphics` - a CHAIN of clips played back to back, each stage
  optionally opening its own spotanim (-1 skips). Takes precedence over
  `animation`/`mirrorAnimation`. The bundled `mirror-home-teleport` rule uses it
  to answer ANY home teleport variant - cosmetic overrides included - with the
  DEFAULT sequence (measured in game: animations 4847/4850/4853/4855/4857 paired
  with graphics 800/-/802/803/804), triggered by each variant's first-stage id.
- `syncToPlayer` - chain pacing. `true` advances each stage when the PLAYER's
  animation steps to its next stage - the server's own schedule - instead of
  when the follower's clip runs out. Teleport clips end in long hold frames the
  server always cuts short by starting the next stage, so clip-length pacing
  freezes for a second or two between stages; slaving to the player removes the
  freeze, paces correctly under overrides on different schedules, and ends the
  moment the player's sequence ends (with a 25-tick watchdog against desync).
- `holdStill` - plant the follower where it stands for the length of the
  animation, releasing it when the animation ends. Movement always wins over an
  emote, so anything triggered by a fight is otherwise cut off the instant the
  follower starts walking back to you - which is immediately. `kill-cheer` and
  `kill-boss-celebrate` both use it.
- `delayTicks` / `delayTicksMax` - ticks to wait between the trigger and the
  whole firing, line and animation together. Set both and each firing draws a
  fresh wait between them, inclusive; set only `delayTicks` for a fixed one.
  Reacting instantly reads as machinery, and so does a *constant* delay once
  you have seen it twice - most obviously in `mimic-emotes`, which waits 1–4
  ticks so the follower joins in a beat behind you rather than moving in
  lockstep. Leave it off where the timing is the point: `mirror-teleport`
  vanishes with your cast and must not lag it.
- `mirrorPose` - the same, held as a LOOP until your animation changes, for the
  animations that never end on their own. Which of the two a rule wants is
  decided by the cache, not by taste: mining, woodcutting, fishing and fletching
  are authored loops (`frameStep >= 0`) and hang forever if played as a one-shot,
  while cooking, smithing, herblore and firemaking are one-shots the server
  restarts each cycle. `::follower animinfo` prints the `frameStep`.
- `remember` - `{"key": "close-call", "as": "how close that was"}`. Files what
  just happened as the incident the follower keeps coming back to; `remembers`
  conditions match on the key and `{memory}` says the phrase. Filed whether or
  not the line was heard: the thing happened either way.
- `pickUp` - `{"what": "a nice flat rock", "minutes": 25}`. Something it carries
  about and is put out about losing. Refused while its hands are full.
- `bet` - `{"rich": false, "threshold": 50000, "minutes": 5}`: the follower
  calling the next drop UNDER fifty thousand. Both directions exist because
  anything that can only be right is not predicting.
- `mood` - how much this firing moves the follower's mood, positive or negative.
  See below.
- `asks` - names a tree in `dialogs.json`. Marks the line as a question and arms
  that conversation: see below.
- `want` - `{"region": 12850, "label": "Lumbridge", "minutes": 20}`. See below.
- `hushMs` - takes the floor: nothing else speaks for this long. See below.
- `markHere` - files this moment against the PLACE, said later via `{here}`.
  The incident in `remember` is one thing anywhere; this is one per place, and
  it waits for you to come back.
- `advise` - `{"about": "food", "ids": [829, 1191], "minutes": 1}` marks the
  line as advice and says what taking it looks like. `"room": true` settles on
  the inventory getting emptier instead of on an animation. What counts is named
  by the rule, so new advice is a rule and not a code change.
- `challenge` - `{"about": "ten kills", "tally": "kills", "target": 10,
  "minutes": 5}`. A wager on the player against a counter it already keeps.
- `note` - free text, ignored by the plugin.

`asks`, `want`, `pickUp` and `bet` all take effect when the line is actually
**said**, not when the rule wins: a question the mute swallowed is one the player
never heard, a souvenir nobody was told about is invisible, and a silent
prediction can only ever be right. `remember` is the exception - it is filed
regardless, because the chicken killed you whether or not the follower got a
word in.

### Conversations

`dialogs.json` sits beside `phrases.json`, is hot-reloaded the same way, and
holds the conversations the follower **starts**. While a question is open,
right-clicking **Talk-to** opens the tree named by `asks` instead of the
everyday script; once answered, or after a couple of minutes, Talk-to goes back
to normal.

The answer used to be a word typed into public chat. That asked the player to
know a magic word and asked the follower to listen to everything said near it - 
in a crowd, somebody else's "yes" was indistinguishable from yours. A menu of
options can be neither misheard nor guessed at.

```json
{
  "id": "want-outing",
  "start": "start",
  "nodes": [
    { "id": "start", "says": ["Oh good, you came over."], "next": "ask" },
    { "id": "ask", "says": ["Have you got time to go somewhere with me?"],
      "choices": [
        { "label": "Go on then. Where?", "next": "yes-q" },
        { "label": "Not right now.",     "next": "no-q" } ] },
    { "id": "yes-q", "you": ["Go on then. Where?"], "next": "yes-a" },
    { "id": "yes-a", "says": ["Give me a moment."], "answer": "yes" }
  ]
}
```

`says` is the follower speaking, `you` is the player, one page per line. A node
carrying `answer` (`yes` or `no`) is what feeds the reply back to the rules, and
an `answered` condition picks it up.

The answer is **latched** when the branch is reached and **delivered when the
box closes**. The follower's reply to its own question goes overhead, and firing
it the moment the option is clicked puts that line above a dialog box the player
is still reading - so the one thing they were waiting to hear is the one thing
they miss. Latched rather than checked at the end, so closing the box early
still counts: they answered, and whether they read the rest is their business.

A tree is validated as a whole at load and **refused outright** if anything is
wrong - a branch pointing at a node id that does not exist, a start that is not
a node, no answer anywhere. A conversation that dead-ends is worse than one that
never opens, because the player is already inside it when it happens.

Edit them in **Conversations...** in the side panel: one row per node, with who
speaks, the pages, the choices and the answer. Save validates before writing, so
a broken tree is reported rather than saved.

The everyday Talk-to script is still written in Java. It never changes, so a
file would buy nothing; these are different because they are content.

`say` is optional when the rule plays an animation: an animation-only rule is
valid, and it skips the mute and the global speech gap entirely - those throttle
chatter, and a mirrored teleport is not chatter. It does still respect its own
`cooldownMs` and rising-edge semantics.

A JSON syntax error keeps the previously loaded rules rather than leaving you silent;
the problem is reported in the chatbox and the log.

### Existing between sessions

`awayFor` is the only thing the follower remembers when it is not running, and
the only thing that should be - it is the difference between a companion that
existed while you were gone and one created fresh each time you look at it. A
timestamp goes in the config (one number; a whole file would be more machinery
than the fact deserves) and is refreshed about once a minute as well as at
login, because a client that crashes never gets to write anything on the way
out and every crash would otherwise look like an absence.

A quick relog is not an absence. `-1` means it has no idea - a first run, or a
cleared config - and never reads as "just now".

**The levels and the last thing outlive it as well.** Every level the follower
sees is stamped with the time (`levelStamps`, keyed by skill), so the next
level in that skill can say how long it took - "eleven days since the last
one" is a memory, and only somebody who was there for the last one can have
it. And the last nameable thing the two of them did - the work, a boss, a
level, a quest - goes out as `lastThing` and comes back at login as the
`lastTime` condition, so the greeting can be about last time rather than
about the clock. A session that names nothing carries the old one forward.

**Counts and records outlive the session too.** The tallies the follower keeps
(`kill:<name>`, `deaths`, `levels`) and the records it holds (`hit`, `session`)
go into the config alongside the timestamp, on the same once-a-minute timer and
for the same crash reason. This is what makes a milestone worth saying: "your
fiftieth" means nothing if the fifty were all this afternoon, and everything if
they were the fifty since you met. Read them back with `tally` and
`personalBest`, and `npcKill`'s `every` counts across sessions rather than
within one.

The first value ever filed against a record seeds it **silently**. Otherwise
every new install would announce a personal best on its very first hit, which
makes the feature look broken to every new user at once.

Counters are capped at 300 entries, rarest evicted first - every distinct NPC
name you have ever killed earns one, so without a bound it would grow into the
config forever.

### Wanting something

Everything else in the rule set runs one direction: something happened, and a
line remarks on it. A `want` runs the other way. The follower names somewhere it
would like to go, and then the next twenty minutes are an answer to that - going
there fires `wantFulfilled` and is the largest mood swing in the file, while the
deadline passing fires `wantExpired` for a small drop.

A thing with desires that can be *satisfied* reads as alive in a way no amount
of commentary manages, which is why the payoff is priced so high.

Only one want is open at a time - two would mean going anywhere satisfied
something, which is the same as satisfying nothing - and a want for the region
you are already standing in is refused outright. The `wanting` condition, inside
a `none`, is how a rule avoids asking over the top of an open one.

Each place a want can ask for has its own arrival line saying **why** it likes
it, above the generic one. Thanking you for going somewhere is politeness;
saying why *that* place is a preference, and a preference is the part that reads
as a mind.

### The name it asks to call you

The tallies produce a name (`nicknames` at the top of phrases.json: the
gravedigger for deaths, the local menace for kills, the swot for levels) and
the first version simply used it. Now it is proposed. `nicknameEarned` is
true while there is a candidate the player has not been asked about; the
`ask-nickname` rule says it in its overhead line (`{candidate}`) and opens the
`nickname-offer` tree, which cannot say the name itself - a tree is fixed
text - so it asks whether the player minds. Yes makes it the nickname the
other lines say (`nicknamed`, `{nickname}`); no strikes that name through for
good; later leaves it in pencil for the next cooldown. Both the agreed name
and the refused ones are kept between sessions, and a different name earned
later is offered afresh while the agreed one stands. Animal Crossing's
villagers ask before they call you something, and the asking is most of why
the name lands; Mikkelson's observation that "a name really was all it took"
cuts both ways.

### Taking the floor

`hushMs` on a rule means: nothing else speaks for that long. Arriving where the
follower asked is the case it was built for - the region change makes an area
line true on the same pass, and a `feelsAbout` line can land moments later, so
one arrival was coming out as two or three separate remarks.

Two details that are the whole feature:

- The floor is taken when the rule **wins**, not when it speaks. `want-fulfilled`
  waits two to five ticks before saying anything, and a rule holding the floor
  only from the moment it speaks would leave its own delay wide open for exactly
  the line it meant to displace.
- The holder is **exempt** from the global speech gap. This is the half that
  actually bit: an area line a second earlier would drop the arrival line
  entirely, so the moment worth having was the one being lost.

Only speech is hushed. An animation-only rule is movement rather than chatter
and carries on regardless - the same reason those skip the mute.

### Taste

`feelsAbout` asks how *this* follower feels about where it is standing. The
liked and disliked regions are rolled once, the first time it has enough rules
to roll from, and then kept for good in the config - so two people running this
plugin do not get the same follower.

The pool is the regions the rule set already has opinions about, which means it
maintains itself: every area rule added later widens what a follower can come to
love, and there is no second list to fall out of date.

Taste is what makes a mood legible. A number moving for reasons you cannot see
is weather; a follower that is always glad to be back at the same place and
always grumbles about the same one is a temperament you can learn.

### Remembering one thing

A tally makes "your four hundredth yew" possible, which is data delivered well.
It does not make "careful, I have seen what a chicken can do to you" possible,
and only one of those sounds like somebody who was there.

So the follower keeps exactly one incident: a key, a phrase, and a count. A rule
files it with `remember`; later rules match on the key with `remembers` and say
the phrase with `{memory}`. One is the right number. A follower with a list is
running a database, and the thing that makes a memory feel like a memory is that
it crowds out the others.

The count is what makes "again" honest - `recall-death-again` waits for the
third - and it survives logout with the rest of the counters.

### Things, hours and predictions

Three small departures from the follower being made of numbers.

**A souvenir** is the only OBJECT it owns. Everything else it has is a number, a
mood or a place; `pickUp` gives it a nice flat rock, and for the next
twenty-five minutes it is the same rock, and then one day it is not. Losing it
costs mood, and the name survives the loss so the line can mourn it by name.

**A bet** is an opinion about what happens next, which is a different thing from
commentary on what just happened. `bet` calls the next drop over or under a
threshold; `betWon` and `betLost` collect. Both directions exist deliberately - 
anything that can only be right is not predicting - and a bet nobody collects on
is quietly dropped rather than counted as a win.

**The clock** is yours, not the game's. `timeOfDay` reads the hour where you are
sitting and wraps past midnight, so a window can be written the way a person says
it. A follower that knows it is two in the morning is a different kind of
companion from one that knows how much prayer you have left.

### Taste it earned rather than taste it was given

The rolled sets are the follower's temperament before anything has happened to
it, which is the only honest thing to have on the first login. After that they
are the weaker claim. Each region carries a running score, and experience
outranks the shuffle in whichever direction experience points.

Nothing new has to be authored for it. Every rule that already knows a boss
dying is worth +18 and a death is worth -25 is already saying how much the
moment mattered; the place simply keeps its share. A new influence on how the
follower feels about somewhere is a mood value on a rule, exactly as before.

Two things are held out, and neither is obvious from the mood value alone. The
liking rules are themselves worth mood and fire *because* of how it feels about
where it is, so counting them would let the follower talk itself into an ever
firmer opinion on no evidence but its own. And everything about the session
rather than the world - logging in after a day away is worth +8 - happens
wherever you logged out, which is the same tile every time; left in, the
best-loved place in the game would reliably be your bank.

The upshot is that `"I've never liked this place"` stops being a shrug. It is
the same rule and the same words, and after you have died there twice it means
something.

### Taking advice, or not

The follower shouts *eat something* and then, however that turns out, never
mentions it again. That is commentary; after the fiftieth time it is obviously a
recording. `advise` opens a window and names what taking it would look like -
an animation, or simply the bag getting emptier - and `heeded` and `ignored`
give it the other half of the exchange. Both are tallied, so *"ten times I've
told you something"* is a `tally` condition and needs no machinery of its own.

### Saying less

Mood decided what the follower said and never how much, so a bad day sounded
like a costume: the same rate of speech, sadder words. The shared speech gap now
scales with the band - two and a half times longer at the bottom, a little
shorter at the top. It thins everything evenly rather than silencing particular
rules, so the follower still says what matters, with more room around it.

Standing about with nothing happening, the rule file offers roughly 590 lines an
hour before any gap is applied. At a low mood the gap caps it at 480, so the
quiet is real rather than decorative.

### Games

`asks` already turned a line into a question and armed a conversation to answer
it. A game is the same machinery with a wider vocabulary: an answer is any plain
word now, so a menu can offer `left` and `right` as unmistakably as it offers yes
and no.

What a tree cannot do is be surprising - it is fixed text, so a guess it resolved
itself would have the same hand every time. The reveal is left to two rules that
split a coin flip by priority: the winning one is gated on `chance: 50` and
outranks the losing one, so a failed roll falls through. That also puts the
verdict overhead, a beat after the box closes, which is where a reveal belongs.

Opening a question replaces whatever was waiting for an answer, so every `asks`
rule carries a `none`/`asking` guard and a test refuses the file without it.
Offering a game must not silently withdraw the outing.

### Noticing repetition

Two ways, both about the follower appearing to have been paying attention.

**How many.** `npcKill` takes `"every": 25`, which fires on the twenty-fifth
kill of *that* NPC and every twenty-fifth after. `{count}` carries the number,
so a line can say it. The tally is kept per NPC **name**, not id - a player
counts kalphites, not the four ids the game happens to use for them - and it is
counted where the kill is attributed, so the tally and the event can never
disagree about which kill this was.

**How long.** The `repeating` condition asks how many ticks the player has been
playing the same animation. It knows nothing about trees or rocks or fish: an
animation running for minutes *is* the activity, so one condition covers every
skill at once, including ones added later. Brief gaps are tolerated, because
most gathering drops to no animation for a tick between swings and treating
that as stopping would hold the count at zero forever.

### Mood

The follower carries one number, 0 to 100, starting at 50. It is not shown
anywhere - you read it off what it chooses to say.

**Nothing in the code decides what moves it.** A rule carries `"mood": 18` or
`"mood": -25`, and the engine applies that when the rule *fires*. So every
trigger the rule format already understands can affect the mood, and adding a
new influence is a line in `phrases.json` rather than a change to the plugin.
The nudge lands on firing rather than on winning, so a rule held back by its
cooldown, a disabled group or the mute does not quietly move the mood while
saying nothing - the follower's state and its words stay one story.

Rules read it back with the `mood` condition, normally by band:

```json
{"type": "mood", "is": "low"}
```

| Band | Range |
|---|---|
| `low` | 0–20 |
| `down` | 21–40 |
| `even` | 41–59 |
| `good` | 60–79 |
| `high` | 80–100 |

It drifts a point back toward 50 about every 30 seconds, so nothing that
happens is permanent and a bad run is recovered from by carrying on. It is
session-scoped: a follower that remembered last night's bad luck would be
strange, and a fresh start is the honest default for something that cannot see
what you did while it was gone.

The shipped nudges are deliberately asymmetric - a death costs more than a kill
earns - and `mood-low-idle` / `mood-high-idle` are the two rule sets that only
exist at the extremes. `::follower mood` reads the number, and
`::follower mood 90` sets it, both behind developer mode.

---

## Chat commands

The everyday commands below are always available. The diagnostic ones - the
instruments used to build the plugin rather than to use it - are gated behind
**Developer commands** in the plugin settings (Developer section, off by
default), and are marked **[dev]**. Typing a gated one while it is off says so
rather than failing silently.

| Command | Effect |
|---|---|
| `::follower reload` | Re-read `phrases.json` now |
| `::follower copy` | Write your current gear into the follower's outfit |
| `::follower say <text>` | Speak an arbitrary line |
| `::follower here` | Teleport the follower to you |
| `::follower rebuild` | Rebuild the model from scratch |
| `::follower fix` | Re-attach the follower to the scene if it has vanished |
| `::follower status` / `where` | Model + rule status, current region id and coordinates |
| `::follower gfx <id>` | Preview a graphic on the follower |
| `::follower pose <id>` | Hold a looping pose (0 to release) |
| `::follower height <n>` | Ground clearance, if the feet clip into terrain |

The side panel's Commands section lists these same everyday commands, and the
gated ones once Developer commands is on; both read `EVERYDAY_COMMANDS` and
`DEVELOPER_COMMAND_HELP` in `FollowerPlugin`, so a new command goes in there.

**Appearance**

| Command | Effect |
|---|---|
| `::follower hidden` **[dev]** | Print which slots each equipped item covers, from its `wearPos` data |
| `::follower palette` **[dev]** | Copy your exact body colours onto the follower, extracted from the client's own tables |
| `::follower palette clear` **[dev]** | Drop the exact colours, hand control back to the pickers |
| `::follower harvest` **[dev]** | Auto-extract the client's complete colour tables (character flickers a few seconds); `stop` aborts |
| `::follower followtrace` **[dev]** | Live follow-state overlay plus FTRACE log rows; `off` to stop |

**Outfits and animation**

| Command | Effect |
|---|---|
| `::follower anim <id...>` | Play an animation, or a chain of them, on the follower |
| `::follower outfit <name>` | Wear a saved outfit profile |
| `::follower errand` | Send the follower on an errand now |
| `::follower watch` **[dev]** | Print the id of every animation your character plays |
| `::follower stance <weaponId> [idle walk run attack]` **[dev]** | Show or hand-set a weapon's animations |

**Diagnostics** - all developer-gated

| Command | Effect |
|---|---|
| `::follower animinfo` **[dev]** | Frame count, duration, frameStep, restartMode, frame lengths, interpolation state |
| `::follower animtrace` **[dev]** | Trace the follower's and your character's pose frames side by side for ~8s |
| `::follower wrapearly <n>` **[dev]** | Override the loop trim for the animation currently playing |
| `::follower wrapauto` **[dev]** | Drop manual trims and let measurement decide |
| `::follower boat` **[dev]** | Dump the world views, collision grid and objects around you, for boats; walked tiles overlaid |
| `::follower cachecheck` **[dev]** | Diff the live cache parse against the offline dump, field by field |
| `::follower stanceaudit` **[dev]** | Validate every animation id the stance library names, and report weapon coverage |
| `::follower chatwatch` **[dev]** | Print every chat line with its `ChatMessageType`, for writing `chatMessage` rules |
| `::follower mood [0-100]` **[dev]** | Read or set the mood |
| `::follower fire <rule-id>` **[dev]** | Say a rule now, whatever its conditions, cooldown and the mute say |
| `::follower want` **[dev]** | What the follower is hoping for, and whether a question is open |

`::follower fire` is how you test a rule you cannot conveniently provoke - several
only happen on a five percent roll after a minute of standing still, or on the
hundredth login, and waiting for one of those in a live client is hoping rather
than testing. It runs the **whole** rule, not just the words: the animation, the
mood nudge, `asks` and `want` all take effect, which is the point. Placeholders
that come from an event (`{npc}`, `{want}`, `{value}`) have no event to come from
and will print literally.

`animtrace` is the useful one for animation problems: it prints both models' frame
sequences collapsed into `frame xHeld` runs, so a stall shows as an oversized hold, a
restart shows as a jump to 0, and a speed mismatch shows in the averages. Comparing
against the player is what makes it conclusive - the reference implementation is
standing right next to the follower.

`::follower where` is the fast way to get region ids and coordinates while writing
`regionEnter` and `inArea` rules. `::follower watch` is how you find an animation id
by performing it rather than guessing from community lists - it needs **Developer
commands** switched on, being an authoring tool rather than a playing one.

---

## Appearance and body style

The **Follower Buddy** side panel (right-hand toolbar) has five folding
sections: Talking, Memory, Outfit, Behaviour and Commands. The outfit lives
under **Outfit**, below the profiles row, as two sub-headers.

**EQUIPMENT** - the nine real equipment slots. Click one to search all 6,333 wearable
items by name; the slot is resolved from the item itself, so a chestplate cannot land
in the legs slot. Hover a result to preview it; right-click a slot to clear it.

**BODY** - body type, skin tone, and a style cycler per body part (hair, jaw, torso,
arms, hands, legs, boots). Kits carry no names in the cache, so styles are browsed with
`<` `>` and judged by eye; the follower rebuilds instantly, which makes it its own
preview. Only kits matching that slot *and* body type are offered, using each kit's
`bodyPartId` from the dump.

### How following works - the game's own mechanics (2026-08-02)

The movement is a faithful implementation of the real follow system, researched
from the preserved RS2 engine and client sources (LostCity), then verified with a
live trace against a running player. Three layers:

**The follow rule.** Every entity's `followX/followZ` is the tile it last stepped
FROM; a follower re-paths to that tile whenever it finishes its route. That single
rule produces everything: trailing your exact path, stopping one tile behind,
briefly overshooting a double-back.

**The server gate.** Steps are released into the render route at most one per tick
walking, two running - so the follower physically cannot outpace the player. A
run-enabled follower takes two steps even while its target walks, until caught up.
Releases are phase-locked to observed player movement (one release per observed
server tick), because an independent clock drifts and every phase slip loses an
unrecoverable tile. A 3–4 tile observation is two ticks sampled by one slow frame,
not a teleport, and releases twice.

**The client renderer** (`routeMove`, constants verbatim): fine position in
128-units-per-tile world space; speed 4 per 20ms cycle walking, 2 while turning,
doubled on run steps; yaw turned at 32/2048 per cycle toward the 8-direction of
travel; run animation at speed ≥ 8; axis-independent stepping (diagonals genuinely
faster); snap when a step exceeds two tiles. Two deliberate adaptations for
RuneLite's variable frame rate: consumption is continuous (fractional cycles) so
whole-cycle quantisation can't beat against the fps, and the client's queue-depth
catch-up tiers only engage past depth 6 - at normal depths they let the render
sprint ahead of its enqueue phase and visually collapse the running gap onto the
player (verified by trace; the burst exists to recover post-stall backlog only).

Verified steady state: logical gap exactly 1 tile at all times; rendered gap
averaging ~1.5 tiles while running (min 0.2, max 2.8 over a 60s trace).
`::follower followtrace` toggles a live overlay of the follow state plus FTRACE
rows in the client log.

Path steps respect collision with the game's corner rule: a diagonal is legal
only when the target and both flanking cardinal tiles are open with no walls
crossing any edge - so the follower rounds fence corners instead of cutting
through them, both when building its route and when reconstructing the
intermediate tile of a two-tile running observation. Blocked or data-less tiles
fall back to the raw step so the follower never freezes.

Directional poses are the client's own: the follow op face-locks the follower
on the player (an interaction holds facing on its target), and routeMove picks
the pose from the SIGNED difference between the travel heading and the current
yaw - forward walk within a quarter-turn, side-steps out to three-quarters
either way, back-pedal beyond, with the run animation only ever replacing the
FORWARD walk. The turn slowdown (speed 2) applies only without a face target,
also per routeMove. An idle face-locked follower turns in place with the turn
pose as the player circles it. The five directional animations (back, side
left/right, turn left/right) are learned per weapon by `StanceLibrary` exactly
like idle/walk/run; entries saved before those fields existed fall back to
forward walk until the weapon is seen again. Walking to a Send target is the
one unlocked case - that is a Walk-here click, not an interaction, so facing
follows travel.

(Both prior refinements - full BFS pathfinding and the directional poses -
have landed; no known movement gaps remain.)

### Body colours

Every colour button opens a swatch grid over the game's own palette tables - 30
hair, 29 torso, 29 legs, 6 boots, 13 skin - recovered from the client (see the
hair-colour section below). The choice is stored as a palette index and rendered
through the client's own find/replace, so the follower can never wear a colour the
game cannot produce. Jaw follows hair, arms follow torso, hands follow skin, as in
the game. To copy your current look exactly instead, `::follower palette`.

---

## Planned features - ALL FOUR DELIVERED (assessed 2026-08-02, closed 2026-08-06)

Kept as the original assessment and its outcome. Build order was 4 -> 2 -> 3 -> 1:
facing is the primitive, posing uses facing, rule actions use posing's animation
plumbing, and the right-click menu fronts all of it.

1. **Right-click menu on the follower.** DONE: `onMenuOpened` tests the click
   against the follower's clickbox and injects its entries. No native entry is
   possible (the game doesn't know the object exists), but `MenuOpened` + a
   clickbox test via `Perspective.getClickbox` (we hold the model, orientation and
   location) injects "Talk-to" / "Pose" / etc. entries with `onClick` handlers -
   the standard technique. Left-click stays Walk here, and entries coexist with an
   NPC's if one overlaps the follower. Note `Perspective.getClickbox` is marked
   internal in the API (it backs `TileObject#getClickbox()`, which a
   RuneLiteObject can never provide), so it is a watch item across client updates.
2. **Posing - go there / play animation / say.** DONE (movement): `stayTile` is
   a posed destination that PERSISTS - unlike Face-me's `goalTile`, the player
   walking away does not release it. Right-click the follower for **Stay** (hold
   where it stands) or **Follow** (release); SHIFT-right-click any ground tile for
   **Send** (path there and hold; the entry sits at the BOTTOM of the menu so it
   can never steal Walk here's left-click). While posed the follow tile is never
   pathed, the arrival stance faces the player, and the leash widens to 40 tiles
   - any forced relocation (plane change, leash snap, scene recovery, despawn)
   releases the pose. Animation and speech remain commands (`anim`, `say`) plus
   the Wave/Dance menu entries.

   Posed routes come from `findPath` - the client's own Walk-here pathfinder
   (`tryMove`), ported structurally verbatim: a scene-wide BFS recording the
   first-reached direction per tile, neighbours expanded in the client's exact
   W/E/S/N/SW/SE/NW/NE order (the tie-breaker that picks the same corners a
   real player takes), edges tested with the same `canStep` rules the movement
   executor was verified against, and the closest-approach fallback (one ring
   around an unreachable target, lowest flood cost under 100) - so Sending into
   a fenced yard walks the follower up against the fence exactly like your own
   Walk here. Refused only when nothing near the target is reachable, or the
   target sits past the stay leash. Following keeps the verified greedy append
   for its steady-state pathing (the smart-re-path-every-tick experiment was
   reverted for feel - see Known limitations), with the BFS used when
   re-acquiring from more than 3 tiles away.
3. **Condition-triggered animations.** DONE: rules may be animation-only (`say`
   optional once `animation` or `mirrorAnimation` is present), `mirrorAnimation`
   replays the player's own animation id, and the bundled `mirror-teleport` rule
   copies teleport casts (`animationSelf` on the known teleport ids; extend the
   list with `::follower watch`, which needs **Developer commands** on). The existing snap rule relocates the follower
   on landing, which reads as teleporting along. Spell GFX mirror too: the
   cache-dumper now emits `spotanims.json` (graphic id -> model, animation,
   scaling, recolours, lighting), and a mirror firing opens a 12-tick window in
   which the player's spotanims are copied onto the follower as transient
   RuneLiteObjects.
4. **Face the player.** DONE: `facePlayer()` is an atan2 into `dstYaw`, composed
   into Talk-to and the posed states. It grew a `faceLocked()` notion around it -
   thrall combat, errands and held poses hold their facing while the body
   strafes and back-pedals, which is also what selects the directional poses.

## The dialog box and chathead

A conversation drawn over the chatbox in the game's style: real chathead, speaker
name, click-to-continue pages, and branching "Select an Option" menus.

**Why it is drawn, not driven.** The real dialog widgets (`CHAT_LEFT` 231 /
`CHAT_RIGHT` 217) exist only while the game itself has a conversation open, and
no `ScriptID` opens one. A widget also renders only models the cache holds -
`setModelId` takes an id, and there is no `setModel(Model)` - so the follower's
composed head could never appear in one. `ChatheadRenderer` therefore software-
renders the model instead.

**Every visual property is measured or ported, not guessed.** The values below
came from live widget sniffing (`onWidgetLoaded` on the dialog groups, which logs
the whole widget) or line-for-line from the preserved client:

| Property | Value | Source |
|---|---|---|
| Head angle | `rotationZ`: NPC 1882, player 166 (exact mirrors) | sniffed; drives the renderer's yaw, NOT a Z-roll |
| Camera pitch | `rotationX` 40 - orbits the CAMERA, not the model | sniffed + `drawInterface` |
| Zoom | `modelZoom` 796 | sniffed |
| Projection | `eyeY=sin(rx)*zoom`, `eyeZ=cos(rx)*zoom`, then perspective divide | `Client.drawInterface` |
| Widget lighting | `calculateNormals(64, 768, -50, -10, -50)` | `IfType.getTempModel` - differs from actors' `(64,850,-30,-50,-30)` |
| Colours | 65,536-entry gamma-corrected table, interpolated as PACKED indices | `Pix3D.initColourTable` / `gouraudTriangle` |
| Rasterizer | integer scanline walker, 16.16 x steps / 15-bit colour steps | `Pix3D.gouraudTriangle` + `gouraudRaster`, ported verbatim in `GouraudRasterizer` |
| Draw order | integer depth buckets + face-index order, then the 12-class priority algorithm | `Model.draw2` / `calculateBoundsCylinder`, ported in `ChatheadRenderer.resolveDrawOrder` |
| Brightness | varp 166: 1..4 -> gamma 0.9/0.8/0.7/0.6 | `Client.clientVar`; read at login and on change |
| Head origin | widget centre, measured (69, 75) in the chatbox | sniffed |
| Clip | interface rect (inset 7, 6) + 14px | `drawInterface` sets the clip to the component's bounds; the 14 offsets our slightly larger projection |
| Fonts | The game's OWN glyph bitmaps, blitted (`GameFont` + fonts.json from the cache-dumper) | dialog text is fontId 497 = `q8_full` (Quill 8!) - the widget sniff settled it; the wiki's "Plain 12" was wrong. Overhead text is `b12_full`. TTFs can never be pixel-exact |
| Text cells | name y=23, body (39,67) centred, continue y=103; column 380 wide at x=115 (NPC) / x=24 (player, head right at 433,59) | sniffed live widgets |
| Body spacing | lineHeight by line count: 1→16, 2→28, 3→20 | sniffed; the game really spaces two lines wider than three |
| Options menu | header "Select an option" dark red at (20,22), swords sprite 302/301 at (92,24)/(370,24), option step 36−4n from measured tops 46/39/40 (3/4/5 options) | sniffed across three menus |
| Name / continue | `DARKRED 0x800000` / blue `0x0000FF`, hover white; "Please wait..." in flight (base blue on continue, hover-white kept on options) | sniffed + client source |
| Tick gate | open/advance/choose/dismiss resolve on the next GAME TICK via `FollowerDialog.tick()` | the real dialog's server round trip |

**Traps worth remembering.** Backface culling is required or the back of the
head bleeds through the face. Perspective-divided spans are far below 1.0, so a
`max(1f, span)` guard silently shrinks everything tenfold. A float
pixel-centre-sampling rasterizer looks right until the triangles get small:
sub-pixel faces (the pupils of the eyes) miss every sample point and vanish, and
one-pixel cracks open along edges shared with culled faces, letting the
parchment show through as stray light pixels - which is why the fill is the
client's own integer scanline rasterizer, ported verbatim, fed the same
truncated integer screen coordinates the client computes per vertex. Depth
sorting must ALSO be the client's: models layer coplanar detail (the pupil on
the eye-white face) by relying on integer depth buckets falling back to
face-index order, plus per-face render priorities. A float painter's sort
orders those coplanar faces by micro-depth noise instead, and the eye white
lands on top of the pupil on some animation frames.

**Tuning**, live while a dialog is open: `::follower head yaw|pitch|roll|zoom|crop|cliptop|talk <n>`,
or `::follower head tune on` for arrow-key control.

## Plugin hub submission

`runelite-plugin.properties` holds the listing metadata and `icon.png` (48x72,
the hub's maximum) sits in the repository root where the hub build looks for
it. The icon is generated, not hand-painted, so it can be regenerated or
tweaked reproducibly:

```bash
powershell -File tools/make-icon.ps1
```

What remains is process rather than code: fork
[runelite/plugin-hub](https://github.com/runelite/plugin-hub), add a manifest
naming this repository and a commit hash, and open a pull request.

### What a reviewer will want to know

The plugin is cosmetic and entirely client-side. It renders a `RuneLiteObject`
and talks; it never acts on the player's behalf.

- **No network access of any kind** - no HTTP client, no sockets.
- **No reflection, no `Class.forName`, no process spawning.**
- **It never sends input or packets.** Nothing invokes a menu action, writes to
  a packet buffer or synthesises a key press. The menu entries it adds are
  ordinary `createMenuEntry` options the player clicks themselves.
- **Files stay in `~/.runelite/follower`** - phrases, learned weapon stances,
  outfit profiles and measured animation trims. Sound files the player supplies
  live in the `sounds` subfolder beside them.
- **Two paths the player chooses themselves.** Sharing exports a `.zip` to a
  location picked in a `JFileChooser`, and imports one from the same. Those are
  the only reads or writes outside the profile folder, both are initiated by a
  button, and neither happens without the player picking the file.
- **An imported pack is treated as hostile,** because it came from somebody
  else. Entry names are matched against a fixed set of four rather than
  resolved as paths; entry and total sizes are capped before anything is read;
  JSON depth is counted before the parser sees it, so a nested pack cannot
  throw `StackOverflowError` past the handler; and every incoming line is
  refused if it carries chat markup or control characters, so a stranger's text
  cannot render a coloured or icon-bearing message in the chatbox. Text a pack
  supplies for display is sanitised before it reaches a Swing dialog.
  `PackRoundTripTest` and `HostilePackTest` are the two test classes for it.
- **Audio is `net.runelite.client.audio.AudioPlayer` on local files only.** No
  audio ships with the plugin and none is fetched. The Hub's automated check
  rejects direct `javax.sound` use and names this as the replacement; it is
  also the smaller code, since it opens a line per sound, closes it when the
  sound ends, and closes the previous line before starting the next - which is
  the one-mouth behaviour this used to keep by hand.
- **One NPC is hidden, narrowly.** In thrall mode the follower takes the place
  of your own summoned thrall, so that one NPC is not drawn. The predicate is
  `renderable != thrallNpc`, which is a single reference comparison against the
  thrall currently possessed and nothing while none is. It uses the same
  `RenderableDrawListener` the core Entity Hider plugin does.
- **Two API notes.** `Hooks.registerRenderableDrawListener` is deprecated with
  no replacement registration path yet, and core's Entity Hider uses the same
  one. `Perspective.getClickbox` is marked internal; it backs
  `TileObject#getClickbox()`, which a `RuneLiteObject` cannot provide.

Note `shadowJar` in `build.gradle` looks like dead code - nothing in this
repository calls it. **It must not be deleted:** the hub's packager invokes it to
produce the published jar. Removing it as unused turned a green hub build red,
which is how that was established.

The development-only Gradle tasks (`bundle`, `bundleZip`, `launcherJar`) are not
part of `build` and the hub never runs them. They assemble a runnable client for
a tester, which exists only because a plugin cannot be sideloaded into RuneLite
before it is published here.

## Standing clear of a fight

A following follower stands exactly where you want to look: on the boss, on the
tile you are about to click, in the middle of everything. It cannot actually
block anything - it is a client-side object with no collision - but being
visually in the way during a kill is problem enough.

`SpectateController` walks it clear when a fight starts and holds it there.

- **What counts as a fight.** Interacting with something that has a combat level
  and is still alive, or taking damage. Interaction alone is not enough, or
  talking to a banker would count. An eight-tick grace window keeps it steady
  across the gap between one target dying and the next being clicked, so the
  follower does not bob in and out mid-kill.
- **Where it stands.** Not merely "near you" - that could be between you and the
  boss. The vector from the target to you, continued past you, is the one
  direction guaranteed to be out of the line of the fight. Tiles are tried
  outward from there and then fanned to the sides, so a wall behind you degrades
  to the next best angle rather than giving up, and never within 3 tiles of the
  target. It re-seats only after being left 7 tiles behind, so it does not
  re-path every time you shuffle.
- **Who owns the feet.** Thrall mode is exempt, being in the fight by
  definition, and an errand in progress wins too - a follower halfway to a bank
  finishes the trip rather than being yanked sideways because something
  attacked.
- **What it says.** Nothing special: the ordinary rule system never stopped
  running, so health and prayer warnings arrive as they always did. The
  `combat`, `bossFight`, `combatStart` and `combatEnd` conditions simply let a
  rule ask about a fight the way it asks about anything else, and the bundled
  `combat` group uses them for encouragement.
- **The shield flourish.** While watching a boss it occasionally plays a casting
  animation with a graphic over it, as though warding itself. Both ids are
  settings, not constants: unlike a weapon's swing, which the game answers
  definitively, "what a protective shield looks like" has nothing to measure - 
  so pick by eye with `::follower gfx <id>` and `::follower anim <id>`.

## Skilling company

`com.follower.follower.SkillingController` keeps the follower company while the
player works. It is about the body and position, not words: nothing in it
speaks, and the plugin's rules hear of it only through a few events and the
trigger context (`working`, `xpDrop`, `workEnded`, `targetDepleted`, `bagFull`,
`bagHalf` in the condition table). Two settings, under **Skilling**:
`keepsCompany` ("Keeps you company while you skill", the master switch for the
seat and everything below) and `borrowsTools` ("Borrows your tools"). The pure
mirror rules that came with it (`bury-along`, `casting-along`) sit in the
`mimic` group, so the Mimicry toggle governs them.

**The session** (`WorkSession`). Three signals make one: the player's click on a
work option (Chop down, Mine, Fish, Net, Harpoon, Cage, Bait, Lure, Cook, Smith,
Smelt, Craft, Spin, Weave, Pray-at, Craft-rune, Rake, Plant, Harvest,
Check-health, Pick, Steal-from, Pickpocket, Build, Light, Burn, Bury, Lay,
Set-trap, Catch, Use, Feed), which names the target, its tile and its
footprint; the repeating animation; and the XP drops. A click alone opens
nothing - it waits up to 50 ticks for the animation or the drop that proves it
turned into work, and the first drop's skill overrides the guess the option word
made (a "Use" click could be anything). Fletching, herblore and crafting with
nothing to click open on their first drop, standing. A session ends when the
player walks more than 3 tiles from where they worked, goes 25 ticks with no
animation and no drop, opens a bank (unless the bank is within 40 tiles, when
the seat is kept for the trip, capped at 3 minutes), starts a fight, changes
plane or logs out. Ending releases the seat, gives the tool back and clears the
session. Object and NPC events are read only for the target's own tile - a
change of id there is the depletion, the interacted spot despawning is the spot
moving - and ignored during scene loads and for 2 ticks after.

**The seat.** Two tiles off the player's flank, never on the straight line
between them and the target, never on the target's footprint, never on a
same-name neighbour within 2 tiles (the likely next click), and only where the
existing pathing will take it (`stayAt` refusing means plain following, retried
every 10 ticks). Taken once the player has been at it 6 ticks, so a single
click never moves the follower; faced at the middle of the target; chosen again
when the player shifts to the next tree. Per-activity shapes: a miner's company
sits diagonal-behind, an angler's on the bank beside the spot, a cook's across
the fire (or along the range's side), a smith's across the anvil with the notes
out, a crafter's beside the wheel, sat down, a thief's five tiles off toward the
nearest guard, a foreman three tiles off the hotspot. The seat owns the feet:
`holdsTheFeet()` is registered beside the errand and spectate owners in
`stayIsPlayerCommanded`, so no Stay line fires because of it, and
`blocksErrands()` keeps the follower's own errands from starting mid-session.
Thrall mode and the spectator beat it.

**Borrowing** (`Borrow`). One item at a time through the same transient-prop
hands as the scroll (they share the weapon slot, so the tally scroll is skipped
while a tool is held), started at seat time. The mirror rules' disarm keeps a
borrowed item in hand, and every prop the follower takes out plays the reach in
`reachAnimation` on the way, so the thing does not appear in its fist.

**The hand is asked before the table.** A table of item ids cannot help but
fall behind the game: the first version of this borrowed a dragon felling axe
as a bronze one, and completing the table did not fix it. So the player's
weapon slot is read and matched by NAME - an axe that is not a battleaxe, a
greataxe, a thrownaxe or a pickaxe; a pickaxe; a harpoon or a rod - and only
when the tool is in the bag rather than the hand do the tables below answer.
The tool is preferred to a skilling outfit's hat, because the hands hold one
thing and a hat instead of your pickaxe is the worse trade.

**One tree is not the work.** A session that ends without being interrupted
keeps the tool in hand for `TOOL_GRACE_TICKS`, so walking to the next tree
carries on with what it is holding rather than handing it back and taking it
straight out again; different work swaps it, and a bank trip holds it for as
long as it holds the seat. The send-off is measured on the whole stint rather
than the last target, so an hour of chopping earns its bow though no single
tree lasted two minutes, and it waits until the player is plainly finished. A
fight, a plane change or anything else taking the follower puts the tool
straight back, since there is then nobody to hand it to. Farming is the one
exception: the patch inspector's notes need the hand the tool is in, so its end
is a real end. What is borrowed when the hand cannot answer:

| Work | Item |
|---|---|
| Woodcutting | the worn axe off the table (every wieldable tier in the cache, felling axes and ornament kits included), or bronze 1351 |
| Mining | the worn pickaxe off the table (likewise), or bronze 1265 |
| Fishing | by the animation: harpoon 311 or barb-tail 10129 with 618; fishing rod 307 or fly rod 309 with 622/623; nets and cages get nothing |
| Farming | secateurs 5329 |
| Construction | saw 8794 |
| Firemaking | torch 8987, if the cache says it is wearable |
| Smithing | rock hammer 4162, likewise |
| Hunter | butterfly net 10010 |
| Work with no tool of its own, in the full skilling outfit | the outfit's hat: lumberjack 10941, prospector 12013, angler 13258, farmer's 13646, pyromancer 20708, rogue 5554, carpenter's 24872 |

**The moments** (`Moments`), outside any session: a level is clapped (bowed, in
the first two days; every third level is walked over for, and 99 gets the boss
send-off chain); the pet-drop message sends it to stand beside the new pet, bend
to look, and come back, then keeps its wander close for ten minutes; run
turning off under 10% energy sends it ahead to look back once per low stretch;
an NPC with no combat level that spawns and turns to the player (a random
event) is faced, stepped back from and peered at until it goes. A moment
excuses a held seat and the seat is retaken after; thrall work, the spectator
and an errand under way beat a moment. So does the long-haul sit: a level is
then clapped from the seat (up off the sit and back down), and the walks are
not taken, since the rest pose would sit it down wherever it arrived.

**The news** (`Moments.noteworthy`). A drop, or a first-ever item, gets a
reaction sized to it, and the sizes are the loot rules' own numbers so the
words and the body agree about what is big: ten thousand is a glance at the
pile, a hundred thousand is the notes out where it stands facing the pile
and then a look up at the player with a nod, a million is walked over to and
written up on the spot before the look and the nod. In character a great
thing is written down rather than cheered, so the cheer and the clap the loot
rules used to carry have gone and the clap is for levels. A collection log
entry is medium news whatever it sells for, and it is in the bag, so the
write-up faces the player. With the hands full (a borrowed tool) or the
follower sat down for the long haul the scroll cannot come out and it is the
look and the nod on their own.

**The ledger** (`Gestures`). Every body beat the moments make - the glance,
the notes, the walk over, the peer, the wave - goes through a cooldown of its
own first, so no gesture repeats inside its stretch whatever the rules are
doing, and the ones meant to be rare stay rare; when one is spent the tier
below stands in. The lines have had this from the start; the body did not,
and a gesture that repeats is worse than a line that does. For the half hour
after the player did what the follower asked (`TriggerContext.isGrateful`)
every wait is halved: the favour is paid back in attentiveness.

**The farewell.** The logout tab opening is the last thing on screen before a
session ends, so it is a turn to face the player and a wave, from the seat
the seat's way, not twice in five minutes; the `farewell` condition carries
the rare line.

**The gaze** (`Gaze`, the Company section's *Looks where you look*). Where
the follower looks when nothing else owns its head, and head only: a real
facing (a seat, a goal, an errand at its business) outranks a glance. Three
sources, each pacing itself. The cursor: the menu the client would open says
what is under the mouse, and after it has rested on an NPC, a player, a piece
of scenery or a ground item for two ticks the head turns to it, once per
thing until the cursor leaves it. The camera: a swing of more than two
hundred JAU that then settles for two ticks, with the player standing still,
is a look shared four tiles the way the camera looks (camera yaw is the
actor circle a half turn round: yaw 0 sits south and looks north). And the
look round: standing about, every twenty to forty seconds it glances at
something nearby, an NPC or a player or failing that a direction, and then
back at the player, which is the attentive default. Somewhere it likes the
looks are slow and long; somewhere it dislikes, quick and often and always
at the nearest thing that moves. The research behind it is the plainest
there is: a robot that followed people's gaze was rated more likeable and
preferred three to one, without anybody being able to say why.

**Noticing** (`Noticing`, *Notices things*). What the follower notices that
the player has not, and goes to look at through the moments' walk, with one
line each through the `noticed` condition (`{kind}`, `{thing}`): a drop of
the player's own worth twenty thousand or more that they have walked five
tiles from (ownership off the tile item, so somebody else's is not their
business), a bird's nest on the ground, an impling in sight the player is
not already on, a crashed star (walked to and written up), and a signpost,
statue or plaque the player has stood beside for fifteen ticks (read with
the notes out - Elizabeth's points of interest, driven by where the player
stops rather than by a roll; the list is `Noticing.LANDMARKS`). Naughty
Dog's rule for Ellie's callouts is the whole design: a callout about a thing
the player cannot see reflects badly on the companion, so everything here is
checked to still be there and within ten tiles on the tick the follower sets
off, each thing is noticed once, and each kind keeps its own cooldown.
Ground items and objects that arrive with a chunk load are ignored, since
none of them is a drop the player just walked away from.

**The will** (`Will`). The beat before yes. A Send with a misgiving in it -
across the Wilderness ditch from the safe side, or to a spot within two
tiles of an NPC twenty combat levels over the player - is walked to its edge
first (the row south of the ditch; three tiles short of the monster, on the
follower's side), where the follower turns to look at the player for three
ticks and the `hesitated` condition carries the line (`{misgiving}` is the
kind, `{thing}` the Wilderness or the monster's name). Then it goes on if it
can: it cannot cross the ditch, so it waits at the edge; beside the monster
it goes, not hurrying. Once per ditch and per monster. And a parked
follower - a wander, a player's Stay, never a seat or a moment or an errand
- that the player walks straight at steps one tile aside at the last tick,
not before, and `steppedAside` carries the word about it. Ellie's dodge
rule, which the studio credits with making players respect her. Nothing
here refuses: a companion that refuses is an escort quest.

**Taste in the feet.** The wander already scaled its radius by mood; taste
now comes first. Somewhere the follower dislikes it keeps within two tiles
and, instead of going to look at things, keeps to the player's far side from
whatever is nearest; somewhere it likes it strays a tile further than usual.
The gaze does the rest (quick nervous looks, or slow long ones).

**Answering an emote** (the `answer-*` rules in the mimic group, and the
`addressed` condition). An emote done AT the follower - within a tile and
turned toward it, which `TriggerContext.isAddressingFollower` reads off the
player's orientation against the bearing to the follower's tile - is
answered rather than copied: a bow gets a bow, a cheer a clap, a clap a
bow, a dance a dance a beat behind, a raspberry anger, a yawn a yawn. The
answers outrank the mimic by priority for that one event, so the copy never
plays on top, and they are rules, so the table lives in phrases.json and a
new answer is a rule and not a code change. Every reply is an id the mimic
list already proves on the follower. The difference between an echo and a
reply is the difference between a mirror and company, and it is the closest
OSRS gets to petting the dog.

**Goodwill in the body.** For the half hour after the player does what the
follower asked (`isGrateful`), the gestures' cooldowns are halved and the
seat is taken in half the time (`SkillingController.seatAfter`).

**Walking beside** (`FollowerEntity.followTileFor`, *Walks beside you on
open ground*). Pets trail; friends walk alongside. After ten continuous
steps the follow tile becomes the tile at the player's shoulder - one to the
side of the line of travel, on whichever side the follower already is -
for as long as that tile is ground the player could step to. Two blocked
flanks in a row drop it back behind for eight steps before it tries again,
so a hedged path does not have it hopping in and out; a pause of more than
a second and a half ends the run; a stay pose or a goal, a fight, a pocket
being worked, a work session or thrall work keep it behind. Only the choice
of follow tile changes: the path model, the releases and the speeds under
it are the ones the trace sessions verified and are untouched, which is
the reason this is a tile choice and not a second follow model.

**Not in scope.** The agility course spectator (it needs a course table) and
the sailing deckhand (no verified deck stays). Both left out on purpose.

## Weapon stance coverage - PARKED, not a release blocker (2026-08-06)

Filling in the stance library is ongoing work that deliberately does **not** gate
the first release. At parking, 1,034 of the 1,619 weapon-slot items still fall
back to unarmed poses, across 376 weapon families, and 26 weapons have an
observed attack animation.

It can ship this way because the mechanism is finished and only the observed data
is thin. An unknown weapon falls back to unarmed poses plus a generic swing
matched to its combat style, so it looks *plain* rather than wrong - the same
principle that governs every borrowing rule above. Coverage also grows on its own
as the user walks past other players.

To pick it back up:

- `::follower stanceaudit` prints the current counts and lists every uncovered
  weapon in the client log. It and `::follower stance` both need **Developer
  commands** switched on, in the config's Developer section.
- [`stance-wishlist.md`](stance-wishlist.md) is that list grouped into families
  with one representative each, since metal tiers and bracketed variants inherit
  from a plain version. Regenerate it from a fresh audit.
- **Equipping** a weapon teaches its idle/walk/run - no combat needed. Only
  attack animations require actually fighting something.

## Party-visible followers: FUTURE ROUTE, not started (2026-09-02)

A recorded design, deliberately not built. The question was whether other
players could see your follower exactly as you do: gear, colours and speech.
The answer is yes for players in the same RuneLite party, and no for strangers,
and the party version is the one to build if this is ever picked up.

**How it would work.** RuneLite's `PartyService` lets plugins in a party
exchange messages over RuneLite's own websocket; core plugins share HP and
special attack state through it and Hub plugins may use it. A follower's
appearance is already a short string, the same one `OutfitProfileStore` saves:

```
HEAD=item:2581,WEAPON=item:841,TORSO=item:12596,...,colors=...
```

Broadcast that to the party when it changes and every member's client composes
the same model from the same cache with the code that already exists. Speech
travels the same way: send the line text as it is spoken and the receiver
draws it overhead. Sounds do not travel, since they are files on the sender's
machine.

**Position mostly need not travel.** Party members can already see the sender's
player, and the follow logic is deterministic given the player's movement, so
the receiving client can run the same algorithm against the sender's player and
arrive at the same tile. Position only has to be sent for the cases where the
follower is not following: stays, errands, spectating.

**Hard limits.**

- Both players need the plugin. Someone without it sees empty ground and
  nothing sent can change that. The honest name for the feature is "your party
  members with Follower Buddy can see your follower".
- Strangers cannot be reached without a server of our own tracking who is where
  with what. That is a privacy problem, would draw heavy Hub scrutiny, and would
  end the plugin's posture of contacting no external service. Do not build that
  version.
- Animations are the messy part. Stances are learned per machine, so the
  receiver may never have seen the sender's weapon used. Either transmit
  animation ids with events, or accept that the receiver animates from its own
  stance library. The item `category` finding (see `runWeaponAnims`) helps,
  since it makes a correct borrow far more likely.

**What it changes about the plugin.** The README and the reviewer notes say the
plugin has no network access. Party messaging goes over RuneLite's websocket, so
that claim would have to be withdrawn. Reviewers treat RuneLite's own party
infrastructure very differently from a third party server, so it is not a
blocker, only a claim to stop making. It would also be the first feature that
acts on data from another player's client. Incoming appearance strings and
lines must be validated the way share packs are: matched against what the
cache knows, size capped, and never trusted as paths or markup.

**Why not now.** It is a second rendering path, a message protocol and a trust
boundary, on a plugin that is mid review. It answers "nobody else can see it"
with "your friends can", which is the answer people actually want, and it
belongs to a later version rather than a point release.

## Location region ids - VERIFIED against the world map (2026-08-06)

The area rules' region ids were originally computed from documented world
coordinates (`region = (x >> 6) * 256 + (y >> 6)`), which is a guess wherever
the documentation is loose. They are now checked against the game's own data
instead of by walking:

```bash
cd tools/cache-dumper && gradlew runAudit --args="<phrases.json>"
```

`RegionAudit` reads the world map's labels - an `AreaDefinition` carries the
text the game draws ("Lumbridge", "Seers' Village") and a
`WorldMapElementDefinition` places it at a world position - so name plus
position yields the true region for every named place in the game. It reports
each rule as confirmed, adjacent (a town spans regions, so a neighbouring id is
the same place), or far off, and lists what the map labels **inside** the
rule's own regions.

All 55 region-based rules verified. The last seven only looked wrong because a
dungeon's entrance label sits on the surface, regions away from where the
player actually stands; the reverse lookup settles each one - region 11673
contains "blue dragons" and "chaos druids" (Taverley Dungeon), 6557 contains
"hellhounds" and "steel dragons" (the Catacombs), 11602 contains "Saradomin's
Encampment" (the God Wars Dungeon), and so on.

Re-run the audit after a game update that moves or renames an area.

## Rule targets - VERIFIED against the cache (2026-08-08)

A rule that watches for an NPC the game does not have is invisible: it never
fires, and there is nothing to notice. It looks exactly like a rule whose
trigger has not happened yet. The same holds for an `itemEquipped` id that is
one digit out.

```bash
cd tools/cache-dumper && gradlew runTargetAudit --args="<phrases.json>"
```

`RuleTargetAudit` reads every named NPC and item out of the cache and asks the
question the plugin will ask at runtime - whole-name, case-insensitive, `*`
wildcards - of every `npcSpawn` / `npcNearby` / `npcKill` name and every
`itemEquipped` id in the file.

It found one on its first run. `quest-gypsy-aris` watched for **"Gypsy Aris"**,
which is what the wiki calls her; the game calls her **"Aris"** (npc 11868 and
friends), so that rule could never have fired. The spoken lines still call her
Gypsy Aris, because that is what a player would say - only the trigger has to
match the game. 118 names and 700 item ids now all resolve.

Re-run after a game update that renames an NPC, and after adding rules by hand.

The same check exists for the names the PLUGIN hardcodes rather than the rules - 
the scene objects and NPCs the errands look for, and the standard pose ids:

```bash
cd tools/cache-dumper && gradlew runSceneAudit
```

All twelve errand names and all seven `PlayerPose` constants resolve
(2026-08-08). An errand looking for an object the game does not have would
simply never happen, with nothing logged either way.

## Known limitations

These are inherent to the approach, not bugs to be filed:

- **Two path generators, deliberately.** Posed movement (Send/Stay/Face-me) and
  far re-acquisition route through the client's BFS pathfinder; steady-state
  following uses the greedy append. The server-authentic alternative - smart
  re-path replacing the queue every observation, run mode from the varp, the
  client's verbatim catch-up tiers - was implemented and REVERTED 2026-08-04:
  the trace numbers were fine (avg gap 1.24 vs a slow drift to 3 on 15s+ runs)
  but the follow feel was off in game, and the greedy behaviour is the one the
  followtrace sessions verified. Directional poses (strafes, back-pedal) apply
  only in face-locked posed states; following walks facing its travel.
  UNTRIED avenue if follow feel is ever revisited: drive the observation from
  RuneLite's GameTick event instead of per-frame inference - one observation
  per genuine server tick would replace the "3-4 tiles = 2 ticks" heuristics
  with ground truth, and is the likeliest route to an exact-feel follower.
- **Pose animations come from observation, and cannot come from anywhere else.**
  `StanceLibrary` learns a weapon's idle/walk/run (and now its attack) by watching
  real players. This is not laziness - it was measured. Weapon animations are **not**
  in the cache as data: item params hold the combat stat block, item `category`
  disagrees with the observed stances 49 times out of 189, and not one of the
  cache's 3,986 structs carries a pose set. The client resolves them in script at
  equip time. Both probes are kept as `gradlew runProbe` and `runStructProbe` in
  `tools/cache-dumper` so the conclusion can be re-tested after a game update.

  Three things make observation cheap in practice. A starter library ships in the
  jar, so a fresh install already knows ~190 weapons. Every player in the scene
  teaches passively - a few minutes anywhere busy fills it out. And weapons cluster
  hard: those 190 weapons collapse into just **32 stance classes**, two of which
  cover 69% of them, so an unknown weapon's attack can be borrowed from a weapon of
  the same class. Seeing one scimitar swung covers every scimitar in the game.

  Variant markers are stripped generally rather than from a known list. The list
  rotted silently - it knew `(p)` but not `(kp)`, so every karambwan-poisoned
  spear and hasta sat uncovered beside the plain one it should have inherited
  from, and likewise the Bounty Hunter `(bh)` weapons, the Gauntlet's basic /
  attuned / perfected tiers and charge counts like `Enchanted lyre(2)`. Every
  bracket the game uses denotes a *state* of a weapon, never a different weapon.
  Measured before adopting: stripping all of them collapses the observed library
  into 16 multi-item groups, all 16 agreeing on their stance.

  Inheritance also crosses metal tiers: an Adamant longsword animates like a
  Black one. Plain tiers only (bronze through rune) - dragon, crystal, gilded and
  3rd age are excluded because the game special-cases them, which is measured
  rather than assumed. The plain tiers agree on their stance in all 5 observed
  multi-tier groups with no exceptions; admitting the ornamental tiers covers
  about 65 more weapons but introduces a real counterexample, the Dragon
  longsword standing at 809 where the Black longsword stands at 808.

  A stance class alone is **not** enough to borrow an attack, though. It groups
  weapons by how they are *carried*, and the 79-weapon class that walks like an
  unarmed player contains both the Dragon harpoon and the Bow of faerdhinen - 
  identical carry, completely different swing. Borrowing therefore also requires
  the combat style to match, taken from the item's own attack bonuses, and is
  refused outright when either weapon's style can't be established. Poses have no
  such problem: the class *is* the carry, which is why they borrow freely.

  Note that attacks start empty - the shipped library carries stances only, since
  attacks are learned from players seen swinging while interacting, and only a
  weapon actually used in combat records one. Until a class has been seen fighting,
  its weapons fall back to a generic swing for their combat style.
  `::follower stanceaudit` reports exactly where that stands.

  There is deliberately no setting for this. Three per-style attack pickers used to
  exist and were removed once the library could match the animation to the weapon in
  the follower's hands: a typed-in id could only ever contradict it, and for an
  unknown weapon a plain swing of the right kind beats whatever was last typed.
- **Animations must loop defensively.** Many pose animations have `frameStep = -1`,
  meaning they were never authored to loop. `AnimationController`'s default finish
  handler steps back by `frameStep` and, if the frame is still out of range, **drops
  the animation entirely** - the model silently freezes. `FollowerEntity.loopSafely`
  falls back to frame 0 instead. This was the cause of the end-of-cycle skip.
- **Do not touch `setAnimationInterpolationFilter`.** It is keyed on animation ID, not
  on the object being drawn - and the follower uses the same IDs as the player, since
  the stances are learned from real players. So changing interpolation "for the
  follower" silently changes it for the **user's own character**, disabling RuneLite's
  animation smoothing for them. There is no way to scope it per object; the plugin
  leaves it alone.
- **The pose slot is not auto-advanced.** `RuneLiteObject.tick()` advances only the
  main animation controller; the pose slot exists to be blended on top. A controller
  placed there alone renders a static model - the follower's walk/idle therefore lives
  in the main slot.
- **Captured models don't animate.** By construction; see the table above.
- **Capture mode borrows the shared actor model cache.** `Actor#getModel()` returns a
  reference into a cache the client recycles between actors, so a naively held model
  turns into whichever nearby NPC or player reused the slot. The capture path merges
  the model to detach it, but this is inherent fragility in the approach - the cache
  dump path composes its own geometry and is immune. Prefer it.
- **Equipment-based conditions miss rings and ammo.** `itemEquipped` reads the player
  composition, which only covers slots with a visible model.
- The region ids in the starter rules are **examples**. Verify with `::follower where`.
- The follower hides in the Wilderness by default so it can't obscure a real player.
  Turn that off under **Follower** (Hide in PvP areas) if you want.

---

## Animation skip at the loop point - SOLVED (2026-08-02)

**Mechanism, confirmed in client source.** RSSequenceDefinition's transform methods
compute `nextFrame = frame + 1` and set it to `-1` past the end - *"the last frame
is not interpolated"*. So with smoothing on, every frame of a loop glides toward
the next except the final one, which holds statically for its whole duration
(~420ms on a 21-tick frame) and then snaps to the loop target. Crucially the
**actor path does the same**: real players only look smooth because Jagex authors
loops with the last pose adjacent to the first. The follower's earlier trim
workaround was compensating for a hold that is baked into the engine.

**The fix - `WrapLerpController`.** Subclasses `AnimationController` and overrides
`animate()`: on every frame but the last it defers to the client; on the last it
poses the model at the true final frame and the true loop-target frame (both exact
static poses via `applyTransformations` with an unpacked index, loop target
replicating `loop()`'s `frames - frameStep` rule) and lerps vertices between them
using the same elapsed-tick clock. The wrap becomes indistinguishable from any
other frame boundary. Guards: pose-blend and Maya animations fall through to the
client; shared-vertex-array detection disables the lerp rather than corrupt models.

`::follower wraplerp off` reverts to the frame-trim workaround for comparison; the
trim machinery (`wrapearly` / `wrapauto`) is retained as the fallback, and takes
back over automatically if the lerp's safety guard ever trips.

Verified in game 2026-08-02: idle, walk and run all loop smoothly with Animation
Smoothing on, full frame count, no trim. One implementation note that cost a
debugging round: `applyTransformations` returns a model backed by the client's
shared animation scratch buffer - snapshot its vertices immediately and render
into a mergeModels-detached copy, never hold references to its output.

**Superseded history** (the trim era):

The follower pauses briefly at the end of a looping pose,
**only when RuneLite's Animation Smoothing is on**.

**Cause, established from source.** `AnimationSmoothingPlugin` sets a global
interpolation filter that allows every animation except a hardcoded blocklist, so the
follower's poses are interpolated. `AnimationController.getPackedFrame()` then hands
`applyTransformations` a frame index plus sub-frame progress, and the client
interpolates from that frame toward **frame + 1**. On the final frame there is no
frame + 1, so the pose is held for that frame's full duration - roughly a third of a
second - and then jumps.

A real player never shows this: the client's own actor path knows a pose loops and
interpolates across the boundary. `RuneLiteObject` goes through RuneLite's
`AnimationController`, which has no notion of wrapping.

**Current mitigation.** The follower wraps a few frames early so the dead-end frame is
never displayed. The trim is measured per animation: the model is posed at each
candidate wrap point and compared against frame 0, keeping whichever produces the
smallest vertex displacement. Saved to `wrap-trims.json`; `::follower wrapearly <n>`
overrides by hand, `::follower wrapauto` reverts to measurement.

**What is ruled out**, all confirmed by `::follower animtrace` comparing the follower
against the player side by side:

| Suspected | Finding |
|---|---|
| Wrong animation speed | Identical - same frames, same ~20-render holds, same cycle |
| Controller being recreated | 0 restarts over 480 frames |
| Animation dropping out | 0 frames drawn from the base model |
| A long final frame | Frame lengths uniform (all 21) |
| The loop handler | `frameStep = -1` does kill the animation by default; `loopSafely` fixes that, but it was not this |

**Still wrong.** Trimming trades the pause for a small jump, because the pose skips
whatever frames were trimmed. It cannot be blended away through this API:
`applyTransformations` always interpolates toward `frame + 1` with no way to redirect
the target, and its returned model is shared, so the model cannot be posed twice and
blended manually.

**Next avenue if resumed:** drive the model manually - hold the base model, call
`applyTransformations` once per frame, and copy the result before the next call
invalidates it. That would allow a real last-frame-to-first-frame blend. Substantial
work, and it duplicates what `RuneLiteObject` does internally.

**Do not** try to exclude the follower from interpolation: the filter is keyed on
animation id, and the follower shares ids with the player, so it disables the user's
own animation smoothing.

---

## Hair colour fidelity - SOLVED (2026-08-02)

**The mechanism, from the deobfuscated client:** body colours are two plain
find/replace passes per colour slot over the merged model, using palette tables
hardcoded in the client. The highlight is NOT derived from the base colour - it is
a separate source colour with its own replacement table. Every heuristic below was
approximating something that was never a computation.

**The tables are not API-reachable, so `::follower palette` reads them out of the
live game:** compose the player's outfit with no recolouring, diff lit face colours
against the player's own model (hue/sat survive lighting; luminance recovered by
relighting with all 128 candidates and keeping the one that reproduces the client
bit-exactly). Verified against known legacy table values - hair source 6798 →
white 107, skin 4550 → 5681 - every luminance uniquely recovered. The recovered
pairs are applied to the follower exactly as the client applies them, persisted in
config, restored on startup. `::follower palette clear` reverts to picker colours.

**The full tables are now recovered and shipped.** `::follower harvest` steps every
colour index of every body slot through the local composition client-side (the
same cosmetic mutation Fashionscape performs), extracting each entry - 104 runs,
all 30 hair (base + highlight), 29 torso, 29 legs, 6 boots, 13 skin. The classic-era
rows validate exactly against the known legacy tables. The result is hardcoded in
`GamePalette.java`, and `applyBodyColors` now uses it, so **any outfit carrying
colour indices (including `Copy my gear`) renders with exact colours for every
user, no commands needed**. Re-run the harvest after a game update that adds
colours.

**The picker now offers exactly the game's colours.** Hair, torso, legs, boots and
skin rows open a swatch grid over the real tables (30/29/29/6/13 entries); the
choice is stored as a palette INDEX (`colors=a/b/c/d/e` in the outfit string) and
rendered through the client's own find/replace, so the follower can never wear a
colour the game cannot produce. Jaw follows hair, arms follow torso, hands follow
skin - as in the game. Picking a colour while a `::follower palette` copy is
active first converts the copy to its equivalent indices (the pairs are table
entries, so the conversion is exact), then applies the pick.

**The heuristic era is gone.** The free-form colour chooser, the learned-hair
palette, `grabhair`/`hairbright`/`keephair` and the whole heuristic restyle were
removed in the 2026-08-02 cleanup - every one of them approximated tables that are
now known exactly. `color.PART=hsl:` keys in old outfit strings are ignored with a
warning; re-pick the colour in the panel. Two findings from that era worth
keeping: the lighting constants are the client's own, verified as
`toModel(64, 850, -30, -50, -30)` in the deob; and colours must round-trip as
packed HSL, never RGB, which loses precision both ways.

## API version

Builds clean (zero warnings) against **RuneLite 1.12.33** on JDK 17.

The plugin uses the current WorldView-based API rather than the deprecated
client-level equivalents - `client.getTopLevelWorldView().npcs()` /
`.getMapRegions()`, the `LocalPoint(int, int, WorldView)` constructor, and
`net.runelite.api.gameval.VarbitID` in place of the deprecated `Varbits`. If you
build against an older client, those four call sites are the ones to revert.

Sailing put the player in a sub-world. Aboard a boat the player's tiles resolve,
through `Client#findWorldViewFromWorldPoint`, to the boat's own `WorldView`, which
carries its own collision map and tile heights. The follower reads collision from
the view a tile resolves to and tags every point it draws with that same view, so
on deck it walks the deck. Reading the top-level map at the boat's scene
coordinates was how it came to walk the sea beside the hull.

Two more things the deck taught. The client pads a sub-world's collision map:
it is built (sizeX + 6) by (sizeY + 6) with both insets at -1, so scene (x, y)
lives at [x + 1][y + 1], while the top-level map has no inset at all. Read
unshifted, a deck's facilities came out open and the walkway between them
blocked, and the follower walked the facilities. `FollowerEntity.sceneFlags`
applies the inset and is the one place flags are read. And aboard, the boat is
the whole world: any tile that resolves to a different view is the sea, and it
reads as blocked whatever the sea's map says. Boarding is a jump into a world
whose map is still filling in, so the follower lands before the facilities
reach the map; aboard, a settled follower whose own tile has since turned
blocked hops to the spot beside the player. `::follower boat` dumps all of
this for the tiles around you, with the tiles you have stood on overlaid, so a
walked tile that reads blocked shows the map is being misread.

Note `CommandExecuted` lives in `net.runelite.api.events`, not
`net.runelite.client.events`.

### Verified against a real cache

The dumper was run against a live OSRS cache (July 2026) and produced **6,333
wearable items and 307 body kits** in a 0.4 MB file. The default body-kit ids in
`Outfit.withDefaultBody` were checked against that output and all thirteen resolve
to real models.

One thing the dump does *not* contain is item names, so there's no way to look up an
id from within the data file - use RuneLite's item search or the wiki to find the ids
you want for the outfit string.

## Project layout

```
src/main/java/com/follower/
├── FollowerPlugin.java          orchestration, event subscriptions, chat commands
├── FollowerConfig.java          config panel
├── appearance/
│   ├── Outfit.java              12-slot composition encoding + colour indices
│   ├── OutfitParser.java        the config-panel outfit string
│   ├── ModelRepository.java     loads equipment-models.json
│   ├── AppearanceComposer.java  loadModelData -> mergeModels -> palette -> light
│   ├── GamePalette.java         the client's body-colour tables, recovered exactly
│   ├── HslColor.java            packed-HSL helpers + swatch RGB approximation
│   ├── PaletteHarvest.java      append-only record of palette extractions
│   ├── ColorHarvester.java      ::follower harvest - steps every colour index
│   ├── CaptureFallback.java     capture path: composition swap, one-frame grab
│   └── AppearanceService.java   picks a path, memoises the result
├── follower/
│   ├── FollowerEntity.java      RuneLiteObject wrapper, trail walking, orientation
│   ├── WrapLerpController.java  interpolates looping animations across the wrap
│   ├── WrapTrimStore.java       persisted trims for the fallback wrap handling
│   ├── StanceLibrary.java       per-weapon idle/walk/run ids, learned by watching
│   └── PlayerPose.java          stand/walk/run animation ids
├── ui/
│   ├── FollowerPanel.java       the side panel: Talking, Memory, Outfit, Behaviour, Commands
│   ├── PhrasesDialog.java       Messages window: one category per rule group, Find, Sounds, Share
│   ├── DialogsDialog.java       Conversations window: the trees the follower starts
│   ├── VoiceDialog.java         Sounds window: attach a file to a line, the sounds folder
│   └── MemoryDialog.java        Memory window: what it knows, and Forget everything
└── speech/
    ├── RuleLoader.java          phrases.json load + hot reload
    ├── SpeechRule.java          one rule, plus its edge/cooldown state
    ├── Condition.java           the condition evaluator
    ├── TriggerContext.java      per-tick game state snapshot
    ├── TriggerEvent.java        one thing that happened
    ├── SpeechEngine.java        evaluation pass, priority, cooldowns, placeholders
    └── FollowerOverlay.java     projected speech bubble
```

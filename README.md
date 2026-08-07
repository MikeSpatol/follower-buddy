# Follower Buddy

Repository: <https://github.com/MikeSpatol/follower-buddy>

A RuneLite plugin that spawns a client-side follower rendered as a **player model**,
dressable in any gear, that speaks configurable phrases driven by game state.

Nobody else sees it. It is a local rendering only — no packets are sent, nothing is
automated, and the follower cannot be interacted with.

---

## Feature overview

- **Authentic follower** — pet-style following on the game's own mechanics, learned
  weapon stances, game-exact dialog boxes with chatheads, right-click menu, shift-hover
  and click cross exactly as a real NPC.
- **Speech system** — 240 bundled rules across editable groups (bosses, player
  statuses, locations, item equips, quest NPCs, thrall, errands, misc), hot-reloaded
  from `phrases.json` within a second. Editor windows for each group live behind
  buttons on the sidebar panel; every group has a config toggle.
- **Outfit profiles** — named outfits with Load/Save/Delete in the panel and a
  `::follower outfit <name>` command. Melee/Ranged/Magic style profiles are seeded.
- **Thrall mode** — summoning an Arceuus thrall replaces it with the follower:
  combat-proof varbit detection, smooth possession movement, per-style outfits, the
  authentic summoning circle carved from the thrall's own model, spawn/exit vfx, and
  survival across chunk reloads. The real thrall is hidden client-side.
- **Errands** — every so often the follower visits a nearby bank, prays at an altar,
  warms its hands at a fire (usually), attends to cats, and returns by foot or by
  teleport. All lines editable; frequency and per-errand toggles in config.

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
and the plugin rebuilds the model with `loadModelData` + `mergeModels` + `light` —
any item works, and the model is unposed so animations work.

(A capture path also exists in code — overwrite your own `PlayerComposition`,
steal the rendered model, restore — but captured models are already posed and
cannot animate, so it is no longer selectable; the dump is simply required.)

### How gear avoids clipping through the body

Merging every equipped piece plus a full set of body kits draws parts that are
meant to be mutually exclusive, and they intersect. The game does not solve this
geometrically — each item declares which slots it covers, in three cache fields
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

### Composition slot encoding — the item offset is 2048

`PlayerComposition#getEquipmentIds()` packs each of the 12 slots as:

```
0            empty
256 … 2047   kit    (value - 256)
2048+        item   (value - 2048)
```

**The item offset is 2048, not 512.** Getting this wrong corrupts every kit id above
255 — hair and jaw kits run past 300 — in both directions: kit 305 encodes to 561,
which reads back as "item 49", and real items decode to the wrong id entirely
(a Black cape, item 1019, read back as a nonexistent item 2555 and was silently
dropped). Symptoms were a follower missing gear in `Copy my gear` mode, a capture
path that swapped visual garbage onto the player, and `equippedItem` speech rules
firing on hairstyles.

Take these from `PlayerComposition.KIT_OFFSET` / `ITEM_OFFSET` rather than writing
literals, so a future change to the scheme follows automatically.

Verified: composing from a live `PlayerComposition` now produces a model identical
to the client's own — same face count, same distribution across priority buckets:

```
composed: 928 faces: p0x77 p1x166 p2x81 p3x135 p5x86 p6x211 p10x172
client:   928 faces: p0x77 p1x166 p2x81 p3x135 p5x86 p6x211 p10x172
```

`::follower priorities` runs that comparison on demand and then samples both
models' pose animation and frame for ~36 seconds, logging to the client log.

A second, independent source of clipping is vertical placement: `maleOffset` /
`femaleOffset` shift a worn model along Y before merging. 1,709 items have a
non-zero offset; `ModelRepository.Entry.offset(gender)` applies it.

Note that some interpenetration is genuine in OSRS — capes really do pass into
shoulders on the live client. The test for a bug here is whether the follower
clips **more** than your own character wearing the same gear.

**Cape collar "clipping": investigated and closed (2026-08-02).** The cape collar
visible over the shoulders from the front is the game's own rendering, verified
end to end: composed model identical to the client's build (face count and
priority-bucket histogram both exact), same idle animation at the same frames
(tested with loop trim disabled), and finally matched front-view screenshots of
the follower and a real player in the same gear showing the identical collar.
It reads as a follower-only bug because the camera orbits the player — you see
the follower from the front constantly and your own character almost never.
Before re-opening this, reproduce a *difference*: same gear, same camera angle,
follower and player side by side.

---

## Setup

### 0. Prerequisites

**JDK 17** (or 11+) with `JAVA_HOME` pointing at it. A Java 8 JRE will not build this.
Gradle itself is *not* needed — both this project and the dumper have wrappers checked
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
source is the way to use it — see [INSTALL.md](INSTALL.md), written for someone who
just wants to try it rather than work on it.

Add `-Plint` to surface deprecation and unchecked warnings.

### 2. Generate the model data (OPTIONAL — development only)

**As of 2026-08-06 the plugin needs no dumps at all.** Item, kit and spotanim
definitions are parsed straight from the client's own loaded cache at runtime
(`LiveCacheParser`), and the game fonts ship inside the jar. The runtime
parsers are validated byte-exact against this dumper's output with
`::follower cachecheck`. Dump files, when present in `~/.runelite/follower`,
override the live parse — useful for pinning a cache revision during
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

**Appearance → Custom outfit**, one entry per line:

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
set deliberately — it depends on the RuneLite client at runtime, which the published
plugin jar must not.

The task passes `--developer-mode`, which enables RuneLite's Dev Tools. That's how you
find NPC ids, region ids and varbits for writing rules.

Once the client is up: log in, open the plugin panel, enable **Follower Buddy**. Then
`::follower status` confirms the model dump and rules loaded.

### Jagex accounts

A migrated (Jagex account) login needs credentials that the **Jagex Launcher** injects
as `JX_*` environment variables. The dev client is started by Gradle, not the launcher,
so it doesn't get them.

RuneLite's own client jar has no Jagex auth code in it at all — the login is performed
by the injected vanilla game client, which reads those variables straight from its
process environment. So the fix is simply to put them in the dev client's environment.

Copy `jx.env.example` to `jx.env`, fill in the values, and `runClient` picks them up.
`jx.env` is gitignored — the values are live session tokens, treat them like a password.
The file explains where to read them from; they expire, so expect to refresh them.

Shell environment variables are deliberately *not* used here: the Gradle daemon retains
whatever environment it was first started with, so exporting them in your shell works
only sometimes. The file is read at task configuration time and always wins.

### Why not just sideload into the real client?

There's no sideload path. `~/.runelite/plugins` looks like one but is the plugin-hub jar
cache — `ExternalPluginManager` only loads jars named in the manifest it downloaded, keyed
by hash. Installing permanently means the plugin-hub submission route, which needs a
public repo and review.

## Writing phrases

Rules live in `~/.runelite/follower/phrases.json`, written from a starter file on first
run. **Save the file and it reloads within a second** — no client restart.

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
    "Prayer's nearly out — {prayer} points.",
    "Sip a restore, you're on {prayer} prayer."
  ]
}
```

### Firing model

Every rule is evaluated against every event, including a synthetic per-tick event, and
fires on the **rising edge** of its `when` block — the moment it becomes true having
been false. `healthBelow: 40` therefore speaks once when you cross the threshold, not
every tick you spend below it. When several rules fire at once, the highest `priority`
wins.

`cooldownMs` is per rule; **Speech → Minimum gap** is a global floor across all rules.

### Condition types

| Type | Fields |
|---|---|
| `all` / `any` / `none` | `conditions` — nest freely |
| `npcSpawn` / `npcDespawn` | `names` (case-insensitive, `*` wildcards) or `ids` |
| `npcNearby` | `names`/`ids`, `within` (tiles) |
| `healthBelow` / `healthAbove` | `percent` |
| `prayerBelow` / `prayerAbove` | `percent`, `requirePrayerActive` |
| `inRegion` / `regionEnter` | `regions`, `anyLoadedRegion` |
| `inArea` | `x1`, `y1`, `x2`, `y2`, `plane` |
| `chatMessage` | `contains` or `regex` |
| `varbitEquals` / `varbitChanged` | `varbit`, `value` |
| `animationSelf` | `ids` |
| `levelUp` | `names` (skill names, `*` ok) |
| `damageTaken` | `minimum` |
| `itemEquipped` | `ids` |
| `idle` | `ticks` |
| `combat` | — true throughout a fight, including the gaps between targets |
| `bossFight` | `minimum` — target's combat level, default 100 |
| `combatStart` / `combatEnd` | `names`, `{npc}` placeholder |
| `login`, `always` | — |
| `chance` | `percent`, rolled each evaluation |

`anyLoadedRegion: true` checks the loaded map regions rather than the tile you're
standing on, which is what you want for instanced dungeons and raids.

### Placeholders

`{npc}`, `{npcId}`, `{message}`, `{skill}`, `{level}`, `{damage}`, `{value}`, `{region}`,
`{hp}`, `{maxHp}`, `{hpPercent}`, `{prayer}`, `{maxPrayer}`, `{prayerPercent}`, `{player}`.

### Other rule fields

- `output` — `overhead`, `chatbox`, or `both`. Falls back to the config default.
- `group` — used by the **Rule groups** config section to silence whole categories.
  The built-in toggles cover `boss`, `health`, `area` and `idle`; anything else goes in
  the *Other disabled groups* box (rule ids work there too).
- `animation` — optional emote animation id played alongside the line.
- `mirrorAnimation` — `true` to replay the PLAYER'S animation on the follower
  instead of a fixed id. Only meaningful with an `animationSelf` trigger; the
  bundled `mirror-teleport` rule uses it to copy whichever teleport you cast.
  Spell graphics ride along automatically: for 12 ticks after a mirror rule
  fires, any spotanim shown on the player is copied onto the follower too — a
  transient `RuneLiteObject` carrying the spotanim's model (recoloured, scaled
  and lit per its cache definition from `spotanims.json`), despawning when its
  one-shot animation ends. Graphics attach to the follower on every axis — fine
  position, height and yaw, resynced each frame — because the client composites
  an actor's spotanim into the actor's own model and draws it at the actor's
  rotation; a graphic parked where it spawned sits askew as the follower turns.
  The server also sends a HEIGHT with every cast (`Actor.getGraphicHeight()`) —
  a teleport swirl wraps the body, a rune circle sits at the feet — and the
  client raises the spotanim model by it BEFORE resizing (`translate(-height)`
  then `resize`, from `ClientPlayer.getModel`), so the mirrored graphic applies
  the live height in that same order.
  Re-run tools/cache-dumper if `spotanims.json` is missing; the follower logs
  `player animation N` / `player graphic N` lines, so a teleport that fails to
  mirror can be diagnosed from the log.
- `animations` + `graphics` — a CHAIN of clips played back to back, each stage
  optionally opening its own spotanim (-1 skips). Takes precedence over
  `animation`/`mirrorAnimation`. The bundled `mirror-home-teleport` rule uses it
  to answer ANY home teleport variant — cosmetic overrides included — with the
  DEFAULT sequence (measured in game: animations 4847/4850/4853/4855/4857 paired
  with graphics 800/-/802/803/804), triggered by each variant's first-stage id.
- `syncToPlayer` — chain pacing. `true` advances each stage when the PLAYER's
  animation steps to its next stage — the server's own schedule — instead of
  when the follower's clip runs out. Teleport clips end in long hold frames the
  server always cuts short by starting the next stage, so clip-length pacing
  freezes for a second or two between stages; slaving to the player removes the
  freeze, paces correctly under overrides on different schedules, and ends the
  moment the player's sequence ends (with a 25-tick watchdog against desync).
- `note` — free text, ignored by the plugin.

`say` is optional when the rule plays an animation: an animation-only rule is
valid, and it skips the mute and the global speech gap entirely — those throttle
chatter, and a mirrored teleport is not chatter. It does still respect its own
`cooldownMs` and rising-edge semantics.

A JSON syntax error keeps the previously loaded rules rather than leaving you silent;
the problem is reported in the chatbox and the log.

---

## Chat commands

The everyday commands below are always available. The diagnostic ones — the
instruments used to build the plugin rather than to use it — are gated behind
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

**Appearance**

| Command | Effect |
|---|---|
| `::follower hidden` **[dev]** | Print which slots each equipped item covers, from its `wearPos` data |
| `::follower palette` **[dev]** | Copy your exact body colours onto the follower, extracted from the client's own tables |
| `::follower palette clear` **[dev]** | Drop the exact colours, hand control back to the pickers |
| `::follower harvest` **[dev]** | Auto-extract the client's complete colour tables (character flickers a few seconds); `stop` aborts |
| `::follower followtrace` **[dev]** | Live follow-state overlay plus FTRACE log rows; `off` to stop |
| `::follower height <n>` **[dev]** | Ground clearance, if the feet clip into terrain |

**Outfits and animation**

| Command | Effect |
|---|---|
| `::follower anim <id...>` | Play an animation, or a chain of them, on the follower |
| `::follower outfit <name>` | Wear a saved outfit profile |
| `::follower errand` | Send the follower on an errand now |
| `::follower watch` **[dev]** | Print the id of every animation your character plays |
| `::follower stance <weaponId> [idle walk run attack]` **[dev]** | Show or hand-set a weapon's animations |

**Diagnostics** — all developer-gated

| Command | Effect |
|---|---|
| `::follower animinfo` **[dev]** | Frame count, duration, frameStep, restartMode, frame lengths, interpolation state |
| `::follower animtrace` **[dev]** | Trace the follower's and your character's pose frames side by side for ~8s |
| `::follower wrapearly <n>` **[dev]** | Override the loop trim for the animation currently playing |
| `::follower wrapauto` **[dev]** | Drop manual trims and let measurement decide |
| `::follower pose <id>` **[dev]** | Force a looping pose (0 to release) |
| `::follower cachecheck` **[dev]** | Diff the live cache parse against the offline dump, field by field |
| `::follower stanceaudit` **[dev]** | Validate every animation id the stance library names, and report weapon coverage |

`animtrace` is the useful one for animation problems: it prints both models' frame
sequences collapsed into `frame xHeld` runs, so a stall shows as an oversized hold, a
restart shows as a jump to 0, and a speed mismatch shows in the averages. Comparing
against the player is what makes it conclusive — the reference implementation is
standing right next to the follower.

`::follower where` is the fast way to get region ids and coordinates while writing
`regionEnter` and `inArea` rules. `::follower watch` is how you find an animation id
by performing it rather than guessing from community lists — it needs **Developer
commands** switched on, being an authoring tool rather than a playing one.

---

## Appearance and body style

The **Follower outfit** panel (right-hand toolbar) has two sections.

**Body** — body type, skin tone, and a style cycler per body part (hair, jaw, torso,
arms, hands, legs, boots). Kits carry no names in the cache, so styles are browsed with
`<` `>` and judged by eye; the follower rebuilds instantly, which makes it its own
preview. Only kits matching that slot *and* body type are offered, using each kit's
`bodyPartId` from the dump.

**Slots** — the nine real equipment slots. Click one to search all 6,333 wearable items
by name; the slot is resolved from the item itself, so a chestplate cannot land in the
legs slot. Hover a result to preview it.

### How following works — the game's own mechanics (2026-08-02)

The movement is a faithful implementation of the real follow system, researched
from the preserved RS2 engine and client sources (LostCity), then verified with a
live trace against a running player. Three layers:

**The follow rule.** Every entity's `followX/followZ` is the tile it last stepped
FROM; a follower re-paths to that tile whenever it finishes its route. That single
rule produces everything: trailing your exact path, stopping one tile behind,
briefly overshooting a double-back.

**The server gate.** Steps are released into the render route at most one per tick
walking, two running — so the follower physically cannot outpace the player. A
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
catch-up tiers only engage past depth 6 — at normal depths they let the render
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

Every colour button opens a swatch grid over the game's own palette tables — 30
hair, 29 torso, 29 legs, 6 boots, 13 skin — recovered from the client (see the
hair-colour section below). The choice is stored as a palette index and rendered
through the client's own find/replace, so the follower can never wear a colour the
game cannot produce. Jaw follows hair, arms follow torso, hands follow skin, as in
the game. To copy your current look exactly instead, `::follower palette`.

---

## Planned features — ALL FOUR DELIVERED (assessed 2026-08-02, closed 2026-08-06)

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

- **No network access of any kind** — no HTTP client, no sockets.
- **No reflection, no `Class.forName`, no process spawning.**
- **It never sends input or packets.** Nothing invokes a menu action, writes to
  a packet buffer or synthesises a key press. The menu entries it adds are
  ordinary `createMenuEntry` options the player clicks themselves.
- **Files stay in `~/.runelite/follower`** — phrases, learned weapon stances,
  outfit profiles and measured animation trims. Nothing else on disk is touched.
- **One NPC is hidden, narrowly.** In thrall mode the follower takes the place
  of your own summoned thrall, so that one NPC is not drawn. The predicate is
  `renderable != thrallNpc`, which is a single reference comparison against the
  thrall currently possessed and nothing while none is. It uses the same
  `RenderableDrawListener` the core Entity Hider plugin does.
- **Two API notes.** `Hooks.registerRenderableDrawListener` is deprecated with
  no replacement registration path yet, and core's Entity Hider uses the same
  one. `Perspective.getClickbox` is marked internal; it backs
  `TileObject#getClickbox()`, which a `RuneLiteObject` cannot provide.

The development-only Gradle tasks (`bundle`, `bundleZip`, `launcherJar`) are not
part of `build` and the hub never runs them. They assemble a runnable client for
a tester, which exists only because a plugin cannot be sideloaded into RuneLite
before it is published here.

## Standing clear of a fight

A following follower stands exactly where you want to look: on the boss, on the
tile you are about to click, in the middle of everything. It cannot actually
block anything — it is a client-side object with no collision — but being
visually in the way during a kill is problem enough.

`SpectateController` walks it clear when a fight starts and holds it there.

- **What counts as a fight.** Interacting with something that has a combat level
  and is still alive, or taking damage. Interaction alone is not enough, or
  talking to a banker would count. An eight-tick grace window keeps it steady
  across the gap between one target dying and the next being clicked, so the
  follower does not bob in and out mid-kill.
- **Where it stands.** Not merely "near you" — that could be between you and the
  boss. The vector from the target to you, continued past you, is the one
  direction guaranteed to be out of the line of the fight. Tiles are tried
  outward from there and then fanned to the sides, so a wall behind you degrades
  to the next best angle rather than giving up, and never within 3 tiles of the
  target. It re-seats only after being left 7 tiles behind, so it does not
  re-path every time you shuffle.
- **Who owns the feet.** Thrall mode is exempt, being in the fight by
  definition, and an errand in progress wins too — a follower halfway to a bank
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
  definitively, "what a protective shield looks like" has nothing to measure —
  so pick by eye with `::follower gfx <id>` and `::follower anim <id>`.

## Weapon stance coverage — PARKED, not a release blocker (2026-08-06)

Filling in the stance library is ongoing work that deliberately does **not** gate
the first release. At parking, 1,034 of the 1,619 weapon-slot items still fall
back to unarmed poses, across 376 weapon families, and 26 weapons have an
observed attack animation.

It can ship this way because the mechanism is finished and only the observed data
is thin. An unknown weapon falls back to unarmed poses plus a generic swing
matched to its combat style, so it looks *plain* rather than wrong — the same
principle that governs every borrowing rule above. Coverage also grows on its own
as the user walks past other players.

To pick it back up:

- `::follower stanceaudit` prints the current counts and lists every uncovered
  weapon in the client log. It and `::follower stance` both need **Developer
  commands** switched on, in the config's Developer section.
- [`stance-wishlist.md`](stance-wishlist.md) is that list grouped into families
  with one representative each, since metal tiers and bracketed variants inherit
  from a plain version. Regenerate it from a fresh audit.
- **Equipping** a weapon teaches its idle/walk/run — no combat needed. Only
  attack animations require actually fighting something.

## Location region ids — VERIFIED against the world map (2026-08-06)

The area rules' region ids were originally computed from documented world
coordinates (`region = (x >> 6) * 256 + (y >> 6)`), which is a guess wherever
the documentation is loose. They are now checked against the game's own data
instead of by walking:

```bash
cd tools/cache-dumper && gradlew runAudit --args="<phrases.json>"
```

`RegionAudit` reads the world map's labels — an `AreaDefinition` carries the
text the game draws ("Lumbridge", "Seers' Village") and a
`WorldMapElementDefinition` places it at a world position — so name plus
position yields the true region for every named place in the game. It reports
each rule as confirmed, adjacent (a town spans regions, so a neighbouring id is
the same place), or far off, and lists what the map labels **inside** the
rule's own regions.

All 55 region-based rules verified. The last seven only looked wrong because a
dungeon's entrance label sits on the surface, regions away from where the
player actually stands; the reverse lookup settles each one — region 11673
contains "blue dragons" and "chaos druids" (Taverley Dungeon), 6557 contains
"hellhounds" and "steel dragons" (the Catacombs), 11602 contains "Saradomin's
Encampment" (the God Wars Dungeon), and so on.

Re-run the audit after a game update that moves or renames an area.

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
  real players. This is not laziness — it was measured. Weapon animations are **not**
  in the cache as data: item params hold the combat stat block, item `category`
  disagrees with the observed stances 49 times out of 189, and not one of the
  cache's 3,986 structs carries a pose set. The client resolves them in script at
  equip time. Both probes are kept as `gradlew runProbe` and `runStructProbe` in
  `tools/cache-dumper` so the conclusion can be re-tested after a game update.

  Three things make observation cheap in practice. A starter library ships in the
  jar, so a fresh install already knows ~190 weapons. Every player in the scene
  teaches passively — a few minutes anywhere busy fills it out. And weapons cluster
  hard: those 190 weapons collapse into just **32 stance classes**, two of which
  cover 69% of them, so an unknown weapon's attack can be borrowed from a weapon of
  the same class. Seeing one scimitar swung covers every scimitar in the game.

  Variant markers are stripped generally rather than from a known list. The list
  rotted silently — it knew `(p)` but not `(kp)`, so every karambwan-poisoned
  spear and hasta sat uncovered beside the plain one it should have inherited
  from, and likewise the Bounty Hunter `(bh)` weapons, the Gauntlet's basic /
  attuned / perfected tiers and charge counts like `Enchanted lyre(2)`. Every
  bracket the game uses denotes a *state* of a weapon, never a different weapon.
  Measured before adopting: stripping all of them collapses the observed library
  into 16 multi-item groups, all 16 agreeing on their stance.

  Inheritance also crosses metal tiers: an Adamant longsword animates like a
  Black one. Plain tiers only (bronze through rune) — dragon, crystal, gilded and
  3rd age are excluded because the game special-cases them, which is measured
  rather than assumed. The plain tiers agree on their stance in all 5 observed
  multi-tier groups with no exceptions; admitting the ornamental tiers covers
  about 65 more weapons but introduces a real counterexample, the Dragon
  longsword standing at 809 where the Black longsword stands at 808.

  A stance class alone is **not** enough to borrow an attack, though. It groups
  weapons by how they are *carried*, and the 79-weapon class that walks like an
  unarmed player contains both the Dragon harpoon and the Bow of faerdhinen —
  identical carry, completely different swing. Borrowing therefore also requires
  the combat style to match, taken from the item's own attack bonuses, and is
  refused outright when either weapon's style can't be established. Poses have no
  such problem: the class *is* the carry, which is why they borrow freely.

  Note that attacks start empty — the shipped library carries stances only, since
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
  the animation entirely** — the model silently freezes. `FollowerEntity.loopSafely`
  falls back to frame 0 instead. This was the cause of the end-of-cycle skip.
- **Do not touch `setAnimationInterpolationFilter`.** It is keyed on animation ID, not
  on the object being drawn — and the follower uses the same IDs as the player, since
  the stances are learned from real players. So changing interpolation "for the
  follower" silently changes it for the **user's own character**, disabling RuneLite's
  animation smoothing for them. There is no way to scope it per object; the plugin
  leaves it alone.
- **The pose slot is not auto-advanced.** `RuneLiteObject.tick()` advances only the
  main animation controller; the pose slot exists to be blended on top. A controller
  placed there alone renders a static model — the follower's walk/idle therefore lives
  in the main slot.
- **Captured models don't animate.** By construction; see the table above.
- **Capture mode borrows the shared actor model cache.** `Actor#getModel()` returns a
  reference into a cache the client recycles between actors, so a naively held model
  turns into whichever nearby NPC or player reused the slot. The capture path merges
  the model to detach it, but this is inherent fragility in the approach — the cache
  dump path composes its own geometry and is immune. Prefer it.
- **Equipment-based conditions miss rings and ammo.** `itemEquipped` reads the player
  composition, which only covers slots with a visible model.
- The region ids in the starter rules are **examples**. Verify with `::follower where`.
- The follower hides in the Wilderness by default so it can't obscure a real player.
  Turn that off under **Movement** if you want.

---

## Animation skip at the loop point — SOLVED (2026-08-02)

**Mechanism, confirmed in client source.** RSSequenceDefinition's transform methods
compute `nextFrame = frame + 1` and set it to `-1` past the end — *"the last frame
is not interpolated"*. So with smoothing on, every frame of a loop glides toward
the next except the final one, which holds statically for its whole duration
(~420ms on a 21-tick frame) and then snaps to the loop target. Crucially the
**actor path does the same**: real players only look smooth because Jagex authors
loops with the last pose adjacent to the first. The follower's earlier trim
workaround was compensating for a hold that is baked into the engine.

**The fix — `WrapLerpController`.** Subclasses `AnimationController` and overrides
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
shared animation scratch buffer — snapshot its vertices immediately and render
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

## Hair colour fidelity — SOLVED (2026-08-02)

**The mechanism, from the deobfuscated client:** body colours are two plain
find/replace passes per colour slot over the merged model, using palette tables
hardcoded in the client. The highlight is NOT derived from the base colour — it is
a separate source colour with its own replacement table. Every heuristic below was
approximating something that was never a computation.

**The tables are not API-reachable, so `::follower palette` reads them out of the
live game:** compose the player's outfit with no recolouring, diff lit face colours
against the player's own model (hue/sat survive lighting; luminance recovered by
relighting with all 128 candidates and keeping the one that reproduces the client
bit-exactly). Verified against known legacy table values — hair source 6798 →
white 107, skin 4550 → 5681 — every luminance uniquely recovered. The recovered
pairs are applied to the follower exactly as the client applies them, persisted in
config, restored on startup. `::follower palette clear` reverts to picker colours.

**The full tables are now recovered and shipped.** `::follower harvest` steps every
colour index of every body slot through the local composition client-side (the
same cosmetic mutation Fashionscape performs), extracting each entry — 104 runs,
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
skin — as in the game. Picking a colour while a `::follower palette` copy is
active first converts the copy to its equivalent indices (the pairs are table
entries, so the conversion is exact), then applies the pick.

**The heuristic era is gone.** The free-form colour chooser, the learned-hair
palette, `grabhair`/`hairbright`/`keephair` and the whole heuristic restyle were
removed in the 2026-08-02 cleanup — every one of them approximated tables that are
now known exactly. `color.PART=hsl:` keys in old outfit strings are ignored with a
warning; re-pick the colour in the panel. Two findings from that era worth
keeping: the lighting constants are the client's own, verified as
`toModel(64, 850, -30, -50, -30)` in the deob; and colours must round-trip as
packed HSL, never RGB, which loses precision both ways.

## API version

Builds clean (zero warnings) against **RuneLite 1.12.33** on JDK 17.

The plugin uses the current WorldView-based API rather than the deprecated
client-level equivalents — `client.getTopLevelWorldView().npcs()` /
`.getMapRegions()`, the `LocalPoint(int, int, WorldView)` constructor, and
`net.runelite.api.gameval.VarbitID` in place of the deprecated `Varbits`. If you
build against an older client, those four call sites are the ones to revert.

Note `CommandExecuted` lives in `net.runelite.api.events`, not
`net.runelite.client.events`.

### Verified against a real cache

The dumper was run against a live OSRS cache (July 2026) and produced **6,333
wearable items and 307 body kits** in a 0.4 MB file. The default body-kit ids in
`Outfit.withDefaultBody` were checked against that output and all thirteen resolve
to real models.

One thing the dump does *not* contain is item names, so there's no way to look up an
id from within the data file — use RuneLite's item search or the wiki to find the ids
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
│   └── FollowerPanel.java       outfit picker, body styles, exact colour swatches
└── speech/
    ├── RuleLoader.java          phrases.json load + hot reload
    ├── SpeechRule.java          one rule, plus its edge/cooldown state
    ├── Condition.java           the condition evaluator
    ├── TriggerContext.java      per-tick game state snapshot
    ├── TriggerEvent.java        one thing that happened
    ├── SpeechEngine.java        evaluation pass, priority, cooldowns, placeholders
    └── FollowerOverlay.java     projected speech bubble
```

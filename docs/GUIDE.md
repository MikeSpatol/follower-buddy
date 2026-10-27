# Follower Buddy: the full guide

The README has the short version. This is everything the follower does, and every
button, setting and command.

## What it does

**A follower rendered as a player model.** It wears any gear you choose from the
side panel - full equipment slots, body styles, hair and skin colours - and
follows using the game's own pet mechanics, so it walks, runs and turns the way
a real follower does, carrying its weapon the way a player would. Right-click it
for Talk-to, Stay, Follow-me, Step-in-front and a couple of emotes, or hold
Shift and right-click any tile to Send it there.

**It talks.** Nearly 2,500 lines across 623 rules, chosen by what's happening:
bosses sighted and fought, levels, deaths, drops, low HP and prayer, the places
you visit, the gear you put on, things you examine, famous quest NPCs nearby,
moments along the way in the quests its book covers, and long stretches of
nothing at all. Lines appear overhead, in the chatbox, or both. It never repeats
itself within a session's short memory, and lines you've heard many times give
way to fresher ones over the weeks. If it is still too much, Chattiness runs
from Minimal, about one remark a minute at most, up to Chatty. New installs
start on Occasional with the chatbox mirror off.


**It has a character.** A dry, understated field scribe who writes everything
down. It keeps a mood that your day moves, remembers one incident, where it
happened and when, and brings it up, and greets you more formally in the first
days than after a month together.

**It has a life.** Between events it copies your emotes and your teleports, has
a go at whatever you're skilling, wanders when you stand still and sits down
when you stand still for long enough. Take an agility obstacle or a shortcut and
it takes it after you, the same way and in the same order, falls included. Now
and then it goes on an errand - a nearby bank, an altar, a fire, a cat, or
something curious it wants to write up in its scroll - and comes back on foot or
by teleport. Tell it to Stay and it holds the post, notices when you come back,
and updates its notes while it waits.


**It keeps you company.** Start on a tree, a rock or a fishing spot and it works
alongside you: the same one when there is room, the next one along when there is
not, so at Motherlode it mines the vein beside yours. At a farming patch it
works the patch with you. It borrows the tool for the session (the axe or
pickaxe you are wearing, or a bronze one; a net, a rod or a rake; the hat, if
you have the whole outfit on) and gives it back with a bow when you are done,
and now and then it stops for its notes. On a trap run it keeps to one spot by
your traps and watches them. Anywhere else it takes a seat off to one side,
keeps a tally on its scroll, warms its hands at your fire and sits down for the
long haul. It claps a level, goes to see the pet, and if a stranger turns up to
talk to you it stands off and has a good look. In a cutscene it stays where it
is, keeps quiet and watches with you, and anything worth saying waits until the
scene is over. A good drop gets the scroll out; a great one it walks over to and
writes up on the spot, then looks up at you and nods. It knows a ninety-two is
halfway, a first ninety-nine from a third, and how long that level took. It
waves when you open the logout tab. It looks where you look: at whatever your
cursor rests on, the way you turn the camera, and round at what is nearby while
you stand about. And it notices what you have not, and goes to look: a drop of
yours you walked away from, a nest, an impling, a crashed star, the signpost you
stopped beside. Send it somewhere it has misgivings about, across the ditch or
up to something that could kill it, and it stops at the edge and looks back at
you first. Walk straight at it while it is parked and it steps aside at the last
tick, with a word. Do an emote at it, a tile away and facing it, and it answers
rather than copies: a bow gets a bow, a cheer a clap, a dance a dance a beat
behind. On a long run over open ground it draws level and walks at your
shoulder. Movement settings set how far behind it follows and how long you stand
still before it wanders.

**Talk-to.** A proper NPC-style dialog box with a chathead. Ask how today went
and it summarises the session; ask how it's been and it answers by mood. When it
has asked you something itself - an outing, a guessing game - Talk-to becomes
Answer and opens that conversation.

**Thrall mode.** Summon an Arceuus thrall and the follower takes its place, with
a per-style outfit, the summoning circle, and the real thrall hidden
client-side. **Thrall only**, in the Thrall mode settings, makes that the whole
plugin: the follower is absent until you summon a thrall, takes its place while
it lasts, phases out when it goes, and says nothing in between. As the thrall it
talks whenever the follower may; tick **Mute the thrall** and it keeps quiet
while it is the thrall.

## Making it yours

The **Follower Buddy** side panel is behind its icon on the right-hand toolbar.
It has five sections, each folding on its header; Talking and Outfit start open,
and the panel remembers which you leave open.

**Talking.** A **Mute the follower** tick, a **Chattiness** dropdown and an
**Also show lines in the chatbox** tick, each the same setting as in the
plugin's config. Under them, **Messages...** opens every line it can say, one
category per subject: Items, Locations, Bosses, Combat, Statuses, Idle chatter,
Quest NPCs, Errands, Mimicry, Reminiscing, The hour and the session, Reactions,
Thrall, Everything else. Each rule shows its id in grey beside its title, and
nearly every rule has a short note under it on when it is said. A **Find** box
narrows the category to rules containing the text.

**Memory.** One button, **Memory...**: everything the follower currently knows
about you, and **Forget everything** to erase all of it.

**Outfit.** **Copy my gear** and **Clear all**, then OUTFIT PROFILES: a dropdown
of saved outfits with **New profile...** and **Delete**; changes save to the
selected profile automatically. A folded **Used for thralls** row picks the
Melee, Ranged and Magic profile for Thrall mode, and a profile a thrall wears is
marked in the dropdown. The built-in looks and a profile a thrall wears cannot
be deleted. Under that, EQUIPMENT (click a slot to change it, right-click to
clear it) and BODY (style cyclers per body part, and the game's own colours).

**Behaviour.** A **Stay here** / **Follow me** button, the same switch as
right-click Stay and Follow-me on the follower; **Come here**; a **Go on
errands** tick; and a dropdown of errands with **Go now**.

**Commands.** **Come here**, **Fix if it vanished** and **Reload message
files**, the list of everyday chat commands, and your current region id for
writing Locations rules.

The follower's name is not in the panel. It is in the plugin's settings, under
Follower, with the rest of the settings in these sections: Follower (name,
Follow me, Mute, Chattiness, Hide in PvP areas), Movement (follow distance,
wandering, resting, agility), Speech (the chatbox tick, overhead text colour and
height, how long a line stays up, reading speed), Messages (a toggle per
subject, named as in the Messages window, and a box that silences groups or rule
ids by name), Thrall mode, Errands (a toggle per errand and a frequency),
Skilling (works beside you when skilling, borrows your tools), Company (looks
where you look, notices things, walks at your side), Combat and Advanced (closed
by default: the animation and graphic ids).

Everything it says lives in `.runelite/follower/phrases.json`, hot-reloaded
within a second of saving.

Chat commands: type `::follower` on its own for the list. The everyday ones are
`say <text>`, `here`, `fix`, `rebuild`, `copy`, `outfit <name>`, `height <n>`,
`errand [<name>]`, `reload`, `status` and `where`.

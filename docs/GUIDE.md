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

**It talks.** Over 2,700 lines across 671 rules, chosen by what's happening:
bosses sighted and fought, levels, deaths, drops, low HP and prayer, the places
you visit, the gear you put on, things you examine, famous quest NPCs nearby,
moments along the way in the quests its book covers, and long stretches of
nothing at all. Lines appear overhead, in the chatbox, or both. It never repeats
itself within a session's short memory, and lines you've heard many times give
way to fresher ones over the weeks. If it is still too much, Chattiness runs
from Minimal, about one remark a minute at most, up to Chatty. New installs
start on Occasional with the chatbox mirror off.

<!-- public:cut sharevoice -->
**You can give it a voice.** Attach a sound file to any line - one you wrote or
one that ships - and it plays when the follower says it, with the text still
appearing as usual. Attachments are keyed by the line itself, so reordering a
rule keeps every recording with its own words. The plugin ships no audio and
downloads none: the files are yours, in your own folder, and WAV, AIFF and AU
are the formats Java can play.

**You can share what you have written.** The Messages window exports your own
lines, and the sounds attached to them, as a `.zip` you can hand to somebody
else. A pack holds only what you added - never the lines that ship - and
importing one adds to yours rather than replacing anything. You see exactly what
a pack contains, and what was left out and why, before any of it is kept.
<!-- public:end sharevoice -->

**It has a character.** A dry, understated field scribe who writes everything
down. It keeps a mood that your day moves, likes some places and dislikes others
(and won't be argued out of two of them), remembers one incident, where it
happened and when, and brings it up, asks you for small things (a feather, some
papyrus) that you can hand over through Talk-to, makes predictions about drops
and has to live with being wrong, and greets you more formally in the first days
than after a month together. When your play has earned you a name in its notes,
it asks before it uses it.

**It has a life.** Between events it copies your emotes and your teleports, has
a go at whatever you're skilling, wanders when you stand still and sits down
when you stand still for long enough. Take an agility obstacle or a shortcut and
it takes it after you, the same way and in the same order, falls included. On a
rooftop course it watches for marks of grace and says so when you leave one
behind. Now and then it goes on an errand - a nearby bank, an altar, a fire, a
cat, or something curious it wants to write up in its scroll - and comes back on
foot or by teleport. Tell it to Stay and it holds the post, notices when you
come back, and updates its notes while it waits.

<!-- public:cut chronicle -->
**It keeps a chronicle.** Right-click it and choose Read-chronicle to open the
scribe's book, signed with its name and drawn to look like the game's own
Collection Log. When it writes a new page it says so, and each page records the
day it was written, in the world's own calendar, and where. It has a page for
each quest you have finished, telling what happened, who was involved, where it
took place and what is worth knowing about it, and pages for the bosses and
creatures you have met, the places you have walked into, and the factions and
lands behind them. Quests fill in from the game's own record, so one finished
years ago is already written; a creature or a place is written the first time
the scribe sees it, and stays written. Every page has pictures: the creatures
and people are drawn from their own models, turning slowly in their own idle
animation, as an outline in ink until you meet them; the places are sketched by
the scribe itself, with you in the middle of the drawing. Stand somewhere a page
wants a drawing of, right-click the follower and pick Sketch, then strike any
emote while it counts you in, or just stand still. Sketch is only on the menu
when the follower is settled and its hands are free. The drawing is made from
the game view itself, so anything RuneLite draws over the view, such as tile
markers or ground item names, is drawn in too. Ask again for a retake; the old
drawing is kept. Click any picture to see it larger. It starts small: eleven
quests, a miniquest and a handful of pages around them.
<!-- public:end chronicle -->

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
and it summarises the session; ask how it's been and it answers by mood; give it
the thing it asked for. When it has asked you something itself - an outing, a
guessing game - Talk-to becomes Answer and opens that conversation.

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
plugin's config. Then four buttons. **Messages...** opens every line it can say,
one category per subject: Items, Locations, Bosses, Combat, Statuses, Idle
chatter, Quest NPCs, Errands, Mimicry, Reminiscing, Souvenirs, Predictions, The
hour and the session, Reactions, Thrall, Everything else. Each rule shows its id
in grey beside its title, and nearly every rule has a short note under it on
when it is said. A **Find** box narrows the category to rules containing the
text, and every rule has a **Sounds** button that attaches a recording to any of
its lines. **Conversations...** edits the Talk-to trees the follower starts
itself. **Attach sounds...** opens the Sounds window on its own: the folder your
WAV, AIFF or AU files go in, with a button to copy the path. **Share lines...**
exports what you have written or takes on somebody else's pack; the Messages
window's **Share...** button runs the same flow.

<!-- public:cut memory-window -->
**Memory.** One button, **Memory...**: everything the follower currently knows
about you, and **Forget everything** to erase all of it, the Chronicle's
drawings included.
<!-- public:end memory-window -->

**Outfit.** **Copy my gear** and **Clear all**, then OUTFIT PROFILES: a dropdown
of saved outfits with **New profile...** and **Delete**; changes save to the
selected profile automatically. A folded **Used for thralls** row picks the
Melee, Ranged and Magic profile for Thrall mode, and a profile a thrall wears is
marked in the dropdown. The built-in looks and a profile a thrall wears cannot
be deleted. Under that, EQUIPMENT (click a slot to change it, right-click to
clear it) and BODY (style cyclers per body part, and the game's own colours).

**Behaviour.** A **Stay here** / **Follow me** button, the same switch as
right-click Stay and Follow-me on the follower; **Come here**; a **Go on
errands** tick; a dropdown of errands with **Go now**; and **Advanced
posing...**, a window for posing it. While it is open the follower holds still
and keeps quiet, and you pose it, turn it to a compass point or the camera, and
type the lines it says. Find a pose by name or animation id, or pick a group;
**Show all** adds the quest, cutscene and creature animations that also fit the
follower. A pose marked [prop] puts its book, quill or chair in the follower's
hands while it lasts. Lines you type for yourself show over your own head on
your screen only: nothing is sent, and nobody else sees them. Close the window
when you are done. The **Scene** tab writes a scene of dialogue boxes and chat
lines for you and the follower, saves it under a name, and plays it in the game
with **Play**. Advanced posing and its Scene tab show what you write on your own
screen only. A chat line in a scene looks like public chat, but it is never
sent.

**Commands.** **Come here**, **Fix if it vanished** and **Reload message and
sound files**, the list of everyday chat commands, and your current region id
for writing Locations rules.

The follower's name is not in the panel. It is in the plugin's settings, under
Follower, with the rest of the settings in these sections: Follower (name,
Follow me, Mute, Chattiness, Hide in PvP areas), Movement (follow distance,
wandering, resting, agility), Speech (the chatbox tick, overhead text colour and
height, how long a line stays up, reading speed), Sounds (play attached sounds,
volume), Messages (a toggle per subject, named as in the Messages window, and a
box that silences groups or rule ids by name), Thrall mode, Errands (a toggle
per errand and a frequency), Skilling (works beside you when skilling, borrows
your tools), Company (looks where you look, notices things, walks at your side),
Combat, Advanced (closed by default: the animation and graphic ids) and
Developer (closed by default).

Everything it says lives in `.runelite/follower/phrases.json`, hot-reloaded
within a second of saving.

Chat commands: type `::follower` on its own for the list. The everyday ones are
`say <text>`, `here`, `fix`, `rebuild`, `copy`, `outfit <name>`, `anim <id...>`,
`gfx <id>`, `pose <id>`, `posing`, `sketch`, `height <n>`, `errand [<name>]`,
`reload`, `status` and `where`. The diagnostic commands (`watch`, `stance`,
`mood`, `fire` and the rest) are gated behind **Developer commands** in the
Developer section, and typing one while it is off says so.

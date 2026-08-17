# Follower Buddy

A companion that walks behind you, dressed however you like, and talks about
what you're doing. Purely cosmetic and purely client-side: nobody else can see
it, it sends nothing to the game, and it never acts on your behalf.

![Follower Buddy](icon.png)

## What it does

**A follower rendered as a player model.** It wears any gear you choose from
the side panel - full equipment slots, body styles, hair and skin colours -
and follows using the game's own pet mechanics, so it walks, runs and turns
the way a real follower does. Right-click it for Talk-to, Stay, Follow, Face-me
and a couple of emotes.

**It talks.** Over 2,000 lines across 425 rules, chosen by what's happening:
bosses sighted and fought, levels, deaths, drops, low HP and prayer, the
places you visit, the gear you put on, famous quest NPCs nearby, and long
stretches of nothing at all. Lines appear overhead, in the chatbox, or both.
It never repeats itself within a session's short memory, and lines you've
heard many times give way to fresher ones over the weeks.

**It has a character.** A dry, understated field scribe who writes everything
down. It keeps a mood that your day moves, likes some places and dislikes
others (and won't be argued out of two of them), remembers one incident and
brings it up, asks you for small things (a feather, some papyrus) that you
can hand over through Talk-to, makes predictions about drops and has to live
with being wrong, and greets you more formally in the first days than after a
month together.

**It has a life.** Between events it copies your emotes, has a go at whatever
you're skilling, wanders when you stand still and sits down when you stand
still for long enough. Now and then it goes on an errand - a nearby bank, an
altar, a fire, a cat, or something curious it wants to write up in its scroll -
and comes back on foot or by teleport. Tell it to Stay and it holds the post,
notices when you come back, and updates its notes while it waits.

**Talk-to.** A proper NPC-style dialog box with a chathead. Ask how today went
and it summarises the session; ask how it's been and it answers by mood; give
it the thing it asked for. When it has asked you something itself - an outing,
a guessing game - Talk-to opens that conversation instead.

**Thrall mode.** Summon an Arceuus thrall and the follower takes its place,
with a per-style outfit, the summoning circle, and the real thrall hidden
client-side.

## Making it yours

Everything it says lives in `.runelite/follower/phrases.json`, hot-reloaded
within a second of saving. The side panel has editor windows for each group
(items, locations, bosses, combat, statuses, quest NPCs, errands,
conversations), and a **Memory** window that shows everything the follower
currently knows about you - with a button to erase all of it.

Config sections: Appearance, Movement, Speech (chattiness, output, reading
speed), Rule groups (a toggle per group), Thrall mode, Errands (a toggle per
errand type and a frequency), Combat.

Useful commands: `::follower say <text>`, `::follower outfit <name>`,
`::follower errand`, `::follower reload`, `::follower status`.

## Privacy and safety

- Nothing leaves your machine. The follower is a local render only; no packets
  are sent and no game state is changed.
- Nothing is automated. It cannot click, walk you anywhere, or interact with
  the world - it is a model and some text.
- What it remembers (tallies, bests, the incident, places, the day it met you)
  is stored in your RuneLite profile config and nowhere else. The Memory
  window shows all of it and erases it in one click.
- Item, animation and object identities come from the game cache and the
  RuneLite API; no external service is contacted.

## Notes for reviewers

- The plugin uses `Client#createRuneLiteObject` for the follower and a
  standard overlay for the dialog box; its right-click options are added on
  `MenuOpened` via `Menu#createMenuEntry`.
- Worn-item and body-kit models are composed at runtime from `ItemComposition`
  and kit data via `loadModelData` / `mergeModels`, so any item works and the
  model animates.
- Speech is a data-driven rule engine (`src/main/resources/com/follower/`),
  evaluated once per event; a full pass over all rules costs about 0.1 ms.
- The test suite (693 tests) covers the rule engine, the follower's movement
  and errand refusals, the dialog structure, and every bundled rule's
  integrity; see `docs/testing-strategy.md`.

## Documentation

- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) - how it works, the rendering
  approach, writing phrases, condition types, chat commands, and the full
  engineering history.
- [docs/voice.md](docs/voice.md) - the character's voice, for anyone editing
  or writing lines.
- [docs/lines.md](docs/lines.md) - every line it can say, organised by rule.
- [INSTALL.md](INSTALL.md) - building and running from source.

## Credits

Built by MikeS. Not affiliated with Jagex or RuneLite. Old School RuneScape
is a trademark of Jagex Ltd.

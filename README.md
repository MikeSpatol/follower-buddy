# Follower Buddy

A companion that follows you around, wears whatever you dress it in, and talks
about what you're doing.

Summon an Arceuus thrall and the follower can take its place, fighting beside
you in the outfit you choose for each combat style.

It is purely cosmetic and client-side: nobody else can see it, it sends nothing
to the game, and it never acts for you.

![Follower Buddy](icon.png)

## What it does

- **Follows you** as a player model in any gear you pick, walking, running and
  turning like a real follower. It takes agility obstacles and shortcuts after
  you, falls included.
- **Replaces your thrall.** When you summon an Arceuus thrall, the follower
  appears in its place in the summoning circle, wearing a Melee, Ranged or
  Magic outfit you pick, and swings its weapon as it fights. The real thrall is
  hidden on your screen only. Turn on **Thrall only** and the follower appears
  only as your thrall.
- **Talks** about what's happening: bosses, levels, drops, deaths, quests, the
  places you visit and the gear you wear. Around 2,500 lines, overhead or in the
  chatbox. Chattiness goes from Minimal (a remark a minute at most) to Chatty.
- **Has a character.** A dry, understated scribe with moods. It remembers
  moments you shared and brings them up, and asks you for small favours.
- **Keeps you company.** It skills beside you with a borrowed tool, claps your
  levels, writes up your best drops, and now and then goes off on an errand of
  its own.
- **Talk-to** opens a proper dialog box: ask how today went, or answer
  something it asked you.

## Getting started

Install Follower Buddy from the Plugin Hub. The follower appears beside you
when you log in.

- Right-click it for Talk-to, Stay and Follow me.
  Hold Shift and right-click a tile to send it there.
- The **Follower Buddy** side panel, behind its icon on the right-hand toolbar,
  has its outfit, how chatty it is, its memory of you, and every line it can
  say.
- Everything else is in the plugin's settings. Type `::follower` in chat for
  its commands.

The full guide to the panel, the settings and the commands is in
[docs/GUIDE.md](docs/GUIDE.md).

## Make it yours

- **Dress it** in any equipment, body style and colours, and save outfits as
  profiles.
- **Change what it says.** The Messages window lists every line. Edit, add or
  switch off any of them; your changes survive updates.

## Privacy

- The plugin sends nothing anywhere and changes nothing in the game. It cannot
  click, move you or interact with the world.
- What the follower remembers about you is kept in your RuneLite profile's
  settings, which RuneLite syncs if you have profile sync on. The Memory window
  shows all of it and erases it in one click.

## Notes for reviewers

- The follower is a `RuneLiteObject` from `Client#createRuneLiteObject`. Its
  model is composed at runtime from `ItemComposition` and kit data, so any item
  works and the model animates.
- Its menu entries are added on `MenuOpened` with `Menu#createMenuEntry`; over
  bare ground Talk-to is the left-click action, and over anything else the
  left-click stays theirs.
- Speech is a data-driven rule engine (`src/main/resources/com/follower/`). A
  full pass over every rule takes about 0.1 ms.
- 1,836 tests cover the rule engine, movement and every bundled rule; see
  `docs/testing-strategy.md`.

## More

- [docs/GUIDE.md](docs/GUIDE.md): the panel, settings and commands in full.
- [docs/lines.md](docs/lines.md): every line it can say.
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md): how it works, for anyone changing it.
- [INSTALL.md](INSTALL.md): building and running from source.

## Credits

Built by MikeS. Not affiliated with Jagex or RuneLite. Old School RuneScape is
a trademark of Jagex Ltd.

Many weapon stances and attack animations come from Weapon Animation Replacer
by geheur, used under its BSD 2-Clause License. The quest stages and areas the
follower watches for were checked against Quest Helper's data. See
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

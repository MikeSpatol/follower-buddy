# Follower Buddy

<!-- public:cut chronicle -->
A companion that follows you around, wears whatever you dress it in, and talks
about what you're doing. It also keeps a book about your adventures.
<!-- public:end chronicle -->
<!-- public:else A companion that follows you around, wears whatever you dress it in, and talks -->
<!-- public:else about what you're doing. -->

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
  <!-- public:cut chronicle,likes,nicknames,souvenirs-wishes,side-games,marks -->
- **Talks** about what's happening: bosses, levels, drops, deaths, quests, the
  places you visit and the gear you wear. Around 2,700 lines, overhead or in the
  chatbox. Chattiness goes from Minimal (a remark a minute at most) to Chatty.
- **Has a character.** A dry, understated scribe with moods, likes and
  dislikes. It remembers moments you shared and brings them up, asks you for
  small favours, and may give you a nickname, though it asks first.
  <!-- public:cut chronicle -->
- **Keeps a Chronicle.** A book in the style of the Collection Log, with pages
  for the quests you finish, the bosses, creatures and places you meet, and
  sketches it draws of you. Right-click the follower and pick Read-chronicle.
  <!-- public:end chronicle -->
- **Keeps you company.** It skills beside you with a borrowed tool, claps your
  levels, writes up your best drops, spots marks of grace on rooftop courses,
  and now and then goes off on an errand of its own.
- **Talk-to** opens a proper dialog box: ask how today went, or answer
  something it asked you.
<!-- public:end chronicle,likes,nicknames,souvenirs-wishes,side-games,marks -->
<!-- public:else - **Talks** about what's happening: bosses, levels, drops, deaths, quests, the -->
<!-- public:else   places you visit and the gear you wear. Around 2,500 lines, overhead or in the -->
<!-- public:else   chatbox. Chattiness goes from Minimal (a remark a minute at most) to Chatty. -->
<!-- public:else - **Has a character.** A dry, understated scribe with moods. It remembers -->
<!-- public:else   moments you shared and brings them up, and asks you for small favours. -->
<!-- public:else - **Keeps you company.** It skills beside you with a borrowed tool, claps your -->
<!-- public:else   levels, writes up your best drops, and now and then goes off on an errand of -->
<!-- public:else   its own. -->
<!-- public:else - **Talk-to** opens a proper dialog box: ask how today went, or answer -->
<!-- public:else   something it asked you. -->

## Getting started

Install Follower Buddy from the Plugin Hub. The follower appears beside you
when you log in.

<!-- public:cut chronicle -->
- Right-click it for Talk-to, Stay, Follow me, Sketch and Read-chronicle.
  Hold Shift and right-click a tile to send it there.
- The **Follower Buddy** side panel, behind its icon on the right-hand toolbar,
  has its outfit, how chatty it is, its memory of you, and every line it can
  say.
- Everything else is in the plugin's settings. Type `::follower` in chat for
  its commands.
<!-- public:end chronicle -->
<!-- public:else - Right-click it for Talk-to, Stay and Follow me. -->
<!-- public:else   Hold Shift and right-click a tile to send it there. -->
<!-- public:else - The **Follower Buddy** side panel, behind its icon on the right-hand toolbar, -->
<!-- public:else   has its outfit, how chatty it is, its memory of you, and every line it can -->
<!-- public:else   say. -->
<!-- public:else - Everything else is in the plugin's settings. Type `::follower` in chat for -->
<!-- public:else   its commands. -->

The full guide to the panel, the settings and the commands is in
[docs/GUIDE.md](docs/GUIDE.md).

## Make it yours

- **Dress it** in any equipment, body style and colours, and save outfits as
  profiles.
  <!-- public:cut messages-window -->
- **Change what it says.** The Messages window lists every line. Edit, add or
  switch off any of them; your changes survive updates.
  <!-- public:end messages-window -->
  <!-- public:cut sharevoice -->
- **Give it a voice.** Attach your own WAV, AIFF or AU file to any line.
- **Share your lines** with a friend as a `.zip` pack. Importing one adds to
  yours, and shows you what is inside before anything is kept.
  <!-- public:end sharevoice -->
  <!-- public:cut posing -->
- **Pose it** in Advanced posing, and write short scenes for it to play.
  <!-- public:end posing -->

## Privacy

- The plugin sends nothing anywhere and changes nothing in the game. It cannot
  click, move you or interact with the world.
- What the follower remembers about you is kept in your RuneLite profile's
  settings, which RuneLite syncs if you have profile sync on. The Memory window
  shows all of it and erases it in one click.
  <!-- public:cut chronicle -->
- Sketches are pictures of your game view, taken only when you ask for one and
  saved as PNGs in `.runelite/follower/chronicle-sketches`. Forget everything
  deletes the drawings of the accounts used on that RuneLite profile.
  <!-- public:end chronicle -->
  <!-- public:cut sharevoice -->
- Sounds and shared packs are files you choose. Nothing is bundled or
  downloaded, and an imported pack is checked before anything in it is kept.
  <!-- public:end sharevoice -->

## Notes for reviewers

- The follower is a `RuneLiteObject` from `Client#createRuneLiteObject`. Its
  model is composed at runtime from `ItemComposition` and kit data, so any item
  works and the model animates.
- Its menu entries are added on `MenuOpened` with `Menu#createMenuEntry`; over
  bare ground Talk-to is the left-click action, and over anything else the
  left-click stays theirs.
- Speech is a data-driven rule engine (`src/main/resources/com/follower/`). A
  full pass over every rule takes about 0.1 ms.
  <!-- public:cut sharevoice -->
- Audio goes through RuneLite's `AudioPlayer`, on files in the user's own
  folder. Packs are plain `java.util.zip` over a file the user picks, with
  fixed entry names and size caps; see `com.follower.share.PackFile`.
  <!-- public:end sharevoice -->
  <!-- public:cut chronicle,sharevoice -->
- 2,432 tests cover the rule engine, movement, the Chronicle, hostile share
  packs and every bundled rule; see `docs/testing-strategy.md`.
<!-- public:end chronicle,sharevoice -->
<!-- public:else - 1,836 tests cover the rule engine, movement and every bundled rule; see -->
<!-- public:else   `docs/testing-strategy.md`. -->

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

# Follower Buddy — try it out

A RuneLite client with the Follower Buddy plugin already in it. Nothing is
installed, nothing is downloaded, and nothing is changed on your system outside
your normal RuneLite settings folder.

## Running it

1. Install **Java 17 or newer** if you do not have it — [adoptium.net](https://adoptium.net/),
   take the LTS build and let the installer finish. This is the only requirement.
2. Unzip this folder anywhere.
3. **Windows:** double-click `run.bat`.
   **macOS / Linux:** `chmod +x run.sh` then `./run.sh`.

RuneLite opens with the plugin loaded. Find **Follower Buddy** in the plugin list
and switch it on; the **Follower outfit** panel is on the right-hand toolbar.

## Two things to know before you start

**Log in with a legacy account** — username and password. A Jagex account will not
work here. The Jagex Launcher passes the real client a set of login tokens, and a
client started by double-clicking never receives them. Nothing can be done about
that from this end; it goes away when the plugin is published to the Plugin Hub and
you install it the normal way.

**It uses your existing RuneLite settings.** Your layout, your other plugin settings,
your everything — all as usual, from `~/.runelite`. The follower keeps its own files
in `~/.runelite/follower` and touches nothing else.

## What it does

A second character follows you around: dressable in any gear in the game, moving on
the real follow mechanics, and talking. It reacts to bosses, places, gear you equip,
your health and prayer, quest NPCs it recognises, and it runs little errands of its
own. It can also stand in for your Arceuus thralls, and it steps out of the way and
watches when you fight.

Everything it says is editable — the buttons in its panel open the phrase tables.

## If it does not start

- **A window flashes and disappears** — run it from a terminal instead so the error
  stays on screen. On Windows: open the folder, type `cmd` in the address bar, press
  enter, then type `run.bat`.
- **"Java was not found"** — Java is not installed, or not on the PATH. Installing
  from the link above normally sorts both.
- **"Unsupported class file major version"** — the Java you have is too old.
  `java -version` should say 17 or higher.
- **Anything else** — the log at `~/.runelite/logs/client.log` usually names the
  problem in its last few lines.

## A note on what is in here

`lib/` holds RuneLite and the libraries it needs, copied as they are published. It is
a development build of the client, so Dev Tools appear in the sidebar and the console
says more than usual. Both are normal.

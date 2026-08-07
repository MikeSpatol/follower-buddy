# Trying Follower Buddy on another PC

Follower Buddy is not on the Plugin Hub yet, and **RuneLite has no sideloading**:
the client only loads external plugins that came from the Hub, checked against its
manifest, so dropping a jar into `.runelite` does nothing. Until it is published,
the way to run it is from source — which launches a normal RuneLite client with the
plugin built in.

It is less work than it sounds. Two steps, and nothing is installed system-wide.

## What you need

**A JDK, version 17 or newer.** [Adoptium](https://adoptium.net/) is the usual free
one — take the LTS build for your platform and let the installer add it to `PATH`.
A plain Java *runtime* is not enough; it has to be a JDK.

Nothing else. Gradle, RuneLite and every library download themselves on first run.

## Running it

**Windows** — double-click `run.bat`, or from a terminal in this folder:

```bash
gradlew runClient
```

**macOS / Linux**:

```bash
./gradlew runClient
```

The first run takes a few minutes: it downloads Gradle, RuneLite and the libraries
(a few hundred MB, cached afterwards under `~/.gradle`). Later runs take seconds.

A RuneLite window opens with Follower Buddy already loaded. Enable it in the plugin
list like any other plugin; the **Follower outfit** panel is on the right-hand
toolbar.

## Things worth knowing

**Log in with a legacy account** — username and password. A Jagex account cannot log
into a client started this way: the Jagex Launcher hands the real client a set of
session variables that a client launched from a terminal never receives. That is a
limitation of running from source, and it goes away once the plugin is on the Hub,
where the normal launcher does the work.

**It uses your existing RuneLite profile.** Settings live in `~/.runelite` exactly as
usual, so your layout and other plugin settings are the ones you already have. The
follower keeps its own files in `~/.runelite/follower`.

**No game data files are needed.** Item, kit and graphic definitions are read
straight from the game cache the client already has. Nothing to download or generate.

**It is a developer client**, so Dev Tools appear in the sidebar and the console is
chattier than usual. That is normal and harmless.

## If something goes wrong

- `JAVA_HOME is not set` or `Unsupported class file major version` — the JDK is
  missing or too old. `java -version` should say 17 or higher.
- The window opens and closes immediately — run from a terminal rather than
  double-clicking, so the error stays on screen.
- Anything else: the client log is at `~/.runelite/logs/client.log`, and the last
  hundred lines usually say what happened.

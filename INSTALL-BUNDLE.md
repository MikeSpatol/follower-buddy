# Follower Buddy — how to run it

This is a copy of RuneLite with the Follower Buddy plugin built into it. Nothing
gets installed, nothing is changed on your PC, and your normal RuneLite is left
completely alone. To remove it later, delete the folder.

Total time: about five minutes, most of it waiting for Java to install.

---

## Before you start: two things to know

**You need a legacy account** — the kind you log into with a username and
password. A Jagex account will not work here, and there is no way around it: the
Jagex Launcher hands the real RuneLite a set of login tokens, and a copy started
by double-clicking never receives them. If you only have a Jagex account, wait
for this to reach the RuneLite Plugin Hub and install it the normal way instead.

**It shares your RuneLite settings.** Your layout, your other plugins' settings,
your bank tags — all exactly as you left them, because it reads the same
`.runelite` folder your usual client does. It adds one folder of its own,
`.runelite\follower`, and touches nothing else.

---

## Step 1 — Install Java

Skip this if you already have Java 17 or newer. To check, see step 2 first.

1. Go to **https://adoptium.net/**
2. The big download button should already say **Temurin 21 (LTS)** and detect
   Windows. Click it. You want the **.msi** installer.
3. Run the downloaded file and click through it. **Leave the default options
   alone** — the defaults add Java to your PATH, which is what makes the rest of
   this work.
4. Let it finish.

---

## Step 2 — Check Java is working

1. Press **Windows key + R**, type `cmd`, press Enter. A black window opens.
2. Type this and press Enter:

   ```
   java -version
   ```

3. You should see something like `openjdk version "21.0.5"`. **Any number 17 or
   higher is fine.**

If it says *'java' is not recognized*, Java either did not install or was not
added to your PATH. Reinstall from step 1 and leave the options at their
defaults. A restart of the PC sometimes helps here.

You can close the black window.

---

## Step 3 — Unzip the folder

1. Right-click `follower-buddy-runnable.zip` → **Extract All...**
2. Pick somewhere easy, like your Desktop, and click **Extract**.

**This step matters.** Windows lets you open a zip and look inside without
actually extracting it. If you double-click the launcher from that preview
window, it will not work — nothing else it needs is really there. Make sure you
have a normal folder you can open, containing `run.bat`, `run.sh`,
`INSTALL-BUNDLE.md` and a `lib` folder.

---

## Step 4 — Start it

Double-click **`run.bat`**.

**If Windows shows a blue "Windows protected your PC" box**, that is SmartScreen
being cautious about a script it has not seen before, which is expected. Click
**More info**, then **Run anyway**.

A black console window opens and stays open — that is normal, it is the client's
log. After ten to thirty seconds the RuneLite window appears.

Leave the black window alone while you play. Closing it closes the game.

---

## Step 5 — Turn the plugin on

1. In RuneLite, click the **wrench icon** in the top-right sidebar.
2. Type `follower` in the search box.
3. Tick **Follower Buddy**.

A new icon appears in the right-hand sidebar — that is the **Follower outfit**
panel, where you dress it and edit everything it says.

Log in with your legacy account and a second character will appear behind you.

---

## Playing with it

**Dress it** in the Follower outfit panel: pick a body, then click any equipment
slot to search all 6,000-odd wearable items by name.

**Talk to it** — right-click the follower for options, and shift-right-click the
ground to send it somewhere.

**It reacts on its own** to bosses appearing, places you visit, gear you equip,
your health and prayer running low, and quest characters it recognises. It runs
small errands by itself, stands in for your Arceuus thralls, and steps out of
the way to watch when you fight.

**Everything it says is editable.** The buttons in its panel open tables of every
line, grouped by what triggers them — change them, delete them, add your own.

---

## If something goes wrong

**The window flashes and disappears.**
Run it from a terminal so the error stays put: open the folder, click in the
address bar at the top, type `cmd`, press Enter, then type `run.bat` and press
Enter. Whatever it prints is the answer.

**"Java was not found."**
Java is not installed or not on your PATH. Go back to steps 1 and 2.

**"Unsupported class file major version"**
Your Java is too old. `java -version` must say 17 or higher.

**Antivirus blocks it.**
Some antivirus software dislikes unsigned `.bat` files on principle. Allowing
the folder is safe — you can read `run.bat` in Notepad, it is nine lines and all
it does is start Java.

**It starts but something is wrong in game.**
There is a log at `C:\Users\<you>\.runelite\logs\client.log`. The last hundred
lines almost always name the problem — send those over.

---

## Removing it

Delete the folder. That is all — nothing was installed.

If you also want the follower's own settings gone, delete
`C:\Users\<you>\.runelite\follower`. Leave the rest of `.runelite` alone or you
will lose your normal RuneLite setup.

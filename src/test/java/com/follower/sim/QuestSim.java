package com.follower.sim;

import com.follower.speech.SpeechOutput;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerEvent;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.NPC;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.coords.WorldPoint;

/**
 * A quest walked stage by stage: the shipped rules in the Harness, with the
 * plugin's side of the speech path modelled as FollowerPlugin does it: the
 * overhead queue (3 deep, 15.4 s stale), reading time (17 cps, 4 s floor, 12 s
 * cap), the NPC-dialogue hush in speakNow, a line that waits its turn kept in
 * the queue through a conversation (keptForTheBox), the cutscene clearing the queue and
 * re-saying a once/occasion line cut in its first 2 s, the 25-tick quiet
 * after a scene, and the dialogue reading that stays "open" 2 ticks after
 * the box goes. Pacing is ON (Occasional, 6 s) unless set otherwise.
 */
public final class QuestSim
{
	public final Harness h;
	public final long[] ms = {1_700_000_000_000L};
	public int tick;
	public final StringBuilder log = new StringBuilder();
	public final List<String> heard = new ArrayList<>();
	public final List<String> heardIds = new ArrayList<>();
	public final List<String> lost = new ArrayList<>();

	// world
	public boolean npcDialog;
	public boolean cutscene;
	private int goneTicks = 99;
	private boolean sceneOn;
	private int sceneGone = 99;
	private int quietUntil;
	private int region = -1;
	private final Map<String, NPC> npcs = new HashMap<>();

	// plugin speech side
	private static final class U
	{
		final String text;
		final SpeechRule rule;
		final int anim;
		final Runnable onSaid;
		long queuedAt;

		U(String text, SpeechRule rule, int anim, Runnable onSaid, long queuedAt)
		{
			this.text = text;
			this.rule = rule;
			this.anim = anim;
			this.onSaid = onSaid;
			this.queuedAt = queuedAt;
		}
	}

	private final ArrayDeque<U> queue = new ArrayDeque<>();
	private long speakingUntil;
	private U onScreen;
	private long onScreenSince;
	private U cut;
	private int cutHeld;

	public QuestSim(Path dir, long gapMs) throws java.io.IOException
	{
		h = new Harness(dir);
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(gapMs);
		h.engine.setSink((text, output, rule, anim, onSaid) -> pluginSpeak(text, rule, anim, onSaid));
		h.engine.setOnSuppressed((rule, why) -> note("   held back: " + rule.id + " (" + why + ")"));
		h.game.at(3222, 3218, 0);
		ticks(3);
	}

	public void note(String s)
	{
		log.append(String.format("t%-5d %6.1fs  %s%n", tick, (ms[0] - 1_700_000_000_000L) / 1000.0, s));
	}

	// ------------------------------------------------------------ plugin side

	private long readingTime(String text)
	{
		long needed = text.length() * 1000L / 17;
		return Math.min(12_000L, Math.max(4_000L, needed));
	}

	private void pluginSpeak(String text, SpeechRule rule, int anim, Runnable onSaid)
	{
		if (text.isEmpty())
		{
			speakNow(new U(text, rule, anim, onSaid, ms[0]));
			return;
		}
		if (ms[0] < speakingUntil || keptForTheBox(rule))
		{
			if (queue.size() < 3)
			{
				queue.addLast(new U(text, rule, anim, onSaid, ms[0]));
				note("   queued: " + rule.id);
			}
			else
			{
				note("   QUEUE FULL, dropped: " + rule.id);
				lost.add(rule.id + " (queue full)");
			}
			return;
		}
		speakNow(new U(text, rule, anim, onSaid, ms[0]));
	}

	private void speakNow(U u)
	{
		boolean hushed = !u.text.isEmpty() && npcDialog;
		if (hushed)
		{
			note("   HUSHED by NPC dialogue (words dropped): " + id(u.rule) + " \"" + u.text + "\"");
			lost.add(id(u.rule) + " (hushed by dialogue)");
			return;
		}
		u.onSaid.run();
		if (!u.text.isEmpty())
		{
			onScreen = u;
			onScreenSince = ms[0];
			speakingUntil = ms[0] + readingTime(u.text) + 400;
			heard.add(u.text);
			heardIds.add(id(u.rule));
			note("SAYS [" + id(u.rule) + "] \"" + u.text + "\"" + (u.anim > 0 ? " anim=" + u.anim : "")
				+ (u.rule != null && u.rule.mood != null ? " mood=" + u.rule.mood : "")
				+ (u.rule != null && u.rule.remember != null ? " remembers='" + u.rule.remember.as + "'" : ""));
		}
	}

	private static String id(SpeechRule r)
	{
		return r == null ? "(direct)" : r.id;
	}

	private void noticeTheScene()
	{
		sceneGone = cutscene ? 0 : Math.min(sceneGone + 1, 1000);
		boolean on = cutscene || (sceneOn && sceneGone <= 2);
		if (on != sceneOn)
		{
			sceneOn = on;
			if (on)
			{
				cut = onScreen != null && ms[0] < speakingUntil && onScreen.rule != null
					&& (onScreen.rule.isOnce() || onScreen.rule.isOccasion())
					&& ms[0] - onScreenSince < 2000 ? onScreen : null;
				if (onScreen != null && ms[0] < speakingUntil)
				{
					note("   scene starts: overhead cleared mid-line (" + id(onScreen.rule) + ")"
						+ (cut != null ? ", will be re-said" : ""));
				}
				if (!queue.isEmpty())
				{
					for (U q : queue)
					{
						note("   scene starts: queued line cleared (" + id(q.rule) + ")");
						lost.add(id(q.rule) + " (queue cleared by cutscene)");
					}
				}
				cutHeld = 0;
				queue.clear();
				onScreen = null;
				speakingUntil = 0;
			}
			else
			{
				quietUntil = tick + 25;
			}
		}
		boolean quiet = sceneOn || tick < quietUntil;
		h.engine.setSceneQuiet(quiet);
		if (cut != null && quiet)
		{
			if (++cutHeld > 500)
			{
				cut = null;
			}
		}
		else if (cut != null)
		{
			note("   re-says the line the scene cut:");
			h.engine.remark(cut.text);
			cut = null;
		}
	}

	/** FollowerPlugin.keptForTheBox, on the dialogue as the rules read it. */
	private boolean keptForTheBox(SpeechRule rule)
	{
		return (npcDialog || goneTicks <= 2) && rule != null && rule.waitsItsTurn();
	}

	private void drain()
	{
		if (!queue.isEmpty() && keptForTheBox(queue.peekFirst().rule))
		{
			queue.peekFirst().queuedAt = ms[0];
			return;
		}
		while (!queue.isEmpty() && ms[0] - queue.peekFirst().queuedAt > 15_400L)
		{
			U stale = queue.removeFirst();
			note("   STALE in queue, dropped: " + id(stale.rule));
			lost.add(id(stale.rule) + " (stale in queue)");
		}
		if (queue.isEmpty() || ms[0] < speakingUntil)
		{
			return;
		}
		speakNow(queue.removeFirst());
	}

	// ------------------------------------------------------------ the world

	public void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick();
		}
	}

	public void tick()
	{
		ms[0] += 600;
		tick++;
		if (tick % 3 == 0)
		{
			h.game.cameraMoved();
		}
		noticeTheScene();
		drain();
		goneTicks = npcDialog ? 0 : Math.min(goneTicks + 1, 1000);
		h.engine.getContext().setDialogueOpen(npcDialog || goneTicks <= 2);
		h.gameTick();
		int now = regionOf(h.engine.getContext().getRegionId());
		if (now != region)
		{
			int old = region;
			region = now;
			if (old != -1)
			{
				note("   (region " + old + " -> " + now + ")");
				h.dispatch(TriggerEvent.regionChange(now, old));
			}
		}
	}

	private int regionOf(int r)
	{
		return r;
	}

	public void at(int x, int y, int plane)
	{
		h.game.at(x, y, plane);
	}

	/** Walks (or runs, 2 tiles a tick) in a straight line, a tick per step. */
	public void walkTo(int x, int y, int plane, int tilesPerTick)
	{
		WorldPoint p = h.game.player.getWorldLocation();
		int cx = p.getX();
		int cy = p.getY();
		while (cx != x || cy != y)
		{
			for (int s = 0; s < tilesPerTick && (cx != x || cy != y); s++)
			{
				cx += Integer.signum(x - cx);
				cy += Integer.signum(y - cy);
			}
			h.game.at(cx, cy, plane);
			tick();
		}
	}

	public NPC npc(String name, int x, int y, int plane)
	{
		NPC n = npcs.get(name);
		if (n == null)
		{
			n = h.game.spawnNpc(9000 + npcs.size(), name, 10);
			npcs.put(name, n);
		}
		h.game.moveNpc(n, x, y, plane);
		return n;
	}

	public void removeNpc(String name)
	{
		NPC n = npcs.remove(name);
		if (n != null)
		{
			h.game.moveNpc(n, 0, 0, 3);
		}
	}

	public void dialogue(boolean open)
	{
		npcDialog = open;
		note(open ? "-- NPC dialogue opens" : "-- NPC dialogue closes");
	}

	public void scene(boolean on)
	{
		cutscene = on;
		note(on ? "== cutscene starts" : "== cutscene ends");
	}

	/** A conversation of the given length, with any var changes run at its end, still inside it. */
	public void talk(int lengthTicks, Runnable atEnd)
	{
		dialogue(true);
		ticks(lengthTicks);
		if (atEnd != null)
		{
			atEnd.run();
		}
		tick();
		dialogue(false);
	}

	public void varp(int id, int v)
	{
		h.game.varp(id, v);
		note("   varp " + id + " = " + v);
	}

	public void varbit(int id, int v)
	{
		h.game.varbit(id, v);
		note("   varbit " + id + " = " + v);
	}

	public void done(Quest q)
	{
		h.game.quest(q, QuestState.FINISHED);
	}

	private final java.util.Map<String, Long> seenFinished = new java.util.HashMap<>();

	/** Finished with the follower there to see it, some days ago: what the callbacks need. */
	public void witnessed(Quest q)
	{
		done(q);
		seenFinished.put(q.name(), java.time.LocalDate.now().toEpochDay() - 30);
		h.engine.getContext().setQuestWitness(seenFinished::get);
	}

	public void started(Quest q)
	{
		h.game.quest(q, QuestState.IN_PROGRESS);
	}

	public void chat(String message)
	{
		note("   game message: " + message);
		h.dispatch(TriggerEvent.chat(message, net.runelite.api.ChatMessageType.GAMEMESSAGE.getType(), ""));
	}

	public void questComplete(Quest q, String name)
	{
		done(q);
		note("   quest scroll: " + name);
		h.engine.getContext().tally("quests");
		h.dispatch(TriggerEvent.questComplete(name));
	}

	/** The plugin writes the page when the follower has the floor. */
	public void pageWhenQuiet(String name, int maxTicks)
	{
		for (int i = 0; i < maxTicks; i++)
		{
			if (h.engine.isQuiet())
			{
				note("   chronicle page written: " + name);
				h.dispatch(TriggerEvent.pageWritten("quest", name));
				return;
			}
			tick();
		}
		note("   (page news never found the floor in " + maxTicks + " ticks)");
	}

	public void event(TriggerEvent e, String desc)
	{
		note("   event: " + desc);
		h.dispatch(e);
	}

	public void bag(int... ids)
	{
		h.game.inventoryContaining(ids);
	}

	public void summary()
	{
		note("HEARD, in order: " + heardIds);
		note("LOST: " + lost);
	}

	public String transcript()
	{
		return log.toString();
	}
}

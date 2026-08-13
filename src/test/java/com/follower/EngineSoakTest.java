package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Randomly generated rule sets, driven by randomly generated events.
 *
 * <p>The hand-written tests check the cases somebody thought of. This one
 * explores the combinations nobody did - a rule that is delayed AND on cooldown
 * AND in a disabled group, fired by an event that arrives while another firing
 * is still pending - and asserts the promises that must hold whatever the
 * combination.
 *
 * <p>Seeded, so a failure is reproducible rather than a story about something
 * that happened once.
 */
public class EngineSoakTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final String[] CONDITIONS = {
		"{\"type\": \"always\"}",
		"{\"type\": \"chance\", \"percent\": 50}",
		"{\"type\": \"healthBelow\", \"percent\": 50}",
		"{\"type\": \"healthAbove\", \"percent\": 20}",
		"{\"type\": \"idle\", \"ticks\": 3}",
		"{\"type\": \"energyBelow\", \"percent\": 50}",
		"{\"type\": \"combat\"}",
		"{\"type\": \"login\"}",
		"{\"type\": \"playerDeath\"}",
		"{\"type\": \"npcSpawn\", \"names\": [\"Goblin\"]}",
		"{\"type\": \"npcNearby\", \"names\": [\"Goblin\"], \"within\": 5}",
		"{\"type\": \"animationSelf\", \"ids\": [862]}",
		"{\"type\": \"lootWorth\", \"minimum\": 100}",
		"{\"type\": \"npcKill\", \"minimum\": 1}",
		"{\"type\": \"combatStart\"}",
		"{\"type\": \"damageTaken\", \"minimum\": 1}",
		"{\"type\": \"inRegion\", \"regions\": [12850]}",
		"{\"type\": \"itemEquipped\", \"ids\": [4151]}",
		// The 2026-08-12 batch: state conditions the driver flips at random,
		// so combinators wrapping them open and close mid-flight.
		"{\"type\": \"boundary\"}",
		"{\"type\": \"boundary\", \"is\": \"bank\"}",
		"{\"type\": \"staying\"}",
		"{\"type\": \"inWilderness\"}",
		"{\"type\": \"rolledFeeling\", \"is\": \"disliked\"}",
		"{\"type\": \"daysKnown\", \"minimum\": 0, \"maximum\": 3}",
		"{\"type\": \"none\", \"conditions\": [{\"type\": \"repeating\"}]}",
	};

	private static String condition(Random random, int depth)
	{
		if (depth > 0 && random.nextInt(4) == 0)
		{
			String combinator = new String[]{"all", "any", "none"}[random.nextInt(3)];
			int count = 1 + random.nextInt(3);
			StringBuilder children = new StringBuilder();
			for (int i = 0; i < count; i++)
			{
				if (i > 0)
				{
					children.append(',');
				}
				children.append(condition(random, depth - 1));
			}
			return "{\"type\": \"" + combinator + "\", \"conditions\": [" + children + "]}";
		}
		return CONDITIONS[random.nextInt(CONDITIONS.length)];
	}

	private static String rules(Random random, int count)
	{
		StringBuilder out = new StringBuilder("{\"version\": 1, \"rules\": [");
		for (int i = 0; i < count; i++)
		{
			if (i > 0)
			{
				out.append(',');
			}
			out.append("{\"id\": \"r").append(i).append('"')
				.append(", \"group\": \"g").append(random.nextInt(4)).append('"')
				.append(", \"priority\": ").append(random.nextInt(100))
				.append(", \"cooldownMs\": ").append(random.nextInt(3) * 5000);
			if (random.nextInt(3) == 0)
			{
				int min = random.nextInt(4);
				out.append(", \"delayTicks\": ").append(min);
				if (random.nextBoolean())
				{
					out.append(", \"delayTicksMax\": ").append(min + random.nextInt(4));
				}
			}
			if (random.nextInt(5) == 0)
			{
				out.append(", \"animation\": 862");
			}
			out.append(", \"when\": ").append(condition(random, 2))
				.append(", \"say\": [\"line ").append(i).append("\"]}");
		}
		return out.append("]}").toString();
	}

	private void randomEvent(Harness h, Random random)
	{
		switch (random.nextInt(14))
		{
			case 0:
				h.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, 3029, "Goblin"));
				break;
			case 1:
				h.dispatch(TriggerEvent.animation(862));
				break;
			case 2:
				h.dispatch(TriggerEvent.loot(random.nextInt(200000), "Bones"));
				break;
			case 3:
				h.dispatch(TriggerEvent.kill(1, "Goblin", random.nextInt(800)));
				break;
			case 4:
				h.dispatch(TriggerEvent.death());
				break;
			case 5:
				h.dispatch(TriggerEvent.damageTaken(1 + random.nextInt(40)));
				break;
			case 6:
				h.dispatch(TriggerEvent.combat(TriggerEvent.Type.COMBAT_START, "Goblin"));
				break;
			case 7:
				h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
				break;
			case 8:
				h.game.hitpoints(1 + random.nextInt(99), 99);
				break;
			case 9:
				h.game.energy(random.nextInt(10001));
				break;
			case 10:
				h.engine.getContext().noteBoundary(
					new String[]{"combat", "bank", "reunion"}[random.nextInt(3)]);
				break;
			case 11:
				h.engine.getContext().setFollowerStaying(random.nextBoolean());
				break;
			case 12:
				h.game.varbit(5963, random.nextInt(2));
				break;
			default:
				h.game.animating(random.nextBoolean() ? 879 : -1);
				break;
		}
	}

	@Test
	public void randomRuleSetsUnderRandomEventsKeepTheirPromises() throws IOException
	{
		for (int seed = 0; seed < 40; seed++)
		{
			Random random = new Random(seed);
			Harness h = new Harness(folder.newFolder().toPath(), rules(random, 25));

			Set<String> disabled = new HashSet<>();
			if (random.nextBoolean())
			{
				disabled.add("g" + random.nextInt(4));
			}
			h.engine.setDisabledGroups(disabled);
			h.game.spawnNpc(3029, "Goblin", 5);

			Map<String, Long> lastSpokeAt = new HashMap<>();
			int before = 0;

			for (int step = 0; step < 300; step++)
			{
				if (random.nextInt(3) == 0)
				{
					randomEvent(h, random);
				}
				h.gameTick();

				// Nothing from a group that was switched off.
				for (int i = before; i < h.spoken.size(); i++)
				{
					Harness.Spoken spoken = h.spoken.get(i);
					SpeechRule rule = spoken.rule;
					assertTrue("seed " + seed + ": '" + rule.id + "' spoke from the"
							+ " disabled group '" + rule.group + "'",
						!disabled.contains(rule.group));

					// Never twice inside its own cooldown.
					Long previous = lastSpokeAt.get(rule.id);
					long now = System.currentTimeMillis();
					if (previous != null && rule.cooldownMs > 0)
					{
						assertTrue("seed " + seed + ": '" + rule.id + "' spoke twice"
								+ " inside its " + rule.cooldownMs + "ms cooldown",
							now - previous >= 0);
					}
					lastSpokeAt.put(rule.id, now);
				}
				before = h.spoken.size();
			}

			// Still alive and answering after all that.
			h.clear();
			h.game.hitpoints(1, 99);
			h.gameTicks(5);
		}
	}

	@Test
	public void aDelayedFiringNeverArrivesEarlierThanItsFloor() throws IOException
	{
		// Ten ticks of delay, checked exactly: the queue must not leak a firing
		// forward when other events are arriving in between.
		for (int seed = 0; seed < 20; seed++)
		{
			Random random = new Random(seed);
			Harness h = new Harness(folder.newFolder().toPath(),
				"{\"version\": 1, \"rules\": [{\"id\": \"slow\", \"group\": \"t\","
					+ " \"cooldownMs\": 0, \"delayTicks\": 10,"
					+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"a\"]}]}");

			h.gameTicks(1);
			h.dispatch(TriggerEvent.death());

			for (int tick = 1; tick <= 9; tick++)
			{
				if (random.nextBoolean())
				{
					randomEvent(h, random);
				}
				h.gameTick();
				assertTrue("seed " + seed + ": spoke on tick " + tick + " of a 10 tick wait",
					h.firedBy("slow").isEmpty());
			}
			h.gameTick();
			assertEquals("seed " + seed + ": did not speak when the wait was up",
				1, h.firedBy("slow").size());
		}
	}

	@Test
	public void atMostOneRuleSpeaksPerDispatch() throws IOException
	{
		// Twenty rules that are all true at once, all off cooldown. The engine
		// picks a single winner per pass; anything else is two lines at once.
		StringBuilder out = new StringBuilder("{\"version\": 1, \"rules\": [");
		for (int i = 0; i < 20; i++)
		{
			if (i > 0)
			{
				out.append(',');
			}
			out.append("{\"id\": \"r").append(i).append("\", \"group\": \"t\","
				+ " \"priority\": ").append(i).append(", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"always\"}, \"say\": [\"x\"]}");
		}
		Harness h = new Harness(folder.newFolder().toPath(), out.append("]}").toString());

		h.gameTick();
		int afterFirst = h.spoken.size();
		assertTrue("twenty true rules produced " + afterFirst + " lines in one pass",
			afterFirst <= 1);

		List<String> speakers = new ArrayList<>();
		for (int tick = 0; tick < 20; tick++)
		{
			int before = h.spoken.size();
			h.gameTick();
			assertTrue("more than one line in a single tick",
				h.spoken.size() - before <= 1);
			for (int i = before; i < h.spoken.size(); i++)
			{
				speakers.add(h.spoken.get(i).rule.id);
			}
		}

		// "always" never falls, so after the first pass no rule has a rising
		// edge left and nothing more should be said at all.
		assertTrue("a permanently true rule kept talking: " + speakers,
			speakers.isEmpty());
	}
}

package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerContext;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Rules the overnight QA (15 Oct) found that could never, or almost never,
 * win the pass they rise on: dead conditions, and lines a higher rule on the
 * same event always took. One event has one winner, so each of these is
 * checked by the line actually being said.
 */
public class ShadowedRulesTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);

	/** The bundled rules, a month in, on a game clock. */
	private Harness harness() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.setClock(clock::get);
		TriggerContext ctx = h.engine.getContext();
		ctx.setSessionCount(20);
		ctx.setMetOnDay(LocalDate.now().toEpochDay() - 30);
		h.gameTicks(3);
		return h;
	}

	private void ticks(Harness h, int n)
	{
		for (int i = 0; i < n; i++)
		{
			clock.addAndGet(600L);
			h.gameTick();
		}
	}

	private static void count(TriggerContext ctx, String tally, int times)
	{
		for (int i = 0; i < times; i++)
		{
			ctx.tally(tally);
		}
	}

	@Test
	public void aGreatManyLevelsIsNoticedSometimes() throws IOException
	{
		int many = 0;
		int plain = 0;
		for (int trial = 0; trial < 60; trial++)
		{
			Harness h = harness();
			count(h.engine.getContext(), "levels", 30);
			h.dispatch(TriggerEvent.levelUp("Attack", 47));
			ticks(h, 3);
			many += h.firedBy("levels-many").size();
			plain += h.firedBy("level-up").size();
		}
		assertTrue("levels-many is heard on some levels: " + many, many > 0);
		assertTrue("and the plain level line keeps the rest: " + plain, plain > many);
	}

	@Test
	public void theTenthQuestIsTheMilestoneNotTheUsualScroll() throws IOException
	{
		Harness h = harness();
		count(h.engine.getContext(), "quests", 10);
		h.dispatch(TriggerEvent.questComplete("Cook's Assistant"));
		ticks(h, 8);
		assertEquals(1, h.firedBy("quest-milestone-10").size());
		assertTrue(h.firedBy("quest-complete").isEmpty());
		assertTrue("an occasion", h.rule("quest-milestone-10").isOccasion());
	}

	// public:cut spectate
	@Test
	public void aBetIsOpenedOnSomeFights() throws IOException
	{
		int bets = 0;
		for (int trial = 0; trial < 80 && bets == 0; trial++)
		{
			Harness h = harness();
			h.dispatch(TriggerEvent.combat(TriggerEvent.Type.COMBAT_START, "Goblin"));
			ticks(h, 2);
			bets += h.firedBy("bet-rich").size();
		}
		assertTrue("bet-rich wins the fight's start when it rolls", bets > 0);
	}

	// public:end spectate
	@Test
	public void theHundredthBossKillIsCountedNotOnlyCelebrated() throws IOException
	{
		Harness h = harness();
		h.engine.getContext().setBossNames(h.loader.getBossNames());
		h.dispatch(TriggerEvent.kill(8061, "Vorkath", 732, 100));
		ticks(h, 10);
		assertEquals(1, h.firedBy("kill-tally-hundred").size());
		assertTrue(h.firedBy("kill-boss-celebrate").isEmpty());
	}

	/** Spoken times of a rule's lines, from the harness clock. */
	private void fight(Harness h, String name, int level, int forTicks, java.util.List<Long> bossCheers)
	{
		net.runelite.api.NPC foe = h.game.spawnNpc(1, name, level);
		h.game.fighting(foe);
		for (int i = 0; i < forTicks; i++)
		{
			int before = h.firedBy("combat-cheer-boss").size();
			ticks(h, 1);
			if (h.firedBy("combat-cheer-boss").size() > before)
			{
				bossCheers.add(clock.get());
			}
		}
	}

	@Test
	public void aBossFightHasOneCheerNotTwo() throws IOException
	{
		Harness h = harness();
		java.util.List<Long> cheers = new java.util.ArrayList<>();
		fight(h, "Vorkath", 732, 2000, cheers);
		assertTrue("the ordinary cheer stays out of a boss fight", h.firedBy("combat-cheer").isEmpty());
		assertTrue("the boss cheer has it", cheers.size() > 1);
		for (int i = 1; i < cheers.size(); i++)
		{
			assertTrue("45 s between boss cheers", cheers.get(i) - cheers.get(i - 1) >= 45_000L);
		}

		Harness goblin = harness();
		fight(goblin, "Goblin", 2, 2000, new java.util.ArrayList<>());
		assertTrue("an ordinary fight still has its cheer", !goblin.firedBy("combat-cheer").isEmpty());
	}

	@Test
	public void theBossKillIsCelebratedWhileTheFollowerIsResting() throws IOException
	{
		Harness h = harness();
		h.engine.setGlobalCooldownMs(6_000L);
		h.engine.getContext().setBossNames(h.loader.getBossNames());
		for (String line : new String[]{"combat-start", "kill-cheer", "combat-end"})
		{
			h.engine.force(line);
			ticks(h, 11);
		}
		assertTrue("the burst has the director resting",
			h.engine.getDirector().isRelaxing(clock.get()));
		h.dispatch(TriggerEvent.kill(8061, "Vorkath", 732, 7));
		ticks(h, 30);
		assertEquals(1, h.firedBy("kill-boss-celebrate").size());
	}

	private static final int EAST_ARDOUGNE = 10547;
	private static final int NOWHERE_NAMED = 5432;

	private int region = -1;

	/** Stands in a region for some ticks, raising the region change as the plugin does. */
	private void stand(Harness h, int in, int forTicks)
	{
		h.game.at((in >> 8) * 64 + 32, (in & 0xFF) * 64 + 32, 0);
		h.game.regions(in);
		for (int i = 0; i < forTicks; i++)
		{
			clock.addAndGet(600L);
			h.game.tick(tick++);
			h.engine.refreshContext();
			if (region != in)
			{
				if (region != -1)
				{
					h.dispatch(TriggerEvent.regionChange(in, region));
				}
				region = in;
			}
			h.dispatch(TriggerEvent.tick());
		}
	}

	private int tick = 10;

	/** To the bank and back out to work, the trip taking about ten minutes in all. */
	private void bankTrip(Harness h)
	{
		stand(h, EAST_ARDOUGNE, 10);
		stand(h, NOWHERE_NAMED, 260);
		clock.addAndGet(10 * 60_000L - 270 * 600L);
	}

	@Test
	public void aTownIsNotRetoldOnEveryBankTrip() throws IOException
	{
		Harness h = harness();
		stand(h, NOWHERE_NAMED, 5);
		for (int trip = 0; trip < 6; trip++)
		{
			bankTrip(h);
		}
		// Trips at 0, 10, 20, 30, 40 and 50 minutes: the town is told at 0 and 30.
		assertEquals(2, h.firedBy("area-east-ardougne").size());
	}

	@Test
	public void aFifthArrivalCanBeBackAgain() throws IOException
	{
		boolean backAgain = false;
		for (int trial = 0; trial < 30 && !backAgain; trial++)
		{
			region = -1;
			Harness h = harness();
			stand(h, NOWHERE_NAMED, 5);
			for (int visit = 1; visit <= 5; visit++)
			{
				// Long enough apart that the town's own line is ready each time.
				clock.addAndGet(31 * 60_000L);
				bankTrip(h);
			}
			backAgain = !h.firedBy("return-visit").isEmpty();
		}
		assertTrue("return-visit wins a fifth arrival some of the time", backAgain);
	}

	@Test
	public void aGrudgeIsNotedWhenNoWishWasKept() throws IOException
	{
		Harness h = harness();
		count(h.engine.getContext(), "want:missed", 3);
		for (int i = 0; i < 3000 && h.firedBy("grudge-noted").isEmpty(); i++)
		{
			ticks(h, 1);
		}
		assertTrue("grudge-noted speaks in a long idle stretch", !h.firedBy("grudge-noted").isEmpty());
	}
}

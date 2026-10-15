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

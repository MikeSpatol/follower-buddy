package com.follower.speech;

import com.follower.sim.FakeGame;
import com.google.gson.JsonPrimitive;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Levelling earns "the swot" or "the grinder" (the user, 18 Oct): a chance of
 * either, but each follower keeps to the one it picked, and a refused one
 * gives way to the other.
 */
public class NicknameChoiceTest
{
	private static TriggerContext metOn(long day)
	{
		TriggerContext ctx = new TriggerContext(new FakeGame().client);
		ctx.setMetOnDay(day);
		ctx.setNicknames(Collections.singletonMap("levels", "the swot|the grinder"));
		for (int i = 0; i < 40; i++)
		{
			ctx.tally("levels");
		}
		return ctx;
	}

	@Test
	public void bothNamesCanBeEarned()
	{
		Set<String> seen = new HashSet<>();
		for (long day = 20000; day < 20010; day++)
		{
			seen.add(metOn(day).getEarnedNickname());
		}
		assertEquals(Set.of("the swot", "the grinder"), seen);
	}

	@Test
	public void aFollowerKeepsToTheOneItPicked()
	{
		TriggerContext ctx = metOn(20001);
		String first = ctx.getEarnedNickname();
		for (int i = 0; i < 20; i++)
		{
			assertEquals("the same name every time it is asked for", first, ctx.getEarnedNickname());
		}
	}

	@Test
	public void aRefusedNameGivesWayToTheOther()
	{
		TriggerContext ctx = metOn(20000);
		String first = ctx.getEarnedNickname();
		ctx.refuseNickname(first);
		String second = ctx.getEarnedNickname();
		assertFalse("not the refused one", first.equals(second));
		assertTrue("and still on offer", ctx.hasNicknameToOffer());
		ctx.refuseNickname(second);
		assertFalse("both refused: nothing left to offer", ctx.hasNicknameToOffer());
	}

	@Test
	public void anExistingInstallGetsTheChoiceButKeepsANameOfItsOwn()
	{
		JsonPrimitive bundled = new JsonPrimitive("the swot|the grinder");
		assertTrue("the old default widens", RuleLoader.widens(new JsonPrimitive("the swot"), bundled));
		assertFalse("already up to date", RuleLoader.widens(bundled, bundled));
		assertFalse("a name the player wrote stays", RuleLoader.widens(new JsonPrimitive("the bookworm"), bundled));
	}
}

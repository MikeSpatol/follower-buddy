package com.follower;

import java.io.IOException;
import net.runelite.api.ChatMessageType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The skilling company's rules: the four pieces of it that are pure rules
 * in the shipped file rather than code. The head shake at a run of burnt
 * food and the nod at a batch of bows are gated on the session, so the
 * company's switch governs them; the two mirrors sit in the mimic group,
 * so the mimicry switch does.
 */
public class SkillingRulesTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static TriggerEvent burn()
	{
		return TriggerEvent.chat("You accidentally burn the shrimps.",
			ChatMessageType.GAMEMESSAGE.getType(), "");
	}

	@Test
	public void everyThirdBurnWhileCookingIsAShakeOfTheHead() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		for (int i = 0; i < 6; i++)
		{
			h.dispatch(burn());
		}
		assertTrue("no session, no head shake", h.firedBy("burnt").isEmpty());

		h.engine.getContext().setWork(true, "cooking", "Fire", null, 10, 0, Integer.MAX_VALUE);
		h.gameTicks(1);
		h.dispatch(burn());
		h.dispatch(burn());
		assertTrue("two burns is not a run", h.firedBy("burnt").isEmpty());
		h.dispatch(burn());
		assertEquals("the third", 1, h.firedBy("burnt").size());
		assertEquals("EMOTE_NO, and no words", 856, h.firedBy("burnt").get(0).animationId);
		assertEquals("", h.firedBy("burnt").get(0).text);
	}

	@Test
	public void everyFifthBowWhileFletchingIsANod() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.getContext().setWork(true, "fletching", "", null, 10, 0, Integer.MAX_VALUE);
		h.gameTicks(1);
		for (int i = 0; i < 4; i++)
		{
			h.dispatch(TriggerEvent.animation(1248));
		}
		assertTrue("four is not a batch", h.firedBy("counting-batches").isEmpty());
		h.dispatch(TriggerEvent.animation(1248));
		assertEquals(1, h.firedBy("counting-batches").size());
		assertEquals(855, h.firedBy("counting-batches").get(0).animationId);
		assertEquals("a reaction, not a mirror: the Mimicry toggle must not govern it",
			"reactions", h.rule("counting-batches").group);

		Harness idle = new Harness(folder.newFolder().toPath());
		idle.gameTicks(1);
		for (int i = 0; i < 10; i++)
		{
			idle.dispatch(TriggerEvent.animation(1248));
		}
		assertTrue("no session, no count", idle.firedBy("counting-batches").isEmpty());
	}

	@Test
	public void theTwoMirrorsAreMimicRulesOnTheRightAnimations() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		SpeechRule bury = h.rule("bury-along");
		assertEquals("mimic", bury.group);
		assertEquals(Boolean.TRUE, bury.mirrorAnimation);
		assertTrue(bury.when.conditions.get(0).ids.contains(827));

		SpeechRule casting = h.rule("casting-along");
		assertEquals("mimic", casting.group);
		assertEquals(Boolean.TRUE, casting.mirrorAnimation);
		assertEquals("a minute between", 60000L, casting.cooldownMs);
		for (int id : new int[]{712, 713, 719, 720, 721, 725, 726})
		{
			assertTrue("cast " + id, casting.when.conditions.get(0).ids.contains(id));
		}
		assertEquals(Integer.valueOf(5), casting.when.conditions.get(1).percent);
	}
}

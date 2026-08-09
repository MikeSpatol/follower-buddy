package com.follower.speech;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The small pieces that show up in what the follower actually says: where a
 * line is sent, and how an amount of gold is written.
 */
public class SpeechDetailsTest
{
	// ----------------------------------------------------------------- output

	@Test
	public void outputModesParseHoweverTheyAreWritten()
	{
		assertEquals(SpeechOutput.OVERHEAD, SpeechOutput.parse("overhead", SpeechOutput.BOTH));
		assertEquals(SpeechOutput.OVERHEAD, SpeechOutput.parse("OVERHEAD", SpeechOutput.BOTH));
		assertEquals(SpeechOutput.CHATBOX, SpeechOutput.parse("  ChatBox  ", SpeechOutput.BOTH));
		assertEquals(SpeechOutput.BOTH, SpeechOutput.parse("both", SpeechOutput.OVERHEAD));
	}

	@Test
	public void anUnknownOutputFallsBackRatherThanFailing()
	{
		assertEquals(SpeechOutput.BOTH, SpeechOutput.parse(null, SpeechOutput.BOTH));
		assertEquals(SpeechOutput.BOTH, SpeechOutput.parse("", SpeechOutput.BOTH));
		assertEquals(SpeechOutput.BOTH, SpeechOutput.parse("shouting", SpeechOutput.BOTH));
	}

	@Test
	public void eachModeGoesWhereItsNameSays()
	{
		assertTrue(SpeechOutput.OVERHEAD.showsOverhead());
		assertFalse(SpeechOutput.OVERHEAD.showsChatbox());

		assertFalse(SpeechOutput.CHATBOX.showsOverhead());
		assertTrue(SpeechOutput.CHATBOX.showsChatbox());

		assertTrue(SpeechOutput.BOTH.showsOverhead());
		assertTrue(SpeechOutput.BOTH.showsChatbox());
	}

	// ------------------------------------------------------------------- gold

	/** The {value} placeholder in a loot line, written the way a player says it. */
	private static String gp(int amount)
	{
		return TriggerEvent.loot(amount, "thing").getPlaceholders().get("value");
	}

	@Test
	public void goldIsWrittenTheWayAPlayerSaysIt()
	{
		assertEquals("0", gp(0));
		assertEquals("950", gp(950));
		assertEquals("9999", gp(9999));
		assertEquals("10K", gp(10_000));
		assertEquals("214K", gp(214_500));
		assertEquals("999K", gp(999_999));
		assertEquals("1.0M", gp(1_000_000));
		assertEquals("1.2M", gp(1_234_567));
	}

	@Test
	public void aHugeDropDoesNotOverflowIntoNonsense()
	{
		assertEquals("2147.5M", gp(Integer.MAX_VALUE));
	}

	@Test
	public void aLootEventCarriesTheItemAndTheAmount()
	{
		TriggerEvent event = TriggerEvent.loot(1_500_000, "Twisted bow");
		assertEquals("Twisted bow", event.getPlaceholders().get("item"));
		assertEquals("1.5M", event.getPlaceholders().get("value"));
		assertEquals("the raw value is what lootWorth compares against",
			1_500_000, event.getValue());
	}

	@Test
	public void aLootEventWithNoNamedItemIsStillUsable()
	{
		TriggerEvent event = TriggerEvent.loot(100, null);
		assertEquals("", event.getPlaceholders().get("item"));
	}

	// ------------------------------------------------------------- kill event

	@Test
	public void aKillCarriesEverythingAKillLineCouldWant()
	{
		TriggerEvent event = TriggerEvent.kill(2042, "Zulrah", 725);
		assertEquals("Zulrah", event.getPlaceholders().get("npc"));
		assertEquals("2042", event.getPlaceholders().get("npcId"));
		assertEquals("725", event.getPlaceholders().get("level"));
		assertEquals("the combat level is what npcKill brackets on",
			725, event.getValue());
	}

	@Test
	public void aKillWithNoNameDoesNotCarryANull()
	{
		TriggerEvent event = TriggerEvent.kill(1, null, 3);
		assertEquals("", event.getPlaceholders().get("npc"));
	}

	// --------------------------------------------------------------- phrases

	@Test
	public void aSinglePhraseRuleAlwaysSaysIt()
	{
		SpeechRule rule = new SpeechRule();
		rule.say = java.util.Collections.singletonList("only line");
		for (int i = 0; i < 20; i++)
		{
			assertEquals("only line", rule.pickPhrase());
		}
	}

	@Test
	public void aRuleWithNoLinesSaysNothingRatherThanFailing()
	{
		assertEquals("", new SpeechRule().pickPhrase());

		SpeechRule empty = new SpeechRule();
		empty.say = new java.util.ArrayList<>();
		assertEquals("", empty.pickPhrase());
	}

	@Test
	public void aPhraseIsNeverRepeatedBackToBack()
	{
		SpeechRule rule = new SpeechRule();
		rule.say = java.util.Arrays.asList("a", "b", "c");

		String previous = null;
		for (int i = 0; i < 500; i++)
		{
			String picked = rule.pickPhrase();
			assertFalse("said '" + picked + "' twice in a row", picked.equals(previous));
			previous = picked;
		}
	}

	@Test
	public void twoPhrasesAlternateRatherThanHanging()
	{
		// The no-repeat draw loops until it gets a different index; with only
		// two lines that leaves exactly one choice each time. The liveness half
		// of this is that it returns at all - a draw that could not satisfy
		// itself would spin forever on the game thread.
		//
		// The name also claims they ALTERNATE, which nothing here checked: the
		// case passed just as well against a draw that returned "a" a hundred
		// times. Now it says both.
		SpeechRule rule = new SpeechRule();
		rule.say = java.util.Arrays.asList("a", "b");

		String previous = null;
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (int i = 0; i < 100; i++)
		{
			String picked = rule.pickPhrase();
			seen.add(picked);
			if (previous != null)
			{
				assertNotEquals("with two lines every draw has to swap", previous, picked);
			}
			previous = picked;
		}
		assertEquals("both lines have to come up", 2, seen.size());
	}
}

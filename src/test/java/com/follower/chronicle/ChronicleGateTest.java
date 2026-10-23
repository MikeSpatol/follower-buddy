// public:file chronicle
package com.follower.chronicle;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * No quest before its time: a passage that would give a later quest away
 * waits, off the page, until that quest is done.
 */
public class ChronicleGateTest
{
	private static int width(String s)
	{
		return s.length() * 6;
	}

	private static ChronicleEntry.Section section(String heading, String body)
	{
		ChronicleEntry.Section section = new ChronicleEntry.Section();
		section.heading = heading;
		section.body = body;
		return section;
	}

	private static ChronicleEntry cook()
	{
		ChronicleEntry entry = new ChronicleEntry();
		entry.id = "quest-x";
		entry.kind = "quest";
		entry.name = "X";
		entry.summary = "The cook forgot the cake.\n\n{done:RECIPE_FOR_DISASTER} He forgets more later.";
		entry.sections = Arrays.asList(
			section("Worth knowing", "The cook has form.\n\n{done:RECIPE_FOR_DISASTER} A banquet goes wrong."),
			section("Later", "{done:DEATH_TO_THE_DORGESHUUN} He offers Zanik the errand."));
		entry.people = Arrays.asList("Gillie Groats - the milkmaid",
			"{done:RECIPE_FOR_DISASTER} The Culinaromancer - crashed the feast");
		entry.details = Arrays.asList("Reward - 1 quest point");
		return entry;
	}

	@Test
	public void aGatedParagraphWaitsForItsQuest()
	{
		ChronicleEntry before = cook().readAs(quest -> false);
		assertEquals("The cook has form.", before.sections.get(0).body);
		assertEquals("The cook forgot the cake.", before.summary);

		ChronicleEntry after = cook().readAs(quest -> true);
		assertEquals("its gate is not read out", "The cook has form.\n\nA banquet goes wrong.",
			after.sections.get(0).body);
		assertEquals("The cook forgot the cake.\n\nHe forgets more later.", after.summary);
	}

	@Test
	public void aSectionWithNothingLeftLosesItsHeading()
	{
		ChronicleEntry before = cook().readAs(quest -> false);
		assertEquals(1, before.sections.size());
		for (ChronicleLayout.Line line : ChronicleLayout.page(before, 2000,
			ChronicleGateTest::width, ChronicleGateTest::width))
		{
			assertFalse("an empty heading on the page: " + line, line.toString().equals("HEADING:Later"));
		}
		assertEquals(2, cook().readAs(quest -> true).sections.size());
	}

	@Test
	public void aListItemWaitsToo()
	{
		assertEquals(Collections.singletonList("Gillie Groats - the milkmaid"),
			cook().readAs(quest -> false).people);
		assertEquals("The Culinaromancer - crashed the feast", cook().readAs(quest -> true).people.get(1));
		assertEquals("an ungated list is left as it is", cook().details, cook().readAs(quest -> false).details);
		ChronicleEntry bare = cook();
		bare.places = null;
		assertNull("no list stays no list", bare.readAs(quest -> true).places);
	}

	@Test
	public void everyQuestAGateNamesMustBeDone()
	{
		ChronicleEntry entry = cook();
		entry.details = Arrays.asList("{done:BIOHAZARD,REGICIDE} Lathas lied.");
		Set<String> done = new TreeSet<>(Collections.singletonList("BIOHAZARD"));
		assertTrue("one of two is not enough", entry.readAs(done::contains).details.isEmpty());
		done.add("REGICIDE");
		assertEquals(Collections.singletonList("Lathas lied."), entry.readAs(done::contains).details);
	}

	@Test
	public void theGatesOnAPageAreKnown()
	{
		assertEquals(new TreeSet<>(Arrays.asList("RECIPE_FOR_DISASTER", "DEATH_TO_THE_DORGESHUUN")),
			new TreeSet<>(cook().gateQuests()));
		assertEquals(Arrays.asList("BIOHAZARD", "REGICIDE"),
			ChronicleEntry.gateOf("{done:BIOHAZARD,REGICIDE} Lathas lied."));
		assertTrue(ChronicleEntry.gateOf("Lathas lied. {done:BIOHAZARD}").isEmpty());
		assertEquals("Lathas lied.", ChronicleEntry.ungated("{done:BIOHAZARD} Lathas lied."));
		assertEquals("only a gate that opens the passage counts", "Lathas {done:BIOHAZARD} lied.",
			ChronicleEntry.ungated("Lathas {done:BIOHAZARD} lied."));
	}

	@Test
	public void readingLeavesTheBookAlone()
	{
		ChronicleEntry entry = cook();
		entry.readAs(quest -> false);
		assertTrue(entry.summary.contains("{done:RECIPE_FOR_DISASTER}"));
		assertEquals(2, entry.sections.size());
		List<String> passages = entry.passages();
		assertEquals("two summary paragraphs, three section paragraphs, three list items", 8, passages.size());
	}
}

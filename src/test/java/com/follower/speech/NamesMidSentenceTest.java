package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

/**
 * Names as the middle of a sentence has them. Text QA5: "the wording of this
 * Statue", "One more Crate", and the bosses' articles uneven: "your death to
 * the Dagannoth Rex", "That's a Kraken."
 */
public class NamesMidSentenceTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private Harness harness() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		return h;
	}

	// public:cut company-extras,noticing
	@Test
	public void aLandmarkIsACommonNounAfterThis() throws IOException
	{
		Harness h = harness();
		assertEquals("Hold on. I want the wording of this statue exactly.",
			h.engine.spokenText("Hold on. I want the wording of this {thing} exactly.",
				TriggerEvent.noticed("landmark", "Statue")));
		assertEquals("an item keeps the game's spelling", "Look at this Abyssal whip.",
			h.engine.spokenText("Look at this {thing}.", TriggerEvent.noticed("valuable", "Abyssal whip")));
	}

	// public:end company-extras,noticing
	@Test
	public void aThingInTheSceneIsACommonNounToo() throws IOException
	{
		Harness h = harness();
		assertEquals("One more crate and it's going in the notebook.",
			h.engine.spokenText("One more {target} and it's going in the notebook.",
				TriggerEvent.examinedCreature("Crate")));
		assertEquals("Everything on this monument is in the book now.",
			h.engine.spokenText("Everything on this {target} is in the book now.",
				TriggerEvent.examinedCreature("Monument")));
		assertEquals("an item examined keeps its capital", "One more Abyssal whip and it's going in the notebook.",
			h.engine.spokenText("One more {target} and it's going in the notebook.",
				TriggerEvent.examinedThing("Abyssal whip")));
	}

	@Test
	public void theDagannothKingsAreSomebody() throws IOException
	{
		Harness h = harness();
		h.engine.getContext().restoreIncident("the-death", "your death to Dagannoth Rex", 1, 0,
			System.currentTimeMillis() - 2 * 24 * 3_600_000L, "Dagannoth Rex");
		assertEquals("That's Dagannoth Rex. I'd know it anywhere.",
			h.engine.spokenText("That's a {memoryWho}. I'd know it anywhere.", TriggerEvent.tick()));
	}

	@Test
	public void theMemorysOwnBossIsTheOneThereIs() throws IOException
	{
		Harness h = harness();
		h.engine.getContext().restoreIncident("the-boss", "the Kraken going down", 1, 0,
			System.currentTimeMillis() - 2 * 24 * 3_600_000L, "Kraken");
		assertEquals("That's the Kraken. I'd know it anywhere.",
			h.engine.spokenText("That's a {memoryWho}. I'd know it anywhere.", TriggerEvent.tick()));
		assertEquals("The Kraken. That shape's on a page I keep rereading.",
			h.engine.spokenText("A {memoryWho}. That shape's on a page I keep rereading.", TriggerEvent.tick()));

		h.engine.getContext().restoreIncident("the-death", "your death to the Hill Giant", 1, 0,
			System.currentTimeMillis() - 2 * 24 * 3_600_000L, "Hill Giant");
		assertEquals("one of many keeps its a", "That's a Hill Giant. I'd know it anywhere.",
			h.engine.spokenText("That's a {memoryWho}. I'd know it anywhere.", TriggerEvent.tick()));
	}
}

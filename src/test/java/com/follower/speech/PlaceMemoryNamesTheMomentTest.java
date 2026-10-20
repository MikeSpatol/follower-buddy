package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import java.util.Collections;
import net.runelite.api.NPC;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a place holds names the moment, as a memory does. Text QA5, 17 Oct:
 * "Same spot. This is where it went wrong for you." and "You may not
 * remember. This is where it went wrong for you." told the player nothing.
 *
 * <p>The forms here are written to try the machinery; the shipped rules keep
 * their words until new ones are approved, and until then say nothing.
 */
public class PlaceMemoryNamesTheMomentTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final String DEATH = "{\"id\": \"death-moment\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"markHere\": \"you died to the {foe}\", \"markHereOr\": [\"you died\"],"
		+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"Get up!\"]}";

	private Harness harness(String rules) throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), "{\"version\": 1, \"rules\": [" + rules + "]}");
		h.game.at(3090, 3490, 0);
		h.gameTicks(1);
		return h;
	}

	private static String held(Harness h)
	{
		TriggerContext c = h.engine.getContext();
		return c.getPlaceMemories().get(c.getRegionId());
	}

	@Test
	public void thePlaceIsMarkedWithWhatHappened() throws IOException
	{
		Harness h = harness(DEATH);
		NPC giant = h.game.spawnNpc(2098, "Hill Giant", 28);
		h.game.fighting(giant);
		h.gameTicks(3);
		h.dispatch(TriggerEvent.death());
		assertEquals("you died to the Hill Giant", held(h));
	}

	@Test
	public void withNothingKnownAPlainerFormIsFiled() throws IOException
	{
		Harness h = harness(DEATH);
		h.dispatch(TriggerEvent.death());
		assertEquals("you died", held(h));
	}

	@Test
	public void theOldMarksAreNeverSaid() throws IOException
	{
		Harness h = harness("");
		TriggerContext c = h.engine.getContext();
		for (String old : new String[]{"it went wrong for you", "you got that drop", "you put that thing down",
			"you hit harder than you ever had"})
		{
			c.restorePlaces(Collections.emptyMap(), Collections.singletonMap(c.getRegionId(), old));
			assertFalse(old, c.hasPlaceMemory());
		}
		c.restorePlaces(Collections.emptyMap(), Collections.singletonMap(c.getRegionId(), "you died"));
		assertTrue(c.hasPlaceMemory());
	}

	@Test
	public void aLineFilledTooLongGivesWayToOneThatFits() throws IOException
	{
		Harness h = harness("{\"id\": \"place-remembers\", \"group\": \"t\", \"cooldownMs\": 0,"
			+ " \"when\": {\"type\": \"levelUp\", \"names\": [\"Attack\"]},"
			+ " \"say\": [\"You may not remember. This is where {here}.\", \"Here. {here}.\"]}");
		TriggerContext c = h.engine.getContext();
		c.restorePlaces(Collections.emptyMap(), Collections.singletonMap(c.getRegionId(),
			"the Twisted ancestral colour kit dropped off the Great Olm"));
		for (int i = 0; i < 6; i++)
		{
			h.dispatch(TriggerEvent.levelUp("Attack", 30 + i));
			h.gameTicks(1);
		}
		assertEquals(6, h.firedBy("place-remembers").size());
		for (Harness.Spoken said : h.firedBy("place-remembers"))
		{
			assertTrue(said.text, said.text.length() <= SpeechEngine.OVERHEAD_MAX_CHARS);
		}
	}
}

package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * R15: speech scales to attention. Three minutes with camera, feet and
 * hands all untouched and the world is playing to an empty chair - so the
 * follower stops performing to it, reactions included. Occasions still
 * land, the rules ABOUT the absence still speak into it, and any input at
 * all clears the state instantly.
 */
public class AttentionDamperTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final String RULES = "{\"version\": 1, \"rules\": ["
		+ "{\"id\": \"reaction\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"when\": {\"type\": \"npcSpawn\", \"names\": [\"Goblin\"]},"
		+ " \"say\": [\"a goblin arrives\"]},"
		+ "{\"id\": \"warning\", \"group\": \"t\", \"cooldownMs\": 0, \"occasion\": true,"
		+ " \"when\": {\"type\": \"npcSpawn\", \"names\": [\"Dragon\"]},"
		+ " \"say\": [\"a dragon arrives\"]},"
		+ "{\"id\": \"absence\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"when\": {\"type\": \"unattended\", \"ticks\": 250},"
		+ " \"say\": [\"gone, then\"]}]}";

	/** A harness left genuinely alone: no camera, no feet, no hands, no events. */
	private Harness abandoned() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), RULES);
		h.gameTicks(320);
		return h;
	}

	@Test
	public void nobodyWatchingMeansNoPerformance() throws IOException
	{
		Harness h = abandoned();
		h.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, 3029, "Goblin"));
		assertTrue("a reaction played to an empty chair",
			h.firedBy("reaction").isEmpty());
	}

	@Test
	public void anyInputAtAllRestoresTheAudience() throws IOException
	{
		// The camera.
		Harness h = abandoned();
		h.game.cameraMoved();
		h.gameTicks(1);
		h.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, 3029, "Goblin"));
		assertEquals("the camera moving is presence", 1, h.firedBy("reaction").size());

		// The hands.
		Harness busy = abandoned();
		busy.game.animating(879);
		busy.gameTicks(1);
		busy.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, 3029, "Goblin"));
		assertEquals("animating is presence", 1, busy.firedBy("reaction").size());

		// A real event, which cannot happen from the kettle.
		Harness fighting = abandoned();
		fighting.dispatch(TriggerEvent.simple(TriggerEvent.Type.COMBAT_START));
		fighting.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, 3029, "Goblin"));
		assertEquals("a fight starting is presence", 1, fighting.firedBy("reaction").size());
	}

	@Test
	public void theWarningStillLandsOnTheEmptyChair() throws IOException
	{
		// An occasion matters the moment they glance back at the screen.
		Harness h = abandoned();
		h.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, 3029, "Dragon"));
		assertEquals("occasions are exempt from the damper",
			1, h.firedBy("warning").size());
	}

	@Test
	public void theAbsenceItselfMayStillBeRemarkedOn() throws IOException
	{
		// The gone-away jokes are ABOUT the empty chair; silencing them with
		// it would remove the only lines written for exactly this moment.
		Harness h = abandoned();
		assertEquals("the absence rules speak into the absence",
			1, h.firedBy("absence").size());
	}
}

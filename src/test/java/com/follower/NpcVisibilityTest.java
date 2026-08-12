package com.follower;

import com.follower.sim.Harness;
import net.runelite.api.CollisionDataFlag;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A callout must be at something the player can actually see.
 *
 * <p>"That thing is very large and very close" invites the player to look. If
 * what is large and close is a kalphite behind a wall, the line is worse than
 * silence: one bad callout teaches the player that the follower's callouts
 * are not worth checking, which spends the credibility every good one earned.
 * Distance was the whole test before this - a wall three tiles away did not
 * exist as far as npcNearby was concerned.
 *
 * <p>The sight check is the game's own: {@code WorldArea.hasLineOfSightTo}
 * reads the scene's sight-blocking collision flags, so what the follower can
 * "see" and what the game would let an archer shoot agree by construction.
 */
public class NpcVisibilityTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** One rule that requires sight, one that does not, same NPC. */
	private static final String RULES = "{\"version\": 1, \"rules\": ["
		+ "{\"id\": \"callout\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"when\": {\"type\": \"npcNearby\", \"minimum\": 250, \"within\": 8,"
		+ " \"visible\": true},"
		+ " \"say\": [\"look at that\"]},"
		+ "{\"id\": \"sense\", \"group\": \"t\", \"cooldownMs\": 0, \"priority\": 10,"
		+ " \"when\": {\"type\": \"npcNearby\", \"minimum\": 250, \"within\": 8},"
		+ " \"say\": [\"something is about\"]}]}";

	/**
	 * The fake's collision map is scene-sized with a base of zero, so world
	 * coordinates must stay inside the 104-tile scene. Out-of-scene
	 * coordinates do not merely miss - the LOS lookup throws, the engine's
	 * guard disables the throwing rule, and the test fails by silence, which
	 * took some finding.
	 */
	private Harness harness() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), RULES);
		h.game.withCollision();
		h.game.at(50, 50, 0);
		return h;
	}

	@Test
	public void aVisibleGiantGetsTheCallout() throws IOException
	{
		Harness h = harness();
		h.game.moveNpc(h.game.spawnNpc(494, "Kalphite Queen", 333), 54, 50, 0);
		h.gameTicks(3);

		assertEquals("open ground: the callout fires", 1, h.firedBy("callout").size());
	}

	@Test
	public void aGiantBehindAWallIsNotACallout() throws IOException
	{
		Harness h = harness();
		h.game.moveNpc(h.game.spawnNpc(494, "Kalphite Queen", 333), 54, 50, 0);
		// A sight-blocking wall between the player and it.
		h.game.wallAt(52, 50, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL);
		h.gameTicks(3);

		assertTrue("a callout at a wall teaches the player to stop checking",
			h.firedBy("callout").isEmpty());
		assertEquals("but a rule that only senses, without pointing, still may",
			1, h.firedBy("sense").size());
	}

	@Test
	public void noCollisionDataReadsAsVisible() throws IOException
	{
		// During loading there is nothing to consult, and refusing to mention
		// a boss because the map has not finished arriving is the wrong kind
		// of caution. The harness without withCollision() is exactly the
		// no-data world.
		Harness h = new Harness(folder.newFolder().toPath(), RULES);
		h.game.at(50, 50, 0);
		h.game.moveNpc(h.game.spawnNpc(494, "Kalphite Queen", 333), 54, 50, 0);
		h.gameTicks(3);

		assertEquals("benefit of the doubt with no map", 1, h.firedBy("callout").size());
	}

	@Test
	public void theShippedLookableRulesAllRequireSight() throws IOException
	{
		// The audit itself, pinned: every rule whose lines invite the player
		// to look at an NPC carries the visible requirement. If a new
		// pointing rule arrives without it, this list is where the argument
		// happens.
		Harness h = new Harness(folder.newFolder().toPath());
		for (String id : new String[]{"something-big-nearby", "cats-and-pets", "boss-while-low"})
		{
			com.follower.speech.SpeechRule rule = h.rule(id);
			assertTrue(id + " points at an NPC and must require sight of it",
				requiresSight(rule.when));
		}
	}

	private static boolean requiresSight(com.follower.speech.Condition condition)
	{
		if (condition == null)
		{
			return false;
		}
		if ("npcNearby".equalsIgnoreCase(condition.type)
			&& Boolean.TRUE.equals(condition.visible))
		{
			return true;
		}
		if (condition.conditions != null)
		{
			for (com.follower.speech.Condition child : condition.conditions)
			{
				if (requiresSight(child))
				{
					return true;
				}
			}
		}
		return false;
	}
}

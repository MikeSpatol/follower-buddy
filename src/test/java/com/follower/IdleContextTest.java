package com.follower;

import com.follower.sim.Harness;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * R18: the idle pool swaps by context. Exactly one of the three sets serves
 * in any state - the guards partition them the way the voice eras partition
 * the calendar - so an idle stretch in the Wilderness sounds watchful, one
 * in a crowd sounds like people-watching, and neither leaks anywhere else.
 */
public class IdleContextTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int WILDERNESS_VARBIT = 5963;

	/** Idles long enough that the 30% flicker has fired whichever pool serves. */
	private Harness idled(java.util.function.Consumer<Harness> context) throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		context.accept(h);
		h.gameTicks(400);
		return h;
	}

	@Test
	public void theWildernessIdlesWatchfully() throws IOException
	{
		Harness h = idled(g -> g.game.varbit(WILDERNESS_VARBIT, 1));
		assertFalse("the wilds pool serves here", h.firedBy("idle-chatter-wilds").isEmpty());
		assertTrue(h.firedBy("idle-chatter").isEmpty());
		assertTrue(h.firedBy("idle-chatter-town").isEmpty());
	}

	@Test
	public void aCrowdIdlesAsPeopleWatching() throws IOException
	{
		Harness h = idled(g ->
		{
			for (int i = 0; i < 8; i++)
			{
				g.game.spawnPlayer(1 + i % 3, 1 + i / 3);
			}
		});
		assertFalse("the town pool serves here", h.firedBy("idle-chatter-town").isEmpty());
		assertTrue(h.firedBy("idle-chatter").isEmpty());
		assertTrue(h.firedBy("idle-chatter-wilds").isEmpty());
	}

	@Test
	public void anEmptyFieldIdlesTheOldWay()  throws IOException
	{
		Harness h = idled(g -> { });
		assertFalse("the default pool still serves", h.firedBy("idle-chatter").isEmpty());
		assertTrue(h.firedBy("idle-chatter-wilds").isEmpty());
		assertTrue(h.firedBy("idle-chatter-town").isEmpty());
	}
}

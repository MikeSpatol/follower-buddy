package com.follower.speech;

import com.follower.FollowerConfig;
import com.follower.follower.FollowerEntity;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.config.Alpha;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The overhead line, drawn as the game draws one (QA, 16 Oct): over the
 * follower only while the follower is there to be seen, and never
 * translucent.
 */
public class OverheadDrawTest
{
	private boolean shown = true;
	private int placed;

	private final FollowerEntity follower = new FollowerEntity(null, null)
	{
		@Override
		public boolean isSpawned()
		{
			return true;
		}

		@Override
		public boolean isShown()
		{
			return shown;
		}

		@Override
		public LocalPoint getLastRenderedLocation()
		{
			// Asked for only when the line is about to be placed over it.
			placed++;
			return null;
		}
	};

	@Test
	public void theLineWaitsOutOfSightWithItsFollower()
	{
		FollowerOverlay overlay = new FollowerOverlay(null, follower, null, null);
		overlay.show("Hm.", 60_000);

		// An NPC stopped on its tile, a teleport, the comedy death.
		shown = false;
		assertNull(overlay.render(null));
		assertEquals("not drawn over the empty tile, or the NPC on it", 0, placed);

		shown = true;
		overlay.render(null);
		assertEquals("back with the follower, its time not up", 1, placed);
	}

	@Test
	public void theColourHasNoOpacityToSet() throws NoSuchMethodException
	{
		// The game draws overhead text opaque, and so does the bitmap font
		// here, so a slider for it would do nothing.
		assertNull(FollowerConfig.class.getMethod("speechColor").getAnnotation(Alpha.class));
	}
}

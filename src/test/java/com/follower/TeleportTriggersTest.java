package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.List;
import net.runelite.api.gameval.AnimationID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Every spellbook's teleport is copied, not only the Standard and Ancient
 * ones (QA, 15 Oct). The ids are gameval's, on the player skeleton in the
 * cache; which one each spellbook plays is to be watched in game.
 */
public class TeleportTriggersTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private Harness copying(int animation) throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(2);
		h.dispatch(TriggerEvent.animation(animation));
		h.gameTicks(2);
		return h;
	}

	@Test
	public void theLunarArceuusAndTabletTeleportsAreCopied() throws IOException
	{
		for (int cast : new int[]{AnimationID.HUMAN_TELEPORT_OTHER_IMPACT,
			AnimationID.HUMAN_SPELLCAST_ARCEUUS_TELEPORT, AnimationID.POH_ABSORB_TABLET_TELEPORT})
		{
			List<Harness.Spoken> copied = copying(cast).firedBy("mirror-teleport");
			assertEquals("teleport " + cast, 1, copied.size());
			assertEquals(cast, copied.get(0).animationId);
		}
	}

	@Test
	public void breakingATabletIsNotATeleport() throws IOException
	{
		// The smash is the same for every tablet, bones to bananas and the
		// enchantments too: only the teleport's own absorb is copied.
		assertTrue(copying(AnimationID.POH_SMASH_MAGIC_TABLET).firedBy("mirror-teleport").isEmpty());
	}
}

package com.follower;

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

	private Harness copying(int animation, String... silenced) throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.setDisabledGroups(new java.util.HashSet<>(java.util.Arrays.asList(silenced)));
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

	/**
	 * QA3: the copies were in Reactions, so unticking Reactions to quiet the
	 * death and level remarks stopped them, and Mimicry, which governs the
	 * other copies, did not.
	 */
	@Test
	public void mimicryGovernsTheTeleportCopiesAndReactionsDoesNot() throws IOException
	{
		int cast = AnimationID.HUMAN_SPELLCAST_ARCEUUS_TELEPORT;
		int home = AnimationID.LEAGUE03_HOME_TELEPORT_HUMAN_1;
		assertEquals(1, copying(cast, "reactions").firedBy("mirror-teleport").size());
		assertEquals(1, copying(home, "reactions").firedBy("mirror-home-teleport").size());
		assertTrue(copying(cast, "mimic").firedBy("mirror-teleport").isEmpty());
		assertTrue(copying(home, "mimic").firedBy("mirror-home-teleport").isEmpty());
	}

	@Test
	public void breakingATabletIsNotATeleport() throws IOException
	{
		// The smash is the same for every tablet, bones to bananas and the
		// enchantments too: only the teleport's own absorb is copied.
		assertTrue(copying(AnimationID.POH_SMASH_MAGIC_TABLET).firedBy("mirror-teleport").isEmpty());
	}

	/**
	 * QA, 16 Oct: two cosmetic home teleports' first stages, beside the
	 * Trailblazer and League 3 ones in gameval and the cache, were missing,
	 * and with them the copy. COW_HOME_TELEPORT_1 in RuneLite's own legacy
	 * constants is 1696, so that one stays though gameval names it a dodge.
	 */
	@Test
	public void everyHomeTeleportsFirstStageStartsTheCopy() throws IOException
	{
		for (int start : new int[]{AnimationID.AIDE_DRAWING_CHALK_CIRCLE,
			AnimationID.LEAGUES_6_HOME_TELEPORT_PLAYER_01, AnimationID.LEAGUE_TRAILBLAZER_HOME_TELEPORT_HUMAN_1,
			AnimationID.LEAGUE03_HOME_TELEPORT_HUMAN_1, AnimationID.HUMAN_DODGE_RF_A,
			AnimationID.HOME_TELEPORT_HUMAN_FIRE_1, AnimationID.HOME_TELEPORT_HUMAN_ECHOES_1})
		{
			assertEquals("home teleport " + start, 1, copying(start).firedBy("mirror-home-teleport").size());
		}
	}
}

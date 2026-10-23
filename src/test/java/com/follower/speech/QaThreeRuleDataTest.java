package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import net.runelite.api.NPC;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The shipped rules the third QA pass found wrong in their data: the warning
 * pacing, the advice to get out, Prince Itzla's stage and the place
 * callbacks said seconds after their quest.
 */
public class QaThreeRuleDataTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final long[] ms = {1_700_000_000_000L};

	private Harness shipped() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(6000L);
		h.game.at(3222, 3218, 0);
		ticks(h, 20);
		h.spoken.clear();
		return h;
	}

	private void ticks(Harness h, int count)
	{
		for (int i = 0; i < count; i++)
		{
			ms[0] += 600;
			h.game.cameraMoved();
			h.gameTick();
		}
	}

	/**
	 * Behaviour QA, 16 Oct: for a player who eats late, low-hp was about 68
	 * an hour in combat, a third of all combat lines. Behaviour QA4, 16 Oct:
	 * at Minimal the warning loop was still 62% of combat lines. Once every
	 * three minutes at most; critical-hp still cuts in for real danger.
	 */
	@Test
	public void lowHealthIsSaidAtMostOnceEveryThreeMinutes() throws IOException
	{
		Harness h = shipped();
		for (int dip = 0; dip < 6; dip++)
		{
			h.game.hitpoints(25, 70);
			ticks(h, 3);
			h.game.hitpoints(60, 70);
			ticks(h, 30);
		}
		assertEquals("six dips in a minute and a half", 1, h.firedBy("low-hp").size());
		ticks(h, 110);
		h.game.hitpoints(25, 70);
		ticks(h, 3);
		assertEquals("and the next dip three minutes on", 2, h.firedBy("low-hp").size());
	}

	/**
	 * Probe P12: told to get out, the player broke a teleport tablet or cast
	 * a Lunar teleport, and a minute later was told the advice was ignored.
	 * The ids are gameval (1.13.1): HUMAN_TELEPORT_OTHER_IMPACT 1816,
	 * HUMAN_SPELLCAST_ARCEUUS_TELEPORT 6575, POH_SMASH_MAGIC_TABLET 4069,
	 * POH_ABSORB_TABLET_TELEPORT 4071 - the casts mirror-teleport knows.
	 */
	// public:cut side-games
	@Test
	public void aTabletOrALunarTeleportHeedsGetOut() throws IOException
	{
		for (int cast : new int[]{1816, 6575, 4069, 4071})
		{
			Harness h = shipped();
			h.game.hitpoints(10, 70);
			ticks(h, 1);
			assertEquals(1, h.firedBy("critical-hp").size());
			assertTrue(h.engine.getContext().isAdvising());
			ticks(h, 2);
			h.dispatch(TriggerEvent.animation(cast));
			ticks(h, 2);
			assertEquals("cast " + cast + " heeds it", 1, h.engine.getContext().getTally("advice:taken"));
		}
	}
	// public:end side-games

	/**
	 * Quests QA, 16 Oct: Prince Itzla's line rose inside the delegation's
	 * scene (real log, twice), was said after it with him gone, and its hour
	 * kept it off the roof. Not on the delegation's stage (9632 = 1-15).
	 */
	@Test
	public void itzlaIsNotAnnouncedAtTheDelegation() throws IOException
	{
		Harness h = shipped();
		h.game.varbit(9632, 4);
		NPC itzla = h.game.spawnNpc(12592, "Prince Itzla Arkan", 0);
		ticks(h, 20);
		assertTrue("not at the delegation", h.firedBy("quest-itzla").isEmpty());

		h.game.moveNpc(itzla, 3300, 3300, 0);
		ticks(h, 5);
		h.game.varbit(9632, 16);
		h.game.moveNpc(itzla, 3223, 3218, 0);
		ticks(h, 30);
		assertEquals("on the roof", 1, h.firedBy("quest-itzla").size());
	}

	/**
	 * Quests QA, 16 Oct: "Sedridor's basement is under us. I'm still working
	 * out how he knew you", 8 s after the Rune Mysteries scroll. A place
	 * callback whose words assume time has passed waits a day, as Tobyn's
	 * does.
	 */
	@Test
	public void aPlaceCallbackWaitsADayAfterItsQuest() throws IOException
	{
		String[][] callbacks = {
			{"fun-wizards-tower-after-rune-mysteries", "RUNE_MYSTERIES"},
			{"fun-paterdomus-after-priest-in-peril", "PRIEST_IN_PERIL"},
			{"fun-baxtorian-falls-after-waterfall-quest", "WATERFALL_QUEST"},
			{"fun-varrock-after-children-of-the-sun", "CHILDREN_OF_THE_SUN"},
			// Quests QA4 #28 and chronicle QA4 #43: the four missed.
			{"fun-lumbridge-after-cooks-assistant", "COOKS_ASSISTANT"},
			{"fun-lumbridge-after-sheep-shearer", "SHEEP_SHEARER"},
			{"fun-draynor-after-x-marks-the-spot", "X_MARKS_THE_SPOT"},
			{"fun-aubury-after-rune-mysteries", "RUNE_MYSTERIES"}};
		Harness h = shipped();
		long today = java.time.LocalDate.now().toEpochDay();
		for (String[] callback : callbacks)
		{
			SpeechRule rule = h.rule(callback[0]);
			Condition witnessed = null;
			for (Condition c : rule.when.conditions)
			{
				if ("questWitnessed".equals(c.type))
				{
					witnessed = c;
				}
			}
			h.engine.getContext().setQuestWitness(q -> callback[1].equals(q) ? today : null);
			assertFalse(callback[0] + " the day of the quest",
				witnessed.matches(h.engine.getContext(), TriggerEvent.tick()));
			h.engine.getContext().setQuestWitness(q -> callback[1].equals(q) ? today - 1 : null);
			assertTrue(callback[0] + " a day on", witnessed.matches(h.engine.getContext(), TriggerEvent.tick()));
		}
	}

	/**
	 * Behaviour QA, 16 Oct: souvenir-mention, whose every line says time has
	 * passed, came 8-19 s after the picking up in 5 of 12 days. It waits ten
	 * minutes of carrying.
	 */
	// public:cut souvenirs-wishes
	@Test
	public void aSouvenirIsMentionedOnlyOnceItHasBeenCarriedAWhile() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), "{\"version\": 1, \"rules\": ["
			+ "{\"id\": \"keeper\", \"group\": \"t\", \"cooldownMs\": 0,"
			+ " \"pickUp\": {\"what\": \"a flat rock\", \"minutes\": 60},"
			+ " \"when\": {\"type\": \"login\"}, \"say\": [\"I'm keeping this rock\"]},"
			+ "{\"id\": \"mention\", \"group\": \"t\", \"cooldownMs\": 0,"
			+ " \"when\": {\"type\": \"all\", \"conditions\": [{\"type\": \"notesEdited\"},"
			+ " {\"type\": \"carrying\", \"minimum\": 10}]}, \"say\": [\"still got the rock\"]}]}");
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));
		assertTrue(h.engine.getContext().isCarrying());
		h.gameTicks(15);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.NOTES_EDITED));
		assertTrue("not seconds after", h.firedBy("mention").isEmpty());
		for (int i = 0; i < 1000; i++)
		{
			h.game.cameraMoved();
			h.gameTick();
		}
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.NOTES_EDITED));
		assertEquals("ten minutes on", 1, h.firedBy("mention").size());

		boolean waits = false;
		for (Condition c : shipped().rule("souvenir-mention").when.conditions)
		{
			waits |= "carrying".equals(c.type) && Integer.valueOf(10).equals(c.minimum);
		}
		assertTrue("the shipped mention waits ten minutes", waits);
	}
	// public:end souvenirs-wishes
}

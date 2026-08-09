package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.Condition;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The follower asking for something, and what happens either way.
 *
 * <p>Everything else in the rule set runs one direction: something happened,
 * and a line remarks on it. A want runs the other way, which is why it gets its
 * own tests - the failure modes are different. A want that can never be
 * fulfilled (a region no rule has heard of) is silent rather than broken, and a
 * want whose label names a different place from its region is worse than
 * silent, because it fires and lies.
 */
public class WantsTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private Set<Integer> regionsOf(Condition condition)
	{
		Set<Integer> found = new TreeSet<>();
		if (condition != null)
		{
			condition.collectRegions(found);
		}
		return found;
	}

	@Test
	public void everyWantPointsAtAPlaceTheRuleSetKnows() throws IOException
	{
		// A want naming a region no area rule claims is a want that can only
		// ever expire - it would look like the follower asking for somewhere
		// imaginary and then sulking about not being taken there.
		Harness h = new Harness(folder.newFolder().toPath());

		Set<Integer> claimed = new HashSet<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if ("area".equals(rule.group))
			{
				claimed.addAll(regionsOf(rule.when));
			}
		}

		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.want == null)
			{
				continue;
			}
			assertTrue(rule.id + " wants region " + rule.want.region
					+ ", which no area rule has ever heard of",
				claimed.contains(rule.want.region));
			assertTrue(rule.id + " must say where it is asking to go",
				rule.want.label != null && !rule.want.label.isEmpty());
			assertTrue(rule.id + " must give the player time to get there",
				rule.want.minutes != null && rule.want.minutes >= 5);
		}
	}

	@Test
	public void askingAndBeingTakenThereIsTheWholeLoop() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		// Somewhere that is not one of the places it can ask for. The default
		// tile is region 12850, which IS Lumbridge - and a want for where you
		// already are is refused, so from there the ask sometimes goes nowhere.
		h.game.at(3000, 3000, 0);
		h.gameTicks(1);

		// The question, then the answer, then the ask. Which place it names is
		// a chance roll between three rules, so this may take a couple of goes.
		for (int i = 0; i < 20 && !h.engine.getContext().isWanting(); i++)
		{
			h.engine.getContext().noteQuestion();
			h.playerSays("yes");
			h.gameTicks(5);
		}
		assertTrue("saying yes should get the follower to name somewhere",
			h.engine.getContext().isWanting());
		String asked = h.engine.getContext().getWantLabel();
		assertFalse("and it has to be somewhere in particular", asked.isEmpty());

		// Lumbridge, the Grand Exchange or the Fishing Guild, whichever won.
		SpeechRule winner = null;
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.want != null && asked.equals(rule.want.label))
			{
				winner = rule;
			}
		}
		assertFalse("the label has to belong to a real want rule", winner == null);

		int region = winner.want.region;
		h.game.at((region >> 8) << 6, (region & 0xFF) << 6, 0);
		assertEquals("the test tile has to be inside the wanted region", region,
			new WorldPoint((region >> 8) << 6, (region & 0xFF) << 6, 0).getRegionID());

		h.clear();
		// want-fulfilled rolls a two-to-five tick delay, so four ticks leaves it
		// pending a quarter of the time. Comfortably past the longest roll.
		h.gameTicks(10);
		// Either the place-specific arrival or the generic fallback: which one
		// depends on whether that place has a line of its own, and both are a
		// correct answer to having been taken there.
		assertFalse("going where it asked is the whole point",
			h.firedBy("want-fulfilled").isEmpty()
				&& h.firedBy("want-fulfilled-" + winner.id.substring("want-".length())).isEmpty());
		assertTrue("and the want is spent", !h.engine.getContext().isWanting());
	}

	@Test
	public void beingTakenThereIsTheBiggestMoodSwingInTheFile() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		int before = h.engine.getContext().getMood();

		int best = 0;
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.mood != null && rule.mood > best)
			{
				best = rule.mood;
			}
		}
		SpeechRule fulfilled = h.rule("want-fulfilled");
		assertEquals("nothing should please the follower more than being listened to",
			best, (int) fulfilled.mood);
		// Mood bands are twenty points wide, so a swing smaller than that could
		// leave the follower in the same band it was already in - delighted in
		// a way nothing about it actually showed.
		assertTrue("the lift has to be big enough to change the band it is in",
			fulfilled.mood >= 20);
		assertTrue("and it starts somewhere it can climb from", before < 100);
	}

	@Test
	public void aWantThatLapsesIsADisappointmentNotASulk() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		SpeechRule expired = h.rule("want-expired");
		SpeechRule fulfilled = h.rule("want-fulfilled");

		assertTrue("the drop for being ignored has to be survivable",
			expired.mood != null && expired.mood > -15);
		assertTrue("and smaller than the lift for being listened to",
			Math.abs(expired.mood) < fulfilled.mood);
	}

	@Test
	public void takingTheFloorKeepsEverythingElseQuietForAWhile() throws IOException
	{
		// The engine mechanism on its own. The harness runs with no global
		// speech gap, so without the floor the second line would come straight
		// out - which is what makes this bite.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"important\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"hushMs\": 5000, \"when\": {\"type\": \"examined\"},"
				+ " \"say\": [\"listen to me\"]},"
				+ "{\"id\": \"chatter\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"unrelated\"]}]}");
		h.gameTicks(1);

		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertFalse("the rule taking the floor says its line",
			h.firedBy("important").isEmpty());

		h.dispatch(TriggerEvent.death());
		assertTrue("and nothing else gets a word in while it holds it",
			h.firedBy("chatter").isEmpty());
	}

	@Test
	public void theRuleTakingTheFloorIsNotHeldBackByWhatSpokeBeforeIt() throws IOException
	{
		// The same failure from the other end, and the one that actually bit:
		// an area line a second earlier would drop the arrival line entirely on
		// the global speech gap, so the moment worth having was the one lost.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"important\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"hushMs\": 5000, \"when\": {\"type\": \"examined\"},"
				+ " \"say\": [\"listen to me\"]},"
				+ "{\"id\": \"chatter\", \"group\": \"t\", \"cooldownMs\": 0,"
				+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"unrelated\"]}]}");
		h.engine.setGlobalCooldownMs(3000L);
		h.gameTicks(1);

		h.dispatch(TriggerEvent.death());
		assertFalse("something ordinary speaks first", h.firedBy("chatter").isEmpty());

		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertFalse("the important line must not be eaten by the gap",
			h.firedBy("important").isEmpty());
	}

	@Test
	public void arrivingWhereItAskedIsOneThoughtAndNotTwo() throws IOException
	{
		// Walking into the wanted region makes the arrival rule and the area
		// rule true on the same pass. Only one can win, and both are evaluated
		// in the WANT_FULFILLED dispatch, so the area rule's rising edge is
		// consumed there without firing - it is not that it loses later, it is
		// that its one chance was spent on a dispatch it did not win.
		Harness h = new Harness(folder.newFolder().toPath());
		h.game.at(3000, 3000, 0);
		h.gameTicks(2);
		h.engine.getContext().setWant(12850, "Lumbridge", 20);

		// Into Lumbridge: region 12850, which area-lumbridge also watches.
		h.game.at(12850 >> 8 << 6, (12850 & 0xFF) << 6, 0);
		assertEquals("the test tile has to be Lumbridge", 12850,
			new WorldPoint(12850 >> 8 << 6, (12850 & 0xFF) << 6, 0).getRegionID());
		h.clear();
		h.gameTicks(12);

		assertFalse("the arrival it asked for has to survive the region change",
			h.firedBy("want-fulfilled-lumbridge").isEmpty());
		assertTrue("and the area line must not talk over it",
			h.firedBy("area-lumbridge").isEmpty());
	}

	@Test
	public void theArrivalLineSaysWhyRatherThanOnlyThankYou() throws IOException
	{
		// A place-specific line for every place a want can ask for. Thanking
		// you for going somewhere is politeness; saying why THAT place is a
		// preference, and a preference is the thing that reads as a mind.
		Harness h = new Harness(folder.newFolder().toPath());
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.want == null)
			{
				continue;
			}
			String expected = "want-fulfilled-" + rule.id.substring("want-".length());
			SpeechRule arrival = h.rule(expected);
			assertTrue(expected + " must beat the generic want-fulfilled",
				arrival.priority > h.rule("want-fulfilled").priority);
			assertTrue(expected + " must take the floor like the generic one does",
				arrival.hushMs != null && arrival.hushMs > 0);
		}
	}

	@Test
	public void holdingTheFloorDoesNotSilenceTheOneHoldingIt() throws IOException
	{
		// The obvious way to get this wrong: a rule caught by its own hush,
		// which would make the feature silence exactly the line it exists for.
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);

		assertTrue(h.engine.force("want-fulfilled-lumbridge"));
		assertFalse("the rule holding the floor still speaks",
			h.firedBy("want-fulfilled-lumbridge").isEmpty());

		// And an animation-only rule is movement rather than chatter, so the
		// hush is not meant to stop it. A 20-29 hit is the band nothing is said
		// about, so flinch-big-hit is the only rule in the running.
		h.clear();
		h.dispatch(TriggerEvent.damageTaken(25));
		h.gameTicks(2);
		assertFalse("the flinch is not speech and must not be hushed",
			h.firedBy("flinch-big-hit").isEmpty());
	}

	@Test
	public void forcingARuleRunsTheWholeRuleAndNotJustTheWords() throws IOException
	{
		// ::follower fire exists because several rules only happen on a small
		// roll after a minute of standing still. It is worth nothing if it
		// only says the line: the point of forcing ask-outing is to get the
		// question WINDOW open so the answer can be tested.
		Harness h = new Harness(folder.newFolder().toPath());
		h.game.at(3000, 3000, 0);
		h.gameTicks(1);

		assertFalse("nothing has been asked", h.engine.getContext().isAwaitingAnswer());
		assertTrue("the rule has to exist to be forced", h.engine.force("ask-outing"));
		assertTrue("forcing a rule marked asks must open the window",
			h.engine.getContext().isAwaitingAnswer());
		assertFalse("and it must actually have said something",
			h.firedBy("ask-outing").isEmpty());

		assertFalse("a rule that does not exist reports as much",
			h.engine.force("no-such-rule"));
	}

	@Test
	public void forcingIgnoresTheCooldownThatWouldNormallyHoldItBack() throws IOException
	{
		// ask-outing has a fifteen minute cooldown. A test tool that respected
		// it would be useless the second time you reached for it.
		Harness h = new Harness(folder.newFolder().toPath());
		h.game.at(3000, 3000, 0);
		h.gameTicks(1);

		h.engine.force("examined");
		h.engine.force("examined");
		assertTrue("forcing twice in a row has to work twice",
			h.firedBy("examined").size() >= 2);
	}

	@Test
	public void itDoesNotAskToBeTakenWhereItAlreadyIs() throws IOException
	{
		// Being delighted about arriving somewhere you never left is not a wish.
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		int here = h.engine.getContext().getRegionId();

		h.engine.getContext().setWant(here, "right here", 20);
		assertFalse("a want for where we are standing must be refused",
			h.engine.getContext().isWanting());

		h.engine.getContext().setWant(here + 1, "somewhere else", 20);
		assertTrue("somewhere we are not is a real want",
			h.engine.getContext().isWanting());
	}

	@Test
	public void goingSomewhereElseDoesNotCountAsGoingWhereItAsked() throws IOException
	{
		// The whole value of a want is that it names ONE place. A fulfilment
		// that fires on any region change would make the follower delighted
		// with wherever you were already heading, which is flattery rather
		// than being listened to - and it would look identical from outside
		// until you noticed it was never wrong.
		Harness h = new Harness(folder.newFolder().toPath());
		h.game.at(3000, 3000, 0);
		h.gameTicks(1);
		h.engine.getContext().setWant(12850, "Lumbridge", 20);

		// Region 12598, which is not 12850.
		h.game.at(12598 >> 8 << 6, (12598 & 0xFF) << 6, 0);
		h.gameTicks(10);

		assertTrue("the wrong town must not satisfy it",
			h.firedBy("want-fulfilled").isEmpty());
		assertTrue("and the want has to still be open",
			h.engine.getContext().isWanting());
	}

	@Test
	public void onlyOneThingCanBeWantedAtATime() throws IOException
	{
		// Two open wants would make going anywhere satisfy something, which is
		// the same as satisfying nothing.
		Harness h = new Harness(folder.newFolder().toPath());
		h.game.at(3000, 3000, 0);
		h.gameTicks(1);
		h.engine.getContext().setWant(12850, "Lumbridge", 20);
		h.engine.getContext().setWant(12598, "the Grand Exchange", 20);

		assertEquals("the second ask must not quietly replace the first",
			"Lumbridge", h.engine.getContext().getWantLabel());
	}

	@Test
	public void thereAreEnoughPlacesToRollATasteFrom() throws IOException
	{
		// The trait roll draws liked and disliked regions from the regions the
		// rule set already has opinions about. Too small a pool and the roll
		// silently never happens, so no follower ever has a taste.
		Harness h = new Harness(folder.newFolder().toPath());
		Set<Integer> pool = new TreeSet<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			pool.addAll(regionsOf(rule.when));
		}
		assertTrue("a taste rolled from " + pool.size() + " places would barely differ"
			+ " between two followers", pool.size() >= 20);
	}
}

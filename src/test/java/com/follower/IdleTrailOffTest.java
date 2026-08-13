package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The follower runs out of things to say about standing still.
 *
 * <p>The director cannot produce this behaviour. It handles bursts - intensity
 * rising faster than it decays - and an idle afternoon is the opposite shape:
 * a line every forty-five seconds decays completely between lines and sails
 * under the peak forever. Measured, a forty-one minute AFK produced eighty-two
 * lines an hour and no silence longer than two minutes, which is not a
 * companion, it is a dripping tap.
 *
 * <p>Two facts about idleness shaped the mechanism, and both were learned from
 * failures rather than foresight. Idle rules fire on a RISING edge, so a
 * player who is literally motionless hears each of them once - the eighty
 * lines an hour came from POTTERING, little moves between little stops, each
 * stop re-arming the edges. Which is why the stretch must not be ended by
 * movement: the first draft reset it on any step, and would have been a no-op
 * in exactly the session it was built from. Doing something real ends it.
 */
public class IdleTrailOffTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final long GAP = 3_000L;
	private static final long TICK_MS = 600L;

	/**
	 * One chatty idle rule, one reaction, one idle-conditioned occasion, so
	 * all three treatments are observable. The occasion needs a LATER idle
	 * threshold than the chatter - both rising on the same tick would hand the
	 * win to one and consume the other's edge, the first-page bug again - and
	 * a REAL cooldown, because occasions are exempt from the trail-off and
	 * rely on their own pacing. The first draft gave it cooldown zero and it
	 * cheerfully fired seventy-five times an hour, which said nothing about
	 * the engine and everything about writing an occasion with no cooldown.
	 */
	private static final String RULES = "{\"version\": 1, \"rules\": ["
		+ "{\"id\": \"chatter\", \"group\": \"idle\", \"cooldownMs\": 30000,"
		+ " \"when\": {\"type\": \"idle\", \"ticks\": 10},"
		+ " \"say\": [\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\","
		+ "\"k\",\"l\",\"m\",\"n\",\"o\",\"p\",\"q\",\"r\",\"s\",\"t\"]},"
		+ "{\"id\": \"reaction\", \"group\": \"reactions\", \"cooldownMs\": 0,"
		+ " \"when\": {\"type\": \"examined\"}, \"say\": [\"x\", \"y\"]},"
		+ "{\"id\": \"big-day\", \"group\": \"memory\", \"cooldownMs\": 3600000,"
		+ " \"occasion\": true,"
		+ " \"when\": {\"type\": \"idle\", \"ticks\": 25}, \"say\": [\"today matters\"]}]}";

	private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

	private Harness harness() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), RULES);
		h.engine.setClock(clock::get);
		h.engine.setGlobalCooldownMs(GAP);
		return h;
	}

	private boolean parity;

	/**
	 * What an "idle" session actually is: pottering. Stand for most of a
	 * minute, shuffle a tile, stand again. Every stop re-arms the idle rules'
	 * rising edges, which is how a real player going nowhere in particular
	 * produced a line every forty-five seconds.
	 */
	private void potterFor(Harness h, int ticks)
	{
		for (int i = 0; i < ticks; i++)
		{
			if (i % 80 == 79)
			{
				parity = !parity;
				h.game.at(3200 + (parity ? 1 : 0), 3200, 0);
			}
			clock.addAndGet(TICK_MS);
			h.gameTick();
		}
	}

	private int said(Harness h, String id)
	{
		return h.firedBy(id).size();
	}

	// ------------------------------------------------------------ the shape

	@Test
	public void aPotteredHourStartsChattyAndWindsDown() throws IOException
	{
		// The rule's cooldown alone would allow a line a minute: sixty of
		// them. The trail-off lets the early ones through at full rate and
		// stretches everything after.
		Harness h = harness();
		List<Long> spokenAt = new ArrayList<>();
		h.engine.setSink((text, output, rule, animationId, onSaid) ->
		{
			onSaid.run();
			if (!text.isEmpty())
			{
				spokenAt.add(clock.get());
			}
		});
		potterFor(h, 6000);      // one hour

		assertTrue("the first minutes should still be chatty: only "
			+ spokenAt.size() + " lines all hour", spokenAt.size() >= 5);
		assertTrue("an hour of pottering produced " + spokenAt.size()
			+ " lines, which is the dripping tap this exists to stop",
			spokenAt.size() <= 14);

		// And the gaps have to GROW, or this is a slower drip rather than a
		// wind-down.
		long firstGap = spokenAt.get(1) - spokenAt.get(0);
		long lastGap = spokenAt.get(spokenAt.size() - 1)
			- spokenAt.get(spokenAt.size() - 2);
		assertTrue("gaps should stretch as the stretch wears on: first "
			+ firstGap + "ms, last " + lastGap + "ms", lastGap >= firstGap * 3);
	}

	@Test
	public void doingSomethingRealResetsTheStretch() throws IOException
	{
		Harness h = harness();
		potterFor(h, 3000);      // half an hour: deep in the wind-down
		int wound = said(h, "chatter");

		// A fight. Not a step - steps happen constantly while pottering and
		// must not reset anything.
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.COMBAT_START));

		potterFor(h, 500);       // five minutes afterwards
		assertTrue("after real activity the follower should perk back up: had "
			+ wound + ", now " + said(h, "chatter"),
			said(h, "chatter") >= wound + 2);
	}

	@Test
	public void potteringItselfNeverResetsTheStretch() throws IOException
	{
		// The design error the first draft shipped: moving resets the idle
		// counter, pottering is nothing but little moves, so a movement-based
		// reset makes the trail-off a no-op in exactly the session that
		// motivated it. Half an hour of pottering must stay wound down.
		Harness h = harness();
		potterFor(h, 1500);
		int atFifteen = said(h, "chatter");
		potterFor(h, 1500);

		assertTrue("the second quarter hour should be much quieter than the"
			+ " first: " + atFifteen + " then " + said(h, "chatter"),
			said(h, "chatter") - atFifteen <= atFifteen / 2);
	}

	@Test
	public void reactionsAreNeverPartOfTheChatter() throws IOException
	{
		// Deep in the wind-down, the world still gets answered. An examine is
		// an event; responding to it is not talking for the sake of it.
		Harness h = harness();
		potterFor(h, 3000);

		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertEquals("a reaction must cut straight through the trail-off",
			1, said(h, "reaction"));
	}

	@Test
	public void anIdleConditionedOccasionIsExempt() throws IOException
	{
		// The anniversary fires off the idle condition - it waits for a calm
		// moment on the right day. Winding it down because the player went AFK
		// on their anniversary would be the settling-damper bug in new clothes.
		Harness h = harness();
		potterFor(h, 3000);

		assertEquals("an occasion says its piece whatever the stretch looks like",
			1, said(h, "big-day"));
	}

	@Test
	public void theHarnessDefaultOfNoGapDisablesTheTrailOff() throws IOException
	{
		// Every window in the trail-off is a multiple of the base gap, so the
		// unpaced harness the rest of the suite runs under is untouched - the
		// same design as the director, for the same reason.
		Harness h = new Harness(folder.newFolder().toPath(), RULES);
		h.engine.setClock(clock::get);        // paced clock, no gap
		potterFor(h, 3000);

		assertTrue("with no gap set, the rule's own cooldown is the only pacing",
			said(h, "chatter") >= 20);
	}

	@Test
	public void theTranscriptSeesTheWindDown() throws IOException
	{
		Harness h = harness();
		List<String> reasons = new ArrayList<>();
		h.engine.setOnSuppressed((rule, why) -> reasons.add(why));

		potterFor(h, 3000);
		assertFalse("a wound-down stretch must be visible in the transcript,"
			+ " or a quiet follower and a throttled one look identical",
			reasons.stream().noneMatch("trailing"::equals));
	}
}

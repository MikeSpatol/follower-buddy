package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The states that OPEN something must not open on a line nobody saw.
 *
 * <p>The plugin queues lines behind the overhead box, and a queued line can
 * still be dropped: aged out as stale, displaced by a full queue, or cleared
 * with the scene. The engine used to latch the question, the want and the
 * wish the moment it handed the line over, so a dropped ask left its state
 * open anyway - which is how a gift option turned up in the Talk-to box for
 * a soft clay nobody ever heard wished for. The sink now owns the moment of
 * saying, and these three latch only when it reports the line landed. So does
 * everything else a line leaves behind: a first spent, a souvenir, a bet, a
 * dare, advice and a granted wish.
 */
public class SwallowedLineTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final String RULES = "{\"version\": 1, \"rules\": ["
		+ "{\"id\": \"wisher\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"wish\": {\"what\": \"soft clay\", \"minutes\": 5, \"items\": [1761]},"
		+ " \"when\": {\"type\": \"login\"}, \"say\": [\"some soft clay, if you find any\"]},"
		+ "{\"id\": \"asker\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"asks\": \"want-outing\","
		+ " \"when\": {\"type\": \"levelUp\"}, \"say\": [\"talk to me a minute?\"]},"
		+ "{\"id\": \"wanter\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"want\": {\"region\": 10553, \"label\": \"the guild\", \"minutes\": 5},"
		+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"can we go?\"]}]}";

	/**
	 * One of each line that leaves something behind once said: a first, a
	 * souvenir, a bet, a challenge, a piece of advice and a thank-you.
	 */
	private static final String LATCHES = "{\"version\": 1, \"rules\": ["
		+ "{\"id\": \"first\", \"group\": \"t\", \"cooldownMs\": 0, \"once\": true,"
		+ " \"when\": {\"type\": \"examined\"}, \"say\": [\"we have not met\"]},"
		+ "{\"id\": \"keeper\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"pickUp\": {\"what\": \"a flat rock\", \"minutes\": 20},"
		+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"I'm keeping this rock\"]},"
		+ "{\"id\": \"better\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"bet\": {\"rich\": true, \"threshold\": 1000, \"minutes\": 5},"
		+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"a thousand says the next one's good\"]},"
		+ "{\"id\": \"darer\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"challenge\": {\"about\": \"ten kills\", \"tally\": \"kills\", \"target\": 10, \"minutes\": 5},"
		+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"ten kills in five minutes?\"]},"
		+ "{\"id\": \"adviser\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"advise\": {\"about\": \"food\", \"ids\": [829], \"minutes\": 1},"
		+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"eat something\"]},"
		+ "{\"id\": \"thanker\", \"group\": \"t\", \"cooldownMs\": 0, \"grantsWish\": true,"
		+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"thank you for the clay\"]}]}";

	private static final String[] LATCHING = {"first", "keeper", "better", "darer", "adviser", "thanker"};

	/**
	 * Advice and a rock, and the rules that read them: a dare that will not
	 * talk over advice still outstanding, a gift pocketed only while the
	 * hands are empty, and a word about the rock once it is carried.
	 */
	private static final String GUARDS = "{\"version\": 1, \"rules\": ["
		+ "{\"id\": \"adviser\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"advise\": {\"about\": \"food\", \"ids\": [829], \"minutes\": 1},"
		+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"eat something\"]},"
		+ "{\"id\": \"keeper\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"pickUp\": {\"what\": \"a flat rock\", \"minutes\": 20},"
		+ " \"when\": {\"type\": \"login\"}, \"say\": [\"I'm keeping this rock\"]},"
		+ "{\"id\": \"darer\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"challenge\": {\"about\": \"ten kills\", \"tally\": \"kills\", \"target\": 10, \"minutes\": 5},"
		+ " \"when\": {\"type\": \"all\", \"conditions\": [{\"type\": \"examined\"},"
		+ " {\"type\": \"none\", \"conditions\": [{\"type\": \"advising\"}]}]},"
		+ " \"say\": [\"ten kills in five minutes?\"]},"
		+ "{\"id\": \"pocketer\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"pickUp\": {\"what\": \"the clay you found me\", \"minutes\": 20},"
		+ " \"when\": {\"type\": \"all\", \"conditions\": [{\"type\": \"underfoot\"},"
		+ " {\"type\": \"none\", \"conditions\": [{\"type\": \"carrying\"}]}]},"
		+ " \"say\": [\"the clay goes in the good pocket\"]},"
		+ "{\"id\": \"mentioner\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"when\": {\"type\": \"all\", \"conditions\": [{\"type\": \"notesEdited\"},"
		+ " {\"type\": \"carrying\"}]}, \"say\": [\"still got the rock\"]}]}";

	@Test
	public void aDroppedLineSpendsNothing() throws IOException
	{
		// The queue and the dialogue hush both drop lines the engine already
		// handed over. A first nobody heard is still owed, a souvenir nobody
		// was told about is not carried, and a bet, a dare or advice nobody
		// heard is not open to be settled. A thank-you nobody heard leaves the
		// wish open, so the gift can be given again and thanked for.
		Harness h = new Harness(folder.newFolder().toPath(), LATCHES);
		h.gameTicks(1);
		h.engine.getContext().setWish("soft clay", 5);

		h.engine.setSink((text, output, rule, animationId, onSaid) -> { });
		for (String id : LATCHING)
		{
			h.engine.force(id);
		}

		assertFalse("a first nobody heard is not spent",
			h.engine.getContext().hasSaidOnce("first"));
		assertFalse("nor is a rock picked up", h.engine.getContext().isCarrying());
		assertFalse("nor a bet placed", h.engine.getContext().isBetting());
		assertFalse("nor a dare set", h.engine.getContext().isChallenging());
		assertFalse("nor advice given", h.engine.getContext().isAdvising());
		assertTrue("nor the wish granted", h.engine.getContext().isWishing());
		assertFalse("nor a favour owed", h.engine.getContext().isGrateful());

		// Still owed, so the next time it rises it is said, and spent then.
		h.engine.setSink((text, output, rule, animationId, onSaid) -> onSaid.run());
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertTrue(h.engine.getContext().hasSaidOnce("first"));
	}

	@Test
	public void aQueuedLineSpendsItsLatchesWhenItLands() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), LATCHES);
		h.gameTicks(1);
		h.engine.getContext().setWish("soft clay", 5);

		List<Runnable> held = new ArrayList<>();
		h.engine.setSink((text, output, rule, animationId, onSaid) -> held.add(onSaid));
		for (String id : LATCHING)
		{
			h.engine.force(id);
		}
		assertFalse("waiting its turn is not yet said",
			h.engine.getContext().hasSaidOnce("first"));
		assertFalse(h.engine.getContext().isCarrying());
		assertFalse(h.engine.getContext().isBetting());
		assertTrue(h.engine.getContext().isWishing());

		held.forEach(Runnable::run);
		assertTrue("the moment it lands, the first is spent",
			h.engine.getContext().hasSaidOnce("first"));
		assertEquals("the rock is carried", "a flat rock", h.engine.getContext().getSouvenir());
		assertTrue("the bet is on", h.engine.getContext().isBetting());
		assertTrue("the dare is set", h.engine.getContext().isChallenging());
		assertTrue("the advice is given", h.engine.getContext().isAdvising());
		assertFalse("and the wish is granted", h.engine.getContext().isWishing());
		assertTrue(h.engine.getContext().isGrateful());
	}

	@Test
	public void aDroppedLineOpensNothing() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), RULES);
		h.gameTicks(1);

		// A sink that swallows everything, the way a cleared or overfull
		// queue does: the line is accepted and never said. Each opener is
		// FORCED, because back-to-back dispatches pace each other out and a
		// rule that never fired proves nothing about its latch - the first
		// version of this test only ever fired the wisher, and a mutation
		// latching the question at hand-off sailed through the other two.
		h.engine.setSink((text, output, rule, animationId, onSaid) -> { });
		h.engine.force("wisher");
		h.engine.force("asker");
		h.engine.force("wanter");

		assertFalse("a wish nobody heard must not open",
			h.engine.getContext().isWishing());
		assertEquals("nor a question", "", h.engine.getContext().getAskedTree());
		assertFalse("nor a want", h.engine.getContext().isWanting());
	}

	@Test
	public void aDroppedLineWearsNothingOut() throws IOException
	{
		// The wear ledger (R17) counts what was heard. A line the queue
		// swallowed has not worn out its welcome - and if drops counted,
		// a noisy scene could retire lines the player never got to read.
		Harness h = new Harness(folder.newFolder().toPath(), RULES);
		h.gameTicks(1);

		// force() rather than a second LOGIN: the director's pacing would
		// otherwise hold the second firing, and this test is about the sink.
		h.engine.setSink((text, output, rule, animationId, onSaid) -> { });
		h.engine.force("wisher");
		assertEquals("swallowed, so unworn", 0,
			h.engine.getContext().lineWear("some soft clay, if you find any"));

		h.engine.setSink((text, output, rule, animationId, onSaid) -> onSaid.run());
		h.engine.force("wisher");
		assertEquals("said, so worn by one", 1,
			h.engine.getContext().lineWear("some soft clay, if you find any"));
	}

	@Test
	public void aQueuedLineOpensWhenItFinallyLands() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), RULES);
		h.gameTicks(1);

		// A sink that holds its lines the way the plugin's queue does, and
		// says them later. All three openers, for the same reason as above.
		List<Runnable> held = new ArrayList<>();
		h.engine.setSink((text, output, rule, animationId, onSaid) -> held.add(onSaid));
		h.engine.force("wisher");
		h.engine.force("asker");
		h.engine.force("wanter");

		assertFalse("waiting its turn behind the overhead box is not yet said",
			h.engine.getContext().isWishing());
		assertEquals("", h.engine.getContext().getAskedTree());
		assertFalse(h.engine.getContext().isWanting());

		held.forEach(Runnable::run);
		assertTrue("the moment it lands, the wish is open",
			h.engine.getContext().isWishing());
		assertEquals("soft clay", h.engine.getContext().getWishLabel());
		assertEquals("and the question", "want-outing",
			h.engine.getContext().getAskedTree());
		assertTrue("and the want", h.engine.getContext().isWanting());
	}

	@Test
	public void aGuardWaitsForTheLineItGuardsAgainst() throws IOException
	{
		// The advice and the rock latch when their lines land, so while they
		// wait in the queue a guard reading them sees nothing yet. Judged then,
		// the dare opened over the advice and the gift went into hands the
		// rock was about to fill, and both played back to back.
		long[] now = {1_000_000L};
		Harness h = new Harness(folder.newFolder().toPath(), GUARDS);
		h.engine.setClock(() -> now[0]);
		h.gameTicks(1);

		List<String> handedOver = new ArrayList<>();
		List<Runnable> held = new ArrayList<>();
		h.engine.setSink((text, output, rule, animationId, onSaid) ->
		{
			handedOver.add(rule.id);
			held.add(onSaid);
		});
		h.engine.force("adviser");
		h.engine.force("keeper");

		now[0] += 5_000L;
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.UNDERFOOT));
		assertEquals("nothing opens over a line still on its way",
			java.util.Arrays.asList("adviser", "keeper"), handedOver);

		held.forEach(Runnable::run);
		assertTrue("landed, the advice answers the guard itself",
			h.engine.getContext().isAdvising());
		assertEquals("a flat rock", h.engine.getContext().getSouvenir());

		// And a landed line is waited on no longer: the rock can be talked about.
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.NOTES_EDITED));
		assertEquals("mentioner", handedOver.get(handedOver.size() - 1));
	}

	@Test
	public void aDroppedLineIsNotWaitedOnOnceItCanNoLongerLand() throws IOException
	{
		// A line the queue dropped never lands. Once it is older than any line
		// the queue will still say, the guard stops waiting and reads the
		// state as it is.
		long[] now = {1_000_000L};
		Harness h = new Harness(folder.newFolder().toPath(), GUARDS);
		h.engine.setClock(() -> now[0]);
		h.gameTicks(1);

		List<String> handedOver = new ArrayList<>();
		h.engine.setSink((text, output, rule, animationId, onSaid) -> handedOver.add(rule.id));
		h.engine.force("adviser");

		now[0] += 5_000L;
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertFalse("still able to land, so still waited on", handedOver.contains("darer"));

		now[0] += 20_000L;
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertTrue("past landing, the dare is free to open", handedOver.contains("darer"));
		assertFalse("and the advice nobody heard is not outstanding",
			h.engine.getContext().isAdvising());
	}
}

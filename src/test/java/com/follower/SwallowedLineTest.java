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
 * saying, and these three latch only when it reports the line landed.
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
}

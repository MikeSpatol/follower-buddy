package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.RuleLoader;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Lines the follower says once, ever.
 *
 * <p>A line that only has to hold up on a single hearing can be specific in a
 * way no repeating line can afford to be: it can name the moment, admit
 * something, or land a joke that would be unbearable the fourth time. That
 * makes it the highest value per word in the file - and the easiest to ruin, by
 * saying it twice.
 */
public class OnceOnlyTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final String RULES =
		"{\"version\": 1, \"rules\": ["
			+ "{\"id\": \"hello-first\", \"group\": \"t\", \"cooldownMs\": 0, \"once\": true,"
			+ " \"when\": {\"type\": \"examined\"}, \"say\": [\"we have not met\"]},"
			+ "{\"id\": \"hello-again\", \"group\": \"t\", \"cooldownMs\": 0, \"priority\": 10,"
			+ " \"when\": {\"type\": \"examined\"}, \"say\": [\"back again\"]}]}";

	private Harness harness() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), RULES);
		h.gameTicks(1);
		return h;
	}

	/** The trigger, twice, with a tick between so the edge can fall. */
	private static void examineTwice(Harness h)
	{
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
	}

	@Test
	public void aFirstIsOnlyEverAFirst() throws IOException
	{
		Harness h = harness();
		examineTwice(h);

		assertEquals("said once", 1, h.firedBy("hello-first").size());
		assertEquals("and the lower-priority everyday line takes over after",
			1, h.firedBy("hello-again").size());
	}

	@Test
	public void whatWasSaidIsRememberedByTheFollowerNotTheRule() throws IOException
	{
		// The rule file reloads whenever it changes on disk, throwing every rule
		// object away. A flag on the object would un-say the introduction every
		// time a phrase was edited, which is the single most likely moment for
		// it to happen.
		Harness h = harness();
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertEquals(1, h.firedBy("hello-first").size());

		Path file = h.getScratch().resolve(RuleLoader.FILE_NAME);
		Files.write(file, RULES.replace("we have not met", "we have not met, you and I")
			.getBytes(StandardCharsets.UTF_8));
		// The loader compares the modified time, which has a second's resolution
		// on some filesystems.
		Files.setLastModifiedTime(file,
			java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000L));
		assertTrue("the edited file should have been picked up", h.loader.reloadIfChanged());

		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertEquals("editing the file must not un-say it",
			1, h.firedBy("hello-first").size());
	}

	@Test
	public void aLineNobodyHeardIsNotSpent() throws IOException
	{
		// The worst possible place to be strict. Muted, the follower said
		// nothing; burning the one chance it had would lose the line for good
		// on behalf of a player who never got it.
		Harness h = harness();
		h.engine.setMuted(true);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertTrue(h.firedBy("hello-first").isEmpty());
		assertFalse(h.engine.getContext().hasSaidOnce("hello-first"));

		h.engine.setMuted(false);
		h.gameTicks(1);
		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertEquals("still owed, and still paid", 1, h.firedBy("hello-first").size());
	}

	@Test
	public void sayingItIsWorthWritingDown() throws IOException
	{
		// Nothing is saved unless something says it needs saving, so a first
		// that did not mark the blob dirty would be forgotten by the next
		// restart - and said a second time.
		Harness h = harness();
		h.engine.getContext().clearCountersDirty();

		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertTrue("a spent first has to reach the disk",
			h.engine.getContext().isCountersDirty());
		assertTrue(h.engine.getContext().getSpokenOnce().contains("hello-first"));
	}

	@Test
	public void whatWasSaidLastTimeStaysSaid() throws IOException
	{
		Harness h = harness();
		h.engine.getContext().restoreSpokenOnce(
			java.util.Collections.singletonList("hello-first"));

		h.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
		assertTrue("a restored session must not repeat the introduction",
			h.firedBy("hello-first").isEmpty());
		assertEquals("and the everyday line is there to take its place",
			1, h.firedBy("hello-again").size());
	}
}

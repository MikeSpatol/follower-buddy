package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.runelite.api.NPC;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A memory said over the follower's head has to fit there. QA, 15 Oct: the
 * recall lines, once filled with the incident and its where and when, ran to
 * 92-151 characters, past the 80 any overhead in the game can carry, and in
 * fixed mode the viewport clipped both ends of every one of them. Measured
 * here as they are said: filed by the shipped rules, filled by the engine.
 */
public class MemoryLineLengthTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final long THREE_DAYS_MS = 3L * 24 * 60 * 60_000L;

	/**
	 * Over the limit with an everyday memory, and waiting for new wording,
	 * which is the player's to approve: see high-fixes-text.json. Kept
	 * exact, so a line that is reworded comes off this list, and a line that
	 * grows past the limit cannot hide behind it.
	 */
	private static final List<String> AWAITING_WORDING = java.util.Arrays.asList(
		"recall-here: It was right about here, {memoryWhen}. The page on {memory} is still creased.",
		"recall-here: Stand still a moment. The notes on {memory} start here.",
		"recall-here: I know this spot. This is where I wrote down {memory}.",
		"recall-idle: It's quiet, so naturally my mind goes back to {memory}.");

	@Test
	public void everyRecallLineFitsOverheadWithAnEverydayMemory() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		Map<Integer, String> names = new HashMap<>();
		names.put(12342, "Edgeville");
		h.engine.getContext().setRegionNames(names);
		h.game.regions(12342).at(3090, 3490, 0).hitpoints(6, 99);
		NPC giant = h.game.spawnNpc(2098, "Hill Giant", 28);
		h.game.fighting(giant);
		h.gameTicks(3);
		TriggerContext context = h.engine.getContext();
		assertEquals("the close call was filed by the shipped rule", "close-call", context.getIncidentKey());
		String closeCall = context.getIncidentPhrase();

		// Each kind of memory, as its rule files it in an ordinary session.
		String[][] memories = {
			{"close-call", closeCall},
			{"the-death", "the way that went with the Hill Giant"},
			{"big-drop", "the Abyssal whip that dropped"},
			{"the-boss", "the General Graardor going down"},
		};

		TreeSet<String> over = new TreeSet<>();
		List<String> measured = new ArrayList<>();
		for (String[] memory : memories)
		{
			context.restoreIncident(memory[0], memory[1], 1, 12342,
				System.currentTimeMillis() - THREE_DAYS_MS, "Hill Giant");
			for (SpeechRule rule : h.loader.getRules())
			{
				if (rule.say == null)
				{
					continue;
				}
				for (String template : rule.say)
				{
					if (!template.contains("{memory"))
					{
						continue;
					}
					String said = h.engine.spokenText(template, TriggerEvent.tick());
					measured.add(said);
					assertTrue("no placeholder left: " + said, !said.contains("{"));
					if (said.length() > SpeechEngine.OVERHEAD_MAX_CHARS)
					{
						over.add(rule.id + ": " + template);
					}
				}
			}
		}
		assertTrue("the recall lines were measured", measured.size() > 40);
		assertEquals("recall lines over " + SpeechEngine.OVERHEAD_MAX_CHARS + " characters",
			new TreeSet<>(AWAITING_WORDING), over);
	}

	@Test
	public void aLongLineDropsTheWhereAndWhenAndKeepsTheMemory() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		Map<Integer, String> names = new HashMap<>();
		names.put(12342, "Edgeville");
		h.engine.getContext().setRegionNames(names);
		h.gameTicks(1);
		h.engine.getContext().restoreIncident("big-drop", "that drop", 1, 12342,
			System.currentTimeMillis() - THREE_DAYS_MS, "");
		assertEquals("a line that fits keeps them", "I keep thinking about that drop in Edgeville.",
			h.engine.spokenText("I keep thinking about {memory}{memoryWhere}.", TriggerEvent.tick()));

		h.engine.getContext().restoreIncident("the-death",
			"the way that went with the Thermonuclear smoke devil", 1, 12342,
			System.currentTimeMillis() - THREE_DAYS_MS, "Thermonuclear smoke devil");
		String said = h.engine.spokenText(
			"Before you start. Remember {memory}? That was {memoryWhen}.", TriggerEvent.tick());
		assertEquals("Before you start. Remember the way that went with the Thermonuclear smoke devil?", said);
		assertTrue(said.length() <= SpeechEngine.OVERHEAD_MAX_CHARS);
	}

	@Test
	public void theParticularsComeOutWithoutLeavingAHole()
	{
		assertEquals("I keep thinking about {memory}.",
			SpeechEngine.withoutMemoryParticulars("I keep thinking about {memory}{memoryWhere}."));
		assertEquals("Do you ever think about {memory}? I do.",
			SpeechEngine.withoutMemoryParticulars("Do you ever think about {memory}? I do. It was only {memoryWhen}."));
		assertEquals("A {memoryWho}. I remember {memory}.",
			SpeechEngine.withoutMemoryParticulars("A {memoryWho}. I remember {memory}, {memoryWhen}."));
		assertEquals("It was right about here. The page on {memory} is still creased.",
			SpeechEngine.withoutMemoryParticulars(
				"It was right about here, {memoryWhen}. The page on {memory} is still creased."));
		assertNull("nothing to take out", SpeechEngine.withoutMemoryParticulars("Careful. I've not forgotten {memory}."));
		assertNull("not at the cost of the memory itself",
			SpeechEngine.withoutMemoryParticulars("Back on {memoryWhen} I wrote {memory} down."));
	}
}

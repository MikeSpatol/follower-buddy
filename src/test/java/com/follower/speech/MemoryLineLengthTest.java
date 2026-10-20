package com.follower.speech;

import com.follower.sim.Harness;
import com.follower.ui.GameFont;
import com.follower.ui.GameFontRepository;
import com.google.gson.Gson;
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
 * here as they are said: filed by the shipped rules, filled by the engine,
 * in the font the overhead is drawn in.
 */
public class MemoryLineLengthTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final long DAY_MS = 24L * 60 * 60_000L;
	private static final long THREE_DAYS_MS = 3 * DAY_MS;

	/**
	 * Over the limit with an everyday memory, and waiting for new wording,
	 * which is the player's to approve: see memories/text.json. Kept
	 * exact, so a line that is reworded comes off this list, and a line that
	 * grows past the limit cannot hide behind it.
	 */
	private static final List<String> AWAITING_WORDING = java.util.Collections.emptyList();

	/**
	 * Over the limit with the longest particulars, and waiting for the new
	 * memory wording (memories/text.json), whose shorter forms are what a
	 * line falls back to. Kept exact, as above.
	 */
	private static final List<String> AWAITING_LONGEST = List.of(
		// Text QA5 #66: a boss is "the" one, two characters longer than "a".
		// Said, a line over the limit gives way to the rule's next that fits.
		"recall-foe: That's a {memoryWho}. Remember {memory}?");

	private GameFont overheadFont() throws IOException
	{
		GameFontRepository fonts = new GameFontRepository(new Gson());
		fonts.load(folder.newFolder().toPath());
		return fonts.getByName("b12_full");
	}

	private static boolean fits(String said, GameFont font)
	{
		return said.length() <= SpeechEngine.OVERHEAD_MAX_CHARS
			&& font.stringWidth(said) <= SpeechEngine.OVERHEAD_MAX_PX;
	}

	@Test
	public void everyRecallLineFitsOverheadWithAnEverydayMemory() throws IOException
	{
		GameFont font = overheadFont();
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.setOverheadWidth(font::stringWidth);
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
			{"the-death", "your death to the Hill Giant"},
			{"big-drop", "the Abyssal whip from the abyssal demon"},
			{"the-boss", "General Graardor going down"},
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
					if (!fits(said, font))
					{
						over.add(rule.id + ": " + template);
					}
				}
			}
		}
		assertTrue("the recall lines were measured", measured.size() > 40);
		assertEquals("recall lines over " + SpeechEngine.OVERHEAD_MAX_CHARS + " characters or "
			+ SpeechEngine.OVERHEAD_MAX_PX + " px", new TreeSet<>(AWAITING_WORDING), over);
	}

	/**
	 * The same with the longest of everything: the longest foe and item
	 * {@link OverheadLineLengthTest} knows, the widest place name the
	 * Locations rules give, borrowed from next door, and the widest "when".
	 * A line that cannot carry all of it carries less, but has to fit.
	 */
	@Test
	public void everyRecallLineFitsOverheadWithTheLongestParticulars() throws IOException
	{
		GameFont font = overheadFont();
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.setOverheadWidth(font::stringWidth);
		h.gameTicks(1);
		TriggerContext context = h.engine.getContext();

		String widestPlace = "";
		for (String name : h.loader.getRegionNames().values())
		{
			String place = TriggerContext.shortPlace(name.split("[:,(]| - ")[0]);
			if (font.stringWidth(place) > font.stringWidth(widestPlace))
			{
				widestPlace = place;
			}
		}
		long now = System.currentTimeMillis();
		long widestWhen = now;
		int widestWhenPx = -1;
		for (int days = 0; days < 20; days++)
		{
			context.restoreIncident("x", "y", 1, 0, now - days * DAY_MS, "");
			int px = font.stringWidth(context.getMemoryAgo(now));
			if (px > widestWhenPx)
			{
				widestWhenPx = px;
				widestWhen = now - days * DAY_MS;
			}
		}

		TreeSet<String> over = new TreeSet<>();
		int measured = 0;
		for (SpeechRule filer : h.loader.getRules())
		{
			if (filer.remember == null || filer.remember.key == null)
			{
				continue;
			}
			String forms = String.join(" ", filer.remember.forms());
			String who = forms.contains("{foe}") || forms.contains("{npc}") ? "Thermonuclear smoke devil" : "";
			// Filed with an empty phrase, so it is said as its forms say it.
			context.restoreIncident(filer.remember.key, "", 1, 0, widestWhen, who,
				widestPlace, true, "Tumeken's shadow (uncharged)", 12);
			for (SpeechRule rule : h.loader.getRules())
			{
				if (rule.say == null || !bringsUp(rule, filer.remember.key, !who.isEmpty()))
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
					measured++;
					assertTrue("no placeholder left: " + said, !said.contains("{"));
					if (!fits(said, font))
					{
						over.add(rule.id + ": " + template);
					}
				}
			}
		}
		assertTrue("the recall lines were measured", measured > 100);
		assertEquals("recall lines over " + SpeechEngine.OVERHEAD_MAX_CHARS + " characters or "
				+ SpeechEngine.OVERHEAD_MAX_PX + " px with the longest particulars",
			new TreeSet<>(AWAITING_LONGEST), over);
	}

	/** Whether the rule can bring up a memory filed under this key: its remembers, and a foe to see. */
	private static boolean bringsUp(SpeechRule rule, String key, boolean hasWho)
	{
		if (rule.when == null || (!hasWho && rule.when.usesType("memoryFoeNearby")))
		{
			return false;
		}
		List<String> named = new ArrayList<>();
		namedMemories(rule.when, named);
		return named.isEmpty() || named.contains(key);
	}

	private static void namedMemories(Condition condition, List<String> into)
	{
		if ("remembers".equalsIgnoreCase(condition.type) && condition.is != null)
		{
			into.add(condition.is);
		}
		if (condition.conditions != null && !"none".equalsIgnoreCase(condition.type))
		{
			for (Condition inner : condition.conditions)
			{
				namedMemories(inner, into);
			}
		}
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
		assertEquals("a line that fits keeps them", "I keep thinking about your big drop in Edgeville.",
			h.engine.spokenText("I keep thinking about {memory}{memoryWhere}.", TriggerEvent.tick()));

		h.engine.getContext().restoreIncident("the-death",
			"the way that went with the Thermonuclear smoke devil", 1, 12342,
			System.currentTimeMillis() - THREE_DAYS_MS, "Thermonuclear smoke devil");
		String said = h.engine.spokenText(
			"Before you start. Remember {memory}? That was {memoryWhen}.", TriggerEvent.tick());
		assertEquals("Before you start. Remember the way that went with the Thermonuclear smoke devil?", said);
		assertTrue(said.length() <= SpeechEngine.OVERHEAD_MAX_CHARS);
	}

	private static final String FORMS = "{\"version\": 1, \"rules\": ["
		+ "{\"id\": \"memory-death\", \"group\": \"memory\", \"cooldownMs\": 0,"
		+ " \"remember\": {\"key\": \"the-death\", \"as\": \"your death to the {foe}\","
		+ "  \"or\": [\"your death {where}\"], \"plain\": \"the time you died\"},"
		+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"Noted.\"]}]}";

	@Test
	public void aLineTooWideSaysLessWhenThenWhereThenAShorterMemory() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(), FORMS);
		h.gameTicks(1);
		TriggerContext c = h.engine.getContext();
		String line = "I keep thinking about {memory}{memoryWhere}, {memoryAgo}.";
		c.restoreIncident("the-death", "your death to the Hill Giant", 1, 0,
			System.currentTimeMillis() - 2 * DAY_MS, "Hill Giant", "Edgeville", false, "", -1);
		assertEquals("all of it, while it fits",
			"I keep thinking about your death to the Hill Giant in Edgeville, two days ago.",
			h.engine.spokenText(line, TriggerEvent.tick()));

		// Wider than 480 px at seven a character: the when goes first.
		h.engine.setOverheadWidth(text -> text.length() * 7);
		assertEquals("I keep thinking about your death to the Hill Giant in Edgeville.",
			h.engine.spokenText(line, TriggerEvent.tick()));

		// Then the where; then the memory a shorter way, keeping what fits.
		c.restoreIncident("the-death", "your death to the Thermonuclear smoke devil", 1, 0,
			System.currentTimeMillis() - 2 * DAY_MS, "Thermonuclear smoke devil",
			"Tree Gnome Stronghold", true, "", -1);
		assertEquals("I keep thinking about your death to the Thermonuclear smoke devil.",
			h.engine.spokenText(line, TriggerEvent.tick()));
		h.engine.setOverheadWidth(text -> text.length() * 8);
		assertEquals("I keep thinking about your death near Tree Gnome Stronghold.",
			h.engine.spokenText(line, TriggerEvent.tick()));
		h.engine.setOverheadWidth(text -> text.length() * 9);
		assertEquals("only the kind of moment, when nothing more fits",
			"I keep thinking about the time you died.", h.engine.spokenText(line, TriggerEvent.tick()));
	}

	/**
	 * A line that names the creature itself says the memory without it.
	 * Play report, 17 Oct: "A Hill Giant. I remember your death to the Hill
	 * Giant" named it twice.
	 */
	@Test
	public void aLineThatNamesTheFoeSaysTheMemoryWithoutIt() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		Map<Integer, String> names = new HashMap<>();
		names.put(12342, "Edgeville");
		h.engine.getContext().setRegionNames(names);
		h.gameTicks(1);
		TriggerContext c = h.engine.getContext();
		long twoDays = System.currentTimeMillis() - 2 * DAY_MS;
		String line = "A {memoryWho}. I remember {memory}{memoryWhere}.";

		c.restoreIncident("the-death", "your death to the Hill Giant", 1, 12342, twoDays, "Hill Giant");
		assertEquals("A Hill Giant. I remember your death in Edgeville.",
			h.engine.spokenText(line, TriggerEvent.tick()));
		assertEquals("a line that does not name it keeps the foe",
			"I was rereading the entry on your death to the Hill Giant in Edgeville.",
			h.engine.spokenText("I was rereading the entry on {memory}{memoryWhere}.", TriggerEvent.tick()));

		c.restoreIncident("the-death", "your death to the Hill Giant", 1, 0, twoDays, "Hill Giant");
		assertEquals("with no place, the plain form", "A Hill Giant. I remember the time you died.",
			h.engine.spokenText(line, TriggerEvent.tick()));

		c.restoreIncident("big-drop", "the Abyssal whip from the abyssal demon", 1, 12342, twoDays,
			"Abyssal demon", null, false, "Abyssal whip", 40);
		assertEquals("An abyssal demon. I remember the Abyssal whip that dropped in Edgeville.",
			h.engine.spokenText(line, TriggerEvent.tick()));

		c.restoreIncident("the-boss", "General Graardor going down", 1, 12342, twoDays, "General Graardor");
		assertEquals("General Graardor. I remember your big kill in Edgeville.",
			h.engine.spokenText(line, TriggerEvent.tick()));
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
		assertEquals("the ago goes as the when does", "Before you start. Remember {memory}?",
			SpeechEngine.withoutMemoryParticulars("Before you start. Remember {memory}? That was {memoryAgo}."));
		assertEquals("the when alone, keeping the where", "I keep thinking about {memory}{memoryWhere}.",
			SpeechEngine.withoutMemoryWhen("I keep thinking about {memory}{memoryWhere}, {memoryAgo}."));
		assertNull("no when to take", SpeechEngine.withoutMemoryWhen("I keep thinking about {memory}{memoryWhere}."));
	}
}

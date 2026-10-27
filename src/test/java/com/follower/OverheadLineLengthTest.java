package com.follower;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Every line fits over the follower's head once it is filled in. QA, 15 Oct:
 * the only length check measured the template, so a line that read fine as
 * "{quest}, done and witnessed" ran to 86 characters with the longest quest
 * in it, and 70 lines were wider than the whole fixed-mode viewport. Measured
 * here as said - through the engine, with the longest value each placeholder
 * really takes - in the font the overlay draws with.
 *
 * <p>The memory lines are left to {@link MemoryLineLengthTest}, which files
 * the memory the way the rules do.
 */
public class OverheadLineLengthTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** Wider than this and the line is clipped in fixed mode unless the follower stands dead centre. */
	static final int OVERHEAD_MAX_PX = 480;

	/**
	 * The longest value each placeholder really takes: real NPC, item, quest
	 * and raid names, and the longest phrases the follower files itself.
	 */
	private static final Map<String, String> LONGEST = new LinkedHashMap<>();

	/** By rule: the longest a value can be where its trigger says what it is. */
	private static final Map<String, Map<String, String>> OVERRIDES = new LinkedHashMap<>();

	static
	{
		LONGEST.put("npc", "Thermonuclear smoke devil");
		LONGEST.put("foe", "Thermonuclear smoke devil");
		LONGEST.put("boss", "Chambers of Xeric Challenge Mode");
		LONGEST.put("item", "Tumeken's shadow (uncharged)");
		LONGEST.put("thing", "Tumeken's shadow (uncharged)");
		LONGEST.put("target", "Divine super combat potion(4)");
		LONGEST.put("value", "1.5B");
		LONGEST.put("skill", "Construction");
		LONGEST.put("level", "99");
		LONGEST.put("total", "2277");
		LONGEST.put("base", "99");
		LONGEST.put("nines", "twenty-three");
		LONGEST.put("ninesOrdinal", "twenty-third");
		LONGEST.put("sinceLast", "eleven months");
		LONGEST.put("count", "3000");
		LONGEST.put("ordinal", "seventy-seventh");
		LONGEST.put("hp", "12");
		LONGEST.put("maxHp", "99");
		LONGEST.put("hpPercent", "12");
		LONGEST.put("prayer", "9");
		LONGEST.put("damage", "84");
		LONGEST.put("days", "1095");
		LONGEST.put("world", "565");
		LONGEST.put("previous", "78");
		LONGEST.put("player", "MumbleWomble");
		LONGEST.put("name", "MumbleWomble");
		LONGEST.put("style", "ranged");
		LONGEST.put("from", "magic");
		LONGEST.put("want", "the Grand Exchange");
		LONGEST.put("wish", "ball of wool");
		LONGEST.put("souvenir", "the ball of wool you found me");
		LONGEST.put("here", "you hit harder than you ever had");
		LONGEST.put("nickname", "the local menace");
		LONGEST.put("candidate", "the local menace");
		LONGEST.put("lastTime", "finishing Desert Treasure II - The Fallen Empire");
		LONGEST.put("track", "Ballad of the Basilisk");
		LONGEST.put("quest", "Desert Treasure II - The Fallen Empire");
		LONGEST.put("page", "the Seven Priestly Warriors");
		LONGEST.put("challenge", "ten kills");

		// Where the trigger narrows what can arrive.
		OVERRIDES.put("hesitated-wilderness", Map.of("thing", "the Wilderness"));
		OVERRIDES.put("hesitated-monster", Map.of("thing", "Thermonuclear smoke devil"));
		OVERRIDES.put("level-quick", Map.of("sinceLast", "ten minutes"));
		OVERRIDES.put("kill-count-first", Map.of("count", "1", "ordinal", "first"));
		OVERRIDES.put("kill-count-round", Map.of("count", "2500", "ordinal", "seventy-fifth"));
		OVERRIDES.put("kill-count-round-long", Map.of("count", "2500", "ordinal", "seventy-fifth"));
		OVERRIDES.put("kill-count-thousand", Map.of("ordinal", "3000th"));
		OVERRIDES.put("gifted-swap", Map.of("souvenir", "the soft clay you found me"));
		OVERRIDES.put("noticed-impling", Map.of("thing", "Eclectic impling"));
		OVERRIDES.put("noticed-landmark", Map.of("thing", "Notice board"));
		OVERRIDES.put("chronicle-page-boss", Map.of("page", "the King Black Dragon"));
		OVERRIDES.put("chronicle-page-enemy", Map.of("page", "Witch's Experiment"));
		OVERRIDES.put("chronicle-page-place", Map.of("page", "the Wizards' Tower"));
	}

	/**
	 * Over the limit with the longest values, and waiting for new wording,
	 * which is the player's to approve: see overhead-text.json. Kept exact,
	 * so a line that is reworded comes off this list, and a line that grows
	 * past the limit cannot hide behind it.
	 */
	private static final List<String> AWAITING_WORDING = java.util.Collections.emptyList();

	private GameFont overheadFont() throws IOException
	{
		GameFontRepository fonts = new GameFontRepository(new Gson());
		fonts.load();
		return fonts.getByName("b12_full");
	}

	@Test
	public void everyLineFitsOverheadFilledWithTheLongestValues() throws IOException
	{
		GameFont font = overheadFont();
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);

		TreeSet<String> over = new TreeSet<>();
		int measured = 0;
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.say == null)
			{
				continue;
			}
			for (String template : rule.say)
			{
				if (template.contains("{memory"))
				{
					continue;
				}
				TriggerEvent event = TriggerEvent.simple(TriggerEvent.Type.MANUAL);
				event.getPlaceholders().putAll(LONGEST);
				event.getPlaceholders().putAll(OVERRIDES.getOrDefault(rule.id, Map.of()));
				String said = h.engine.spokenText(template, event);
				measured++;
				assertFalse("every placeholder has a longest value: " + said, said.contains("{"));
				if (said.length() > SpeechEngine.OVERHEAD_MAX_CHARS || font.stringWidth(said) > OVERHEAD_MAX_PX)
				{
					over.add(rule.id + ": " + template);
				}
			}
		}
		assertTrue("the lines were measured", measured > 2000);
		assertEquals("lines over " + SpeechEngine.OVERHEAD_MAX_CHARS + " characters or "
			+ OVERHEAD_MAX_PX + " px, filled", new TreeSet<>(AWAITING_WORDING), over);
	}
}

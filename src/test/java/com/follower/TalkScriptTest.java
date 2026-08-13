package com.follower;

import com.follower.speech.FollowerDialog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The everyday Talk-to conversation.
 *
 * <p>It is the only speech in the plugin that does not live in phrases.json,
 * and until now it was the only speech nothing checked. Every failure the rule
 * file is guarded against applies here too, and worse: a branch pointing at a
 * node that does not exist shuts the box in the player's face mid-sentence,
 * and a character the game has no glyph for renders as a different character
 * entirely rather than as nothing.
 */
public class TalkScriptTest
{
	private static Map<String, FollowerDialog.Node> script()
	{
		// The day-summary branch is built fresh each visit from figures only the
		// running plugin has. A stand-in keeps the structural checks honest
		// (the node is reachable, its pages are readable, it ends where it says
		// it does); the wording itself is walked branch by branch below.
		// Built WITH a wish open and the thing in the bag, so the gift branch
		// exists and every structural check walks it too; the other variants
		// are checked in their own tests below.
		return FollowerPlugin.talkScript(
			() -> FollowerPlugin.daySummary(95, 12, 2, 1, "good", "that chicken"),
			answer -> { }, "feather", true, "even");
	}

	/** Every node id something points at, via {@code then} or a choice. */
	private static Set<String> targets(Map<String, FollowerDialog.Node> script)
	{
		Set<String> found = new TreeSet<>();
		for (FollowerDialog.Node node : script.values())
		{
			found.addAll(node.getTargets());
		}
		return found;
	}

	@Test
	public void pickingAnOptionSaysExactlyThatOption()
	{
		// The option IS the sentence you are about to speak, not a summary of
		// it - that is how the game works and how players read it. An option
		// whose node says something slightly different reads as a bug, because
		// it is one; and one that jumps straight to a menu without saying
		// anything reads as the click having gone astray.
		Map<String, FollowerDialog.Node> script = script();
		List<String> problems = new ArrayList<>();

		for (Map.Entry<String, FollowerDialog.Node> entry : script.entrySet())
		{
			FollowerDialog.Node node = entry.getValue();
			List<String> labels = node.getOptionLabels();
			List<String> targets = node.getOptionTargets();

			for (int i = 0; i < labels.size(); i++)
			{
				String label = labels.get(i);
				FollowerDialog.Node landed = script.get(targets.get(i));
				if (landed == null)
				{
					continue;
				}
				if (!landed.isPlayerSpeaking() || landed.getPages().isEmpty())
				{
					problems.add(entry.getKey() + ": \"" + label
						+ "\" is picked but never said");
					continue;
				}
				String spoken = landed.getPages().get(0);
				if (!label.equals(spoken))
				{
					problems.add(entry.getKey() + ": picked \"" + label
						+ "\" but said \"" + spoken + "\"");
				}
			}
		}
		assertTrue("options that do not match what the player says:\n  "
			+ String.join("\n  ", problems), problems.isEmpty());
	}

	@Test
	public void theGiftOptionOnlyExistsWhileAWishDoes()
	{
		// The first gift design offered "Found you something." at all times,
		// and it failed in play precisely because there was no something and
		// no wanting. The option now appears with the wish, names the thing,
		// and disappears with it.
		Map<String, FollowerDialog.Node> wishing = script();
		assertTrue("with a wish open the gift branch exists",
			wishing.containsKey("gift-q"));
		assertTrue("and the option names the thing",
			wishing.get("start").getOptionLabels().contains("Found you that feather."));

		Map<String, FollowerDialog.Node> wishless = FollowerPlugin.talkScript(
			() -> FollowerPlugin.daySummary(95, 12, 2, 1, "good", "that chicken"),
			answer -> { }, "", false, "even");
		assertTrue("with no wish there is no gift branch at all",
			!wishless.containsKey("gift-q"));
		for (FollowerDialog.Node node : wishless.values())
		{
			for (String label : node.getOptionLabels())
			{
				assertTrue("a wishless script must not offer a gift: " + label,
					!label.startsWith("Found you"));
			}
		}
	}

	@Test
	public void theBoxItselfKnowsWhetherTheBagHoldsTheThing()
	{
		// The neutral version - "Let's see it, then." either way - read in
		// play as the follower not looking, and a scribe that does not look
		// is out of character in its own conversation. Same claim, different
		// reception, decided by the bag.
		java.util.List<String> reactions = new java.util.ArrayList<>();

		Map<String, FollowerDialog.Node> holding = FollowerPlugin.talkScript(
			() -> new String[]{""}, reactions::add, "feather", true, "even");
		holding.get("gift-a").runFinish();
		assertTrue("with the feather in the bag, the box accepts",
			holding.get("gift-a").getPages().get(0).contains("That's the one"));

		Map<String, FollowerDialog.Node> bluffing = FollowerPlugin.talkScript(
			() -> new String[]{""}, reactions::add, "feather", false, "even");
		bluffing.get("gift-a").runFinish();
		assertTrue("with an empty bag, the box catches the bluff",
			bluffing.get("gift-a").getPages().get(0).contains("empty bag"));

		assertTrue("and the two branches answer differently",
			reactions.contains("gift") && reactions.contains("bluff"));
	}

	@Test
	public void aLowDaySharpensTheQuestion()
	{
		// "How have you been?" becomes "You all right?" when the band is low
		// or down - same slot, same spirit, pointed by state - and asking is
		// itself the kindness: the answer node latches "comforted" on arrival.
		java.util.List<String> answers = new java.util.ArrayList<>();

		Map<String, FollowerDialog.Node> low = FollowerPlugin.talkScript(
			() -> new String[]{""}, answers::add, "", false, "low");
		assertTrue("the sharpened option leads somewhere real",
			low.containsKey("allright-q"));
		assertTrue("and the hub offers it",
			low.get("start").getOptionLabels().contains("You all right?"));
		assertTrue("while the everyday entrance rests",
			!low.get("start").getOptionLabels().contains("How have you been?"));
		low.get("allright-a").runFinish();
		assertTrue("asking is the kindness", answers.contains("comforted"));

		Map<String, FollowerDialog.Node> fine = FollowerPlugin.talkScript(
			() -> new String[]{""}, answers::add, "", false, "good");
		assertTrue("on a fine day the everyday question is back",
			fine.get("start").getOptionLabels().contains("How have you been?"));
		assertTrue("and nobody asks a cheerful follower if it is all right",
			!fine.containsKey("allright-q"));
	}

	@Test
	public void theCombatBranchExplainsTheThrallOutfits()
	{
		// Thrall mode is the one feature where getting it wrong looks broken
		// rather than deliberate: an unset outfit puts the follower in a mage
		// fight holding a greataxe. The dialogue is the only place a player is
		// told that the three styles want three outfits.
		String text = allText();
		assertTrue("nothing about standing in for a thrall", text.contains("thrall"));
		for (String style : new String[]{"zombie", "skeleton", "ghost",
			"melee", "ranged", "magic"})
		{
			assertTrue("the thrall talk never mentions " + style, text.contains(style));
		}
		assertTrue("never says to set an outfit for each", text.contains("outfit for each"));
	}

	/**
	 * Every structurally distinct build of the script: the two question
	 * shapes (lowish and everyday) crossed with the three wish states. The
	 * structural checks walk ALL of them, because a dead end that only
	 * exists in the low-band variant is exactly the kind nobody meets until
	 * a bad day - which is the worst possible day to have the box shut in
	 * your face. (The first version of these checks walked one variant; the
	 * third deep-testing pass caught the gap.)
	 */
	private static Map<String, Map<String, FollowerDialog.Node>> allVariants()
	{
		Map<String, Map<String, FollowerDialog.Node>> variants =
			new java.util.LinkedHashMap<>();
		for (String band : new String[]{"low", "even"})
		{
			variants.put(band + "/no-wish", FollowerPlugin.talkScript(
				() -> FollowerPlugin.daySummary(95, 12, 2, 1, "good", "that chicken"),
				answer -> { }, "", false, band));
			variants.put(band + "/wish-held", FollowerPlugin.talkScript(
				() -> FollowerPlugin.daySummary(95, 12, 2, 1, "good", "that chicken"),
				answer -> { }, "feather", true, band));
			variants.put(band + "/wish-empty", FollowerPlugin.talkScript(
				() -> FollowerPlugin.daySummary(95, 12, 2, 1, "good", "that chicken"),
				answer -> { }, "feather", false, band));
		}
		return variants;
	}

	@Test
	public void everyBranchLeadsToANodeThatExists()
	{
		for (Map.Entry<String, Map<String, FollowerDialog.Node>> variant
			: allVariants().entrySet())
		{
			Set<String> missing = new TreeSet<>(targets(variant.getValue()));
			missing.removeAll(variant.getValue().keySet());
			assertTrue(variant.getKey() + ": branches pointing at nodes that do"
				+ " not exist, which shut the box mid-conversation: " + missing,
				missing.isEmpty());
		}
	}

	@Test
	public void everyNodeCanBeReached()
	{
		// An unreachable node is dialogue nobody will ever read. Usually it
		// means a branch was repointed and its old destination left behind.
		for (Map.Entry<String, Map<String, FollowerDialog.Node>> variant
			: allVariants().entrySet())
		{
			Map<String, FollowerDialog.Node> script = variant.getValue();
			Set<String> reachable = new HashSet<>(targets(script));
			reachable.add("start");

			Set<String> orphans = new TreeSet<>(script.keySet());
			orphans.removeAll(reachable);
			assertTrue(variant.getKey() + ": nodes nothing leads to: " + orphans,
				orphans.isEmpty());
		}
	}

	@Test
	public void everyLineHasAGlyphInBothGameFonts() throws IOException
	{
		// Same check the rule file gets, and for the same reason: GameFont
		// indexes with charAt(i) & 0xFF, so anything above U+00FF is truncated
		// and drawn as whatever lives at that index. The line does not come out
		// blank, it comes out wrong, and nothing reports it.
		JsonObject fonts = bundled("/com/follower/fonts.json");
		JsonObject overhead = fontById(fonts, 496);
		JsonObject dialog = fontById(fonts, 497);

		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, Map<String, FollowerDialog.Node>> variant
			: allVariants().entrySet())
		{
			for (Map.Entry<String, FollowerDialog.Node> entry : variant.getValue().entrySet())
			{
				for (String line : entry.getValue().getLines())
				{
					for (char c : line.toCharArray())
					{
						if (c == ' ')
						{
							continue;
						}
						if (!hasGlyph(overhead, c) || !hasGlyph(dialog, c))
						{
							problems.add(String.format("%s %s: '%c' (U+%04X) in %s",
								variant.getKey(), entry.getKey(), c, (int) c, line));
						}
					}
				}
			}
		}
		assertTrue("lines the game cannot draw:\n  "
			+ String.join("\n  ", problems), problems.isEmpty());
	}

	@Test
	public void everyDaySummaryReadsLikeASentence()
	{
		// The one branch of the conversation whose wording is assembled at run
		// time rather than written out, so it is the one branch the checks
		// above cannot see. Sixteen combinations of what happened today, times
		// five moods, and each has to be a sentence the player can read.
		List<String> problems = new ArrayList<>();
		String[] moods = {"low", "down", "neutral", "good", "high"};
		int[][] figures = {
			{0, 0, 0, 0}, {1, 1, 1, 1}, {59, 0, 0, 0}, {60, 0, 0, 0},
			{95, 12, 2, 1}, {480, 999, 9, 40}, {0, 3, 0, 0}, {0, 0, 0, 2},
		};

		for (String mood : moods)
		{
			for (int[] f : figures)
			{
				for (String incident : new String[]{null, "that chicken"})
				{
					String[] pages = FollowerPlugin.daySummary(
						f[0], f[1], f[2], f[3], mood, incident);
					String where = mood + " " + java.util.Arrays.toString(f);

					if (pages.length == 0)
					{
						problems.add(where + ": said nothing at all");
						continue;
					}
					for (String page : pages)
					{
						if (page.trim().isEmpty())
						{
							problems.add(where + ": blank page");
						}
						else if (page.length() > 110)
						{
							problems.add(where + " (" + page.length() + "): " + page);
						}
						else if (!page.endsWith(".") && !page.endsWith("!")
							&& !page.endsWith("?"))
						{
							problems.add(where + ": unfinished - " + page);
						}
						// A plural agreement slip is the tell that the numbers
						// were pasted in rather than spoken.
						if (page.contains("1 things") || page.contains("1 levels")
							|| page.contains("1 deaths") || page.contains("1 minutes")
							|| page.contains("1 hours"))
						{
							problems.add(where + ": plural on one - " + page);
						}
					}
				}
			}
		}
		assertTrue("day summaries that do not read:\n  "
			+ String.join("\n  ", problems), problems.isEmpty());
	}

	@Test
	public void aQuietDaySaysSoRatherThanReportingZeroes()
	{
		// The reason the branch exists at all. A follower that answers "0
		// kills, 0 levels, 0 deaths" has read the tallies out; one that says
		// nothing happened has understood them.
		String[] pages = FollowerPlugin.daySummary(0, 0, 0, 0, "neutral", null);
		String first = pages[0];
		assertFalse("reported a zero: " + first, first.contains("0 "));
		assertFalse("reported a zero: " + first, first.startsWith("So far:"));
	}

	@Test
	public void nothingRunsOnTooLongToRead()
	{
		// The dialog body is a 380px column of three lines. A page much past
		// this is either cut off or crushes the whole conversation into a wall,
		// and a follower that monologues stops sounding like an NPC.
		List<String> tooLong = new ArrayList<>();
		for (Map.Entry<String, Map<String, FollowerDialog.Node>> variant
			: allVariants().entrySet())
		{
			for (Map.Entry<String, FollowerDialog.Node> entry : variant.getValue().entrySet())
			{
				for (String line : entry.getValue().getLines())
				{
					if (line.length() > 110)
					{
						tooLong.add(variant.getKey() + " " + entry.getKey()
							+ " (" + line.length() + "): " + line);
					}
				}
			}
		}
		assertTrue("pages too long for the box:\n  "
			+ String.join("\n  ", tooLong), tooLong.isEmpty());
	}

	@Test
	public void theConversationCanAlwaysBeEndedFromAnywhere()
	{
		// The property that actually matters is not that every menu has a
		// "back" on it - the joke loop escapes two hops away, through the
		// groan - but that no node strands the player. From anywhere, some
		// sequence of clicks has to reach an ending.
		for (Map.Entry<String, Map<String, FollowerDialog.Node>> variant
			: allVariants().entrySet())
		{
			Map<String, FollowerDialog.Node> script = variant.getValue();
			List<String> trapped = new ArrayList<>();
			for (String from : script.keySet())
			{
				if (!canEnd(script, from))
				{
					trapped.add(from);
				}
			}
			assertTrue(variant.getKey() + ": nodes the player cannot get out of: "
				+ trapped, trapped.isEmpty());
		}
	}

	/** Whether any path from here reaches a node that closes the box. */
	private static boolean canEnd(Map<String, FollowerDialog.Node> script, String from)
	{
		Set<String> seen = new HashSet<>();
		java.util.Deque<String> queue = new java.util.ArrayDeque<>();
		queue.add(from);
		while (!queue.isEmpty())
		{
			String id = queue.poll();
			if (!seen.add(id))
			{
				continue;
			}
			FollowerDialog.Node node = script.get(id);
			if (node == null)
			{
				continue;
			}
			List<String> next = node.getTargets();
			if (next.isEmpty())
			{
				// Nothing to continue to: the pages run out and the box shuts.
				return true;
			}
			queue.addAll(next);
		}
		return false;
	}

	@Test
	public void theFollowerTalksAboutWhatItCanActuallyDo()
	{
		// The script drifted a whole feature set behind once already: it still
		// described a follower that only walked, stayed and danced, long after
		// it had a memory, a mood, tastes and things it wanted. Nothing catches
		// that automatically - this at least catches it going backwards.
		String text = allText();

		// Matched on the player's own question rather than on the follower's
		// answer: the answer is characterisation and gets reworded, the
		// question is the capability and does not.
		assertTrue("nothing about counting or remembering", text.contains("keep track"));
		assertTrue("nothing about having moods", text.contains("mood"));
		assertTrue("nothing about places it likes", text.contains("places i like"));
		assertTrue("nothing about asking to be taken somewhere",
			text.contains("take me somewhere"));
		assertTrue("nothing about giving you room while thieving",
			text.contains("pockets"));
		assertFalse("the old Whitman line is not how an NPC talks",
			text.contains("contain multitudes"));
	}

	// ------------------------------------------------------------- plumbing

	/** Every line the script can put on screen, lowercased. */
	private static String allText()
	{
		StringBuilder all = new StringBuilder();
		for (FollowerDialog.Node node : script().values())
		{
			for (String line : node.getLines())
			{
				all.append(line).append(' ');
			}
		}
		return all.toString().toLowerCase(java.util.Locale.ROOT);
	}

	private static JsonObject bundled(String resource) throws IOException
	{
		try (InputStream in = TalkScriptTest.class.getResourceAsStream(resource))
		{
			assertTrue("missing bundled resource " + resource, in != null);
			return new JsonParser().parse(
				new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static JsonObject fontById(JsonObject fonts, int id)
	{
		for (com.google.gson.JsonElement element : fonts.getAsJsonArray("fonts"))
		{
			JsonObject font = element.getAsJsonObject();
			if (font.get("id").getAsInt() == id)
			{
				return font;
			}
		}
		throw new AssertionError("no font " + id + " in the bundled dump");
	}

	private static boolean hasGlyph(JsonObject font, char c)
	{
		// Anything past Latin-1 would be truncated to a different character.
		if (c > 0xFF)
		{
			return false;
		}
		// Exactly what GameFont.drawTop does to choose a glyph.
		JsonObject glyph = font.getAsJsonArray("glyphs").get(c & 0xFF).getAsJsonObject();
		com.google.gson.JsonElement mask = glyph.get("mask");
		return glyph.get("w").getAsInt() > 0
			&& mask != null && !mask.isJsonNull() && !mask.getAsString().isEmpty();
	}
}

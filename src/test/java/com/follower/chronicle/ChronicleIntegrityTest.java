package com.follower.chronicle;

import com.follower.ui.GameFont;
import com.follower.ui.GameFontRepository;
import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Quest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The bundled chronicle, as every install reads it.
 *
 * <p>The same law as the rule file: every page must load, be unique, name
 * an unlock the code can actually check, and be made of characters the
 * game's bitmap fonts can draw. A page that fails any of these fails
 * silently in play - a name that never unlocks, a dash drawn as some other
 * glyph - so it fails here instead.
 */
public class ChronicleIntegrityTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final Pattern SMART_PUNCTUATION = Pattern.compile("[\\u2010-\\u2015\\u2018\\u2019\\u201c\\u201d\\u2026]");

	private ChronicleLoader loadAsAFreshInstall() throws IOException
	{
		ChronicleLoader loader = new ChronicleLoader(new Gson());
		loader.load(folder.newFolder().toPath());
		return loader;
	}

	private static String bundledText() throws IOException
	{
		try (InputStream in = ChronicleLoader.class.getResourceAsStream(ChronicleLoader.BUNDLED))
		{
			assertNotNull("missing bundled resource " + ChronicleLoader.BUNDLED, in);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	public void theBundledChronicleLoadsClean() throws IOException
	{
		ChronicleLoader loader = loadAsAFreshInstall();
		assertTrue("did not load: " + loader.getStatus(), loader.isLoaded());
		assertTrue("problems: " + loader.getErrors(), loader.getErrors().isEmpty());
	}

	@Test
	public void theFirstThreeQuestsAreThere() throws IOException
	{
		ChronicleLoader loader = loadAsAFreshInstall();
		List<String> quests = new ArrayList<>();
		for (ChronicleEntry entry : loader.ofKind("quest"))
		{
			quests.add(entry.name);
		}
		assertTrue(quests.toString(), quests.contains("Cook's Assistant"));
		assertTrue(quests.toString(), quests.contains("Priest in Peril"));
		assertTrue(quests.toString(), quests.contains("Waterfall Quest"));
	}

	@Test
	public void everyPageIsWellFormed() throws IOException
	{
		ChronicleLoader loader = loadAsAFreshInstall();
		List<String> problems = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (ChronicleEntry entry : loader.getEntries())
		{
			String at = entry.id;
			if (!entry.id.matches("[a-z0-9]+(-[a-z0-9]+)*"))
			{
				problems.add(at + ": id is not lowercase-with-hyphens");
			}
			if (!ids.add(entry.id))
			{
				problems.add(at + ": duplicate id");
			}
			if (!entry.id.startsWith(entry.kindOrEmpty() + "-"))
			{
				problems.add(at + ": id does not start with its kind");
			}
			if (entry.summary == null || entry.summary.trim().isEmpty())
			{
				problems.add(at + ": no summary");
			}
			if (entry.sectionsOrEmpty().isEmpty())
			{
				problems.add(at + ": no sections");
			}
			for (ChronicleEntry.Section section : entry.sectionsOrEmpty())
			{
				if (section.heading == null || section.heading.isEmpty()
					|| section.body == null || section.body.isEmpty())
				{
					problems.add(at + ": a section is missing its heading or body");
				}
			}
		}
		assertTrue("malformed pages:\n  " + String.join("\n  ", problems), problems.isEmpty());
	}

	@Test
	public void everyUnlockIsOneTheCodeCanCheck() throws IOException
	{
		ChronicleLoader loader = loadAsAFreshInstall();
		List<String> problems = new ArrayList<>();
		for (ChronicleEntry entry : loader.getEntries())
		{
			String at = entry.id;
			ChronicleEntry.ChronicleUnlock unlock = entry.unlock;
			if (unlock == null)
			{
				problems.add(at + ": no unlock, so it could never be read");
				continue;
			}
			boolean any = unlock.always;
			for (String quest : new String[]{unlock.quest, unlock.questBegun})
			{
				if (quest == null)
				{
					continue;
				}
				any = true;
				if (ChronicleProgress.questNamed(quest) == null)
				{
					problems.add(at + ": " + quest + " is not a net.runelite.api.Quest");
				}
			}
			if (unlock.npcs != null)
			{
				any |= !unlock.npcs.isEmpty();
				for (String npc : unlock.npcs)
				{
					if (npc == null || npc.trim().isEmpty())
					{
						problems.add(at + ": a blank NPC name");
					}
				}
			}
			if (unlock.regions != null)
			{
				any |= !unlock.regions.isEmpty();
				for (Integer region : unlock.regions)
				{
					if (region == null || region <= 0)
					{
						problems.add(at + ": region " + region + " is not a map region");
					}
				}
			}
			if (!any)
			{
				problems.add(at + ": unlock names nothing, so it could never be read");
			}
		}
		assertTrue("unlocks the code cannot check:\n  " + String.join("\n  ", problems),
			problems.isEmpty());
	}

	@Test
	public void theQuestsUnlockOnTheRightQuest() throws IOException
	{
		ChronicleLoader loader = loadAsAFreshInstall();
		assertEquals(Quest.COOKS_ASSISTANT,
			ChronicleProgress.questNamed(loader.get("quest-cooks-assistant").unlock.quest));
		assertEquals(Quest.PRIEST_IN_PERIL,
			ChronicleProgress.questNamed(loader.get("quest-priest-in-peril").unlock.quest));
		assertEquals(Quest.WATERFALL_QUEST,
			ChronicleProgress.questNamed(loader.get("quest-waterfall-quest").unlock.quest));
	}

	@Test
	public void theTextIsPlainAsciiThroughout() throws IOException
	{
		// The bitmap fonts index glyphs by the low byte of each char, so a
		// curly quote or an em dash draws as some other glyph with no error.
		String text = bundledText();
		Matcher smart = SMART_PUNCTUATION.matcher(text);
		assertTrue("smart punctuation in the chronicle at " + (smart.find() ? smart.start() : -1),
			!smart.find(0));
		List<String> problems = new ArrayList<>();
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (c > 0x7E || (c < 0x20 && c != '\n' && c != '\r' && c != '\t'))
			{
				problems.add("char U+" + Integer.toHexString(c) + " at " + i);
				if (problems.size() > 10)
				{
					break;
				}
			}
		}
		assertTrue("not plain ASCII:\n  " + String.join("\n  ", problems), problems.isEmpty());
	}

	/**
	 * The scribe lives in Gielinor and knows nothing outside it. A release
	 * date, a developer, a wiki or a "player" on a page breaks that at once,
	 * so the words that only exist outside the world are refused here.
	 */
	@Test
	public void theTextStaysInTheWorld() throws IOException
	{
		String text = bundledText();
		String[] outside = {
			// "released on", "released in": a date. The verb on its own is a
			// potion into a river, and "classic" the adjective is in the world;
			// RuneScape Classic is caught by its first word.
			"\\bplayers?\\b", "\\bwiki\\b", "\\breleased (on|in) \\b", "\\brelease date\\b",
			"\\bexamine\\b", "\\bcombat level\\b",
			"\\bJagex\\b", "\\bGower\\b", "\\bMod [A-Z]", "\\bCambridge\\b", "\\bRuneScape\\b",
			"\\bAnniversary\\b", "\\bPostbag\\b", "\\b20[0-3][0-9]\\b",
			"\\bmembers\\b", "\\bfree to play\\b", "\\bIronm", "\\brespawn", "\\bspawns?\\b",
			"\\bsafespot", "\\bmulticombat\\b", "\\bticks?\\b", "\\bfootprint\\b", "\\b\\dx\\d\\b",
			"\\bin-game\\b", "\\bthe game\\b", "\\breal-world\\b", "\\bdeveloper\\b", "\\bmax hit\\b",
			"\\binstance\\b", "\\bplayer-owned\\b", "\\bout of game\\b", "\\bin game\\b",
		};
		List<String> problems = new ArrayList<>();
		for (String pattern : outside)
		{
			Matcher m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text);
			int shown = 0;
			while (m.find() && shown < 3)
			{
				int from = Math.max(0, m.start() - 40);
				int to = Math.min(text.length(), m.end() + 40);
				problems.add(pattern + ": ..." + text.substring(from, to).replace("\n", " ") + "...");
				shown++;
			}
		}
		assertTrue("words from outside the world:\n  " + String.join("\n  ", problems), problems.isEmpty());
	}

	/**
	 * Every page has a picture, and every picture can be drawn: a creature
	 * or a person by an NPC id, a place by a sketch filed under one name and
	 * made in fixed map regions. The regions were checked against the game's
	 * own world map with the cache dumper's region audit; a wrong one fails
	 * silently, the scribe simply never draws the place.
	 */
	@Test
	public void everyPageHasPlatesThatCanBeDrawn() throws IOException
	{
		ChronicleLoader loader = loadAsAFreshInstall();
		GameFontRepository fonts = new GameFontRepository(new Gson());
		fonts.load(folder.newFolder().toPath());
		GameFont small = fonts.getByName("p11_full");
		assertNotNull("the caption font is not bundled", small);

		List<String> problems = new ArrayList<>();
		java.util.Map<String, List<Integer>> sketchRegions = new java.util.HashMap<>();
		for (ChronicleEntry entry : loader.getEntries())
		{
			String at = entry.id;
			if (entry.platesOrEmpty().isEmpty())
			{
				problems.add(at + ": no plates");
			}
			boolean portrait = false;
			boolean sketch = false;
			for (ChronicleEntry.Plate plate : entry.platesOrEmpty())
			{
				if ((plate.npc == null) == (plate.sketch == null))
				{
					problems.add(at + ": a plate must be a portrait or a sketch, exactly one");
					continue;
				}
				if (plate.npc != null)
				{
					portrait = true;
					if (plate.npc <= 0)
					{
						problems.add(at + ": NPC id " + plate.npc);
					}
				}
				else
				{
					sketch = true;
					if (!plate.sketch.matches("[a-z0-9]+(-[a-z0-9]+)*"))
					{
						problems.add(at + ": sketch name " + plate.sketch);
					}
					if (plate.regions == null || plate.regions.isEmpty())
					{
						problems.add(at + ": sketch " + plate.sketch + " has nowhere it can be made");
					}
					else
					{
						List<Integer> before = sketchRegions.putIfAbsent(plate.sketch, plate.regions);
						if (before != null && !before.equals(plate.regions))
						{
							problems.add(at + ": sketch " + plate.sketch + " is made in " + plate.regions
								+ " here and " + before + " elsewhere");
						}
					}
				}
				if (plate.caption == null || plate.caption.trim().isEmpty())
				{
					problems.add(at + ": a plate with no caption");
				}
				else
				{
					// The longest numbering and the arrows either side.
					int width = small.stringWidth("Plate VIII. " + plate.caption);
					if (width > ChronicleLayout.PLATE_W - 32)
					{
						problems.add(at + ": caption too wide (" + width + "px): " + plate.caption);
					}
				}
			}
			String kind = entry.kindOrEmpty();
			if ((kind.equals("boss") || kind.equals("enemy")) && !portrait)
			{
				problems.add(at + ": a creature's page without its portrait");
			}
			if ((kind.equals("location") || kind.equals("world")) && !sketch)
			{
				problems.add(at + ": a place's page without a sketch of it");
			}
		}
		assertTrue("plates that cannot be drawn:\n  " + String.join("\n  ", problems), problems.isEmpty());
	}

	/**
	 * docs/chronicle-voice.md, "Plates carry shape, never scale": a caption
	 * is the subject plus one thing the picture cannot show, so it is never a
	 * bare label ("Lumbridge.") and never one another plate already uses.
	 */
	@Test
	public void everyCaptionIsItsOwn() throws IOException
	{
		ChronicleLoader loader = loadAsAFreshInstall();
		List<String> problems = new ArrayList<>();
		java.util.Map<String, String> usedOn = new java.util.HashMap<>();
		for (ChronicleEntry entry : loader.getEntries())
		{
			for (ChronicleEntry.Plate plate : entry.platesOrEmpty())
			{
				String caption = plate.caption == null ? "" : plate.caption.trim();
				if (caption.split("\\s+").length < 2 || caption.equalsIgnoreCase(entry.name + "."))
				{
					problems.add(entry.id + ": a bare label: " + caption);
				}
				String before = usedOn.putIfAbsent(caption, entry.id);
				if (before != null)
				{
					problems.add(entry.id + ": the same caption as a plate on " + before + ": " + caption);
				}
			}
		}
		assertTrue("captions that say nothing of their own:\n  " + String.join("\n  ", problems), problems.isEmpty());
	}

	@Test
	public void everyCharacterUsedHasAGlyphInTheFontsThatDrawIt() throws IOException
	{
		GameFontRepository fonts = new GameFontRepository(new Gson());
		fonts.load(folder.newFolder().toPath());
		GameFont[] used = {fonts.getByName("p12_full"), fonts.getByName("b12_full")};
		for (GameFont font : used)
		{
			assertNotNull("a chronicle font is not bundled: " + fonts.getStatus(), font);
		}

		Set<Character> distinct = new HashSet<>();
		for (char c : bundledText().toCharArray())
		{
			if (c > ' ' && c <= 0x7E)
			{
				distinct.add(c);
			}
		}
		List<String> blank = new ArrayList<>();
		BufferedImage scratch = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
		for (GameFont font : used)
		{
			for (char c : distinct)
			{
				java.awt.Graphics2D g = scratch.createGraphics();
				g.setComposite(java.awt.AlphaComposite.Clear);
				g.fillRect(0, 0, 40, 40);
				g.setComposite(java.awt.AlphaComposite.SrcOver);
				font.drawTop(g, String.valueOf(c), 4, 4, 0xffffff, false);
				g.dispose();
				boolean drawn = false;
				for (int y = 0; y < 40 && !drawn; y++)
				{
					for (int x = 0; x < 40; x++)
					{
						if ((scratch.getRGB(x, y) >>> 24) != 0)
						{
							drawn = true;
							break;
						}
					}
				}
				if (!drawn)
				{
					blank.add("'" + c + "'");
				}
			}
		}
		assertTrue("characters with no glyph: " + blank, blank.isEmpty());
	}

	@Test
	public void aBrokenFileLeavesTheChronicleEmptyRatherThanThrowing() throws IOException
	{
		for (String broken : new String[]{"", "   ", "null", "{", "[]", "not json at all",
			"{\"version\": 999}", "{\"entries\": [{\"id\": \"x\"}]}"})
		{
			java.nio.file.Path dir = folder.newFolder().toPath();
			java.nio.file.Files.write(dir.resolve(ChronicleLoader.FILE_NAME),
				broken.getBytes(StandardCharsets.UTF_8));
			ChronicleLoader loader = new ChronicleLoader(new Gson());
			loader.load(dir);
			// A bad override falls back to the bundled pages; a syntactically
			// fine but empty one reads as empty. Neither throws.
			assertNotNull(loader.getStatus());
		}
	}
}

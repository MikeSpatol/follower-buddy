package com.follower.chronicle;

import com.follower.ui.GameFont;
import com.follower.ui.GameFontRepository;
import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Every bundled page, laid out in the fonts the window draws it with, fits
 * the page: nothing runs off the right, and the last line can be scrolled
 * into view.
 *
 * <p>Play feedback: text at the bottom of a page was cut off. The page
 * scrolls in whole lines, and the limit was the number of lines less the
 * fourteen a screen holds; but headings and paragraphs sit a few pixels
 * apart, so fourteen lines with gaps between them are taller than the body,
 * and the last lines could never be reached. List items also wrapped at the
 * full column and then gained an indent, so a wrapped item ran a few pixels
 * past the column.
 */
public class ChroniclePageFitTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int BODY_HEIGHT = ChronicleLayout.CONTENT_BOTTOM - ChronicleLayout.BODY_TOP;
	private static final int WIDTH = ChronicleLayout.PAGE_TEXT_RIGHT - ChronicleLayout.PAGE_TEXT_X;

	private ChronicleLoader loader;
	private GameFont body;
	private GameFont bold;
	private GameFont small;

	@Before
	public void setUp() throws IOException
	{
		loader = new ChronicleLoader(new Gson());
		loader.load(folder.newFolder().toPath());
		GameFontRepository fonts = new GameFontRepository(new Gson());
		fonts.load(folder.newFolder().toPath());
		body = fonts.getByName("p12_full");
		bold = fonts.getByName("b12_full");
		small = fonts.getByName("p11_full");
		assertNotNull(body);
		assertNotNull(bold);
		assertNotNull(small);
	}

	private List<List<ChronicleLayout.Line>> everyPageBothWays()
	{
		List<List<ChronicleLayout.Line>> out = new ArrayList<>();
		for (ChronicleEntry entry : loader.getEntries())
		{
			// Every quest done: the longest each page gets, with nothing held back.
			ChronicleEntry whole = entry.readAs(quest -> true);
			out.add(ChronicleLayout.page(whole, WIDTH, body::stringWidth, bold::stringWidth));
			out.add(ChronicleLayout.lockedPage(whole, "The scribe has not met one of these yet.", WIDTH,
				body::stringWidth, bold::stringWidth));
		}
		return out;
	}

	/** Where each line's baseline falls below the body's top, scrolled to {@code scroll}, as the window draws them. */
	private static int lastBaseline(List<ChronicleLayout.Line> lines, int scroll)
	{
		int y = ChronicleLayout.LINE_HEIGHT - 2;
		for (int i = scroll + 1; i < lines.size(); i++)
		{
			y += lines.get(i).gapAbove + ChronicleLayout.LINE_HEIGHT;
		}
		return y;
	}

	@Test
	public void theDescentAllowedCoversTheLowestGlyphAndItsShadow()
	{
		BufferedImage image = new BufferedImage(200, 40, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		int baseline = 20;
		body.drawBaseline(g, "gjpqy,;()[]_Q", 2, baseline, 0xffffff, true);
		g.dispose();
		int lowest = 0;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) >>> 24) != 0)
				{
					lowest = Math.max(lowest, y);
				}
			}
		}
		assertTrue("glyphs reach " + (lowest - baseline) + " below the baseline",
			lowest - baseline <= ChronicleLayout.DESCENT);
	}

	@Test
	public void theLastLineOfEveryPageCanBeScrolledIntoView()
	{
		List<String> problems = new ArrayList<>();
		for (List<ChronicleLayout.Line> lines : everyPageBothWays())
		{
			int most = ChronicleLayout.lastScroll(lines, BODY_HEIGHT);
			int bottom = lastBaseline(lines, most) + ChronicleLayout.DESCENT;
			if (bottom >= BODY_HEIGHT)
			{
				problems.add(lines.size() + " lines: the last reaches " + bottom + " of " + BODY_HEIGHT);
			}
			// And no further than it needs to: one line less and it would not fit.
			if (most > 0 && lastBaseline(lines, most - 1) + ChronicleLayout.DESCENT < BODY_HEIGHT)
			{
				problems.add(lines.size() + " lines: scrolls further than needed");
			}
		}
		assertTrue(String.join("\n", problems), problems.isEmpty());
	}

	@Test
	public void pagingThroughShowsEveryLineWhole()
	{
		// Play review: a press on the track moved by as many lines as fit at
		// the foot of the page, where there are no gaps, so higher up some
		// lines were never drawn whole on any screen.
		List<String> problems = new ArrayList<>();
		for (List<ChronicleLayout.Line> lines : everyPageBothWays())
		{
			int last = ChronicleLayout.lastScroll(lines, BODY_HEIGHT);
			boolean[] seen = new boolean[lines.size()];
			int top = 0;
			while (true)
			{
				int whole = ChronicleLayout.linesThatFit(lines, top, BODY_HEIGHT);
				for (int i = top; i < Math.min(lines.size(), top + whole); i++)
				{
					if (lastBaseline(lines.subList(0, i + 1), top) + ChronicleLayout.DESCENT >= BODY_HEIGHT)
					{
						problems.add(lines.size() + " lines: line " + i + " counted whole from " + top + " but is not");
					}
					seen[i] = true;
				}
				if (top >= last)
				{
					break;
				}
				top = Math.min(last, top + whole);
			}
			for (int i = 0; i < seen.length; i++)
			{
				if (!seen[i])
				{
					problems.add(lines.size() + " lines: line " + i + " is never shown whole paging down");
				}
			}
			// And back up: each screen ends just above the last one's top.
			top = last;
			while (top > 0)
			{
				int above = ChronicleLayout.linesAbove(lines, top, BODY_HEIGHT);
				int from = top - above;
				if (above < 1 || lastBaseline(lines.subList(0, top), from) + ChronicleLayout.DESCENT >= BODY_HEIGHT)
				{
					problems.add(lines.size() + " lines: a page up from " + top + " by " + above + " does not show whole");
					break;
				}
				top = from;
			}
		}
		assertTrue(String.join("\n", problems), problems.isEmpty());
	}

	@Test
	public void theHeaderFitsTheColumnOnEveryPage()
	{
		// Play review: the header is not wrapped or clipped, and "Written
		// when: Waterfall Quest is under way, or one is met" ran over the rim
		// and off the side of the window.
		List<String> problems = new ArrayList<>();
		for (ChronicleEntry entry : loader.getEntries())
		{
			String[][] lines = {
				{entry.name, "bold"},
				{ChronicleWindow.kindLabel(entry.kindOrEmpty()) + ": Not yet written", "small"},
				{"Written when: " + ChronicleWindow.unlockLabel(entry), "small"},
			};
			for (String[] line : lines)
			{
				int width = ("bold".equals(line[1]) ? bold : small).stringWidth(line[0]);
				if (width > WIDTH)
				{
					problems.add(entry.id + ": " + width + "px: " + line[0]);
				}
			}
		}
		assertTrue(String.join("\n", problems), problems.isEmpty());
	}

	@Test
	public void theBookIsSignedWithTheFollowersNameWhenItFits()
	{
		assertEquals("Bartholomew's Chronicle - 5/17",
			ChronicleWindow.title("Bartholomew", 5, 17, bold::stringWidth));
		assertEquals("no name, no signature", "Chronicle - 5/17",
			ChronicleWindow.title("  ", 5, 17, bold::stringWidth));
		assertEquals("Chronicle - 5/17", ChronicleWindow.title(null, 5, 17, bold::stringWidth));
		String longName = "Sir Reginald Fortescue-Smythe the Unusually Thorough";
		assertEquals("a name too long for the title bar leaves the book unsigned", "Chronicle - 5/17",
			ChronicleWindow.title(longName, 5, 17, bold::stringWidth));
		assertTrue(bold.stringWidth(ChronicleWindow.title("Bartholomew", 150, 150, bold::stringWidth))
			<= ChronicleLayout.TITLE_ROOM);
	}

	@Test
	public void aWrittenPageSaysWhenInTheWorldsCalendarAndWhereOnTheLineBelow() throws IOException
	{
		long day = java.time.LocalDate.of(2026, 3, 22).toEpochDay();
		assertEquals("Written on the 4th of Bennath, year 169",
			ChronicleWindow.writtenOn(day, "Enemy: ", small::stringWidth, WIDTH));
		assertEquals("Written on Moevyng Day, year 169", ChronicleWindow.writtenOn(
			java.time.LocalDate.of(2028, 2, 29).toEpochDay(), "Place: ", small::stringWidth, WIDTH));

		// Every kind on its longest day: the year goes before the line runs over.
		long longest = java.time.LocalDate.of(2026, 10, 17).toEpochDay();
		for (String kind : new String[]{"quest", "boss", "enemy", "location", "faction", "world"})
		{
			String label = ChronicleWindow.kindLabel(kind) + ": ";
			String line = ChronicleWindow.writtenOn(longest, label, small::stringWidth, WIDTH);
			assertTrue(line, small.stringWidth(label + line) <= WIDTH);
			assertTrue(line, line.startsWith("Written on the 40th of Ire of Phyrrys"));
		}

		// Play review: the Locations rules name places descriptively, and
		// "Lumbridge town and castle" never fitted beside a date.
		com.follower.speech.RuleLoader rules = new com.follower.speech.RuleLoader(new Gson());
		rules.initialise(folder.newFolder().toPath());
		com.follower.speech.TriggerContext names = new com.follower.speech.TriggerContext(null);
		names.setRegionNames(rules.getRegionNames());
		assertEquals("Lumbridge", ChronicleWindow.shortPlace(names.placeName(12850)));
		assertEquals("the Grand Exchange", ChronicleWindow.shortPlace("The Grand Exchange plaza"));
		assertEquals("East Ardougne", ChronicleWindow.shortPlace("East Ardougne and its market"));
		assertEquals("the Barrows", ChronicleWindow.shortPlace("The Barrows mounds"));
		assertEquals("Fishing Guild", ChronicleWindow.shortPlace("Fishing Guild"));
		List<String> problems = new ArrayList<>();
		for (Integer region : rules.getRegionNames().keySet())
		{
			String line = ChronicleWindow.WRITTEN_AT + ChronicleWindow.shortPlace(names.placeName(region));
			if (small.stringWidth(line) > WIDTH)
			{
				problems.add(region + ": " + line);
			}
		}
		assertTrue(String.join(", ", problems), problems.isEmpty());
	}

	@Test
	public void noLineRunsPastTheColumn()
	{
		List<String> problems = new ArrayList<>();
		for (List<ChronicleLayout.Line> lines : everyPageBothWays())
		{
			for (ChronicleLayout.Line line : lines)
			{
				GameFont font = line.style == ChronicleLayout.Style.HEADING ? bold : body;
				String text = line.text.trim();
				// Only a single word with no hyphen to break at may overflow.
				String word = text.startsWith("- ") ? text.substring(2) : text;
				boolean unbreakable = !word.contains(" ") && !word.contains("-");
				if (!unbreakable && font.stringWidth(line.text) > WIDTH)
				{
					problems.add(font.stringWidth(line.text) + "px: " + line.text);
				}
			}
		}
		assertTrue(String.join("\n", problems), problems.isEmpty());
	}

	@Test
	public void everyPagesOwnNumberFitsOnOneLine()
	{
		// A five-figure count, more goblins than anyone kills, still one line
		// in the margin and not two.
		List<String> problems = new ArrayList<>();
		int tallies = 0;
		for (ChronicleEntry entry : loader.getEntries())
		{
			String line = ChronicleWindow.tallyLine(entry, 99999);
			if (line == null)
			{
				continue;
			}
			tallies++;
			if (body.stringWidth(line) > WIDTH)
			{
				problems.add(entry.id + ": " + body.stringWidth(line) + "px: " + line);
			}
			List<ChronicleLayout.Line> lines = ChronicleLayout.page(entry.readAs(quest -> true), line, false, WIDTH,
				body::stringWidth, bold::stringWidth);
			int margin = entry.platesOrEmpty().isEmpty() ? 0 : ChronicleLayout.PLATE_ROWS + 1;
			if (!lines.get(margin).text.equals(line))
			{
				problems.add(entry.id + ": not straight under the plate: " + lines.get(margin));
			}
		}
		assertTrue(String.join("\n", problems), problems.isEmpty());
		assertEquals("the creature and boss pages count", 9, tallies);
	}

	@Test
	public void everyPoseLineFitsUnderThePlate()
	{
		// The caption's own room, though the pose line has no arrows beside it.
		List<String> problems = new ArrayList<>();
		for (String phrase : ChronicleWindow.POSE_PHRASES.values())
		{
			if (small.stringWidth(phrase) > ChronicleLayout.PLATE_W - 32)
			{
				problems.add(small.stringWidth(phrase) + "px: " + phrase);
			}
			if (!phrase.chars().allMatch(c -> c >= 0x20 && c < 0x7f))
			{
				problems.add("not plain ASCII: " + phrase);
			}
		}
		assertTrue(String.join("\n", problems), problems.isEmpty());
	}

	@Test
	public void theLastLineCanStillBeReachedWithTheNumberAndThePose()
	{
		List<String> problems = new ArrayList<>();
		for (ChronicleEntry entry : loader.getEntries())
		{
			List<ChronicleLayout.Line> lines = ChronicleLayout.page(entry.readAs(quest -> true),
				ChronicleWindow.tallyLine(entry, 99999), true, WIDTH, body::stringWidth, bold::stringWidth);
			int most = ChronicleLayout.lastScroll(lines, BODY_HEIGHT);
			if (lastBaseline(lines, most) + ChronicleLayout.DESCENT >= BODY_HEIGHT)
			{
				problems.add(entry.id);
			}
		}
		assertTrue(String.join("\n", problems), problems.isEmpty());
	}
}

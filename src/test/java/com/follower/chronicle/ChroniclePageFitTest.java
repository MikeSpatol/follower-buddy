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
			out.add(ChronicleLayout.page(entry, WIDTH, body::stringWidth, bold::stringWidth));
			out.add(ChronicleLayout.lockedPage(entry, "The scribe has not met one of these yet.", WIDTH,
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
}

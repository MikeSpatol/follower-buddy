package com.follower.chronicle;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The window's geometry and page maths, without a canvas.
 *
 * <p>The window is the Collection Log's 500 by 314, and every rectangle in
 * it is measured off the real thing; these pin the arithmetic that turns
 * that into rows, tabs, scroll positions and wrapped lines.
 */
public class ChronicleLayoutTest
{
	/** Six pixels a character, a stand-in for a font. */
	private static int width(String s)
	{
		return s.length() * 6;
	}

	@Test
	public void theWindowIsTheCollectionLogsSize()
	{
		assertEquals(500, ChronicleLayout.WIDTH);
		assertEquals(314, ChronicleLayout.HEIGHT);
	}

	@Test
	public void theTabsSitWhereTheGamesDo()
	{
		// Measured off the Collection Log: five tabs of 94 at x = 6, 103,
		// 200, 297 and 394, three pixels of tile between each.
		Rectangle[] tabs = ChronicleLayout.tabs(5);
		assertEquals(5, tabs.length);
		int[] lefts = {6, 103, 200, 297, 394};
		for (int i = 0; i < tabs.length; i++)
		{
			assertEquals("tab " + i, lefts[i], tabs[i].x);
			assertEquals(94, tabs[i].width);
			assertEquals(20, tabs[i].height);
			assertEquals(35, tabs[i].y);
		}
		Rectangle last = tabs[4];
		assertTrue("inside the rim", last.x + last.width <= ChronicleLayout.CONTENT_RIGHT);
	}

	@Test
	public void thePanesDoNotOverlapAndSitInsideTheRim()
	{
		Rectangle list = ChronicleLayout.LIST;
		Rectangle listBar = ChronicleLayout.LIST_SCROLL;
		Rectangle page = ChronicleLayout.PAGE;
		Rectangle pageBar = ChronicleLayout.PAGE_SCROLL;
		assertEquals(list.x + list.width, listBar.x);
		assertTrue(listBar.x + listBar.width <= page.x);
		assertEquals(page.x + page.width, pageBar.x);
		assertTrue("inside the rim", pageBar.x + pageBar.width <= ChronicleLayout.WIDTH - ChronicleLayout.EDGE);
		assertTrue(list.y > ChronicleLayout.CONTENT_TOP);
		assertEquals("the list runs down to the line above the rim",
			ChronicleLayout.CONTENT_BOTTOM, list.y + list.height);
		assertTrue("the split bar lies between the list's bar and the page",
			listBar.x + listBar.width <= ChronicleLayout.SPLIT_X && ChronicleLayout.SPLIT_X + 2 <= page.x);
		assertEquals("the page's bar starts under the header rules",
			ChronicleLayout.BODY_TOP, pageBar.y);
		assertEquals("the close button is one pixel in from the rim on both sides",
			new Rectangle(473, 7, 21, 21), ChronicleLayout.CLOSE);
	}

	@Test
	public void rowsTileTheListExactly()
	{
		int visible = ChronicleLayout.visibleRows();
		assertTrue(visible >= 15);
		Rectangle first = ChronicleLayout.row(0);
		Rectangle last = ChronicleLayout.row(visible - 1);
		assertEquals("the first band sits under the top line", ChronicleLayout.ROW_BAND_TOP, first.y);
		assertEquals(15, first.height);
		assertTrue(last.y + last.height <= ChronicleLayout.CONTENT_BOTTOM);
	}

	@Test
	public void scrollingStaysWithinWhatThereIs()
	{
		assertEquals(0, ChronicleLayout.clampScroll(-3, 40, 16));
		assertEquals(24, ChronicleLayout.clampScroll(99, 40, 16));
		assertEquals("nothing to scroll when it all fits", 0, ChronicleLayout.clampScroll(5, 10, 16));
	}

	@Test
	public void theThumbIsProportionalAndTravelsTheTrack()
	{
		Rectangle bar = new Rectangle(0, 0, 16, 248);
		int[] all = ChronicleLayout.thumb(bar, 0, 10, 16);
		assertEquals("everything fits: the thumb fills the track", 16, all[0]);
		assertEquals(248 - 32, all[1]);

		int[] top = ChronicleLayout.thumb(bar, 0, 100, 16);
		int[] bottom = ChronicleLayout.thumb(bar, 84, 100, 16);
		assertEquals(16, top[0]);
		assertEquals("the same height at both ends", top[1], bottom[1]);
		assertEquals("and it reaches the bottom of the track", 248 - 16, bottom[0] + bottom[1]);
		assertTrue("never too small to grab", top[1] >= 10);
	}

	@Test
	public void draggingTheThumbBackToWhereItWasDrawnGivesTheSameOffset()
	{
		// The thumb's top for an offset, fed back in, must give that offset:
		// otherwise a drag that does not move the mouse still scrolls.
		Rectangle bar = new Rectangle(0, 0, 16, 204);
		// Exact wherever the thumb moves at least a pixel a row.
		for (int total : new int[]{17, 40, 100})
		{
			for (int offset = 0; offset <= total - 14; offset += 3)
			{
				int top = ChronicleLayout.thumb(bar, offset, total, 14)[0];
				assertEquals("total " + total + " offset " + offset, offset,
					ChronicleLayout.scrollForThumb(bar, top, total, 14));
			}
		}
		// A page longer than the track has pixels: several rows share a
		// pixel, so the round trip can only be as close as that allows.
		int rowsPerPixel = (350 - 14) / (204 - 32 - 10) + 1;
		for (int offset = 0; offset <= 336; offset += 7)
		{
			int top = ChronicleLayout.thumb(bar, offset, 350, 14)[0];
			int back = ChronicleLayout.scrollForThumb(bar, top, 350, 14);
			assertTrue("offset " + offset + " came back as " + back,
				Math.abs(back - offset) <= rowsPerPixel);
		}
		assertEquals("above the track is the top", 0, ChronicleLayout.scrollForThumb(bar, -50, 100, 14));
		assertEquals("below it is the bottom", 86, ChronicleLayout.scrollForThumb(bar, 999, 100, 14));
		assertEquals("nothing to drag when it all fits", 0, ChronicleLayout.scrollForThumb(bar, 40, 10, 14));
	}

	@Test
	public void wrappingBreaksOnSpacesAndNeverLosesAWord()
	{
		List<String> lines = ChronicleLayout.wrap("the quick brown fox jumps over the lazy dog", 60,
			ChronicleLayoutTest::width);
		for (String line : lines)
		{
			assertTrue(line, width(line) <= 60 || !line.contains(" "));
		}
		assertEquals("the quick brown fox jumps over the lazy dog", String.join(" ", lines));
	}

	@Test
	public void aWordLongerThanTheColumnOverflowsRatherThanVanishing()
	{
		List<String> lines = ChronicleLayout.wrap("a supercalifragilistic b", 30, ChronicleLayoutTest::width);
		assertEquals(Arrays.asList("a", "supercalifragilistic", "b"), lines);
	}

	@Test
	public void aHyphenatedWordLongerThanTheColumnBreaksAfterItsHyphens()
	{
		// Play feedback: Malignius-Mortifer's-Super-Ultra-Flora-Growth-Potion
		// ran off the side of the Giant Mole's page.
		List<String> lines = ChronicleLayout.wrap("a Super-Ultra-Flora-Growth b", 40, ChronicleLayoutTest::width);
		assertEquals(Arrays.asList("a", "Super-", "Ultra-", "Flora-", "Growth", "b"), lines);
		assertEquals("a word that fits keeps its hyphens whole",
			Arrays.asList("well-known"), ChronicleLayout.wrap("well-known", 80, ChronicleLayoutTest::width));
	}

	@Test
	public void aPageReadsSummaryThenSectionsThenTheLists()
	{
		ChronicleEntry entry = new ChronicleEntry();
		entry.id = "quest-test";
		entry.kind = "quest";
		entry.name = "Test";
		entry.summary = "One two.";
		ChronicleEntry.Section section = new ChronicleEntry.Section();
		section.heading = "What happened";
		section.body = "First paragraph.\n\nSecond paragraph.";
		entry.sections = Arrays.asList(section);
		entry.people = Arrays.asList("Someone - did a thing");
		entry.places = Arrays.asList();
		entry.details = Arrays.asList("A detail");

		List<ChronicleLayout.Line> lines = ChronicleLayout.page(entry, 600,
			ChronicleLayoutTest::width, ChronicleLayoutTest::width);
		assertEquals("BODY:One two.", lines.get(0).toString());
		assertEquals("HEADING:What happened", lines.get(1).toString());
		assertEquals("BODY:First paragraph.", lines.get(2).toString());
		assertEquals("BODY:Second paragraph.", lines.get(3).toString());
		assertTrue("a paragraph break is a gap, not a blank line", lines.get(3).gapAbove > 0);
		assertEquals("HEADING:People", lines.get(4).toString());
		assertEquals("NOTE:- Someone - did a thing", lines.get(5).toString());
		assertEquals("an empty list gets no heading", "HEADING:Particulars", lines.get(6).toString());
		assertEquals("NOTE:- A detail", lines.get(7).toString());
		assertEquals(8, lines.size());
	}

	@Test
	public void aPageWithPicturesOpensWithThePlateAndItsCaption()
	{
		ChronicleEntry entry = new ChronicleEntry();
		entry.id = "enemy-x";
		entry.kind = "enemy";
		entry.name = "X";
		entry.summary = "One two.";
		ChronicleEntry.Plate plate = new ChronicleEntry.Plate();
		plate.npc = 3029;
		plate.caption = "A goblin.";
		entry.plates = Arrays.asList(plate);

		List<ChronicleLayout.Line> lines = ChronicleLayout.page(entry, 600,
			ChronicleLayoutTest::width, ChronicleLayoutTest::width);
		for (int i = 0; i < ChronicleLayout.PLATE_ROWS; i++)
		{
			assertEquals("row " + i + " is held for the plate", ChronicleLayout.Style.PLATE, lines.get(i).style);
		}
		assertEquals(ChronicleLayout.Style.CAPTION, lines.get(ChronicleLayout.PLATE_ROWS).style);
		ChronicleLayout.Line summary = lines.get(ChronicleLayout.PLATE_ROWS + 1);
		assertEquals("BODY:One two.", summary.toString());
		assertTrue("a gap between the caption and the words", summary.gapAbove > 0);
		assertTrue("the plate fits the rows it is given",
			ChronicleLayout.PLATE_H <= ChronicleLayout.PLATE_ROWS * ChronicleLayout.LINE_HEIGHT);

		entry.plates = null;
		assertEquals("no pictures, no room held", "BODY:One two.",
			ChronicleLayout.page(entry, 600, ChronicleLayoutTest::width, ChronicleLayoutTest::width).get(0).toString());
	}

	@Test
	public void platesAreNumberedAsABookNumbersThem()
	{
		assertEquals("I", ChronicleLayout.roman(1));
		assertEquals("IV", ChronicleLayout.roman(4));
		assertEquals("VIII", ChronicleLayout.roman(8));
	}

	@Test
	public void theEnlargedPlateFillsTheBoxUnderTheTabs()
	{
		Rectangle big = ChronicleLayout.BIG_PLATE;
		Rectangle box = new Rectangle(ChronicleLayout.CONTENT_LEFT + 1, ChronicleLayout.CONTENT_TOP + 1,
			ChronicleLayout.CONTENT_RIGHT - ChronicleLayout.CONTENT_LEFT - 1,
			ChronicleLayout.CONTENT_BOTTOM - ChronicleLayout.CONTENT_TOP - 1);
		assertTrue("inside the box", box.contains(big));
		assertEquals("centred across the window", ChronicleLayout.WIDTH - big.x - big.width, big.x);
		assertTrue("the name sits above the picture", ChronicleLayout.BIG_NAME_BASELINE + 4 < big.y);
		assertTrue("and clear of the tabs", ChronicleLayout.BIG_NAME_BASELINE - 12 > ChronicleLayout.CONTENT_TOP);
		assertTrue("the caption under it", ChronicleLayout.BIG_CAPTION_BASELINE - 10 > big.y + big.height);
		assertTrue("inside the box", ChronicleLayout.BIG_CAPTION_BASELINE + 4 < ChronicleLayout.CONTENT_BOTTOM);
		// The same view larger: a sketch keeps its shape.
		double small = (ChronicleLayout.PLATE_W - 4) / (double) (ChronicleLayout.PLATE_H - 4);
		double large = (big.width - 4) / (double) (big.height - 4);
		assertEquals(small, large, small * 0.01);
		assertTrue("and it is bigger by most of twice", big.width - 4 >= (ChronicleLayout.PLATE_W - 4) * 1.8);
	}

	@Test
	public void aLongListItemWrapsUnderItsDash()
	{
		ChronicleEntry entry = new ChronicleEntry();
		entry.id = "boss-x";
		entry.kind = "boss";
		entry.name = "X";
		entry.summary = "S.";
		entry.details = Arrays.asList("one two three four five six");
		List<ChronicleLayout.Line> lines = ChronicleLayout.page(entry, 80,
			ChronicleLayoutTest::width, ChronicleLayoutTest::width);
		assertTrue(lines.get(2).text.startsWith("- "));
		assertTrue("continuation lines are indented under the text", lines.get(3).text.startsWith("  "));
		assertFalse(lines.get(3).text.startsWith("- "));
	}
}

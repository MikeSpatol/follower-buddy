package com.follower.chronicle;

import com.follower.ui.GameFont;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The geometry of the chronicle window, measured pixel by pixel off the
 * game's own Collection Log, and the page maths - wrapping, scrolling, hit
 * rectangles - kept apart from the drawing so it can be tested without a
 * canvas.
 *
 * <p>Everything is in the window's own pixels with (0, 0) at its top-left
 * corner; the overlay adds where the window sits on the canvas. The window
 * is the Collection Log's 500 by 314: a 6 pixel steel rim with 25 by 30
 * riveted corners round a tiled stone backing, the title on its band, a
 * steel divider, a strip of five tabs, a list down the left with its
 * scrollbar, and the page on the right with a header block and its own.
 * Every number here was confirmed against the real window's pixels.
 */
public final class ChronicleLayout
{
	public static final int WIDTH = 500;
	public static final int HEIGHT = 314;

	/** The rim: corner sprites and the 6 pixel strips between them. */
	public static final int CORNER_W = 25;
	public static final int CORNER_H = 30;
	public static final int EDGE = 6;

	/** The vertical rim strips repeat on this phase; the divider on the other. */
	public static final int RIM_PHASE_Y = 12;
	public static final int DIVIDER_PHASE_X = 23;

	/** The title's baseline, centred on the window, and the close button. */
	public static final int TITLE_BASELINE = 22;
	public static final Rectangle CLOSE = new Rectangle(473, 7, 21, 21);

	/** How wide the centred title may be and stay clear of the close button either side. */
	public static final int TITLE_ROOM = 2 * (473 - WIDTH / 2 - 4);

	/** The steel divider under the title band: 6 high, rim to rim. */
	public static final int DIVIDER_TOP = 29;

	/** The tab strip: five tabs of 94, three pixels apart, sat on the line below. */
	public static final int TABS_TOP = 35;
	public static final int TABS_HEIGHT = 20;
	public static final int TABS_LEFT = 6;
	public static final int TAB_WIDTH = 94;
	public static final int TAB_GAP = 3;

	/** The thin lines that box the content: under the tabs, above the bottom rim, the sides. */
	public static final int CONTENT_TOP = 55;
	public static final int CONTENT_BOTTOM = 307;
	public static final int CONTENT_LEFT = 6;
	public static final int CONTENT_RIGHT = 493;

	/** The two pixel bar between the list and the page. */
	public static final int SPLIT_X = 205;

	/** The list: its text column, its rows, its scrollbar. */
	public static final Rectangle LIST = new Rectangle(7, 56, 182, 251);
	public static final int LIST_TEXT_X = 9;
	public static final int ROW_HEIGHT = 15;
	public static final int ROW_BAND_TOP = 55;
	public static final int ROW_BASELINE = 12;
	public static final int SCROLLBAR_W = 16;
	public static final Rectangle LIST_SCROLL = new Rectangle(189, 56, SCROLLBAR_W, 251);

	/** The page: the header block, two lines under it, then the scrolling body. */
	public static final Rectangle PAGE = new Rectangle(207, 56, 270, 251);
	public static final int PAGE_TEXT_X = 214;
	public static final int PAGE_TEXT_RIGHT = 470;
	public static final int HEADER_BASELINE = 69;
	public static final int HEADER_LINE2_BASELINE = 83;
	public static final int HEADER_LINE3_BASELINE = 95;
	public static final int HEADER_RULE_Y = 100;
	public static final int BODY_TOP = 103;
	public static final Rectangle PAGE_SCROLL = new Rectangle(477, BODY_TOP, SCROLLBAR_W, CONTENT_BOTTOM - BODY_TOP);
	public static final int LINE_HEIGHT = 14;
	public static final int PARAGRAPH_GAP = 6;

	/**
	 * The plate at the head of a page: the text column's width, and a whole
	 * number of lines tall so it scrolls with the text in the same steps.
	 */
	public static final int PLATE_ROWS = 8;
	public static final int PLATE_W = PAGE_TEXT_RIGHT - PAGE_TEXT_X;
	public static final int PLATE_H = PLATE_ROWS * LINE_HEIGHT - 8;

	/**
	 * A plate opened across the window: the whole box under the tabs, the
	 * page's name above the picture and the caption line below. The picture
	 * keeps the page plate's shape, so a sketch is the same view larger.
	 */
	public static final int BIG_PLATE_W = 472;
	public static final int BIG_PLATE_H = 190;
	public static final Rectangle BIG_PLATE = new Rectangle((WIDTH - BIG_PLATE_W) / 2, 84, BIG_PLATE_W, BIG_PLATE_H);
	public static final int BIG_NAME_BASELINE = 76;
	public static final int BIG_CAPTION_BASELINE = 84 + BIG_PLATE_H + 14;

	/** The vanilla colours, confirmed by pixel count on the real window. */
	public static final int TITLE_ORANGE = 0xff981f;
	public static final int TAB_LABEL_SELECTED = 0xffa82f;
	public static final int READABLE_GREEN = 0x0dc10d;
	public static final int LOCKED_RED = 0xff0000;
	public static final int BODY_WHITE = 0xffffff;
	public static final int NOTE_PALE = 0xdfdfdf;
	public static final int SELECTED_ROW = 0x70695f;
	public static final int ROW_STRIPE = 0x564c42;
	public static final int TAB_FACE = 0x28251e;
	public static final int TAB_FACE_SELECTED = 0x3e3529;
	public static final int TAB_OUTLINE = 0x5d5848;
	public static final int BACKING = 0x483e33;
	public static final int LINE = 0x585040;
	public static final int TRACK = 0x252019;
	public static final int STEEL = 0x2d2b24;

	private ChronicleLayout()
	{
	}

	/** The tabs, left to right: fixed width, fixed pitch, as the game lays them. */
	public static Rectangle[] tabs(int count)
	{
		Rectangle[] out = new Rectangle[count];
		for (int i = 0; i < count; i++)
		{
			out[i] = new Rectangle(TABS_LEFT + i * (TAB_WIDTH + TAB_GAP), TABS_TOP, TAB_WIDTH, TABS_HEIGHT);
		}
		return out;
	}

	/** How many whole rows the list shows; a part of one more peeks below. */
	public static int visibleRows()
	{
		return LIST.height / ROW_HEIGHT;
	}

	/** The band of the nth visible row; the first sits just under the top line. */
	public static Rectangle row(int visibleIndex)
	{
		return new Rectangle(LIST.x, ROW_BAND_TOP + visibleIndex * ROW_HEIGHT, LIST.width, ROW_HEIGHT);
	}

	/** How far the page's glyphs and their shadow reach below a baseline. */
	public static final int DESCENT = 4;

	/**
	 * The furthest a page scrolls: the first line from which everything to
	 * the end, the gaps above headings and paragraphs included, fits the
	 * body with the last line's descenders inside it. The page scrolls in
	 * whole lines, but the gaps are not lines, so counting lines alone left
	 * the last few out of reach.
	 */
	public static int lastScroll(List<Line> lines, int bodyHeight)
	{
		// From the first line shown, the last one's baseline sits the first
		// baseline's depth down plus a line and a gap for every line after.
		int room = bodyHeight - 1 - (LINE_HEIGHT - 2) - DESCENT;
		int first = lines.size() - 1;
		int used = 0;
		while (first > 0 && used + LINE_HEIGHT + lines.get(first).gapAbove <= room)
		{
			used += LINE_HEIGHT + lines.get(first).gapAbove;
			first--;
		}
		return Math.max(0, first);
	}

	/**
	 * How many lines, from the one at the top, the body shows whole: a page
	 * down moves by this many, so the line half off the foot of one screen is
	 * the top of the next and no line is passed over unseen.
	 */
	public static int linesThatFit(List<Line> lines, int first, int bodyHeight)
	{
		int room = bodyHeight - 1 - (LINE_HEIGHT - 2) - DESCENT;
		int used = 0;
		int count = 1;
		for (int i = first + 1; i < lines.size(); i++)
		{
			used += LINE_HEIGHT + lines.get(i).gapAbove;
			if (used > room)
			{
				break;
			}
			count++;
		}
		return count;
	}

	/** And a page up: as many lines as fit whole ending just above the top one. */
	public static int linesAbove(List<Line> lines, int first, int bodyHeight)
	{
		int room = bodyHeight - 1 - (LINE_HEIGHT - 2) - DESCENT;
		int used = 0;
		int count = 0;
		for (int top = Math.min(first, lines.size()) - 1; top >= 0; top--)
		{
			if (count > 0)
			{
				// The line below this one moves down by a line and its gap.
				used += LINE_HEIGHT + lines.get(top + 1).gapAbove;
				if (used > room)
				{
					break;
				}
			}
			count++;
		}
		return count;
	}

	/** A scroll offset, kept inside what there is to scroll to. */
	public static int clampScroll(int offset, int total, int visible)
	{
		int most = Math.max(0, total - visible);
		return Math.max(0, Math.min(offset, most));
	}

	/**
	 * Where a reader stays when the page they are on is laid out again: in
	 * the same place, moved on past the page's number if it has just
	 * appeared above them, so the line they were reading stays at the top.
	 */
	public static int keptScroll(int scroll, List<Line> before, List<Line> after)
	{
		// The number sits straight under the plate, if the page has one.
		int at = 0;
		while (at < after.size() && (after.get(at).style == Style.PLATE || after.get(at).style == Style.CAPTION))
		{
			at++;
		}
		if (scroll == 0 || scroll < at)
		{
			return scroll;
		}
		return Math.max(0, scroll + count(after, Style.TALLY) - count(before, Style.TALLY));
	}

	private static int count(List<Line> lines, Style style)
	{
		int n = 0;
		for (Line line : lines)
		{
			n += line.style == style ? 1 : 0;
		}
		return n;
	}

	/**
	 * Where the thumb sits on a scrollbar whose arrows are 16 high: its top
	 * and height, in the bar's own pixels, proportional to what is shown.
	 */
	public static int[] thumb(Rectangle bar, int offset, int total, int visible)
	{
		int trackTop = 16;
		int trackHeight = bar.height - 32;
		if (total <= visible || total <= 0)
		{
			return new int[]{trackTop, trackHeight};
		}
		int height = Math.max(10, trackHeight * visible / total);
		int travel = trackHeight - height;
		// Rounded, as the inverse is, so a thumb put back where it was drawn
		// reads as the offset it was drawn for.
		int top = trackTop + (int) Math.round(travel * (double) offset / Math.max(1, total - visible));
		return new int[]{top, height};
	}

	/**
	 * The scroll offset that puts the thumb's top at {@code thumbTop}, in the
	 * bar's own pixels: the inverse of {@link #thumb}, for dragging it.
	 */
	public static int scrollForThumb(Rectangle bar, int thumbTop, int total, int visible)
	{
		if (total <= visible || total <= 0)
		{
			return 0;
		}
		int trackTop = 16;
		int trackHeight = bar.height - 32;
		int height = thumb(bar, 0, total, visible)[1];
		int travel = trackHeight - height;
		if (travel <= 0)
		{
			return 0;
		}
		int along = Math.max(0, Math.min(thumbTop - trackTop, travel));
		return (int) Math.round(along * (double) (total - visible) / travel);
	}

	/**
	 * What a page shows while it cannot be read: the reason, and for a
	 * creature the section that says where to find it, so the book is a
	 * guide to filling itself rather than a list of blanks.
	 */
	public static List<Line> lockedPage(ChronicleEntry entry, String reason, int width,
		ToIntFunction<String> bodyWidth, ToIntFunction<String> headingWidth)
	{
		List<Line> lines = new ArrayList<>();
		if (entry == null)
		{
			return lines;
		}
		plate(lines, entry);
		paragraphs(lines, reason, width, bodyWidth, Style.BODY, 0);
		String kind = entry.kindOrEmpty();
		if (!kind.equals("boss") && !kind.equals("enemy"))
		{
			return lines;
		}
		for (ChronicleEntry.Section section : entry.sectionsOrEmpty())
		{
			if (section.heading != null && section.heading.equalsIgnoreCase("Where it is found"))
			{
				heading(lines, section.heading, width, headingWidth);
				paragraphs(lines, section.body, width, bodyWidth, Style.BODY, 0);
			}
		}
		return lines;
	}

	/**
	 * Greedy word wrap measured with the game's own advances, the dialog's
	 * recipe. A word longer than the column breaks after its hyphens, as a
	 * printer would break it; one with no hyphen is left to overflow. A
	 * dash standing alone keeps a word either side of it, and a last line of
	 * one word takes another with it from the line above.
	 */
	public static List<String> wrap(String text, int maxWidth, ToIntFunction<String> width)
	{
		return wrap(text, maxWidth, maxWidth, width);
	}

	/** The same, with the lines after the first narrower, for a hanging indent. */
	public static List<String> wrap(String text, int firstWidth, int restWidth, ToIntFunction<String> width)
	{
		List<String> out = new ArrayList<>();
		if (text == null)
		{
			return out;
		}
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			String[] pieces = width.applyAsInt(word) > Math.min(firstWidth, restWidth)
				? word.split("(?<=-)(?=.)")
				: new String[]{word};
			for (int p = 0; p < pieces.length; p++)
			{
				// The pieces of one word join with no space between them.
				String piece = pieces[p];
				String candidate = line.length() == 0 ? piece : line + (p > 0 ? "" : " ") + piece;
				int maxWidth = out.isEmpty() ? firstWidth : restWidth;
				if (width.applyAsInt(candidate) > maxWidth && line.length() > 0)
				{
					// A list's "label - text" separator, left at the end of a
					// line, reads as if the line ran out, and opening the next
					// it reads as a new item; it goes down with its label's
					// last word.
					String full = line.toString();
					int cut = p == 0 && full.endsWith(" -") ? full.lastIndexOf(' ', full.length() - 3) : -1;
					String carried = cut > 0 ? full.substring(cut + 1) + " " + piece : null;
					if (carried != null && !full.substring(0, cut).equals("-")
						&& width.applyAsInt(carried) <= restWidth)
					{
						out.add(full.substring(0, cut));
						line = new StringBuilder(carried);
					}
					else
					{
						out.add(full);
						line = new StringBuilder(piece);
					}
				}
				else
				{
					line = new StringBuilder(candidate);
				}
			}
		}
		if (line.length() > 0)
		{
			out.add(line.toString());
		}
		unorphan(out, restWidth, width);
		return out;
	}

	/**
	 * A paragraph that ends on one word alone takes the last word of the
	 * line above down with it, if that line has words to spare and the two
	 * still fit.
	 */
	private static void unorphan(List<String> out, int restWidth, ToIntFunction<String> width)
	{
		int last = out.size() - 1;
		if (last < 1 || out.get(last).contains(" "))
		{
			return;
		}
		String above = out.get(last - 1);
		int cut = above.lastIndexOf(' ');
		// Not after a long word broken at its hyphen: its pieces join with no space.
		if (above.split(" ").length < 3 || above.endsWith("-"))
		{
			return;
		}
		String kept = above.substring(0, cut);
		String moved = above.substring(cut + 1) + " " + out.get(last);
		if (kept.endsWith(" -") || width.applyAsInt(moved) > restWidth)
		{
			return;
		}
		out.set(last - 1, kept);
		out.set(last, moved);
	}

	public static List<String> wrap(String text, int maxWidth, GameFont font)
	{
		return wrap(text, maxWidth, font::stringWidth);
	}

	/** One drawn line of a page: its text, its style, and a gap above it. */
	public static final class Line
	{
		public final String text;
		public final Style style;
		public final int gapAbove;

		Line(String text, Style style, int gapAbove)
		{
			this.text = text;
			this.style = style;
			this.gapAbove = gapAbove;
		}

		@Override
		public String toString()
		{
			return style + ":" + text;
		}
	}

	public enum Style
	{
		HEADING, BODY, NOTE,
		/** The page's own number, drawn as the header's label and value. */
		TALLY,
		/** Room held for the plate, drawn as one picture over all its rows. */
		PLATE,
		/** The line under the plate; the window fills in which plate it is. */
		CAPTION
	}

	/**
	 * The plate's rows and its caption line, when the page has pictures. A
	 * sketch's pose is pencilled on the drawing rather than given a line: a
	 * line kept for it showed blank under every other plate on the page.
	 */
	private static void plate(List<Line> lines, ChronicleEntry entry)
	{
		if (entry.platesOrEmpty().isEmpty())
		{
			return;
		}
		for (int i = 0; i < PLATE_ROWS; i++)
		{
			lines.add(new Line("", Style.PLATE, 0));
		}
		lines.add(new Line("", Style.CAPTION, 0));
	}

	/** Plate numbers as a book has them. */
	public static String roman(int n)
	{
		String[] numerals = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
		return n >= 1 && n <= numerals.length ? numerals[n - 1] : String.valueOf(n);
	}

	/**
	 * A page laid out as lines, in reading order: the summary, then each
	 * section under its heading, then the people, places and particulars as
	 * headed lists. Widths are measured with the body font; headings are set
	 * in the bold face, which is narrower or wider by a little, so the
	 * caller passes both measures.
	 */
	public static List<Line> page(ChronicleEntry entry, int width,
		ToIntFunction<String> bodyWidth, ToIntFunction<String> headingWidth)
	{
		return page(entry, null, width, bodyWidth, headingWidth);
	}

	/**
	 * The same, with the page's own number under the plate, a note in the
	 * margin before the summary, when there is one ({@code tally} null when
	 * not).
	 */
	public static List<Line> page(ChronicleEntry entry, String tally, int width,
		ToIntFunction<String> bodyWidth, ToIntFunction<String> headingWidth)
	{
		List<Line> lines = new ArrayList<>();
		if (entry == null)
		{
			return lines;
		}
		plate(lines, entry);
		paragraphs(lines, tally, width, bodyWidth, Style.TALLY, 0);
		paragraphs(lines, entry.summary, width, bodyWidth, Style.BODY, 0);
		for (ChronicleEntry.Section section : entry.sectionsOrEmpty())
		{
			heading(lines, section.heading, width, headingWidth);
			paragraphs(lines, section.body, width, bodyWidth, Style.BODY, 0);
		}
		// Headed apart from every section above them: "Worth knowing" twice on
		// one page read as a recap, and got written as one.
		list(lines, "People", entry.peopleOrEmpty(), width, bodyWidth, headingWidth);
		list(lines, "Places", entry.placesOrEmpty(), width, bodyWidth, headingWidth);
		list(lines, "Particulars", entry.detailsOrEmpty(), width, bodyWidth, headingWidth);
		return lines;
	}

	private static void heading(List<Line> lines, String text, int width, ToIntFunction<String> measure)
	{
		if (text == null || text.isEmpty())
		{
			return;
		}
		boolean first = true;
		for (String wrapped : wrap(text, width, measure))
		{
			lines.add(new Line(wrapped, Style.HEADING, first && !lines.isEmpty() ? PARAGRAPH_GAP * 2 : 0));
			first = false;
		}
	}

	private static void paragraphs(List<Line> lines, String text, int width,
		ToIntFunction<String> measure, Style style, int indent)
	{
		if (text == null || text.isEmpty())
		{
			return;
		}
		for (String paragraph : text.split("\n\\s*\n"))
		{
			boolean first = true;
			for (String wrapped : wrap(paragraph.replace('\n', ' ').trim(), width - indent, measure))
			{
				lines.add(new Line(wrapped, style, first && !lines.isEmpty() ? PARAGRAPH_GAP : 0));
				first = false;
			}
		}
	}

	private static void list(List<Line> lines, String title, List<String> items, int width,
		ToIntFunction<String> bodyWidth, ToIntFunction<String> headingWidth)
	{
		if (items.isEmpty())
		{
			return;
		}
		heading(lines, title, width, headingWidth);
		// Lines after an item's first sit under its text, so they have that much less room.
		int indent = bodyWidth.applyAsInt("  ");
		for (String item : items)
		{
			boolean first = true;
			for (String wrapped : wrap("- " + item, width, width - indent, bodyWidth))
			{
				lines.add(new Line(first ? wrapped : "  " + wrapped, Style.NOTE, 0));
				first = false;
			}
		}
	}
}

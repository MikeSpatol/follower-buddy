package com.follower.chronicle;

import com.follower.ui.GameFont;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The geometry of the chronicle window, measured off the game's own
 * Collection Log, and the page maths - wrapping, scrolling, hit rectangles -
 * kept apart from the drawing so it can be tested without a canvas.
 *
 * <p>Everything is in the window's own pixels with (0, 0) at its top-left
 * corner; the overlay adds where the window sits on the canvas. The window
 * is the Collection Log's 500 by 314: a steel rim of 25 by 30 corners and
 * 6 pixel edges round a tiled stone backing, the title across the top, the
 * close button top right, a strip of tabs, a list down the left with its
 * scrollbar, and the page on the right with its own.
 */
public final class ChronicleLayout
{
	public static final int WIDTH = 500;
	public static final int HEIGHT = 314;

	/** The rim: corner sprites and the strip between them. */
	public static final int CORNER_W = 25;
	public static final int CORNER_H = 30;
	public static final int EDGE = 6;

	/** The title's baseline and the close button's square. */
	public static final int TITLE_BASELINE = 24;
	public static final Rectangle CLOSE = new Rectangle(WIDTH - 6 - 22, 7, 21, 21);

	/** The tab strip. */
	public static final int TABS_TOP = 36;
	public static final int TABS_HEIGHT = 20;
	public static final int TABS_LEFT = 8;
	public static final int TABS_RIGHT = WIDTH - 8;
	public static final int TAB_GAP = 2;

	/** The list and its scrollbar. */
	public static final Rectangle LIST = new Rectangle(8, 58, 182, 248);
	public static final int ROW_HEIGHT = 15;
	public static final int SCROLLBAR_W = 16;
	public static final Rectangle LIST_SCROLL = new Rectangle(LIST.x + LIST.width, LIST.y, SCROLLBAR_W, LIST.height);

	/** The page: the header block, a divider, then the scrolling body. */
	public static final Rectangle PAGE = new Rectangle(210, 58, WIDTH - 210 - 8 - SCROLLBAR_W, 248);
	public static final Rectangle PAGE_SCROLL = new Rectangle(PAGE.x + PAGE.width, PAGE.y, SCROLLBAR_W, PAGE.height);
	public static final int PAGE_PAD = 6;
	public static final int HEADER_BASELINE = PAGE.y + 12;
	public static final int LINE_HEIGHT = 14;
	public static final int PARAGRAPH_GAP = 6;

	/** The vanilla colours, sampled off the real window. */
	public static final int TITLE_ORANGE = 0xff981f;
	public static final int READABLE_GREEN = 0x0dc10d;
	public static final int LOCKED_GREY = 0x8f8f8f;
	public static final int HOVER_WHITE = 0xffffff;
	public static final int BODY_WHITE = 0xffffff;
	public static final int NOTE_PALE = 0xd8d0c0;
	public static final int SELECTED_ROW = 0x70695f;
	public static final int TAB_FACE = 0x28251e;
	public static final int TAB_FACE_SELECTED = 0x3e3529;
	public static final int TAB_EDGE = 0x1a1813;
	public static final int BACKING = 0x483e33;
	public static final int DIVIDER = 0x585040;
	public static final int TRACK = 0x252019;

	private ChronicleLayout()
	{
	}

	/** The tabs, left to right, evenly across the strip. */
	public static Rectangle[] tabs(int count)
	{
		Rectangle[] out = new Rectangle[count];
		int span = TABS_RIGHT - TABS_LEFT - TAB_GAP * (count - 1);
		int x = TABS_LEFT;
		for (int i = 0; i < count; i++)
		{
			int w = span / count + (i < span % count ? 1 : 0);
			out[i] = new Rectangle(x, TABS_TOP, w, TABS_HEIGHT);
			x += w + TAB_GAP;
		}
		return out;
	}

	/** How many rows the list shows at once. */
	public static int visibleRows()
	{
		return LIST.height / ROW_HEIGHT;
	}

	/** The rectangle of the nth visible row. */
	public static Rectangle row(int visibleIndex)
	{
		return new Rectangle(LIST.x, LIST.y + visibleIndex * ROW_HEIGHT, LIST.width, ROW_HEIGHT);
	}

	/** A scroll offset, kept inside what there is to scroll to. */
	public static int clampScroll(int offset, int total, int visible)
	{
		int most = Math.max(0, total - visible);
		return Math.max(0, Math.min(offset, most));
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
		int top = trackTop + travel * offset / Math.max(1, total - visible);
		return new int[]{top, height};
	}

	/**
	 * Greedy word wrap measured with the game's own advances, the dialog's
	 * recipe: a single word longer than the column is left to overflow.
	 */
	public static List<String> wrap(String text, int maxWidth, ToIntFunction<String> width)
	{
		List<String> out = new ArrayList<>();
		if (text == null)
		{
			return out;
		}
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (width.applyAsInt(candidate) > maxWidth && line.length() > 0)
			{
				out.add(line.toString());
				line = new StringBuilder(word);
			}
			else
			{
				line = new StringBuilder(candidate);
			}
		}
		if (line.length() > 0)
		{
			out.add(line.toString());
		}
		return out;
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
		HEADING, BODY, NOTE
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
		List<Line> lines = new ArrayList<>();
		if (entry == null)
		{
			return lines;
		}
		paragraphs(lines, entry.summary, width, bodyWidth, Style.BODY, 0);
		for (ChronicleEntry.Section section : entry.sectionsOrEmpty())
		{
			heading(lines, section.heading, width, headingWidth);
			paragraphs(lines, section.body, width, bodyWidth, Style.BODY, 0);
		}
		list(lines, "Who was involved", entry.peopleOrEmpty(), width, bodyWidth, headingWidth);
		list(lines, "Where", entry.placesOrEmpty(), width, bodyWidth, headingWidth);
		list(lines, "Worth knowing", entry.detailsOrEmpty(), width, bodyWidth, headingWidth);
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
		// A section already headed the same way in the data is not headed
		// twice: the summary lists are the short form of the long sections.
		heading(lines, title, width, headingWidth);
		for (String item : items)
		{
			boolean first = true;
			for (String wrapped : wrap("- " + item, width, bodyWidth))
			{
				lines.add(new Line(first ? wrapped : "  " + wrapped, Style.NOTE, 0));
				first = false;
			}
		}
	}
}

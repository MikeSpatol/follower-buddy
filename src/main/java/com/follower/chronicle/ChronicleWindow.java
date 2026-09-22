package com.follower.chronicle;

import com.follower.ui.GameFont;
import com.follower.ui.GameFontRepository;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.SpriteID;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The chronicle: the scribe's book of what has happened, opened from the
 * follower's right-click menu and drawn to look like the game's own
 * Collection Log.
 *
 * <p>Drawn as an overlay above the widgets, from the client's own sprites
 * (the steel rim, the stone backing, the close button and the scrollbars)
 * and its own bitmap fonts, because a plugin cannot open a real interface
 * and anything less than the real pieces reads as a mod. Sprites arrive a
 * frame or two after they are asked for, so every piece has a flat stand-in
 * for the first frames, as the dialog does with the chatbox.
 *
 * <p>Input follows the dialog: a mouse listener that consumes every press
 * inside the window and lets a press outside it fall through to the game,
 * which closes the window the way any click closes a real interface; the
 * wheel scrolls whichever side it is over; Escape closes.
 */
@Slf4j
@Singleton
public class ChronicleWindow extends Overlay
{
	/** The window's name, in the title and on the menu. */
	public static final String NAME = "Chronicle";

	/** The tabs, in order, and the kinds behind them. */
	static final String[] TAB_NAMES = {"Quests", "Bosses", "Enemies", "Places", "World"};
	static final String[][] TAB_KINDS = {
		{"quest"}, {"boss"}, {"enemy"}, {"location"}, {"faction", "world"},
	};

	private final Client client;
	private final MouseManager mouseManager;
	private final KeyManager keyManager;
	private final SpriteManager spriteManager;
	private final GameFontRepository gameFonts;
	private final ChronicleLoader loader;

	private ChronicleProgress progress;

	private volatile boolean open;
	private volatile int tab;
	private volatile int listScroll;
	private volatile int pageScroll;
	private volatile String selectedId;

	/** Where the window was last drawn, in canvas pixels; the hit region. */
	private final Rectangle bounds = new Rectangle();
	private Rectangle[] tabRects = new Rectangle[0];
	private List<ChronicleEntry> listed = new ArrayList<>();
	private List<ChronicleLayout.Line> pageLines = new ArrayList<>();
	private String pageLinesFor;
	private int pageVisibleLines;

	@Inject
	public ChronicleWindow(Client client, MouseManager mouseManager, KeyManager keyManager,
		SpriteManager spriteManager, GameFontRepository gameFonts, ChronicleLoader loader)
	{
		this.client = client;
		this.mouseManager = mouseManager;
		this.keyManager = keyManager;
		this.spriteManager = spriteManager;
		this.gameFonts = gameFonts;
		this.loader = loader;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		// Above the dialog, which sits at the default: a book opened over a
		// conversation is on top of it.
		setPriority(Overlay.PRIORITY_HIGH);
	}

	/** The plugin builds the progress with its own memory; the window borrows it. */
	public void attach(ChronicleProgress progress)
	{
		this.progress = progress;
	}

	public void register()
	{
		mouseManager.registerMouseListener(clickAdapter);
		mouseManager.registerMouseWheelListener(wheelAdapter);
		keyManager.registerKeyListener(keyAdapter);
	}

	public void unregister()
	{
		mouseManager.unregisterMouseListener(clickAdapter);
		mouseManager.unregisterMouseWheelListener(wheelAdapter);
		keyManager.unregisterKeyListener(keyAdapter);
	}

	public boolean isOpen()
	{
		return open;
	}

	/** Opens on the current tab and page; the first time, on the first tab. */
	public void open()
	{
		if (progress != null)
		{
			progress.refresh(client.getTickCount());
		}
		open = true;
	}

	public void close()
	{
		open = false;
	}

	/** Opens straight onto one page, for the follower to show something it wrote. */
	public void openAt(String entryId)
	{
		ChronicleEntry entry = loader.get(entryId);
		if (entry != null)
		{
			for (int i = 0; i < TAB_KINDS.length; i++)
			{
				for (String kind : TAB_KINDS[i])
				{
					if (kind.equals(entry.kindOrEmpty()))
					{
						tab = i;
					}
				}
			}
			selectedId = entryId;
			pageScroll = 0;
		}
		open();
	}

	// ----------------------------------------------------------------- input

	private final MouseAdapter clickAdapter = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			if (!open)
			{
				return event;
			}
			if (!bounds.contains(event.getPoint()))
			{
				// As any click away from a real interface: it closes, and the
				// click still does whatever it was aimed at.
				close();
				return event;
			}
			event.consume();
			if (!javax.swing.SwingUtilities.isLeftMouseButton(event))
			{
				return event;
			}
			int x = event.getX() - bounds.x;
			int y = event.getY() - bounds.y;
			if (ChronicleLayout.CLOSE.contains(x, y))
			{
				close();
				return event;
			}
			for (int i = 0; i < tabRects.length; i++)
			{
				if (tabRects[i].contains(x, y))
				{
					if (tab != i)
					{
						tab = i;
						listScroll = 0;
						pageScroll = 0;
						selectedId = null;
					}
					return event;
				}
			}
			if (ChronicleLayout.LIST.contains(x, y))
			{
				int row = (y - ChronicleLayout.LIST.y) / ChronicleLayout.ROW_HEIGHT + listScroll;
				if (row >= 0 && row < listed.size())
				{
					String id = listed.get(row).id;
					if (!id.equals(selectedId))
					{
						selectedId = id;
						pageScroll = 0;
					}
				}
				return event;
			}
			if (ChronicleLayout.LIST_SCROLL.contains(x, y))
			{
				scrollList(y < ChronicleLayout.LIST_SCROLL.y + 16 ? -1
					: y >= ChronicleLayout.LIST_SCROLL.y + ChronicleLayout.LIST_SCROLL.height - 16 ? 1 : 0);
				return event;
			}
			if (ChronicleLayout.PAGE_SCROLL.contains(x, y))
			{
				scrollPage(y < ChronicleLayout.PAGE_SCROLL.y + 16 ? -1
					: y >= ChronicleLayout.PAGE_SCROLL.y + ChronicleLayout.PAGE_SCROLL.height - 16 ? 1 : 0);
			}
			return event;
		}
	};

	private final MouseWheelListener wheelAdapter = new MouseWheelListener()
	{
		@Override
		public MouseWheelEvent mouseWheelMoved(MouseWheelEvent event)
		{
			if (!open || !bounds.contains(event.getPoint()))
			{
				return event;
			}
			event.consume();
			int x = event.getX() - bounds.x;
			int step = event.getWheelRotation() > 0 ? 1 : -1;
			if (x < ChronicleLayout.PAGE.x)
			{
				scrollList(step);
			}
			else
			{
				scrollPage(step * 3);
			}
			return event;
		}
	};

	private final KeyListener keyAdapter = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent e)
		{
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			if (open && e.getKeyCode() == KeyEvent.VK_ESCAPE)
			{
				close();
				e.consume();
			}
		}
	};

	private void scrollList(int by)
	{
		listScroll = ChronicleLayout.clampScroll(listScroll + by, listed.size(),
			ChronicleLayout.visibleRows());
	}

	private void scrollPage(int by)
	{
		pageScroll = ChronicleLayout.clampScroll(pageScroll + by, pageLines.size(),
			Math.max(1, pageVisibleLines));
	}

	// --------------------------------------------------------------- drawing

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!open)
		{
			return null;
		}
		// Centred over the 3D view, where the game puts its own windows.
		int x = client.getViewportXOffset()
			+ Math.max(0, (client.getViewportWidth() - ChronicleLayout.WIDTH) / 2);
		int y = client.getViewportYOffset()
			+ Math.max(0, (client.getViewportHeight() - ChronicleLayout.HEIGHT) / 2);
		bounds.setBounds(x, y, ChronicleLayout.WIDTH, ChronicleLayout.HEIGHT);

		Object textAa = graphics.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		Object shapeAa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
			RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		graphics.translate(x, y);
		try
		{
			GameFont body = gameFonts.getByName("p12_full");
			GameFont bold = gameFonts.getByName("b12_full");
			listed = currentList();
			ChronicleEntry selected = currentPage();

			drawBacking(graphics);
			drawRim(graphics);
			drawTitle(graphics, bold);
			drawCloseButton(graphics);
			drawTabs(graphics, body);
			drawList(graphics, body);
			drawPage(graphics, selected, body, bold);
		}
		finally
		{
			graphics.translate(-x, -y);
			graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, textAa);
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, shapeAa);
		}
		return null;
	}

	private List<ChronicleEntry> currentList()
	{
		List<ChronicleEntry> out = new ArrayList<>();
		int which = Math.max(0, Math.min(tab, TAB_KINDS.length - 1));
		for (String kind : TAB_KINDS[which])
		{
			out.addAll(loader.ofKind(kind));
		}
		return out;
	}

	private ChronicleEntry currentPage()
	{
		if (selectedId != null)
		{
			for (ChronicleEntry entry : listed)
			{
				if (entry.id.equals(selectedId))
				{
					return entry;
				}
			}
		}
		// Nothing chosen on this tab yet: the first page, as the Collection
		// Log opens on the first category.
		ChronicleEntry first = listed.isEmpty() ? null : listed.get(0);
		selectedId = first == null ? null : first.id;
		return first;
	}

	private BufferedImage sprite(int id)
	{
		try
		{
			return spriteManager.getSprite(id, 0);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	private void drawBacking(Graphics2D g)
	{
		BufferedImage tile = sprite(SpriteID.TRADEBACKING);
		int inner = ChronicleLayout.EDGE;
		Shape clip = g.getClip();
		g.clipRect(inner, inner, ChronicleLayout.WIDTH - inner * 2, ChronicleLayout.HEIGHT - inner * 2);
		if (tile == null)
		{
			g.setColor(new Color(ChronicleLayout.BACKING));
			g.fillRect(0, 0, ChronicleLayout.WIDTH, ChronicleLayout.HEIGHT);
		}
		else
		{
			for (int ty = 0; ty < ChronicleLayout.HEIGHT; ty += tile.getHeight())
			{
				for (int tx = 0; tx < ChronicleLayout.WIDTH; tx += tile.getWidth())
				{
					g.drawImage(tile, tx, ty, null);
				}
			}
		}
		g.setClip(clip);
	}

	private void drawRim(Graphics2D g)
	{
		int w = ChronicleLayout.WIDTH;
		int h = ChronicleLayout.HEIGHT;
		int cw = ChronicleLayout.CORNER_W;
		int ch = ChronicleLayout.CORNER_H;
		BufferedImage top = sprite(SpriteID.Steelborder2.EDGE_TOP);
		BufferedImage right = sprite(SpriteID.Steelborder2.EDGE_RIGHT);
		if (top == null || right == null)
		{
			g.setColor(new Color(0x2d2b24));
			g.fillRect(0, 0, w, ChronicleLayout.EDGE);
			g.fillRect(0, h - ChronicleLayout.EDGE, w, ChronicleLayout.EDGE);
			g.fillRect(0, 0, ChronicleLayout.EDGE, h);
			g.fillRect(w - ChronicleLayout.EDGE, 0, ChronicleLayout.EDGE, h);
		}
		else
		{
			Shape clip = g.getClip();
			// Top and bottom strips between the corners; the bottom is the
			// top flipped, there being no bottom sprite in the game.
			g.clipRect(cw, 0, w - cw * 2, h);
			for (int tx = cw; tx < w - cw; tx += top.getWidth())
			{
				g.drawImage(top, tx, 0, null);
				g.drawImage(top, tx, h, top.getWidth(), -top.getHeight(), null);
			}
			g.setClip(clip);
			g.clipRect(0, ch, w, h - ch * 2);
			for (int ty = ch; ty < h - ch; ty += right.getHeight())
			{
				g.drawImage(right, w - right.getWidth(), ty, null);
				g.drawImage(right, right.getWidth(), ty, -right.getWidth(), right.getHeight(), null);
			}
			g.setClip(clip);
		}
		drawAt(g, SpriteID.Steelborder.TOP_LEFT, 0, 0);
		drawAt(g, SpriteID.Steelborder.TOP_RIGHT, w - cw, 0);
		drawAt(g, SpriteID.Steelborder.BOTTOM_LEFT, 0, h - ch);
		drawAt(g, SpriteID.Steelborder.BOTTOM_RIGHT, w - cw, h - ch);
	}

	private void drawAt(Graphics2D g, int spriteId, int x, int y)
	{
		BufferedImage image = sprite(spriteId);
		if (image != null)
		{
			g.drawImage(image, x, y, null);
		}
	}

	private void drawTitle(Graphics2D g, GameFont bold)
	{
		int[] tally = progress == null ? new int[]{0, loader.getEntries().size()} : progress.tally();
		String title = NAME + " - " + tally[0] + "/" + tally[1];
		text(g, bold, true, title, ChronicleLayout.WIDTH / 2, ChronicleLayout.TITLE_BASELINE,
			ChronicleLayout.TITLE_ORANGE, true);
	}

	private void drawCloseButton(Graphics2D g)
	{
		Rectangle close = ChronicleLayout.CLOSE;
		boolean hover = hovered(close);
		BufferedImage image = sprite(hover
			? SpriteID.SteelborderCloseButton._1 : SpriteID.SteelborderCloseButton._0);
		if (image != null)
		{
			g.drawImage(image, close.x, close.y, null);
		}
		else
		{
			g.setColor(new Color(hover ? 0x8a8070 : 0x6a6050));
			g.fillRect(close.x, close.y, close.width, close.height);
		}
	}

	private void drawTabs(Graphics2D g, GameFont body)
	{
		tabRects = ChronicleLayout.tabs(TAB_NAMES.length);
		for (int i = 0; i < tabRects.length; i++)
		{
			Rectangle r = tabRects[i];
			boolean selected = i == tab;
			g.setColor(new Color(selected ? ChronicleLayout.TAB_FACE_SELECTED : ChronicleLayout.TAB_FACE));
			g.fillRect(r.x, r.y, r.width, r.height);
			g.setColor(new Color(ChronicleLayout.TAB_EDGE));
			g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
			int colour = hovered(r) && !selected ? ChronicleLayout.HOVER_WHITE : ChronicleLayout.TITLE_ORANGE;
			text(g, body, false, TAB_NAMES[i], r.x + r.width / 2, r.y + 14, colour, true);
		}
	}

	private void drawList(Graphics2D g, GameFont body)
	{
		int visible = ChronicleLayout.visibleRows();
		listScroll = ChronicleLayout.clampScroll(listScroll, listed.size(), visible);
		Shape clip = g.getClip();
		g.clipRect(ChronicleLayout.LIST.x, ChronicleLayout.LIST.y, ChronicleLayout.LIST.width,
			ChronicleLayout.LIST.height);
		for (int i = 0; i < visible && i + listScroll < listed.size(); i++)
		{
			ChronicleEntry entry = listed.get(i + listScroll);
			Rectangle row = ChronicleLayout.row(i);
			boolean selected = entry.id.equals(selectedId);
			if (selected)
			{
				g.setColor(new Color(ChronicleLayout.SELECTED_ROW));
				g.fillRect(row.x, row.y, row.width, row.height);
			}
			boolean readable = progress != null && progress.isReadable(entry);
			int colour = hovered(row) ? ChronicleLayout.HOVER_WHITE
				: readable ? ChronicleLayout.READABLE_GREEN : ChronicleLayout.LOCKED_GREY;
			text(g, body, false, entry.name, row.x + 3, row.y + 12, colour, false);
		}
		g.setClip(clip);
		drawScrollbar(g, ChronicleLayout.LIST_SCROLL, listScroll, listed.size(), visible);
	}

	private void drawPage(Graphics2D g, ChronicleEntry entry, GameFont body, GameFont bold)
	{
		Rectangle page = ChronicleLayout.PAGE;
		int left = page.x + ChronicleLayout.PAGE_PAD;
		int width = page.width - ChronicleLayout.PAGE_PAD * 2;
		if (entry == null)
		{
			text(g, body, false, "Nothing written here yet.", left, ChronicleLayout.HEADER_BASELINE,
				ChronicleLayout.LOCKED_GREY, false);
			pageLines = new ArrayList<>();
			pageLinesFor = null;
			return;
		}

		// The header: the name, then what it is and whether it is written.
		boolean readable = progress != null && progress.isReadable(entry);
		text(g, bold, true, entry.name, left, ChronicleLayout.HEADER_BASELINE, ChronicleLayout.TITLE_ORANGE, false);
		int y = ChronicleLayout.HEADER_BASELINE + ChronicleLayout.LINE_HEIGHT;
		String kind = kindLabel(entry.kindOrEmpty());
		text(g, body, false, kind + ": ", left, y, ChronicleLayout.TITLE_ORANGE, false);
		int after = left + measure(g, body, false, kind + ": ");
		text(g, body, false, readable ? "Written" : "Not yet written", after, y,
			readable ? ChronicleLayout.READABLE_GREEN : ChronicleLayout.LOCKED_GREY, false);
		y += 6;
		g.setColor(new Color(ChronicleLayout.DIVIDER));
		g.fillRect(page.x, y, page.width, 1);
		int bodyTop = y + 4;

		// The body, wrapped once per page and cached until the page changes.
		int bodyHeight = page.y + page.height - bodyTop;
		pageVisibleLines = Math.max(1, bodyHeight / ChronicleLayout.LINE_HEIGHT);
		if (!readable)
		{
			pageLines = new ArrayList<>();
			pageLinesFor = null;
			List<String> hint = ChronicleLayout.wrap(unwrittenHint(entry), width,
				s -> measure(g, body, false, s));
			int hy = bodyTop + ChronicleLayout.LINE_HEIGHT;
			for (String line : hint)
			{
				text(g, body, false, line, left, hy, ChronicleLayout.LOCKED_GREY, false);
				hy += ChronicleLayout.LINE_HEIGHT;
			}
			drawScrollbar(g, ChronicleLayout.PAGE_SCROLL, 0, 0, 1);
			return;
		}
		if (!entry.id.equals(pageLinesFor))
		{
			pageLines = ChronicleLayout.page(entry, width,
				s -> measure(g, body, false, s), s -> measure(g, bold, true, s));
			pageLinesFor = entry.id;
			pageScroll = 0;
		}
		pageScroll = ChronicleLayout.clampScroll(pageScroll, pageLines.size(), pageVisibleLines);

		Shape clip = g.getClip();
		g.clipRect(page.x, bodyTop, page.width, bodyHeight);
		int ly = bodyTop + ChronicleLayout.LINE_HEIGHT - 2;
		for (int i = pageScroll; i < pageLines.size(); i++)
		{
			ChronicleLayout.Line line = pageLines.get(i);
			if (i > pageScroll)
			{
				ly += line.gapAbove;
			}
			if (ly - ChronicleLayout.LINE_HEIGHT > page.y + page.height)
			{
				break;
			}
			switch (line.style)
			{
				case HEADING:
					text(g, bold, true, line.text, left, ly, ChronicleLayout.TITLE_ORANGE, false);
					break;
				case NOTE:
					text(g, body, false, line.text, left, ly, ChronicleLayout.NOTE_PALE, false);
					break;
				default:
					text(g, body, false, line.text, left, ly, ChronicleLayout.BODY_WHITE, false);
					break;
			}
			ly += ChronicleLayout.LINE_HEIGHT;
		}
		g.setClip(clip);
		drawScrollbar(g, ChronicleLayout.PAGE_SCROLL, pageScroll, pageLines.size(), pageVisibleLines);
	}

	/** What the locked page says instead of its text. */
	static String unwrittenHint(ChronicleEntry entry)
	{
		ChronicleEntry.ChronicleUnlock unlock = entry.unlock;
		if (unlock == null)
		{
			return "The scribe has nothing on this yet.";
		}
		if (unlock.quest != null)
		{
			return "The scribe will write this up once the quest is done.";
		}
		if (unlock.questBegun != null)
		{
			return "The scribe will write this up once the quest is under way.";
		}
		if (unlock.npcs != null && !unlock.npcs.isEmpty())
		{
			return "The scribe has not met one yet.";
		}
		if (unlock.regions != null && !unlock.regions.isEmpty())
		{
			return "The scribe has not been there yet.";
		}
		return "The scribe has nothing on this yet.";
	}

	static String kindLabel(String kind)
	{
		switch (kind)
		{
			case "quest":
				return "Quest";
			case "boss":
				return "Boss";
			case "enemy":
				return "Enemy";
			case "location":
				return "Place";
			case "faction":
				return "Faction";
			case "world":
				return "The world";
			default:
				return "Page";
		}
	}

	private void drawScrollbar(Graphics2D g, Rectangle bar, int offset, int total, int visible)
	{
		BufferedImage up = sprite(SpriteID.ScrollbarV2.ARROW_UP);
		BufferedImage down = sprite(SpriteID.ScrollbarV2.ARROW_DOWN);
		BufferedImage track = sprite(SpriteID.ScrollbarDraggerV2.TRACK);
		BufferedImage thumbTop = sprite(SpriteID.ScrollbarDraggerV2.TOP);
		BufferedImage thumbMid = sprite(SpriteID.ScrollbarDraggerV2.MIDDLE);
		BufferedImage thumbBottom = sprite(SpriteID.ScrollbarDraggerV2.BOTTOM);

		// The track, then the arrows over its ends.
		Shape clip = g.getClip();
		g.clipRect(bar.x, bar.y + 16, bar.width, bar.height - 32);
		if (track == null)
		{
			g.setColor(new Color(ChronicleLayout.TRACK));
			g.fillRect(bar.x, bar.y, bar.width, bar.height);
		}
		else
		{
			for (int ty = bar.y + 16; ty < bar.y + bar.height - 16; ty += track.getHeight())
			{
				g.drawImage(track, bar.x, ty, null);
			}
		}
		int[] thumb = ChronicleLayout.thumb(bar, offset, total, visible);
		int top = bar.y + thumb[0];
		int height = thumb[1];
		if (thumbMid == null || thumbTop == null || thumbBottom == null)
		{
			g.setColor(new Color(0x6a5c43));
			g.fillRect(bar.x + 1, top, bar.width - 2, height);
		}
		else
		{
			for (int ty = top; ty < top + height; ty += thumbMid.getHeight())
			{
				g.drawImage(thumbMid, bar.x, ty, null);
			}
			g.drawImage(thumbTop, bar.x, top, null);
			g.drawImage(thumbBottom, bar.x, top + height - thumbBottom.getHeight(), null);
		}
		g.setClip(clip);
		if (up != null && down != null)
		{
			g.drawImage(up, bar.x, bar.y, null);
			g.drawImage(down, bar.x, bar.y + bar.height - 16, null);
		}
		else
		{
			g.setColor(new Color(0x6a5c43));
			g.fillRect(bar.x, bar.y, bar.width, 16);
			g.fillRect(bar.x, bar.y + bar.height - 16, bar.width, 16);
		}
	}

	// ------------------------------------------------------------------ text

	private boolean hovered(Rectangle inWindow)
	{
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		return mouse != null && inWindow.contains(mouse.getX() - bounds.x, mouse.getY() - bounds.y);
	}

	private int measure(Graphics2D g, GameFont font, boolean bold, String text)
	{
		if (font != null)
		{
			return font.stringWidth(text);
		}
		g.setFont(bold ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont());
		return g.getFontMetrics().stringWidth(text);
	}

	/**
	 * Draws with the game's glyphs when they are loaded, else RuneLite's
	 * recreation of the font; {@code y} is the baseline, and the black copy
	 * one pixel down and right is the shadow every interface text has.
	 */
	private void text(Graphics2D g, GameFont font, boolean bold, String s, int x, int y,
		int rgb, boolean centred)
	{
		if (s == null)
		{
			return;
		}
		if (font != null)
		{
			if (centred)
			{
				font.drawCenteredBaseline(g, s, x, y, rgb, true);
			}
			else
			{
				font.drawBaseline(g, s, x, y, rgb, true);
			}
			return;
		}
		g.setFont(bold ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont());
		int left = centred ? x - g.getFontMetrics().stringWidth(s) / 2 : x;
		g.setColor(Color.BLACK);
		g.drawString(s, left + 1, y + 1);
		g.setColor(new Color(rgb));
		g.drawString(s, left, y);
	}
}

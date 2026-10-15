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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Quest;
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
 * (the steel rim, the stone backing, the divider, the close button and the
 * scrollbars) and its own bitmap fonts, because a plugin cannot open a real
 * interface and anything less than the real pieces reads as a mod. Every
 * position and colour was measured off the real window. Sprites arrive a
 * frame or two after they are asked for, so every piece has a flat stand-in
 * for the first frames, as the dialog does with the chatbox.
 *
 * <p>Input follows the dialog: a mouse listener that consumes every press
 * inside the window and lets a press outside it fall through to the game,
 * which closes the window the way any click closes a real interface; the
 * wheel scrolls whichever side it is over; the thumbs drag; Escape closes.
 * And the buttons wait for the tick, as the game's do: a tab, a row or the
 * close button takes effect on the next game tick through a {@link
 * TickGate}, and a second press before then is ignored. Scrolling is not
 * gated, because in the game a scrollbar is the client's own.
 *
 * <p>A press on a page's plate opens it across the whole of the window
 * below the tabs, to be looked at properly; a press anywhere on it, a tab,
 * or Escape puts it back.
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

	/** The plate's frame, a portrait's backdrop top to foot, and paper not yet drawn on. */
	private static final int PLATE_EDGE = 0x0e0c09;
	// Paper, as the sketches are: a black dragon on a dark backdrop is not a
	// picture of anything, and every plate reads as drawn in the same book.
	private static final int PORTRAIT_TOP = 0xe3d4b0;
	private static final int PORTRAIT_FOOT = 0xbfa97f;
	private static final int BARE_PAPER = 0xcbb994;

	/** How far above the drawing's foot the pose is pencilled on a page's plate. */
	static final int POSE_RISE = 4;

	/** How much of the drawing the strip under the pencilled pose hides, out of 255. */
	private static final int POSE_PAPER_ALPHA = 190;

	/** How much a plate under the mouse lightens, out of 255. */
	private static final int HOVER_ALPHA = 28;

	private static final int DRAG_NONE = 0;
	private static final int DRAG_LIST = 1;
	private static final int DRAG_PAGE = 2;

	private final Client client;
	private final MouseManager mouseManager;
	private final KeyManager keyManager;
	private final SpriteManager spriteManager;
	private final GameFontRepository gameFonts;
	private final ChronicleLoader loader;

	private ChronicleProgress progress;
	private SketchBook sketches;

	/** Whose book it is: the follower's name, from its settings. */
	private Supplier<String> owner = () -> null;
	private final ChroniclePortraits portraits;

	/** Which of a page's plates is showing, and the arrows that turn them. */
	private volatile int plateIndex;
	private String plateIndexFor;
	private final Rectangle platePrev = new Rectangle();
	private final Rectangle plateNext = new Rectangle();

	/** The plate opened across the window, and the part of the page's plate a press can land on. */
	private volatile boolean enlarged;
	private final Rectangle plateShown = new Rectangle();

	private volatile boolean open;
	private volatile int tab;
	private volatile int listScroll;
	private volatile int pageScroll;
	private volatile String selectedId;

	/** The buttons' wait for the tick. */
	private final TickGate gate = new TickGate();

	/** A thumb being dragged, and where on it the mouse took hold. */
	private volatile int dragging = DRAG_NONE;
	private volatile int dragGrab;

	/** Where the window was last drawn, in canvas pixels; the hit region. */
	private final Rectangle bounds = new Rectangle();
	private Rectangle[] tabRects = new Rectangle[0];
	private final Rectangle listThumb = new Rectangle();
	private final Rectangle pageThumb = new Rectangle();
	private List<ChronicleEntry> listed = new ArrayList<>();
	private List<ChronicleLayout.Line> pageLines = new ArrayList<>();
	private String pageLinesFor;
	private String pageLinesOf;
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
		this.portraits = new ChroniclePortraits(client);
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

	/** And the sketches, which live with the rest of the follower's files. */
	public void attach(SketchBook sketches)
	{
		this.sketches = sketches;
	}

	/** The follower's name, for the title: the book is signed. */
	public void setOwner(Supplier<String> owner)
	{
		this.owner = owner == null ? () -> null : owner;
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
		enlarged = false;
		dragging = DRAG_NONE;
		gate.clear();
	}

	/** Opens on the next game tick, as a right-click op on a real thing would. */
	public void openNextTick()
	{
		gate.offer(this::open);
	}

	/** The game tick: whatever button was pressed since the last one lands now. */
	public void tick()
	{
		gate.tick();
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

	final MouseAdapter clickAdapter = new MouseAdapter()
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
				// As any click away from a real interface: it closes, on the
				// tick the click's own action processes, and the click still
				// does whatever it was aimed at.
				gate.offer(ChronicleWindow.this::close);
				return event;
			}
			event.consume();
			if (!javax.swing.SwingUtilities.isLeftMouseButton(event))
			{
				return event;
			}
			int x = event.getX() - bounds.x;
			int y = event.getY() - bounds.y;
			// The buttons: one press in flight at a time, landing on the tick.
			// The scrollbars below are not buttons and answer at once.
			if (ChronicleLayout.CLOSE.contains(x, y))
			{
				gate.offer(ChronicleWindow.this::close);
				return event;
			}
			for (int i = 0; i < tabRects.length; i++)
			{
				if (tabRects[i].contains(x, y))
				{
					final int chosen = i;
					gate.offer(() -> showTab(chosen));
					return event;
				}
			}
			if (platePrev.contains(x, y))
			{
				gate.offer(() -> turnPlate(-1));
				return event;
			}
			if (plateNext.contains(x, y))
			{
				gate.offer(() -> turnPlate(1));
				return event;
			}
			if (enlarged)
			{
				// Everything but the tabs, the arrows and the close button is
				// the picture's now; a press anywhere on it puts it back.
				gate.offer(() -> enlarged = false);
				return event;
			}
			if (plateShown.contains(x, y))
			{
				gate.offer(() -> enlarged = true);
				return event;
			}
			if (ChronicleLayout.LIST.contains(x, y))
			{
				int row = (y - ChronicleLayout.ROW_BAND_TOP) / ChronicleLayout.ROW_HEIGHT + listScroll;
				if (row >= 0 && row < listed.size())
				{
					final String id = listed.get(row).id;
					gate.offer(() -> showPage(id));
				}
				return event;
			}
			if (ChronicleLayout.LIST_SCROLL.contains(x, y))
			{
				pressOnBar(ChronicleLayout.LIST_SCROLL, listThumb, DRAG_LIST, y, listed.size(),
					ChronicleLayout.visibleRows());
				return event;
			}
			if (ChronicleLayout.PAGE_SCROLL.contains(x, y))
			{
				pressOnBar(ChronicleLayout.PAGE_SCROLL, pageThumb, DRAG_PAGE, y, pageLines.size(),
					Math.max(1, pageVisibleLines));
			}
			return event;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent event)
		{
			if (!open || dragging == DRAG_NONE)
			{
				return event;
			}
			event.consume();
			int y = event.getY() - bounds.y;
			if (dragging == DRAG_LIST)
			{
				Rectangle bar = ChronicleLayout.LIST_SCROLL;
				listScroll = ChronicleLayout.scrollForThumb(bar, y - bar.y - dragGrab, listed.size(),
					ChronicleLayout.visibleRows());
			}
			else
			{
				Rectangle bar = ChronicleLayout.PAGE_SCROLL;
				pageScroll = ChronicleLayout.scrollForThumb(bar, y - bar.y - dragGrab, pageLines.size(),
					Math.max(1, pageVisibleLines));
			}
			return event;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent event)
		{
			if (dragging != DRAG_NONE)
			{
				dragging = DRAG_NONE;
				event.consume();
			}
			return event;
		}
	};

	private void showTab(int which)
	{
		enlarged = false;
		if (tab != which)
		{
			tab = which;
			listScroll = 0;
			pageScroll = 0;
			selectedId = null;
		}
	}

	private void turnPlate(int by)
	{
		plateIndex += by;
	}

	private void showPage(String id)
	{
		enlarged = false;
		if (!id.equals(selectedId))
		{
			selectedId = id;
			pageScroll = 0;
		}
	}

	/**
	 * A press on a scrollbar: the arrows step, the thumb takes hold for a
	 * drag, and the track either side of it moves a page toward the press.
	 */
	private void pressOnBar(Rectangle bar, Rectangle thumb, int which, int y, int total, int visible)
	{
		if (y < bar.y + 16)
		{
			scroll(which, -1, total, visible);
		}
		else if (y >= bar.y + bar.height - 16)
		{
			scroll(which, 1, total, visible);
		}
		else if (y >= thumb.y && y < thumb.y + thumb.height)
		{
			dragging = which;
			dragGrab = y - thumb.y;
		}
		else if (which == DRAG_PAGE)
		{
			// A page at a time, as far as the screen shows whole lines, so
			// no line is passed over unseen between one screen and the next.
			List<ChronicleLayout.Line> lines = pageLines;
			int body = ChronicleLayout.CONTENT_BOTTOM - ChronicleLayout.BODY_TOP;
			scroll(which, y < thumb.y
				? -ChronicleLayout.linesAbove(lines, pageScroll, body)
				: ChronicleLayout.linesThatFit(lines, pageScroll, body), total, visible);
		}
		else
		{
			scroll(which, y < thumb.y ? -visible : visible, total, visible);
		}
	}

	private void scroll(int which, int by, int total, int visible)
	{
		if (which == DRAG_LIST)
		{
			listScroll = ChronicleLayout.clampScroll(listScroll + by, total, visible);
		}
		else
		{
			pageScroll = ChronicleLayout.clampScroll(pageScroll + by, total, visible);
		}
	}

	final MouseWheelListener wheelAdapter = new MouseWheelListener()
	{
		@Override
		public MouseWheelEvent mouseWheelMoved(MouseWheelEvent event)
		{
			if (!open || !bounds.contains(event.getPoint()))
			{
				return event;
			}
			event.consume();
			if (enlarged)
			{
				return event;
			}
			int x = event.getX() - bounds.x;
			int step = event.getWheelRotation() > 0 ? 1 : -1;
			if (x < ChronicleLayout.SPLIT_X)
			{
				scroll(DRAG_LIST, step, listed.size(), ChronicleLayout.visibleRows());
			}
			else
			{
				scroll(DRAG_PAGE, step * 3, pageLines.size(), Math.max(1, pageVisibleLines));
			}
			return event;
		}
	};

	final KeyListener keyAdapter = new KeyListener()
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
				// Out of the enlarged plate first, as backing out of any view.
				if (enlarged)
				{
					enlarged = false;
				}
				else
				{
					close();
				}
				e.consume();
			}
		}
	};

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
			GameFont small = gameFonts.getByName("p11_full");
			if (small == null)
			{
				small = body;
			}
			listed = currentList();
			ChronicleEntry selected = currentPage();
			boolean big = enlarged && selected != null && !selected.platesOrEmpty().isEmpty();

			drawBacking(graphics);
			drawRim(graphics);
			drawLines(graphics, !big);
			drawTitle(graphics, bold);
			drawCloseButton(graphics);
			drawTabs(graphics, body);
			// Where this frame's plate and arrows can be pressed, handed to
			// the mouse together once the frame is drawn: cleared at the top
			// and set again after the slow drawing, a press between the two
			// would find nothing there and fall through to whatever is under.
			Rectangle prev = new Rectangle();
			Rectangle next = new Rectangle();
			Rectangle shown = new Rectangle();
			if (big)
			{
				drawEnlarged(graphics, selected, bold, small, prev, next);
			}
			else
			{
				drawList(graphics, body);
				drawPage(graphics, selected, body, bold, small, prev, next, shown);
			}
			platePrev.setBounds(prev);
			plateNext.setBounds(next);
			plateShown.setBounds(shown);
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

	/** The stone backing, tiled from the window's corner under everything. */
	private void drawBacking(Graphics2D g)
	{
		BufferedImage tile = sprite(SpriteID.TRADEBACKING);
		if (tile == null)
		{
			g.setColor(new Color(ChronicleLayout.BACKING));
			g.fillRect(0, 0, ChronicleLayout.WIDTH, ChronicleLayout.HEIGHT);
			return;
		}
		Shape clip = g.getClip();
		g.clipRect(0, 0, ChronicleLayout.WIDTH, ChronicleLayout.HEIGHT);
		for (int ty = 0; ty < ChronicleLayout.HEIGHT; ty += tile.getHeight())
		{
			for (int tx = 0; tx < ChronicleLayout.WIDTH; tx += tile.getWidth())
			{
				g.drawImage(tile, tx, ty, null);
			}
		}
		g.setClip(clip);
	}

	/**
	 * The steel rim and the divider under the title. The edges go down
	 * first and the corners over them, so the tile phase can only ever be
	 * wrong under a corner. The left and bottom strips are their own rivet
	 * sprites: the right and top ones flipped are not what the game draws.
	 */
	private void drawRim(Graphics2D g)
	{
		int w = ChronicleLayout.WIDTH;
		int h = ChronicleLayout.HEIGHT;
		int cw = ChronicleLayout.CORNER_W;
		int ch = ChronicleLayout.CORNER_H;
		int edge = ChronicleLayout.EDGE;
		BufferedImage top = sprite(SpriteID.Steelborder2.EDGE_TOP);
		BufferedImage right = sprite(SpriteID.Steelborder2.EDGE_RIGHT);
		BufferedImage left = sprite(SpriteID.Miscgraphics.IRON_RIVETS_VERTICAL);
		BufferedImage bottom = sprite(SpriteID.Miscgraphics.IRON_RIVETS_HORIZONTAL);
		BufferedImage divider = sprite(SpriteID.SteelborderDivider._0);

		Shape clip = g.getClip();
		if (top == null || right == null || left == null || bottom == null)
		{
			g.setColor(new Color(ChronicleLayout.STEEL));
			g.fillRect(0, 0, w, edge);
			g.fillRect(0, h - edge, w, edge);
			g.fillRect(0, 0, edge, h);
			g.fillRect(w - edge, 0, edge, h);
		}
		else
		{
			g.clipRect(cw, 0, w - cw * 2, h);
			for (int tx = cw; tx < w - cw; tx += top.getWidth())
			{
				g.drawImage(top, tx, 0, null);
				g.drawImage(bottom, tx, h - edge, null);
			}
			g.setClip(clip);
			g.clipRect(0, ch, w, h - ch * 2);
			for (int ty = ChronicleLayout.RIM_PHASE_Y - right.getHeight(); ty < h; ty += right.getHeight())
			{
				g.drawImage(right, w - edge, ty, null);
				g.drawImage(left, 0, ty, null);
			}
			g.setClip(clip);
		}
		drawAt(g, SpriteID.Steelborder.TOP_LEFT, 0, 0);
		drawAt(g, SpriteID.Steelborder.TOP_RIGHT, w - cw, 0);
		drawAt(g, SpriteID.Steelborder.BOTTOM_LEFT, 0, h - ch);
		drawAt(g, SpriteID.Steelborder.BOTTOM_RIGHT, w - cw, h - ch);

		// The divider between the title band and the tabs, rim to rim.
		int dy = ChronicleLayout.DIVIDER_TOP;
		int span = ChronicleLayout.CONTENT_RIGHT + 1 - ChronicleLayout.CONTENT_LEFT;
		if (divider == null)
		{
			g.setColor(new Color(ChronicleLayout.STEEL));
			g.fillRect(ChronicleLayout.CONTENT_LEFT, dy, span, edge);
		}
		else
		{
			g.clipRect(ChronicleLayout.CONTENT_LEFT, dy, span, edge);
			for (int tx = ChronicleLayout.DIVIDER_PHASE_X - divider.getWidth(); tx < w; tx += divider.getWidth())
			{
				g.drawImage(divider, tx, dy, null);
			}
			g.setClip(clip);
		}
	}

	/** The thin lines that box the list and the page, and the bar between them. */
	private void drawLines(Graphics2D g, boolean panes)
	{
		int l = ChronicleLayout.CONTENT_LEFT;
		int r = ChronicleLayout.CONTENT_RIGHT;
		int t = ChronicleLayout.CONTENT_TOP;
		int b = ChronicleLayout.CONTENT_BOTTOM;
		g.setColor(new Color(ChronicleLayout.LINE));
		g.fillRect(l, t, r - l + 1, 1);
		g.fillRect(l, b, r - l + 1, 1);
		g.fillRect(l, t, 1, b - t + 1);
		g.fillRect(r, t, 1, b - t + 1);
		if (!panes)
		{
			// An enlarged plate has the whole box to itself.
			return;
		}
		g.fillRect(ChronicleLayout.SPLIT_X, t, 2, b - t + 1);
		g.fillRect(ChronicleLayout.PAGE.x, ChronicleLayout.HEADER_RULE_Y, r - ChronicleLayout.PAGE.x, 1);
		g.fillRect(ChronicleLayout.PAGE.x, ChronicleLayout.HEADER_RULE_Y + 2, r - ChronicleLayout.PAGE.x, 1);
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
		String title = title(owner.get(), tally[0], tally[1], s -> measure(g, bold, true, s));
		text(g, bold, true, title, ChronicleLayout.WIDTH / 2, ChronicleLayout.TITLE_BASELINE,
			ChronicleLayout.TITLE_ORANGE, true);
	}

	/**
	 * "Bartholomew's Chronicle - 5/17": the book signed with the follower's
	 * name, or plain "Chronicle" when it has none or a name too long to sit
	 * centred clear of the close button.
	 */
	static String title(String owner, int written, int all, ToIntFunction<String> width)
	{
		String count = " - " + written + "/" + all;
		String name = owner == null ? "" : owner.trim();
		if (!name.isEmpty())
		{
			String signed = name + "'s " + NAME + count;
			if (width.applyAsInt(signed) <= ChronicleLayout.TITLE_ROOM)
			{
				return signed;
			}
		}
		return NAME + count;
	}

	/**
	 * When a written page went into the book, for the header's status line
	 * after its kind: "Written on the 17th of Ire of Phyrrys, year 169", the
	 * real day in the world's own calendar, giving up the year if the line
	 * has no room for it.
	 */
	static String writtenOn(long epochDay, String label, ToIntFunction<String> width, int room)
	{
		String written = "Written on " + GielinorCalendar.date(LocalDate.ofEpochDay(epochDay));
		String withYear = written + ", year " + GielinorCalendar.YEAR;
		return width.applyAsInt(label + withYear) <= room ? withYear : written;
	}

	/** The header's label for where a page was written, on the line under its date. */
	static final String WRITTEN_AT = "Written at: ";

	/**
	 * A place as a page header names it: the Locations rules describe their
	 * places ("Lumbridge town and castle", "The Grand Exchange plaza"), and a
	 * header has room only for the name ("Lumbridge", "the Grand Exchange").
	 */
	static String shortPlace(String place)
	{
		String name = place.trim();
		int and = name.indexOf(" and ");
		if (and > 0)
		{
			name = name.substring(0, and);
		}
		if (name.startsWith("The "))
		{
			name = "the " + name.substring(4);
		}
		for (String tail : new String[]{" town", " plaza", " mounds", " lobby area", " camp"})
		{
			if (name.endsWith(tail) && name.length() > tail.length() + 4)
			{
				name = name.substring(0, name.length() - tail.length());
				break;
			}
		}
		return name;
	}

	private void drawCloseButton(Graphics2D g)
	{
		Rectangle close = ChronicleLayout.CLOSE;
		boolean hover = hovered(close);
		BufferedImage image = sprite(hover ? SpriteID.CloseButtons.HOVERED : SpriteID.CloseButtons.BUTTON);
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

	/**
	 * The tabs: a flat face with a one pixel outline whose top corners step
	 * in over four rows, no line along the bottom, sat on the line under
	 * the strip. The chosen one is lighter with a warmer label.
	 */
	private void drawTabs(Graphics2D g, GameFont body)
	{
		tabRects = ChronicleLayout.tabs(TAB_NAMES.length);
		for (int i = 0; i < tabRects.length; i++)
		{
			Rectangle r = tabRects[i];
			boolean selected = i == tab;
			g.setColor(new Color(selected ? ChronicleLayout.TAB_FACE_SELECTED : ChronicleLayout.TAB_FACE));
			g.fillRect(r.x, r.y, r.width, r.height);
			g.setColor(new Color(ChronicleLayout.TAB_OUTLINE));
			// The stair: the outline begins three in on the top row, two in
			// on the next, one in for two rows, then runs straight down.
			int[] inset = {3, 2, 1, 1};
			for (int row = 0; row < inset.length; row++)
			{
				int in = inset[row];
				g.fillRect(r.x + in, r.y + row, 1, 1);
				g.fillRect(r.x + r.width - 1 - in, r.y + row, 1, 1);
			}
			g.fillRect(r.x + 3, r.y, r.width - 6, 1);
			g.fillRect(r.x, r.y + inset.length, 1, r.height - inset.length);
			g.fillRect(r.x + r.width - 1, r.y + inset.length, 1, r.height - inset.length);
			int colour = selected ? ChronicleLayout.TAB_LABEL_SELECTED : ChronicleLayout.TITLE_ORANGE;
			text(g, body, false, TAB_NAMES[i], r.x + r.width / 2, r.y + 14, colour, true);
		}
	}

	/** The list: striped rows, the chosen one lit, green when written and red when not. */
	private void drawList(Graphics2D g, GameFont body)
	{
		int visible = ChronicleLayout.visibleRows();
		listScroll = ChronicleLayout.clampScroll(listScroll, listed.size(), visible);
		Shape clip = g.getClip();
		g.clipRect(ChronicleLayout.LIST.x, ChronicleLayout.LIST.y, ChronicleLayout.LIST.width,
			ChronicleLayout.LIST.height);
		// One more than fits, so a part of the next row shows below, as in
		// the game.
		for (int i = 0; i <= visible && i + listScroll < listed.size(); i++)
		{
			ChronicleEntry entry = listed.get(i + listScroll);
			Rectangle row = ChronicleLayout.row(i);
			boolean selected = entry.id.equals(selectedId);
			if (selected || hovered(row))
			{
				g.setColor(new Color(ChronicleLayout.SELECTED_ROW));
				g.fillRect(row.x, row.y, row.width, row.height);
			}
			else if (i % 2 == 0)
			{
				g.setColor(new Color(ChronicleLayout.ROW_STRIPE));
				g.fillRect(row.x, row.y, row.width, row.height);
			}
			boolean readable = progress != null && progress.isReadable(entry);
			int colour = readable ? ChronicleLayout.READABLE_GREEN : ChronicleLayout.LOCKED_RED;
			text(g, body, false, entry.name, ChronicleLayout.LIST_TEXT_X, row.y + ChronicleLayout.ROW_BASELINE,
				colour, false);
		}
		g.setClip(clip);
		drawScrollbar(g, ChronicleLayout.LIST_SCROLL, listThumb, listScroll, listed.size(), visible);
	}

	private void drawPage(Graphics2D g, ChronicleEntry entry, GameFont body, GameFont bold, GameFont small,
		Rectangle prev, Rectangle next, Rectangle shown)
	{
		int left = ChronicleLayout.PAGE_TEXT_X;
		int width = ChronicleLayout.PAGE_TEXT_RIGHT - left;
		if (entry == null)
		{
			text(g, body, false, "Nothing written here yet.", left, ChronicleLayout.HEADER_BASELINE,
				ChronicleLayout.LOCKED_RED, false);
			pageLines = new ArrayList<>();
			pageLinesFor = null;
			pageLinesOf = null;
			return;
		}

		// The header block: the name, what it is and whether it is written,
		// and what writes it, in the small face the game uses for these.
		boolean readable = progress != null && progress.isReadable(entry);
		text(g, bold, true, entry.name, left, ChronicleLayout.HEADER_BASELINE, ChronicleLayout.TITLE_ORANGE, false);
		// A written page says when it was written, if the book kept the date,
		// and where, if the place has a name; any other says what will write it.
		Long on = readable ? progress.writtenOn(entry) : null;
		String kind = kindLabel(entry.kindOrEmpty()) + ": ";
		labelled(g, small, left, ChronicleLayout.HEADER_LINE2_BASELINE, kind,
			on != null ? writtenOn(on, kind, s -> measure(g, small, false, s), width)
				: readable ? "Written" : "Not yet written",
			readable ? ChronicleLayout.READABLE_GREEN : ChronicleLayout.LOCKED_RED);
		String at = on == null ? "" : progress.writtenAt(entry);
		if (!at.isEmpty())
		{
			labelled(g, small, left, ChronicleLayout.HEADER_LINE3_BASELINE, WRITTEN_AT,
				shortPlace(at), ChronicleLayout.BODY_WHITE);
		}
		else
		{
			labelled(g, small, left, ChronicleLayout.HEADER_LINE3_BASELINE, "Written when: ",
				unlockLabel(entry), ChronicleLayout.BODY_WHITE);
		}

		// The body, laid out once per page and cached until the page or its
		// state changes: the page itself, or the reason it cannot be read
		// yet and, for a creature, where to find one. Either way only what the
		// quests done so far allow; a quest finished opens its passages.
		int bodyTop = ChronicleLayout.BODY_TOP;
		int bodyHeight = ChronicleLayout.CONTENT_BOTTOM - bodyTop;
		Set<String> done = new TreeSet<>();
		for (String quest : entry.gateQuests())
		{
			if (progress != null && progress.isDone(quest))
			{
				done.add(quest);
			}
		}
		// Your own number under the plate, once the page is written and the
		// follower has one.
		String tally = readable ? tallyLine(entry, progress.count(entry)) : null;
		String page = entry.id + (readable ? "" : ":locked");
		String key = page + done + tally;
		if (!key.equals(pageLinesFor))
		{
			ChronicleEntry asRead = entry.readAs(done::contains);
			List<ChronicleLayout.Line> before = pageLines;
			pageLines = readable
				? ChronicleLayout.page(asRead, tally, width,
					s -> measure(g, body, false, s), s -> measure(g, bold, true, s))
				: ChronicleLayout.lockedPage(asRead, unwrittenHint(entry), width,
					s -> measure(g, body, false, s), s -> measure(g, bold, true, s));
			// Another page, or this one just written, opens at the top. The
			// same page changing under the reader - one more kill, a passage
			// opening - keeps their place, moved past a number that has just
			// appeared above it.
			pageScroll = page.equals(pageLinesOf) ? ChronicleLayout.keptScroll(pageScroll, before, pageLines) : 0;
			pageLinesFor = key;
			pageLinesOf = page;
		}
		// What a screen holds, as the scrollbar counts it: every line less the
		// ones scrolled past at the furthest the page goes, gaps allowed for.
		pageVisibleLines = Math.max(1, pageLines.size() - ChronicleLayout.lastScroll(pageLines, bodyHeight));
		pageScroll = ChronicleLayout.clampScroll(pageScroll, pageLines.size(), pageVisibleLines);

		Shape clip = g.getClip();
		g.clipRect(ChronicleLayout.PAGE.x, bodyTop, ChronicleLayout.PAGE.width, bodyHeight);
		List<ChronicleEntry.Plate> plates = entry.platesOrEmpty();
		if (!entry.id.equals(plateIndexFor))
		{
			plateIndexFor = entry.id;
			plateIndex = 0;
		}
		ChronicleEntry.Plate plate = null;
		if (!plates.isEmpty())
		{
			plateIndex = Math.floorMod(plateIndex, plates.size());
			plate = plates.get(plateIndex);
			int plateTop = bodyTop + 2 - pageScroll * ChronicleLayout.LINE_HEIGHT;
			if (plateTop + ChronicleLayout.PLATE_H > bodyTop)
			{
				// Only the part scrolled into view takes a press, and only a
				// picture: paper not yet sketched on is nothing to open.
				if (plate.sketch == null || sketches != null && sketches.has(plate.sketch))
				{
					shown.setBounds(new Rectangle(left, plateTop, ChronicleLayout.PLATE_W, ChronicleLayout.PLATE_H)
						.intersection(new Rectangle(ChronicleLayout.PAGE.x, bodyTop, ChronicleLayout.PAGE.width, bodyHeight)));
				}
				drawPlate(g, plate, left, plateTop, ChronicleLayout.PLATE_W, ChronicleLayout.PLATE_H,
					readable, small, false, hovered(shown));
			}
		}
		int ly = bodyTop + ChronicleLayout.LINE_HEIGHT - 2;
		for (int i = pageScroll; i < pageLines.size(); i++)
		{
			ChronicleLayout.Line line = pageLines.get(i);
			if (i > pageScroll)
			{
				ly += line.gapAbove;
			}
			if (ly - ChronicleLayout.LINE_HEIGHT > ChronicleLayout.CONTENT_BOTTOM)
			{
				break;
			}
			switch (line.style)
			{
				case PLATE:
					break;
				case CAPTION:
					drawCaption(g, small, plate, plates.size(), readable, left, width, ly, prev, next);
					break;
				case HEADING:
					text(g, bold, true, line.text, left, ly, ChronicleLayout.TITLE_ORANGE, false);
					break;
				case NOTE:
					text(g, body, false, line.text, left, ly, ChronicleLayout.NOTE_PALE, false);
					break;
				case TALLY:
					drawTally(g, body, line.text, left, ly);
					break;
				default:
					text(g, body, false, line.text, left, ly,
						readable ? ChronicleLayout.BODY_WHITE : ChronicleLayout.NOTE_PALE, false);
					break;
			}
			ly += ChronicleLayout.LINE_HEIGHT;
		}
		g.setClip(clip);
		drawScrollbar(g, ChronicleLayout.PAGE_SCROLL, pageThumb, pageScroll, pageLines.size(), pageVisibleLines);
	}

	/**
	 * A plate: a thin dark frame, and inside it the picture. A creature or a
	 * person is drawn from its own model, turning, in its idle; one not yet
	 * met is its outline in ink. A place is the scribe's sketch, or bare
	 * paper until you have posed for it there. Enlarged, a sketch is the
	 * copy drawn at that size; on the page, the pose it caught you in is
	 * pencilled along its foot. Under the
	 * mouse it lightens a touch, as the game's own pictures that take a
	 * press do.
	 */
	private void drawPlate(Graphics2D g, ChronicleEntry.Plate plate, int x, int y, int w, int h,
		boolean seen, GameFont small, boolean big, boolean hover)
	{
		g.setColor(new Color(PLATE_EDGE));
		g.drawRect(x, y, w - 1, h - 1);
		g.setColor(new Color(ChronicleLayout.LINE));
		g.drawRect(x + 1, y + 1, w - 3, h - 3);
		int ix = x + 2;
		int iy = y + 2;
		int iw = w - 4;
		int ih = h - 4;
		if (plate.npc != null)
		{
			// Paper, a shade darker at the foot, as a thing stands on the ground.
			for (int row = 0; row < ih; row++)
			{
				g.setColor(new Color(SketchStyle.mix(PORTRAIT_TOP, PORTRAIT_FOOT, row / (double) ih)));
				g.fillRect(ix, iy + row, iw, 1);
			}
			long now = System.currentTimeMillis();
			BufferedImage portrait = portraits.draw(plate.npc, plate.idle, iw, ih,
				ChroniclePortraits.yawAt(now), now, !seen);
			if (portrait != null)
			{
				g.drawImage(portrait, ix, iy, null);
			}
		}
		else if (!drawSketch(g, plate.sketch, ix, iy, iw, ih, big))
		{
			g.setColor(new Color(BARE_PAPER));
			g.fillRect(ix, iy, iw, ih);
			String blank = "Not yet sketched.";
			if (small != null)
			{
				small.drawCenteredBaseline(g, blank, ix + iw / 2, iy + ih / 2 + 4, SketchStyle.INK, false);
			}
			else
			{
				text(g, null, false, blank, ix + iw / 2, iy + ih / 2 + 4, SketchStyle.INK, true);
			}
		}
		else if (!big && poseLine(plate) != null)
		{
			// On the page there is no room under the caption for the pose
			// without a line held blank under every other plate, so it is
			// written on the drawing, as a note in pencil at its foot.
			String said = poseLine(plate);
			int baseline = iy + ih - POSE_RISE;
			// On a strip of paper left clear, or the pencil is lost in a busy drawing.
			int across = measure(g, small, false, said) + 8;
			g.setColor(new Color(SketchStyle.PAPER | POSE_PAPER_ALPHA << 24, true));
			g.fillRect(ix + (iw - across) / 2, baseline - 10, across, 13);
			if (small != null)
			{
				small.drawCenteredBaseline(g, said, ix + iw / 2, baseline, SketchStyle.INK, false);
			}
			else
			{
				text(g, null, false, said, ix + iw / 2, baseline, SketchStyle.INK, true);
			}
		}
		if (hover)
		{
			g.setColor(new Color(255, 255, 255, HOVER_ALPHA));
			g.fillRect(ix, iy, iw, ih);
		}
	}

	/**
	 * The scribe's sketch of a place, if it has one: enlarged, the copy it
	 * drew at that size. A sketch made before there were two sizes has only
	 * the small one, stretched smoothly until the scribe draws it again.
	 */
	private boolean drawSketch(Graphics2D g, String name, int x, int y, int w, int h, boolean big)
	{
		if (sketches == null)
		{
			return false;
		}
		BufferedImage sketch = big && sketches.has(name) ? sketches.getLarge(name) : null;
		if (sketch == null)
		{
			sketch = sketches.get(name);
		}
		if (sketch == null)
		{
			return false;
		}
		if (sketch.getWidth() == w && sketch.getHeight() == h)
		{
			g.drawImage(sketch, x, y, null);
			return true;
		}
		Object before = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(sketch, x, y, w, h, null);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			before != null ? before : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		return true;
	}

	/**
	 * A plate opened across the window: the page's name over it and its
	 * caption, with the arrows, under it, the same plate the page was
	 * showing.
	 */
	private void drawEnlarged(Graphics2D g, ChronicleEntry entry, GameFont bold, GameFont small,
		Rectangle prev, Rectangle next)
	{
		List<ChronicleEntry.Plate> plates = entry.platesOrEmpty();
		plateIndex = Math.floorMod(plateIndex, plates.size());
		ChronicleEntry.Plate plate = plates.get(plateIndex);
		boolean readable = progress != null && progress.isReadable(entry);
		text(g, bold, true, entry.name, ChronicleLayout.WIDTH / 2, ChronicleLayout.BIG_NAME_BASELINE,
			ChronicleLayout.TITLE_ORANGE, true);
		Rectangle frame = ChronicleLayout.BIG_PLATE;
		drawPlate(g, plate, frame.x, frame.y, frame.width, frame.height, readable, small, true, hovered(frame));
		drawCaption(g, small, plate, plates.size(), readable, frame.x, frame.width,
			ChronicleLayout.BIG_CAPTION_BASELINE, prev, next);
		drawPose(g, small, plate, ChronicleLayout.WIDTH / 2,
			ChronicleLayout.BIG_CAPTION_BASELINE + ChronicleLayout.LINE_HEIGHT);
	}

	/** Under the caption, the emote the sketch caught you in, if it caught one. */
	private void drawPose(Graphics2D g, GameFont small, ChronicleEntry.Plate plate, int centre, int baseline)
	{
		String said = plate == null ? null : poseLine(plate);
		if (said != null)
		{
			text(g, small, false, said, centre, baseline, ChronicleLayout.NOTE_PALE, true);
		}
	}

	/** The plate's pose in the scribe's words, or null: not a sketch, not drawn, or no pose. */
	private String poseLine(ChronicleEntry.Plate plate)
	{
		return sketches == null || plate.sketch == null || !sketches.has(plate.sketch) ? null
			: posePhrase(sketches.poseOf(plate.sketch));
	}

	/**
	 * What the scribe says under a sketch of you mid-emote, by the word filed
	 * with it ({@link SketchController#POSE_NAMES}). A word it does not know,
	 * from a later version or a hand-edited file, says nothing.
	 */
	static final java.util.Map<String, String> POSE_PHRASES;

	static
	{
		java.util.Map<String, String> phrases = new java.util.HashMap<>();
		phrases.put("wave", "You were waving. I waved back, after.");
		phrases.put("dance", "You were mid-dance. I couldn't keep up.");
		phrases.put("cheer", "You were cheering. I never learned why.");
		phrases.put("bow", "You were bowing. Nobody bowed back.");
		phrases.put("clap", "You were clapping. Me, I hope.");
		phrases.put("laugh", "You were laughing. I missed the joke.");
		phrases.put("cry", "You were crying. I drew it kindly.");
		phrases.put("think", "You were thinking. Or posing as it.");
		phrases.put("salute", "You were saluting. I stood a little taller.");
		phrases.put("jig", "You were doing a jig. I kept the beat.");
		phrases.put("headbang", "You were headbanging. The ink smudged.");
		phrases.put("jump-for-joy", "You were jumping for joy. Mid-air, in ink.");
		phrases.put("spin", "You were spinning. I drew you twice.");
		phrases.put("blow-kiss", "You were blowing a kiss. I'll pass it on.");
		phrases.put("yawn", "You were yawning. Rude, but noted.");
		phrases.put("shrug", "You were shrugging. So was I.");
		phrases.put("flex", "You were flexing. I drew you bigger.");
		POSE_PHRASES = java.util.Collections.unmodifiableMap(phrases);
	}

	static String posePhrase(String pose)
	{
		return pose == null ? null : POSE_PHRASES.get(pose);
	}

	/**
	 * "Goblins you've killed while I watched: 214" - the page's own number,
	 * or null when it names none or nothing is counted yet, so the line is
	 * simply not there.
	 */
	static String tallyLine(ChronicleEntry entry, int count)
	{
		if (entry == null || entry.tally == null || entry.tally.label == null || count <= 0)
		{
			return null;
		}
		return entry.tally.label + ": " + count;
	}

	/**
	 * A line of the page's number as the header draws its own: the label
	 * and its colon, then the number; a line with no number on it is all label.
	 */
	static String[] tallyParts(String text)
	{
		int colon = text.lastIndexOf(": ");
		return colon < 0 ? new String[]{text, ""}
			: new String[]{text.substring(0, colon + 2), text.substring(colon + 2)};
	}

	private void drawTally(Graphics2D g, GameFont body, String text, int left, int baseline)
	{
		String[] parts = tallyParts(text);
		labelled(g, body, left, baseline, parts[0], parts[1], ChronicleLayout.BODY_WHITE);
	}

	/**
	 * The line under the plate: its number, as a book numbers plates, and
	 * the scribe's caption; arrows either side when there is more than one.
	 */
	private void drawCaption(Graphics2D g, GameFont small, ChronicleEntry.Plate plate, int count,
		boolean seen, int left, int width, int baseline, Rectangle prev, Rectangle next)
	{
		if (plate == null)
		{
			return;
		}
		String said = caption(plate, plateIndex, seen, sketches != null && sketches.has(plate.sketch));
		int centre = left + width / 2;
		text(g, small, false, said, centre, baseline, ChronicleLayout.NOTE_PALE, true);
		if (count > 1)
		{
			int top = baseline - 10;
			prev.setBounds(left, top, 14, 13);
			next.setBounds(left + width - 14, top, 14, 13);
			text(g, small, false, "<", left + 4, baseline,
				hovered(prev) ? ChronicleLayout.BODY_WHITE : ChronicleLayout.TITLE_ORANGE, false);
			text(g, small, false, ">", left + width - 10, baseline,
				hovered(next) ? ChronicleLayout.BODY_WHITE : ChronicleLayout.TITLE_ORANGE, false);
		}
	}

	/** "Plate II. The cook, in his kitchen." - or what stands in for it before it is earned. */
	static String caption(ChronicleEntry.Plate plate, int index, boolean seen, boolean sketched)
	{
		String number = "Plate " + ChronicleLayout.roman(index + 1) + ". ";
		if (plate.npc != null && !seen)
		{
			return number + "Not yet seen.";
		}
		if (plate.sketch != null && !sketched)
		{
			return number + (plate.hint == null || plate.hint.trim().isEmpty()
				? "Pose for me when we're there." : plate.hint);
		}
		return number + (plate.caption == null ? "" : plate.caption);
	}

	/** An orange label with its value after it, the game's own header idiom. */
	private void labelled(Graphics2D g, GameFont font, int x, int baseline, String label, String value, int valueColour)
	{
		text(g, font, false, label, x, baseline, ChronicleLayout.TITLE_ORANGE, false);
		text(g, font, false, value, x + measure(g, font, false, label), baseline, valueColour, false);
	}

	/** The quest's name as the game gives it, or the data's word for it. */
	static String questName(String constant)
	{
		Quest quest = ChronicleProgress.questNamed(constant);
		return quest == null ? constant : quest.getName();
	}

	/** What writes the page, in a few words, naming the quest where there is one. */
	static String unlockLabel(ChronicleEntry entry)
	{
		ChronicleEntry.ChronicleUnlock unlock = entry.unlock;
		if (unlock == null)
		{
			return "never";
		}
		List<String> parts = new ArrayList<>();
		if (unlock.always)
		{
			parts.add("from the start");
		}
		if (unlock.quest != null && !metOnly(entry))
		{
			parts.add(questName(unlock.quest) + " is done");
		}
		// Short, because the header has one line of the column for it: the
		// two-way pages ran off the window as "is under way" and "you go
		// there". The locked page's own line says it in full.
		if (unlock.questBegun != null)
		{
			parts.add(questName(unlock.questBegun) + " starts");
		}
		if (unlock.npcs != null && !unlock.npcs.isEmpty())
		{
			parts.add("boss".equals(entry.kindOrEmpty()) ? "you meet it" : "you meet one");
		}
		if (unlock.regions != null && !unlock.regions.isEmpty())
		{
			parts.add("you visit");
		}
		return parts.isEmpty() ? "never" : String.join(", or ", parts);
	}

	/**
	 * A creature written when it is met. Its quest, finished, writes it too,
	 * for the accounts that met it before there was a book; the quest cannot
	 * be done without the meeting, so the meeting is all the page says.
	 */
	private static boolean metOnly(ChronicleEntry entry)
	{
		String kind = entry.kindOrEmpty();
		return entry.unlock.npcs != null && !entry.unlock.npcs.isEmpty()
			&& ("enemy".equals(kind) || "boss".equals(kind) || "beast".equals(kind));
	}

	/** What the page says instead of its text while it cannot be read. */
	static String unwrittenHint(ChronicleEntry entry)
	{
		ChronicleEntry.ChronicleUnlock unlock = entry.unlock;
		if (unlock == null)
		{
			return "The scribe has nothing on this yet.";
		}
		boolean byQuest = (unlock.quest != null && !metOnly(entry)) || unlock.questBegun != null;
		boolean byNpc = unlock.npcs != null && !unlock.npcs.isEmpty();
		boolean byRegion = unlock.regions != null && !unlock.regions.isEmpty();
		if (byNpc && !byQuest && !byRegion)
		{
			return "boss".equals(entry.kindOrEmpty())
				? "The scribe has not met this boss yet."
				: "The scribe has not met one of these yet.";
		}
		if (byRegion && !byQuest && !byNpc)
		{
			return "The scribe has not been there yet.";
		}
		List<String> parts = new ArrayList<>();
		if (unlock.quest != null && !metOnly(entry))
		{
			parts.add(questName(unlock.quest) + " is done");
		}
		if (unlock.questBegun != null)
		{
			parts.add(questName(unlock.questBegun) + " is under way");
		}
		if (byNpc)
		{
			parts.add("boss".equals(entry.kindOrEmpty()) ? "you have met this boss" : "you have met one of these");
		}
		if (byRegion)
		{
			parts.add("you have been there");
		}
		if (parts.isEmpty())
		{
			return "The scribe has nothing on this yet.";
		}
		return "The scribe will write this up once " + String.join(", or once ", parts) + ".";
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
				return "Land";
			default:
				return "Page";
		}
	}

	/**
	 * A scrollbar: the track, the thumb on it, the arrows over its ends.
	 * The thumb's pieces are clipped to its own height, because the middle
	 * tiles in fives and the last one used to hang out below the cap.
	 */
	private void drawScrollbar(Graphics2D g, Rectangle bar, Rectangle thumbOut, int offset, int total, int visible)
	{
		BufferedImage up = sprite(SpriteID.ScrollbarV2.ARROW_UP);
		BufferedImage down = sprite(SpriteID.ScrollbarV2.ARROW_DOWN);
		BufferedImage track = sprite(SpriteID.ScrollbarDraggerV2.TRACK);
		BufferedImage thumbTop = sprite(SpriteID.ScrollbarDraggerV2.TOP);
		BufferedImage thumbMid = sprite(SpriteID.ScrollbarDraggerV2.MIDDLE);
		BufferedImage thumbBottom = sprite(SpriteID.ScrollbarDraggerV2.BOTTOM);

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
		thumbOut.setBounds(bar.x, top, bar.width, height);
		g.setClip(clip);
		g.clipRect(bar.x, top, bar.width, height);
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

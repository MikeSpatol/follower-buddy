package com.follower.chronicle;

import com.follower.follower.ErrandController;
import com.follower.follower.FollowerEntity;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.DrawManager;

/**
 * The scribe sketches the places it has a page for. The first time you
 * stand still somewhere that wants a sketch, the follower takes out its
 * scroll, the view is taken - you in the middle of it - and drawn up in ink
 * and wash for the page. Then it puts the scroll away and says so, after,
 * so its words are not in the picture.
 *
 * <p>One sketch per place, kept; a moment spoiled by your moving off, a
 * fight or a window opening waits a while and tries again.
 */
@Slf4j
public class SketchController
{
	/** Standing still this long before it starts: it is not going to chase you. */
	static final int IDLE_TICKS = 3;

	/** The scroll out this long before the picture is taken. */
	static final int HOLD_TICKS = 3;

	/** And this long after, before it goes away. */
	static final int PUT_AWAY_TICKS = 2;

	/** A spoiled attempt waits this long. */
	static final int RETRY_TICKS = 100;

	/** Said once the sketch is in the book; never before, or it is in the picture. */
	static final String[] DONE = {
		"That's the view. You're in it, for scale.",
		"In the book. I've drawn you a touch taller.",
		"Sketched. The light did most of the work.",
		"Got it. Hold still longer next time and I'll do hands.",
	};

	enum Stage
	{
		WAITING, HOLDING, TAKEN
	}

	private final Client client;
	private final FollowerEntity follower;
	private final ErrandController.Hands hands;
	private final ChronicleLoader loader;
	private final SketchBook book;
	private final DrawManager drawManager;
	private final Executor executor;
	private final BooleanSupplier busy;
	private final IntSupplier idleTicks;
	private final IntSupplier region;
	private final Consumer<String> say;
	private final Random random = new Random();

	private Stage stage = Stage.WAITING;
	private String key;
	private int ticks;
	private int cooldown;

	public SketchController(Client client, FollowerEntity follower, ErrandController.Hands hands,
		ChronicleLoader loader, SketchBook book, DrawManager drawManager, Executor executor,
		BooleanSupplier busy, IntSupplier idleTicks, IntSupplier region, Consumer<String> say)
	{
		this.client = client;
		this.follower = follower;
		this.hands = hands;
		this.loader = loader;
		this.book = book;
		this.drawManager = drawManager;
		this.executor = executor;
		this.busy = busy;
		this.idleTicks = idleTicks;
		this.region = region;
		this.say = say;
	}

	/**
	 * The sketch a place still wants, standing in {@code regionId}: the key
	 * of the first plate on any page that sketches here and has none yet.
	 */
	static String wanted(List<ChronicleEntry> entries, int regionId, Predicate<String> have)
	{
		for (ChronicleEntry entry : entries)
		{
			for (ChronicleEntry.Plate plate : entry.platesOrEmpty())
			{
				if (plate.sketch != null && plate.regions != null
					&& plate.regions.contains(regionId) && !have.test(plate.sketch))
				{
					return plate.sketch;
				}
			}
		}
		return null;
	}

	public boolean isSketching()
	{
		return stage != Stage.WAITING;
	}

	/** Once a game tick. */
	public void tick()
	{
		switch (stage)
		{
			case HOLDING:
				if (busy.getAsBoolean() || idleTicks.getAsInt() == 0 || !follower.isSpawned())
				{
					// You moved off, or something else began. Another time.
					putAway(null);
					cooldown = RETRY_TICKS;
					return;
				}
				if (++ticks >= HOLD_TICKS)
				{
					capture();
					stage = Stage.TAKEN;
					ticks = 0;
				}
				return;
			case TAKEN:
				if (++ticks >= PUT_AWAY_TICKS)
				{
					putAway(DONE[random.nextInt(DONE.length)]);
				}
				return;
			default:
				break;
		}
		if (cooldown > 0)
		{
			cooldown--;
			return;
		}
		if (busy.getAsBoolean() || idleTicks.getAsInt() < IDLE_TICKS
			|| !follower.isSpawned() || !follower.isSettled())
		{
			return;
		}
		String here = wanted(loader.getEntries(), region.getAsInt(), book::has);
		if (here == null)
		{
			return;
		}
		hands.hold(ErrandController.SCROLL_PROP);
		if (!hands.isHolding())
		{
			cooldown = RETRY_TICKS;
			return;
		}
		follower.setPoseOverride(ErrandController.READ_SCROLL_POSE);
		key = here;
		stage = Stage.HOLDING;
		ticks = 0;
	}

	/** Drops everything, as a logout or a hop does. */
	public void reset()
	{
		if (stage != Stage.WAITING)
		{
			putAway(null);
		}
		cooldown = 0;
	}

	private void putAway(String line)
	{
		hands.release();
		follower.setPoseOverride(0);
		stage = Stage.WAITING;
		key = null;
		ticks = 0;
		if (line != null)
		{
			say.accept(line);
		}
	}

	/**
	 * Asks for the next frame, and keeps the part of it round you: the
	 * middle of the view, clear of the minimap, the chat and the side
	 * panels, at the plate's own shape.
	 */
	private void capture()
	{
		String filing = key;
		Rectangle crop = cropAroundYou();
		if (filing == null || crop == null)
		{
			return;
		}
		long seed = filing.hashCode();
		drawManager.requestNextFrameListener(frame -> copyAndDraw(frame, crop, filing, seed));
	}

	private void copyAndDraw(Image frame, Rectangle crop, String filing, long seed)
	{
		// The frame is the client's own buffer, reused on the next draw: copy
		// the part wanted now, and do the slow drawing somewhere else.
		BufferedImage copy = new BufferedImage(crop.width, crop.height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = copy.createGraphics();
		try
		{
			g.drawImage(frame, -crop.x, -crop.y, null);
		}
		finally
		{
			g.dispose();
		}
		executor.execute(() ->
		{
			try
			{
				book.put(filing, SketchStyle.draw(copy, ChronicleLayout.PLATE_W - 4,
					ChronicleLayout.PLATE_H - 4, seed));
			}
			catch (RuntimeException e)
			{
				log.warn("Could not draw the sketch of {}", filing, e);
			}
		});
	}

	private Rectangle cropAroundYou()
	{
		Player me = client.getLocalPlayer();
		LocalPoint where = me == null ? null : me.getLocalLocation();
		if (where == null)
		{
			return null;
		}
		Point centre = Perspective.localToCanvas(client, where,
			client.getTopLevelWorldView().getPlane(), me.getLogicalHeight() / 2);
		Rectangle clear = clearOfInterfaces(client.getViewportXOffset(), client.getViewportYOffset(),
			client.getViewportWidth(), client.getViewportHeight(), client.isResized());
		return centre == null ? null : crop(clear, centre.getX(), centre.getY(),
			(double) ChronicleLayout.PLATE_W / ChronicleLayout.PLATE_H);
	}

	/**
	 * The part of the view no interface sits on. Fixed mode has none on the
	 * view; resizable lays the minimap, the side panel and the chat over its
	 * corners and edges.
	 */
	static Rectangle clearOfInterfaces(int x, int y, int width, int height, boolean resizable)
	{
		if (!resizable)
		{
			return new Rectangle(x, y, width, height);
		}
		int left = 10;
		int top = 10;
		int right = 250;
		int bottom = 180;
		return new Rectangle(x + left, y + top, Math.max(1, width - left - right),
			Math.max(1, height - top - bottom));
	}

	/**
	 * A crop of the given shape, as wide as half the clear area allows,
	 * centred on you and slid back inside the clear area if it spills.
	 */
	static Rectangle crop(Rectangle clear, int cx, int cy, double aspect)
	{
		int width = (int) Math.min(clear.width, Math.max(160, clear.width * 0.6));
		int height = (int) Math.round(width / aspect);
		if (height > clear.height)
		{
			height = clear.height;
			width = (int) Math.round(height * aspect);
		}
		int x = Math.max(clear.x, Math.min(cx - width / 2, clear.x + clear.width - width));
		int y = Math.max(clear.y, Math.min(cy - height / 2, clear.y + clear.height - height));
		return new Rectangle(x, y, width, height);
	}
}

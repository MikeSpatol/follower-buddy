package com.follower.speech;

import com.follower.FollowerConfig;
import com.follower.follower.FollowerEntity;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the follower's overhead chat.
 *
 * <p>A {@code RuneLiteObject} isn't an {@code Actor}, so the client's real overhead
 * chat system can't be used - the text is projected and drawn by hand, to the
 * client's own recipe (its chat-above-head draw, ported verbatim): the BOLD 12
 * glyph font, a black copy one pixel STRAIGHT DOWN (not diagonal), the coloured
 * text over it, centred on the entity's projected height point, and no fade -
 * real overhead text simply disappears when its timer ends. And ONE line,
 * however long: the game never wraps overhead text, so a wrapped line here
 * read as the follower doing something no player or NPC can.
 */
public class FollowerOverlay extends Overlay
{
	private final Client client;
	private final FollowerEntity follower;
	private final FollowerConfig config;
	private final com.follower.ui.GameFontRepository gameFonts;

	private String message;
	private long shownAtMs;
	private long durationMs;

	/** How long the line has been out of sight with its follower, which does not count against it. */
	private long unseenMs;
	private long lastRenderMs;

	/** The most a line waits for its follower to be seen again. */
	private static final long MAX_UNSEEN_MS = 8000;

	@Inject
	public FollowerOverlay(Client client, FollowerEntity follower, FollowerConfig config,
		com.follower.ui.GameFontRepository gameFonts)
	{
		this.client = client;
		this.follower = follower;
		this.config = config;
		this.gameFonts = gameFonts;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	public void show(String text, long durationMs)
	{
		this.message = text;
		this.shownAtMs = System.currentTimeMillis();
		this.durationMs = durationMs;
		unseenMs = 0;
		lastRenderMs = shownAtMs;
	}

	public void clear()
	{
		message = null;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (message == null || !follower.isSpawned())
		{
			return null;
		}

		long now = System.currentTimeMillis();
		if (!follower.isShown())
		{
			unseenMs += now - lastRenderMs;
		}
		lastRenderMs = now;
		long elapsed = now - shownAtMs - Math.min(unseenMs, MAX_UNSEEN_MS);
		if (elapsed > durationMs)
		{
			message = null;
			return null;
		}

		// Kept while it is out of sight, its clock stopped, so the line comes
		// back with it to be read. Drawn over an empty tile, or an NPC standing
		// on it, the line reads as someone else's.
		if (!follower.isShown())
		{
			return null;
		}

		LocalPoint location = follower.getLastRenderedLocation();
		if (location == null)
		{
			return null;
		}

		int zOffset = config.speechHeight();
		int rgb = config.speechColor().getRGB() & 0xFFFFFF;

		// Over the follower's own floor, which agility can leave a floor or
		// more from the player's: on the ground below a roof they are on.
		Point anchor = Perspective.localToCanvas(client, location,
			follower.getWorldLocation().getPlane(), zOffset);
		if (anchor == null)
		{
			return null;
		}

		// The client's own overhead draw: BOLD 12 glyphs, centred on the
		// entity's projected height point, black copy one pixel straight down,
		// coloured text over it. No fade - real overhead text just disappears.
		com.follower.ui.GameFont b12 = gameFonts.getByName("b12_full");
		if (b12 != null)
		{
			b12.drawCenteredOverhead(graphics, message, anchor.getX(), anchor.getY(), rgb);
			return null;
		}

		// Fallback while fonts.json is missing: RuneLite's recreation.
		graphics.setFont(FontManager.getRunescapeBoldFont());
		FontMetrics metrics = graphics.getFontMetrics();
		int x = anchor.getX() - metrics.stringWidth(message) / 2;
		graphics.setColor(Color.BLACK);
		graphics.drawString(message, x, anchor.getY() + 1);
		graphics.setColor(new Color(rgb));
		graphics.drawString(message, x, anchor.getY());

		return null;
	}
}

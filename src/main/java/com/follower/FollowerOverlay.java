package com.follower;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.Overlay;

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
class FollowerOverlay extends Overlay
{
	final Client client;
	final FollowerEntity follower;
	final FollowerConfig config;
	final GameFontRepository gameFonts;

	String message;
	long shownAtMs,
		durationMs;

	/** How long the line has been out of sight with its follower, which does not count against it. */
	long unseenMs,
		lastRenderMs;

	@Inject
	FollowerOverlay(Client client, FollowerEntity follower, FollowerConfig config,
		GameFontRepository gameFonts)
	{
		this.client = client;
		this.follower = follower;
		this.config = config;
		this.gameFonts = gameFonts;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	void show(String text, long durationMs)
	{
		this.message = text;
		this.shownAtMs = System.currentTimeMillis();
		this.durationMs = durationMs;
		unseenMs = 0;
		lastRenderMs = shownAtMs;
	}

	void clear()
	{
		message = null;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (message == null || !follower.isSpawned())
			return null;

		long now = System.currentTimeMillis();
		if (!follower.isShown())
			unseenMs += now - lastRenderMs;
		lastRenderMs = now;
		// MAX_UNSEEN_MS: The most a line waits for its follower to be seen again.
		long elapsed = now - shownAtMs - Math.min(unseenMs, 8000L /* MAX_UNSEEN_MS */);
		if (elapsed > durationMs)
		{
			message = null;
			return null;
		}

		// Kept while it is out of sight, its clock stopped, so the line comes
		// back with it to be read. Drawn over an empty tile, or an NPC standing
		// on it, the line reads as someone else's.
		if (!follower.isShown())
			return null;

		var location = follower.getLastRenderedLocation();
		if (location == null)
			return null;

		int zOffset = config.speechHeight(),
			rgb = config.speechColor().getRGB() & 0xFFFFFF;

		// Over the follower's own floor, which agility can leave a floor or
		// more from the player's: on the ground below a roof they are on.
		var anchor = Perspective.localToCanvas(client, location,
			follower.getWorldLocation().getPlane(), zOffset);
		if (anchor == null)
			return null;

		// The client's own overhead draw: BOLD 12 glyphs, centred on the
		// entity's projected height point, black copy one pixel straight down,
		// coloured text over it. No fade - real overhead text just disappears.
		var b12 = gameFonts.getByName("b12_full");
			b12.drawCenteredOverhead(graphics, message, anchor.getX(), anchor.getY(), rgb);
			return null;
	}
}

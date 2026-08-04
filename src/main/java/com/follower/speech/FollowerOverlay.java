package com.follower.speech;

import com.follower.FollowerConfig;
import com.follower.follower.FollowerEntity;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
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
 * real overhead text simply disappears when its timer ends.
 */
public class FollowerOverlay extends Overlay
{
	private static final int MAX_LINE_CHARS = 34;

	private final Client client;
	private final FollowerEntity follower;
	private final FollowerConfig config;
	private final com.follower.ui.GameFontRepository gameFonts;

	private String message;
	private long shownAtMs;
	private long durationMs;

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

		long elapsed = System.currentTimeMillis() - shownAtMs;
		if (elapsed > durationMs)
		{
			message = null;
			return null;
		}

		LocalPoint location = follower.getLastRenderedLocation();
		if (location == null)
		{
			return null;
		}

		List<String> lines = wrap(message);
		int zOffset = config.speechHeight();
		int rgb = config.speechColor().getRGB() & 0xFFFFFF;

		// The client's own overhead draw: BOLD 12 glyphs, centred on the
		// entity's projected height point, black copy one pixel straight down,
		// coloured text over it, stacked upward by the font's height. No fade -
		// real overhead text just disappears.
		com.follower.ui.GameFont b12 = gameFonts.getByName("b12_full");
		if (b12 != null)
		{
			Point anchor = Perspective.localToCanvas(client, location,
				client.getTopLevelWorldView().getPlane(), zOffset);
			if (anchor == null)
			{
				return null;
			}
			int lineHeight = b12.getLineHeight();
			for (int i = 0; i < lines.size(); i++)
			{
				int baseline = anchor.getY() - (lines.size() - 1 - i) * lineHeight;
				b12.drawCenteredOverhead(graphics, lines.get(i),
					anchor.getX(), baseline, rgb);
			}
			return null;
		}

		// Fallback while fonts.json is missing: RuneLite's recreation.
		graphics.setFont(FontManager.getRunescapeBoldFont());
		FontMetrics metrics = graphics.getFontMetrics();
		Point anchor = Perspective.getCanvasTextLocation(
			client, graphics, location, lines.get(0), zOffset);
		if (anchor == null)
		{
			return null;
		}
		int lineHeight = metrics.getHeight();
		int centreX = anchor.getX() + metrics.stringWidth(lines.get(0)) / 2;
		for (int i = 0; i < lines.size(); i++)
		{
			String line = lines.get(i);
			int x = centreX - metrics.stringWidth(line) / 2;
			int y = anchor.getY() - (lines.size() - 1 - i) * lineHeight;
			graphics.setColor(Color.BLACK);
			graphics.drawString(line, x, y + 1);
			graphics.setColor(config.speechColor());
			graphics.drawString(line, x, y);
		}

		return null;
	}

	private static List<String> wrap(String text)
	{
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();

		for (String word : text.split(" "))
		{
			if (line.length() > 0 && line.length() + 1 + word.length() > MAX_LINE_CHARS)
			{
				lines.add(line.toString());
				line.setLength(0);
			}
			if (line.length() > 0)
			{
				line.append(' ');
			}
			line.append(word);
		}

		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		if (lines.isEmpty())
		{
			lines.add(text);
		}
		return lines;
	}
}

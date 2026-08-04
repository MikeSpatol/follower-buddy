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
 * Draws the follower's speech bubble.
 *
 * <p>A {@code RuneLiteObject} isn't an {@code Actor}, so the client's real overhead
 * chat system can't be used — the text is projected and drawn by hand.
 */
public class FollowerOverlay extends Overlay
{
	private static final int MAX_LINE_CHARS = 34;
	private static final int LINE_PADDING = 3;
	private static final int FADE_MS = 400;

	private final Client client;
	private final FollowerEntity follower;
	private final FollowerConfig config;

	private String message;
	private long shownAtMs;
	private long durationMs;

	@Inject
	public FollowerOverlay(Client client, FollowerEntity follower, FollowerConfig config)
	{
		this.client = client;
		this.follower = follower;
		this.config = config;

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

		// The game's own font is most of what makes overhead text read as overhead
		// text; the default AWT font looks immediately wrong next to real NPC chat.
		graphics.setFont(FontManager.getRunescapeFont());
		FontMetrics metrics = graphics.getFontMetrics();
		List<String> lines = wrap(message, metrics);
		int lineHeight = metrics.getHeight() + LINE_PADDING;

		// Anchor above the follower's head, then stack lines upward.
		int zOffset = config.speechHeight();
		Point anchor = Perspective.getCanvasTextLocation(client, graphics, location, lines.get(0), zOffset);
		if (anchor == null)
		{
			return null;
		}

		int alpha = 255;
		long remaining = durationMs - elapsed;
		if (remaining < FADE_MS)
		{
			alpha = (int) Math.max(0, Math.min(255, (remaining * 255) / FADE_MS));
		}

		Color textColor = config.speechColor();
		Color faded = new Color(textColor.getRed(), textColor.getGreen(), textColor.getBlue(),
			Math.min(alpha, textColor.getAlpha()));
		// Overhead chat uses a hard black drop shadow one pixel down-right, at full
		// opacity - not the soft translucent shadow a UI overlay would use.
		Color shadow = new Color(0, 0, 0, alpha);

		// getCanvasTextLocation already centres on the first line, so centre every
		// other line against that same anchor rather than left-aligning them.
		int firstWidth = metrics.stringWidth(lines.get(0));
		int centreX = anchor.getX() + firstWidth / 2;
		int topY = anchor.getY() - (lines.size() - 1) * lineHeight;

		for (int i = 0; i < lines.size(); i++)
		{
			String line = lines.get(i);
			int x = centreX - metrics.stringWidth(line) / 2;
			int y = topY + i * lineHeight;

			graphics.setColor(shadow);
			graphics.drawString(line, x + 1, y + 1);
			graphics.setColor(faded);
			graphics.drawString(line, x, y);
		}

		return null;
	}

	private static List<String> wrap(String text, FontMetrics metrics)
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

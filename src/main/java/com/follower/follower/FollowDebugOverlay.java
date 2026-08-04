package com.follower.follower;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Live view of the follow state - gap, queues, speed, gait - so movement
 * behaviour can be watched rather than inferred. Toggled by
 * {@code ::follower followtrace}, which also streams FTRACE rows to the log.
 */
@Singleton
public class FollowDebugOverlay extends OverlayPanel
{
	private final FollowerEntity follower;

	@Inject
	public FollowDebugOverlay(FollowerEntity follower)
	{
		this.follower = follower;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!follower.isDebugEnabled())
		{
			return null;
		}

		panelComponent.getChildren().add(TitleComponent.builder().text("Follow debug").build());
		for (String line : follower.debugLines())
		{
			panelComponent.getChildren().add(LineComponent.builder().left(line).build());
		}
		return super.render(graphics);
	}
}

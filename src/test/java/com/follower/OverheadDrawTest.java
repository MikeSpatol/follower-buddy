package com.follower;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.Alpha;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The overhead line, drawn as the game draws one (QA, 16 Oct): over the
 * follower only while the follower is there to be seen, and never
 * translucent.
 */
public class OverheadDrawTest
{
	private boolean shown = true;
	private int placed;

	private final FollowerEntity follower = new FollowerEntity(null, null)
	{
		@Override
		public boolean isSpawned()
		{
			return true;
		}

		@Override
		public boolean isShown()
		{
			return shown;
		}

		@Override
		public LocalPoint getLastRenderedLocation()
		{
			// Asked for only when the line is about to be placed over it.
			placed++;
			return null;
		}
	};

	@Test
	public void theLineWaitsOutOfSightWithItsFollower()
	{
		FollowerOverlay overlay = new FollowerOverlay(null, follower, null, null);
		overlay.show("Hm.", 60_000);

		// An NPC stopped on its tile, a teleport, the comedy death.
		shown = false;
		assertNull(overlay.render(null));
		assertEquals("not drawn over the empty tile, or the NPC on it", 0, placed);

		shown = true;
		overlay.render(null);
		assertEquals("back with the follower, its time not up", 1, placed);
	}

	@Test
	public void itsTimeDoesNotRunOutWhileItsFollowerIsUnseen() throws InterruptedException
	{
		FollowerOverlay overlay = new FollowerOverlay(null, follower, null, null);
		overlay.show("Hm.", 200);

		// Hidden for longer than the line lasts.
		shown = false;
		overlay.render(null);
		Thread.sleep(500);
		overlay.render(null);

		shown = true;
		overlay.render(null);
		assertEquals("still there to be read once the follower is back", 1, placed);
	}

	/**
	 * Agility can leave the follower on the ground while the player is up
	 * on a roof. The line goes over the follower, at its own floor's height,
	 * not at the player's floor three floors up (QA4 #13).
	 */
	@Test
	public void theLineIsDrawnAtTheFollowersOwnFloor()
	{
		LocalPoint at = new LocalPoint(52 * 128 + 64, 52 * 128 + 64, 0);
		Client client = projecting(3);
		FollowerEntity below = new FollowerEntity(null, null)
		{
			@Override
			public boolean isSpawned()
			{
				return true;
			}

			@Override
			public boolean isShown()
			{
				return true;
			}

			@Override
			public LocalPoint getLastRenderedLocation()
			{
				return at;
			}

			@Override
			public WorldPoint getWorldLocation()
			{
				return new WorldPoint(52, 52, 0);
			}
		};
		FollowerOverlay overlay = new FollowerOverlay(client, below, new FollowerConfig()
		{
		}, loaded());
		overlay.show("Hm.", 60_000);
		BufferedImage canvas = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = canvas.createGraphics();
		overlay.render(graphics);
		graphics.dispose();

		int zOffset = new FollowerConfig()
		{
		}.speechHeight();
		int own = Perspective.localToCanvas(client, at, 0, zOffset).getY();
		int players = Perspective.localToCanvas(client, at, 3, zOffset).getY();
		int lowest = -1;
		for (int y = 0; y < canvas.getHeight(); y++)
		{
			for (int x = 0; x < canvas.getWidth(); x++)
			{
				if ((canvas.getRGB(x, y) >>> 24) != 0)
				{
					lowest = y;
				}
			}
		}
		assertTrue("the floors are well apart on screen: " + own + " and " + players, Math.abs(own - players) > 40);
		assertTrue("drawn on the follower's floor (" + own + "), not the player's (" + players + "): " + lowest,
			Math.abs(lowest - own) <= 4);
	}
	
	private static GameFontRepository loaded()
	{
		GameFontRepository fonts = new GameFontRepository(new Gson());
		fonts.load();
		return fonts;
	}

	/**
	 * A client that projects a fixed camera over floors 240 units apart, the
	 * one the view says is current being the player's.
	 */
	private static Client projecting(int playersFloor)
	{
		int[][][] heights = new int[4][105][105];
		for (int plane = 0; plane < 4; plane++)
		{
			for (int[] row : heights[plane])
			{
				java.util.Arrays.fill(row, -240 * plane);
			}
		}
		WorldView view = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(),
			new Class<?>[]{WorldView.class}, (self, method, args) ->
			{
				switch (method.getName())
				{
					case "getSizeX":
					case "getSizeY":
						return 104;
					case "getTileHeights":
						return heights;
					case "getTileSettings":
						return new byte[4][104][104];
					case "getPlane":
						return playersFloor;
					default:
						return method.getReturnType() == int.class ? 0 : null;
				}
			});
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(self, method, args) ->
			{
				switch (method.getName())
				{
					case "getWorldView":
					case "getTopLevelWorldView":
						return view;
					case "isGpu":
						return false;
					case "getCameraX":
						return 52 * 128 + 64;
					case "getCameraY":
						return 40 * 128;
					case "getCameraZ":
						return -1200;
					case "getCameraPitch":
						return 256;
					case "getCameraYaw":
						return 0;
					case "getScale":
						return 512;
					case "getViewportWidth":
						return 800;
					case "getViewportHeight":
						return 600;
					default:
						return method.getReturnType() == int.class ? 0
							: method.getReturnType() == boolean.class ? false : null;
				}
			});
	}

	@Test
	public void theColourHasNoOpacityToSet() throws NoSuchMethodException
	{
		// The game draws overhead text opaque, and so does the bitmap font
		// here, so a slider for it would do nothing.
		assertNull(FollowerConfig.class.getMethod("speechColor").getAnnotation(Alpha.class));
	}
}

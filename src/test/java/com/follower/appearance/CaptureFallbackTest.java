package com.follower.appearance;

import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.kit.KitType;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The live capture, now that RuneLite 1.13 has no way to detach a model from
 * the client's shared actor cache.
 *
 * <p>The player's live model is a slot the client recycles for other actors.
 * Handed to the follower, it turns the follower into whichever NPC or player
 * reuses the slot next, so a capture that cannot copy it hands back nothing.
 */
public class CaptureFallbackTest
{
	private final int[] equipment = new int[Outfit.SLOTS];
	private int[] worn;
	private int hashes;
	private Model live;
	private CaptureFallback capture;
	private final Outfit outfit = new Outfit();

	private boolean answered;
	private Model captured;

	/** Answers {@code method} with {@code answer}, and everything else with nothing. */
	private static Object proxy(Class<?> type, String method, Object answer)
	{
		return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
			(self, called, args) ->
			{
				if (called.getName().equals(method))
				{
					return answer;
				}
				Class<?> returns = called.getReturnType();
				return returns == int.class ? 0 : returns == boolean.class ? false : null;
			});
	}

	@Before
	public void wearOwnGear()
	{
		for (int i = 0; i < equipment.length; i++)
		{
			equipment[i] = 256 + i;
		}
		worn = equipment.clone();

		live = (Model) proxy(Model.class, "toString", "the live model");
		PlayerComposition composition = (PlayerComposition) Proxy.newProxyInstance(
			PlayerComposition.class.getClassLoader(), new Class<?>[]{PlayerComposition.class},
			(self, called, args) ->
			{
				switch (called.getName())
				{
					case "getEquipmentIds":
						return equipment;
					case "setHash":
						hashes++;
						return null;
					default:
						Class<?> returns = called.getReturnType();
						return returns == int.class ? 0 : returns == boolean.class ? false : null;
				}
			});
		Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
			(self, called, args) ->
			{
				switch (called.getName())
				{
					case "getModel":
						return live;
					case "getPlayerComposition":
						return composition;
					default:
						Class<?> returns = called.getReturnType();
						return returns == int.class ? 0 : returns == boolean.class ? false : null;
				}
			});
		Client client = (Client) proxy(Client.class, "getLocalPlayer", player);

		outfit.setItem(KitType.WEAPON, 4151);
		captured = live;
		capture = new CaptureFallback(client);
	}

	private void request()
	{
		capture.request(outfit, model ->
		{
			answered = true;
			captured = model;
		});
	}

	@Test
	public void aCaptureNeverHandsBackTheSharedModel()
	{
		request();
		capture.tick();
		capture.tick();

		assertTrue(answered);
		assertNull("nothing from the shared cache", captured);
		assertFalse(capture.isBusy());
		assertArrayEquals("the player's own gear goes back", worn, equipment);
	}

	/**
	 * With nothing to take back, the swap would only show the follower's outfit
	 * on the player for a frame, and again on every request after, since an
	 * empty answer is not remembered.
	 */
	@Test
	public void aCaptureNeverPutsTheOutfitOnThePlayer()
	{
		request();

		assertArrayEquals("the player's own gear never came off", worn, equipment);
		assertEquals("the player's look was never rebuilt", 0, hashes);
		assertTrue("answered at once", answered);
		assertNull(captured);
		assertFalse(capture.isBusy());
	}
}

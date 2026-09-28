package com.follower.appearance;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.kit.KitType;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
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

	@Test
	public void aCaptureNeverHandsBackTheSharedModel()
	{
		int[] equipment = new int[Outfit.SLOTS];
		for (int i = 0; i < equipment.length; i++)
		{
			equipment[i] = 256 + i;
		}
		int[] worn = equipment.clone();

		Model live = (Model) proxy(Model.class, "toString", "the live model");
		PlayerComposition composition = (PlayerComposition) proxy(PlayerComposition.class, "getEquipmentIds", equipment);
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

		Outfit outfit = new Outfit();
		outfit.setItem(KitType.WEAPON, 4151);

		AtomicBoolean answered = new AtomicBoolean();
		AtomicReference<Model> captured = new AtomicReference<>(live);
		CaptureFallback capture = new CaptureFallback(client);
		capture.request(outfit, model ->
		{
			answered.set(true);
			captured.set(model);
		});
		assertTrue("the outfit went on", capture.isBusy());

		capture.tick();
		capture.tick();

		assertTrue(answered.get());
		assertNull("nothing from the shared cache", captured.get());
		assertFalse(capture.isBusy());
		assertArrayEquals("the player's own gear goes back", worn, equipment);
	}
}

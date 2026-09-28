package com.follower.appearance;

import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;

/**
 * Fallback path for outfits the cache dump can't build: temporarily overwrite the
 * local player's composition with the target outfit, let the client render one
 * frame, steal the resulting {@link Model}, then put everything back.
 *
 * <p>Two known costs, both unavoidable with this technique:
 * <ul>
 *   <li>Your own character visibly wears the follower's outfit for one frame.</li>
 *   <li>The captured model is already posed at whatever animation frame was
 *       current, so it can't be re-animated — see {@link ComposedAppearance}.</li>
 * </ul>
 *
 * <p>Since RuneLite 1.13 the stolen model cannot be detached from the client's
 * shared cache, so {@link #request} answers null before the outfit goes on, and
 * the swap below is not reached; see {@link #tick()}.
 *
 * <p>All methods must be called on the client thread. {@link #tick()} drives the
 * one-frame delay and should be called from a per-frame event.
 */
@Slf4j
@Singleton
public class CaptureFallback
{
	private enum State
	{
		IDLE,
		SWAPPED,
	}

	private final Client client;

	private State state = State.IDLE;
	private Outfit pending;
	private Consumer<Model> callback;
	private int[] savedEquipment;
	private int framesWaited;
	private boolean noDetachLogged;

	@Inject
	public CaptureFallback(Client client)
	{
		this.client = client;
	}

	public boolean isBusy()
	{
		return state != State.IDLE;
	}

	/**
	 * Queues a capture. The callback fires on the client thread with the captured
	 * model, or with null if the capture could not be completed.
	 */
	public void request(Outfit outfit, Consumer<Model> onCaptured)
	{
		if (isBusy())
		{
			log.debug("Capture already in flight, dropping request");
			onCaptured.accept(null);
			return;
		}

		// With no way to detach what a swap would capture (see tick), putting the
		// outfit on would only show it on the player for a frame, and again on
		// every request after, since an empty answer is not remembered. So the
		// request is answered empty before anything is worn.
		if (!noDetachLogged)
		{
			noDetachLogged = true;
			log.debug("Capture has no way to detach the player model from the shared cache; "
				+ "capturing nothing. Use the cache dump path.");
		}
		onCaptured.accept(null);
	}

	public void tick()
	{
		if (state != State.SWAPPED)
		{
			return;
		}

		// Give the client one frame to rebuild the model from the new hash.
		if (framesWaited++ < 1)
		{
			return;
		}

		// Actor#getModel hands back a reference into the client's shared actor
		// model cache. That slot gets recycled for other actors, so holding the
		// reference makes the follower visibly turn into whichever nearby NPC or
		// player reused it. A single-model merge used to detach a copy, but
		// RuneLite 1.13 removed the Model merge with nothing in its place, so
		// there is no copy to hand back and the capture comes back empty.
		swapOut();
		finish(null);
	}

	/** Restores the player's real appearance. Safe to call at any time. */
	public void abort()
	{
		if (state == State.SWAPPED)
		{
			swapOut();
		}
		finish(null);
	}

	private boolean swapIn()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return false;
		}

		PlayerComposition composition = local.getPlayerComposition();
		if (composition == null)
		{
			return false;
		}

		int[] live = composition.getEquipmentIds();
		if (live == null || live.length < Outfit.SLOTS)
		{
			return false;
		}

		savedEquipment = live.clone();
		// The live array uses the game's encoding, not our internal one.
		int[] desired = pending.toGameEquipmentIds();
		System.arraycopy(desired, 0, live, 0, Outfit.SLOTS);
		composition.setHash();

		state = State.SWAPPED;
		return true;
	}

	private void swapOut()
	{
		Player local = client.getLocalPlayer();
		PlayerComposition composition = local == null ? null : local.getPlayerComposition();
		if (composition != null && savedEquipment != null)
		{
			int[] live = composition.getEquipmentIds();
			if (live != null && live.length >= Outfit.SLOTS)
			{
				System.arraycopy(savedEquipment, 0, live, 0, Outfit.SLOTS);
				composition.setHash();
			}
		}
		savedEquipment = null;
		state = State.IDLE;
	}

	private void finish(Model model)
	{
		Consumer<Model> cb = callback;
		callback = null;
		pending = null;
		state = State.IDLE;
		if (cb != null)
		{
			cb.accept(model);
		}
	}
}

package com.follower.appearance;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;

/**
 * Automates the palette harvest: steps the local player's colour indices through
 * every value of every body-colour slot, letting the client rebuild the model each
 * time and extracting the exact recolour pairs, then restores the real colours.
 *
 * <p>This is the same purely client-side composition mutation the capture fallback
 * already performs for equipment (and that Fashionscape ships for colours): nothing
 * is sent to the server, no input is synthesised - the character just visibly
 * flickers through the palette for a few seconds while it runs.
 *
 * <p>All entry points must be called on the client thread; {@link #tick()} is
 * driven per frame so each index gets a couple of frames to rebuild before its
 * model is read.
 */
@Slf4j
@Singleton
public class ColorHarvester
{
	/**
	 * Colour count per body-colour slot: hair, torso, legs, boots, skin - from the
	 * in-game design interface. Indices past a table's real end are simply skipped
	 * by the client's own bounds check, so overshooting is harmless.
	 */
	private static final int[] SLOT_SIZES = {30, 29, 29, 6, 13};

	private static final String[] SLOT_NAMES = {"hair", "torso", "legs", "boots", "skin"};

	/** Frames to let the client rebuild the model after a colour change. */
	private static final int SETTLE_FRAMES = 2;

	private final Client client;
	private final AppearanceComposer composer;
	private final PaletteHarvest harvest;

	private boolean running;
	private int slot;
	private int index;
	private int framesWaited;
	private int[] savedColors;
	private int extracted;
	private Consumer<String> status;

	@Inject
	public ColorHarvester(Client client, AppearanceComposer composer, PaletteHarvest harvest)
	{
		this.client = client;
		this.composer = composer;
		this.harvest = harvest;
	}

	public boolean isRunning()
	{
		return running;
	}

	public void start(Consumer<String> statusSink)
	{
		if (running)
		{
			statusSink.accept("Harvest already running.");
			return;
		}

		PlayerComposition composition = composition();
		int[] colors = composition == null ? null : composition.getColors();
		if (colors == null || colors.length < SLOT_SIZES.length)
		{
			statusSink.accept("No player colours to harvest yet.");
			return;
		}

		savedColors = colors.clone();
		status = statusSink;
		running = true;
		slot = 0;
		index = 0;
		framesWaited = 0;
		extracted = 0;

		statusSink.accept("Harvesting all body colours - your character will flicker "
			+ "for a few seconds. Gear that hides hair (helmets) blocks those entries.");
		apply();
	}

	/** Restores the player's real colours. Safe to call at any time. */
	public void abort()
	{
		if (!running)
		{
			return;
		}
		restore();
		running = false;
		if (status != null)
		{
			status.accept("Harvest aborted; colours restored.");
		}
	}

	public void tick()
	{
		if (!running)
		{
			return;
		}
		if (framesWaited++ < SETTLE_FRAMES)
		{
			return;
		}
		framesWaited = 0;

		Player local = client.getLocalPlayer();
		PlayerComposition composition = composition();
		if (local == null || composition == null)
		{
			abort();
			return;
		}

		Map<Short, Short> pairs = new LinkedHashMap<>();
		composer.comparePalette(Outfit.from(composition), local.getModel(), pairs);
		if (!pairs.isEmpty())
		{
			harvest.record(composition.getColors(), pairs);
			extracted++;
		}

		index++;
		if (index >= SLOT_SIZES[slot])
		{
			index = 0;
			slot++;
			if (slot < SLOT_SIZES.length && status != null)
			{
				status.accept("Harvesting " + SLOT_NAMES[slot] + " colours...");
			}
		}
		if (slot >= SLOT_SIZES.length)
		{
			restore();
			running = false;
			if (status != null)
			{
				status.accept("Harvest complete: " + extracted + " extractions, "
					+ harvest.size() + " distinct colour sets banked.");
			}
			return;
		}

		apply();
	}

	private void apply()
	{
		PlayerComposition composition = composition();
		if (composition == null)
		{
			abort();
			return;
		}
		int[] colors = composition.getColors();
		if (colors == null)
		{
			abort();
			return;
		}
		// Reset the previous slot to the player's own colour before mutating the
		// next, so each run varies exactly one index against a known baseline.
		System.arraycopy(savedColors, 0, colors, 0, savedColors.length);
		colors[slot] = index;
		composition.setHash();
	}

	private void restore()
	{
		PlayerComposition composition = composition();
		if (composition != null && savedColors != null)
		{
			int[] colors = composition.getColors();
			if (colors != null)
			{
				System.arraycopy(savedColors, 0, colors, 0,
					Math.min(colors.length, savedColors.length));
				composition.setHash();
			}
		}
		savedColors = null;
	}

	private PlayerComposition composition()
	{
		Player local = client.getLocalPlayer();
		return local == null ? null : local.getPlayerComposition();
	}
}

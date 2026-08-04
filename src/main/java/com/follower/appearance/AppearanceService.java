package com.follower.appearance;

import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Model;

/**
 * Turns an {@link Outfit} into a {@link ComposedAppearance}, preferring the cache
 * dump and falling back to live capture. Results are memoised on the outfit, so
 * an unchanged outfit costs nothing per frame.
 *
 * <p>Client thread only.
 */
@Slf4j
@Singleton
public class AppearanceService
{
	private final AppearanceComposer composer;
	private final CaptureFallback capture;

	@Getter
	private ComposedAppearance current;

	private Outfit inFlight;

	@Inject
	public AppearanceService(AppearanceComposer composer, CaptureFallback capture)
	{
		this.composer = composer;
		this.capture = capture;
	}

	/** Drives the capture state machine. Call once per frame. */
	public void tick()
	{
		capture.tick();
	}

	/**
	 * Requests an appearance for {@code outfit}. The callback fires immediately for
	 * the cached and dump paths, or a frame later for the capture path. It may
	 * receive null if neither path could produce a model.
	 */
	public void request(Outfit outfit, ModelSource source, Consumer<ComposedAppearance> onReady)
	{
		if (current != null && current.getOutfit().equals(outfit))
		{
			onReady.accept(current);
			return;
		}

		if (outfit.equals(inFlight))
		{
			// Capture already running for this exact outfit; let it land.
			return;
		}

		if (source != ModelSource.CAPTURE_ONLY)
		{
			Model model = composer.compose(outfit);
			if (model != null)
			{
				publish(new ComposedAppearance(model, ComposedAppearance.Source.DUMP, outfit), onReady);
				return;
			}

			if (source == ModelSource.DUMP_ONLY)
			{
				log.warn("No model data for outfit {} and fallback is disabled", outfit);
				onReady.accept(null);
				return;
			}

			log.debug("Dump could not build {}, falling back to capture", outfit);
		}

		inFlight = new Outfit(outfit);
		capture.request(outfit, model ->
		{
			inFlight = null;
			if (model == null)
			{
				onReady.accept(null);
				return;
			}
			publish(new ComposedAppearance(model, ComposedAppearance.Source.CAPTURE, outfit), onReady);
		});
	}

	private void publish(ComposedAppearance appearance, Consumer<ComposedAppearance> onReady)
	{
		current = appearance;
		onReady.accept(appearance);
	}

	public void invalidate()
	{
		current = null;
		inFlight = null;
		capture.abort();
	}
}

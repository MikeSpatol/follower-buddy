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

	@Getter
	private ComposedAppearance current;

	@Inject
	public AppearanceService(AppearanceComposer composer)
	{
		this.composer = composer;
	}

	/**
	 * Requests an appearance for {@code outfit}. The callback fires immediately for
	 * the cached and dump paths, or a frame later for the capture path. It may
	 * receive null if neither path could produce a model.
	 */
	public void request(Outfit outfit, Consumer<ComposedAppearance> onReady)
	{
		if (current != null && current.getOutfit().equals(outfit))
		{
			onReady.accept(current);
			return;
		}
		Model model = composer.compose(outfit);
		if (model != null)
		{
			publish(new ComposedAppearance(model, outfit), onReady);
			return;
		}
		log.warn("No model data for outfit {} and fallback is disabled", outfit);
		onReady.accept(null);
	}

	private void publish(ComposedAppearance appearance, Consumer<ComposedAppearance> onReady)
	{
		current = appearance;
		onReady.accept(appearance);
	}

	public void invalidate()
	{
		current = null;
	}
}

package com.follower;

import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.*;
import lombok.*;
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
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AppearanceService
{
	/**
	 * How long a look watches for the models it went without, in game ticks:
	 * a minute, well past a slow first download. An id that never loads is
	 * given up on then.
	 */
	static final int WAIT_TICKS = 100;

	final AppearanceComposer composer;

	@Getter
	ComposedAppearance current;

	/**
	 * The models the last build went without because the client was still
	 * fetching them, the look that wait is for, and the ticks left of it.
	 */
	List<Integer> downloading = Collections.emptyList();
	Outfit waitingFor;
	int waitTicks;

	/**
	 * Requests an appearance for {@code outfit}. The callback fires immediately for
	 * the cached and dump paths, or a frame later for the capture path. It may
	 * receive null if neither path could produce a model. It does not fire while
	 * every model the outfit needs is still downloading: the follower stays as it
	 * is until {@link #partsArrived} says one has landed.
	 */
	void request(Outfit outfit, Consumer<ComposedAppearance> onReady)
	{
		// A look built without some of its models is not reused: building it
		// again is what puts them on once they have landed.
		if (current != null && downloading.isEmpty() && current.getOutfit().equals(outfit))
		{
			onReady.accept(current);
			return;
		}
		List<Integer> missing = new ArrayList<>();
		var model = composer.compose(outfit, missing);
		downloading = missing;
		if (!missing.isEmpty() && !outfit.equals(waitingFor))
		{
			// One wait per look: building the same look again does not start it over.
			waitingFor = new Outfit(outfit);
			waitTicks = WAIT_TICKS;
		}
		if (model != null)
		{
			publish(new ComposedAppearance(model, outfit), onReady);
			return;
		}
		if (!missing.isEmpty())
			return;
		log.warn("No model data for outfit {} and fallback is disabled", outfit);
		onReady.accept(null);
	}

	/**
	 * Whether a model the last build went without has landed since, so the look
	 * should be built again. Asked once a tick; a look is watched for
	 * {@link #WAIT_TICKS} at most.
	 */
	boolean partsArrived()
	{
		if (downloading.isEmpty() || waitTicks <= 0)
			return false;
		waitTicks--;
		return composer.anyArrived(downloading);
	}

	void publish(ComposedAppearance appearance, Consumer<ComposedAppearance> onReady)
	{
		current = appearance;
		onReady.accept(appearance);
	}

	void invalidate()
	{
		current = null;
		downloading = Collections.emptyList();
		waitingFor = null;
	}
}

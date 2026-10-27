package com.follower;

import net.runelite.api.Model;
import lombok.Getter;

/**
 * A finished follower model plus what we know about it.
 *
 * <p>{@link #animatable} matters: a model composed from the cache dump is unposed,
 * so pose/idle/walk animations apply cleanly. A model captured from the local
 * player is already posed at whatever frame the client rendered, so applying a
 * second animation on top compounds the transform — those followers stand still.
 */
class ComposedAppearance
{
	@Getter
	final Model model;
	@Getter
	final Outfit outfit;

	ComposedAppearance(Model model, Outfit outfit)
	{
		this.model = model;
		this.outfit = new Outfit(outfit);
	}
}

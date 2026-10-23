package com.follower.appearance;

import net.runelite.api.Model;

/**
 * A finished follower model plus what we know about it.
 *
 * <p>{@link #animatable} matters: a model composed from the cache dump is unposed,
 * so pose/idle/walk animations apply cleanly. A model captured from the local
 * player is already posed at whatever frame the client rendered, so applying a
 * second animation on top compounds the transform — those followers stand still.
 */
public final class ComposedAppearance
{
	private final Model model;
	private final Outfit outfit;

	public ComposedAppearance(Model model, Outfit outfit)
	{
		this.model = model;
		this.outfit = new Outfit(outfit);
	}

	public Model getModel()
	{
		return model;
	}

	public Outfit getOutfit()
	{
		return outfit;
	}
}

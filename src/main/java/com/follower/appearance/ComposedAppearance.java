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
	public enum Source
	{
		/** Built from equipment-models.json via loadModelData + mergeModels. */
		DUMP,
		/** Grabbed from the local player after a temporary composition swap. */
		CAPTURE
	}

	private final Model model;
	private final Source source;
	private final Outfit outfit;

	public ComposedAppearance(Model model, Source source, Outfit outfit)
	{
		this.model = model;
		this.source = source;
		this.outfit = new Outfit(outfit);
	}

	public Model getModel()
	{
		return model;
	}

	public Source getSource()
	{
		return source;
	}

	public Outfit getOutfit()
	{
		return outfit;
	}

	public boolean isAnimatable()
	{
		return source == Source.DUMP;
	}
}

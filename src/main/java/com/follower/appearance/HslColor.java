package com.follower.appearance;

import java.awt.Color;

/**
 * Conversions for the game's packed colour format.
 *
 * <p>Model face colours are 16-bit HSL: hue in bits 10-15, saturation in bits 7-9,
 * luminance in bits 0-6. Lighting rewrites only the luminance bits, which is why
 * hue and saturation read off a lit model are the authored values.
 */
public final class HslColor
{
	private HslColor()
	{
	}

	public static int hue(short packed)
	{
		return (packed >> 10) & 0x3F;
	}

	public static int saturation(short packed)
	{
		return (packed >> 7) & 0x07;
	}

	public static int luminance(short packed)
	{
		return packed & 0x7F;
	}

	/**
	 * Approximates a packed game colour as RGB, for showing swatches in the panel.
	 * The game's own conversion table is internal to the client, so this is a plain
	 * HSL conversion over the same ranges - close enough to pick a colour by eye.
	 * The stored value is always the packed colour itself, never this RGB.
	 */
	public static int toRgb(short packed)
	{
		float h = hue(packed) / 63f;
		float s = saturation(packed) / 7f;
		float l = luminance(packed) / 127f;

		// HSL to RGB via HSB, which java.awt provides directly.
		float brightness = l + s * Math.min(l, 1f - l);
		float saturationHsb = brightness == 0f ? 0f : 2f * (1f - l / brightness);
		return Color.HSBtoRGB(h, Math.max(0f, Math.min(1f, saturationHsb)),
			Math.max(0f, Math.min(1f, brightness))) & 0xFFFFFF;
	}
}

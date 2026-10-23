package com.follower.appearance;

import java.awt.Color;
import net.runelite.api.JagexColor;

/**
 * Conversions for the game's packed colour format.
 *
 * <p>Model face colours are 16-bit HSL: hue in bits 10-15, saturation in bits 7-9,
 * luminance in bits 0-6, unpacked by RuneLite's {@link JagexColor}. Lighting
 * rewrites only the luminance bits, which is why hue and saturation read off a
 * lit model are the authored values.
 */
public final class HslColor
{
	private HslColor()
	{
	}

	/**
	 * Approximates a packed game colour as RGB, for showing swatches in the panel.
	 * The game's own conversion table is internal to the client, so this is a plain
	 * HSL conversion over the same ranges - close enough to pick a colour by eye.
	 * The stored value is always the packed colour itself, never this RGB.
	 */
	public static int toRgb(short packed)
	{
		float h = JagexColor.unpackHue(packed) / 63f;
		float s = JagexColor.unpackSaturation(packed) / 7f;
		float l = JagexColor.unpackLuminance(packed) / 127f;

		// HSL to RGB via HSB, which java.awt provides directly.
		float brightness = l + s * Math.min(l, 1f - l);
		float saturationHsb = brightness == 0f ? 0f : 2f * (1f - l / brightness);
		return Color.HSBtoRGB(h, Math.max(0f, Math.min(1f, saturationHsb)),
			Math.max(0f, Math.min(1f, brightness))) & 0xFFFFFF;
	}
}

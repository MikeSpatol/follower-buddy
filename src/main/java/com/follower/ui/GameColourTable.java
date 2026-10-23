// public:file chathead
package com.follower.ui;

/**
 * The client's 65,536-entry colour table, ported from
 * {@code Pix3D.initColourTable}.
 *
 * <p>The game never converts packed HSL to RGB with a formula at draw time: it
 * precomputes this table - a bespoke HSL-to-RGB with half-step hue/saturation
 * offsets, truncating arithmetic, and a gamma curve from the brightness setting
 * (0.8 at the default "Normal") - and rasterises by interpolating the PACKED
 * index across each triangle, looking up the table per pixel. A plain linear
 * HSL conversion produces visibly different tones, which is why the follower's
 * face never quite matched a real chathead's colours.
 *
 * <p>The client also jitters brightness by up to ±0.015 per login; that noise is
 * deliberately omitted for determinism.
 */
public final class GameColourTable
{
	private static volatile int[] table = build(0.8);
	private static volatile double currentGamma = 0.8;

	private GameColourTable()
	{
	}

	/** RGB for a packed HSL value, through the game's own table. */
	public static int rgb(int packed)
	{
		return table[packed & 0xFFFF];
	}

	/**
	 * Rebuilds for the player's brightness setting, so the follower's head is on
	 * the SAME gamma table as every real chathead on screen. The setting's varp
	 * maps 1..4 to gammas 0.9, 0.8, 0.7, 0.6 (Dark to V.Bright) in the client.
	 */
	public static void setBrightnessSetting(int varpValue)
	{
		double gamma = varpValue >= 1 && varpValue <= 4 ? (10 - varpValue) / 10.0 : 0.8;
		if (gamma != currentGamma)
		{
			currentGamma = gamma;
			table = build(gamma);
		}
	}

	public static double getCurrentGamma()
	{
		return currentGamma;
	}

	private static int[] build(double brightness)
	{
		int[] table = new int[65536];
		int offset = 0;

		for (int y = 0; y < 512; y++)
		{
			double hue = (y / 8) / 64.0 + 0.0078125;
			double saturation = (y & 0x7) / 8.0 + 0.0625;

			for (int x = 0; x < 128; x++)
			{
				// Saturation is never 0 here (it starts at 1/16), so the client's
				// grey branch never runs.
				double lightness = x / 128.0;
				double q;
				if (lightness < 0.5)
				{
					q = lightness * (saturation + 1.0);
				}
				else
				{
					q = lightness + saturation - lightness * saturation;
				}

				double p = lightness * 2.0 - q;

				double t = hue + (1.0 / 3.0);
				if (t > 1.0)
				{
					t--;
				}
				double d = hue - (1.0 / 3.0);
				if (d < 0.0)
				{
					d++;
				}

				table[offset++] = gammaCorrect(channel(p, q, t), brightness) << 16
					| gammaCorrect(channel(p, q, hue), brightness) << 8
					| gammaCorrect(channel(p, q, d), brightness);
			}
		}
		return table;
	}

	private static double channel(double p, double q, double t)
	{
		if (t * 6.0 < 1.0)
		{
			return p + (q - p) * 6.0 * t;
		}
		if (t * 2.0 < 1.0)
		{
			return q;
		}
		if (t * 3.0 < 2.0)
		{
			return p + (q - p) * ((2.0 / 3.0) - t) * 6.0;
		}
		return p;
	}

	/**
	 * One channel, quantised to 8 bits and then gamma-corrected, as the client
	 * does it. Every channel stays below 1.0, so the 8-bit fields never carry
	 * into each other and can be built one at a time.
	 */
	private static int gammaCorrect(double channel, double gamma)
	{
		return (int) (Math.pow((int) (channel * 256.0) / 256.0, gamma) * 256.0);
	}
}

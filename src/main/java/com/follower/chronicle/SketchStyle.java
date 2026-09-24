package com.follower.chronicle;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Turns a frame of the game into a plate in the scribe's hand: a watercolour
 * sketch. The colours of the place stay, so it is still recognisably the
 * place, but softened toward the paper and laid in flat washes as a brush
 * lays them; ink lines go where the picture has its edges; the paper has a
 * grain and its corners are darker, as a page's are.
 *
 * <p>The lines are found at twice the plate's size and the whole is brought
 * down at the end, because edges found after shrinking a frame to a plate
 * are too soft to find at all. Which edges become lines is decided by the
 * picture itself - its strongest few in a hundred - so a busy market and a
 * bare field both get a drawing rather than a smudge or a scribble.
 *
 * <p>Pure: an image in, an image out, the same every time for the same
 * seed, so it can be tested without a client and a plate never shimmers.
 */
public final class SketchStyle
{
	/** The ink, and the paper it is on. */
	static final int INK = 0x3a2a1c;
	static final int PAPER = 0xe8dbba;

	/** How much of the colour survives the brush, and how much the paper lifts it. */
	private static final double SATURATION = 0.85;
	private static final double LIFT = 0.12;

	/** Flat washes: each channel laid in this many steps. */
	private static final int STEPS = 7;

	/** The share of the picture's edges that become lines. */
	private static final double LINE_SHARE = 0.12;

	private SketchStyle()
	{
	}

	/**
	 * The plate: {@code source} brought to {@code width} by {@code height} and
	 * painted over.
	 *
	 * @param seed fixes the paper grain, so a given sketch always looks the same
	 */
	public static BufferedImage draw(BufferedImage source, int width, int height, long seed)
	{
		int w2 = width * 2;
		int h2 = height * 2;
		// The washes: an edge-keeping smoothing that flattens texture into
		// patches of one colour and leaves the outlines of things sharp,
		// which is what a brush does and a blur does not.
		int[] rgb = kuwahara(pixels(scaled(source, w2, h2), w2, h2), w2, h2, 2);
		double[] light = new double[w2 * h2];
		for (int i = 0; i < rgb.length; i++)
		{
			light[i] = luminance(rgb[i]);
		}
		stretch(light);
		// Found on the painted image, whose texture is already gone: the grain
		// of a stone wall is not an edge, and inking it reads as dirt.
		double[] soft = light;

		double[] edges = new double[w2 * h2];
		for (int y = 0; y < h2; y++)
		{
			for (int x = 0; x < w2; x++)
			{
				edges[y * w2 + x] = sobel(soft, w2, h2, x, y);
			}
		}
		double threshold = percentile(edges, 1 - LINE_SHARE);
		double strong = Math.max(threshold + 1e-6, percentile(edges, 0.97));

		int[] painted = new int[w2 * h2];
		for (int y = 0; y < h2; y++)
		{
			for (int x = 0; x < w2; x++)
			{
				int i = y * w2 + x;
				int wash = wash(rgb[i]);
				double ink = Math.min(1, Math.max(0, (edges[i] - threshold) / (strong - threshold)));
				painted[i] = mix(wash, INK, ink * 0.9);
			}
		}

		// Down to the plate, then the paper under it all.
		BufferedImage full = new BufferedImage(w2, h2, BufferedImage.TYPE_INT_RGB);
		full.setRGB(0, 0, w2, h2, painted, 0, w2);
		BufferedImage out = scaled(full, width, height);
		for (int y = 0; y < height; y++)
		{
			for (int x = 0; x < width; x++)
			{
				double grain = (hash(x, y, seed) - 0.5) * 0.07;
				double dx = (x - width / 2.0) / (width / 2.0);
				double dy = (y - height / 2.0) / (height / 2.0);
				double vignette = 0.20 * Math.min(1.0, (dx * dx + dy * dy) * 0.55);
				int c = out.getRGB(x, y) & 0xffffff;
				c = grain >= 0 ? mix(c, PAPER, grain) : mix(c, INK, -grain);
				out.setRGB(x, y, mix(c, INK, vignette));
			}
		}
		return out;
	}

	/**
	 * A colour as the brush lays it: lighter, as watercolour on paper is,
	 * a little less saturated, in flat steps, the paper warming it.
	 */
	private static int wash(int rgb)
	{
		double r = ((rgb >> 16) & 0xff) / 255.0;
		double g = ((rgb >> 8) & 0xff) / 255.0;
		double b = (rgb & 0xff) / 255.0;
		double l = 0.299 * r + 0.587 * g + 0.114 * b;
		// Lightened, and laid in flat steps of value, by scaling the whole
		// colour so the hue holds: stepping each channel apart invents hues.
		double lighter = 0.16 + 0.84 * Math.pow(l, 0.8);
		lighter = Math.round(lighter * (STEPS - 1)) / (double) (STEPS - 1);
		double gain = l > 0.004 ? lighter / l : 0;
		r = Math.min(1, l > 0.004 ? r * gain : lighter);
		g = Math.min(1, l > 0.004 ? g * gain : lighter);
		b = Math.min(1, l > 0.004 ? b * gain : lighter);
		l = 0.299 * r + 0.587 * g + 0.114 * b;
		r = l + (r - l) * SATURATION;
		g = l + (g - l) * SATURATION;
		b = l + (b - l) * SATURATION;
		int stepped = (channel(r) << 16) | (channel(g) << 8) | channel(b);
		// Watercolour is transparent: the paper lifts the colour and warms it.
		int lifted = mix(stepped, 0xffffff, LIFT);
		return multiply(lifted, PAPER);
	}

	private static int multiply(int a, int b)
	{
		int r = ((a >> 16) & 0xff) * ((b >> 16) & 0xff) / 255;
		int g = ((a >> 8) & 0xff) * ((b >> 8) & 0xff) / 255;
		int bl = (a & 0xff) * (b & 0xff) / 255;
		return r << 16 | g << 8 | bl;
	}

	private static double[] blur(double[] v, int w, int h)
	{
		double[] out = new double[w * h];
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				double sum = 0;
				for (int dy = -1; dy <= 1; dy++)
				{
					for (int dx = -1; dx <= 1; dx++)
					{
						sum += at(v, w, h, x + dx, y + dy);
					}
				}
				out[y * w + x] = sum / 9;
			}
		}
		return out;
	}

	private static int channel(double v)
	{
		return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
	}

	/**
	 * The Kuwahara filter: each pixel takes the average colour of whichever
	 * of the four squares meeting at it is the most even. Inside a patch of
	 * texture that flattens it; at an edge the square on one side wins
	 * outright, so the edge stays where it was.
	 */
	static int[] kuwahara(int[] rgb, int w, int h, int radius)
	{
		int[] out = new int[w * h];
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				double bestVariance = Double.MAX_VALUE;
				int best = rgb[y * w + x];
				for (int q = 0; q < 4; q++)
				{
					int x0 = (q & 1) == 0 ? x - radius : x;
					int y0 = (q & 2) == 0 ? y - radius : y;
					double sr = 0;
					double sg = 0;
					double sb = 0;
					double sl = 0;
					double sll = 0;
					int n = 0;
					for (int yy = y0; yy <= y0 + radius; yy++)
					{
						for (int xx = x0; xx <= x0 + radius; xx++)
						{
							int c = rgb[Math.max(0, Math.min(h - 1, yy)) * w + Math.max(0, Math.min(w - 1, xx))];
							int r = (c >> 16) & 0xff;
							int g = (c >> 8) & 0xff;
							int b = c & 0xff;
							double l = 0.299 * r + 0.587 * g + 0.114 * b;
							sr += r;
							sg += g;
							sb += b;
							sl += l;
							sll += l * l;
							n++;
						}
					}
					double mean = sl / n;
					double variance = sll / n - mean * mean;
					if (variance < bestVariance)
					{
						bestVariance = variance;
						best = (int) (sr / n) << 16 | (int) (sg / n) << 8 | (int) (sb / n);
					}
				}
				out[y * w + x] = best;
			}
		}
		return out;
	}

	/** Light spread to fill the range, so a dim cave and a bright field both have edges. */
	private static void stretch(double[] light)
	{
		double low = percentile(light, 0.02);
		double high = percentile(light, 0.98);
		double span = Math.max(0.05, high - low);
		for (int i = 0; i < light.length; i++)
		{
			light[i] = Math.max(0, Math.min(1, (light[i] - low) / span));
		}
	}

	private static double percentile(double[] values, double at)
	{
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		int index = (int) Math.max(0, Math.min(sorted.length - 1, Math.round(at * (sorted.length - 1))));
		return sorted[index];
	}

	/** Area-averaged down (or nearest up) to a size. */
	static BufferedImage scaled(BufferedImage source, int width, int height)
	{
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int sw = source.getWidth();
		int sh = source.getHeight();
		for (int y = 0; y < height; y++)
		{
			int y0 = y * sh / height;
			int y1 = Math.max(y0 + 1, (y + 1) * sh / height);
			for (int x = 0; x < width; x++)
			{
				int x0 = x * sw / width;
				int x1 = Math.max(x0 + 1, (x + 1) * sw / width);
				long r = 0;
				long g = 0;
				long b = 0;
				int n = 0;
				for (int sy = y0; sy < y1 && sy < sh; sy++)
				{
					for (int sx = x0; sx < x1 && sx < sw; sx++)
					{
						int rgb = source.getRGB(sx, sy);
						r += (rgb >> 16) & 0xff;
						g += (rgb >> 8) & 0xff;
						b += rgb & 0xff;
						n++;
					}
				}
				n = Math.max(1, n);
				out.setRGB(x, y, (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n));
			}
		}
		return out;
	}

	private static int[] pixels(BufferedImage image, int w, int h)
	{
		return image.getRGB(0, 0, w, h, null, 0, w);
	}

	static double luminance(int rgb)
	{
		return (0.299 * ((rgb >> 16) & 0xff) + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff)) / 255.0;
	}

	private static double sobel(double[] l, int w, int h, int x, int y)
	{
		double gx = at(l, w, h, x + 1, y - 1) + 2 * at(l, w, h, x + 1, y) + at(l, w, h, x + 1, y + 1)
			- at(l, w, h, x - 1, y - 1) - 2 * at(l, w, h, x - 1, y) - at(l, w, h, x - 1, y + 1);
		double gy = at(l, w, h, x - 1, y + 1) + 2 * at(l, w, h, x, y + 1) + at(l, w, h, x + 1, y + 1)
			- at(l, w, h, x - 1, y - 1) - 2 * at(l, w, h, x, y - 1) - at(l, w, h, x + 1, y - 1);
		return Math.sqrt(gx * gx + gy * gy) / 4.0;
	}

	private static double at(double[] l, int w, int h, int x, int y)
	{
		return l[Math.max(0, Math.min(h - 1, y)) * w + Math.max(0, Math.min(w - 1, x))];
	}

	/** A fixed pseudo-random value in [0, 1) for a pixel and a seed. */
	private static double hash(int x, int y, long seed)
	{
		long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + y * 0x165667B19E3779F9L;
		h ^= h >>> 33;
		h *= 0xFF51AFD7ED558CCDL;
		h ^= h >>> 33;
		return (h >>> 11) / (double) (1L << 53);
	}

	/** {@code a} at 0, {@code b} at 1. */
	static int mix(int a, int b, double t)
	{
		int r = (int) Math.round(((a >> 16) & 0xff) + (((b >> 16) & 0xff) - ((a >> 16) & 0xff)) * t);
		int g = (int) Math.round(((a >> 8) & 0xff) + (((b >> 8) & 0xff) - ((a >> 8) & 0xff)) * t);
		int bl = (int) Math.round((a & 0xff) + ((b & 0xff) - (a & 0xff)) * t);
		return r << 16 | g << 8 | bl;
	}
}

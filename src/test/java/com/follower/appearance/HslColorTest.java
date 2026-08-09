package com.follower.appearance;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The game's packed colour format, unpacked.
 *
 * <p>Sixteen bits: hue in 10-15, saturation in 7-9, luminance in 0-6. Three
 * shifts and three masks, which is exactly the shape of code where an off-by-one
 * produces something that still looks like a colour - the follower comes out
 * slightly the wrong shade and nothing anywhere says why.
 *
 * <p>Everything here is checked against the field layout rather than against
 * remembered values: each case builds a packed colour from known components and
 * asks for them back.
 */
public class HslColorTest
{
	/** Packs the three fields the way the client's model data does. */
	private static short pack(int hue, int saturation, int luminance)
	{
		return (short) ((hue << 10) | (saturation << 7) | luminance);
	}

	@Test
	public void eachFieldComesBackOutOfItsOwnBits()
	{
		short packed = pack(37, 5, 91);

		assertEquals("hue", 37, HslColor.hue(packed));
		assertEquals("saturation", 5, HslColor.saturation(packed));
		assertEquals("luminance", 91, HslColor.luminance(packed));
	}

	@Test
	public void theFieldsDoNotBleedIntoEachOther()
	{
		// The failure a wrong mask gives: a neighbouring field's bits leaking
		// in. Each of these is one field at its maximum with the others zero,
		// so anything reading past its own bits picks the extra up.
		short hueOnly = pack(63, 0, 0);
		assertEquals(63, HslColor.hue(hueOnly));
		assertEquals("saturation read hue's bits", 0, HslColor.saturation(hueOnly));
		assertEquals("luminance read hue's bits", 0, HslColor.luminance(hueOnly));

		short saturationOnly = pack(0, 7, 0);
		assertEquals(7, HslColor.saturation(saturationOnly));
		assertEquals("hue read saturation's bits", 0, HslColor.hue(saturationOnly));
		assertEquals("luminance read saturation's bits", 0, HslColor.luminance(saturationOnly));

		short luminanceOnly = pack(0, 0, 127);
		assertEquals(127, HslColor.luminance(luminanceOnly));
		assertEquals("hue read luminance's bits", 0, HslColor.hue(luminanceOnly));
		assertEquals("saturation read luminance's bits", 0, HslColor.saturation(luminanceOnly));
	}

	@Test
	public void aColourWithEveryBitSetStillReadsInRange()
	{
		// The top bit of a packed colour makes the short negative, so an
		// arithmetic shift drags sign bits down into the hue. Masking is what
		// stops that, and this is the value that proves it.
		short allBits = (short) 0xFFFF;

		assertEquals("hue ran past its six bits", 63, HslColor.hue(allBits));
		assertEquals("saturation ran past its three bits", 7, HslColor.saturation(allBits));
		assertEquals("luminance ran past its seven bits", 127, HslColor.luminance(allBits));
	}

	@Test
	public void everyFieldCoversItsWholeRangeAndNoMore()
	{
		for (int hue = 0; hue <= 63; hue++)
		{
			assertEquals(hue, HslColor.hue(pack(hue, 0, 0)));
		}
		for (int saturation = 0; saturation <= 7; saturation++)
		{
			assertEquals(saturation, HslColor.saturation(pack(0, saturation, 0)));
		}
		for (int luminance = 0; luminance <= 127; luminance++)
		{
			assertEquals(luminance, HslColor.luminance(pack(0, 0, luminance)));
		}
	}

	// ------------------------------------------------------------ the swatch

	@Test
	public void theSwatchStaysInsideTwentyFourBits()
	{
		// It is handed straight to a Swing colour. An alpha byte left in the
		// top would come out as a wrong colour rather than an error.
		for (int hue = 0; hue <= 63; hue += 7)
		{
			for (int saturation = 0; saturation <= 7; saturation++)
			{
				for (int luminance = 0; luminance <= 127; luminance += 13)
				{
					int rgb = HslColor.toRgb(pack(hue, saturation, luminance));
					assertEquals("alpha bits survived in " + hue + "/" + saturation
						+ "/" + luminance, 0, rgb & 0xFF000000);
				}
			}
		}
	}

	@Test
	public void luminanceRunsFromBlackToWhite()
	{
		assertEquals("the darkest colour is black", 0x000000,
			HslColor.toRgb(pack(20, 4, 0)));
		assertEquals("the lightest is white", 0xFFFFFF,
			HslColor.toRgb(pack(20, 4, 127)));
	}

	@Test
	public void noSaturationIsGreyWhateverTheHue()
	{
		// With saturation zero the hue cannot matter. If it does, the
		// conversion has its arguments crossed.
		int first = HslColor.toRgb(pack(0, 0, 64));
		int second = HslColor.toRgb(pack(63, 0, 64));

		assertEquals("hue changed a colour with no saturation in it", first, second);

		int red = (first >> 16) & 0xFF;
		int green = (first >> 8) & 0xFF;
		int blue = first & 0xFF;
		assertTrue("an unsaturated colour is grey, not " + Integer.toHexString(first),
			Math.abs(red - green) <= 1 && Math.abs(green - blue) <= 1);
	}

	@Test
	public void fullSaturationIsNotGrey()
	{
		// The other side of the same check: if saturation were ignored, every
		// swatch in the panel would be a shade of grey.
		int saturated = HslColor.toRgb(pack(0, 7, 64));

		int red = (saturated >> 16) & 0xFF;
		int blue = saturated & 0xFF;
		assertNotEquals("saturation made no difference at all", red, blue);
	}
}

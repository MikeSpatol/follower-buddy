package com.follower.ui;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The gamma table every drawn pixel of the follower's face resolves through.
 *
 * <p>It exists so the follower's chathead sits on the SAME table as every real
 * chathead on screen - the client rebuilds its own when the brightness setting
 * changes, and a head lit differently from the one beside it is immediately
 * obvious. The table is indexed by packed HSL, and the index is masked rather
 * than checked, so the interesting questions are about its edges.
 */
public class GameColourTableTest
{
	@After
	public void restoreDefaultBrightness()
	{
		// Static state shared with every other test in the JVM.
		GameColourTable.setBrightnessSetting(2);
	}

	// ---------------------------------------------------------------- lookup

	@Test
	public void everyPackedValueResolvesToAColour()
	{
		// 16 bits of packed HSL, all of which a model can contain.
		for (int packed = 0; packed <= 0xFFFF; packed++)
		{
			int rgb = GameColourTable.rgb(packed);
			assertTrue("packed " + packed + " gave " + rgb, rgb >= 0);
			assertTrue("packed " + packed + " overflowed 24 bits", rgb <= 0xFFFFFF);
		}
	}

	@Test
	public void anOutOfRangeIndexIsMaskedNotAnException()
	{
		// The rasterizer interpolates colour endpoints and can run past the
		// range; masking is what keeps that from throwing mid-render.
		GameColourTable.rgb(-1);
		GameColourTable.rgb(Integer.MAX_VALUE);
		GameColourTable.rgb(Integer.MIN_VALUE);
		GameColourTable.rgb(0x1FFFF);

		assertEquals("the mask should wrap, not clamp",
			GameColourTable.rgb(0), GameColourTable.rgb(0x10000));
	}

	@Test
	public void theTableIsNotAllOneColour()
	{
		// A build that silently produced zeroes would draw a black face.
		int distinct = 0;
		int previous = -1;
		for (int packed = 0; packed < 4096; packed++)
		{
			int rgb = GameColourTable.rgb(packed);
			if (rgb != previous)
			{
				distinct++;
				previous = rgb;
			}
		}
		assertTrue("the table only produced " + distinct + " runs of colour",
			distinct > 100);
	}

	// ------------------------------------------------------------ brightness

	@Test
	public void theFourBrightnessSettingsMapToTheClientsGammas()
	{
		// The client's varp maps 1..4 to Dark through V.Bright.
		GameColourTable.setBrightnessSetting(1);
		assertEquals(0.9, GameColourTable.getCurrentGamma(), 0.0001);

		GameColourTable.setBrightnessSetting(2);
		assertEquals(0.8, GameColourTable.getCurrentGamma(), 0.0001);

		GameColourTable.setBrightnessSetting(3);
		assertEquals(0.7, GameColourTable.getCurrentGamma(), 0.0001);

		GameColourTable.setBrightnessSetting(4);
		assertEquals(0.6, GameColourTable.getCurrentGamma(), 0.0001);
	}

	@Test
	public void anUnknownSettingFallsBackToTheDefault()
	{
		GameColourTable.setBrightnessSetting(99);
		assertEquals(0.8, GameColourTable.getCurrentGamma(), 0.0001);

		GameColourTable.setBrightnessSetting(-5);
		assertEquals(0.8, GameColourTable.getCurrentGamma(), 0.0001);

		GameColourTable.setBrightnessSetting(0);
		assertEquals(0.8, GameColourTable.getCurrentGamma(), 0.0001);
	}

	@Test
	public void changingBrightnessActuallyChangesTheColours()
	{
		GameColourTable.setBrightnessSetting(1);
		int dark = GameColourTable.rgb(9000);

		GameColourTable.setBrightnessSetting(4);
		int bright = GameColourTable.rgb(9000);

		assertFalse("the table was not rebuilt for the new setting", dark == bright);
	}

	@Test
	public void aBrighterSettingIsActuallyBrighter()
	{
		// Lower gamma means brighter in the client's scheme, which is easy to
		// get backwards.
		GameColourTable.setBrightnessSetting(1);
		long darkTotal = luminanceOver(2000, 12000);

		GameColourTable.setBrightnessSetting(4);
		long brightTotal = luminanceOver(2000, 12000);

		assertTrue("V.Bright came out darker than Dark", brightTotal > darkTotal);
	}

	private static long luminanceOver(int from, int to)
	{
		long total = 0;
		for (int packed = from; packed < to; packed++)
		{
			int rgb = GameColourTable.rgb(packed);
			total += ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
		}
		return total;
	}

	@Test
	public void settingTheSameBrightnessTwiceIsCheapAndStable()
	{
		GameColourTable.setBrightnessSetting(3);
		int first = GameColourTable.rgb(9000);

		for (int i = 0; i < 100; i++)
		{
			GameColourTable.setBrightnessSetting(3);
		}

		assertEquals("re-setting the same value must not disturb the table",
			first, GameColourTable.rgb(9000));
		assertEquals(0.7, GameColourTable.getCurrentGamma(), 0.0001);
	}
}

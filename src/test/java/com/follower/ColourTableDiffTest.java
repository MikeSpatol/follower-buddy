package com.follower;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link GameColourTable} against the table it replaced (kept in
 * {@code ui.before}): the gamma for every brightness setting the varp can
 * hold, including the ones it falls back from, and all 65,536 entries for
 * each, so the whole input domain is compared rather than sampled.
 */
public class ColourTableDiffTest
{
	@After
	public void restoreDefaultBrightness()
	{
		// Static state shared with every other test in the JVM.
		GameColourTable.setBrightnessSetting(2);
		BeforeGameColourTable.setBrightnessSetting(2);
	}

	@Test
	public void everyBrightnessBuildsTheSameTable()
	{
		for (int varp : new int[]{2, 1, 3, 4, 0, -5, 5, 99, Integer.MIN_VALUE, Integer.MAX_VALUE, 2, 1})
		{
			GameColourTable.setBrightnessSetting(varp);
			BeforeGameColourTable.setBrightnessSetting(varp);
			assertEquals("gamma for " + varp, BeforeGameColourTable.getCurrentGamma(),
				GameColourTable.getCurrentGamma(), 0);
			for (int packed = 0; packed <= 0xFFFF; packed++)
			{
				assertEquals("entry " + packed + " at brightness " + varp,
					BeforeGameColourTable.rgb(packed), GameColourTable.rgb(packed));
			}
		}
	}
}

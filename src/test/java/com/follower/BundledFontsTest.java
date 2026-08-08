package com.follower;

import com.follower.ui.GameFont;
import com.follower.ui.GameFontRepository;
import com.google.gson.Gson;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The font dump as a fresh install sees it.
 *
 * <p>Development machines have their own fonts.json in the data directory,
 * which takes precedence, so the bundled copy is the one path that is never
 * exercised while working on the plugin - and the one every hub user gets. The
 * dump is trimmed to the two fonts the plugin draws with, and nothing else
 * would notice if that trim took one too many.
 */
public class BundledFontsTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private GameFontRepository loadAsAFreshInstall() throws IOException
	{
		GameFontRepository fonts = new GameFontRepository(new Gson());
		// An empty directory: no override, so this is the bundled resource.
		fonts.load(folder.newFolder().toPath());
		return fonts;
	}

	@Test
	public void theBundledDumpLoads() throws IOException
	{
		GameFontRepository fonts = loadAsAFreshInstall();
		assertTrue("the bundled dump did not load: " + fonts.getStatus(), fonts.isLoaded());
	}

	@Test
	public void bothFontsThePluginDrawsWithArePresent() throws IOException
	{
		GameFontRepository fonts = loadAsAFreshInstall();

		assertNotNull("font 497 is FollowerDialog's DIALOG_FONT_ID", fonts.get(497));
		assertNotNull("b12_full is what FollowerOverlay draws the overhead line with",
			fonts.getByName("b12_full"));
	}

	@Test
	public void theTrimmedDumpCarriesNothingElse() throws IOException
	{
		GameFontRepository fonts = loadAsAFreshInstall();
		// Not a correctness requirement, a size one: if a font creeps back in,
		// the jar grows by tens of kilobytes for glyphs nothing asks for.
		for (int id : new int[]{494, 495, 645, 646, 764, 819, 1442, 1447, 6315})
		{
			assertEquals("font " + id + " is bundled but never drawn with",
				null, fonts.get(id));
		}
	}

	@Test
	public void theFontsCanActuallyMeasureAndDrawText() throws IOException
	{
		GameFontRepository fonts = loadAsAFreshInstall();

		for (int id : new int[]{496, 497})
		{
			GameFont font = fonts.get(id);
			assertTrue("font " + id + " has no line height", font.getLineHeight() > 0);
			int width = font.stringWidth("The quick brown fox, 0123456789!");
			assertTrue("font " + id + " measured a real sentence as " + width, width > 0);
		}
	}
}

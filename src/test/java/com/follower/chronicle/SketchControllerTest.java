package com.follower.chronicle;

import com.google.gson.Gson;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Which place the scribe sketches, and which part of the view it keeps.
 */
public class SketchControllerTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private ChronicleLoader loader() throws IOException
	{
		ChronicleLoader loader = new ChronicleLoader(new Gson());
		loader.load(folder.newFolder().toPath());
		return loader;
	}

	@Test
	public void standingInAPlaceWithAPageWantsItsSketch() throws IOException
	{
		ChronicleLoader loader = loader();
		Set<String> have = new HashSet<>();
		assertEquals("lumbridge", SketchController.wanted(loader.getEntries(), 12850, have::contains));
		assertEquals("varrock-square", SketchController.wanted(loader.getEntries(), 12853, have::contains));
		assertEquals("varrock-palace", SketchController.wanted(loader.getEntries(), 12854, have::contains));
		assertEquals("grand-exchange", SketchController.wanted(loader.getEntries(), 12598, have::contains));
		assertEquals("falador", SketchController.wanted(loader.getEntries(), 11828, have::contains));
		assertEquals("paterdomus", SketchController.wanted(loader.getEntries(), 13622, have::contains));
		assertEquals("baxtorian-falls", SketchController.wanted(loader.getEntries(), 10038, have::contains));
		assertEquals("morytania", SketchController.wanted(loader.getEntries(), 14131, have::contains));
	}

	@Test
	public void aPlaceAlreadyDrawnIsNotDrawnAgain() throws IOException
	{
		ChronicleLoader loader = loader();
		Set<String> have = new HashSet<>();
		have.add("lumbridge");
		assertNull("one sketch per place, shared by every page that shows it",
			SketchController.wanted(loader.getEntries(), 12850, have::contains));
	}

	@Test
	public void somewhereWithNoPageWantsNothing() throws IOException
	{
		assertNull(SketchController.wanted(loader().getEntries(), 9999, k -> false));
	}

	@Test
	public void resizableKeepsClearOfTheMinimapTheSidePanelAndTheChat()
	{
		Rectangle fixed = SketchController.clearOfInterfaces(4, 4, 512, 334, false);
		assertEquals("fixed mode lays nothing over the view", new Rectangle(4, 4, 512, 334), fixed);

		Rectangle resizable = SketchController.clearOfInterfaces(0, 0, 1600, 900, true);
		assertTrue("left of the side panel and the minimap", resizable.x + resizable.width <= 1600 - 240);
		assertTrue("above the chat", resizable.y + resizable.height <= 900 - 170);
	}

	@Test
	public void theCropIsThePlatesShapeCentredOnYou()
	{
		double aspect = (double) ChronicleLayout.PLATE_W / ChronicleLayout.PLATE_H;
		Rectangle clear = new Rectangle(4, 4, 512, 334);
		Rectangle crop = SketchController.crop(clear, 260, 170, aspect);
		assertEquals("the plate's shape", aspect, crop.width / (double) crop.height, 0.05);
		assertEquals("you in the middle", 260, crop.x + crop.width / 2, 1);
		assertEquals(170, crop.y + crop.height / 2, 1);
	}

	@Test
	public void nearAnEdgeTheCropSlidesBackInsideRatherThanCatchingTheFrame()
	{
		double aspect = (double) ChronicleLayout.PLATE_W / ChronicleLayout.PLATE_H;
		Rectangle clear = new Rectangle(4, 4, 512, 334);
		Rectangle crop = SketchController.crop(clear, 10, 330, aspect);
		assertTrue("inside the clear area: " + crop, clear.contains(crop));
	}

	@Test
	public void aSmallViewStillGivesAPlateShapedCrop()
	{
		double aspect = (double) ChronicleLayout.PLATE_W / ChronicleLayout.PLATE_H;
		Rectangle clear = new Rectangle(0, 0, 300, 60);
		Rectangle crop = SketchController.crop(clear, 150, 30, aspect);
		assertTrue(clear.contains(crop));
		assertEquals(aspect, crop.width / (double) crop.height, 0.1);
	}

	@Test
	public void theLineItSaysIsSaidAfterAndIsTheScribes()
	{
		for (String line : SketchController.DONE)
		{
			assertTrue(line, line.length() <= 85);
			assertTrue(line, line.chars().allMatch(c -> c >= 0x20 && c < 0x7f));
		}
	}
}

package com.follower.chronicle;

import com.follower.follower.ErrandController;
import com.follower.follower.FollowerEntity;
import com.google.gson.Gson;
import java.awt.Rectangle;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

	// ------------------------------------------------- the moment it sketches

	private boolean busy;
	private int idle;
	private int region = 12850;
	private boolean holding;
	/** Whether the scroll will not come out when asked for. */
	private boolean handsRefuse;
	private final List<String> said = new ArrayList<>();
	private FollowerEntity follower;

	/**
	 * The scribe in Lumbridge, which wants a sketch, with the view and the
	 * hands under the test's control. There is no player to crop round, so
	 * nothing is drawn; the line said after is what shows the view was taken.
	 */
	private SketchController scribe() throws IOException
	{
		return scribe(new SketchBook(null));
	}

	private SketchController scribe(SketchBook book) throws IOException
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				Class<?> type = method.getReturnType();
				return type == int.class ? 0 : type == boolean.class ? false : null;
			});
		follower = new FollowerEntity(null, null)
		{
			@Override
			public boolean isSpawned()
			{
				return true;
			}

			@Override
			public boolean isSettled()
			{
				return true;
			}
		};
		ErrandController.Hands hands = new ErrandController.Hands()
		{
			@Override
			public void hold(int itemId)
			{
				holding = !handsRefuse;
			}

			@Override
			public void release()
			{
				holding = false;
			}

			@Override
			public boolean isHolding()
			{
				return holding;
			}
		};
		return new SketchController(client, follower, hands, loader(), book, null, null,
			() -> busy, () -> idle, () -> region, said::add);
	}

	private static void tick(SketchController scribe, int times)
	{
		for (int i = 0; i < times; i++)
		{
			scribe.tick();
		}
	}

	@Test
	public void itSketchesOnceYouStandStillAndSpeaksOnlyWhenTheScrollIsAway() throws IOException
	{
		SketchController scribe = scribe();
		idle = SketchController.IDLE_TICKS - 1;
		scribe.tick();
		assertFalse("not while you might still be moving off", scribe.isSketching());

		idle = SketchController.IDLE_TICKS;
		scribe.tick();
		assertTrue(scribe.isSketching());
		assertTrue("the scroll is out", holding);
		assertEquals(ErrandController.READ_SCROLL_POSE, follower.getPoseOverride());

		tick(scribe, SketchController.HOLD_TICKS + SketchController.PUT_AWAY_TICKS - 1);
		assertTrue("nothing said while the view could still be taken", said.isEmpty());
		scribe.tick();
		assertEquals(1, said.size());
		assertFalse("and the scroll put away", holding);
		assertEquals(0, follower.getPoseOverride());
	}

	@Test
	public void aWindowOpenedMidSketchSpoilsItAndItWaitsBeforeTryingAgain() throws IOException
	{
		// The view is the picture: a bank opened over it, or a fight, is
		// not a sketch of the place.
		SketchController scribe = scribe();
		idle = SketchController.IDLE_TICKS;
		scribe.tick();
		busy = true;
		scribe.tick();
		assertFalse(scribe.isSketching());
		assertFalse(holding);

		busy = false;
		tick(scribe, SketchController.HOLD_TICKS + SketchController.PUT_AWAY_TICKS);
		assertTrue("nothing taken, so nothing said", said.isEmpty());
		tick(scribe, SketchController.RETRY_TICKS - SketchController.HOLD_TICKS - SketchController.PUT_AWAY_TICKS);
		assertFalse("a while before it tries again", scribe.isSketching());
		scribe.tick();
		assertTrue(scribe.isSketching());
	}

	@Test
	public void theScrollWaitsForAnEmptyHand() throws IOException
	{
		// A tool kept between trees is still in the hand once the work stops.
		// The scroll would take its place, and putting it away would let go
		// of both while the loan still thinks the tool is held.
		SketchController scribe = scribe();
		holding = true;
		idle = SketchController.IDLE_TICKS;
		scribe.tick();
		assertFalse("not over the tool", scribe.isSketching());
		tick(scribe, SketchController.HOLD_TICKS + SketchController.PUT_AWAY_TICKS);
		assertTrue("which is still in the hand", holding);
		assertTrue(said.isEmpty());

		holding = false;
		scribe.tick();
		assertTrue("once it is given back, the sketch", scribe.isSketching());
	}

	@Test
	public void walkingOffMidSketchSpoilsItToo() throws IOException
	{
		SketchController scribe = scribe();
		idle = SketchController.IDLE_TICKS;
		scribe.tick();
		idle = 0;
		scribe.tick();
		assertFalse(scribe.isSketching());
		idle = SketchController.IDLE_TICKS;
		tick(scribe, SketchController.HOLD_TICKS + SketchController.PUT_AWAY_TICKS);
		assertTrue(said.isEmpty());
	}

	@Test
	public void aResetMidSketchTakesThePoseOffAndSaysNothing() throws IOException
	{
		// A disable or a logout with the scroll out. The pose is set on the
		// follower, which outlives both, so the reset is the one thing that
		// takes it off.
		SketchController scribe = scribe();
		idle = SketchController.IDLE_TICKS;
		scribe.tick();
		assertTrue(scribe.isSketching());

		scribe.reset();
		assertFalse("the sketch is over", scribe.isSketching());
		assertFalse("the scroll put away", holding);
		assertEquals("and the reading pose with it", 0, follower.getPoseOverride());
		assertTrue("nothing said for a sketch never taken", said.isEmpty());
	}

	// ------------------------------------------------------- the force-path

	@Test
	public void theForcedSketchComesWithoutTheStandStillWait() throws IOException
	{
		SketchController scribe = scribe();
		idle = 1;
		assertEquals("Sketching lumbridge.", scribe.forceSketch());
		assertTrue(scribe.isSketching());
		assertTrue("the scroll is out", holding);
		tick(scribe, SketchController.HOLD_TICKS + SketchController.PUT_AWAY_TICKS);
		assertEquals("the real hold, the real line after", 1, said.size());
		assertFalse(holding);
	}

	@Test
	public void theForcedSketchDoesNotWaitOutASpoiledAttempt() throws IOException
	{
		SketchController scribe = scribe();
		idle = SketchController.IDLE_TICKS;
		scribe.tick();
		busy = true;
		scribe.tick();
		busy = false;
		scribe.tick();
		assertFalse("the tick waits a while after a spoiled one", scribe.isSketching());
		scribe.forceSketch();
		assertTrue("the force does not", scribe.isSketching());
	}

	@Test
	public void aPlaceAlreadyDrawnIsDrawnAgainWithItsFilesKeptFirst() throws IOException
	{
		java.nio.file.Path data = folder.newFolder().toPath();
		SketchBook book = new SketchBook(data);
		book.openFor("acct");
		java.awt.image.BufferedImage old = new java.awt.image.BufferedImage(4, 4,
			java.awt.image.BufferedImage.TYPE_INT_RGB);
		book.put("lumbridge", old, old);
		SketchController scribe = scribe(book);
		idle = 1;
		assertNull("the tick would not draw it again",
			SketchController.wanted(loader().getEntries(), 12850, book::hasBoth));

		String reply = scribe.forceSketch();
		assertEquals("Sketching lumbridge; the old one is kept as lumbridge.large.png.bak,"
			+ " lumbridge.png.bak.", reply);
		java.nio.file.Path acct = data.resolve(SketchBook.DIR_NAME).resolve("acct");
		assertTrue(java.nio.file.Files.isRegularFile(acct.resolve("lumbridge.png.bak")));
		assertTrue(java.nio.file.Files.isRegularFile(acct.resolve("lumbridge.large.png.bak")));
		assertTrue(scribe.isSketching());
	}

	@Test
	public void noBackupNoSketch() throws IOException
	{
		SketchController scribe = scribe(new SketchBook(null)
		{
			@Override
			public List<String> backUp(String key) throws IOException
			{
				throw new IOException("disk full");
			}
		});
		idle = 1;
		assertEquals("Could not back up the old sketch of lumbridge, so it is left alone.",
			scribe.forceSketch());
		assertFalse(scribe.isSketching());
		assertFalse("the scroll put away again", holding);
	}

	@Test
	public void aScrollThatWillNotComeOutLeavesNoCopiesBehind() throws IOException
	{
		// The copies were taken before the scroll was asked for, so a
		// refusal left them on disk and its reply never named them.
		java.nio.file.Path data = folder.newFolder().toPath();
		SketchBook book = new SketchBook(data);
		book.openFor("acct");
		java.awt.image.BufferedImage old = new java.awt.image.BufferedImage(4, 4,
			java.awt.image.BufferedImage.TYPE_INT_RGB);
		book.put("lumbridge", old, old);
		SketchController scribe = scribe(book);
		idle = 1;
		handsRefuse = true;
		assertEquals("The scroll would not come out.", scribe.forceSketch());
		assertFalse(scribe.isSketching());
		java.nio.file.Path acct = data.resolve(SketchBook.DIR_NAME).resolve("acct");
		assertFalse(java.nio.file.Files.exists(acct.resolve("lumbridge.png.bak")));
		assertFalse(java.nio.file.Files.exists(acct.resolve("lumbridge.large.png.bak")));
	}

	@Test
	public void theForcedSketchKeepsTheGates() throws IOException
	{
		SketchController scribe = scribe();
		idle = 1;
		busy = true;
		assertEquals("The follower is busy.", scribe.forceSketch());
		busy = false;
		holding = true;
		assertEquals("The follower's hands are full.", scribe.forceSketch());
		holding = false;
		region = 9999;
		assertEquals("No page sketches this place (region 9999).", scribe.forceSketch());
		assertFalse(scribe.isSketching());

		region = 12850;
		scribe.forceSketch();
		assertEquals("Already sketching.", scribe.forceSketch());
		idle = 0;
		scribe.tick();
		assertFalse("moving off still spoils a forced one", scribe.isSketching());
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

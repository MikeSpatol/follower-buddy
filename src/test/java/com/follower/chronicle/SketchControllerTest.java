package com.follower.chronicle;

import com.follower.follower.ErrandController;
import com.follower.follower.FollowerEntity;
import com.follower.sim.FakeGame;
import com.google.gson.Gson;
import java.awt.Rectangle;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.AnimationID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Which place the scribe sketches, which part of the view it keeps, and
 * the pose it is asked for.
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

	// ------------------------------------------------- posing for a sketch

	private boolean busy;
	private int idle;
	private int region = 12850;
	private boolean holding;
	/** The left hand's thing of a pair, the quill, while it is out; 0 otherwise. */
	private int heldLeft;
	/** Whether the scroll will not come out when asked for. */
	private boolean handsRefuse;
	/** Whether putting it away fails, as a model rebuild off the client thread does. */
	private boolean releaseFails;
	/** Your tile, and what you are playing: -1 for nothing. */
	private WorldPoint tile = new WorldPoint(3222, 3218, 0);
	private int animation = -1;
	/** Whether a right-click menu is open over the view. */
	private boolean menuOpen;
	/** Said through the engine, which the mute holds back. */
	private final List<String> said = new ArrayList<>();
	/** Of those, the ones said ahead of whatever is waiting. */
	private final List<String> saidNext = new ArrayList<>();
	/** Shown straight over its head whatever the mute; null each time it was cleared. */
	private final List<String> overhead = new ArrayList<>();
	/** How often it turned to you. */
	private int faced;
	/** Whether it is held facing you, over watching what you do. */
	private boolean facingYou;
	private FollowerEntity follower;

	/**
	 * The scribe in Lumbridge, which has a plate, with the view, your tile
	 * and the hands under the test's control. There is no player to crop
	 * round, so nothing is drawn; the overhead cleared for the picture and
	 * the line said after are what show the view was taken.
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
				if (method.getName().equals("isMenuOpen"))
				{
					return menuOpen;
				}
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

			@Override
			public void facePlayer()
			{
				faced++;
			}

			@Override
			public void setFacingThePlayer(boolean facing)
			{
				facingYou = facing;
			}
		};
		return scribe(book, client);
	}

	/** The scribe on {@link #follower}, in the given game. */
	private SketchController scribe(SketchBook book, Client client) throws IOException
	{
		ErrandController.Hands hands = new ErrandController.Hands()
		{
			@Override
			public void hold(int itemId)
			{
				holding = !handsRefuse;
			}

			@Override
			public void hold(int leftItemId, int rightItemId)
			{
				hold(rightItemId);
				heldLeft = holding ? leftItemId : 0;
			}

			@Override
			public void release()
			{
				if (releaseFails)
				{
					throw new IllegalStateException("must be called on client thread");
				}
				holding = false;
				heldLeft = 0;
			}

			@Override
			public boolean isHolding()
			{
				return holding;
			}
		};
		return new SketchController(client, follower, hands, loader(), book, null, null,
			() -> busy, () -> idle, () -> region, () -> tile, () -> animation, said::add,
			line ->
			{
				said.add(line);
				saidNext.add(line);
			}, overhead::add);
	}

	private static void tick(SketchController scribe, int times)
	{
		for (int i = 0; i < times; i++)
		{
			scribe.tick();
		}
	}

	private static String last(List<String> lines)
	{
		return lines.get(lines.size() - 1);
	}

	/** The start of the count and each line of it, as shown before the picture. */
	private static List<String> theCount()
	{
		List<String> count = new ArrayList<>();
		count.add(SketchController.START);
		count.addAll(Arrays.asList(SketchController.COUNT));
		return count;
	}

	/** A book with Lumbridge already in it, at both sizes. */
	private SketchBook drawnBook(java.nio.file.Path data)
	{
		SketchBook book = new SketchBook(data);
		book.openFor("acct");
		java.awt.image.BufferedImage old = new java.awt.image.BufferedImage(4, 4,
			java.awt.image.BufferedImage.TYPE_INT_RGB);
		book.put("lumbridge", old, old);
		return book;
	}

	@Test
	public void nothingIsDrawnUnaskedButAnEmptyPlateIsPointedOutOnceASession() throws IOException
	{
		// Play test: a sketch taken while nobody posed is a picture of
		// someone walking past. Now it says so and waits to be asked.
		SketchController scribe = scribe();
		idle = SketchController.IDLE_TICKS - 1;
		scribe.tick();
		assertTrue("not while you might still be moving off", said.isEmpty());

		idle = SketchController.IDLE_TICKS;
		tick(scribe, 20);
		assertEquals("pointed out, once", Collections.singletonList(SketchController.INVITE), said);
		assertTrue("waiting its turn, as any line of its own", saidNext.isEmpty());
		assertFalse("and nothing drawn unasked", scribe.isSketching());
		assertFalse("no scroll out for it", holding);
		assertTrue(overhead.isEmpty());

		region = 12853;
		scribe.tick();
		assertEquals("the square's plate is its own", 2, said.size());
		region = 12850;
		tick(scribe, 20);
		assertEquals("Lumbridge's is not pointed out again", 2, said.size());

		scribe.reset();
		scribe.tick();
		assertEquals("the next session points it out again", 3, said.size());
	}

	@Test
	public void aPlaceAlreadyDrawnIsNotPointedOutButStillOffersSketch() throws IOException
	{
		SketchController scribe = scribe(drawnBook(folder.newFolder().toPath()));
		idle = SketchController.IDLE_TICKS;
		busy = true;
		scribe.tick();
		busy = false;
		tick(scribe, 20);
		assertTrue("nothing to point out", said.isEmpty());
		assertTrue("but Sketch is offered, for a retake", scribe.canSketchNow());
		region = 9999;
		assertFalse("and only where a page has a plate", scribe.canSketchNow());
	}

	/**
	 * Play test, 8 Oct: Sketch was offered where pressing it only got a
	 * refusal. It is offered only when asking would start one.
	 */
	@Test
	public void sketchIsOfferedOnlyWhenAskingWouldStartOne() throws IOException
	{
		SketchController scribe = scribe();
		assertTrue("a plate here, nothing in the way", scribe.canSketchNow());
		busy = true;
		assertFalse("not while something else has it", scribe.canSketchNow());
		busy = false;
		holding = true;
		assertFalse("not with its hands full", scribe.canSketchNow());
		holding = false;
		assertNull("and when it is offered, asking starts one", scribe.askForSketch());
		assertFalse("not while one is under way", scribe.canSketchNow());
	}

	@Test
	public void notPointedOutWithAToolStillInTheHand() throws IOException
	{
		// Between trees the axe is kept a while with nothing else holding
		// the feet. Pointed out then, Sketch was refused for full hands, and
		// the one time a session it is pointed out was spent.
		SketchController scribe = scribe();
		idle = SketchController.IDLE_TICKS;
		holding = true;
		tick(scribe, 20);
		assertTrue(said.isEmpty());
		holding = false;
		scribe.tick();
		assertEquals("once the hand is empty", Collections.singletonList(SketchController.INVITE), said);
	}

	@Test
	public void notPointedOutMidFight() throws IOException
	{
		SketchController scribe = scribe();
		idle = SketchController.IDLE_TICKS;
		busy = true;
		tick(scribe, 20);
		assertTrue(said.isEmpty());
		busy = false;
		scribe.tick();
		assertEquals(1, said.size());
	}

	@Test
	public void askingTakesOutTheScrollTurnsToYouAndCountsYouIn() throws IOException
	{
		SketchController scribe = scribe();
		assertNull(scribe.askForSketch());
		assertTrue(scribe.isSketching());
		assertTrue("other lines wait", scribe.isPosing());
		assertTrue("the scroll is out", holding);
		assertEquals("and the quill to write in it with", ErrandController.QUILL_PROP, heldLeft);
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.getPoseOverride());
		assertEquals("turned to you", 1, faced);
		assertTrue("and held on you, whatever you do", facingYou);
		assertEquals(Collections.singletonList(SketchController.START), overhead);
		assertTrue("the count is not said through the engine, so the mute does not hold it", said.isEmpty());
	}

	@Test
	public void itKeepsFacingYouThroughThePose() throws IOException, ReflectiveOperationException
	{
		// Following and standing, it watches your work: while you animate
		// it is turned the way you are turned. The emote is the pose, and it
		// was drawn side-on to you, or with its back to you. The frame that
		// turns it cannot run headless, so its own step is called.
		FakeGame game = new FakeGame().at(50, 50, 0);
		LocalPoint itsSpot = game.player.getLocalLocation();
		// You three tiles south of it, turned east.
		game.at(50, 47, 0).turned(1536);
		follower = new FollowerEntity(game.client, null)
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
		Field drawnAt = FollowerEntity.class.getDeclaredField("lastRenderedLocation");
		drawnAt.setAccessible(true);
		drawnAt.set(follower, itsSpot);
		Method watchTheWork = FollowerEntity.class.getDeclaredMethod("watchTheWork", Player.class);
		watchTheWork.setAccessible(true);
		Field dstYaw = FollowerEntity.class.getDeclaredField("dstYaw");
		dstYaw.setAccessible(true);

		SketchController scribe = scribe(new SketchBook(null), game.client);
		assertNull(scribe.askForSketch());
		game.animating(AnimationID.EMOTE_DANCE);
		watchTheWork.invoke(follower, game.player);
		assertEquals("still facing you, to its south", 0, dstYaw.get(follower));

		// Asked for where the wander had taken it to look at something to
		// its north: the drift is kept through the pose, and so was the look.
		Field tile = FollowerEntity.class.getDeclaredField("tile");
		tile.setAccessible(true);
		tile.set(follower, new WorldPoint(50, 50, 0));
		follower.setStayFaceTile(new WorldPoint(50, 53, 0));
		Method faceStayAnchor = FollowerEntity.class.getDeclaredMethod("faceStayAnchor");
		faceStayAnchor.setAccessible(true);
		faceStayAnchor.invoke(follower);
		assertEquals("you, not what it drifted over to", 0, dstYaw.get(follower));

		scribe.abort();
		watchTheWork.invoke(follower, game.player);
		assertEquals("the scroll away, it watches what you do again", 1536, dstYaw.get(follower));
		faceStayAnchor.invoke(follower);
		assertEquals("and at the thing again", 1024, dstYaw.get(follower));
	}

	@Test
	public void standingStillIsTakenAtTheEndOfTheCountWithNoWordsInThePicture() throws IOException
	{
		SketchController scribe = scribe();
		scribe.askForSketch();
		tick(scribe, SketchController.COUNT_TICKS - 1);
		assertEquals("counted in", theCount(), overhead);
		assertTrue(scribe.isPosing());

		scribe.tick();
		assertNull("cleared for the picture, which is the next frame", last(overhead));
		assertTrue("nothing said into it", said.isEmpty());
		assertTrue("and other lines still held", scribe.isPosing());

		scribe.tick();
		assertEquals("then the word, after", SketchController.THERE, last(overhead));
		assertTrue(said.isEmpty());
		assertTrue("still facing you", facingYou);

		scribe.tick();
		assertEquals(1, said.size());
		assertTrue("the usual line for a pose held still", Arrays.asList(SketchController.DONE).contains(said.get(0)));
		assertEquals("said next, ahead of what waited through the pose", said, saidNext);
		assertFalse("the scroll put away", holding);
		assertEquals(0, follower.getPoseOverride());
		assertFalse("free to watch what you do again", facingYou);
		assertFalse(scribe.isPosing());

		double seconds = (SketchController.COUNT_TICKS + 1) * 0.6;
		assertTrue("about five seconds from the ask to the word: " + seconds, seconds >= 4 && seconds <= 6);
	}

	@Test
	public void anEmoteIsTakenAMomentInAndEndsTheCount() throws IOException
	{
		SketchController scribe = scribe();
		scribe.askForSketch();
		scribe.tick();
		animation = AnimationID.EMOTE_DANCE;
		// An emote zeroes the stand-still count, which spoiled every
		// sketch it was done in. The pose is held on the tile instead.
		idle = 0;
		scribe.tick();
		assertTrue(scribe.isSketching());
		tick(scribe, SketchController.POSE_DELAY - 1);
		assertFalse("not on its first frames", overhead.contains(null));
		assertEquals("and no more counting once you pose", Collections.singletonList(SketchController.START),
			overhead);

		scribe.tick();
		assertNull("taken", last(overhead));
		scribe.tick();
		assertEquals(SketchController.THERE, last(overhead));
		scribe.tick();
		assertEquals("a word about the pose", Collections.singletonList(
			SketchController.POSED.get(AnimationID.EMOTE_DANCE)), said);
	}

	@Test
	public void anEmoteItHasNoWordForGetsTheUsualLine() throws IOException
	{
		SketchController scribe = scribe();
		scribe.askForSketch();
		animation = AnimationID.EMOTE_JUMP_WITH_JOY;
		tick(scribe, 1 + SketchController.POSE_DELAY + SketchController.PUT_AWAY_TICKS);
		assertEquals(1, said.size());
		assertTrue(Arrays.asList(SketchController.DONE).contains(said.get(0)));
	}

	@Test
	public void steppingOffYourTileSpoilsItAndItSaysSo() throws IOException
	{
		SketchController scribe = scribe();
		scribe.askForSketch();
		scribe.tick();
		tile = tile.dx(1);
		scribe.tick();
		assertFalse(scribe.isSketching());
		assertFalse("the scroll put away", holding);
		assertEquals(0, follower.getPoseOverride());
		assertFalse("and its eyes free again", facingYou);
		assertEquals(SketchController.MOVED, last(overhead));
		assertFalse("never taken", overhead.contains(null));
		tick(scribe, SketchController.COUNT_TICKS);
		assertTrue("so nothing said after", said.isEmpty());
	}

	@Test
	public void aFightOrAWindowOpeningSpoilsItToo() throws IOException
	{
		// The view is the picture: a bank opened over it, or a fight, is
		// not a sketch of the place.
		SketchController scribe = scribe();
		scribe.askForSketch();
		scribe.tick();
		busy = true;
		scribe.tick();
		assertFalse(scribe.isSketching());
		assertFalse(holding);
		assertFalse(facingYou);
		assertEquals(SketchController.CAME_UP, last(overhead));
		assertTrue(said.isEmpty());
	}

	@Test
	public void aRightClickMenuOpenOverTheViewIsWaitedOut() throws IOException
	{
		SketchController scribe = scribe();
		scribe.askForSketch();
		menuOpen = true;
		tick(scribe, SketchController.COUNT_TICKS + 3);
		assertFalse("not taken with the menu in it", overhead.contains(null));
		assertTrue(scribe.isPosing());
		menuOpen = false;
		scribe.tick();
		assertNull("taken once it is shut", last(overhead));
	}

	@Test
	public void aRetakeKeepsTheOldDrawingFirst() throws IOException
	{
		java.nio.file.Path data = folder.newFolder().toPath();
		SketchController scribe = scribe(drawnBook(data));
		assertNull(scribe.askForSketch());
		java.nio.file.Path acct = data.resolve(SketchBook.DIR_NAME).resolve("acct");
		assertTrue(java.nio.file.Files.isRegularFile(acct.resolve("lumbridge.png.bak")));
		assertTrue(java.nio.file.Files.isRegularFile(acct.resolve("lumbridge.large.png.bak")));
		assertTrue(scribe.isPosing());
	}

	@Test
	public void askedAtABadMomentItSaysWhyAndTheScrollStaysAway() throws IOException
	{
		SketchController scribe = scribe();
		busy = true;
		assertEquals(SketchController.NOT_NOW, scribe.askForSketch());
		busy = false;
		holding = true;
		assertEquals("a tool kept between trees is still in the hand", SketchController.HANDS_FULL,
			scribe.askForSketch());
		holding = false;
		region = 9999;
		assertEquals(SketchController.NOTHING_HERE, scribe.askForSketch());
		region = 12850;
		handsRefuse = true;
		assertEquals(SketchController.NOT_NOW, scribe.askForSketch());
		handsRefuse = false;
		assertFalse(scribe.isSketching());
		assertFalse(holding);
		assertTrue("a refusal is the caller's to say", overhead.isEmpty() && said.isEmpty());

		assertNull(scribe.askForSketch());
		assertEquals("one at a time", SketchController.NOT_NOW, scribe.askForSketch());
	}

	@Test
	public void noBackupNoPose() throws IOException
	{
		SketchController scribe = scribe(new SketchBook(null)
		{
			@Override
			public List<String> backUp(String key) throws IOException
			{
				throw new IOException("disk full");
			}
		});
		assertEquals(SketchController.NO_BACKUP, scribe.askForSketch());
		assertFalse(scribe.isSketching());
		assertFalse("the scroll put away again", holding);
		assertTrue(overhead.isEmpty());
	}

	@Test
	public void aResetMidPoseTakesThePoseOffAndSaysNothing() throws IOException
	{
		// A disable or a logout with the scroll out. The pose is set on the
		// follower, which outlives both, so the reset is the one thing that
		// takes it off.
		SketchController scribe = scribe();
		scribe.askForSketch();
		assertTrue(scribe.isSketching());

		scribe.reset();
		assertFalse("the sketch is over", scribe.isSketching());
		assertFalse("the scroll put away", holding);
		assertEquals("and the reading pose with it", 0, follower.getPoseOverride());
		assertFalse("and the hold on its facing", facingYou);
		assertTrue("nothing said for a sketch never taken", said.isEmpty());
		assertEquals(Collections.singletonList(SketchController.START), overhead);
	}

	@Test
	public void aPutAwayThatFailsStillTakesThePoseOff() throws IOException
	{
		// Play test, 29 Sep: switched off mid-sketch, the put-away rebuilt
		// the model from the Swing thread and threw. The scroll had gone, but
		// the pose was set after it and never came off: the follower went on
		// reading an empty hand after the plugin came back. The pose is only
		// a field, so it goes first.
		SketchController scribe = scribe();
		scribe.askForSketch();
		assertEquals(ErrandController.WRITE_SCROLL_POSE, follower.getPoseOverride());

		releaseFails = true;
		try
		{
			scribe.reset();
		}
		catch (IllegalStateException expected)
		{
			// The failure itself is the stop's to avoid; see LifecycleGlueTest.
		}
		assertEquals("the reading pose is off whatever the hands did", 0, follower.getPoseOverride());
	}

	@Test
	public void aPutAwayLeavesAPoseSomethingElsePutOnSince() throws IOException
	{
		SketchController scribe = scribe();
		scribe.askForSketch();
		follower.setPoseOverride(10061);
		tile = tile.dx(1);
		scribe.tick();
		assertFalse(scribe.isSketching());
		assertEquals("only the scroll's own pose comes off", 10061, follower.getPoseOverride());
	}

	@Test
	public void aSceneStartingPutsItAwayWithoutAWordAndKeepsWhatWasPointedOut() throws IOException
	{
		SketchController scribe = scribe();
		idle = SketchController.IDLE_TICKS;
		scribe.tick();
		assertEquals(1, said.size());
		assertNull(scribe.askForSketch());

		scribe.abort();
		assertFalse(scribe.isSketching());
		assertFalse(holding);
		assertEquals(0, follower.getPoseOverride());
		assertFalse("the film faces it its own way", facingYou);
		assertEquals("nothing over the take", Collections.singletonList(SketchController.START), overhead);
		tick(scribe, 20);
		assertEquals("and not pointed out again this session", 1, said.size());
	}

	// ------------------------------------------------------- the force-path

	@Test
	public void theForcedSketchComesWithoutTheCount() throws IOException
	{
		SketchController scribe = scribe();
		idle = 1;
		assertEquals("Sketching lumbridge.", scribe.forceSketch());
		assertTrue(scribe.isSketching());
		assertFalse("lines are not held for it", scribe.isPosing());
		assertTrue("the scroll is out", holding);
		tick(scribe, SketchController.HOLD_TICKS + SketchController.PUT_AWAY_TICKS);
		assertEquals("the real hold, the real line after", 1, said.size());
		assertFalse(holding);
		assertEquals("no count: only cleared for the picture", Collections.singletonList((String) null), overhead);
	}

	@Test
	public void aPlaceAlreadyDrawnIsDrawnAgainWithItsFilesKeptFirst() throws IOException
	{
		java.nio.file.Path data = folder.newFolder().toPath();
		SketchBook book = drawnBook(data);
		SketchController scribe = scribe(book);
		idle = 1;
		assertNull("the invitation would not ask for it again",
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
		SketchController scribe = scribe(drawnBook(data));
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
	public void everyLineItSaysIsTheScribesAndFits()
	{
		List<String> lines = new ArrayList<>(Arrays.asList(SketchController.DONE));
		lines.addAll(SketchController.POSED.values());
		lines.addAll(theCount());
		lines.addAll(Arrays.asList(SketchController.INVITE, SketchController.THERE, SketchController.MOVED,
			SketchController.CAME_UP, SketchController.NOT_NOW, SketchController.HANDS_FULL,
			SketchController.NOTHING_HERE, SketchController.NO_BACKUP));
		for (String line : lines)
		{
			assertTrue(line, line.length() <= 85);
			assertTrue(line, line.chars().allMatch(c -> c >= 0x20 && c < 0x7f));
		}
		assertEquals("a word for each pose it knows", 6, SketchController.POSED.size());
	}
}

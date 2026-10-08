package com.follower.follower;

import com.follower.sim.FakeGame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Perspective;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The film window's hold on the follower: each compass point and the
 * camera turn it the way they say, a pose is held or played and only the
 * film's own is let go of, and the player's line is given its time. A
 * pose is dressed with what its animation holds, before it starts, and only
 * for as long as it is on; a paired graphic plays as it starts.
 *
 * <p>The frame that turns the follower cannot run headless, so the turn is
 * read off the stay's own facing step, which the frame calls.
 */
public class FilmSetTest
{
	private static final WorldPoint HERE = new WorldPoint(50, 50, 0);

	/** Records what is played once, and whether it is still playing; a real follower otherwise. */
	private static final class Played extends FollowerEntity
	{
		final List<Integer> once = new ArrayList<>();
		/** Whether a one-shot is on, as the follower's own emote flag would say. */
		boolean emoting;
		/** False for a follower with no model to play anything on. */
		boolean plays = true;
		int cancelled;
		/** Whether it is walking, as the follower's last frame would say. */
		boolean moving;

		Played(FakeGame game)
		{
			super(game.client, new StanceLibrary(new com.google.gson.Gson(),
				new com.follower.appearance.ModelRepository(new com.google.gson.Gson())));
		}

		@Override
		public void playAnimation(int animationId)
		{
			once.add(animationId);
			emoting = plays;
		}

		@Override
		public boolean isEmotePlaying()
		{
			return emoting;
		}

		@Override
		public boolean isMoving()
		{
			return moving;
		}

		@Override
		public void cancelEmote()
		{
			if (emoting)
			{
				cancelled++;
			}
			emoting = false;
			super.cancelEmote();
		}
	}

	private Played follower;
	private FilmSet film;

	/** Each rebuild the film asked for: what the hands held, and the pose on, at the time. */
	private final List<String> dressed = new ArrayList<>();

	/** Each graphic the film played. */
	private final List<Integer> graphics = new ArrayList<>();

	@Before
	public void setUp() throws ReflectiveOperationException
	{
		// Every animation the tests play: loadable, and the props are the
		// cache's own for each.
		FakeGame game = new FakeGame().at(HERE.getX(), HERE.getY() - 3, 0)
			.animation(863, -1, FilmSet.EMPTY, FilmSet.EMPTY)
			.animation(10061, 8, FilmSet.EMPTY, FilmSet.EMPTY)
			.animation(10062, 4, FilmSet.EMPTY, FilmSet.EMPTY)
			.animation(1350, 5, FilmSet.EMPTY, 3850)
			.animation(4073, 9, FilmSet.EMPTY, 8798)
			.animation(13302, -1, 31829, 31830)
			.animation(13993, -1, FilmSet.KEEP, FilmSet.KEEP)
			.animation(836, -1, FilmSet.KEEP, FilmSet.KEEP)
			.animation(4751, -1, FilmSet.EMPTY, FilmSet.EMPTY)
			.animation(12072, 4, FilmSet.EMPTY, FilmSet.EMPTY);
		follower = new Played(game);
		set("spawned", true);
		set("tile", HERE);
		set("simTile", HERE);
		set("stayTile", HERE);
		film = new FilmSet(follower,
			() -> dressed.add(Arrays.toString(film.hands()) + " over pose " + follower.getPoseOverride()),
			graphics::add);
	}

	@Test
	public void eachCompassPointTurnsItThatWay() throws ReflectiveOperationException
	{
		String[] points = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		for (int i = 0; i < points.length; i++)
		{
			int orientation = i * 256;
			film.face(orientation);
			assertEquals(points[i], orientation, yaw());
		}
	}

	@Test
	public void theCameraButtonFacesTheViewer() throws ReflectiveOperationException
	{
		// The camera's forward as the client projects with it: a point lies
		// ahead of the camera by y * cos - x * sin of its yaw, a 14-bit
		// angle. The follower must face back along that, toward the screen,
		// whichever way the camera is turned. Taken as an orientation, the
		// yaw wrapped eight times round and had east and west swapped.
		for (int cameraYaw : new int[]{0, 1000, 2048, 4096, 6000, 8192, 10000, 12288, 16383})
		{
			double aheadX = -Perspective.SINE14[cameraYaw];
			double aheadY = Perspective.COSINE14[cameraYaw];
			film.face(FilmSet.cameraFacing(cameraYaw));
			// An orientation faces along -sin, -cos: 0 south, 512 west.
			double radians = yaw() * 2 * Math.PI / 2048;
			double toward = (Math.sin(radians) * aheadX + Math.cos(radians) * aheadY)
				/ Math.hypot(aheadX, aheadY);
			assertTrue("camera yaw " + cameraYaw + " gave " + yaw(), toward > Math.cos(Math.toRadians(2)));
		}
		assertEquals("looking west, the viewer is to the east", 1536, FilmSet.cameraFacing(4096));
		assertEquals("looking south, to the north", 1024, FilmSet.cameraFacing(8192));
		assertEquals("looking east, to the west", 512, FilmSet.cameraFacing(12288));
	}

	@Test
	public void meFacesThePlayerAsAnyStayDoes() throws ReflectiveOperationException
	{
		film.face(1024);
		film.faceMe();
		assertNull(get("stayFaceTile"));
	}

	@Test
	public void aSendMovesTheFacingWithIt() throws ReflectiveOperationException
	{
		film.face(512);
		WorldPoint sent = new WorldPoint(50, 70, 0);
		set("stayTile", sent);
		set("tile", sent);
		film.reaim();
		assertEquals("still due west from where it now stands", 512, yaw());
	}

	@Test
	public void aHeldPoseIsHeldAndOnlyTheFilmsIsLetGo()
	{
		film.hold(10061);
		assertEquals(10061, follower.getPoseOverride());
		assertTrue("its hands are its own, so the emote disarm stands down", film.isPosing());

		film.standNormally();
		assertEquals(0, follower.getPoseOverride());
		assertFalse(film.isPosing());

		film.hold(10061);
		follower.setPoseOverride(5354);
		film.standNormally();
		assertEquals("a pose something else put on since stays", 5354, follower.getPoseOverride());
	}

	@Test
	public void againPlaysTheLastOneShot()
	{
		assertFalse("nothing to play again yet", film.again());
		film.once(863);
		film.hold(10061);
		assertTrue(film.again());
		assertEquals(java.util.Arrays.asList(863, 863), follower.once);
		assertEquals("a held pose comes back after it", 10061, follower.getPoseOverride());
	}

	@Test
	public void aHeldPoseIsDressedWithWhatItsAnimationHoldsBeforeItStarts()
	{
		film.hold(1350);
		assertArrayEquals("an empty left hand and the book in the right",
			new int[]{FilmSet.EMPTY, 3850}, film.hands());
		// The rebuild restarts a clip from its first frame, so it comes first.
		assertEquals(Arrays.asList("[-512, 3850] over pose 0"), dressed);
		assertEquals(1350, follower.getPoseOverride());

		film.standNormally();
		assertNull("the book goes with the pose", film.hands());
		assertEquals("and the follower's own gear comes back", "null over pose 0", dressed.get(1));
	}

	@Test
	public void aOneShotsPropsComeOffWhenItEndsAndTheHeldPoseIsDressedAgain()
	{
		film.hold(1350);
		film.once(13302);
		assertArrayEquals("the quill in the left hand, the scroll in the right",
			new int[]{31829, 31830}, film.hands());

		follower.emoting = false;
		film.onceOver();
		assertArrayEquals("back to the held book", new int[]{FilmSet.EMPTY, 3850}, film.hands());
		assertEquals(3, dressed.size());
		film.onceOver();
		assertEquals("over is over: no rebuild for nothing", 3, dressed.size());
	}

	@Test
	public void aHeldPosesPropsComeOffForAWalkAndGoBackOnWhereItStops()
	{
		follower.moving = true;
		film.frame();
		follower.moving = false;
		film.frame();
		assertTrue("a walk with no pose of the film's on rebuilds nothing", dressed.isEmpty());

		// Sent across the room from a chair: it walked there with the chair
		// in its hand, and in the chair's stance.
		film.hold(4073);
		assertArrayEquals(new int[]{FilmSet.EMPTY, 8798}, film.hands());

		follower.moving = true;
		film.frame();
		assertNull("its own gear for the walk", film.hands());
		assertEquals("null over pose 4073", dressed.get(dressed.size() - 1));
		film.frame();
		assertEquals("one rebuild as it sets off", 2, dressed.size());
		assertTrue("the pose is still the film's", film.isPosing());

		follower.moving = false;
		film.frame();
		assertArrayEquals("the chair again as it sits", new int[]{FilmSet.EMPTY, 8798}, film.hands());
		assertEquals(3, dressed.size());
	}

	@Test
	public void aPoseThatKeepsItsHandsKeepsTheFollowersOwnGear()
	{
		// Peer names nothing for either hand, so the sword stays in it, as on
		// a player.
		film.hold(13993);
		assertArrayEquals(new int[]{FilmSet.KEEP, FilmSet.KEEP}, film.hands());
	}

	@Test
	public void standNormallyGetsUpFromAOneShotStillLying()
	{
		// A death lies still on its last frame for a minute or more.
		film.once(836);
		assertTrue(film.isPosing());
		film.standNormally();
		assertEquals("the death is cut short", 1, follower.cancelled);
		assertFalse(film.isPosing());
		assertNull(film.hands());

		follower.emoting = true;
		film.standNormally();
		assertEquals("a one-shot the film never played is left alone", 1, follower.cancelled);
	}

	@Test
	public void aOneShotThatNeverStartsLeavesNoPropsBehind()
	{
		follower.plays = false;
		film.once(13302);
		assertFalse(film.isPosing());
		assertNull(film.hands());
		assertEquals("dressed, then undressed", 2, dressed.size());
	}

	@Test
	public void aPairedGraphicPlaysAsThePoseStarts()
	{
		film.once(4751);
		assertEquals("air guitar's own graphic", Arrays.asList(1239), graphics);
		film.hold(12072);
		assertEquals("the held idea's lightbulb", Arrays.asList(1239, 712), graphics);
		film.once(863);
		assertEquals("a wave has none", 2, graphics.size());
	}

	@Test
	public void theEndOfTheSceneLetsGoOfTheFilmsPoseAndFaceOnly() throws ReflectiveOperationException
	{
		WorldPoint booth = new WorldPoint(52, 50, 0);
		follower.setStayFaceTile(booth);
		follower.setPoseOverride(5354);
		film.letGo();
		assertEquals("a face the film never set", booth, get("stayFaceTile"));
		assertEquals("a pose the film never put on", 5354, follower.getPoseOverride());

		film.hold(10062);
		film.face(0);
		film.letGo();
		assertEquals(0, follower.getPoseOverride());
		assertNull(get("stayFaceTile"));
	}

	@Test
	public void copyMyEmoteHasThePlayersLastAnimation()
	{
		assertEquals(-1, film.getLastPlayerAnimation());
		film.notePlayerAnimation(866);
		film.notePlayerAnimation(-1);
		assertEquals("stopping is not an animation", 866, film.getLastPlayerAnimation());
	}

	@Test
	public void thePlayersLineIsUpForItsReadingTime()
	{
		// The game's own chat: 150 cycles, three seconds.
		assertEquals(150, FilmSet.overheadCycles(3000));
		assertEquals(600, FilmSet.overheadCycles(12_000));
	}

	@Test
	public void everyPoseTheWindowOfferedBeforeIsStillOfferedTheSameWay()
	{
		int[] holds = {10061, 10062, 5354, 1350, 13993, 179, 10048, 3193, 2691, 3803, 12050,
			189, 190, 191, 192, 193, 194, 195, 196, 197};
		int[] onces = {855, 856, 857, 858, 859, 860, 861, 862, 863, 864, 865, 866, 1374,
			2105, 2106, 2107, 2108, 2109, 2110, 2111, 2112, 2113, 4275, 4276, 4278, 4751, 8917};
		for (int id : holds)
		{
			assertTrue(id + " is held", FilmSet.pose(id).hold);
		}
		for (int id : onces)
		{
			assertFalse(id + " is played once", FilmSet.pose(id).hold);
		}
		assertNull("the lightbulb's graphic is not a pose", FilmSet.pose(4277));
		assertNotEquals("the sit's own stand-up is not a sit", "Sit", FilmSet.pose(10053).name);
	}

	/** The way the stay's facing step turns it, from the facing the film set. */
	private int yaw() throws ReflectiveOperationException
	{
		Method face = FollowerEntity.class.getDeclaredMethod("faceStayAnchor");
		face.setAccessible(true);
		face.invoke(follower);
		return (Integer) get("dstYaw");
	}

	private void set(String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerEntity.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(follower, value);
	}

	private Object get(String name) throws ReflectiveOperationException
	{
		Field field = FollowerEntity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(follower);
	}
}

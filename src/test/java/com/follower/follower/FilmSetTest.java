package com.follower.follower;

import com.follower.sim.FakeGame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Perspective;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The film window's hold on the follower: each compass point and the
 * camera turn it the way they say, a pose is held or played and only the
 * film's own is let go of, and the player's line is given its time.
 *
 * <p>The frame that turns the follower cannot run headless, so the turn is
 * read off the stay's own facing step, which the frame calls.
 */
public class FilmSetTest
{
	private static final WorldPoint HERE = new WorldPoint(50, 50, 0);

	/** Records what is played once; a real follower otherwise. */
	private static final class Played extends FollowerEntity
	{
		final List<Integer> once = new ArrayList<>();

		Played(FakeGame game)
		{
			super(game.client, new StanceLibrary(new com.google.gson.Gson(),
				new com.follower.appearance.ModelRepository(new com.google.gson.Gson())));
		}

		@Override
		public void playAnimation(int animationId)
		{
			once.add(animationId);
		}
	}

	private Played follower;
	private FilmSet film;

	@Before
	public void setUp() throws ReflectiveOperationException
	{
		FakeGame game = new FakeGame().at(HERE.getX(), HERE.getY() - 3, 0);
		follower = new Played(game);
		set("spawned", true);
		set("tile", HERE);
		set("simTile", HERE);
		set("stayTile", HERE);
		film = new FilmSet(follower);
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
		assertTrue("the weapon goes away for it", film.isHolding());

		film.standNormally();
		assertEquals(0, follower.getPoseOverride());
		assertFalse(film.isHolding());

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
	public void everyPoseIsAGameEmoteListedOnce()
	{
		Set<Integer> ids = new HashSet<>();
		for (FilmSet.Pose pose : FilmSet.POSES)
		{
			assertTrue(pose.name + " is listed twice", ids.add(pose.id));
			assertTrue(pose.name, pose.id > 0);
		}
		assertFalse("the lightbulb's graphic is not a pose", ids.contains(4277));
		assertFalse("the sit's own stand-up is not a sit", ids.contains(10053));
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

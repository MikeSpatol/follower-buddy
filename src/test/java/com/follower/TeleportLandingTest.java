package com.follower;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A teleport whose clip outlasts its cast (QA, 15 Oct). The Lunar
 * teleports' HUMAN_TELEPORT_OTHER_IMPACT (1816) runs 2064 client cycles, 41
 * seconds, most of it a held last frame, so the follower's copy was still
 * playing long after the player had landed, and its vanish never came. The
 * player landing is the cast's end too: the follower lands with them.
 */
public class TeleportLandingTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int LUNAR = 1816;
	private static final WorldPoint CAST_FROM = new WorldPoint(3222, 3218, 0);

	private FakeGame game;
	private FollowerEntity follower;
	private int snaps;

	@Before
	public void setUp() throws IOException, ReflectiveOperationException
	{
		game = new FakeGame().withCollision().animation(LUNAR, -1);
		StanceLibrary stances = new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		stances.load(folder.newFolder().toPath());
		follower = new FollowerEntity(game.client, stances);
		follower.setOnSnapped(() -> snaps++);
		set("object", new RuneLiteObject(game.client));
		set("animatable", true);
		set("spawned", true);
		set("tile", new WorldPoint(3221, 3218, 0));
		follower.updateFrame();

		// mirror-teleport: planted, the copy of the cast, the vanish armed.
		follower.stayHere();
		follower.playAnimation(LUNAR);
		follower.vanishAfterEmote();
		follower.updateFrame();
		assertTrue(follower.isEmotePlaying());
	}

	private Object get(String name) throws ReflectiveOperationException
	{
		Field field = FollowerEntity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(follower);
	}

	private void set(String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerEntity.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(follower, value);
	}

	private void landsBesideThePlayer(WorldPoint landing) throws ReflectiveOperationException
	{
		game.at(landing.getX(), landing.getY(), landing.getPlane());
		follower.updateFrame();
		assertFalse("the copy ends with the cast", follower.isEmotePlaying());
		assertFalse((Boolean) get("vanished"));
		assertNull("and its hold with it", get("stayTile"));
		assertTrue("beside the player: " + follower.getWorldLocation(),
			follower.getWorldLocation().distanceTo(landing) <= 2);
		assertEquals("told, so an errand or the minutes end in the same frame", 1, snaps);
	}

	@Test
	public void aLongTeleportLandsTheFollowerWithThePlayer() throws ReflectiveOperationException
	{
		landsBesideThePlayer(new WorldPoint(2100, 3900, 0));
	}

	@Test
	public void soDoesOneInsideTheStayLeash() throws ReflectiveOperationException
	{
		// Twelve tiles: no snap of the leash's own would come for this.
		landsBesideThePlayer(new WorldPoint(CAST_FROM.getX() + 12, CAST_FROM.getY(), 0));
	}

	/** Gone with the cast, and back beside the player where they land. */
	@Test
	public void theReappearanceAfterAVanishIsASnapToo() throws ReflectiveOperationException
	{
		follower.cancelEmote();
		set("vanished", true);
		set("vanishPlayerTile", CAST_FROM);
		game.at(2100, 3900, 0);
		follower.updateFrame();
		assertFalse((Boolean) get("vanished"));
		assertEquals(1, snaps);
	}

	@Test
	public void aStepIsNotALanding() throws ReflectiveOperationException
	{
		game.at(CAST_FROM.getX() + 1, CAST_FROM.getY(), 0);
		follower.updateFrame();
		assertTrue("a step is not a teleport", follower.isEmotePlaying());
		assertEquals(0, snaps);
	}
}

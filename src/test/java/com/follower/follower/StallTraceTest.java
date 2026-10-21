package com.follower.follower;

import com.follower.appearance.ModelRepository;
import com.follower.sim.FakeGame;
import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

/**
 * QA5: the follower stood still well short of a player who had stopped,
 * until the leash snapped it over, and nothing on record said why. After
 * two seconds of it the trace says, once, what was holding it.
 */
public class StallTraceTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final FakeGame game = new FakeGame().withCollision().keepsRuneLiteObjects().at(60, 50, 0);
	private FollowerEntity follower;
	private final List<String> trace = new ArrayList<>();

	@Before
	public void setUp() throws IOException, ReflectiveOperationException
	{
		StanceLibrary stances = new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		stances.load(folder.newFolder().toPath());
		follower = new FollowerEntity(game.client, stances);
		follower.setAgilityTrace(trace::add);
		set("object", new RuneLiteObject(game.client));
		set("animatable", true);
		set("spawned", true);
		// The first frame puts it in the scene.
		standOn(new WorldPoint(50, 50, 0));
		frames(1);
		standOn(new WorldPoint(50, 50, 0));
		trace.clear();
	}

	@Test
	public void heldByAStay() throws ReflectiveOperationException
	{
		follower.stayHere();
		frames(30);
		assertEquals(List.of("follower: still 10 tiles from the player at 60,50,0 for 2.0s: a stay at 50,50,0 (unknown)"),
			trace);
	}

	@Test
	public void walledIn() throws ReflectiveOperationException
	{
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if (dx != 0 || dy != 0)
				{
					game.blockTile(50 + dx, 50 + dy);
				}
			}
		}
		frames(30);
		assertEquals(List.of("follower: still 10 tiles from the player at 60,50,0 for 2.0s: no route to 60,50,0"),
			trace);
	}

	/** A tenth of a second each. */
	private void frames(int count) throws ReflectiveOperationException
	{
		for (int i = 0; i < count; i++)
		{
			set("lastFrameMs", System.currentTimeMillis() - 100);
			follower.updateFrame();
		}
	}

	private void standOn(WorldPoint at) throws ReflectiveOperationException
	{
		set("tile", at);
		set("simTile", at);
		set("fineWX", at.getX() * 128L + 64);
		set("fineWY", at.getY() * 128L + 64);
	}

	private void set(String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerEntity.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(follower, value);
	}
}

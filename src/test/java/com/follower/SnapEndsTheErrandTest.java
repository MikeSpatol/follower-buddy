package com.follower;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Behaviour QA, 15 Oct: the follower is snapped beside the player in the
 * frame a teleport lands, while an errand or the minutes only noticed the
 * player had gone on the next game tick. Any frame drawn between showed the
 * scroll and the writing pose beside the landed player, and the minutes were
 * never ended by a teleport at all: the snap had already put the follower
 * inside their leash. The snap now ends both, there and then.
 *
 * <p>Reached on a bare plugin, as EmoteHandsTest reaches the hands.
 */
public class SnapEndsTheErrandTest
{
	/** The feet: what the snap's listener does to them. */
	private static final class Feet extends FollowerEntity
	{
		int poseOverride = -1;
		boolean released;

		Feet()
		{
			super(null, null);
		}

		@Override
		public void setPoseOverride(int animationId)
		{
			poseOverride = animationId;
		}

		@Override
		public void resumeFollowing()
		{
			released = true;
		}
	}

	/** An errand under way, as far as the plugin can see. */
	private static final class Errand extends ErrandController
	{
		boolean busy = true;
		boolean reset;

		Errand()
		{
			super(null, null, null, null, null, null, null);
		}

		@Override
		public boolean isBusy()
		{
			return busy;
		}

		@Override
		public void reset()
		{
			reset = true;
			busy = false;
		}
	}

	private FollowerPlugin plugin;
	private Feet feet;
	private boolean holding;

	@Before
	public void setUp() throws ReflectiveOperationException
	{
		plugin = new FollowerPlugin();
		feet = new Feet();
		set("follower", feet);
		set("hands", new ErrandController.Hands()
		{
			@Override
			public void hold(int itemId)
			{
				holding = true;
			}

			@Override
			public void release()
			{
				holding = false;
			}
		});
	}

	private void set(String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(plugin, value);
	}

	private Object get(String name) throws ReflectiveOperationException
	{
		Field field = FollowerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(plugin);
	}

	private void snapped() throws ReflectiveOperationException
	{
		Method method = FollowerPlugin.class.getDeclaredMethod("followerSnapped");
		method.setAccessible(true);
		method.invoke(plugin);
	}

	@Test
	public void theMinutesEndWithTheSnap() throws ReflectiveOperationException
	{
		// Writing: the scroll out and the pose on.
		set("minutesState", 2);
		holding = true;
		snapped();
		assertEquals("over", 0, get("minutesState"));
		assertFalse("the scroll put away", holding);
		assertEquals("the writing pose off", 0, feet.poseOverride);
		assertTrue("and the follower its own again", feet.released);
	}

	@Test
	public void anErrandEndsWithTheSnap() throws ReflectiveOperationException
	{
		Errand errand = new Errand();
		errand.reset = false;
		errand.busy = true;
		set("errands", errand);
		snapped();
		assertTrue("ended in the frame, not on the next tick", errand.reset);
	}

	@Test
	public void aSnapWithNothingUnderWayTouchesNothing() throws ReflectiveOperationException
	{
		Errand errand = new Errand();
		errand.reset = false;
		errand.busy = false;
		set("errands", errand);
		snapped();
		assertFalse("no fresh schedule for an errand that was never under way", errand.reset);
		assertFalse(feet.released);
	}

	@Test
	public void theSnapIsWiredAtStartUp() throws IOException
	{
		String source = PublicMarkers.strip(new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8));
		assertTrue(source.contains("follower.setOnSnapped(this::followerSnapped);"));
	}
}

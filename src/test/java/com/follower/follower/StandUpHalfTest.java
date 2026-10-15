package com.follower.follower;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.RuneLiteObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

/**
 * The stand-up is the second half of the Sit emote, 10053. QA, 15 Oct: the
 * emote starts standing, sits, holds and stands, and played whole from a sit
 * it popped the follower up, sat it back down and stood it again, 2.8 seconds
 * of up-down-up where a second's stand-up was meant. Cache measurement: frames
 * 9-16 are the seated pose, 17-25 the rise from it, 50 client cycles.
 */
public class StandUpHalfTest
{
	private final List<Integer> loaded = new ArrayList<>();
	private final Animation sitEmote = animation();

	private static Animation animation()
	{
		return (Animation) Proxy.newProxyInstance(Animation.class.getClassLoader(),
			new Class<?>[]{Animation.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "isMayaAnim":
						return false;
					case "getFrameLengths":
						return new int[26];
					case "getDuration":
						return 26;
					case "getFrameStep":
						return -1;
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == args[0];
					default:
						Class<?> type = method.getReturnType();
						return type == boolean.class ? false : type == int.class ? 0 : null;
				}
			});
	}

	private FollowerEntity follower() throws ReflectiveOperationException
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, method, args) ->
			{
				if ("loadAnimation".equals(method.getName()))
				{
					loaded.add((Integer) args[0]);
					return (Integer) args[0] == FollowerEntity.SIT_EMOTE ? sitEmote : null;
				}
				Class<?> type = method.getReturnType();
				return type == boolean.class ? false : type == int.class ? 0 : type == long.class ? 0L : null;
			});
		FollowerEntity follower = new FollowerEntity(client, null);
		set(follower, "object", new RuneLiteObject(client));
		set(follower, "animatable", true);
		return follower;
	}

	private static void set(FollowerEntity follower, String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerEntity.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(follower, value);
	}

	private static AnimationController playing(FollowerEntity follower) throws ReflectiveOperationException
	{
		Field field = FollowerEntity.class.getDeclaredField("object");
		field.setAccessible(true);
		return ((RuneLiteObject) field.get(follower)).getAnimationController();
	}

	@Test
	public void theStandUpIsTheSitEmoteFromTheSeatedPose() throws ReflectiveOperationException
	{
		FollowerEntity follower = follower();
		follower.playAnimation(FollowerEntity.STAND_UP);
		assertEquals("the clip is the Sit emote", java.util.Collections.singletonList(10053), loaded);
		AnimationController controller = playing(follower);
		assertSame(sitEmote, controller.getAnimation());
		assertEquals("from its stand-up half", 17, controller.getFrame());
	}

	@Test
	public void aGestureFromTheSeatStandsUpFirstTheSameWay() throws ReflectiveOperationException
	{
		FollowerEntity follower = follower();
		follower.playAnimations(new int[]{KeepingCompany.STAND_UP, 863});
		assertEquals(17, playing(follower).getFrame());
	}

	@Test
	public void theSitEmoteItselfStillPlaysWhole() throws ReflectiveOperationException
	{
		// Copying a player's Sit is the emote, not a stand-up.
		FollowerEntity follower = follower();
		follower.playAnimation(10053);
		assertEquals(0, playing(follower).getFrame());
	}

	@Test
	public void everyStandUpIsTheHalfAndFitsItsWait() throws ReflectiveOperationException
	{
		assertEquals("the seat's stand-up", FollowerEntity.STAND_UP, KeepingCompany.STAND_UP);
		Field rest = com.follower.FollowerPlugin.class.getDeclaredField("REST_STAND_UP");
		rest.setAccessible(true);
		assertEquals("the rest's stand-up", FollowerEntity.STAND_UP, rest.getInt(null));
		assertNotEquals("an id of its own, so 10053 stays the emote", 10053, FollowerEntity.STAND_UP);
		// Frames 17-25 are 50 client cycles of 20 ms: a second, inside two ticks.
		assertEquals(2, KeepingCompany.STAND_UP_TICKS);
	}
}

package com.follower.follower;

import com.follower.appearance.ModelRepository;
import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.RuneLiteObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * How the follower's body handles the clips that were authored for something
 * other than the way it plays them (QA, 15 Oct): the writing pose held for
 * minutes when it was made to play once, the mood stances played as emotes,
 * and a gesture asked for while it walks.
 *
 * <p>Cache measurements behind the numbers: 13302 is 22 frames, a lift over
 * frames 0-3, writing to 17 and a lowering over 18-21; frame 16 is frame 9
 * exactly. The mood clips start and end 63-104 units from the idle.
 */
public class WritingPoseTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int WRITE = ErrandController.WRITE_SCROLL_POSE;

	private int tickCount;
	private final List<Integer> loaded = new ArrayList<>();

	private static Animation animation(int id, int frames)
	{
		return (Animation) Proxy.newProxyInstance(Animation.class.getClassLoader(),
			new Class<?>[]{Animation.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getId":
						return id;
					case "isMayaAnim":
						return false;
					case "getFrameLengths":
						int[] lengths = new int[frames];
						java.util.Arrays.fill(lengths, 8);
						return lengths;
					case "getDuration":
						return frames;
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

	private FollowerEntity follower() throws IOException, ReflectiveOperationException
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "loadAnimation":
						int id = (Integer) args[0];
						loaded.add(id);
						return animation(id, id == WRITE ? 22 : 10);
					case "getTickCount":
						return tickCount;
					default:
						Class<?> type = method.getReturnType();
						return type == boolean.class ? false : type == int.class ? 0
							: type == long.class ? 0L : null;
				}
			});
		StanceLibrary stances = new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		stances.load(folder.newFolder().toPath());
		FollowerEntity follower = new FollowerEntity(client, stances);
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

	private static Object call(FollowerEntity follower, String name, Object... args) throws ReflectiveOperationException
	{
		for (Method method : FollowerEntity.class.getDeclaredMethods())
		{
			if (method.getName().equals(name) && method.getParameterCount() == args.length)
			{
				method.setAccessible(true);
				return method.invoke(follower, args);
			}
		}
		throw new AssertionError("FollowerEntity." + name + " has moved");
	}

	private static AnimationController playing(FollowerEntity follower) throws ReflectiveOperationException
	{
		Field field = FollowerEntity.class.getDeclaredField("object");
		field.setAccessible(true);
		return ((RuneLiteObject) field.get(follower)).getAnimationController();
	}

	/** The writing pose put on and shown, as a frame of the update does. */
	private FollowerEntity writing(int frame) throws IOException, ReflectiveOperationException
	{
		FollowerEntity follower = follower();
		follower.setPoseOverride(WRITE);
		call(follower, "applyPose", WRITE, true);
		playing(follower).setFrame(frame);
		return follower;
	}

	// ------------------------------------------------------------ the hold

	@Test
	public void aHeldWritingPoseWritesOnRatherThanLoweringAndLiftingTheScroll()
		throws IOException, ReflectiveOperationException
	{
		FollowerEntity follower = writing(15);
		call(follower, "holdTheWriting");
		assertEquals("still writing", 15, playing(follower).getFrame());

		playing(follower).setFrame(FollowerEntity.WRITE_LOOP_END);
		call(follower, "holdTheWriting");
		assertEquals("back to the frame 16 is a copy of, not on to the lowering",
			FollowerEntity.WRITE_LOOP_START, playing(follower).getFrame());
		assertEquals(9, FollowerEntity.WRITE_LOOP_START);
		assertEquals(16, FollowerEntity.WRITE_LOOP_END);
	}

	@Test
	public void anyOtherPoseLoopsWhole() throws IOException, ReflectiveOperationException
	{
		FollowerEntity follower = follower();
		call(follower, "applyPose", 5354, true);
		playing(follower).setFrame(FollowerEntity.WRITE_LOOP_END);
		call(follower, "holdTheWriting");
		assertEquals(FollowerEntity.WRITE_LOOP_END, playing(follower).getFrame());
	}

	// -------------------------------------------------------- the put-away

	@Test
	public void lettingGoOfTheWritingLowersTheScrollFirst() throws IOException, ReflectiveOperationException
	{
		FollowerEntity follower = writing(12);
		follower.setPoseOverride(0);
		assertTrue("the lowering plays", follower.isPuttingAway());
		assertTrue(follower.isEmotePlaying());
		AnimationController controller = playing(follower);
		assertEquals(WRITE, controller.getAnimation().getId());
		assertEquals("from the top of the lowering", FollowerEntity.WRITE_PUT_AWAY_FRAME, controller.getFrame());
	}

	@Test
	public void anotherPoseOverTheWritingLowersItToo() throws IOException, ReflectiveOperationException
	{
		FollowerEntity follower = writing(10);
		follower.setPoseOverride(10061);
		assertTrue(follower.isPuttingAway());
		assertEquals("and the new pose is waiting", 10061, follower.getPoseOverride());
	}

	@Test
	public void noLoweringWhileStillLiftingOrOnTheMove() throws IOException, ReflectiveOperationException
	{
		FollowerEntity lifting = writing(2);
		lifting.setPoseOverride(0);
		assertFalse("caught in the lift, nothing to lower", lifting.isEmotePlaying());

		FollowerEntity walking = writing(12);
		set(walking, "onTheMove", true);
		walking.setPoseOverride(0);
		assertFalse("walking, the writing was never shown", walking.isEmotePlaying());
	}

	@Test
	public void whatWaitsOnTheLoweringRunsWhenItEndsHoweverItEnds() throws IOException, ReflectiveOperationException
	{
		FollowerEntity follower = writing(12);
		follower.setPoseOverride(0);
		List<String> ran = new ArrayList<>();
		follower.atEmoteFraction(1, () -> ran.add("props away"));
		call(follower, "passEmoteMilestones", false);
		assertTrue("not at the top of the lowering", ran.isEmpty());

		follower.cancelEmote();
		assertEquals("cut short by a walk, the props still go", 1, ran.size());
		assertFalse(follower.isPuttingAway());

		List<String> now = new ArrayList<>();
		follower.atEmoteFraction(1, () -> now.add("at once"));
		assertEquals("with nothing playing, at once", 1, now.size());
	}

	@Test
	public void anotherClipTakingOverEndsTheWait() throws IOException, ReflectiveOperationException
	{
		FollowerEntity follower = writing(12);
		follower.setPoseOverride(0);
		List<String> ran = new ArrayList<>();
		follower.atEmoteFraction(1, () -> ran.add("props away"));
		follower.playAnimation(714);
		assertEquals("the teleport home does not carry the scroll", 1, ran.size());
		assertFalse(follower.isPuttingAway());
	}

	@Test
	public void theReachHandsThePropOverAtTheBottomOfTheBend() throws IOException, ReflectiveOperationException
	{
		FollowerEntity follower = follower();
		follower.playAnimation(827);
		List<String> ran = new ArrayList<>();
		follower.atEmoteFraction(0.5, () -> ran.add("prop in hand"));
		playing(follower).setFrame(4);
		call(follower, "passEmoteMilestones", false);
		assertTrue("still going down", ran.isEmpty());
		playing(follower).setFrame(5);
		call(follower, "passEmoteMilestones", false);
		assertEquals(1, ran.size());
	}

	// ------------------------------------------------- a gesture on the move

	@Test
	public void aGestureAskedForOnTheMoveWaitsForTheFollowerToStand()
		throws IOException, ReflectiveOperationException
	{
		FollowerEntity follower = follower();
		set(follower, "onTheMove", true);
		follower.playWhenStill(863, 3);
		assertFalse("not dropped on its first frame by the walk", follower.isEmotePlaying());

		tickCount = 2;
		call(follower, "playWhatWaitedForStill");
		assertFalse("still walking", follower.isEmotePlaying());

		set(follower, "onTheMove", false);
		call(follower, "playWhatWaitedForStill");
		assertTrue("stood, so it waves", follower.isEmotePlaying());
		assertEquals(863, playing(follower).getAnimation().getId());
	}

	@Test
	public void aGestureThatWaitedTooLongIsDropped() throws IOException, ReflectiveOperationException
	{
		FollowerEntity follower = follower();
		set(follower, "onTheMove", true);
		follower.playWhenStill(863, 3);
		tickCount = 4;
		set(follower, "onTheMove", false);
		call(follower, "playWhatWaitedForStill");
		assertFalse("a wave four ticks after the line is a wave at nothing", follower.isEmotePlaying());
	}

	@Test
	public void aGestureAskedForStandingPlaysAtOnce() throws IOException, ReflectiveOperationException
	{
		FollowerEntity follower = follower();
		follower.playWhenStill(863, 3);
		assertTrue(follower.isEmotePlaying());
	}

	// ------------------------------------------------------ the mood stances

	@Test
	public void aMoodStanceIsEasedInFromStandingAndBackOut() throws IOException, ReflectiveOperationException
	{
		FollowerEntity follower = follower();
		follower.playAnimation(14001);
		assertTrue("HUMAN_CONFUSED_IDLE, 104 units from the idle at its first frame",
			playing(follower) instanceof EaseInOutController);

		follower.playAnimation(857);
		assertFalse("a real emote starts on the idle and is left alone",
			playing(follower) instanceof EaseInOutController);

		for (int mood : new int[]{13994, 13995, 13997, 13999, 14000, 14001})
		{
			assertTrue(mood + " is eased", FollowerEntity.EASED_STANCES.contains(mood));
		}
	}

	@Test
	public void theEaseIsStandingAtBothEndsAndTheClipBetween()
	{
		int duration = 252;
		assertEquals(0f, EaseInOutController.weight(0, duration), 0f);
		assertEquals(0.5f, EaseInOutController.weight(EaseInOutController.EASE_CYCLES / 2, duration), 0.01f);
		assertEquals(1f, EaseInOutController.weight(100, duration), 0f);
		assertEquals(0.5f, EaseInOutController.weight(duration - EaseInOutController.EASE_CYCLES / 2, duration), 0.01f);
		assertEquals("ends where the idle begins", 0f, EaseInOutController.weight(duration, duration), 0f);
	}

	@Test
	public void theTwoHalvesAreTheirClipsFromTheirFrames()
	{
		assertEquals(WRITE, FollowerEntity.clipOf(FollowerEntity.WRITE_PUT_AWAY));
		assertEquals(17, FollowerEntity.firstFrameOf(FollowerEntity.WRITE_PUT_AWAY));
		assertEquals(WRITE, FollowerEntity.clipOf(WRITE));
		assertEquals(0, FollowerEntity.firstFrameOf(WRITE));
		assertEquals(FollowerEntity.SIT_EMOTE, FollowerEntity.clipOf(FollowerEntity.STAND_UP));
		// The held prayer kneel is 645's frame 8 exactly; 9-11 rise from it.
		assertEquals(645, FollowerEntity.clipOf(FollowerEntity.PRAY_STAND_UP));
		assertEquals(8, FollowerEntity.firstFrameOf(FollowerEntity.PRAY_STAND_UP));
	}
}

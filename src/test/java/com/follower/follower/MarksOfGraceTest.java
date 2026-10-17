package com.follower.follower;

import com.follower.speech.TriggerEvent;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Marks of grace on a rooftop course: spotted when one is on the player's
 * roof, left behind when they walk past it and on to the next obstacle,
 * collected when it goes into the bag. Only on a course, only a mark, only
 * on the player's floor, and each mark gets one line at most.
 */
public class MarksOfGraceTest
{
	private static final int ROOF = 3;

	/** A Draynor roof, between the second rope and the narrow wall. */
	private static final WorldPoint MARK = new WorldPoint(3090, 3266, ROOF);

	private final List<TriggerEvent> events = new ArrayList<>();
	private final MarksOfGrace marks = new MarksOfGrace(events::add);

	private long now = 1_700_000_000_000L;
	private long lastXp;
	private int held;

	/** One tick standing at x, y on the roof, not crossing. */
	private void at(int x, int y)
	{
		at(x, y, ROOF, false);
	}

	private void at(int x, int y, int plane, boolean crossing)
	{
		now += 600;
		marks.tick(now, new WorldPoint(x, y, plane), crossing, lastXp, held);
	}

	/** An obstacle: a few ticks on it, the XP as it completes, and the calm after. */
	private void cross(int fromX, int fromY, int toX, int toY, int toPlane)
	{
		cross(fromX, fromY, ROOF, toX, toY, toPlane);
	}

	private void cross(int fromX, int fromY, int fromPlane, int toX, int toY, int toPlane)
	{
		at(fromX, fromY, fromPlane, true);
		at(fromX, fromY, fromPlane, true);
		lastXp = now + 600;
		at(toX, toY, toPlane, true);
		at(toX, toY, toPlane, true);
	}

	private List<String> stages()
	{
		List<String> out = new ArrayList<>();
		for (TriggerEvent event : events)
		{
			assertEquals(TriggerEvent.Type.GRACE, event.getType());
			out.add(event.getName());
		}
		return out;
	}

	private void onTheCourse()
	{
		lastXp = now;
	}

	// -------------------------------------------------------------- spotted

	@Test
	public void theMarkIsTheGameValItem()
	{
		assertEquals(ItemID.GRACE, MarksOfGrace.MARK);
		assertEquals(11849, MarksOfGrace.MARK);
	}

	@Test
	public void aMarkThatTurnsUpOnTheCourseIsSpotted()
	{
		onTheCourse();
		at(3092, 3267);
		marks.spawned(MarksOfGrace.MARK, MARK);
		at(3092, 3267);
		assertEquals(List.of("spotted"), stages());
	}

	@Test
	public void aMarkAlreadyOnTheRoofIsSpottedOnArrival()
	{
		// Lying there since the chunk loaded, with the player on the ground.
		at(3103, 3279, 0, false);
		marks.spawned(MarksOfGrace.MARK, new WorldPoint(3100, 3280, ROOF));
		at(3103, 3279, 0, false);
		assertTrue("not from the ground, off the course", events.isEmpty());

		// Up the rough wall, which pays as the player arrives.
		cross(3103, 3279, 0, 3102, 3279, ROOF);
		assertEquals(List.of("spotted"), stages());
	}

	@Test
	public void aMarkOffACourseIsNotSpotted()
	{
		at(3092, 3267);
		marks.spawned(MarksOfGrace.MARK, MARK);
		at(3092, 3267);
		assertTrue("no agility at all", events.isEmpty());

		// Agility two minutes ago is not a lap now.
		lastXp = now - 120_000;
		at(3092, 3267);
		assertTrue("agility long ago", events.isEmpty());
	}

	@Test
	public void otherGroundItemsAreIgnored()
	{
		onTheCourse();
		at(3092, 3267);
		marks.spawned(ItemID.BONES, MARK);
		marks.spawned(ItemID.GRACEFUL_HOOD, new WorldPoint(3091, 3267, ROOF));
		marks.spawned(ItemID.COINS, new WorldPoint(3091, 3266, ROOF));
		for (int i = 0; i < 4; i++)
		{
			at(3091 - i, 3266);
		}
		cross(3089, 3265, 3088, 3261, ROOF);
		assertTrue("bones, a hood and coins are not marks: " + stages(), events.isEmpty());
	}

	@Test
	public void aMarkOnAnotherFloorIsNotSpottedOrLeft()
	{
		onTheCourse();
		// Under the roof, on the street, right below the player.
		marks.spawned(MarksOfGrace.MARK, new WorldPoint(3090, 3266, 0));
		at(3091, 3266);
		at(3090, 3266);
		cross(3089, 3265, 3088, 3261, ROOF);
		assertTrue("a floor away: " + stages(), events.isEmpty());
	}

	@Test
	public void aMarkFarOffOnTheSameFloorIsNotSpotted()
	{
		onTheCourse();
		marks.spawned(MarksOfGrace.MARK, new WorldPoint(3090, 3240, ROOF));
		at(3090, 3266);
		assertTrue("off the screen", events.isEmpty());
	}

	// ---------------------------------------------------------- left behind

	@Test
	public void walkedPastAndOnToTheNextObstacleIsLeftBehind()
	{
		onTheCourse();
		marks.spawned(MarksOfGrace.MARK, MARK);
		// Off the second rope and along the roof, a tile from the mark.
		at(3092, 3267);
		at(3091, 3266);
		at(3090, 3265);
		at(3089, 3265);
		assertEquals(List.of("spotted"), stages());
		// On to the narrow wall, and off it on the next roof.
		cross(3089, 3265, 3088, 3261, ROOF);
		at(3088, 3260);
		assertEquals(List.of("spotted", "left"), stages());
	}

	@Test
	public void leavingTheRoofsFloorLeavesItBehind()
	{
		onTheCourse();
		WorldPoint last = new WorldPoint(3101, 3262, ROOF);
		marks.spawned(MarksOfGrace.MARK, last);
		at(3100, 3261);
		at(3101, 3261);
		// The crate: down to the street.
		cross(3101, 3261, 3103, 3261, 0);
		at(3103, 3261, 0, false);
		assertEquals(List.of("spotted", "left"), stages());
	}

	@Test
	public void pickedUpRightAfterPassingIsNotLeftBehind()
	{
		onTheCourse();
		marks.spawned(MarksOfGrace.MARK, MARK);
		at(3091, 3266);
		at(3089, 3265);
		at(3087, 3264);
		// A second later: back for it.
		at(3089, 3265);
		at(3090, 3266);
		marks.despawned(MarksOfGrace.MARK, MARK);
		held = 1;
		at(3090, 3266);
		cross(3089, 3265, 3088, 3261, ROOF);
		at(3088, 3260);
		assertEquals(List.of("spotted", "collected"), stages());
	}

	@Test
	public void theBagFillingATickLateIsStillThePickUp()
	{
		onTheCourse();
		marks.spawned(MarksOfGrace.MARK, MARK);
		at(3090, 3266);
		marks.despawned(MarksOfGrace.MARK, MARK);
		at(3090, 3266);
		held = 1;
		at(3090, 3266);
		assertEquals(List.of("spotted", "collected"), stages());
	}

	@Test
	public void aMarkThatTimesOutIsNeitherLeftNorCollected()
	{
		onTheCourse();
		marks.spawned(MarksOfGrace.MARK, MARK);
		at(3090, 3266);
		marks.despawned(MarksOfGrace.MARK, MARK);
		for (int i = 0; i < 5; i++)
		{
			at(3090, 3266);
		}
		cross(3089, 3265, 3088, 3261, ROOF);
		assertEquals(List.of("spotted"), stages());
	}

	@Test
	public void aMarkSeenAcrossTheGapIsNotLeftByJumpingToIt()
	{
		onTheCourse();
		// On the next roof, three tiles over the gap from where it starts.
		WorldPoint ahead = new WorldPoint(3096, 3256, ROOF);
		marks.spawned(MarksOfGrace.MARK, ahead);
		at(3094, 3255);
		cross(3094, 3255, 3096, 3256, ROOF);
		at(3096, 3257);
		assertEquals("landed beside it", List.of("spotted"), stages());
	}

	@Test
	public void aMarkNeverWalkedNearIsNotLeftBehind()
	{
		onTheCourse();
		// Spotted across the roof, never within three tiles of the route.
		marks.spawned(MarksOfGrace.MARK, new WorldPoint(3085, 3272, ROOF));
		at(3090, 3276);
		at(3091, 3274);
		at(3092, 3276);
		cross(3092, 3276, 3092, 3267, ROOF);
		assertEquals(List.of("spotted"), stages());
	}

	@Test
	public void obstaclesTheWatchMissedStillCountAsMovingOn()
	{
		// No crossing seen, only the XP as the next obstacle completes.
		onTheCourse();
		marks.spawned(MarksOfGrace.MARK, MARK);
		at(3091, 3266);
		at(3089, 3265);
		at(3088, 3263);
		lastXp = now + 600;
		at(3088, 3261);
		assertEquals(List.of("spotted", "left"), stages());
	}

	// ---------------------------------------------------------------- once

	@Test
	public void eachMarkIsRaisedOncePerStage()
	{
		onTheCourse();
		marks.spawned(MarksOfGrace.MARK, MARK);
		at(3090, 3266);
		// A chunk load hands the same mark back: still the same mark.
		marks.spawned(MarksOfGrace.MARK, MARK);
		at(3090, 3265);
		cross(3089, 3265, 3088, 3261, ROOF);
		at(3088, 3260);
		assertEquals(List.of("spotted", "left"), stages());
		// Next lap, past it again and on.
		at(3090, 3266);
		cross(3089, 3265, 3088, 3261, ROOF);
		at(3088, 3260);
		assertEquals(List.of("spotted", "left"), stages());
	}

	@Test
	public void aMarkSpokenOfWhenSpottedIsNotRemindedOf()
	{
		onTheCourse();
		marks.spawned(MarksOfGrace.MARK, MARK);
		at(3092, 3267);
		events.get(0).noteSaid();
		at(3090, 3266);
		cross(3089, 3265, 3088, 3261, ROOF);
		at(3088, 3260);
		assertEquals("its one line was the sighting", List.of("spotted"), stages());
	}

	@Test
	public void aMarkRemindedOfIsNotPraisedWhenFetched()
	{
		onTheCourse();
		marks.spawned(MarksOfGrace.MARK, MARK);
		at(3090, 3266);
		cross(3089, 3265, 3088, 3261, ROOF);
		at(3088, 3260);
		assertEquals(List.of("spotted", "left"), stages());
		events.get(1).noteSaid();
		// Back across for it.
		cross(3088, 3261, 3089, 3265, ROOF);
		at(3090, 3266);
		marks.despawned(MarksOfGrace.MARK, MARK);
		held = 1;
		at(3090, 3266);
		assertEquals("no second line", List.of("spotted", "left"), stages());
	}

	@Test
	public void aPickUpOffTheCourseIsNotRaised()
	{
		marks.spawned(MarksOfGrace.MARK, MARK);
		at(3090, 3266);
		marks.despawned(MarksOfGrace.MARK, MARK);
		held = 1;
		at(3090, 3266);
		assertTrue(events.isEmpty());
	}

	@Test
	public void aResetForgetsTheScene()
	{
		onTheCourse();
		marks.spawned(MarksOfGrace.MARK, MARK);
		marks.reset();
		at(3090, 3266);
		assertTrue(events.isEmpty());
	}
}

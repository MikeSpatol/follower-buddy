package com.follower.follower;

import com.follower.sim.FakeGame;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.HitsplatID;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Telling an agility obstacle from everything else the player does. The
 * player here is a stream of frames, twenty milliseconds apart, the way
 * the plugin reads them: where they are drawn, which floor, what pose and
 * what animation, and their base set. Nothing in the watch knows any
 * obstacle, so neither do these: a log is a swapped walk, a jump is a
 * glide on the stand, a climb is a clip and a hop.
 */
public class CrossingWatchTest
{
	/** HUMAN_READY, HUMAN_WALK_F, HUMAN_RUNNING and the standard turns. */
	private static final int[] NORMAL = {808, 819, 824, 820, 821, 822, 823, 823};

	/** The base set a log swaps in: HUMAN_WALK_LOGBALANCE_READY to stand, HUMAN_WALK_LOGBALANCE to walk. */
	private static final int[] ON_A_LOG = {763, 762, 762, 762, 762, 762, 763, 763};

	private final List<String> events = new ArrayList<>();
	private final List<Crossing> queued = new ArrayList<>();
	private final CrossingWatch watch = new CrossingWatch(new CrossingWatch.Listener()
	{
		@Override
		public void expectCrossing(WorldPoint start)
		{
			events.add("expect " + start.getX() + "," + start.getY() + "," + start.getPlane());
		}

		@Override
		public void queueCrossing(Crossing crossing)
		{
			WorldPoint start = crossing.getStart();
			events.add("queue " + start.getX() + "," + start.getY() + "," + start.getPlane()
				+ (crossing.isComplete() ? "" : " live"));
			queued.add(crossing);
		}

		@Override
		public void cancelExpectedCrossing()
		{
			events.add("cancel");
		}

		@Override
		public void clearCrossings()
		{
			events.add("clear");
		}
	});

	// The player.
	private long t = 1_000_000;
	private long x = 50 * 128 + 64;
	private long y = 50 * 128 + 64;
	private int plane;
	private int pose = 808;
	private int action = -1;
	private int[] base = NORMAL;
	private int weapon = -1;
	private boolean interacting;

	// ---------------------------------------------------------- not agility

	@Test
	public void walkingAndRunningAreNot()
	{
		stand(200);
		walk(6, 0, 819, 4);
		walk(0, 8, 824, 8);
		walk(-3, -3, 819, 4);
		stand(2000);
		assertEquals("nothing seen", List.of(), events);
	}

	@Test
	public void aSlowFrameOfOrdinaryStepsIsNotAHop()
	{
		stand(200);
		pose = 824;
		x += 3 * 128;
		frame(1200);
		x += 3 * 128;
		frame(1200);
		pose = 808;
		stand(2000);
		assertEquals(List.of(), events);
	}

	@Test
	public void anEmoteIsNot()
	{
		stand(200);
		action = 866;
		stand(3000);
		action = -1;
		stand(2000);
		assertEquals("a clip that moves nobody", List.of(), events);
	}

	@Test
	public void anEmoteThenWalkingOnIsNot()
	{
		stand(200);
		action = 866;
		stand(1000);
		action = -1;
		walk(5, 0, 819, 4);
		stand(2000);
		assertEquals(List.of(), events);
	}

	@Test
	public void eatingOnTheRunIsNot()
	{
		stand(200);
		pose = 824;
		action = 829;
		walk(4, 0, 824, 8);
		action = -1;
		walk(4, 0, 824, 8);
		stand(2000);
		assertEquals(List.of(), events);
	}

	@Test
	public void aTeleportIsNot()
	{
		stand(200);
		action = 714;
		stand(1800);
		x += 400 * 128;
		action = -1;
		frame(20);
		stand(2000);
		assertEquals("nothing copied, the queue let go", List.of("clear"), events);
		assertTrue(queued.isEmpty());
	}

	@Test
	public void aLadderIsHeldForAMomentAndLetGo()
	{
		stand(200);
		action = 828;
		stand(1200);
		plane = 1;
		action = -1;
		frame(20);
		stand(3000);
		assertEquals("held while the XP might come, then let go",
			List.of("expect 50,50,0", "cancel"), events);
		assertTrue(queued.isEmpty());
	}

	@Test
	public void stairsAreNot()
	{
		stand(200);
		plane = 1;
		x += 2 * 128;
		frame(20);
		stand(2000);
		assertEquals("a hop with nothing odd about it", List.of(), events);
	}

	@Test
	public void aKnockBackInAFightIsNot()
	{
		stand(200);
		interacting = true;
		action = 424;
		stand(300);
		glide(3, 0, 808, 12);
		action = -1;
		stand(2000);
		assertEquals(List.of(), events);
	}

	@Test
	public void theOneFrameAWalkEndsOnTheStandIsNotAGlide()
	{
		for (int i = 0; i < 6; i++)
		{
			walk(1, 0, 819, 4);
			// The step lands on the stand in the same frame it arrives.
			x += 4;
			pose = 808;
			frame(20);
			stand(300);
		}
		assertEquals(List.of(), events);
	}

	@Test
	public void aTransformedPlayerIsNotWatched()
	{
		FakeGame game = new FakeGame().at(50, 50, 0).transformedInto(7314);
		assertNull(CrossingWatch.Sample.read(game.player, 0));
		assertNotNull(CrossingWatch.Sample.read(new FakeGame().at(50, 50, 0).player, 0));
	}

	// -------------------------------------------------------------- agility

	@Test
	public void aLogBalanceIsACrossing()
	{
		stand(200);
		walk(2, 0, 819, 4);
		// On: the base set swaps, and the walk over the log is the swapped walk.
		base = ON_A_LOG;
		pose = 763;
		stand(300);
		walk(7, 0, 762, 4);
		base = NORMAL;
		pose = 808;
		stand(2000);

		assertEquals("handed over as soon as it is seen, still recording",
			List.of("queue 52,50,0 live"), events);
		Crossing log = queued.get(0);
		assertTrue("and ended since", log.isComplete());
		assertEquals(new WorldPoint(52, 50, 0), log.getStart());
		assertEquals(new WorldPoint(59, 50, 0), log.getEnd());
		assertTrue("the balance is in it", poses(log).contains(762));
		assertFalse("the walk up to it is not, past the frame it set off from",
			poses(log).subList(1, poses(log).size()).contains(819));
		assertFalse(log.isFell());
	}

	@Test
	public void aForcedJumpIsACrossing()
	{
		stand(200);
		action = 741;
		stand(300);
		// Forced movement: the fine position glides with the stand showing.
		glide(2, 0, 808, 10);
		action = -1;
		stand(2000);
		assertEquals(List.of("queue 50,50,0 live"), events);
		Crossing jump = queued.get(0);
		assertEquals(new WorldPoint(52, 50, 0), jump.getEnd());
		assertTrue(actions(jump).contains(741));
	}

	@Test
	public void aClimbWithItsXpIsACrossing()
	{
		stand(200);
		action = 737;
		stand(1200);
		watch.noteAgilityXp(t + 10);
		x += 128;
		plane = 1;
		action = -1;
		frame(20);
		stand(2000);
		assertEquals("the XP was in before the hop: no wait", List.of("queue 50,50,0 live"), events);
		Crossing climb = queued.get(0);
		assertEquals(new WorldPoint(50, 50, 0), climb.getStart());
		assertEquals(new WorldPoint(51, 50, 1), climb.getEnd());
	}

	@Test
	public void theXpMayComeATickAfterTheHop()
	{
		stand(200);
		action = 737;
		stand(1200);
		x += 128;
		plane = 1;
		action = -1;
		frame(20);
		stand(500);
		watch.noteAgilityXp(t);
		stand(2000);
		assertEquals(List.of("expect 50,50,0", "queue 50,50,0 live"), events);
	}

	@Test
	public void aFallIsRecordedAsAFall()
	{
		plane = 3;
		stand(200);
		action = 2586;
		stand(300);
		glide(1, 0, 808, 10);
		// Down to the ground three tiles over, and hurt.
		x += 3 * 128;
		plane = 0;
		action = 3068;
		frame(20);
		watch.noteDamage(t);
		stand(1500);
		action = -1;
		stand(2000);
		assertEquals(List.of("queue 50,50,3 live"), events);
		Crossing fall = queued.get(0);
		assertTrue(fall.isFell());
		assertEquals(new WorldPoint(54, 50, 0), fall.getEnd());
	}

	@Test
	public void aFallAloneCountsWithoutXp()
	{
		plane = 3;
		stand(200);
		action = 3068;
		stand(600);
		x -= 2 * 128;
		plane = 0;
		frame(20);
		watch.noteDamage(t);
		action = -1;
		stand(2000);
		assertEquals(List.of("expect 50,50,3", "queue 50,50,3 live"), events);
		assertTrue(queued.get(0).isFell());
	}

	@Test
	public void poisonOnALadderIsNotAFall()
	{
		assertFalse(CrossingWatch.isFallDamage(HitsplatID.POISON));
		assertFalse(CrossingWatch.isFallDamage(HitsplatID.VENOM));
		assertTrue(CrossingWatch.isFallDamage(HitsplatID.DAMAGE_ME));
	}

	@Test
	public void walkingOnSplitsTwoObstaclesInOrder()
	{
		stand(200);
		action = 741;
		stand(200);
		glide(2, 0, 808, 10);
		action = -1;
		// Straight on to the next, running.
		walk(2, 0, 824, 8);
		base = ON_A_LOG;
		walk(4, 0, 762, 4);
		base = NORMAL;
		pose = 808;
		stand(2000);
		assertEquals(List.of("queue 50,50,0 live", "queue 54,50,0 live"), events);
		assertEquals(new WorldPoint(52, 50, 0), queued.get(0).getEnd());
		assertEquals(new WorldPoint(54, 50, 0), queued.get(1).getStart());
		assertEquals(new WorldPoint(58, 50, 0), queued.get(1).getEnd());
	}

	@Test
	public void aPauseBetweenStagesIsOneCrossing()
	{
		stand(200);
		action = 741;
		stand(200);
		glide(1, 0, 808, 10);
		action = -1;
		stand(500);
		action = 741;
		stand(200);
		glide(1, 0, 808, 10);
		action = -1;
		stand(2000);
		assertEquals(List.of("queue 50,50,0 live"), events);
		assertEquals(new WorldPoint(52, 50, 0), queued.get(0).getEnd());
	}

	@Test
	public void aTeleportMidCrossingDropsItAndTheQueue()
	{
		stand(200);
		base = ON_A_LOG;
		walk(3, 0, 762, 4);
		x += 300 * 128;
		frame(20);
		assertEquals(List.of("queue 50,50,0 live", "clear"), events);
		assertFalse(watch.isWatching());
		assertTrue("cut where it got to", queued.get(0).isComplete());
		assertEquals(new WorldPoint(53, 50, 0), queued.get(0).getEnd());
	}

	@Test
	public void aLogoutMidCrossingDropsIt()
	{
		stand(200);
		base = ON_A_LOG;
		walk(3, 0, 762, 4);
		watch.sample(null);
		assertEquals(List.of("queue 50,50,0 live"), events);
		assertFalse(watch.isWatching());
		assertTrue("cut where it got to", queued.get(0).isComplete());
		assertFalse("but still a crossing", queued.get(0).isDropped());
	}

	@Test
	public void somethingLongerThanAnyObstacleIsDroppedAndBecomesNormal()
	{
		stand(200);
		// Swimming, say: a whole base set of its own, for minutes.
		int[] swimming = {6993, 6994, 6994, 6994, 6994, 6994, 6993, 6993};
		base = swimming;
		for (int i = 0; i < 30; i++)
		{
			walk(1, 0, 6994, 4);
			walk(-1, 0, 6994, 4);
		}
		assertEquals("handed over, then let go", List.of("queue 50,50,0 live"), events);
		assertTrue("as not an obstacle at all", queued.get(0).isDropped());
		events.clear();
		walk(3, 0, 6994, 4);
		stand(2000);
		assertEquals("the swim is the normal walk now", List.of(), events);
	}

	@Test
	public void aWeaponChangeIsANewNormalNotAnOddPose()
	{
		stand(200);
		weapon = 4151;
		base = new int[]{808, 1660, 1661, 1205, 1206, 1207, 823, 823};
		walk(4, 0, 1660, 4);
		stand(2000);
		assertEquals(List.of(), events);
	}

	@Test
	public void aJumpThatPutsThemDownATileOverIsAHop()
	{
		// A gap: the clip, then set down on the next tile with nothing between.
		stand(200);
		action = 2586;
		stand(600);
		watch.noteAgilityXp(t + 10);
		x += 128;
		y += 128;
		action = 2588;
		frame(20);
		stand(600);
		action = -1;
		stand(2000);
		assertEquals(List.of("queue 50,50,0 live"), events);
		assertEquals(new WorldPoint(51, 51, 0), queued.get(0).getEnd());
	}

	@Test
	public void aTileOverWithNoClipOnIsNotAHop()
	{
		// The frame a walk ends on, late: a whole tile, on the stand, nothing playing.
		stand(200);
		x += 128;
		frame(20);
		stand(2000);
		assertEquals(List.of(), events);
	}

	@Test
	public void onACourseAHopWaitsLongerForItsXp()
	{
		watch.noteAgilityXp(t);
		stand(200);
		action = 828;
		stand(1200);
		plane = 3;
		action = -1;
		frame(20);
		// Past the two ticks a ladder gets, inside the course's five.
		stand(2000);
		watch.noteAgilityXp(t);
		stand(2000);
		assertEquals("held, and confirmed in place",
			List.of("expect 50,50,0", "queue 50,50,0 live"), events);
	}

	// ------------------------------------------------------- rebuilt from XP

	@Test
	public void anObstacleTheSignalsMissIsRebuiltWhenItsXpArrives()
	{
		// Shown as a clip over the ordinary walk: it looks like eating on the
		// run, and the watch lets it go as it would that.
		stand(200);
		walk(2, 0, 819, 4);
		action = 7134;
		walk(6, 0, 819, 4);
		action = -1;
		stand(300);
		assertEquals("missed", List.of(), events);

		watch.noteAgilityXp(t);
		frame(20);
		assertEquals("late, but not skipped", List.of("queue 52,50,0"), events);
		Crossing rebuilt = queued.get(0);
		assertEquals("from the last of their own walk", new WorldPoint(52, 50, 0), rebuilt.getStart());
		assertEquals("to where it stopped", new WorldPoint(58, 50, 0), rebuilt.getEnd());
		assertTrue(actions(rebuilt).contains(7134));
		assertFalse("not the walk up to it, past the frame it set off from",
			actions(rebuilt).get(0) == 7134);
	}

	@Test
	public void xpLaterThanTheWaitStillRebuildsTheClimb()
	{
		stand(200);
		action = 828;
		stand(1200);
		x += 128;
		plane = 1;
		action = -1;
		frame(20);
		stand(1500);
		assertEquals("let go as a ladder", List.of("expect 50,50,0", "cancel"), events);

		watch.noteAgilityXp(t);
		frame(20);
		assertEquals(List.of("expect 50,50,0", "cancel", "queue 50,50,0"), events);
		assertEquals(new WorldPoint(50, 50, 0), queued.get(0).getStart());
		assertEquals(new WorldPoint(51, 50, 1), queued.get(0).getEnd());
		assertTrue(actions(queued.get(0)).contains(828));
	}

	@Test
	public void xpForACrossingAlreadyHandedOverRebuildsNothing()
	{
		stand(200);
		base = ON_A_LOG;
		walk(5, 0, 762, 4);
		base = NORMAL;
		walk(2, 0, 819, 4);
		watch.noteAgilityXp(t);
		stand(2000);
		assertEquals(List.of("queue 50,50,0 live"), events);
	}

	@Test
	public void xpWithNothingMovedRebuildsNothing()
	{
		// A lamp rubbed after an emote and a walk: XP, but no obstacle.
		stand(200);
		action = 866;
		stand(1500);
		action = -1;
		walk(3, 0, 819, 4);
		stand(500);
		watch.noteAgilityXp(t);
		stand(2000);
		assertEquals(List.of(), events);
	}

	@Test
	public void xpLongAfterTheMovementRebuildsNothing()
	{
		stand(200);
		action = 7134;
		walk(4, 0, 819, 4);
		action = -1;
		stand(4000);
		watch.noteAgilityXp(t);
		stand(200);
		assertEquals("a lamp, minutes on, is not the obstacle's", List.of(), events);
	}

	@Test
	public void aMissedObstacleGoesAheadOfOneSeenSince()
	{
		stand(200);
		action = 7134;
		walk(4, 0, 819, 4);
		action = -1;
		walk(1, 0, 819, 4);
		// Straight onto a log, recognised, before the first one's XP lands.
		base = ON_A_LOG;
		walk(3, 0, 762, 4);
		watch.noteAgilityXp(t);
		walk(2, 0, 762, 4);
		base = NORMAL;
		stand(2000);
		assertEquals(List.of("queue 55,50,0 live", "queue 50,50,0"), events);
		assertTrue("the rebuilt one began first: the follower orders them by that",
			queued.get(1).getStartedAtMs() < queued.get(0).getStartedAtMs());
	}

	@Test
	public void aTeleportForgetsWhatCameBefore()
	{
		stand(200);
		action = 7134;
		walk(4, 0, 819, 4);
		action = -1;
		x += 300 * 128;
		frame(20);
		watch.noteAgilityXp(t);
		stand(200);
		assertEquals(List.of("clear"), events);
	}

	// ------------------------------------------------------------ recording

	@Test
	public void theRecordingIsInWorldUnits()
	{
		FakeGame game = new FakeGame().fineAt(53 * 128 + 20, 60 * 128 + 100, 2);
		CrossingWatch.Sample sample = CrossingWatch.Sample.read(game.player, 0);
		assertNotNull(sample);
		Crossing one = new Crossing(List.of(sample.frame()), NORMAL, false);
		assertEquals(new WorldPoint(53, 60, 2), one.getStart());
		assertEquals(53 * 128 + 20, one.fineAt(0)[0]);
		assertEquals(60 * 128 + 100, one.fineAt(0)[1]);
	}

	@Test
	public void aRecordingRoundTripsItsPositionsAndFloors()
	{
		List<Crossing.Frame> frames = new ArrayList<>();
		frames.add(new Crossing.Frame(500, 50 * 128 + 64, 50 * 128 + 64, 0, 1536, 808, 0, 741, 0));
		frames.add(new Crossing.Frame(1500, 52 * 128 + 64, 50 * 128 + 64, 0, 1536, 808, 0, 741, 9));
		frames.add(new Crossing.Frame(2000, 53 * 128 + 64, 51 * 128 + 64, 1, 1024, 762, 3, -1, 0));
		Crossing crossing = new Crossing(frames, NORMAL, false);

		assertEquals("rebased to its first frame", 1500, crossing.getDurationMs());
		assertEquals(new WorldPoint(50, 50, 0), crossing.getStart());
		assertEquals(new WorldPoint(53, 51, 1), crossing.getEnd());
		assertEquals("half way along the glide", 51 * 128 + 64, crossing.fineAt(500)[0]);
		assertEquals(0, crossing.frameAt(999).getPlane());
		assertEquals("a floor change is not glided across", 52 * 128 + 64, crossing.fineAt(1200)[0]);
		assertEquals(1, crossing.frameAt(1500).getPlane());
		assertEquals(762, crossing.frameAt(1500).getPose());
		assertEquals(-1, crossing.frameAt(1500).getAction());
		assertEquals(1, crossing.normalIndex(819));
		assertEquals(-1, crossing.normalIndex(762));
	}

	// ---------------------------------------------------------------- helpers

	private void frame(long ms)
	{
		t += ms;
		watch.sample(new CrossingWatch.Sample(t, x, y, plane, 0, pose, 0, action, 0,
			base, weapon, interacting));
	}

	private void stand(long ms)
	{
		for (long i = 0; i < ms; i += 20)
		{
			frame(20);
		}
	}

	/** Steps in a walking (or swapped) pose at so many fine units a frame. */
	private void walk(int tilesX, int tilesY, int walkPose, int unitsPerFrame)
	{
		move(tilesX, tilesY, walkPose, unitsPerFrame);
	}

	/** Forced movement: the same, with whatever pose the game leaves on. */
	private void glide(int tilesX, int tilesY, int glidePose, int unitsPerFrame)
	{
		move(tilesX, tilesY, glidePose, unitsPerFrame);
	}

	private void move(int tilesX, int tilesY, int movePose, int unitsPerFrame)
	{
		long toX = x + tilesX * 128L;
		long toY = y + tilesY * 128L;
		pose = movePose;
		while (x != toX || y != toY)
		{
			x += Math.max(-unitsPerFrame, Math.min(unitsPerFrame, toX - x));
			y += Math.max(-unitsPerFrame, Math.min(unitsPerFrame, toY - y));
			frame(20);
		}
		pose = base[0];
	}

	private static List<Integer> poses(Crossing crossing)
	{
		List<Integer> out = new ArrayList<>();
		crossing.getFrames().forEach(f -> out.add(f.getPose()));
		return out;
	}

	private static List<Integer> actions(Crossing crossing)
	{
		List<Integer> out = new ArrayList<>();
		crossing.getFrames().forEach(f -> out.add(f.getAction()));
		return out;
	}
}

// public:file agility-all,marks
package com.follower;

import com.follower.follower.MarksOfGrace;
import com.follower.sim.Harness;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Ten laps of the Draynor rooftop course with a mark of grace on every
 * other one, through the rules the plugin ships, at the user's Minimal (a
 * 45 s gap) and at Occasional (6 s). The follower must never talk on every
 * lap: a mark has one line at most, and a lap one at most. A mark walked
 * past and left is never let go silently.
 *
 * <p>The course is the real one, its tiles moved into the test scene; the
 * player runs two tiles a tick between obstacles and takes a tick to click
 * each, each obstacle pays its XP as it completes, and the agility watch
 * stays on it two ticks after: about 41 s a lap, a quick one.
 */
public class MarksOfGracePacingTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final int OFFSET_X = 3040;
	private static final int OFFSET_Y = 3220;

	private static final long MINIMAL = 45_000L;
	private static final long OCCASIONAL = 6_000L;

	private static final int LAPS = 10;
	private static final int RUNS = 25;

	/** Each obstacle: where it is begun, where it puts the player down, and how many ticks it takes. */
	private static final int[][] OBSTACLES = {
		{3103, 3279, 0, 3102, 3279, 3, 3},   // rough wall
		{3098, 3277, 3, 3090, 3277, 3, 10},  // first rope
		{3092, 3276, 3, 3092, 3267, 3, 10},  // second rope
		{3089, 3265, 3, 3088, 3261, 3, 6},   // narrow wall
		{3088, 3257, 3, 3088, 3255, 3, 4},   // wall
		{3094, 3255, 3, 3096, 3256, 3, 3},   // gap
		{3101, 3261, 3, 3102, 3261, 0, 3},   // crate
	};

	/**
	 * Where a mark lies, by the obstacle whose landing roof it is on: beside
	 * the run to the next obstacle, so walking on passes within a tile or two.
	 */
	private static final int[][] MARK_SPOTS = {
		{1, 3091, 3278}, {2, 3091, 3266}, {4, 3091, 3254}, {0, 3100, 3280}, {5, 3098, 3258},
	};

	private enum Player
	{
		/** Picks up every mark. */
		COLLECTS,
		/** Runs past every mark. */
		LEAVES,
		/** Passes it, then turns back for it a second later. */
		DOUBLES_BACK,
	}

	/** What one run of ten laps came to. */
	private static final class Run
	{
		int markLines;
		int lapsWithAMarkLine;
		int leftUnsaid;
		int allLines;
		/** The longest a reminder took from the moment it was raised, in seconds. */
		double latestReminder;
		final List<String> said = new ArrayList<>();
	}

	private final long[] ms = {1_700_000_000_000L};

	private static WorldPoint tile(int x, int y, int plane)
	{
		return new WorldPoint(x - OFFSET_X, y - OFFSET_Y, plane);
	}

	/** The simulated course, one run. */
	private final class Course
	{
		final Harness h;
		final MarksOfGrace marks;
		final Run run = new Run();
		WorldPoint here = tile(3103, 3279, 0);
		long lastXp;
		int held;
		int calm;
		int markLinesThisLap;
		/** Marks raised as left and not yet given a line, and when each was. */
		int leftPending;
		final java.util.ArrayDeque<Long> leftAt = new java.util.ArrayDeque<>();

		Course(long gapMs) throws IOException
		{
			h = new Harness(folder.newFolder().toPath());
			h.engine.setClock(() -> ms[0]);
			h.engine.setGlobalCooldownMs(gapMs);
			marks = new MarksOfGrace(event ->
			{
				if ("left".equals(event.getName()))
				{
					leftPending++;
					leftAt.add(ms[0]);
				}
				h.dispatch(event);
			}, () -> { });
			h.game.at(here.getX(), here.getY(), here.getPlane());
			// The login baseline, taken standing at the foot of the wall.
			h.gameTicks(10);
		}

		void tick(boolean crossing)
		{
			ms[0] += 600;
			h.game.at(here.getX(), here.getY(), here.getPlane());
			h.game.animating(crossing ? 762 : -1);
			int before = h.spoken.size();
			h.gameTick();
			marks.tick(ms[0], here, crossing || calm > 0, lastXp, held, true);
			if (calm > 0)
			{
				calm--;
			}
			for (Harness.Spoken spoken : h.spoken.subList(before, h.spoken.size()))
			{
				run.allLines++;
				if (spoken.rule != null && spoken.rule.id.startsWith("mark-"))
				{
					run.markLines++;
					markLinesThisLap++;
					run.said.add(spoken.rule.id);
					if ("mark-left-behind".equals(spoken.rule.id))
					{
						leftPending--;
						run.latestReminder = Math.max(run.latestReminder,
							(ms[0] - leftAt.removeFirst()) / 1000.0);
					}
				}
			}
		}

		/** Runs to the tile, two a tick, by the straightest steps. */
		void runTo(WorldPoint to)
		{
			while (!here.equals(to))
			{
				for (int step = 0; step < 2 && !here.equals(to); step++)
				{
					here = new WorldPoint(here.getX() + Integer.signum(to.getX() - here.getX()),
						here.getY() + Integer.signum(to.getY() - here.getY()), here.getPlane());
				}
				tick(false);
			}
		}

		void obstacle(int[] o)
		{
			here = tile(o[0], o[1], o[2]);
			for (int i = 0; i < o[6] - 1; i++)
			{
				tick(true);
			}
			here = tile(o[3], o[4], o[5]);
			lastXp = ms[0] + 600;
			calm = 2;
			tick(true);
		}

		void lap(int number, Player player)
		{
			markLinesThisLap = 0;
			int[] spot = number % 2 == 0 ? MARK_SPOTS[(number / 2) % MARK_SPOTS.length] : null;
			WorldPoint mark = spot == null ? null : tile(spot[1], spot[2], 3);
			// Every other mark already lies on its roof as the lap begins.
			if (mark != null && number % 4 == 0)
			{
				marks.spawned(MarksOfGrace.MARK, mark);
			}
			for (int i = 0; i < OBSTACLES.length; i++)
			{
				int[] o = OBSTACLES[i];
				runTo(tile(o[0], o[1], o[2]));
				tick(false);
				obstacle(o);
				if (mark != null && spot[0] == i)
				{
					// The rest of them turn up as the player lands.
					if (number % 4 != 0)
					{
						marks.spawned(MarksOfGrace.MARK, mark);
					}
					int[] next = OBSTACLES[(i + 1) % OBSTACLES.length];
					if (player == Player.COLLECTS)
					{
						runTo(mark);
						pickUp(mark);
					}
					else if (player == Player.DOUBLES_BACK)
					{
						runTo(new WorldPoint(mark.getX() + Integer.signum(next[0] - OFFSET_X - mark.getX()) * 3,
							mark.getY() + Integer.signum(next[1] - OFFSET_Y - mark.getY()) * 3, 3));
						runTo(mark);
						pickUp(mark);
					}
				}
			}
			runTo(tile(3103, 3279, 0));
			if (markLinesThisLap > 0)
			{
				run.lapsWithAMarkLine++;
			}
			assertTrue("at most one mark line a lap", markLinesThisLap <= 1);
		}

		void pickUp(WorldPoint mark)
		{
			marks.despawned(MarksOfGrace.MARK, mark);
			held++;
			tick(false);
		}

		Run laps(Player player)
		{
			for (int lap = 0; lap < LAPS; lap++)
			{
				lap(lap, player);
			}
			// The last reminder's wait, played out.
			for (int i = 0; i < 100; i++)
			{
				tick(false);
			}
			run.leftUnsaid = leftPending;
			return run;
		}
	}

	private List<Run> runs(long gapMs, Player player) throws IOException
	{
		List<Run> out = new ArrayList<>();
		for (int i = 0; i < RUNS; i++)
		{
			out.add(new Course(gapMs).laps(player));
		}
		return out;
	}

	private static String summary(String label, List<Run> runs)
	{
		int lines = 0;
		int most = 0;
		int laps = 0;
		int mostLaps = 0;
		int all = 0;
		double late = 0;
		for (Run run : runs)
		{
			late = Math.max(late, run.latestReminder);
			lines += run.markLines;
			most = Math.max(most, run.markLines);
			laps += run.lapsWithAMarkLine;
			mostLaps = Math.max(mostLaps, run.lapsWithAMarkLine);
			all += run.allLines;
		}
		return String.format("%s: mark lines %.1f a run (most %d), laps with one %.1f of %d (most %d),"
				+ " every line %.1f a run, a reminder %.1f s late at most", label, lines / (double) runs.size(), most,
			laps / (double) runs.size(), LAPS, mostLaps, all / (double) runs.size(), late);
	}

	@Test
	public void aPlayerWhoCollectsEveryMarkHearsAboutFewOfThem() throws IOException
	{
		for (long gap : new long[]{MINIMAL, OCCASIONAL})
		{
			List<Run> runs = runs(gap, Player.COLLECTS);
			System.out.println(summary("collects, gap " + gap / 1000 + " s", runs));
			for (Run run : runs)
			{
				assertTrue("no reminders for marks picked up: " + run.said,
					!run.said.contains("mark-left-behind"));
				// Spotted cools for five minutes, collected for ten: ten laps
				// of about fifty seconds hear three at the very most.
				assertTrue("a commentary, not a nod: " + run.said, run.markLines <= 3);
			}
		}
	}

	@Test
	public void aPlayerWhoLeavesEveryMarkIsToldOfEachOnce() throws IOException
	{
		for (long gap : new long[]{MINIMAL, OCCASIONAL})
		{
			List<Run> runs = runs(gap, Player.LEAVES);
			System.out.println(summary("leaves, gap " + gap / 1000 + " s", runs));
			for (Run run : runs)
			{
				assertEquals("one line for each of the five marks: " + run.said, 5, run.markLines);
				assertEquals("every reminder raised was said", 0, run.leftUnsaid);
				assertTrue("never on every lap", run.lapsWithAMarkLine <= LAPS / 2);
			}
		}
	}

	@Test
	public void atMinimalAReminderInsideTheGapWaitsForIt() throws IOException
	{
		// waits, not occasion: the 45 s gap is kept, and the reminder is
		// said once it is out rather than lost to it.
		Harness h = new Harness(folder.newFolder().toPath());
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(MINIMAL);
		h.gameTicks(10);
		h.engine.remark("Hold still a moment.");
		ms[0] += 600;
		h.gameTick();
		boolean[] voiced = {false};
		h.dispatch(com.follower.speech.TriggerEvent.grace("left", () -> voiced[0] = true));
		for (int i = 0; i < 60; i++)
		{
			ms[0] += 600;
			h.gameTick();
		}
		assertTrue("not inside the gap", h.firedBy("mark-left-behind").isEmpty());
		for (int i = 0; i < 20; i++)
		{
			ms[0] += 600;
			h.gameTick();
		}
		assertEquals("said once the gap is out", 1, h.firedBy("mark-left-behind").size());
		assertTrue("and the mark knows it had its line", voiced[0]);
	}

	@Test
	public void aPlayerWhoDoublesBackIsNotReminded() throws IOException
	{
		for (long gap : new long[]{MINIMAL, OCCASIONAL})
		{
			List<Run> runs = runs(gap, Player.DOUBLES_BACK);
			System.out.println(summary("doubles back, gap " + gap / 1000 + " s", runs));
			for (Run run : runs)
			{
				assertTrue("picked up a second after passing: " + run.said,
					!run.said.contains("mark-left-behind"));
				assertTrue(run.markLines <= 3);
			}
		}
	}
}

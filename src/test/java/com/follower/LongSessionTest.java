package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Random;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;

/**
 * A session long enough for slow growth to show.
 *
 * <p>The state snapshot remembers things on purpose - how often each region has
 * been visited, when each was last seen, which NPCs the player has damaged -
 * and every one of those is a map that only ever gets keys added. A player who
 * leaves the client running all day walks through a lot of regions and hits a
 * lot of monsters. None of it is freed by anything on the tick path, so the
 * question is whether the growth is bounded by something real or just by how
 * long the session is.
 *
 * <p>Nothing here asserts an exact number; the point is the shape of the curve.
 */
public class LongSessionTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@SuppressWarnings("unchecked")
	private static Map<Integer, Integer> mapField(Object target, String name)
	{
		try
		{
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return (Map<Integer, Integer>) field.get(target);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError("the field this test watches has been renamed: " + name, e);
		}
	}

	@Test
	public void regionMemoryIsBoundedByPlacesNotByTime() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		Random random = new Random(20260808L);

		// Twenty places, walked between for a very long time. The memory should
		// settle at twenty entries, not keep climbing with the tick count.
		WorldPoint[] places = new WorldPoint[20];
		for (int i = 0; i < places.length; i++)
		{
			places[i] = new WorldPoint(3200 + i * 64, 3200, 0);
		}

		int afterFirstLap = 0;
		for (int step = 0; step < 20000; step++)
		{
			WorldPoint place = places[random.nextInt(places.length)];
			h.game.at(place.getX(), place.getY(), 0);
			h.gameTick();

			if (step == 2000)
			{
				afterFirstLap = mapField(h.engine.getContext(), "regionLastSeenTick").size();
			}
		}

		Map<Integer, Integer> lastSeen = mapField(h.engine.getContext(), "regionLastSeenTick");
		Map<Integer, Integer> visits = mapField(h.engine.getContext(), "regionVisits");

		assertTrue("the test did not actually move between regions",
			afterFirstLap > 1);
		assertTrue("region memory grew to " + lastSeen.size() + " entries for "
				+ places.length + " places, so it is keyed by something other"
				+ " than the place", lastSeen.size() <= places.length + 2);
		assertTrue("visit counts grew to " + visits.size() + " entries",
			visits.size() <= places.length + 2);
	}

	@Test
	public void aVeryLongSessionOfEverythingDoesNotDrift() throws IOException
	{
		// Not a leak test so much as a soak: a hundred thousand ticks of the
		// whole event mix, checking it neither throws nor slows to a crawl nor
		// stops answering at the end.
		Harness h = new Harness(folder.newFolder().toPath());
		Random random = new Random(99L);
		h.game.spawnNpc(3029, "Goblin", 5);

		long start = System.nanoTime();
		for (int step = 0; step < 100000; step++)
		{
			switch (random.nextInt(12))
			{
				case 0:
					h.game.at(3200 + random.nextInt(40) * 64, 3200, 0);
					break;
				case 1:
					h.dispatch(TriggerEvent.kill(1, "Goblin", random.nextInt(900)));
					break;
				case 2:
					h.dispatch(TriggerEvent.loot(random.nextInt(300000), "Bones"));
					break;
				case 3:
					h.dispatch(TriggerEvent.damageTaken(1 + random.nextInt(40)));
					break;
				case 4:
					h.game.hitpoints(1 + random.nextInt(99), 99);
					break;
				case 5:
					h.dispatch(TriggerEvent.animation(862));
					break;
				case 6:
					h.game.energy(random.nextInt(10001));
					break;
				case 7:
					h.dispatch(TriggerEvent.death());
					break;
				default:
					break;
			}
			h.gameTick();

			// The sink would otherwise hold every line ever spoken; the plugin
			// does not keep them, so neither should the measurement.
			if (h.spoken.size() > 500)
			{
				h.clear();
			}
		}
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		// A hundred thousand ticks is about seventeen hours of play. If that
		// takes minutes here, something is accumulating per-tick work.
		assertTrue("100,000 ticks took " + elapsedMs + "ms, which suggests the"
			+ " per-tick cost is growing with session length", elapsedMs < 60000);

		// Still working afterwards.
		h.clear();
		h.game.hitpoints(1, 99);
		h.gameTicks(3);
	}

	@Test
	public void thePendingQueueDrainsRatherThanAccumulating() throws IOException
	{
		// A delayed rule that is re-triggered constantly must not build up a
		// backlog of firings waiting to be spoken.
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [{\"id\": \"slow\", \"group\": \"t\","
				+ " \"cooldownMs\": 0, \"delayTicks\": 3,"
				+ " \"when\": {\"type\": \"playerDeath\"}, \"say\": [\"a\"]}]}");

		for (int i = 0; i < 5000; i++)
		{
			h.dispatch(TriggerEvent.death());
			h.gameTick();
		}

		Object engine = h.engine;
		try
		{
			Field field = engine.getClass().getDeclaredField("pending");
			field.setAccessible(true);
			java.util.List<?> pending = (java.util.List<?>) field.get(engine);
			assertTrue("the delayed-firing queue holds " + pending.size()
				+ " entries after 5000 triggers", pending.size() <= 5);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError("the pending queue has been renamed", e);
		}
	}

	@Test
	public void aSessionSpentEntirelyInOnePlaceStaysCheap() throws IOException
	{
		// The common case: standing at a bank for hours. Nothing should be
		// recorded over and over for a region already known.
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(20000);

		Map<Integer, Integer> lastSeen = mapField(h.engine.getContext(), "regionLastSeenTick");
		assertTrue("standing still recorded " + lastSeen.size() + " regions",
			lastSeen.size() <= 1);
		assertTrue("standing still counted "
				+ h.engine.getContext().getRegionVisits() + " visits to one place",
			h.engine.getContext().getRegionVisits() <= 1);
	}
}

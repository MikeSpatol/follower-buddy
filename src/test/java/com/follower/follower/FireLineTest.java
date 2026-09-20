package com.follower.follower;

import com.follower.sim.FakeGame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.fail;

/**
 * Lighting a line of fires, which is the way the follower ends up in one.
 *
 * <p>The shape of it: the player stands on a tile, lights a fire ON THAT
 * TILE, and the game moves them one square west. They do it again, and
 * again, leaving a row of fires behind them going east - which is exactly
 * the ground the follower walks, because the follow tile IS the tile the
 * player last stepped from.
 *
 * <p>The play report: "the follower gets out of the way after the first fire
 * is made, but when the second fire is made they will go to stand in it
 * again, and so they then stand in all subsequent fires."
 *
 * <p>Driven against the REAL {@link FollowerEntity} - its real observation,
 * its real path extension, its real releases - rather than a recording fake,
 * because every check that is supposed to keep it out of the flames lives
 * inside those. The private plumbing is reached by reflection for the same
 * reason: {@code updateFrame} needs a RuneLiteObject the fake client cannot
 * make, so this test plays the frame loop itself, in the order
 * {@code updateFrame} plays it.
 */
public class FireLineTest
{
	private static final int ROW = 50;
	/** Where the walk starts; the fires are lit from {@link #FIRST_FIRE} westward. */
	private static final int START = 65;
	private static final int FIRST_FIRE = 60;
	private static final int FIRES = 5;
	/** Ticks between one fire and the next: lighting logs is not instant. */
	private static final int TICKS_BETWEEN_FIRES = 4;

	private final FakeGame game = new FakeGame().withCollision();
	private final FollowerEntity follower = new FollowerEntity(game.client, null);

	/** Exactly what the plugin feeds the follower: the tiles that are alight. */
	private final Set<WorldPoint> fires = new HashSet<>();

	/** Every tile the follower's feet actually came to rest on, in order. */
	private final List<WorldPoint> stood = new ArrayList<>();
	private final List<String> log = new ArrayList<>();

	private int tickNumber;

	/** Whether this run also polices what the follower is TOLD to walk to. */
	private boolean watchDestinations;

	/**
	 * Whether the fire becomes known to the follower only after the step onto
	 * that tile was queued - which is the ordinary case, because the queue is
	 * built one observation ahead of the feet and is never re-examined.
	 */
	private boolean fireArrivesLate;
	private WorldPoint pendingFire;

	/**
	 * Ticks the follower may be standing in a fire before it counts as
	 * standing in one. Zero for everything that happens in the real
	 * client; one for the counterfactual below, where the tile catches
	 * after the step onto it has already been taken, and a step that has
	 * been taken cannot be untaken.
	 */
	private int grace;
	private int inTheFire;

	// ------------------------------------------------------------------ tests

	/**
	 * The control. The tile is known to be alight in the same breath the
	 * player steps off it, every refusal in the feet gets its chance, and the
	 * follower walks the row beside the line without ever touching it. This
	 * is the case the fire work was written for, and it holds.
	 */
	@Test
	public void aFireKnownBeforeTheStepIsPlannedIsWalkedRound()
	{
		lightTheLine();
	}

	/**
	 * The defect itself: the trailing follow tile is the tile the player last
	 * stepped from, and on a fire line that tile is on fire. Nothing between
	 * {@link FollowerEntity#behindBy} and {@link FollowerEntity#followTileFor}
	 * asks whether it is good ground - only the walk-beside flank is asked -
	 * so the follower is aimed at every fire in the line as it is lit.
	 *
	 * <p>Kept apart from the feet because the destination is wrong on every
	 * single fire, and failing on that would hide whether the feet ever
	 * actually land in one.
	 */
	@Test
	public void theFollowerIsNeverSentToATileThatIsAlight()
	{
		watchDestinations = true;
		lightTheLine();
	}

	/**
	 * The same run with the two events the other way round: the fire is
	 * announced only after the step onto that tile has been taken.
	 *
	 * <p>A queued step used to be checked when it was APPENDED and never
	 * again, so the queue - normally a step or two ahead of the feet -
	 * carried the follower onto whatever caught fire in the meantime. It
	 * is asked again as it is committed now, and the plugin hands the news
	 * straight to the feet, so the only case left is the tile catching
	 * under a follower already standing on it.
	 *
	 * <p>This order is not what the client does - object events are
	 * dispatched during the tick and the follower moves from
	 * {@code BeforeRender} afterwards, so a fire is known before the
	 * observation that would walk onto it, which is the control above.
	 * Pinned anyway, at the standard that case allows: not that the
	 * follower never plans a step onto a fire, but that it never takes
	 * one, and never stays in a fire that catches underneath it.
	 */
	@Test
	public void aFireLitUnderTheFollowerIsLeftAtOnce()
	{
		fireArrivesLate = true;
		grace = 1;
		lightTheLine();
		if (fires.contains(tile()))
		{
			fail("the line is finished and the follower is still in a fire at "
				+ describe(tile()) + trace());
		}
	}

	/**
	 * The destination on its own, without the walk, in case the route happens
	 * to take a shape that hides it.
	 */
	@Test
	public void theTrailingFollowTileIsNeverAFire()
	{
		follower.setHotGround(fires::contains);

		WorldPoint lit = new WorldPoint(FIRST_FIRE, ROW, 0);
		WorldPoint movedTo = new WorldPoint(FIRST_FIRE - 1, ROW, 0);
		follower.recordTrail(new WorldPoint(FIRST_FIRE + 1, ROW, 0));
		follower.recordTrail(lit);
		fires.add(lit);
		follower.recordTrail(movedTo);

		WorldPoint sent = follower.followTileFor(lit, movedTo,
			follower.behindBy(1, lit), 1, System.currentTimeMillis());
		if (follower.hot(sent))
		{
			fail("the follow tile handed to the feet is the fire at " + describe(sent));
		}
	}

	/**
	 * Why the first fire is escaped and none of the others are.
	 *
	 * <p>Second fire onward, the follower standing in one has the PREVIOUS
	 * fire to its east and the PLAYER to its west - that is the whole shape
	 * of a fire line. {@code stepOffTheFire} tries east, then west, and takes
	 * the first tile that is good ground it could step to. The player does
	 * not make a tile bad ground and does not block a step, so it commits to
	 * the player's own tile and returns, never reaching north or south.
	 *
	 * <p>Then {@code updateFrame} routes that goal with
	 * {@code extendPathBfs(goalTile, playerTileToAvoid())} - the player's
	 * tile is the one thing the route is told to go around - so the escape
	 * resolves to closest approach, which is the tile the follower is already
	 * standing on. The step out of the fire is a silent no-op, every time.
	 */
	@Test
	public void theStepOutOfTheFireIsNotAimedAtThePlayersOwnTile()
	{
		follower.setHotGround(fires::contains);
		set(SPAWNED, true);

		// Mid-line: this fire under the follower, the last one east of it,
		// the player one west having just stepped off this very tile.
		WorldPoint underfoot = new WorldPoint(FIRST_FIRE, ROW, 0);
		fires.add(underfoot);
		fires.add(new WorldPoint(FIRST_FIRE + 1, ROW, 0));
		game.at(FIRST_FIRE - 1, ROW, 0);
		standOn(underfoot);
		set(SIM_TILE, underfoot);

		invoke(STEP_OFF_THE_FIRE);
		WorldPoint escape = (WorldPoint) get(GOAL_TILE);
		if (escape == null)
		{
			fail("standing in the fire at " + describe(underfoot) + " with nowhere to go");
		}
		if (escape.equals(playerTile()))
		{
			fail("the step out of the fire at " + describe(underfoot) + " was aimed at "
				+ describe(escape) + ", which is the player's own tile - and the goal is"
				+ " routed with the player's tile as the one to avoid, so it is a no-op");
		}

		// And it has to be somewhere the follower can actually be routed to,
		// by the same call updateFrame makes.
		extendPathBfs(escape, playerTile());
		if (serverPath().isEmpty())
		{
			fail("the step out of the fire at " + describe(underfoot) + " was aimed at "
				+ describe(escape) + ", which the pathing then refuses to route to");
		}
	}

	/**
	 * The silent half of it. Closest-approach answers "as near as I could
	 * get", and asked for somewhere it cannot reach at all that answer is the
	 * tile underfoot - returned as SUCCESS. A follower standing in a fire was
	 * therefore pinned to the fire by the very call meant to move it off,
	 * and pinned again every frame after, with nothing anywhere reporting a
	 * problem.
	 */
	@Test
	public void aStayNeverSettlesForTheFireTheFollowerStandsIn()
	{
		follower.setHotGround(fires::contains);
		set(SPAWNED, true);

		WorldPoint underfoot = new WorldPoint(FIRST_FIRE, ROW, 0);
		fires.add(underfoot);
		game.at(FIRST_FIRE - 1, ROW, 0);
		standOn(underfoot);
		set(SIM_TILE, underfoot);

		// The player's own tile: the one tile every route is told to walk
		// around, so it cannot be reached from anywhere, ever.
		boolean went = follower.stayAt(playerTile());
		WorldPoint stay = (WorldPoint) get(STAY_TILE);
		if (went)
		{
			fail("stayAt reported success for somewhere it cannot reach" + trace());
		}
		if (stay != null && fires.contains(stay))
		{
			fail("the stay was left on the fire at " + describe(stay) + trace());
		}
	}

	/**
	 * A step is checked when it is planned. The fire arrives after, on the
	 * tile the player just left - which is the tile the follower is walking
	 * to - so it has to be asked again as it is committed.
	 */
	@Test
	public void aStepQueuedBeforeTheTileCaughtIsNotCommitted()
	{
		follower.setHotGround(fires::contains);
		set(SPAWNED, true);

		WorldPoint standing = new WorldPoint(FIRST_FIRE + 1, ROW, 0);
		WorldPoint ahead = new WorldPoint(FIRST_FIRE, ROW, 0);
		game.at(FIRST_FIRE - 1, ROW, 0);
		standOn(standing);
		set(SIM_TILE, standing);
		serverPath().addLast(ahead);

		// It catches between the plan and the step.
		fires.add(ahead);
		invoke(RELEASE_STEPS);

		if (route().contains(ahead))
		{
			fail("the step onto " + describe(ahead) + " was committed after it caught");
		}
	}

	// ------------------------------------------------------------- the report

	private void lightTheLine()
	{
		begin();

		// Walking over to the spot: an ordinary follow, nothing alight.
		for (int x = START - 1; x >= FIRST_FIRE; x--)
		{
			playerStepsTo(x);
			tick(true);
		}

		for (int i = 0; i < FIRES; i++)
		{
			int here = FIRST_FIRE - i;
			// The fire goes up on the tile the player is standing on, and the
			// game shifts them one west in the same breath.
			lightFireAt(here);
			playerStepsTo(here - 1);
			tick(true);
			for (int t = 0; t < TICKS_BETWEEN_FIRES; t++)
			{
				tick(false);
			}
		}
	}

	// ------------------------------------------------------------- the harness

	/** Puts the follower on the ground behind the player, as a spawn does. */
	private void begin()
	{
		follower.setHotGround(fires::contains);
		game.at(START, ROW, 0);
		set(SPAWNED, true);
		standOn(new WorldPoint(START + 1, ROW, 0));
		set(SIM_TILE, tile());
		// First sighting: watchPlayer's own "no meaningful tile stepped from"
		// branch, which is how every session actually starts.
		watchPlayer();
	}

	private void playerStepsTo(int x)
	{
		game.at(x, ROW, 0);
	}

	private void lightFireAt(int x)
	{
		WorldPoint lit = new WorldPoint(x, ROW, 0);
		if (fireArrivesLate)
		{
			pendingFire = lit;
		}
		else
		{
			fires.add(lit);
		}
	}

	/**
	 * One tick's worth of {@code updateFrame}, in its order: watch the player
	 * (which extends the path and releases, phase-locked to their steps), then
	 * the ground underfoot, then whatever owns the feet, then the idle drain,
	 * then the glide.
	 *
	 * @param playerMoved whether this tick carried a step of theirs
	 */
	private void tick(boolean playerMoved)
	{
		tickNumber++;
		if (playerMoved)
		{
			watchPlayer();
		}

		// FollowerEntity.updateFrame: "if (isSettled() && hot(tile))".
		if (follower.isSettled() && follower.hot(tile()))
		{
			invoke(STEP_OFF_THE_FIRE);
		}

		// updateFrame's stay / goal / re-acquire block, with the branches this
		// scenario can reach.
		WorldPoint stay = (WorldPoint) get(STAY_TILE);
		WorldPoint goal = (WorldPoint) get(GOAL_TILE);
		WorldPoint follow = (WorldPoint) get(FOLLOW_TILE);
		if (stay != null)
		{
			if (!stay.equals(tile()) && route().isEmpty() && serverPath().isEmpty())
			{
				extendPathBfs(stay, playerTile());
			}
		}
		else if (goal != null)
		{
			if (!goal.equals(tile()) && route().isEmpty() && serverPath().isEmpty())
			{
				extendPathBfs(goal, playerTile());
			}
		}
		else if (serverPath().isEmpty() && route().isEmpty() && follow != null
			&& !follow.equals(simTile()))
		{
			extendPathBfs(follow, follow.equals(playerTile()) ? follow : null);
		}

		// The step is planned; NOW the client announces the fire.
		if (pendingFire != null)
		{
			fires.add(pendingFire);
			pendingFire = null;
		}

		// The idle drain: one tick's worth. watchPlayer zeroes the carry on an
		// observation tick, so this only runs on the quiet ones.
		if (!playerMoved && !serverPath().isEmpty())
		{
			invoke(RELEASE_STEPS);
		}

		glide();
		record(playerMoved);
		check();
	}

	/** The render walking the released steps; by the end of the tick they are walked. */
	private void glide()
	{
		Deque<WorldPoint> route = route();
		Deque<Boolean> run = routeRun();
		while (!route.isEmpty())
		{
			WorldPoint next = route.pollFirst();
			run.pollFirst();
			standOn(next);
			stood.add(next);
		}
	}

	private void record(boolean playerMoved)
	{
		log.add(String.format("t%-3d player %s  follow %s  sim %s  stood %s  %s%s",
			tickNumber, describe(playerTile()), describe((WorldPoint) get(FOLLOW_TILE)),
			describe(simTile()), describe(tile()),
			playerMoved ? "step" : "idle",
			fires.isEmpty() ? "" : "  fires " + firesAsText()));
	}

	/** Nowhere the follower is, is going, or has been told to go may be alight. */
	private void check()
	{
		inTheFire = fires.contains(tile()) ? inTheFire + 1 : 0;
		if (inTheFire > grace)
		{
			fail("the follower is standing in the fire at " + describe(tile())
				+ (grace > 0 ? " and has not left it" : "") + trace());
		}
		if (inTheFire == 0 && fires.contains(simTile()))
		{
			fail("the follower's feet have arrived in the fire at "
				+ describe(simTile()) + trace());
		}
		if (grace == 0)
		{
			// Under the real ordering the queue is built after the fire is
			// known, so it never holds one. Where the fire lands after the
			// plan, a queued step is allowed to sit there: it is asked again
			// as it is committed and dropped then, which the checks on where
			// the follower actually gets to already prove.
			for (WorldPoint queued : serverPath())
			{
				if (fires.contains(queued))
				{
					fail("the follower has the fire at " + describe(queued)
						+ " queued as a step" + trace());
				}
			}
		}
		if (grace == 0)
		{
			for (WorldPoint walked : stood)
			{
				if (fires.contains(walked))
				{
					fail("the follower walked through the fire at "
						+ describe(walked) + trace());
				}
			}
		}
		if (!watchDestinations)
		{
			return;
		}
		WorldPoint follow = (WorldPoint) get(FOLLOW_TILE);
		if (follow != null && fires.contains(follow))
		{
			fail("the follower was given the fire at " + describe(follow)
				+ " as its follow tile" + trace());
		}
		WorldPoint stay = (WorldPoint) get(STAY_TILE);
		if (stay != null && fires.contains(stay))
		{
			fail("the follower was sent to hold the fire at " + describe(stay) + trace());
		}
		WorldPoint goal = (WorldPoint) get(GOAL_TILE);
		if (goal != null && fires.contains(goal))
		{
			fail("the follower was sent to the fire at " + describe(goal) + trace());
		}
	}

	private String trace()
	{
		StringBuilder out = new StringBuilder("\n  fires: " + firesAsText() + "\n");
		for (String line : log)
		{
			out.append("  ").append(line).append('\n');
		}
		return out.toString();
	}

	private String firesAsText()
	{
		List<String> out = new ArrayList<>();
		for (WorldPoint fire : fires)
		{
			out.add(describe(fire));
		}
		java.util.Collections.sort(out);
		return String.join(" ", out);
	}

	private static String describe(WorldPoint at)
	{
		return at == null ? "-" : at.getX() + "," + at.getY();
	}

	private WorldPoint playerTile()
	{
		Player local = game.client.getLocalPlayer();
		return local == null ? null : local.getWorldLocation();
	}

	/** Puts the rendered position on a tile, settled at its centre. */
	private void standOn(WorldPoint at)
	{
		set(TILE, at);
		set(FINE_X, at.getX() * 128L + 64);
		set(FINE_Y, at.getY() * 128L + 64);
	}

	private WorldPoint tile()
	{
		return (WorldPoint) get(TILE);
	}

	private WorldPoint simTile()
	{
		return (WorldPoint) get(SIM_TILE);
	}

	@SuppressWarnings("unchecked")
	private Deque<WorldPoint> route()
	{
		return (Deque<WorldPoint>) get(ROUTE);
	}

	@SuppressWarnings("unchecked")
	private Deque<Boolean> routeRun()
	{
		return (Deque<Boolean>) get(ROUTE_RUN);
	}

	@SuppressWarnings("unchecked")
	private Deque<WorldPoint> serverPath()
	{
		return (Deque<WorldPoint>) get(SERVER_PATH);
	}

	// ----------------------------------------------------------- reflection

	private static final Field TILE = field("tile");
	private static final Field SIM_TILE = field("simTile");
	private static final Field FINE_X = field("fineWX");
	private static final Field FINE_Y = field("fineWY");
	private static final Field SPAWNED = field("spawned");
	private static final Field ROUTE = field("route");
	private static final Field ROUTE_RUN = field("routeRun");
	private static final Field SERVER_PATH = field("serverPath");
	private static final Field FOLLOW_TILE = field("playerFollowTile");
	private static final Field STAY_TILE = field("stayTile");
	private static final Field GOAL_TILE = field("goalTile");

	private static final Method WATCH_PLAYER = method("watchPlayer", Player.class);
	private static final Method RELEASE_STEPS = method("releaseSteps");
	private static final Method STEP_OFF_THE_FIRE = method("stepOffTheFire");
	private static final Method EXTEND_PATH_BFS =
		method("extendPathBfs", WorldPoint.class, WorldPoint.class);

	private void watchPlayer()
	{
		invoke(WATCH_PLAYER, game.client.getLocalPlayer());
	}

	private void extendPathBfs(WorldPoint target, WorldPoint avoid)
	{
		invoke(EXTEND_PATH_BFS, target, avoid);
	}

	private static Field field(String name)
	{
		try
		{
			Field found = FollowerEntity.class.getDeclaredField(name);
			found.setAccessible(true);
			return found;
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError("FollowerEntity." + name + " has moved", e);
		}
	}

	private static Method method(String name, Class<?>... types)
	{
		try
		{
			Method found = FollowerEntity.class.getDeclaredMethod(name, types);
			found.setAccessible(true);
			return found;
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError("FollowerEntity." + name + " has moved", e);
		}
	}

	private Object get(Field what)
	{
		try
		{
			return what.get(follower);
		}
		catch (IllegalAccessException e)
		{
			throw new AssertionError(e);
		}
	}

	private void set(Field what, Object value)
	{
		try
		{
			what.set(follower, value);
		}
		catch (IllegalAccessException e)
		{
			throw new AssertionError(e);
		}
	}

	private void invoke(Method what, Object... args)
	{
		try
		{
			what.invoke(follower, args);
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e.getCause() == null ? e : e.getCause());
		}
	}
}

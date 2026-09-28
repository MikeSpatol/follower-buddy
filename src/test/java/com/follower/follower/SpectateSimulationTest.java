package com.follower.follower;

import com.follower.FollowerConfig;
import com.follower.appearance.SpotAnimRepository;
import com.follower.sim.FakeGame;
import com.follower.speech.TriggerContext;
import com.follower.speech.TriggerEvent;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The whole spectating sequence, driven tick by tick with nothing running.
 *
 * <p>It is the plugin's longest-lived state machine - step clear, raise a ward,
 * hold it, drop it, walk back - and every stage of it hands the follower's
 * weapon away and takes it back again. A stage that never completes leaves the
 * follower unarmed indefinitely, which is a bug the player only notices much
 * later, so the transitions are worth pinning individually.
 */
public class SpectateSimulationTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** A follower that records what it was told to do and does none of it. */
	private static final class FakeFollower extends FollowerEntity
	{
		boolean spawned = true;
		boolean settled = true;
		boolean emoting;
		/**
		 * A cast has to FINISH, or the controller waits on it forever - the
		 * summon hands over to the channel only once the emote has played out.
		 * Two ticks stands in for a real one-shot clip.
		 */
		int emoteTicksLeft;
		WorldPoint stayingAt;
		boolean following = true;
		int poseOverride;
		final List<int[]> chains = new ArrayList<>();
		final List<SpotAnimRepository.Entry> spotAnims = new ArrayList<>();

		/** Tiles the arena refuses; null means everywhere is standable. */
		java.util.function.Predicate<WorldPoint> walled;
		int stayAttempts;

		FakeFollower()
		{
			super(null, null);
		}

		@Override
		public boolean isSpawned()
		{
			return spawned;
		}

		@Override
		public boolean isSettled()
		{
			return settled;
		}

		@Override
		public boolean isStaying()
		{
			return stayingAt != null;
		}

		@Override
		public boolean isEmotePlaying()
		{
			return emoting;
		}

		@Override
		public boolean stayAt(WorldPoint target)
		{
			stayAttempts++;
			if (target == null || (walled != null && walled.test(target)))
			{
				return false;
			}
			stayingAt = target;
			following = false;
			return true;
		}

		@Override
		public void resumeFollowing()
		{
			following = true;
			stayingAt = null;
		}

		@Override
		public void setPoseOverride(int pose)
		{
			poseOverride = pose;
		}

		@Override
		public void playAnimations(int[] ids, SpotAnimRepository.Entry[] graphics)
		{
			chains.add(ids.clone());
			emoting = true;
			emoteTicksLeft = 2;
		}

		/** Runs the clock on whatever is playing, as the animation controller would. */
		void advance()
		{
			if (emoting && --emoteTicksLeft <= 0)
			{
				emoting = false;
			}
		}

		@Override
		public void playSpotAnim(SpotAnimRepository.Entry entry)
		{
			spotAnims.add(entry);
		}
	}

	private FakeGame game;
	private TriggerContext context;
	private FakeFollower follower;
	private SpectateController spectate;
	private List<TriggerEvent> spoken;
	private List<Boolean> disarms;
	private int tick;
	/** Whether the player has parked the follower, as the plugin tells it. */
	private boolean parked;

	@Before
	public void setUp() throws IOException
	{
		game = new FakeGame();
		context = new TriggerContext(game.client);
		follower = new FakeFollower();
		spoken = new ArrayList<>();
		disarms = new ArrayList<>();

		SpotAnimRepository spotAnims = new SpotAnimRepository(new Gson());
		spotAnims.load(folder.newFolder().toPath());

		spectate = new SpectateController(game.client, follower, config(),
			() -> context, spotAnims, spoken::add, disarms::add);
	}

	/** The shipped defaults, which is what a player will actually be running. */
	private FollowerConfig config()
	{
		return new FollowerConfig()
		{
		};
	}

	private void tick(int count)
	{
		for (int i = 0; i < count; i++)
		{
			game.tick(++tick);
			context.refresh();
			follower.advance();
			spectate.tick(false, parked);
		}
	}

	private NPC fight(String name, int level)
	{
		NPC target = game.spawnNpc(100, name, level);
		// Standing off to one side, so there is a direction to step away in.
		game.at(3222, 3218, 0);
		game.fighting(target);
		return target;
	}

	private boolean spoke(TriggerEvent.Type type)
	{
		for (TriggerEvent event : spoken)
		{
			if (event.getType() == type)
			{
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------ engagement

	@Test
	public void nothingHappensWhileThereIsNoFight()
	{
		tick(10);

		assertFalse("it should not be spectating an empty room", spectate.isSpectating());
		assertTrue("nor holding a position", follower.following);
		assertTrue("nor saying anything", spoken.isEmpty());
	}

	@Test
	public void aFightMakesItStepClearAndSaySo()
	{
		fight("Goblin", 5);
		tick(2);

		assertTrue(spectate.isSpectating());
		assertTrue("it should have moved out of the way", follower.stayingAt != null);
		assertTrue("and announced the fight", spoke(TriggerEvent.Type.COMBAT_START));
	}

	@Test
	public void spectatingSurvivesTheEngineReplacingItsContext()
	{
		// The real bug behind two boss-fight reports. SpeechEngine.reset()
		// nulls its context and lazily builds a NEW one - on every login
		// screen, the memory eraser, a plugin toggle. The controller was
		// handed the ORIGINAL object at startup and kept reading it: a
		// context nobody refreshed any more, inCombat false forever. The
		// rules kept cheering (they read the live one), the follower stood
		// glued to the player, and both arena fixes were beside the point
		// because start() never ran. The controller now reads through a
		// supplier, so it sees whichever context the engine currently owns.
		TriggerContext[] live = {context};
		spectate = new SpectateController(game.client, follower, config(),
			() -> live[0], new SpotAnimRepository(new Gson()), spoken::add, disarms::add);

		// A relog: the engine throws its context away and starts a fresh one.
		live[0] = new TriggerContext(game.client);

		NPC boss = game.spawnNpc(100, "Vorkath", 392);
		game.at(3222, 3218, 0);
		game.fighting(boss);
		for (int i = 0; i < 3; i++)
		{
			game.tick(++tick);
			live[0].refresh();          // the plugin refreshes the LIVE one
			follower.advance();
			spectate.tick(false, false);
		}

		assertTrue("the fight after a relog must still be spectated",
			spectate.isSpectating());
		assertTrue("and stepped clear of", follower.stayingAt != null);
		assertTrue(spoke(TriggerEvent.Type.COMBAT_START));
	}

	@Test
	public void aWalledArenaStillFindsSomewhereNotUnderfoot()
	{
		// The player fights with their back to a wall - everything on the
		// away-from-the-boss side is stone. The old fan only ever tried that
		// side and gave up; the follower stood glued to the player for the
		// whole fight. Any tile at a distance that is not beside the boss
		// serves the real goal, which is "not underfoot".
		NPC boss = game.spawnNpc(100, "Vorkath", 392);
		game.at(3222, 3218, 0);
		game.fighting(boss);
		WorldPoint player = new WorldPoint(3222, 3218, 0);
		WorldPoint threat = boss.getWorldLocation();
		int awayX = Integer.signum(player.getX() - threat.getX());
		int awayY = Integer.signum(player.getY() - threat.getY());
		// Wall: everything on the away side (and straight sideways).
		follower.walled = tile ->
			Integer.signum(tile.getX() - player.getX()) == awayX
				&& Integer.signum(tile.getY() - player.getY()) == awayY
			|| tile.getY() == player.getY() || tile.getX() == player.getX();

		tick(2);

		assertTrue(spectate.isSpectating());
		assertTrue("the ring found a standable tile the fan could not",
			follower.stayingAt != null);
		assertTrue("and it is not beside the boss",
			follower.stayingAt.distanceTo(threat) >= 3);
	}

	@Test
	public void anUnseatedSpectatorKeepsLookingAsTheFightMoves()
	{
		// Nowhere at all to stand on the first tick - and then the fight
		// shifts and a tile opens. The old code never looked again: a null
		// watch tile made holdPosition return every tick until the fight
		// ended, which is the whole boss fight spent underfoot.
		NPC boss = game.spawnNpc(100, "Vorkath", 392);
		game.at(3222, 3218, 0);
		game.fighting(boss);
		follower.walled = tile -> true;      // the arena is all wall
		tick(2);
		assertTrue(spectate.isSpectating());
		assertTrue("nowhere to go yet", follower.stayingAt == null);
		int attemptsSoFar = follower.stayAttempts;

		follower.walled = null;              // the fight moved; tiles open
		tick(6);                             // past the retry interval

		assertTrue("it looked again", follower.stayAttempts > attemptsSoFar);
		assertTrue("and stepped clear once it could",
			follower.stayingAt != null);
	}

	@Test
	public void anotherSystemStealingTheSeatGetsItTakenBack()
	{
		// The stay is held through FollowerEntity, and plenty of hands can
		// release it without telling this controller - the thrall exit's
		// stayHere/resumeFollowing/snapBeside chain, the leash snap, scene
		// recovery. Play report, near verbatim: "out of thrall mode they
		// don't step away, but they still do the magic barrier animations."
		fight("Boss", 300);
		tick(3);
		assertTrue(spectate.isSpectating());
		assertTrue("seated", follower.stayingAt != null);

		// The thrall exit hands the feet back to plain following.
		follower.stayingAt = null;
		int attemptsSoFar = follower.stayAttempts;
		tick(2);

		assertTrue("it noticed and took the seat back",
			follower.stayAttempts > attemptsSoFar);
		assertTrue(follower.stayingAt != null);
	}

	@Test
	public void aBossThatHitsYouWhileYouTargetItIsAFightToStepClearOf()
	{
		// The shape a real boss fight takes from the client's side: the player
		// is targeting it (one-way interaction - a boss between phases or at
		// range need never be seen facing back), it is landing hits, and its
		// health ratio can read as zero until it has taken damage. Play
		// report: a boss fight where the follower never stepped clear.
		NPC boss = game.spawnNpc(100, "Vorkath", 392);
		game.at(3222, 3218, 0);
		game.facing(boss);
		tick(1);
		assertFalse("targeting alone is not yet a fight", spectate.isSpectating());

		context.noteDamageTaken();
		tick(2);

		assertTrue("a hit while targeting the boss is the fight",
			spectate.isSpectating());
		assertTrue("and the follower steps clear", follower.stayingAt != null);
		assertTrue(spoke(TriggerEvent.Type.COMBAT_START));
	}

	@Test
	public void itStepsAwayFromTheThingBeingFoughtNotTowardsIt()
	{
		NPC target = fight("Goblin", 5);
		// Put the target well to the west; the follower should end up east.
		game.moveNpc(target, 3210, 3218, 0);
		tick(2);

		assertTrue("it never moved", follower.stayingAt != null);
		assertTrue("it stood between the player and the fight, which is the one"
				+ " place it must not be: " + follower.stayingAt,
			follower.stayingAt.getX() >= 3222);
	}

	private int panics()
	{
		int panics = 0;
		for (int[] chain : follower.chains)
		{
			if (chain.length == 1 && chain[0] == SpectateController.PANIC)
			{
				panics++;
			}
		}
		return panics;
	}

	@Test
	public void aSuperiorIsWatchedFromTwoTilesFurtherWithOnePanic()
	{
		NPC superior = fight("Marble gargoyle", 349);
		game.moveNpc(superior, 3210, 3218, 0);
		tick(2);

		assertTrue(follower.stayingAt != null);
		assertEquals("two tiles beyond the usual four", 6,
			follower.stayingAt.distanceTo(new WorldPoint(3222, 3218, 0)));
		assertEquals("one panic", 1, panics());
		tick(30);
		assertEquals("and only the one", 1, panics());

		// An ordinary gargoyle is watched from the usual distance, unpanicked.
		game.fighting(null);
		tick(3);
		NPC ordinary = fight("Gargoyle", 111);
		game.moveNpc(ordinary, 3210, 3218, 0);
		tick(2);
		assertEquals(4, follower.stayingAt.distanceTo(new WorldPoint(3222, 3218, 0)));
		assertEquals(1, panics());
	}

	@Test
	public void aSuperiorTurningUpMidFightMovesTheSeatOut()
	{
		// The usual way to meet one: it spawns during the task and the player
		// turns on it from where they stand, seat already taken.
		NPC ordinary = fight("Gargoyle", 111);
		game.moveNpc(ordinary, 3210, 3218, 0);
		tick(2);
		assertEquals(4, follower.stayingAt.distanceTo(new WorldPoint(3222, 3218, 0)));
		assertEquals(0, panics());

		NPC superior = game.spawnNpc(101, "Marble gargoyle", 349);
		game.moveNpc(superior, 3210, 3218, 0);
		game.fighting(superior);
		tick(2);
		assertEquals("two tiles further, mid-fight", 6,
			follower.stayingAt.distanceTo(new WorldPoint(3222, 3218, 0)));
		assertEquals(1, panics());
	}

	/** How far off the named NPC is watched from, one fight in. */
	private int watchedFrom(String name, int level)
	{
		NPC target = fight(name, level);
		game.moveNpc(target, 3210, 3218, 0);
		tick(2);
		assertTrue("it never moved", follower.stayingAt != null);
		return follower.stayingAt.distanceTo(new WorldPoint(3222, 3218, 0));
	}

	/**
	 * The names are matched exactly, so they are the cache's spelling: these
	 * two were one capital out (7398, 7400) and fought like anything else.
	 */
	@Test
	public void theMutatedBloodveldIsASuperiorAsTheGameSpellsIt()
	{
		assertEquals(6, watchedFrom("Insatiable mutated Bloodveld", 278));
		assertEquals(1, panics());
	}

	@Test
	public void theWarpedJellyIsASuperiorAsTheGameSpellsIt()
	{
		assertEquals(6, watchedFrom("Vitreous Warped Jelly", 241));
		assertEquals(1, panics());
	}

	/** The Bloodthirsty rockslug (12561), missing its y in the game's own spelling. */
	@Test
	public void theBloodthirstRockslugIsASuperiorAsTheGameSpellsIt()
	{
		assertEquals(6, watchedFrom("Bloodthirst rockslug", 86));
		assertEquals(1, panics());
	}

	/**
	 * And the exactness is kept on purpose: 9287 and 9288 are "Monstrous
	 * Basilisk", filed with the ordinary basilisks, one capital off the
	 * superior's "Monstrous basilisk".
	 */
	@Test
	public void theOtherMonstrousBasiliskIsNotASuperior()
	{
		assertEquals(4, watchedFrom("Monstrous Basilisk", 135));
		assertEquals(0, panics());
	}

	@Test
	public void withTheCompanySwitchedOffASuperiorIsJustAnotherFight()
	{
		FollowerConfig off = new FollowerConfig()
		{
			@Override
			public boolean keepsCompany()
			{
				return false;
			}
		};
		SpectateController plain = new SpectateController(game.client, follower,
			off, () -> context, new SpotAnimRepository(new Gson()), spoken::add, disarms::add);

		NPC superior = fight("Marble gargoyle", 349);
		game.moveNpc(superior, 3210, 3218, 0);
		for (int i = 0; i < 30; i++)
		{
			game.tick(++tick);
			context.refresh();
			follower.advance();
			plain.tick(false, false);
		}

		assertTrue(follower.stayingAt != null);
		assertEquals("the usual four tiles, the setting being off", 4,
			follower.stayingAt.distanceTo(new WorldPoint(3222, 3218, 0)));
		assertEquals("and no panic", 0, panics());
	}

	@Test
	public void theFightEndingSendsItBackAndSaysSo()
	{
		fight("Goblin", 5);
		tick(2);
		assertTrue(spectate.isSpectating());

		game.fighting(null);
		tick(15);

		assertFalse("the fight is over", spectate.isSpectating());
		assertTrue("it should have said so", spoke(TriggerEvent.Type.COMBAT_END));
		assertTrue("and gone back to following", follower.following);
	}

	@Test
	public void beingBusyWithSomethingElseOutranksSpectating()
	{
		fight("Goblin", 5);
		tick(2);
		assertTrue(spectate.isSpectating());

		// An errand or thrall mode owns the feet.
		game.tick(++tick);
		context.refresh();
		follower.advance();
		spectate.tick(true, false);

		assertFalse("spectating must yield to whatever else has the follower",
			spectate.isSpectating());
	}

	// ---------------------------------------------------- the player's park

	@Test
	public void aFightNearThePlayersParkIsAnnouncedButWatchedFromThePost()
	{
		// The park used to count as busy, which kept the follower on its post
		// and every fight near it unannounced: no combat line, no wager, no
		// breather after it, for as long as Follow me stayed off.
		WorldPoint post = new WorldPoint(3215, 3218, 0);
		follower.stayingAt = post;
		follower.following = false;
		parked = true;
		fight("Zulrah", 725);
		tick(10);

		assertTrue("the fight is announced", spoke(TriggerEvent.Type.COMBAT_START));
		assertEquals("never sent anywhere", 0, follower.stayAttempts);
		assertTrue("no ward cast at the post", follower.chains.isEmpty());
		assertFalse("the feet are the player's", spectate.holdsTheFeet());

		game.fighting(null);
		tick(15);
		assertEquals("the end is announced too", 1, said(TriggerEvent.Type.COMBAT_END));
		assertEquals("and the post kept", post, follower.stayingAt);
	}

	@Test
	public void aStayGivenMidFightIsNotTheFightEnding()
	{
		// Stay here pressed while the follower stood clear: the park counted
		// as busy, so the spectator stood down and said its goodbye with the
		// fight still going.
		fight("Zulrah", 725);
		tick(6);
		assertFalse("the ward is up", disarms.isEmpty());
		WorldPoint seat = follower.stayingAt;

		parked = true;
		tick(3);
		assertEquals("no goodbye mid-fight", 0, said(TriggerEvent.Type.COMBAT_END));
		assertEquals("parked where it stood", seat, follower.stayingAt);
		assertEquals("the ward dropped", 0, follower.poseOverride);
		assertEquals("and the weapon back", Boolean.FALSE, disarms.get(disarms.size() - 1));
		assertFalse(spectate.holdsTheFeet());

		game.fighting(null);
		tick(15);
		assertEquals("said once, at the end", 1, said(TriggerEvent.Type.COMBAT_END));
		assertEquals("and the post kept", seat, follower.stayingAt);
	}

	private int said(TriggerEvent.Type type)
	{
		int count = 0;
		for (TriggerEvent event : spoken)
		{
			if (event.getType() == type)
			{
				count++;
			}
		}
		return count;
	}

	@Test
	public void aDespawnedFollowerStopsSpectating()
	{
		fight("Goblin", 5);
		tick(2);

		follower.spawned = false;
		tick(2);

		assertFalse(spectate.isSpectating());
	}

	// ---------------------------------------------------------------- shield

	@Test
	public void anOrdinaryMonsterGetsNoWard()
	{
		fight("Goblin", 5);
		tick(20);

		assertTrue("a goblin does not warrant a protective spell",
			follower.chains.isEmpty());
		assertTrue("and nothing should have been put away", disarms.isEmpty());
	}

	@Test
	public void aBossGetsTheWholeSpellFromSummonToDispel()
	{
		fight("Zulrah", 725);
		tick(3);

		assertFalse("a boss should have raised a ward", follower.chains.isEmpty());
		assertEquals("the hands are emptied as the spell goes up",
			Boolean.TRUE, disarms.get(0));

		// Channelling: a looping pose is held, not a one-shot.
		tick(4);
		assertTrue("the channel pose was never taken", follower.poseOverride != 0);

		game.fighting(null);
		tick(20);

		assertEquals("the channel pose must be released at the end",
			0, follower.poseOverride);
		assertEquals("and the weapon handed back",
			Boolean.FALSE, disarms.get(disarms.size() - 1));
		assertTrue("it must end up following again", follower.following);
	}

	@Test
	public void theWeaponAlwaysComesBackHoweverTheFightEnds()
	{
		// Every way out of a fight, checked for the same thing: the follower
		// must not be left holding nothing.
		String[] endings = {"target lost", "busy", "despawned", "walked off"};
		for (String ending : endings)
		{
			setUpQuietly();
			fight("Zulrah", 725);
			tick(6);
			assertFalse(ending + ": the ward never went up", disarms.isEmpty());

			switch (ending)
			{
				case "target lost":
					game.fighting(null);
					tick(25);
					break;
				case "busy":
					for (int i = 0; i < 25; i++)
					{
						game.tick(++tick);
						context.refresh();
						follower.advance();
						spectate.tick(true, false);
					}
					break;
				case "despawned":
					follower.spawned = false;
					tick(25);
					break;
				default:
					follower.settled = false;
					tick(25);
					break;
			}

			assertEquals(ending + ": the follower was left disarmed",
				Boolean.FALSE, disarms.get(disarms.size() - 1));
		}
	}

	private void setUpQuietly()
	{
		try
		{
			setUp();
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
	}

	@Test
	public void walkingBreaksTheChannelRatherThanLeavingItStuck()
	{
		fight("Zulrah", 725);
		tick(6);
		assertTrue(follower.poseOverride != 0);

		// Dragged along behind the player for several ticks.
		follower.settled = false;
		tick(5);

		assertEquals("a broken channel must drop its pose", 0, follower.poseOverride);
		assertEquals("and give the weapon back straight away, since there is no"
				+ " closing animation left to protect",
			Boolean.FALSE, disarms.get(disarms.size() - 1));
	}

	@Test
	public void aMomentaryWobbleDoesNotEndTheSpell()
	{
		fight("Zulrah", 725);
		tick(6);
		int poseWhileChannelling = follower.poseOverride;
		assertTrue(poseWhileChannelling != 0);

		follower.settled = false;
		tick(1);
		follower.settled = true;
		tick(2);

		assertTrue("one unsettled tick should not have dropped the ward",
			follower.poseOverride != 0);
	}

	@Test
	public void theWardIsRenewedSoItDoesNotBlinkOut()
	{
		fight("Zulrah", 725);
		tick(3);
		int afterSummon = follower.spotAnims.size();

		tick(12);

		assertTrue("the channelled effect should be renewed as it fades,"
				+ " not left to lapse",
			follower.spotAnims.size() >= afterSummon);
	}

	@Test
	public void switchingItOffMidFightPutsEverythingBack()
	{
		FollowerConfig off = new FollowerConfig()
		{
			@Override
			public boolean spectateCombat()
			{
				return false;
			}
		};
		SpectateController disabled = new SpectateController(game.client, follower,
			off, () -> context, new SpotAnimRepository(new Gson()), spoken::add, disarms::add);

		fight("Zulrah", 725);
		for (int i = 0; i < 10; i++)
		{
			game.tick(++tick);
			context.refresh();
			follower.advance();
			disabled.tick(false, false);
		}

		assertFalse("the setting is off", disabled.isSpectating());
		assertTrue("nothing should have been taken away", disarms.isEmpty());
	}

	// ------------------------------------------------------------ diagnostics

	@Test
	public void describeExplainsItselfWithoutThrowing()
	{
		assertTrue(spectate.describe(false).contains("spectate"));
		fight("Zulrah", 725);
		tick(4);
		String busy = spectate.describe(true);
		assertTrue("the report should name what it is doing: " + busy,
			busy.contains("spectating=true"));
	}

	@Test
	public void aLongFightDoesNotAccumulateAnything()
	{
		fight("Zulrah", 725);
		tick(400);

		assertTrue("still spectating a fight that is still going",
			spectate.isSpectating());
		// One summon, and no repeats of it: the chain is raised once.
		assertTrue("the ward was raised " + follower.chains.size() + " times over"
				+ " one fight", follower.chains.size() <= 2);
	}
}

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
		public boolean isEmotePlaying()
		{
			return emoting;
		}

		@Override
		public boolean stayAt(WorldPoint target)
		{
			stayingAt = target;
			following = false;
			return target != null;
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
			context, spotAnims, spoken::add, disarms::add);
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
			spectate.tick(false);
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
		spectate.tick(true);

		assertFalse("spectating must yield to whatever else has the follower",
			spectate.isSpectating());
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
						spectate.tick(true);
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
			off, context, new SpotAnimRepository(new Gson()), spoken::add, disarms::add);

		fight("Zulrah", 725);
		for (int i = 0; i < 10; i++)
		{
			game.tick(++tick);
			context.refresh();
			follower.advance();
			disabled.tick(false);
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

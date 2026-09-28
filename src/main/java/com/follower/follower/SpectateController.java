package com.follower.follower;

import com.follower.FollowerConfig;
import com.follower.appearance.SpotAnimRepository;
import com.follower.speech.TriggerContext;
import com.follower.speech.TriggerEvent;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

/**
 * Gets the follower out of the way when a fight starts, and gives it something
 * to do while it waits.
 *
 * <p>A following follower stands where a player wants to look: on the boss, on
 * the tile they are about to click, in the middle of the fight. It cannot
 * actually block anything - it is a client-side object with no collision - but
 * being visually in the way is enough of a problem during a kill. So when a
 * fight starts it walks clear and watches from a few tiles off.
 *
 * <p>Thrall mode is deliberately exempt. A possessed thrall is IN the fight by
 * definition, and sending it away would undo the whole feature.
 *
 * <p>Speech is not handled here beyond announcing the start and end of a fight.
 * Encouragement and the health and prayer warnings come from the ordinary rule
 * system, which never stopped running - the {@code combat} and {@code bossFight}
 * conditions exist so a rule can ask about a fight the same way it asks about
 * anything else.
 */
@Slf4j
public class SpectateController
{
	/**
	 * How far the follower tries to stand from the player while watching. Close
	 * enough to still read as a companion, far enough not to sit on top of the
	 * fight.
	 */
	private static final int WATCH_DISTANCE = 4;

	/**
	 * The superior slayer monsters, by name, in one place. The spectator
	 * stands {@link #SUPERIOR_EXTRA_TILES} further from one of these than
	 * from anything else, and panics once at the sight of it.
	 *
	 * <p>Matched exactly, capitals and all, and the capitals are the game's
	 * own, which it does not keep straight: the superior warped jelly (7400)
	 * is "Vitreous Warped Jelly" and a stronger copy of it (12457) "Vitreous
	 * warped Jelly", so both are here. The other way round, the superior
	 * basilisk (7395) is "Monstrous basilisk", while 9287 and 9288, "Monstrous
	 * Basilisk", are filed with the ordinary basilisks, not the superiors -
	 * which is why this stays exact. The Bloodthirsty ones are the game's
	 * category of superiors too (980), one of them spelled "Bloodthirst
	 * rockslug" by the game itself. runTargetAudit checks every name here
	 * against the cache.
	 */
	public static final java.util.Set<String> SUPERIOR_NAMES = new java.util.HashSet<>(
		java.util.Arrays.asList(
			"Crushing hand", "Chasm Crawler", "Screaming banshee",
			"Screaming twisted banshee", "Giant rockslug", "Cockathrice",
			"Flaming pyrelord", "Monstrous basilisk", "Malevolent Mage",
			"Insatiable Bloodveld", "Insatiable mutated Bloodveld", "Vitreous Jelly",
			"Vitreous Warped Jelly", "Vitreous warped Jelly", "Cave abomination", "Abhorrent spectre",
			"Repugnant spectre", "Basilisk Sentinel", "Choke devil", "King kurask",
			"Marble gargoyle", "Nechryarch", "Greater abyssal demon", "Night beast",
			"Nuclear smoke devil", "Shadow Wyrm", "Guardian Drake", "Colossal Hydra",
			"Infernal pyrelord", "Spiked Turoth", "Mutated Terrorbird", "Mutated Tortoise",
			"Dreadborn Araxyte", "Ancient Custodian", "Dire gryphon", "Vitreous Chilled Jelly",
			"Elder aquanite", "Magma strykewyrm", "Blood-starved venator",
			"Bloodthirsty hand", "Bloodthirsty Crawler", "Bloodthirsty banshee",
			"Bloodthirsty twisted banshee", "Bloodthirst rockslug", "Bloodthirsty Cockathrice",
			"Bloodthirsty pyrelord", "Bloodthirsty Infernal pyrelord", "Bloodthirsty basilisk",
			"Bloodthirsty Basilisk Sentinel", "Bloodthirsty Mage", "Bloodthirsty Bloodveld",
			"Bloodthirsty mutated Bloodveld", "Bloodthirsty Jelly", "Bloodthirsty Warped Jelly",
			"Bloodthirsty abomination", "Bloodthirsty spectre", "Bloodthirsty Repugnant spectre",
			"Bloodthirsty choke devil", "Bloodthirsty kurask", "Bloodthirsty smoke devil",
			"Bloodthirsty gargoyle", "Bloodthirsty Night beast", "Bloodthirsty abyssal demon",
			"Bloodthirsty Nechryarch", "Bloodthirsty Turoth", "Bloodthirsty Shadow Wyrm",
			"Bloodthirsty Drake", "Bloodthirsty Hydra"));

	static final int SUPERIOR_EXTRA_TILES = 2;

	/** EMOTE_PANIC, proven on the follower by the mirror rules. */
	static final int PANIC = 2105;

	/**
	 * A superior is being fought and the panic has not played: it waits for
	 * the follower to be settled at its seat, since movement would cut it off.
	 */
	private boolean panicDue;
	private boolean panicked;

	/**
	 * Whether the seat was chosen for a superior, two tiles further out: the
	 * one that spawns mid-task is attacked from the ordinary seat, which is
	 * then the wrong distance.
	 */
	private boolean seatedForSuperior;

	/**
	 * The superior is part of the skilling company, so the Skilling master
	 * switch turns it off along with the rest: with it off a superior is
	 * watched like any other fight.
	 */
	private boolean isSuperior(Actor target)
	{
		return config.keepsCompany() && target != null && target.getName() != null
			&& SUPERIOR_NAMES.contains(target.getName());
	}

	/**
	 * How far the follower may drift from its watching spot before it is moved
	 * again. Without this it would re-path every tick as the player shuffles.
	 *
	 * <p>Generous on purpose. Every re-seat ends the channelled spell, so a
	 * tight leash meant a boss that moves the player around - Scurrius was the
	 * case that showed it - reduced the whole sequence to repeated casting and
	 * sitting. A watcher standing well back is the point, so it only follows
	 * once genuinely left behind.
	 */
	private static final int RESEAT_DISTANCE = 12;

	/**
	 * How often the channelled graphic is renewed, in game ticks.
	 *
	 * <p>Two ticks is 1.2 seconds against a 1.34-second effect, so each renewal
	 * starts just before the last has faded and the ward looks continuous
	 * rather than blinking once in a while.
	 */
	private static final int SHIELD_MAINTAIN_TICKS = 2;

	/**
	 * How long the follower stays put after dispelling, so the closing
	 * animation is not cut off by it walking back. Movement always wins over an
	 * emote, so releasing on the same tick would kill the flourish outright.
	 */
	private static final int DISPEL_HOLD_TICKS = 4;

	private final Client client;
	private final FollowerEntity follower;
	private final FollowerConfig config;

	/**
	 * Read fresh on every use, never held. The engine REPLACES its context on
	 * every reset - each login screen, the memory eraser, a plugin toggle -
	 * and a controller holding the original object was reading a context
	 * that had stopped being refreshed: its isInCombat was false forever, so
	 * spectating never started again after the first relog. Cheers still
	 * fired (the rules read the live context), which is what made the boss
	 * fights look detected while the follower stood glued to the player.
	 */
	private final java.util.function.Supplier<TriggerContext> contextSource;
	private final SpotAnimRepository spotAnims;
	private final Consumer<TriggerEvent> speech;

	private TriggerContext context()
	{
		return contextSource.get();
	}

	/** Puts the follower's weapon and shield away while it channels. */
	private final java.util.function.Consumer<Boolean> disarm;

	/** Whether the follower is currently standing aside for a fight. */
	private boolean spectating;

	/**
	 * Whether the player has parked the follower themselves. A fight is then
	 * announced at both ends as ever, but watched from the post: nothing here
	 * moves it, casts at it or lets it go, and the feet are not held.
	 */
	private boolean parked;

	private WorldPoint watchTile;

	/**
	 * The shield is a three-part spell rather than a repeating effect: it is
	 * summoned once, channelled for as long as the follower stands and fights
	 * are still going, and finished off deliberately at the end.
	 */
	private enum Shield
	{
		NONE, SUMMONING, CHANNELLING
	}

	private Shield shield = Shield.NONE;
	private int ticksSinceShield;

	/** Counts down after a dispel; the follower walks back when it reaches zero. */
	private int releaseTicks;

	/**
	 * How long the follower must have stood still before it starts the spell.
	 *
	 * <p>Observed in a Scurrius fight: a summon began and was abandoned a second
	 * later because the player moved and the follower had to re-seat, which
	 * reads as a cast that goes nowhere. Waiting a couple of ticks costs
	 * nothing and means a spell is only started when there is a reasonable
	 * chance of holding it.
	 */
	private static final int SETTLE_DWELL_TICKS = 3;

	private int ticksSettled;

	/**
	 * Consecutive unsettled ticks before the channel is considered broken. A
	 * real walk lasts many ticks; a momentary wobble should not end a spell.
	 */
	private static final int UNSETTLED_DROP_TICKS = 3;

	private int ticksUnsettled;

	public SpectateController(Client client, FollowerEntity follower, FollowerConfig config,
		java.util.function.Supplier<TriggerContext> contextSource, SpotAnimRepository spotAnims,
		Consumer<TriggerEvent> speech, java.util.function.Consumer<Boolean> disarm)
	{
		this.client = client;
		this.follower = follower;
		this.config = config;
		this.contextSource = contextSource;
		this.spotAnims = spotAnims;
		this.speech = speech;
		this.disarm = disarm;
	}

	public boolean isSpectating()
	{
		return spectating;
	}

	/**
	 * Whether the spectate logic owns the follower's feet right now, which is
	 * a longer window than {@link #isSpectating()}.
	 *
	 * <p>stop() clears the flag at once but leaves the follower standing where
	 * it is for a few ticks, so the closing cast is not cut off by walking
	 * away. Through that gap the follower is parked and no longer counted as
	 * spectating, which is indistinguishable from a Stay the player asked for
	 * unless somebody asks this instead. A fight watched from the player's
	 * park holds nothing: the feet are theirs.
	 */
	public boolean holdsTheFeet()
	{
		return spectateOwnsTheFeet(spectating && !parked, releaseTicks);
	}

	/** The above as a decision, so it can be tested without a controller. */
	public static boolean spectateOwnsTheFeet(boolean spectating, int releaseTicks)
	{
		return spectating || releaseTicks > 0;
	}

	/**
	 * Why the follower is or is not standing clear right now, for
	 * {@code ::follower spectate}. Every gate in {@link #tick} reads back here,
	 * so a feature that silently does nothing can be diagnosed in one command
	 * rather than by guessing which condition failed.
	 */
	public String describe(boolean busy)
	{
		return "spectate: setting=" + config.spectateCombat()
			+ " busy=" + busy
			+ " parked=" + parked
			+ " spawned=" + follower.isSpawned()
			+ " inCombat=" + context().isInCombat()
			+ " target='" + context().getCombatTarget() + "' level=" + context().getCombatTargetLevel()
			+ " thieving=" + context().isThieving()
			+ " thievingSession=" + context().isInThievingSession()
			+ " boss=" + context().isBossFight()
			+ " | spectating=" + spectating
			+ " watchTile=" + watchTile
			+ " settled=" + follower.isSettled()
			+ " shield=" + shield
			+ " releaseTicks=" + releaseTicks;
	}

	/**
	 * @param busy true when something else owns the follower's feet - thrall
	 * mode or an errand - in which case spectating stays out of the way
	 * @param parked true when the player has parked the follower
	 */
	public void tick(boolean busy, boolean parked)
	{
		this.parked = parked;
		// A dispel in progress owns the feet until its animation has played.
		// The weapon comes back at the same moment, for the same reason: the
		// rebuild that returns it would otherwise cut the closing cast short.
		if (releaseTicks > 0 && --releaseTicks == 0)
		{
			disarm.accept(false);
			follower.resumeFollowing();
		}

		if (!config.spectateCombat() || busy || !follower.isSpawned())
		{
			if (spectating)
			{
				stop();
			}
			return;
		}

		// Thieving is not a fight and is not spectated. Standing clear was tried
		// and is the wrong shape: it holds one spot facing the action, which
		// reads as an audience. A player picking pockets wants the follower
		// gone, not watching - so the thieving session suppresses this outright
		// and the wandering takes over instead.
		if (!context().isInCombat() || context().isInThievingSession())
		{
			if (spectating)
			{
				stop();
			}
			return;
		}

		if (!spectating)
		{
			start(true);
		}
		else if (parked)
		{
			letTheSeatGo();
		}
		else
		{
			holdPosition();
		}
		if (parked)
		{
			// Watched from the post: no panic and no ward there.
			return;
		}

		// The panic, once stood still at the seat: an emote begun while the
		// feet are still moving is cancelled by the very next movement frame.
		if (panicDue && follower.isStaying() && follower.isSettled())
		{
			panicDue = false;
			panicked = true;
			follower.playAnimation(PANIC);
			log.debug("A superior; panicking, once");
		}

		maybeCastShield();
	}

	/**
	 * @param announce whether this is a fight, which is worth saying something
	 * about. Thieving is not: it steps clear and keeps quiet.
	 */
	private void start(boolean announce)
	{
		spectating = true;
		announced = announce;
		releaseTicks = 0;
		unseatedTicks = 0;
		if (parked)
		{
			log.debug("Parked by the player; watching from the post");
		}
		else if (!moveClear())
		{
			// Nowhere to go is not a reason to keep announcing a fight; the
			// follower simply carries on following.
			log.debug("Nowhere to stand clear; staying put");
		}
		if (!announce)
		{
			log.debug("Standing clear while thieving");
			return;
		}
		log.debug("Spectating '{}' level {} boss={} shieldEnabled={}",
			context().getCombatTarget(), context().getCombatTargetLevel(),
			context().isBossFight(), config.spectateShield());
		speech.accept(TriggerEvent.combat(TriggerEvent.Type.COMBAT_START,
			context().getCombatTarget()));
	}

	/**
	 * Whether the current stand-clear was announced as a fight, so that only
	 * the ones that said hello say goodbye.
	 */
	private boolean announced;

	private void stop()
	{
		spectating = false;
		watchTile = null;
		unseatedTicks = 0;
		panicDue = false;
		panicked = false;

		// Finishing the spell is its own moment: drop the channelled pose, play
		// the closing animation where it stands, and only walk back once it has
		// had time to play - movement would cut it off otherwise.
		boolean wasCasting = shield != Shield.NONE;
		dropChannel("fight over");
		// Stand, then cast: it gets to its feet before releasing the spell, so
		// the sequence closes the way it opened.
		if (wasCasting && castChain(
			new int[]{config.spectateShieldChannelEnd(), config.spectateShieldEndAnimation()},
			new int[]{config.spectateShieldEndGraphic(), config.spectateShieldEndGraphic()}))
		{
			// The weapon stays away until the closing cast has played: taking
			// it back rebuilds the model, and a rebuild would cut the animation
			// off mid-flourish.
			releaseTicks = DISPEL_HOLD_TICKS;
		}
		else
		{
			if (wasCasting)
			{
				disarm.accept(false);
			}
			// A park the player gave is theirs to end, not the fight's.
			if (!parked)
			{
				follower.resumeFollowing();
			}
		}

		// Only a fight says goodbye. A thieving stand-clear ended is the
		// follower simply coming back, which needs no remark.
		if (announced)
		{
			speech.accept(TriggerEvent.combat(TriggerEvent.Type.COMBAT_END,
				context().getCombatTarget()));
		}
		announced = false;
	}

	/**
	 * The player parked the follower mid-fight, where it stands, and the post
	 * is theirs now: the seat is forgotten without being let go, and a ward
	 * held on it is dropped, weapon back, since nothing is cast at the post.
	 */
	private void letTheSeatGo()
	{
		if (shield != Shield.NONE)
		{
			dropChannel("parked");
			disarm.accept(false);
		}
		watchTile = null;
		panicDue = false;
	}

	/** Re-seats the follower only once it has been left well behind. */
	private void holdPosition()
	{
		Player local = client.getLocalPlayer();
		if (local == null || local.getWorldLocation() == null)
		{
			return;
		}
		// Unseated - the first moveClear found nowhere to stand. That used to
		// be final: this method returned on a null watchTile, so a follower
		// that failed once in a cramped arena stood glued to the player for
		// the whole fight. Play report: a boss fight where it never stepped
		// clear. Fights move; tiles open up; try again every few ticks.
		if (watchTile == null)
		{
			if (++unseatedTicks % RESEAT_RETRY_TICKS == 0)
			{
				moveClear();
			}
			return;
		}
		// The seat is HELD through FollowerEntity's stayTile, and plenty of
		// hands can release that without telling this controller: the thrall
		// exit's stayHere/resumeFollowing/snapBeside chain, the leash snap,
		// scene recovery. Measuring watchTile against the PLAYER missed all
		// of them - the tile was still nearby, so the spectator stood glued
		// to the player casting wards while never stepping away. Play report:
		// "out of thrall mode they don't step away, but still do the magic
		// barrier animations." If the follower no longer holds ANY stay, it
		// was un-seated, whoever did it: take the seat again.
		if (!follower.isStaying()
			|| watchTile.distanceTo(local.getWorldLocation()) > RESEAT_DISTANCE
			|| watchTile.getPlane() != local.getWorldLocation().getPlane()
			|| isSuperior(local.getInteracting()) != seatedForSuperior)
		{
			moveClear();
		}
	}

	/** Ticks spent spectating without a tile to stand on. */
	private int unseatedTicks;

	/** How often an unseated spectator looks again for somewhere to stand. */
	private static final int RESEAT_RETRY_TICKS = 5;

	/**
	 * Puts the follower behind the player, away from whatever is being fought.
	 *
	 * <p>The direction is the one that matters: standing "near the player" is no
	 * good if that happens to be between them and the boss. The vector from the
	 * target to the player, continued past the player, is the one place
	 * guaranteed not to be in the line of the fight. Tiles are tried outward
	 * from there, so a wall or a corner degrades to the next best angle rather
	 * than giving up.
	 */
	private boolean moveClear()
	{
		Player local = client.getLocalPlayer();
		if (local == null || local.getWorldLocation() == null)
		{
			return false;
		}
		WorldPoint player = local.getWorldLocation();

		Actor target = local.getInteracting();
		WorldPoint threat = target == null ? null : target.getWorldLocation();

		// A superior is worth two more tiles of distance, and a panic once
		// the follower has stopped where it is going.
		boolean superior = isSuperior(target);
		seatedForSuperior = superior;
		int watch = WATCH_DISTANCE + (superior ? SUPERIOR_EXTRA_TILES : 0);
		if (superior && !panicked)
		{
			panicDue = true;
		}

		int dx;
		int dy;
		if (threat == null || threat.equals(player))
		{
			// Nothing to stand clear OF; just get out of arm's reach.
			dx = 0;
			dy = -1;
		}
		else
		{
			dx = Integer.signum(player.getX() - threat.getX());
			dy = Integer.signum(player.getY() - threat.getY());
			if (dx == 0 && dy == 0)
			{
				dy = -1;
			}
		}

		// Straight back first, then fanned out to either side, then the sides
		// themselves - a corner or a wall behind the player should not end with
		// the follower standing in the fight.
		int[][] offsets = {
			{dx, dy},
			{dx == 0 ? 1 : dx, dy == 0 ? 1 : dy},
			{dx == 0 ? -1 : dx, dy == 0 ? -1 : dy},
			{dy, dx},
			{-dy, -dx},
		};

		for (int[] direction : offsets)
		{
			for (int distance = watch; distance >= 2; distance--)
			{
				WorldPoint candidate = new WorldPoint(
					player.getX() + direction[0] * distance,
					player.getY() + direction[1] * distance,
					player.getPlane());
				if (threat != null && candidate.distanceTo(threat) < 3)
				{
					continue;
				}
				if (follower.stayAt(candidate))
				{
					watchTile = candidate;
					return true;
				}
			}
		}

		// The away-from-the-threat fan is walled - a boss arena is exactly the
		// place where "behind the player" is stone. In a fight the goal is not
		// underfoot, and any tile at a distance that is not next to the threat
		// serves it. The full ring, nearest first, before giving up.
		for (int distance = 2; distance <= watch; distance++)
		{
			for (int ox = -distance; ox <= distance; ox++)
			{
				for (int oy = -distance; oy <= distance; oy++)
				{
					if (Math.max(Math.abs(ox), Math.abs(oy)) != distance)
					{
						continue;
					}
					WorldPoint candidate = new WorldPoint(
						player.getX() + ox, player.getY() + oy, player.getPlane());
					if (threat != null && candidate.distanceTo(threat) < 3)
					{
						continue;
					}
					if (follower.stayAt(candidate))
					{
						watchTile = candidate;
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * The protective-shield flourish: a cast animation with a graphic on top,
	 * played on the follower itself while it watches a boss fight.
	 *
	 * <p>Both ids are settings rather than constants. Unlike a weapon's swing,
	 * which the game answers definitively, "what a protective shield looks
	 * like" has no correct answer to measure - so the honest thing is to pick a
	 * reasonable pair and let it be changed, with {@code ::follower gfx} to
	 * audition alternatives.
	 */
	private void maybeCastShield()
	{
		if (!config.spectateShield() || !context().isBossFight())
		{
			return;
		}
		// An emote started while the feet are still moving is cancelled by the
		// very next movement frame - movement always wins over an emote, which
		// is what stops a dancing follower gliding along behind you. The first
		// cast used to fire on the same tick the follower set off to stand
		// clear, so it was killed before a single particle appeared. Nothing is
		// cast until it has actually stopped at its watching spot.
		//
		// The same rule ends a channel: a follower that has to move cannot go
		// on channelling, so the pose is dropped and the spell begins again
		// once it has settled somewhere new.
		// Settled is not seated. A follower glued to the player - un-seated
		// by the thrall exit, a failed moveClear in a cramped arena, a leash
		// snap - is perfectly still and used to pass this gate, which is how
		// the ward got cast at the player's elbow instead of from the seat.
		// No stay held means no seat, and no seat means no spell.
		if (!follower.isSettled() || !follower.isStaying())
		{
			ticksSettled = 0;
			// One unsettled tick is not a journey. The fine position can leave
			// the tile centre for a moment without the follower going
			// anywhere, and dropping on that alone made the spell restart -
			// standing up out of the kneel and casting it again - while it was
			// supposed to be holding still.
			if (++ticksUnsettled >= UNSETTLED_DROP_TICKS)
			{
				// Broken by movement, so there is no closing animation to
				// protect: the weapon can come straight back.
				boolean wasCasting = shield != Shield.NONE;
				dropChannel("moved");
				if (wasCasting)
				{
					disarm.accept(false);
				}
			}
			return;
		}
		ticksUnsettled = 0;
		ticksSettled++;

		switch (shield)
		{
			case NONE:
				// Only begin once it has genuinely stopped, or the spell is
				// abandoned a second later when it has to re-seat.
				if (ticksSettled < SETTLE_DWELL_TICKS)
				{
					break;
				}
				// Cast, then sit, as one chain: the follower raises the ward
				// and settles into holding it without a gap between the two.
				// Nothing to play is not a reason to skip the channel.
				// The particle rides every stage, not just the cast: the ward is
				// meant to be visibly up from the moment it is raised.
				// Weapon and shield go away as the spell is raised, while the
				// cast covers the rebuild, rather than when the kneel starts.
				disarm.accept(true);
				if (castChain(
					new int[]{config.spectateShieldAnimation(), config.spectateShieldChannelStart()},
					new int[]{config.spectateShieldGraphic(), config.spectateShieldGraphic()}))
				{
					shield = Shield.SUMMONING;
				}
				else
				{
					startChannel();
				}
				break;

			case SUMMONING:
				// The summon is a one-shot; the channelled pose takes over the
				// moment it has played out, so the two read as one spell.
				if (!follower.isEmotePlaying())
				{
					startChannel();
				}
				break;

			case CHANNELLING:
				// A looping pose shows no particles of its own, so the graphic
				// is renewed on its own timer to keep the ward alive.
				if (++ticksSinceShield >= SHIELD_MAINTAIN_TICKS)
				{
					ticksSinceShield = 0;
					SpotAnimRepository.Entry renew =
						spotAnims.get(config.spectateShieldChannelGraphic());
					if (renew != null)
					{
						follower.playSpotAnim(renew);
					}
				}
				break;

			default:
				break;
		}
	}

	/**
	 * Starts the channelled pose. A pose override loops until it is cleared,
	 * unlike an emote, which is what "until it moves or the fight ends" needs.
	 */
	private void startChannel()
	{
		int channel = config.spectateShieldChannelAnimation();
		if (channel > 0)
		{
			follower.setPoseOverride(channel);
		}
		shield = Shield.CHANNELLING;
		// Renew on the very next tick rather than after a full interval, so the
		// ward does not go dark between the summon and the first top-up.
		ticksSinceShield = SHIELD_MAINTAIN_TICKS;
		log.debug("Shield: channelling, pose {}", channel);
	}

	/** Ends the channel and hands the follower's pose back to normal. */
	private void dropChannel(String reason)
	{
		if (shield != Shield.NONE)
		{
			log.debug("Shield: dropping from {} ({})", shield, reason);
		}
		if (shield == Shield.CHANNELLING)
		{
			follower.setPoseOverride(0);
		}
		shield = Shield.NONE;
	}

	/**
	 * Plays one stage of the shield.
	 *
	 * @return false when the stage has nothing configured, so a caller can tell
	 * a skipped stage from a played one
	 */
	/**
	 * Plays several stages back to back, each with its own optional graphic.
	 *
	 * <p>A zero animation is dropped from the chain rather than played, but its
	 * graphic is not: a stage may legitimately be particles alone.
	 *
	 * @return false only when the whole chain had nothing to show
	 */
	private boolean castChain(int[] animations, int[] graphicIds)
	{
		java.util.List<Integer> ids = new java.util.ArrayList<>();
		java.util.List<SpotAnimRepository.Entry> graphics = new java.util.ArrayList<>();
		boolean played = false;

		for (int i = 0; i < animations.length; i++)
		{
			SpotAnimRepository.Entry fx = spotAnims.get(graphicIds[i]);
			if (animations[i] > 0)
			{
				ids.add(animations[i]);
				graphics.add(fx);
			}
			else if (fx != null)
			{
				follower.playSpotAnim(fx);
				played = true;
			}
		}

		if (!ids.isEmpty())
		{
			int[] chain = new int[ids.size()];
			for (int i = 0; i < chain.length; i++)
			{
				chain[i] = ids.get(i);
			}
			// Kept, at debug: this is what showed the sit and stand were
			// running all along and the ids were the problem. ::follower
			// spectate reports the same state on demand without it.
			log.debug("Shield chain: animations {} settled={} emote={}",
				ids, follower.isSettled(), follower.isEmotePlaying());
			follower.playAnimations(chain,
				graphics.toArray(new SpotAnimRepository.Entry[0]));
			played = true;
		}
		return played;
	}

}

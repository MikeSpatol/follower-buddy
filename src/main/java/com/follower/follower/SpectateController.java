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
	 * How far the follower may drift from its watching spot before it is moved
	 * again. Without this it would re-path every tick as the player shuffles.
	 */
	private static final int RESEAT_DISTANCE = 7;

	/** How often the shield is topped up while it is being held, in game ticks. */
	private static final int SHIELD_MAINTAIN_TICKS = 50;

	/**
	 * How long the follower stays put after dispelling, so the closing
	 * animation is not cut off by it walking back. Movement always wins over an
	 * emote, so releasing on the same tick would kill the flourish outright.
	 */
	private static final int DISPEL_HOLD_TICKS = 4;

	private final Client client;
	private final FollowerEntity follower;
	private final FollowerConfig config;
	private final TriggerContext context;
	private final SpotAnimRepository spotAnims;
	private final Consumer<TriggerEvent> speech;

	/** Whether the follower is currently standing aside for a fight. */
	private boolean spectating;

	private WorldPoint watchTile;

	/** Whether the shield has been summoned and is being held. */
	private boolean shieldUp;
	private int ticksSinceShield;

	/** Counts down after a dispel; the follower walks back when it reaches zero. */
	private int releaseTicks;

	public SpectateController(Client client, FollowerEntity follower, FollowerConfig config,
		TriggerContext context, SpotAnimRepository spotAnims, Consumer<TriggerEvent> speech)
	{
		this.client = client;
		this.follower = follower;
		this.config = config;
		this.context = context;
		this.spotAnims = spotAnims;
		this.speech = speech;
	}

	public boolean isSpectating()
	{
		return spectating;
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
			+ " spawned=" + follower.isSpawned()
			+ " inCombat=" + context.isInCombat()
			+ " target='" + context.getCombatTarget() + "' level=" + context.getCombatTargetLevel()
			+ " boss=" + context.isBossFight()
			+ " | spectating=" + spectating
			+ " watchTile=" + watchTile
			+ " settled=" + follower.isSettled()
			+ " shieldUp=" + shieldUp
			+ " releaseTicks=" + releaseTicks;
	}

	/**
	 * @param busy true when something else owns the follower's feet - thrall
	 * mode or an errand - in which case spectating stays out of the way
	 */
	public void tick(boolean busy)
	{
		// A dispel in progress owns the feet until its animation has played.
		if (releaseTicks > 0 && --releaseTicks == 0)
		{
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

		if (!context.isInCombat())
		{
			if (spectating)
			{
				stop();
			}
			return;
		}

		if (!spectating)
		{
			start();
		}
		else
		{
			holdPosition();
		}

		maybeCastShield();
	}

	private void start()
	{
		spectating = true;
		releaseTicks = 0;
		if (!moveClear())
		{
			// Nowhere to go is not a reason to keep announcing a fight; the
			// follower simply carries on following.
			log.debug("Nowhere to stand clear of the fight; staying put");
		}
		speech.accept(TriggerEvent.combat(TriggerEvent.Type.COMBAT_START,
			context.getCombatTarget()));
	}

	private void stop()
	{
		spectating = false;
		watchTile = null;

		// Dropping the shield is its own moment: play it out where it stands,
		// then walk back a few ticks later so the animation is not cut short.
		if (shieldUp && cast(config.spectateShieldEndAnimation(),
			config.spectateShieldEndGraphic()))
		{
			releaseTicks = DISPEL_HOLD_TICKS;
		}
		else
		{
			follower.resumeFollowing();
		}
		shieldUp = false;

		speech.accept(TriggerEvent.combat(TriggerEvent.Type.COMBAT_END,
			context.getCombatTarget()));
	}

	/** Re-seats the follower only once it has been left well behind. */
	private void holdPosition()
	{
		Player local = client.getLocalPlayer();
		if (local == null || local.getWorldLocation() == null || watchTile == null)
		{
			return;
		}
		if (watchTile.distanceTo(local.getWorldLocation()) > RESEAT_DISTANCE
			|| watchTile.getPlane() != local.getWorldLocation().getPlane())
		{
			moveClear();
		}
	}

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
			for (int distance = WATCH_DISTANCE; distance >= 2; distance--)
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
		if (!config.spectateShield() || !context.isBossFight())
		{
			return;
		}
		// An emote started while the feet are still moving is cancelled by the
		// very next movement frame - movement always wins over an emote, which
		// is what stops a dancing follower gliding along behind you. The first
		// cast used to fire on the same tick the follower set off to stand
		// clear, so it was killed before a single particle appeared. Nothing is
		// cast until it has actually stopped at its watching spot.
		if (!follower.isSettled())
		{
			return;
		}

		if (!shieldUp)
		{
			if (cast(config.spectateShieldAnimation(), config.spectateShieldGraphic()))
			{
				shieldUp = true;
				ticksSinceShield = 0;
			}
			return;
		}

		// Holding it: its own stage on its own timer, so a long fight keeps the
		// shield looking alive without repeating the summon.
		if (++ticksSinceShield >= SHIELD_MAINTAIN_TICKS)
		{
			ticksSinceShield = 0;
			cast(config.spectateShieldHoldAnimation(), config.spectateShieldHoldGraphic());
		}
	}

	/**
	 * Plays one stage of the shield.
	 *
	 * @return false when the stage has nothing configured, so a caller can tell
	 * a skipped stage from a played one
	 */
	private boolean cast(int animation, int graphicId)
	{
		SpotAnimRepository.Entry graphic = spotAnims.get(graphicId);
		if (animation <= 0 && graphic == null)
		{
			return false;
		}
		log.debug("Shield stage: animation {} graphic {}", animation, graphicId);

		// playAnimations refuses an empty animation list, so a graphic on its
		// own goes straight to the spotanim path rather than being dropped.
		if (animation <= 0)
		{
			follower.playSpotAnim(graphic);
			return true;
		}
		follower.playAnimations(new int[]{animation},
			graphic == null ? null : new SpotAnimRepository.Entry[]{graphic});
		return true;
	}
}

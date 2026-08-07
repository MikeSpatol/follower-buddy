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

	/** Minimum gap between shield casts, in game ticks. */
	private static final int SHIELD_INTERVAL_TICKS = 50;

	private final Client client;
	private final FollowerEntity follower;
	private final FollowerConfig config;
	private final TriggerContext context;
	private final SpotAnimRepository spotAnims;
	private final Consumer<TriggerEvent> speech;

	/** Whether the follower is currently standing aside for a fight. */
	private boolean spectating;

	private WorldPoint watchTile;
	private int ticksSinceShield = SHIELD_INTERVAL_TICKS;

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
	 * @param busy true when something else owns the follower's feet - thrall
	 * mode or an errand - in which case spectating stays out of the way
	 */
	public void tick(boolean busy)
	{
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
		ticksSinceShield = SHIELD_INTERVAL_TICKS;
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
		follower.resumeFollowing();
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
		if (++ticksSinceShield < SHIELD_INTERVAL_TICKS)
		{
			return;
		}
		ticksSinceShield = 0;

		int animation = config.spectateShieldAnimation();
		SpotAnimRepository.Entry graphic = spotAnims.get(config.spectateShieldGraphic());
		if (animation <= 0 && graphic == null)
		{
			return;
		}
		follower.playAnimations(
			animation > 0 ? new int[]{animation} : new int[0],
			graphic == null ? null : new SpotAnimRepository.Entry[]{graphic});
	}
}

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
	private final TriggerContext context;
	private final SpotAnimRepository spotAnims;
	private final Consumer<TriggerEvent> speech;

	/** Whether the follower is currently standing aside for a fight. */
	private boolean spectating;

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
			+ " shield=" + shield
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
		log.info("Spectating '{}' level {} boss={} shieldEnabled={}",
			context.getCombatTarget(), context.getCombatTargetLevel(),
			context.isBossFight(), config.spectateShield());
		speech.accept(TriggerEvent.combat(TriggerEvent.Type.COMBAT_START,
			context.getCombatTarget()));
	}

	private void stop()
	{
		spectating = false;
		watchTile = null;

		// Finishing the spell is its own moment: drop the channelled pose, play
		// the closing animation where it stands, and only walk back once it has
		// had time to play - movement would cut it off otherwise.
		boolean wasCasting = shield != Shield.NONE;
		dropChannel();
		// Stand, then cast: it gets to its feet before releasing the spell, so
		// the sequence closes the way it opened.
		if (wasCasting && castChain(
			new int[]{config.spectateShieldChannelEnd(), config.spectateShieldEndAnimation()},
			new int[]{config.spectateShieldEndGraphic(), config.spectateShieldEndGraphic()}))
		{
			releaseTicks = DISPEL_HOLD_TICKS;
		}
		else
		{
			follower.resumeFollowing();
		}

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
		//
		// The same rule ends a channel: a follower that has to move cannot go
		// on channelling, so the pose is dropped and the spell begins again
		// once it has settled somewhere new.
		if (!follower.isSettled())
		{
			ticksSettled = 0;
			dropChannel();
			return;
		}
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
		log.info("Shield: channelling, pose {}", channel);
	}

	/** Ends the channel and hands the follower's pose back to normal. */
	private void dropChannel()
	{
		if (shield != Shield.NONE)
		{
			log.info("Shield: dropping from {}", shield);
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
			// INFO while the sequence is being tuned in game: a handful of lines
			// per fight, and the only way to see which stage actually ran.
			log.info("Shield chain: animations {} settled={} emote={}",
				ids, follower.isSettled(), follower.isEmotePlaying());
			follower.playAnimations(chain,
				graphics.toArray(new SpotAnimRepository.Entry[0]));
			played = true;
		}
		return played;
	}

}

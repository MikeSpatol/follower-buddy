package com.follower.follower;

import com.follower.FollowerConfig;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;

/**
 * The moments: the small occasions outside any work session that the
 * company marks with its body rather than a line. A level, the pet, the
 * player's legs giving out, a stranger turning up to talk to them.
 *
 * <p>One at a time, because each takes the feet. A seat held by
 * {@link SkillingController} is not a reason to miss one - most levels land
 * mid-session, and every skilling pet does - so the seat is excused for the
 * moment and retaken after. Anything else that owns the feet (thrall work,
 * the spectator, an errand under way) beats a moment, which then happens in
 * place or not at all - and so does the seat once it has the follower sitting
 * in for the long haul, since walking off would leave the rest pose to sit it
 * back down wherever it arrived. Silent throughout; the level-up and pet-drop
 * lines are the rules' and stay theirs.
 */
@Slf4j
public class Moments
{
	/** EMOTE_CLAP and EMOTE_BOW, both proven on the follower by the rules. */
	static final int CLAP = 865;
	static final int BOW = 858;

	/** The formal era: the first two days bow rather than clap. */
	static final int NEW_DAYS = 2;

	/** Every third level walks over to do it; the others clap in place. */
	static final int WALK_EVERY = 3;
	static final int MASTERY = 99;

	/** The boss send-off's own chain: cheer, dance, jump for joy. */
	static final int[] CELEBRATION = {862, 866, 2109};

	/** Ticks the feet are kept for a clap, and for the chain (7.4 seconds). */
	private static final int BEAT_TICKS = 3;
	private static final int CELEBRATION_TICKS = 13;

	/** The pet-drop message, in the smallest certain fragment of it. */
	static final String PET_MESSAGE = "funny feeling";

	/** The pet is looked for this long after the message. */
	static final int PET_HUNT_TICKS = 20;

	/** The bend, proven on the follower by the cat errand. */
	static final int BEND = 827;
	private static final int BEND_TICKS = 3;

	/** Ten minutes of keeping close after the pet. */
	static final int PET_CLOSE_TICKS = 1000;
	private static final int PET_REACH_TILES = 15;

	/** Second wind: run going off under this much energy, once per stretch. */
	static final int LOW_ENERGY = 10;
	static final int WIND_COOLDOWN_TICKS = 1000;
	private static final int LOOK_BACK_TICKS = 2;

	/** The peer, held while a stranger has the player's ear. */
	static final int PEER = KeepingCompany.PEER;

	/** A spawn this fresh that turns to the player is one that came for them. */
	static final int STRANGER_WINDOW_TICKS = 5;

	/** Which moment has the feet, if any. */
	private enum Doing
	{
		NOTHING, LEVEL, PET, WIND, STRANGER
	}

	private final Client client;
	private final FollowerEntity follower;
	private final FollowerConfig config;
	private final SkillingController skilling;

	/** Thrall work, the spectator or an errand: they all beat a moment. */
	private final BooleanSupplier feetTaken;
	private final BooleanSupplier inCombat;
	private final IntSupplier daysKnown;

	private Doing doing = Doing.NOTHING;
	private int arriveAt;
	private int leaveAt;

	private int levels;
	private int[] beat;

	private int petHuntUntil = -1;
	private NPC lastFollower;
	private int lastFollowerTick = -1000;
	private NPC pet;
	private int closeUntil;

	private boolean wasRunning;
	private boolean windArmed;
	private int windCooldownUntil;

	/** Level-less NPCs that just spawned, and when, until they show their business. */
	private final Map<NPC, Integer> newcomers = new LinkedHashMap<>();
	private NPC stranger;

	public Moments(Client client, FollowerEntity follower, FollowerConfig config,
		SkillingController skilling, BooleanSupplier feetTaken, BooleanSupplier inCombat,
		IntSupplier daysKnown)
	{
		this.client = client;
		this.follower = follower;
		this.config = config;
		this.skilling = skilling;
		this.feetTaken = feetTaken;
		this.inCombat = inCombat;
		this.daysKnown = daysKnown;
	}

	/**
	 * Whether a moment owns the follower's feet. Registered beside the seat
	 * and the errands, so none of this reads as a Stay the player asked for.
	 */
	public boolean holdsTheFeet()
	{
		return doing != Doing.NOTHING;
	}

	/** Whether the wander should stay in close - the ten minutes after the pet. */
	public boolean keepsClose()
	{
		return client.getTickCount() < closeUntil;
	}

	// --------------------------------------------------------------- level up

	/**
	 * A level. A clap, or a bow while the follower is still being polite
	 * about it; every third one is worth walking over for, and the
	 * ninety-ninth gets the send-off a boss does. Facing the player either
	 * way, and up off the sit first when the seat has it sitting.
	 */
	public void levelUp(int level)
	{
		if (!config.keepsCompany())
		{
			return;
		}
		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation();
		if (here == null)
		{
			return;
		}
		levels++;
		int[] play = level >= MASTERY ? CELEBRATION.clone()
			: new int[]{daysKnown.getAsInt() <= NEW_DAYS ? BOW : CLAP};
		int now = client.getTickCount();
		if (levels % WALK_EVERY == 0 && free() && follower.moveToFront(local))
		{
			skilling.setExcused(true);
			doing = Doing.LEVEL;
			beat = play;
			arriveAt = now + walkTicks(here);
			leaveAt = 0;
			log.debug("Level {}: walking over", level);
			return;
		}
		skilling.gesture(play, here);
	}

	// -------------------------------------------------------------------- pet

	/**
	 * A game message. The one the moments read is the pet drop's: the new
	 * pet is whichever follower-flagged NPC turns up within twenty ticks of
	 * it - or turned up a tick or two before, since the two arrive together.
	 */
	public void noteChatMessage(String message, ChatMessageType type)
	{
		if (!config.keepsCompany() || message == null
			|| (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM))
		{
			return;
		}
		String plain = message.replaceAll("<[^>]*>", "").toLowerCase(Locale.ROOT);
		if (!plain.contains(PET_MESSAGE))
		{
			return;
		}
		int now = client.getTickCount();
		if (lastFollower != null && now - lastFollowerTick <= 2)
		{
			goToThePet(lastFollower);
		}
		else
		{
			petHuntUntil = now + PET_HUNT_TICKS;
		}
	}

	/**
	 * An NPC appeared. A follower-flagged one is the pet, if one is being
	 * looked for; one with no combat level is a newcomer to watch, in case
	 * it came for the player.
	 */
	public void npcSpawned(NPC npc)
	{
		if (!config.keepsCompany() || npc == null)
		{
			return;
		}
		int now = client.getTickCount();
		NPCComposition composition = npc.getComposition();
		if (composition != null && composition.isFollower())
		{
			if (now <= petHuntUntil)
			{
				goToThePet(npc);
			}
			else
			{
				lastFollower = npc;
				lastFollowerTick = now;
			}
			return;
		}
		if (npc.getCombatLevel() == 0)
		{
			newcomers.put(npc, now);
		}
	}

	/** An NPC went. The stranger leaving is the end of the peer; so is the pet's. */
	public void npcDespawned(NPC npc)
	{
		if (npc == null)
		{
			return;
		}
		newcomers.remove(npc);
		if (npc == lastFollower)
		{
			lastFollower = null;
		}
		if ((doing == Doing.STRANGER && npc == stranger) || (doing == Doing.PET && npc == pet))
		{
			letGo(true);
		}
	}

	/**
	 * Over to the pet: a tile beside it, a bend to look, and back - and the
	 * wander keeps in close for ten minutes after, whether or not the walk
	 * could be taken.
	 */
	private void goToThePet(NPC npc)
	{
		int now = client.getTickCount();
		petHuntUntil = -1;
		lastFollower = null;
		closeUntil = now + PET_CLOSE_TICKS;
		WorldPoint at = npc.getWorldLocation();
		WorldPoint from = follower.getWorldLocation();
		if (at == null || from == null || !free() || at.getPlane() != from.getPlane()
			|| at.distanceTo(from) > PET_REACH_TILES)
		{
			return;
		}
		List<WorldPoint> beside = SkillingController.ring(at, at, 1, at.getPlane());
		beside.sort((a, b) -> Integer.compare(a.distanceTo(from), b.distanceTo(from)));
		skilling.setExcused(true);
		for (WorldPoint candidate : beside)
		{
			if (follower.stayAt(candidate))
			{
				doing = Doing.PET;
				pet = npc;
				follower.setStayFaceTile(at);
				arriveAt = now + from.distanceTo(candidate) + 2;
				leaveAt = 0;
				log.debug("A pet at {}; going to see", at);
				return;
			}
		}
		skilling.setExcused(false);
	}

	// ------------------------------------------------------------------ tick

	/** Call once per game tick, on the client thread, after the seat's own tick. */
	public void tick()
	{
		int now = client.getTickCount();
		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation();
		if (!config.keepsCompany() || here == null)
		{
			letGo(true);
			newcomers.clear();
			petHuntUntil = -1;
			return;
		}
		watchTheWind(now, here, local);
		watchForStrangers(now, local);
		switch (doing)
		{
			case LEVEL:
			case WIND:
				if (interrupted())
				{
					letGo(false);
				}
				else if (arriveAt > 0 && now >= arriveAt)
				{
					arriveAt = 0;
					if (doing == Doing.LEVEL)
					{
						follower.playAnimations(beat);
						leaveAt = now + (beat.length > 1 ? CELEBRATION_TICKS : BEAT_TICKS);
					}
					else
					{
						// Already facing them, from the front: a second of it.
						leaveAt = now + LOOK_BACK_TICKS;
					}
				}
				else if (leaveAt > 0 && now >= leaveAt)
				{
					letGo(true);
				}
				break;
			case PET:
				if (interrupted() || !follower.isStaying())
				{
					letGo(false);
				}
				else if (arriveAt > 0 && now >= arriveAt)
				{
					arriveAt = 0;
					follower.playAnimation(BEND);
					leaveAt = now + BEND_TICKS;
				}
				else if (leaveAt > 0 && now >= leaveAt)
				{
					letGo(true);
				}
				break;
			case STRANGER:
				if (interrupted() || !follower.isStaying())
				{
					letGo(false);
				}
				else
				{
					// It may wander as it talks; the peer follows it.
					WorldPoint at = stranger.getWorldLocation();
					if (at != null)
					{
						follower.setStayFaceTile(at);
					}
				}
				break;
			default:
				break;
		}
	}

	/**
	 * Second wind: run going off with the energy under ten percent. The
	 * follower goes ahead, looks back at them for a second, and falls in
	 * behind again. Once per low stretch - re-armed when the energy is
	 * back over the line - and not twice in ten minutes.
	 */
	private void watchTheWind(int now, WorldPoint here, Player local)
	{
		boolean running = client.getVarpValue(VarPlayerID.OPTION_RUN) == 1;
		int energy = client.getEnergy() / 100;
		boolean edge = wasRunning && !running;
		wasRunning = running;
		if (energy >= LOW_ENERGY)
		{
			windArmed = true;
			return;
		}
		if (!edge || !windArmed || now < windCooldownUntil)
		{
			return;
		}
		windArmed = false;
		windCooldownUntil = now + WIND_COOLDOWN_TICKS;
		if (free() && follower.moveToFront(local))
		{
			skilling.setExcused(true);
			doing = Doing.WIND;
			arriveAt = now + walkTicks(here);
			leaveAt = 0;
			log.debug("Out of breath; going ahead to look back");
		}
	}

	/**
	 * Who are you: a newcomer with no combat level that turns to the player
	 * within a few ticks of appearing came to talk to them. The follower
	 * faces it, takes one step off, and peers at it until it goes.
	 */
	private void watchForStrangers(int now, Player local)
	{
		Iterator<Map.Entry<NPC, Integer>> it = newcomers.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<NPC, Integer> entry = it.next();
			if (now - entry.getValue() > STRANGER_WINDOW_TICKS)
			{
				it.remove();
			}
			else if (entry.getKey().getInteracting() == local)
			{
				NPC npc = entry.getKey();
				newcomers.clear();
				whoAreYou(npc);
				return;
			}
		}
	}

	private void whoAreYou(NPC npc)
	{
		WorldPoint at = npc.getWorldLocation();
		WorldPoint from = follower.getWorldLocation();
		if (at == null || from == null || !free() || at.getPlane() != from.getPlane())
		{
			return;
		}
		int dx = Integer.signum(from.getX() - at.getX());
		int dy = Integer.signum(from.getY() - at.getY());
		if (dx == 0 && dy == 0)
		{
			dy = -1;
		}
		WorldPoint step = new WorldPoint(from.getX() + dx, from.getY() + dy, from.getPlane());
		skilling.setExcused(true);
		if (!follower.stayAt(step))
		{
			follower.stayHere();
		}
		if (!follower.isStaying())
		{
			skilling.setExcused(false);
			return;
		}
		doing = Doing.STRANGER;
		stranger = npc;
		follower.setStayFaceTile(at);
		follower.setPoseOverride(PEER);
		log.debug("A stranger, {}, for the player", npc.getName());
	}

	// ------------------------------------------------------------------- ends

	/** Logout, hop, shutdown: the feet let go of, nothing said. */
	public void reset()
	{
		letGo(false);
		newcomers.clear();
		petHuntUntil = -1;
		lastFollower = null;
		wasRunning = false;
		windArmed = false;
		closeUntil = 0;
	}

	/**
	 * Whether nothing has the feet, ours or anybody's, and the follower is
	 * there - and not sitting in at the seat, which no moment gets it up
	 * from: a level is clapped from the sit (the gesture stands it first),
	 * the others pass.
	 */
	private boolean free()
	{
		return doing == Doing.NOTHING && !feetTaken.getAsBoolean() && !inCombat.getAsBoolean()
			&& follower.isSpawned() && !skilling.isSitting();
	}

	/** Somebody else took the feet mid-moment, or the follower went. */
	private boolean interrupted()
	{
		return feetTaken.getAsBoolean() || inCombat.getAsBoolean() || !follower.isSpawned();
	}

	/**
	 * The moment is over, however it ended.
	 *
	 * @param release whether to hand the feet back, when they are still ours;
	 * not when somebody else has just taken them
	 */
	private void letGo(boolean release)
	{
		Doing was = doing;
		doing = Doing.NOTHING;
		beat = null;
		pet = null;
		stranger = null;
		arriveAt = 0;
		leaveAt = 0;
		if (was == Doing.STRANGER)
		{
			follower.setPoseOverride(0);
		}
		if (was != Doing.NOTHING)
		{
			if (release && follower.isStaying() && !feetTaken.getAsBoolean())
			{
				follower.resumeFollowing();
			}
			skilling.setExcused(false);
		}
	}

	/** Ticks for the follower to reach a tile by the player, and a beat to settle. */
	private int walkTicks(WorldPoint to)
	{
		WorldPoint from = follower.getWorldLocation();
		return (from == null ? 3 : from.distanceTo(to)) + 2;
	}
}

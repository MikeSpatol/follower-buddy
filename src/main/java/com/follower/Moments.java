package com.follower;

import java.util.*;
import java.util.List;
import java.util.Map;
import java.util.function.*;
import net.runelite.api.*;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;
import com.follower.Gestures.Gesture;
import static com.follower.Moments.Doing.*;

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
 *
 * <p>Every gesture goes through the {@link Gestures} ledger, so no body
 * beat repeats within its own cooldown whatever the rules are doing, and
 * the ones that are meant to be rare (the walk over, the notes) stay rare.
 */
class Moments implements Noticing.Looker
{
	/** EMOTE_CLAP and EMOTE_BOW, both proven on the follower by the rules. */
	static final int CLAP = 865,
		BOW = 858;

	/** The boss send-off's own chain: cheer, dance, jump for joy. */
	static final int[] CELEBRATION = {862, 866, 2109};

	/** The pet is looked for this long after the message. */
	static final int PET_HUNT_TICKS = 20;

	/** The bend, proven on the follower by the cat errand. */
	static final int BEND = 827;

	/** EMOTE_YES: the look up from the notes. EMOTE_WAVE: the farewell. */
	static final int NOD = KeepingCompany.NOD,
		WAVE = 863;

	/** Ticks the notes stay out for a write-up, in place and on the spot. */
	static final int NOTES_TICKS = 5,
		NOTES_ON_THE_SPOT_TICKS = 7,
		LOOK_UP_MS = 1800;

	/** How much of an occasion something is. */
	enum Tier
	{
		/** A glance at it. */
		SMALL,
		/** The notes out, from where it stands. */
		MEDIUM,
		/** Walked over to and written up on the spot, then a look up and a nod. */
		BIG
	}

	/** The tier a drop of this value earns, or null for nothing at all. */
	static Tier tierFor(long value)
	{
		// BIG_DROP: The rarity ladder for a drop, in gp. The research point is that a
		// response only counts as one when it is proportionate to the news: a
		// routine drop gets a glance, a good one gets the scroll out, a great
		// one is walked over to and written up on the spot. The same numbers as
		// the loot rules use, so the words and the body agree about what is big.
		if (value >= 1_000_000 /* BIG_DROP */)
			return Tier.BIG;
		if (value >= 100_000 /* NICE_DROP */)
			return Tier.MEDIUM;
		return value >= 10_000 /* SMALL_DROP */ ? Tier.SMALL : null;
	}

	/** Ten minutes of keeping close after the pet. */
	static final int PET_CLOSE_TICKS = 1000;

	static final int WIND_COOLDOWN_TICKS = 1000;

	/** The peer at a stranger who has the player's ear, then a watch on them. */
	static final int PEER = KeepingCompany.PEER;

	/** A spawn this fresh that turns to the player is one that came for them. */
	static final int STRANGER_WINDOW_TICKS = 5;

	/** Which moment has the feet, if any. */
	enum Doing
	{
		NOTHING, LEVEL, PET, WIND, STRANGER,
		/** Gone to look at something: a drop, a nest, a signpost. */
		LOOK
	}

	final Client client;
	final FollowerEntity follower;
	final SkillingController skilling;

	/** Thrall work, the spectator or an errand: they all beat a moment. */
	final BooleanSupplier feetTaken;
	/**
	 * A park the player gave. It beats every moment that walks or plants
	 * the follower, as the above do; the wave at the logout tab moves
	 * nothing, and is waved from the post.
	 */
	final BooleanSupplier parked,
		inCombat;
	final IntSupplier daysKnown;
	/** The scroll and the pose, for a write-up; null in a harness without hands. */
	final PropFlourish flourish;
	final Gestures gestures;

	boolean lookWrites;
	int lookHoldTicks,
		lookUpAt;

	Doing doing = NOTHING;
	int arriveAt,
		leaveAt;

	int levels;
	int[] beat;

	int petHuntUntil = -1;
	NPC lastFollower;
	int lastFollowerTick = -1000;
	NPC pet;
	int closeUntil;

	boolean wasRunning,
		windArmed;
	int windCooldownUntil;

	/** Level-less NPCs that just spawned, and when, until they show their business. */
	final Map<NPC, Integer> newcomers = new LinkedHashMap<>();
	NPC stranger;

	/**
	 * @param flourish the scroll-and-pose flourish for a write-up, or null
	 * @param grateful whether the follower owes the player one just now,
	 * which halves every gesture's cooldown
	 * @param parked whether the player has parked the follower
	 */
	Moments(Client client, FollowerEntity follower,
		SkillingController skilling, BooleanSupplier feetTaken, BooleanSupplier inCombat,
		IntSupplier daysKnown, PropFlourish flourish, BooleanSupplier grateful,
		BooleanSupplier parked)
	{
		this.client = client;
		this.follower = follower;
		this.skilling = skilling;
		this.feetTaken = feetTaken;
		this.parked = parked;
		this.inCombat = inCombat;
		this.daysKnown = daysKnown;
		this.flourish = flourish;
		this.gestures = new Gestures(client::getTickCount, grateful);
	}

	/**
	 * Whether a moment owns the follower's feet. Registered beside the seat
	 * and the errands, so none of this reads as a Stay the player asked for.
	 */
	boolean holdsTheFeet()
	{
		return doing != NOTHING;
	}

	/** Whether the wander should stay in close - the ten minutes after the pet. */
	boolean keepsClose()
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
	void levelUp(int level)
	{
		var local = client.getLocalPlayer();
		var here = local == null ? null : local.getWorldLocation();
		if (here == null)
			return;
		levels++;
		int[] play = level >= 99 /* MASTERY */ ? CELEBRATION.clone()
			// NEW_DAYS: The formal era: the first two days bow rather than clap.
			: new int[]{daysKnown.getAsInt() <= 2 /* NEW_DAYS */ ? BOW : CLAP};
		int now = client.getTickCount();
		if (follower.isDoingAgility())
		{
			// An Agility level lands as the player finishes an obstacle, with
			// the follower still on it, and the crossing would cancel the clap
			// on its first frame: owed, and clapped once the feet are free.
			owed = play;
			owedUntil = now + OWED_TICKS;
			return;
		}
		// WALK_EVERY: Every third level walks over to do it; the others clap in place.
		if (levels % 3 /* WALK_EVERY */ == 0 && free() && follower.moveToFront(local))
		{
			clap(play, here);
			return;
		}
		// In place, but a level gained on the move (an Agility course) had its
		// clap cancelled on the first frame: moving drops an emote (QA, 15
		// Oct). So on the move it stops where it is, finishes the step, and
		// claps on the next tick, planted for it as the walk over is.
		if (follower.isMoving() && free())
		{
			clap(play, null);
			return;
		}
		skilling.gesture(play, here);
	}

	/**
	 * The level's own moment: the seat excused, and the clap played on
	 * arrival.
	 *
	 * @param walkTo the player's tile to walk over to, or null to stop where
	 * it stands and clap on the next tick
	 */
	void clap(int[] play, WorldPoint walkTo)
	{
		skilling.setExcused(true);
		if (walkTo == null)
			follower.stayHere();
		doing = LEVEL;
		beat = play;
		arriveAt = client.getTickCount() + (walkTo == null ? 1 : walkTicks(walkTo));
		leaveAt = 0;
	}

	/** A clap owed from a level gained on an obstacle, and the tick it is let go. */
	int[] owed;
	int owedUntil;

	/** About ten seconds: past that, the moment has gone. */
	static final int OWED_TICKS = 17;

	/** The owed clap, as a level gained on the move is clapped: stopped, then clapped. */
	void clapOwed(int now)
	{
		int[] play = owed;
		owed = null;
		if (now <= owedUntil)
			clap(play, null);
	}

	// -------------------------------------------------------------------- pet

	/**
	 * A game message. The one the moments read is the pet drop's: the new
	 * pet is whichever follower-flagged NPC turns up within twenty ticks of
	 * it - or turned up a tick or two before, since the two arrive together.
	 */
	void noteChatMessage(String message, ChatMessageType type)
	{
		if (message == null
			|| (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM))
			return;
		String plain = message.replaceAll("<[^>]*>", "").toLowerCase(Locale.ROOT);
		// PET_MESSAGE: The pet-drop message, in the smallest certain fragment of it.
		if (!plain.contains("funny feeling" /* PET_MESSAGE */))
			return;
		int now = client.getTickCount();
		if (lastFollower != null && now - lastFollowerTick <= 2)
			goToThePet(lastFollower);
		else
			petHuntUntil = now + PET_HUNT_TICKS;
	}

	/**
	 * An NPC appeared. A follower-flagged one is the pet, if one is being
	 * looked for; one with no combat level is a newcomer to watch, in case
	 * it came for the player.
	 */
	void npcSpawned(NPC npc)
	{
		if (npc == null)
			return;
		int now = client.getTickCount();
		var composition = npc.getComposition();
		if (composition != null && composition.isFollower())
		{
			if (now <= petHuntUntil)
				goToThePet(npc);
			else
			{
				lastFollower = npc;
				lastFollowerTick = now;
			}
			return;
		}
		if (npc.getCombatLevel() == 0)
			newcomers.put(npc, now);
	}

	/** An NPC went. The stranger leaving is the end of the peer; so is the pet's. */
	void npcDespawned(NPC npc)
	{
		if (npc == null)
			return;
		newcomers.remove(npc);
		if (npc == lastFollower)
			lastFollower = null;
		if ((doing == STRANGER && npc == stranger) || (doing == PET && npc == pet))
			letGo(true);
	}

	/**
	 * Over to the pet: a tile beside it, a bend to look, and back - and the
	 * wander keeps in close for ten minutes after, whether or not the walk
	 * could be taken.
	 */
	void goToThePet(NPC npc)
	{
		int now = client.getTickCount();
		petHuntUntil = -1;
		lastFollower = null;
		closeUntil = now + PET_CLOSE_TICKS;
		WorldPoint at = npc.getWorldLocation(),
			from = follower.getWorldLocation();
		if (at == null || from == null || !free() || at.getPlane() != from.getPlane()
			|| at.distanceTo(from) > 15 /* PET_REACH_TILES */)
			return;
		if (goBeside(at, 1, PET))
			pet = npc;
	}

	/**
	 * Over to the nearest tile so many steps off a thing that the follower
	 * will stand on, faced at it, as the moment given.
	 *
	 * @return whether it set off; false leaves the seat as it was
	 */
	boolean goBeside(WorldPoint at, int steps, Doing what)
	{
		var from = follower.getWorldLocation();
		var spots = SkillingController.ring(at, at, steps, at.getPlane());
		spots.sort(SkillingController.stepsFrom(from));
		skilling.setExcused(true);
		for (var spot : spots)
		{
			if (follower.stayAt(spot))
			{
				doing = what;
				follower.setStayFaceTile(at);
				arriveAt = client.getTickCount() + from.distanceTo(spot) + 2;
				leaveAt = 0;
				return true;
			}
		}
		skilling.setExcused(false);
		return false;
	}

	// ------------------------------------------------------------ the news

	/**
	 * Something worth a reaction: a drop, a first-ever item. The tier says
	 * how much of one. Big news with a tile is walked over to and written
	 * up on the spot, then a look up at the player and a nod - the scribe's
	 * clap; in character, a great thing is written down, not cheered.
	 * Medium news gets the notes out where it stands, facing the thing;
	 * small news a glance. Each through the ledger, so the notes cannot come
	 * out for every drop in a good hour: once they are spent the tier below
	 * stands in.
	 *
	 * @param at where the thing is, or null for something in the bag, which
	 * is reacted to facing the player
	 * @return what the body did, or null for nothing
	 */
	String noteworthy(Tier tier, WorldPoint at)
	{
		if (tier == null)
			return null;
		var here = FollowerEntity.playerTile(client);
		if (here == null || !follower.isSpawned())
			return null;
		var from = follower.getWorldLocation();
		boolean reachable = at != null && from != null && at.getPlane() == from.getPlane()
			&& at.distanceTo(from) <= 12 /* LOOT_REACH_TILES */;
		var facing = at == null ? here : at;

		if (tier == Tier.BIG && reachable && free()
			&& gestures.may(Gesture.WALK_OVER) && gestures.may(Gesture.NOTES)
			&& goAndLook(at, 1, true, NOTES_ON_THE_SPOT_TICKS))
		{
			gestures.spent(Gesture.WALK_OVER);
			gestures.spent(Gesture.NOTES);
			return "the walk over to write it up";
		}
		String wrote = tier != Tier.SMALL ? writeUpInPlace(facing) : null;
		if (wrote != null)
			return wrote;
		if (gestures.may(Gesture.GLANCE))
		{
			gestures.spent(Gesture.GLANCE);
			follower.glanceAt(facing, 2400 /* GLANCE_MS */);
			return "a glance";
		}
		return null;
	}

	/**
	 * The notes out where it stands, facing the thing, a beat of writing,
	 * then the look up at the player with a nod. With the hands full (a
	 * borrowed tool) or the follower sat down for the long haul the scroll
	 * cannot come out, and it is the look and the nod on their own.
	 *
	 * @return what the body did, or null when the notes are not due
	 */
	String writeUpInPlace(WorldPoint facing)
	{
		if (!gestures.may(Gesture.NOTES))
			return null;
		gestures.spent(Gesture.NOTES);
		int now = client.getTickCount();
		boolean canWrite = flourish != null && doing == NOTHING && !skilling.isSitting()
			&& !feetTakenOrParked() && !inCombat.getAsBoolean()
			&& follower.isSettled();
		if (canWrite && flourish.start(ErrandController.QUILL_PROP, ErrandController.SCROLL_PROP,
			ErrandController.WRITE_SCROLL_POSE, NOTES_TICKS))
		{
			follower.glanceAt(facing, NOTES_TICKS * 600);
			lookUpAt = now + NOTES_TICKS - 1;
			return "the notes out where it stands";
		}
		skilling.gesture(new int[]{NOD}, FollowerEntity.playerTile(client));
		return "a nod";
	}

	/**
	 * The player opened the logout tab. The last thing on screen before a
	 * session ends is the thing that gets remembered, so it is a turn to
	 * face them and a wave - from the seat, the seat's way. Not twice in
	 * five minutes, for the player who keeps the tab open.
	 *
	 * @return whether it waved
	 */
	boolean farewell()
	{
		var here = FollowerEntity.playerTile(client);
		if (here == null || !follower.isSpawned() || feetTaken.getAsBoolean()
			|| inCombat.getAsBoolean()
			|| !gestures.may(Gesture.WAVE)
			)
			return false;
		gestures.spent(Gesture.WAVE);
		skilling.gesture(new int[]{WAVE}, here);
		return true;
	}

	/**
	 * Goes to look at something: a tile so many steps from it, the nearest
	 * the follower can reach, faced at it, held for a while, and the feet
	 * handed back. With {@code writes} the notes come out on arrival and
	 * the hold ends with a look up at the player and a nod.
	 *
	 * @return whether it set off; false leaves nothing changed
	 */
	@Override
	public boolean goAndLook(WorldPoint at, int steps, boolean writes, int holdTicks)
	{
		var from = follower.getWorldLocation();
		if (at == null || from == null || !free() || at.getPlane() != from.getPlane()
			|| !goBeside(at, Math.max(1, steps), LOOK))
			return false;
		lookWrites = writes;
		lookHoldTicks = Math.max(1, holdTicks);
		lookUpAt = 0;
		return true;
	}

	/** Whether the follower is off looking at something, or writing it up. */
	@Override
	public boolean isLooking()
	{
		return doing == LOOK;
	}

	// ------------------------------------------------------------------ tick

	/** Call once per game tick, on the client thread, after the seat's own tick. */
	void tick()
	{
		int now = client.getTickCount();
		var local = client.getLocalPlayer();
		var here = local == null ? null : local.getWorldLocation();
		if (here == null)
		{
			letGo(true);
			newcomers.clear();
			petHuntUntil = -1;
			return;
		}
		watchTheWind(now, here, local);
		watchForStrangers(now, local);
		if (owed != null && (now > owedUntil || free()))
			clapOwed(now);
		// The look up from an in-place write-up: the notes are still out,
		// and the nod goes to the player. Not once something has taken the
		// feet since: a scene being filmed puts the notes away as it starts,
		// and the nod would come into the take.
		if (lookUpAt > 0 && now >= lookUpAt && doing == NOTHING)
		{
			lookUpAt = 0;
			if (!feetTaken.getAsBoolean())
			{
				follower.glanceAt(here, LOOK_UP_MS);
				follower.playAnimation(NOD);
			}
		}
		// Every moment but the level and the wind is let go of when its stay
		// is; then the arrival, the look up from the notes, and the leaving.
		if (doing == NOTHING)
			return;
		if (interrupted() || (doing != LEVEL && doing != WIND && !follower.isStaying()))
			letGo(false);
		else if (doing == STRANGER)
		{
			// It may wander as it talks; the peer follows it.
			var at = stranger.getWorldLocation();
			if (at != null)
				follower.setStayFaceTile(at);
		}
		else if (arriveAt > 0 && now >= arriveAt)
		{
			arriveAt = 0;
			arrived(now);
		}
		else if (doing == LOOK && lookUpAt > 0 && now >= lookUpAt)
		{
			lookUpAt = 0;
			follower.glanceAt(here, LOOK_UP_MS);
			follower.playAnimation(NOD);
		}
		else if (leaveAt > 0 && now >= leaveAt)
			letGo(true);
	}

	/** There: what each moment does on arrival, and how long it holds. */
	void arrived(int now)
	{
		switch (doing)
		{
			case LEVEL:
				follower.playAnimations(beat);
				// BEAT_TICKS: Ticks the feet are kept for a clap, and for the chain (7.4 seconds).
				leaveAt = now + (beat.length > 1 ? 13 /* CELEBRATION_TICKS */ : 3 /* BEAT_TICKS */);
				break;
			case WIND:
				// Already facing them, from the front: a second of it.
				leaveAt = now + 2 /* LOOK_BACK_TICKS */;
				break;
			case PET:
				follower.playAnimation(BEND);
				leaveAt = now + 3 /* BEND_TICKS */;
				break;
			default:
				// A look: the notes out on arrival when it writes.
				if (lookWrites && flourish != null && flourish.start(
					ErrandController.QUILL_PROP, ErrandController.SCROLL_PROP,
					ErrandController.WRITE_SCROLL_POSE, lookHoldTicks))
					// The flourish planted it where it stands, which is the
					// spot; the look up comes a beat before the notes go away.
					lookUpAt = now + lookHoldTicks - 1;
				leaveAt = now + lookHoldTicks;
				break;
		}
	}

	/**
	 * Second wind: run going off with the energy under ten percent. The
	 * follower goes ahead, looks back at them for a second, and falls in
	 * behind again. Once per low stretch - re-armed when the energy is
	 * back over the line - and not twice in ten minutes.
	 */
	void watchTheWind(int now, WorldPoint here, Player local)
	{
		boolean running = client.getVarpValue(VarPlayerID.OPTION_RUN) == 1;
		int energy = client.getEnergy() / 100;
		boolean edge = wasRunning && !running;
		wasRunning = running;
		// LOW_ENERGY: Second wind: run going off under this much energy, once per stretch.
		if (energy >= 10 /* LOW_ENERGY */)
		{
			windArmed = true;
			return;
		}
		if (!edge || !windArmed || now < windCooldownUntil)
			return;
		windArmed = false;
		windCooldownUntil = now + WIND_COOLDOWN_TICKS;
		if (free() && follower.moveToFront(local))
		{
			skilling.setExcused(true);
			doing = WIND;
			arriveAt = now + walkTicks(here);
			leaveAt = 0;
		}
	}

	/**
	 * Who are you: a newcomer with no combat level that turns to the player
	 * within a few ticks of appearing came to talk to them. The follower
	 * faces it, takes one step off, and peers at it until it goes.
	 */
	void watchForStrangers(int now, Player local)
	{
		var it = newcomers.entrySet().iterator();
		while (it.hasNext())
		{
			var entry = it.next();
			if (now - entry.getValue() > STRANGER_WINDOW_TICKS)
				it.remove();
			else if (entry.getKey().getInteracting() == local)
			{
				var npc = entry.getKey();
				newcomers.clear();
				whoAreYou(npc);
				return;
			}
		}
	}

	void whoAreYou(NPC npc)
	{
		WorldPoint at = npc.getWorldLocation(),
			from = follower.getWorldLocation();
		if (at == null || from == null || !free() || at.getPlane() != from.getPlane())
			return;
		int dx = Integer.signum(from.getX() - at.getX()),
			dy = Integer.signum(from.getY() - at.getY());
		if (dx == 0 && dy == 0)
			dy = -1;
		var step = from.dx(dx).dy(dy);
		skilling.setExcused(true);
		if (!follower.stayAt(step))
			follower.stayHere();
		if (!follower.isStaying())
		{
			skilling.setExcused(false);
			return;
		}
		doing = STRANGER;
		stranger = npc;
		follower.setStayFaceTile(at);
		// PEER_WAIT_TICKS: How long the peer waits for the step towards the stranger to finish.
		follower.playWhenStill(PEER, 3 /* PEER_WAIT_TICKS */);
	}

	// ------------------------------------------------------------------- ends

	/** Logout, hop, shutdown: the feet let go of, nothing said. */
	void reset()
	{
		letGo(false);
		newcomers.clear();
		petHuntUntil = -1;
		lastFollower = null;
		wasRunning = false;
		windArmed = false;
		closeUntil = 0;
		lookUpAt = 0;
		gestures.reset();
	}

	/**
	 * Whether nothing has the feet, ours or anybody's, and the follower is
	 * there - and not sitting in at the seat, which no moment gets it up
	 * from: a level is clapped from the sit (the gesture stands it first),
	 * the others pass.
	 */
	boolean free()
	{
		return doing == NOTHING && !feetTakenOrParked() && !inCombat.getAsBoolean()
			&& follower.isSpawned() && !skilling.isSitting();
	}

	/** Somebody else took the feet mid-moment, or the follower went. */
	boolean interrupted()
	{
		return feetTakenOrParked() || inCombat.getAsBoolean() || !follower.isSpawned();
	}

	/** Whether the feet are not the moments' to take: somebody else has them, or the player. */
	boolean feetTakenOrParked()
	{
		return feetTaken.getAsBoolean() || parked.getAsBoolean();
	}

	/**
	 * The moment is over, however it ended.
	 *
	 * @param release whether to hand the feet back, when they are still ours;
	 * not when somebody else has just taken them
	 */
	void letGo(boolean release)
	{
		var was = doing;
		doing = NOTHING;
		beat = null;
		pet = null;
		stranger = null;
		arriveAt = 0;
		leaveAt = 0;
		if (was == LOOK)
		{
			lookUpAt = 0;
			if (flourish != null && flourish.isActive())
				flourish.abort();
		}
		if (was != NOTHING)
		{
			if (release && follower.isStaying() && !feetTakenOrParked())
				follower.resumeFollowing();
			skilling.setExcused(false);
		}
	}

	/** Ticks for the follower to reach a tile by the player, and a beat to settle. */
	int walkTicks(WorldPoint to)
	{
		var from = follower.getWorldLocation();
		return (from == null ? 3 : from.distanceTo(to)) + 2;
	}
}

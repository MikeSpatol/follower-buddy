// public:file moments
package com.follower.follower;

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
 *
// public:cut company-extras,gestures
 * <p>Every gesture goes through the {@link Gestures} ledger, so no body
 * beat repeats within its own cooldown whatever the rules are doing, and
 * the ones that are meant to be rare (the walk over, the notes) stay rare.
// public:end company-extras,gestures
 */
@Slf4j
public class Moments implements Noticing.Looker // public:cut company-extras,noticing
// public:else public class Moments
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

	/** EMOTE_YES: the look up from the notes. EMOTE_WAVE: the farewell. */
	static final int NOD = KeepingCompany.NOD; // public:cut skilling
	// public:else static final int NOD = 855;
	static final int WAVE = 863;

	/**
	 * The rarity ladder for a drop, in gp. The research point is that a
	 * response only counts as one when it is proportionate to the news: a
	 * routine drop gets a glance, a good one gets the scroll out, a great
	 * one is walked over to and written up on the spot. The same numbers as
	 * the loot rules use, so the words and the body agree about what is big.
	 */
	static final int BIG_DROP = 1_000_000;
	static final int NICE_DROP = 100_000;
	static final int SMALL_DROP = 10_000;

	/** Ticks the notes stay out for a write-up, in place and on the spot. */
	static final int NOTES_TICKS = 5;
	static final int NOTES_ON_THE_SPOT_TICKS = 7;
	private static final int GLANCE_MS = 2400;
	private static final int LOOK_UP_MS = 1800;
	private static final int LOOT_REACH_TILES = 12;

	/** How much of an occasion something is. */
	public enum Tier
	{
		/** A glance at it. */
		SMALL,
		/** The notes out, from where it stands. */
		MEDIUM,
		/** Walked over to and written up on the spot, then a look up and a nod. */
		BIG
	}

	/** The tier a drop of this value earns, or null for nothing at all. */
	public static Tier tierFor(long value)
	{
		if (value >= BIG_DROP)
		{
			return Tier.BIG;
		}
		if (value >= NICE_DROP)
		{
			return Tier.MEDIUM;
		}
		return value >= SMALL_DROP ? Tier.SMALL : null;
	}

	/** Ten minutes of keeping close after the pet. */
	static final int PET_CLOSE_TICKS = 1000;
	private static final int PET_REACH_TILES = 15;

	/** Second wind: run going off under this much energy, once per stretch. */
	static final int LOW_ENERGY = 10;
	static final int WIND_COOLDOWN_TICKS = 1000;
	private static final int LOOK_BACK_TICKS = 2;

	/** The peer at a stranger who has the player's ear, then a watch on them. */
	static final int PEER = KeepingCompany.PEER; // public:cut skilling
	// public:else static final int PEER = 6840;

	/** How long the peer waits for the step towards the stranger to finish. */
	private static final int PEER_WAIT_TICKS = 3;

	/** A spawn this fresh that turns to the player is one that came for them. */
	static final int STRANGER_WINDOW_TICKS = 5;

	/** Which moment has the feet, if any. */
	private enum Doing
	{
		NOTHING, LEVEL, PET, WIND, STRANGER,
		/** Gone to look at something: a drop, a nest, a signpost. */
		LOOK
	}

	private final Client client;
	private final FollowerEntity follower;
	private final SkillingController skilling; // public:cut skilling

	/** Thrall work, the spectator or an errand: they all beat a moment. */
	private final BooleanSupplier feetTaken;
	/**
	 * A park the player gave. It beats every moment that walks or plants
	 * the follower, as the above do; the wave at the logout tab moves
	 * nothing, and is waved from the post.
	 */
	private final BooleanSupplier parked;
	private final BooleanSupplier inCombat;
	private final IntSupplier daysKnown;
	/** The scroll and the pose, for a write-up; null in a harness without hands. */
	private final PropFlourish flourish;
	private final Gestures gestures; // public:cut company-extras,gestures

	private boolean lookWrites;
	private int lookHoldTicks;
	private int lookUpAt;

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

	/**
	 * @param flourish the scroll-and-pose flourish for a write-up, or null
	// public:cut company-extras,gestures
	 * @param grateful whether the follower owes the player one just now,
	 * which halves every gesture's cooldown
	// public:end company-extras,gestures
	 * @param parked whether the player has parked the follower
	 */
	public Moments(Client client, FollowerEntity follower,
		SkillingController skilling, BooleanSupplier feetTaken, BooleanSupplier inCombat, // public:cut skilling
		// public:else BooleanSupplier feetTaken, BooleanSupplier inCombat,
		IntSupplier daysKnown, PropFlourish flourish, BooleanSupplier grateful, // public:cut company-extras,gestures
		// public:else IntSupplier daysKnown, PropFlourish flourish,
		BooleanSupplier parked)
	{
		this.client = client;
		this.follower = follower;
		this.skilling = skilling; // public:cut skilling
		this.feetTaken = feetTaken;
		this.parked = parked;
		this.inCombat = inCombat;
		this.daysKnown = daysKnown;
		this.flourish = flourish;
		this.gestures = new Gestures(client::getTickCount, grateful); // public:cut company-extras,gestures
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
		// public:cut agility,agility-all
		if (follower.isDoingAgility())
		{
			// An Agility level lands as the player finishes an obstacle, with
			// the follower still on it, and the crossing would cancel the clap
			// on its first frame: owed, and clapped once the feet are free.
			owed = play;
			owedUntil = now + OWED_TICKS;
			return;
		}
		// public:end agility,agility-all
		if (levels % WALK_EVERY == 0 && free() && follower.moveToFront(local))
		{
			clap(play, here);
			log.debug("Level {}: walking over", level); // public:cut logs
			return;
		}
		// In place, but a level gained on the move (an Agility course) had its
		// clap cancelled on the first frame: moving drops an emote (QA, 15
		// Oct). So on the move it stops where it is, finishes the step, and
		// claps on the next tick, planted for it as the walk over is.
		if (follower.isMoving() && free())
		{
			clap(play, null);
			log.debug("Level {}: stopping to clap", level); // public:cut logs
			return;
		}
		skilling.gesture(play, here); // public:cut skilling
		// public:else gesture(play, here);
	// public:else }
		// public:else
	// public:else /** A glance at the player and the emote: the seat's gesture with no seat. */
	// public:else private void gesture(int[] animationIds, WorldPoint player)
	// public:else {
		// public:else if (player != null)
		// public:else {
			// public:else follower.glanceAt(player, 1800);
		// public:else }
		// public:else follower.playAnimations(animationIds);
	}

	/**
	 * The level's own moment: the seat excused, and the clap played on
	 * arrival.
	 *
	 * @param walkTo the player's tile to walk over to, or null to stop where
	 * it stands and clap on the next tick
	 */
	private void clap(int[] play, WorldPoint walkTo)
	{
		skilling.setExcused(true); // public:cut skilling
		if (walkTo == null)
		{
			follower.stayHere();
		}
		doing = Doing.LEVEL;
		beat = play;
		arriveAt = client.getTickCount() + (walkTo == null ? 1 : walkTicks(walkTo));
		leaveAt = 0;
	}

	// public:cut agility,agility-all
	/** A clap owed from a level gained on an obstacle, and the tick it is let go. */
	private int[] owed;
	private int owedUntil;

	/** About ten seconds: past that, the moment has gone. */
	static final int OWED_TICKS = 17;

	/** The owed clap, as a level gained on the move is clapped: stopped, then clapped. */
	private void clapOwed(int now)
	{
		int[] play = owed;
		owed = null;
		if (now <= owedUntil)
		{
			clap(play, null);
		}
	}

	// public:end agility,agility-all
	// -------------------------------------------------------------------- pet

	/**
	 * A game message. The one the moments read is the pet drop's: the new
	 * pet is whichever follower-flagged NPC turns up within twenty ticks of
	 * it - or turned up a tick or two before, since the two arrive together.
	 */
	public void noteChatMessage(String message, ChatMessageType type)
	{
		if (message == null
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
		if (npc == null)
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
		if (goBeside(at, 1, Doing.PET))
		{
			pet = npc;
			log.debug("A pet at {}; going to see", at); // public:cut logs
		}
	}

	/**
	 * Over to the nearest tile so many steps off a thing that the follower
	 * will stand on, faced at it, as the moment given.
	 *
	 * @return whether it set off; false leaves the seat as it was
	 */
	private boolean goBeside(WorldPoint at, int steps, Doing what)
	{
		WorldPoint from = follower.getWorldLocation();
		List<WorldPoint> spots = SkillingController.ring(at, at, steps, at.getPlane()); // public:cut skilling
		// public:else List<WorldPoint> spots = FollowerEntity.ring(at, at, steps, at.getPlane());
		spots.sort(SkillingController.stepsFrom(from)); // public:cut skilling
		// public:else spots.sort(java.util.Comparator.comparingInt(a -> a.distanceTo(from)));
		skilling.setExcused(true); // public:cut skilling
		for (WorldPoint spot : spots)
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
		skilling.setExcused(false); // public:cut skilling
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
	public String noteworthy(Tier tier, WorldPoint at)
	{
		// public:cut dev
		return noteworthy(tier, at, false);
	}

	/**
	 * ::follower force drop: the ladder's reaction to news of this tier now,
	 * whatever the ledger says of the gestures it uses, so the body does
	 * what the tier earns rather than what is left of the stretch. What it
	 * does is spent as ever, and nothing else in the ledger is touched, so
	 * the real ones after it wait as they would. The gates stay as
	 * {@link #noteworthy} keeps them: a fight, a park the player gave or
	 * anything else with the feet still keeps the walk and the scroll away,
	 * and the tier below stands in.
	 *
	 * @return what the body did, or null for nothing
	 */
	public String forceNoteworthy(Tier tier, WorldPoint at)
	{
		return noteworthy(tier, at, true);
	}

	/** @param forced whether the ledger's waits are waived for this one reaction */
	private String noteworthy(Tier tier, WorldPoint at, boolean forced)
	{
		// public:end dev
		if (tier == null)
		{
			return null;
		}
		WorldPoint here = FollowerEntity.playerTile(client);
		if (here == null || !follower.isSpawned())
		{
			return null;
		}
		WorldPoint from = follower.getWorldLocation();
		boolean reachable = at != null && from != null && at.getPlane() == from.getPlane()
			&& at.distanceTo(from) <= LOOT_REACH_TILES;
		WorldPoint facing = at == null ? here : at;

		if (tier == Tier.BIG && reachable && free()
			// public:cut company-extras,gestures
			&& (forced || gestures.may(Gestures.Gesture.WALK_OVER) && gestures.may(Gestures.Gesture.NOTES)) // public:cut dev
			// public:else && gestures.may(Gestures.Gesture.WALK_OVER) && gestures.may(Gestures.Gesture.NOTES)
			// public:end company-extras,gestures
			&& goAndLook(at, 1, true, NOTES_ON_THE_SPOT_TICKS))
		{
			// public:cut company-extras,gestures
			gestures.spent(Gestures.Gesture.WALK_OVER);
			gestures.spent(Gestures.Gesture.NOTES);
			// public:end company-extras,gestures
			log.debug("Big news at {}: going over to write it up", at); // public:cut logs
			return "the walk over to write it up";
		}
		String wrote = tier != Tier.SMALL ? writeUpInPlace(facing, forced) : null; // public:cut dev
		// public:else String wrote = tier != Tier.SMALL ? writeUpInPlace(facing) : null;
		if (wrote != null)
		{
			return wrote;
		}
		// public:cut company-extras,gestures
		if (forced || gestures.may(Gestures.Gesture.GLANCE)) // public:cut dev
		// public:else if (gestures.may(Gestures.Gesture.GLANCE))
		{
			gestures.spent(Gestures.Gesture.GLANCE);
		// public:end company-extras,gestures
			follower.glanceAt(facing, GLANCE_MS); // public:cut company-extras,gestures
		// public:else follower.glanceAt(facing, GLANCE_MS);
			return "a glance"; // public:cut company-extras,gestures
		// public:else return "a glance";
		// public:cut company-extras,gestures
		}
		return null;
		// public:end company-extras,gestures
	}

	/**
	 * The notes out where it stands, facing the thing, a beat of writing,
	 * then the look up at the player with a nod. With the hands full (a
	 * borrowed tool) or the follower sat down for the long haul the scroll
	 * cannot come out, and it is the look and the nod on their own.
	 *
	 * @return what the body did, or null when the notes are not due
	 */
	private String writeUpInPlace(WorldPoint facing, boolean forced) // public:cut dev
	// public:else private String writeUpInPlace(WorldPoint facing)
	{
		// public:cut company-extras,gestures
		if (!forced && !gestures.may(Gestures.Gesture.NOTES)) // public:cut dev
		// public:else if (!gestures.may(Gestures.Gesture.NOTES))
		{
			return null;
		}
		gestures.spent(Gestures.Gesture.NOTES);
		// public:end company-extras,gestures
		int now = client.getTickCount();
		boolean canWrite = flourish != null && doing == Doing.NOTHING && !skilling.isSitting() // public:cut skilling
		// public:else boolean canWrite = flourish != null && doing == Doing.NOTHING
			&& !feetTakenOrParked() && !inCombat.getAsBoolean()
			&& follower.isSettled();
		if (canWrite && flourish.start(ErrandController.QUILL_PROP, ErrandController.SCROLL_PROP,
			ErrandController.WRITE_SCROLL_POSE, NOTES_TICKS))
		{
			follower.glanceAt(facing, NOTES_TICKS * 600);
			lookUpAt = now + NOTES_TICKS - 1;
			log.debug("News: the notes out, facing {}", facing); // public:cut logs
			return "the notes out where it stands";
		}
		skilling.gesture(new int[]{NOD}, FollowerEntity.playerTile(client)); // public:cut skilling
		// public:else gesture(new int[]{NOD}, FollowerEntity.playerTile(client));
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
	public boolean farewell()
	{
		// public:cut dev
		return farewell(false);
	}

	/**
	 * ::follower force wave: the logout tab's wave now, however lately the
	 * last one was. The wave is spent as ever, and nothing else, so the
	 * tab's next one waits as it would. The gates stay: a fight, or
	 * somebody else with the feet.
	 *
	 * @return whether it waved
	 */
	public boolean forceFarewell()
	{
		return farewell(true);
	}

	/** @param forced whether the wave's wait is waived for this one */
	private boolean farewell(boolean forced)
	{
		// public:end dev
		WorldPoint here = FollowerEntity.playerTile(client);
		if (here == null || !follower.isSpawned() || feetTaken.getAsBoolean()
			|| inCombat.getAsBoolean()
			// public:cut company-extras,gestures
			|| !(forced || gestures.may(Gestures.Gesture.WAVE)) // public:cut dev
			// public:else || !gestures.may(Gestures.Gesture.WAVE)
			// public:end company-extras,gestures
			)
		{
			return false;
		}
		gestures.spent(Gestures.Gesture.WAVE); // public:cut company-extras,gestures
		skilling.gesture(new int[]{WAVE}, here); // public:cut skilling
		// public:else gesture(new int[]{WAVE}, here);
		log.debug("The logout tab: a wave"); // public:cut logs
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
	@Override // public:cut company-extras,noticing
	public boolean goAndLook(WorldPoint at, int steps, boolean writes, int holdTicks)
	{
		WorldPoint from = follower.getWorldLocation();
		if (at == null || from == null || !free() || at.getPlane() != from.getPlane()
			|| !goBeside(at, Math.max(1, steps), Doing.LOOK))
		{
			return false;
		}
		lookWrites = writes;
		lookHoldTicks = Math.max(1, holdTicks);
		lookUpAt = 0;
		return true;
	}

	/** Whether the follower is off looking at something, or writing it up. */
	@Override // public:cut company-extras,noticing
	public boolean isLooking()
	{
		return doing == Doing.LOOK;
	}

	// ------------------------------------------------------------------ tick

	/** Call once per game tick, on the client thread, after the seat's own tick. */
	public void tick()
	{
		int now = client.getTickCount();
		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation();
		if (here == null)
		{
			letGo(true);
			newcomers.clear();
			petHuntUntil = -1;
			return;
		}
		watchTheWind(now, here, local);
		watchForStrangers(now, local);
		// public:cut agility,agility-all
		if (owed != null && (now > owedUntil || free()))
		{
			clapOwed(now);
		}
		// public:end agility,agility-all
		// The look up from an in-place write-up: the notes are still out,
		// and the nod goes to the player. Not once something has taken the
		// feet since: a scene being filmed puts the notes away as it starts,
		// and the nod would come into the take.
		if (lookUpAt > 0 && now >= lookUpAt && doing == Doing.NOTHING)
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
		if (doing == Doing.NOTHING)
		{
			return;
		}
		if (interrupted() || (doing != Doing.LEVEL && doing != Doing.WIND && !follower.isStaying()))
		{
			letGo(false);
		}
		else if (doing == Doing.STRANGER)
		{
			// It may wander as it talks; the peer follows it.
			WorldPoint at = stranger.getWorldLocation();
			if (at != null)
			{
				follower.setStayFaceTile(at);
			}
		}
		else if (arriveAt > 0 && now >= arriveAt)
		{
			arriveAt = 0;
			arrived(now);
		}
		else if (doing == Doing.LOOK && lookUpAt > 0 && now >= lookUpAt)
		{
			lookUpAt = 0;
			follower.glanceAt(here, LOOK_UP_MS);
			follower.playAnimation(NOD);
		}
		else if (leaveAt > 0 && now >= leaveAt)
		{
			letGo(true);
		}
	}

	/** There: what each moment does on arrival, and how long it holds. */
	private void arrived(int now)
	{
		switch (doing)
		{
			case LEVEL:
				follower.playAnimations(beat);
				leaveAt = now + (beat.length > 1 ? CELEBRATION_TICKS : BEAT_TICKS);
				break;
			case WIND:
				// Already facing them, from the front: a second of it.
				leaveAt = now + LOOK_BACK_TICKS;
				break;
			case PET:
				follower.playAnimation(BEND);
				leaveAt = now + BEND_TICKS;
				break;
			default:
				// A look: the notes out on arrival when it writes.
				if (lookWrites && flourish != null && flourish.start(
					ErrandController.QUILL_PROP, ErrandController.SCROLL_PROP,
					ErrandController.WRITE_SCROLL_POSE, lookHoldTicks))
				{
					// The flourish planted it where it stands, which is the
					// spot; the look up comes a beat before the notes go away.
					lookUpAt = now + lookHoldTicks - 1;
				}
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
			skilling.setExcused(true); // public:cut skilling
			doing = Doing.WIND;
			arriveAt = now + walkTicks(here);
			leaveAt = 0;
			log.debug("Out of breath; going ahead to look back"); // public:cut logs
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
		WorldPoint step = from.dx(dx).dy(dy);
		skilling.setExcused(true); // public:cut skilling
		if (!follower.stayAt(step))
		{
			follower.stayHere();
		}
		if (!follower.isStaying())
		{
			skilling.setExcused(false); // public:cut skilling
			return;
		}
		doing = Doing.STRANGER;
		stranger = npc;
		follower.setStayFaceTile(at);
		follower.playWhenStill(PEER, PEER_WAIT_TICKS);
		log.debug("A stranger, {}, for the player", npc.getName()); // public:cut logs
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
		lookUpAt = 0;
		gestures.reset(); // public:cut company-extras,gestures
	}

	/**
	 * Whether nothing has the feet, ours or anybody's, and the follower is
	 * there - and not sitting in at the seat, which no moment gets it up
	 * from: a level is clapped from the sit (the gesture stands it first),
	 * the others pass.
	 */
	private boolean free()
	{
		return doing == Doing.NOTHING && !feetTakenOrParked() && !inCombat.getAsBoolean()
			&& follower.isSpawned() && !skilling.isSitting(); // public:cut skilling
			// public:else && follower.isSpawned();
	}

	/** Somebody else took the feet mid-moment, or the follower went. */
	private boolean interrupted()
	{
		return feetTakenOrParked() || inCombat.getAsBoolean() || !follower.isSpawned();
	}

	/** Whether the feet are not the moments' to take: somebody else has them, or the player. */
	private boolean feetTakenOrParked()
	{
		return feetTaken.getAsBoolean() || parked.getAsBoolean();
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
		if (was == Doing.LOOK)
		{
			lookUpAt = 0;
			if (flourish != null && flourish.isActive())
			{
				flourish.abort();
			}
		}
		if (was != Doing.NOTHING)
		{
			if (release && follower.isStaying() && !feetTakenOrParked())
			{
				follower.resumeFollowing();
			}
			skilling.setExcused(false); // public:cut skilling
		}
	}

	/** Ticks for the follower to reach a tile by the player, and a beat to settle. */
	private int walkTicks(WorldPoint to)
	{
		WorldPoint from = follower.getWorldLocation();
		return (from == null ? 3 : from.distanceTo(to)) + 2;
	}
}

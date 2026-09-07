package com.follower.follower;

import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;

/**
 * One stretch of the player working a thing: what they clicked, where it
 * stands, when it began and how the experience has been coming in.
 *
 * <p>Three signals make one of these. The click is the earliest and the most
 * honest - it names the target before a single swing - and the repeating
 * animation and the XP drops are the proof that the click turned into work.
 * {@link SkillingController} opens a session on the first proof after a
 * click and closes it by the rules in the spec; everything the company does
 * during a session reads from here.
 */
public final class WorkSession
{
	/** The option word that started it, tags stripped: "Chop down", "Mine". */
	public final String option;

	/**
	 * A guess at the skill, as the lowercased skill name ("woodcutting"), or
	 * empty while nothing has said. The option word makes the first guess and
	 * the first XP drop corrects it, since a "Use" click could be anything.
	 */
	String activity;

	String targetName;
	int targetId;

	/** The footprint's south-west tile; the seat and the events key on it. */
	WorldPoint targetTile;
	WorldPoint targetMax;

	/** The interacted NPC, for a fishing spot; null for scenery. */
	NPC targetNpc;

	/**
	 * Work with nothing in the world to click - fletching, herblore, gems -
	 * opened by its first XP drop rather than a click. The target is the tile
	 * in front of the player, so the seat has something to stand off, and no
	 * object event can deplete it.
	 */
	boolean standing;

	final int startTick;

	/**
	 * The tick this particular target was clicked: the session's start, or
	 * the shift to the next tree. How long a target has been worked decides
	 * whether its going is a fall or a twig.
	 */
	int targetSinceTick;

	/** Where the player stood when the work began; walking off ends it. */
	WorldPoint workSpot;

	/**
	 * Between a click on a moved spot and the swing at it: the player is on
	 * their way, so their walk is not a walk-off and the seat waits.
	 */
	boolean settling;

	int xpDrops;
	int lastDropTick = -1;

	/** The last tick that showed work: an animation, or a drop. */
	int lastEvidenceTick;

	/** The last animation the player played during it, or -1. */
	int lastAnimation = -1;

	/** The target has changed id or gone, and that has been said once. */
	boolean depleted;

	/** Where a same-name spawn turned up after the target went, or null. */
	WorldPoint newSpot;

	WorkSession(String option, String activity, String targetName, int targetId,
		WorldPoint targetTile, WorldPoint targetMax, NPC targetNpc, WorldPoint workSpot,
		int startTick)
	{
		this.option = option;
		this.activity = activity == null ? "" : activity;
		this.targetName = targetName == null ? "" : targetName;
		this.targetId = targetId;
		this.targetTile = targetTile;
		this.targetMax = targetMax == null ? targetTile : targetMax;
		this.targetNpc = targetNpc;
		this.workSpot = workSpot;
		this.startTick = startTick;
		this.targetSinceTick = startTick;
		this.lastEvidenceTick = startTick;
	}

	public String getActivity()
	{
		return activity;
	}

	public String getTargetName()
	{
		return targetName;
	}

	public int getTargetId()
	{
		return targetId;
	}

	public WorldPoint getTargetTile()
	{
		return targetTile;
	}

	/** The far corner of the footprint; the same tile as the target for a 1x1. */
	public WorldPoint getTargetMax()
	{
		return targetMax;
	}

	public NPC getTargetNpc()
	{
		return targetNpc;
	}

	public boolean isStanding()
	{
		return standing;
	}

	public int getStartTick()
	{
		return startTick;
	}

	public WorldPoint getWorkSpot()
	{
		return workSpot;
	}

	public int getXpDrops()
	{
		return xpDrops;
	}

	public int getLastDropTick()
	{
		return lastDropTick;
	}

	public int getLastAnimation()
	{
		return lastAnimation;
	}

	public boolean isDepleted()
	{
		return depleted;
	}

	public WorldPoint getNewSpot()
	{
		return newSpot;
	}
}

package com.follower.speech;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

/**
 * A snapshot of game state, refreshed once per game tick and shared by every
 * condition evaluated that tick.
 */
@lombok.extern.slf4j.Slf4j
public final class TriggerContext
{
	/** Prayer counts as "active" if points have dropped within this many ticks. */
	private static final int PRAYER_DRAIN_MEMORY_TICKS = 5;

	private final Client client;

	private int hitpoints;
	private int maxHitpoints;
	private int prayerPoints;
	private int maxPrayerPoints;
	private int regionId = -1;
	private int previousRegionId = -1;
	private int idleTicks;
	private int ticksSincePrayerDrain = Integer.MAX_VALUE;
	private int lastPrayerPoints = -1;
	private WorldPoint location;
	private Set<Integer> loadedRegions = new HashSet<>();
	private Set<Integer> equippedItems = new HashSet<>();

	public TriggerContext(Client client)
	{
		this.client = client;
	}

	public void refresh()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		hitpoints = client.getBoostedSkillLevel(Skill.HITPOINTS);
		maxHitpoints = client.getRealSkillLevel(Skill.HITPOINTS);

		prayerPoints = client.getBoostedSkillLevel(Skill.PRAYER);
		maxPrayerPoints = client.getRealSkillLevel(Skill.PRAYER);

		if (lastPrayerPoints >= 0 && prayerPoints < lastPrayerPoints)
		{
			ticksSincePrayerDrain = 0;
		}
		else if (ticksSincePrayerDrain < Integer.MAX_VALUE)
		{
			ticksSincePrayerDrain++;
		}
		lastPrayerPoints = prayerPoints;

		WorldPoint previousLocation = location;
		location = local.getWorldLocation();
		if (location != null)
		{
			int newRegion = location.getRegionID();
			if (newRegion != regionId)
			{
				previousRegionId = regionId;
				regionId = newRegion;
			}
		}

		if (previousLocation != null && previousLocation.equals(location) && local.getAnimation() == -1)
		{
			idleTicks++;
		}
		else
		{
			idleTicks = 0;
		}

		int[] regions = client.getTopLevelWorldView().getMapRegions();
		loadedRegions = regions == null
			? new HashSet<>()
			: Arrays.stream(regions).boxed().collect(java.util.stream.Collectors.toSet());

		refreshEquipment(local.getPlayerComposition());
	}

	/**
	 * Worn item ids taken from the player's composition. Note this covers only
	 * slots with a visible model: rings and ammo never appear here.
	 */
	private void refreshEquipment(PlayerComposition composition)
	{
		Set<Integer> worn = new HashSet<>();
		if (composition != null)
		{
			int[] ids = composition.getEquipmentIds();
			if (ids != null)
			{
				for (int raw : ids)
				{
					// Items start at 2048; 256..2047 is the kit range. Using 512 here
					// meant every body kit was reported as a worn item, so an
					// equippedItem rule could fire on a hairstyle.
					if (raw >= PlayerComposition.ITEM_OFFSET)
					{
						worn.add(raw - PlayerComposition.ITEM_OFFSET);
					}
				}
			}
		}
		if (!worn.equals(equippedItems))
		{
			log.info("equipment now {}", worn);
		}
		equippedItems = worn;
	}

	public Client getClient()
	{
		return client;
	}

	public int getHitpoints()
	{
		return hitpoints;
	}

	public int getMaxHitpoints()
	{
		return maxHitpoints;
	}

	public int getHitpointsPercent()
	{
		return maxHitpoints <= 0 ? 100 : (hitpoints * 100) / maxHitpoints;
	}

	public int getPrayerPoints()
	{
		return prayerPoints;
	}

	public int getMaxPrayerPoints()
	{
		return maxPrayerPoints;
	}

	public int getPrayerPercent()
	{
		return maxPrayerPoints <= 0 ? 100 : (prayerPoints * 100) / maxPrayerPoints;
	}

	public boolean isPrayerActive()
	{
		return ticksSincePrayerDrain <= PRAYER_DRAIN_MEMORY_TICKS;
	}

	public int getRegionId()
	{
		return regionId;
	}

	public int getPreviousRegionId()
	{
		return previousRegionId;
	}

	public int getIdleTicks()
	{
		return idleTicks;
	}

	public WorldPoint getLocation()
	{
		return location;
	}

	public boolean isRegionLoaded(int region)
	{
		return loadedRegions.contains(region);
	}

	public boolean isEquipped(int itemId)
	{
		return equippedItems.contains(itemId);
	}

	public boolean isNpcNearby(java.util.function.Predicate<NPC> predicate, int within)
	{
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || !predicate.test(npc))
			{
				continue;
			}
			WorldPoint npcLocation = npc.getWorldLocation();
			if (location == null || npcLocation == null)
			{
				continue;
			}
			if (npcLocation.distanceTo(location) <= within)
			{
				return true;
			}
		}
		return false;
	}

	public String getPlayerName()
	{
		Player local = client.getLocalPlayer();
		return local == null || local.getName() == null ? "you" : local.getName();
	}
}

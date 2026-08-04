package com.follower.follower;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.kit.KitType;

/**
 * Learns which stand/walk/run animation set goes with which wielded weapon, by
 * watching real players.
 *
 * <p>A weapon's pose set lives in the game's weapon-stance data, which neither the
 * runtime API nor the cache's {@code ItemDefinition} exposes per item. But every
 * {@link net.runelite.api.Actor} reports its <em>resolved</em> pose ids, and every
 * player's wielded weapon is readable from their composition. Pairing the two gives
 * real, correct data rather than a hardcoded guess.
 *
 * <p>Your own character is the main teacher: equip a weapon and its stances are
 * learned immediately. Crowded areas fill the library quickly. Results persist to
 * {@code stances.json}.
 */
@Slf4j
@Singleton
public class StanceLibrary
{
	public static final String FILE_NAME = "stances.json";

	/** Weapon item id used for "nothing wielded". */
	public static final int UNARMED = 0;

	public static class Stance
	{
		public int idle;
		public int walk;
		public int run;

		/**
		 * The directional poses: back-pedal, side-steps, and turn-in-place -
		 * used when the follower moves while FACING something, the way an
		 * interacting player does. Entries saved before these fields existed
		 * deserialize them as 0; the pose selector falls back to {@code walk}
		 * (the client's own -1 fallback) until the weapon is observed again.
		 */
		public int walkBack;
		public int walkLeft;
		public int walkRight;
		public int turnLeft;
		public int turnRight;

		public Stance()
		{
		}

		Stance(int idle, int walk, int run,
			int walkBack, int walkLeft, int walkRight, int turnLeft, int turnRight)
		{
			this.idle = idle;
			this.walk = walk;
			this.run = run;
			this.walkBack = walkBack;
			this.walkLeft = walkLeft;
			this.walkRight = walkRight;
			this.turnLeft = turnLeft;
			this.turnRight = turnRight;
		}

		boolean isComplete()
		{
			return idle > 0 && walk > 0 && run > 0;
		}

		boolean sameAs(Stance other)
		{
			return idle == other.idle && walk == other.walk && run == other.run
				&& walkBack == other.walkBack
				&& walkLeft == other.walkLeft && walkRight == other.walkRight
				&& turnLeft == other.turnLeft && turnRight == other.turnRight;
		}
	}

	private final Gson gson;

	/** weapon item id -> stance set. */
	private final Map<Integer, Stance> stances = new HashMap<>();

	private Path file;
	private boolean dirty;

	@Inject
	public StanceLibrary(Gson gson)
	{
		this.gson = gson;
		// Unarmed is known and never needs learning.
		stances.put(UNARMED, new Stance(PlayerPose.IDLE, PlayerPose.WALK, PlayerPose.RUN,
			PlayerPose.TURN_180, PlayerPose.SIDESTEP_LEFT, PlayerPose.SIDESTEP_RIGHT,
			PlayerPose.IDLE_TURN, PlayerPose.IDLE_TURN));
	}

	public void load(Path dataDir)
	{
		file = dataDir.resolve(FILE_NAME);
		if (!Files.isRegularFile(file))
		{
			return;
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			Map<String, Stance> loaded = gson.fromJson(reader,
				new com.google.gson.reflect.TypeToken<Map<String, Stance>>()
				{
				}.getType());

			if (loaded != null)
			{
				for (Map.Entry<String, Stance> entry : loaded.entrySet())
				{
					try
					{
						stances.put(Integer.parseInt(entry.getKey()), entry.getValue());
					}
					catch (NumberFormatException ignored)
					{
						// Skip malformed key.
					}
				}
				log.info("Loaded {} learned weapon stances", loaded.size());
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not read {}", file, e);
		}
	}

	public void save()
	{
		if (!dirty || file == null)
		{
			return;
		}

		Map<String, Stance> out = new HashMap<>();
		for (Map.Entry<Integer, Stance> entry : stances.entrySet())
		{
			out.put(Integer.toString(entry.getKey()), entry.getValue());
		}

		try
		{
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8))
			{
				gson.toJson(out, writer);
			}
			dirty = false;
		}
		catch (IOException e)
		{
			log.warn("Could not write {}", file, e);
		}
	}

	/**
	 * Records the stance set of every player in the scene. Cheap enough to run each
	 * game tick; only genuinely new weapons cause a write.
	 */
	public void observe(Client client)
	{
		for (Player player : client.getTopLevelWorldView().players())
		{
			if (player == null)
			{
				continue;
			}
			learn(player);
		}
	}

	private void learn(Player player)
	{
		PlayerComposition composition = player.getPlayerComposition();
		if (composition == null)
		{
			return;
		}

		int raw = composition.getEquipmentId(KitType.WEAPON);
		// getEquipmentId returns the item id directly, or -1 when nothing is wielded.
		int weaponId = raw <= 0 ? UNARMED : raw;

		Stance observed = new Stance(
			player.getIdlePoseAnimation(),
			player.getWalkAnimation(),
			player.getRunAnimation(),
			player.getWalkRotate180(),
			player.getWalkRotateLeft(),
			player.getWalkRotateRight(),
			player.getIdleRotateLeft(),
			player.getIdleRotateRight());

		if (!observed.isComplete())
		{
			return;
		}

		Stance known = stances.get(weaponId);
		if (known != null && known.sameAs(observed))
		{
			return;
		}

		stances.put(weaponId, observed);
		dirty = true;
		log.debug("Learned stance for weapon {}: idle={} walk={} run={} back={} left={} right={}",
			weaponId, observed.idle, observed.walk, observed.run,
			observed.walkBack, observed.walkLeft, observed.walkRight);
	}

	/** The stance for a weapon, falling back to unarmed when not yet learned. */
	public Stance forWeapon(int weaponItemId)
	{
		Stance stance = stances.get(weaponItemId <= 0 ? UNARMED : weaponItemId);
		return stance != null ? stance : stances.get(UNARMED);
	}

	public boolean knows(int weaponItemId)
	{
		return stances.containsKey(weaponItemId <= 0 ? UNARMED : weaponItemId);
	}

	public int size()
	{
		return stances.size();
	}
}

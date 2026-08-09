package com.follower.sim;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * A game that is not running, good enough to evaluate rules against.
 *
 * <p>The speech engine only ever asks the client a couple of dozen questions -
 * skill levels, where the player is, what is nearby - so the whole client can
 * be a dynamic proxy that answers from a map and returns harmless defaults for
 * everything else. That is worth far more than mocking each interface by hand:
 * {@link Client} has hundreds of methods and only the answers matter here.
 *
 * <p>Defaults are chosen to be the LEAST eventful reading possible - full
 * health, no gear, nothing nearby, standing still - so a test that does not
 * mention a thing gets a quiet game rather than an accidental trigger.
 */
public final class FakeGame
{
	/**
	 * An answer to one method call. Given the call's arguments so that readings
	 * keyed by an id - varbits, varplayers, skill levels - can differ per id
	 * rather than all sharing one value.
	 */
	private interface Answer
	{
		Object get(Object[] args);
	}

	private final Map<String, Object> clientAnswers = new HashMap<>();
	private final Map<String, Object> playerAnswers = new HashMap<>();
	private final List<NPC> npcs = new ArrayList<>();
	/** Each NPC's answer map, so a spawned one can still be moved about. */
	private final Map<NPC, Map<String, Object>> npcAnswers = new HashMap<>();

	private final Map<Integer, Integer> varbits = new HashMap<>();
	private final Map<Integer, Integer> varps = new HashMap<>();
	private final Map<Skill, int[]> skillLevels = new HashMap<>();

	private int[] mapRegions = {12850};
	private int[] equipment = new int[12];

	private final List<Player> others = new ArrayList<>();

	/** Null until a test says otherwise: the container is not always loaded. */
	private Item[] inventory;

	/**
	 * Collision flags by scene tile, or null for "no data" - which the follower
	 * reads as open, the same way it does at an instance edge.
	 */
	private int[][] collisionFlags;

	public final Client client;
	public final Player player;

	public FakeGame()
	{
		player = proxy(Player.class, playerAnswers);
		client = proxy(Client.class, clientAnswers);

		// A healthy, unremarkable player standing in Lumbridge doing nothing.
		playerAnswers.put("getName", "Tester");
		playerAnswers.put("getWorldLocation", new WorldPoint(3222, 3218, 0));
		playerAnswers.put("getAnimation", -1);
		playerAnswers.put("getSkullIcon", -1);
		playerAnswers.put("getInteracting", null);
		playerAnswers.put("getHealthRatio", -1);
		playerAnswers.put("getCombatLevel", 0);
		playerAnswers.put("getPlayerComposition", composition());

		clientAnswers.put("getLocalPlayer", player);
		clientAnswers.put("getEnergy", 10000);
		clientAnswers.put("getTickCount", 0);
		WorldView view = worldView();
		clientAnswers.put("getTopLevelWorldView", view);
		clientAnswers.put("getWorldView", (Answer) args -> view);
		clientAnswers.put("findWorldViewFromWorldPoint", (Answer) args -> view);

		// Keyed readings: everything unset reads as the quiet default.
		clientAnswers.put("getVarbitValue",
			(Answer) args -> varbits.getOrDefault((Integer) args[0], 0));
		clientAnswers.put("getVarpValue",
			(Answer) args -> varps.getOrDefault((Integer) args[0], 0));
		clientAnswers.put("getBoostedSkillLevel", (Answer) args -> levels(args[0])[0]);
		clientAnswers.put("getRealSkillLevel", (Answer) args -> levels(args[0])[1]);
		clientAnswers.put("getItemContainer",
			(Answer) args -> inventory == null ? null : inventoryContainer());
	}

	private int[] levels(Object skill)
	{
		return skillLevels.getOrDefault(skill, DEFAULT_LEVELS);
	}

	private static final int[] DEFAULT_LEVELS = {99, 99};

	// ------------------------------------------------------------------ setup

	public FakeGame tick(int tickCount)
	{
		clientAnswers.put("getTickCount", tickCount);
		return this;
	}

	public FakeGame at(int x, int y, int plane)
	{
		playerAnswers.put("getWorldLocation", new WorldPoint(x, y, plane));
		return this;
	}

	public FakeGame animating(int id)
	{
		playerAnswers.put("getAnimation", id);
		return this;
	}

	/** Hitpoints as a level pair; the context turns them into a percentage. */
	public FakeGame hitpoints(int current, int max)
	{
		skillLevels.put(Skill.HITPOINTS, new int[]{current, max});
		return this;
	}

	/** No local player, as between the login screen and the world loading. */
	public FakeGame loggedOut()
	{
		clientAnswers.put("getLocalPlayer", null);
		return this;
	}

	/** Run energy in the client's own units: hundredths of a percent, 0-10000. */
	public FakeGame energy(int hundredthsOfAPercent)
	{
		clientAnswers.put("getEnergy", hundredthsOfAPercent);
		return this;
	}

	public FakeGame regions(int... regions)
	{
		mapRegions = regions;
		return this;
	}

	/** Worn item ids, in the composition's raw encoding. */
	public FakeGame wearing(int... itemIds)
	{
		equipment = new int[Math.max(12, itemIds.length)];
		for (int i = 0; i < itemIds.length; i++)
		{
			equipment[i] = itemIds[i] + PlayerComposition.ITEM_OFFSET;
		}
		playerAnswers.put("getPlayerComposition", composition());
		return this;
	}

	/** Puts an NPC in the scene, at the player's own tile unless moved. */
	public NPC spawnNpc(int id, String name, int combatLevel)
	{
		return spawnNpc(id, name, combatLevel, false);
	}

	/**
	 * @param pet whether the game flags it as a follower, which is how the
	 * plugin recognises every pet in the game without naming any of them
	 */
	public NPC spawnNpc(int id, String name, int combatLevel, boolean pet)
	{
		Map<String, Object> composition = new HashMap<>();
		composition.put("isFollower", pet);
		composition.put("getName", name);
		composition.put("getId", id);
		composition.put("getSize", 1);

		Map<String, Object> answers = new HashMap<>();
		answers.put("getId", id);
		answers.put("getName", name);
		answers.put("getCombatLevel", combatLevel);
		answers.put("getHealthRatio", 30);
		answers.put("getWorldLocation", playerAnswers.get("getWorldLocation"));
		answers.put("getComposition", proxy(NPCComposition.class, composition));
		NPC npc = proxy(NPC.class, answers);
		npcs.add(npc);
		npcAnswers.put(npc, answers);
		return npc;
	}

	/** Empties the scene. */
	public FakeGame clearNpcs()
	{
		npcs.clear();
		return this;
	}

	/**
	 * Puts another player in the scene, at the given offset from the local one.
	 * Only their position matters to anything that asks about a crowd.
	 */
	public Player spawnPlayer(int dx, int dy)
	{
		WorldPoint here = (WorldPoint) playerAnswers.get("getWorldLocation");
		Map<String, Object> answers = new HashMap<>();
		answers.put("getName", "Bystander" + others.size());
		answers.put("getWorldLocation",
			new WorldPoint(here.getX() + dx, here.getY() + dy, here.getPlane()));
		Player other = proxy(Player.class, answers);
		others.add(other);
		return other;
	}

	public FakeGame clearPlayers()
	{
		others.clear();
		return this;
	}

	/**
	 * Fills the inventory with that many occupied slots. What is in them does
	 * not matter: only how many slots are left.
	 */
	public FakeGame inventoryUsing(int slots)
	{
		// Item is a final class rather than an interface, so this is the real
		// thing rather than a proxy - which is better anyway.
		inventory = new Item[28];
		for (int i = 0; i < inventory.length; i++)
		{
			inventory[i] = i < slots ? new Item(1511, 1) : new Item(-1, 0);
		}
		return this;
	}

	/** No inventory container at all, as in the first ticks after login. */
	public FakeGame noInventory()
	{
		inventory = null;
		return this;
	}

	/**
	 * Turns collision data on, everything open. Until this is called the scene
	 * has no collision map at all, which is the "off-scene" reading.
	 */
	public FakeGame withCollision()
	{
		collisionFlags = new int[net.runelite.api.Perspective.SCENE_SIZE]
			[net.runelite.api.Perspective.SCENE_SIZE];
		return this;
	}

	/**
	 * Adds a wall on one edge of a world tile. The flag names the side of the
	 * tile it sits on, so a wall between two tiles has to be declared from one
	 * of them - exactly as the game stores it.
	 */
	public FakeGame wallAt(int x, int y, int flag)
	{
		if (collisionFlags == null)
		{
			withCollision();
		}
		collisionFlags[x][y] |= flag;
		return this;
	}

	/** Blocks a whole tile, the way a solid object does. */
	public FakeGame blockTile(int x, int y)
	{
		return wallAt(x, y, net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL);
	}

	/** A varbit reading, by id. Unset varbits read zero. */
	public FakeGame varbit(int id, int value)
	{
		varbits.put(id, value);
		return this;
	}

	/** A varplayer reading, by id. This is where poison and venom live. */
	public FakeGame varp(int id, int value)
	{
		varps.put(id, value);
		return this;
	}

	public FakeGame skulled(boolean skulled)
	{
		playerAnswers.put("getSkullIcon", skulled ? 0 : -1);
		return this;
	}

	/** Prayer points, which the context also uses to decide if prayer is draining. */
	public FakeGame prayer(int current, int max)
	{
		skillLevels.put(Skill.PRAYER, new int[]{current, max});
		return this;
	}

	/** Moves an NPC that is already in the scene. */
	public FakeGame moveNpc(NPC npc, int x, int y, int plane)
	{
		npcAnswers.get(npc).put("getWorldLocation", new WorldPoint(x, y, plane));
		return this;
	}

	/** Makes the player interact with something, which is what combat looks like. */
	public FakeGame fighting(NPC target)
	{
		playerAnswers.put("getInteracting", target);
		return this;
	}

	// ---------------------------------------------------------------- plumbing

	private PlayerComposition composition()
	{
		Map<String, Object> answers = new HashMap<>();
		answers.put("getEquipmentIds", (Answer) args -> equipment);
		return proxy(PlayerComposition.class, answers);
	}

	private WorldView worldView()
	{
		Map<String, Object> answers = new HashMap<>();
		answers.put("getMapRegions", (Answer) args -> mapRegions);
		answers.put("getBaseX", (Answer) args -> 0);
		answers.put("getBaseY", (Answer) args -> 0);
		answers.put("getSizeX", (Answer) args -> net.runelite.api.Perspective.SCENE_SIZE);
		answers.put("getSizeY", (Answer) args -> net.runelite.api.Perspective.SCENE_SIZE);
		answers.put("isInstance", (Answer) args -> false);
		answers.put("getCollisionMaps", (Answer) args -> collisionFlags == null
			? null
			: new CollisionData[]{proxyCollision(), proxyCollision(),
				proxyCollision(), proxyCollision()});
		answers.put("npcs", (Answer) args -> indexed(npcs));
		// The local player is in the real list too, so the crowd count has to
		// exclude it by identity - which is exactly what it does.
		answers.put("players", (Answer) args ->
		{
			List<Player> all = new ArrayList<>(others);
			all.add(player);
			return indexed(all);
		});
		return proxy(WorldView.class, answers);
	}

	private CollisionData proxyCollision()
	{
		Map<String, Object> answers = new HashMap<>();
		answers.put("getFlags", (Answer) args -> collisionFlags);
		return proxy(CollisionData.class, answers);
	}

	private ItemContainer inventoryContainer()
	{
		Map<String, Object> answers = new HashMap<>();
		answers.put("getItems", (Answer) args -> inventory);
		return proxy(ItemContainer.class, answers);
	}

	private static <T> IndexedObjectSet<T> indexed(List<T> backing)
	{
		return new IndexedObjectSet<T>()
		{
			@Override
			public T byIndex(int index)
			{
				return index >= 0 && index < backing.size() ? backing.get(index) : null;
			}

			@Override
			public Iterator<T> iterator()
			{
				return backing.iterator();
			}
		};
	}

	/**
	 * Answers from the map when it has an entry for the method NAME, otherwise
	 * returns the harmless default for the return type. A {@link Function} value
	 * is called at answer time, so mutable state (the scene, worn gear) is read
	 * fresh rather than frozen when the proxy was built.
	 */
	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Map<String, Object> answers)
	{
		InvocationHandler handler = (instance, method, args) ->
		{
			String name = method.getName();

			// equals/hashCode/toString must behave, or collections misbehave.
			switch (name)
			{
				case "equals":
					return instance == args[0];
				case "hashCode":
					return System.identityHashCode(instance);
				case "toString":
					return type.getSimpleName() + "@fake";
				default:
					break;
			}

			if (answers.containsKey(name))
			{
				Object answer = answers.get(name);
				return answer instanceof Answer
					? ((Answer) answer).get(args)
					: answer;
			}
			return defaultFor(method.getReturnType());
		};

		return (T) Proxy.newProxyInstance(
			type.getClassLoader(), new Class<?>[]{type}, handler);
	}

	private static Object defaultFor(Class<?> type)
	{
		if (!type.isPrimitive())
		{
			return null;
		}
		if (type == boolean.class)
		{
			return Boolean.FALSE;
		}
		if (type == void.class)
		{
			return null;
		}
		if (type == long.class)
		{
			return 0L;
		}
		if (type == double.class)
		{
			return 0.0d;
		}
		if (type == float.class)
		{
			return 0.0f;
		}
		if (type == char.class)
		{
			return (char) 0;
		}
		if (type == byte.class)
		{
			return (byte) 0;
		}
		if (type == short.class)
		{
			return (short) 0;
		}
		return 0;
	}
}

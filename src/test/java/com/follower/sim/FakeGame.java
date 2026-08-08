package com.follower.sim;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
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
	private final Map<String, Object> clientAnswers = new HashMap<>();
	private final Map<String, Object> playerAnswers = new HashMap<>();
	private final List<NPC> npcs = new ArrayList<>();

	private int[] mapRegions = {12850};
	private int[] equipment = new int[12];

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
		clientAnswers.put("getBoostedSkillLevel", 99);
		clientAnswers.put("getRealSkillLevel", 99);
		clientAnswers.put("getEnergy", 10000);
		clientAnswers.put("getTickCount", 0);
		clientAnswers.put("getVarbitValue", 0);
		clientAnswers.put("getVarpValue", 0);
		clientAnswers.put("getTopLevelWorldView", worldView());
	}

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
		clientAnswers.put("getBoostedSkillLevel", current);
		clientAnswers.put("getRealSkillLevel", max);
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
		Map<String, Object> answers = new HashMap<>();
		answers.put("getId", id);
		answers.put("getName", name);
		answers.put("getCombatLevel", combatLevel);
		answers.put("getHealthRatio", 30);
		answers.put("getWorldLocation", playerAnswers.get("getWorldLocation"));
		NPC npc = proxy(NPC.class, answers);
		npcs.add(npc);
		return npc;
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
		answers.put("getEquipmentIds", (Function<Void, Object>) ignored -> equipment);
		return proxy(PlayerComposition.class, answers);
	}

	private WorldView worldView()
	{
		Map<String, Object> answers = new HashMap<>();
		answers.put("getMapRegions", (Function<Void, Object>) ignored -> mapRegions);
		answers.put("npcs", (Function<Void, Object>) ignored -> indexed(npcs));
		return proxy(WorldView.class, answers);
	}

	private static IndexedObjectSet<NPC> indexed(List<NPC> backing)
	{
		return new IndexedObjectSet<NPC>()
		{
			@Override
			public NPC byIndex(int index)
			{
				return index >= 0 && index < backing.size() ? backing.get(index) : null;
			}

			@Override
			public Iterator<NPC> iterator()
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
				return answer instanceof Function
					? ((Function<Void, Object>) answer).apply(null)
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

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
import net.runelite.api.GameState;
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
	/** And its composition's, so a spawned one can be made a giant. */
	private final Map<NPC, Map<String, Object>> npcCompositions = new HashMap<>();

	private final Map<Integer, Integer> varbits = new HashMap<>();
	private final Map<Integer, Integer> varps = new HashMap<>();
	private final Map<Skill, int[]> skillLevels = new HashMap<>();

	private int[] mapRegions = {12850};
	private int[] equipment = new int[12];
	private boolean instanced;

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
		clientAnswers.put("getGameState", GameState.LOGGED_IN);
		clientAnswers.put("getEnergy", 10000);
		clientAnswers.put("getTickCount", 0);
		WorldView view = worldView();
		clientAnswers.put("getTopLevelWorldView", view);
		playerAnswers.put("getWorldView", view);
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

		// The camera is how the follower tells "gone" from "standing still on
		// purpose", so a test needs to be able to move it and leave it.
		clientAnswers.put("getCameraX", 0);
		clientAnswers.put("getCameraY", 0);
		clientAnswers.put("getCameraPitch", 128);

		clientAnswers.put("getObjectDefinition", (Answer) args ->
		{
			String name = objectNames.get((Integer) args[0]);
			if (name == null)
			{
				return null;
			}
			Map<String, Object> answers = new HashMap<>();
			answers.put("getName", name);
			answers.put("getActions", objectActions.get((Integer) args[0]));
			answers.put("getImpostorIds", null);
			net.runelite.api.ObjectComposition composition =
				proxy(net.runelite.api.ObjectComposition.class, answers);
			if (multilocs.contains((Integer) args[0]))
			{
				// A multiloc: its current state is itself, as far as a test cares.
				answers.put("getImpostorIds", new int[]{(Integer) args[0]});
				answers.put("getImpostor", composition);
			}
			return composition;
		});
		clientAnswers.put("loadAnimation", (Answer) args ->
		{
			Integer frameStep = frameSteps.get((Integer) args[0]);
			if (frameStep == null)
			{
				return null;
			}
			Map<String, Object> answers = new HashMap<>();
			answers.put("getFrameStep", frameStep);
			int[] hands = handItems.getOrDefault((Integer) args[0], new int[]{-1, -1});
			answers.put("getLeftHandItem", hands[0]);
			answers.put("getRightHandItem", hands[1]);
			return proxy(net.runelite.api.Animation.class, answers);
		});
	}

	/** Objects that change as they grow - a farming patch - by id. */
	private final java.util.Set<Integer> multilocs = new java.util.HashSet<>();

	public FakeGame multiloc(int id)
	{
		multilocs.add(id);
		return this;
	}

	/** Animations the client can load, by id: their frame step, -1 for a one-shot. */
	private final Map<Integer, Integer> frameSteps = new HashMap<>();

	/**
	 * Makes an animation loadable: {@code frameStep} 0 or more for an authored
	 * loop - a swing of the axe, a cast - and -1 for a one-shot, a bite to eat.
	 */
	public FakeGame animation(int id, int frameStep)
	{
		frameSteps.put(id, frameStep);
		return this;
	}

	/** What each loadable animation puts in each hand; unset, it leaves both alone. */
	private final Map<Integer, int[]> handItems = new HashMap<>();

	/**
	 * Makes an animation loadable with what it puts in each hand, as the
	 * client's getters give it: -1 keeps the hand, -512 empties it, and
	 * anything else is an item id.
	 */
	public FakeGame animation(int id, int frameStep, int leftHand, int rightHand)
	{
		handItems.put(id, new int[]{leftHand, rightHand});
		return animation(id, frameStep);
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

	/** Turns the player: the actor circle, 0 south, 512 west, 1024 north, 1536 east. */
	public FakeGame turned(int jau)
	{
		playerAnswers.put("getOrientation", jau);
		playerAnswers.put("getCurrentOrientation", jau);
		return this;
	}

	/** Nudges the camera, which is what somebody being at the keyboard looks like. */
	public FakeGame cameraMoved()
	{
		clientAnswers.put("getCameraX",
			((Integer) clientAnswers.get("getCameraX")) + 64);
		return this;
	}

	public FakeGame at(int x, int y, int plane)
	{
		playerAnswers.put("getWorldLocation", new WorldPoint(x, y, plane));
		// Scene base is 0 here, so scene coords equal world coords. Only
		// meaningful for tests that keep their coordinates inside the
		// 104-tile scene.
		playerAnswers.put("getLocalLocation",
			net.runelite.api.coords.LocalPoint.fromWorld(proxyScene(), x, y));
		return this;
	}

	/** Scene objects by tile, keyed plane:x:y; see {@link #placeObject}. */
	private final Map<String, List<net.runelite.api.GameObject>> placedObjects = new HashMap<>();
	private final Map<Integer, String> objectNames = new HashMap<>();
	private final Map<Integer, String[]> objectActions = new HashMap<>();

	/** Walls by tile, keyed plane:x:y; see {@link #placeWall}. */
	private final Map<String, net.runelite.api.WallObject> placedWalls = new HashMap<>();

	/**
	 * A named object with its options, over a footprint from the south-west
	 * corner to the north-east one. It answers its scene corners the way a
	 * real one does and is found on every tile it covers.
	 */
	public FakeGame placeObject(int id, String name, String[] actions, int minX, int minY,
		int maxX, int maxY, int plane)
	{
		objectNames.put(id, name);
		objectActions.put(id, actions);
		Map<String, Object> answers = new HashMap<>();
		answers.put("getId", id);
		answers.put("getPlane", plane);
		answers.put("getWorldLocation", new WorldPoint(minX, minY, plane));
		answers.put("getSceneMinLocation", new net.runelite.api.Point(minX, minY));
		answers.put("getSceneMaxLocation", new net.runelite.api.Point(maxX, maxY));
		net.runelite.api.GameObject object = proxy(net.runelite.api.GameObject.class, answers);
		for (int x = minX; x <= maxX; x++)
		{
			for (int y = minY; y <= maxY; y++)
			{
				placedObjects.computeIfAbsent(plane + ":" + x + ":" + y, k -> new ArrayList<>())
					.add(object);
			}
		}
		return this;
	}

	/** Takes every object with this id off the tiles it covered: felled, emptied. */
	public FakeGame removeObject(int id)
	{
		for (List<net.runelite.api.GameObject> here : placedObjects.values())
		{
			here.removeIf(object -> object.getId() == id);
		}
		return this;
	}

	/**
	 * A wall on a tile - a Motherlode vein - with its options. The wall faces
	 * the edge the game's orientation names: 1 west, 2 north, 4 east, 8 south.
	 * Placing another on the same tile replaces it, as a vein emptying does.
	 */
	public FakeGame placeWall(int id, String name, String[] actions, int x, int y, int plane,
		int orientationA)
	{
		objectNames.put(id, name);
		objectActions.put(id, actions);
		Map<String, Object> answers = new HashMap<>();
		answers.put("getId", id);
		answers.put("getPlane", plane);
		answers.put("getWorldLocation", new WorldPoint(x, y, plane));
		answers.put("getOrientationA", orientationA);
		placedWalls.put(plane + ":" + x + ":" + y, proxy(net.runelite.api.WallObject.class, answers));
		return this;
	}

	/**
	 * Puts a named object into the scene, the way the errand scanner sees one.
	 * The scene base is 0, so world coordinates must stay under the 104-tile
	 * scene size for the object to be inside the tile grid at all.
	 */
	public FakeGame placeObject(int id, String name, int x, int y, int plane)
	{
		objectNames.put(id, name);
		Map<String, Object> answers = new HashMap<>();
		answers.put("getId", id);
		answers.put("getWorldLocation", new WorldPoint(x, y, plane));
		placedObjects
			.computeIfAbsent(plane + ":" + x + ":" + y, k -> new ArrayList<>())
			.add(proxy(net.runelite.api.GameObject.class, answers));
		return this;
	}

	/**
	 * Something standing on one tile: an object placed as the given location
	 * type (10 for a booth, a table, a chair; 0 to 3 for walls) whose model
	 * reaches from {@code bottom} to {@code top} model units above the floor.
	 * Collision is left to the test, because the furniture that matters is
	 * the kind the map leaves open.
	 */
	public FakeGame furniture(int x, int y, int plane, int type, int bottom, int top)
	{
		Map<String, Object> model = new HashMap<>();
		// Y runs negative upward, as in a real model.
		model.put("getVerticesY", new float[]{-bottom, -top});
		model.put("getVerticesCount", 2);
		Map<String, Object> answers = new HashMap<>();
		answers.put("getWorldLocation", new WorldPoint(x, y, plane));
		answers.put("getConfig", type | (2 << 6));
		answers.put("getRenderable", proxy(net.runelite.api.Model.class, model));
		placedObjects
			.computeIfAbsent(plane + ":" + x + ":" + y, k -> new ArrayList<>())
			.add(proxy(net.runelite.api.GameObject.class, answers));
		return this;
	}

	/** As {@link #furniture}, but a named object with options: a crop with Pick, say. */
	public FakeGame furniture(int x, int y, int plane, int type, int bottom, int top,
		int id, String name, String[] actions)
	{
		objectNames.put(id, name);
		objectActions.put(id, actions);
		furniture(x, y, plane, type, bottom, top);
		List<net.runelite.api.GameObject> here = placedObjects.get(plane + ":" + x + ":" + y);
		net.runelite.api.GameObject plain = here.remove(here.size() - 1);
		Map<String, Object> answers = new HashMap<>();
		answers.put("getId", id);
		answers.put("getWorldLocation", plain.getWorldLocation());
		answers.put("getConfig", plain.getConfig());
		answers.put("getRenderable", plain.getRenderable());
		here.add(proxy(net.runelite.api.GameObject.class, answers));
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

	/**
	 * The client at startup, before the game has loaded: no player, no scene,
	 * and a varbit read throws inside the client itself - the real one says
	 * "Cannot read the array length because client.ax is null".
	 */
	public FakeGame starting()
	{
		loggedOut();
		clientAnswers.put("getTopLevelWorldView", null);
		clientAnswers.put("getVarbitValue", (Answer) args ->
		{
			throw new NullPointerException("varbits not loaded");
		});
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
		composition.put("getActions", (Answer) args -> npcActions.get(id));

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
		npcCompositions.put(npc, composition);
		return npc;
	}

	/** How many tiles a side an NPC stands on: one for a person, two for a giant. */
	public FakeGame npcSize(NPC npc, int size)
	{
		npcCompositions.get(npc).put("getSize", size);
		return this;
	}

	/** Which way an NPC faces: 0 south, 512 west, 1024 north, 1536 east. */
	public FakeGame npcTurned(NPC npc, int orientation)
	{
		npcAnswers.get(npc).put("getOrientation", orientation);
		return this;
	}

	/** Options offered by NPCs of this id: a fishing spot's Net and Bait. */
	private final Map<Integer, String[]> npcActions = new HashMap<>();

	public FakeGame npcActions(int id, String... actions)
	{
		npcActions.put(id, actions);
		return this;
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

	/** An inventory holding exactly these item ids, one of each. */
	public FakeGame inventoryContaining(int... ids)
	{
		inventory = new Item[28];
		for (int i = 0; i < inventory.length; i++)
		{
			inventory[i] = i < ids.length ? new Item(ids[i], 1) : new Item(-1, 0);
		}
		return this;
	}

	/** The game state the client reports: logged in unless a test says otherwise. */
	public FakeGame gameState(GameState state)
	{
		clientAnswers.put("getGameState", state);
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

	/** The boat's own collision map, separate from the sea's; null until aboard. */
	private int[][] boatFlags;

	/** An eight by sixteen deck, the size of the boat the bug was found on. */
	public static final int BOAT_WIDTH = 8;
	public static final int BOAT_LENGTH = 16;

	/**
	 * Puts the player aboard a boat: a second world view, eight by sixteen at
	 * the origin, with its own collision map, everything open, while the
	 * top-level map underneath stays whatever the test made it. Tiles inside
	 * the deck resolve to the boat, tiles beyond it to the sea. That is the
	 * shape of sailing - a sub-world laid over the sea - and the follower has
	 * to read the deck's map, not the water's.
	 *
	 * <p>The map is padded the way the client pads a sub-world's: (sizeX + 6)
	 * by (sizeY + 6) with both insets at -1, so scene (x, y) lives at
	 * [x + 1][y + 1]. A reader that ignores the inset sees the wrong tile.
	 */
	public FakeGame aboardBoat()
	{
		boatFlags = new int[BOAT_WIDTH + 6][BOAT_LENGTH + 6];
		Map<String, Object> answers = new HashMap<>();
		answers.put("getId", (Answer) args -> 1);
		answers.put("isTopLevel", (Answer) args -> false);
		answers.put("getPlane", (Answer) args -> 0);
		answers.put("getBaseX", (Answer) args -> 0);
		answers.put("getBaseY", (Answer) args -> 0);
		answers.put("getSizeX", (Answer) args -> BOAT_WIDTH);
		answers.put("getSizeY", (Answer) args -> BOAT_LENGTH);
		answers.put("isInstance", (Answer) args -> false);
		Map<String, Object> deck = new HashMap<>();
		deck.put("getFlags", (Answer) args -> boatFlags);
		CollisionData deckMap = proxy(CollisionData.class, deck);
		answers.put("getCollisionMaps", (Answer) args ->
			new CollisionData[]{deckMap, deckMap, deckMap, deckMap});
		WorldView boat = proxy(WorldView.class, answers);
		WorldView sea = (WorldView) clientAnswers.get("getTopLevelWorldView");
		clientAnswers.put("findWorldViewFromWorldPoint", (Answer) args ->
		{
			WorldPoint w = (WorldPoint) args[0];
			return w.getX() >= 0 && w.getX() < BOAT_WIDTH && w.getY() >= 0 && w.getY() < BOAT_LENGTH
				? boat : sea;
		});
		clientAnswers.put("getWorldView",
			(Answer) args -> ((Integer) args[0]) == 1 ? boat : sea);
		playerAnswers.put("getWorldView", boat);
		return this;
	}

	/**
	 * Aboard, with the water past the hull resolving to no view at all, as
	 * past the edge of what the client has loaded. No data reads as open
	 * everywhere else.
	 */
	public FakeGame seaUnmapped()
	{
		Answer aboard = (Answer) clientAnswers.get("findWorldViewFromWorldPoint");
		WorldView sea = (WorldView) clientAnswers.get("getTopLevelWorldView");
		clientAnswers.put("findWorldViewFromWorldPoint", (Answer) args ->
		{
			Object view = aboard.get(args);
			return view == sea ? null : view;
		});
		return this;
	}

	/** Solid on the boat's map only: a rail, a mast, a cannon, a workbench. */
	public FakeGame boatBlocksTile(int x, int y)
	{
		boatFlags[x + 1][y + 1] |= net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL;
		return this;
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

	/**
	 * An NPC's pose animation - its walk, its stir - against an idle pose of
	 * zero; the same number is standing still.
	 */
	public FakeGame npcPosing(NPC npc, int poseAnimation)
	{
		npcAnswers.get(npc).put("getPoseAnimation", poseAnimation);
		return this;
	}

	/** Whether the top-level world view is an instance - a house, a raid. */
	public FakeGame instanced(boolean instanced)
	{
		this.instanced = instanced;
		return this;
	}

	/**
	 * A real fight: both sides squared up at each other.
	 *
	 * <p>This used to set only the player's side, on the reasoning that
	 * interacting with an NPC is what combat looks like. It is also what
	 * walking up to a guard for directions looks like, and what trading and
	 * talking look like - so the context could not tell them apart and called
	 * all of them a fight. Now that it needs evidence, the harness has to model
	 * the evidence: a fight has two sides.
	 */
	public FakeGame fighting(NPC target)
	{
		playerAnswers.put("getInteracting", target);
		if (target != null)
		{
			// null is how a test says the target is gone.
			npcAnswers.get(target).put("getInteracting", player);
		}
		return this;
	}

	/**
	 * Facing an NPC without fighting it - walking over to talk, to trade, or
	 * to rob it. One-way interaction, which is precisely the case that used to
	 * be indistinguishable from combat.
	 */
	public FakeGame facing(NPC target)
	{
		playerAnswers.put("getInteracting", target);
		return this;
	}

	/**
	 * An NPC turning to the player, who has not turned to it: a random
	 * event arriving, a pet catching up. The other one-way case.
	 */
	public FakeGame npcFacingPlayer(NPC npc)
	{
		npcAnswers.get(npc).put("getInteracting", player);
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
		answers.put("getId", (Answer) args -> 0);
		answers.put("isTopLevel", (Answer) args -> true);
		answers.put("getMapRegions", (Answer) args -> mapRegions);
		answers.put("getBaseX", (Answer) args -> 0);
		answers.put("getBaseY", (Answer) args -> 0);
		answers.put("getSizeX", (Answer) args -> net.runelite.api.Perspective.SCENE_SIZE);
		answers.put("getSizeY", (Answer) args -> net.runelite.api.Perspective.SCENE_SIZE);
		answers.put("isInstance", (Answer) args -> instanced);
		answers.put("getCollisionMaps", (Answer) args -> collisionFlags == null
			? null
			: new CollisionData[]{proxyCollision(), proxyCollision(),
				proxyCollision(), proxyCollision()});
		// hasLineOfSightTo reads the scene's tiles for bridge handling; a grid
		// of nulls is honest for a fake with no bridges, where a null SCENE is
		// a NullPointerException that the engine's rule guard then swallows -
		// the throwing rule gets disabled and the test fails by silence.
		answers.put("getScene", (Answer) args -> proxyScene());
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

	/**
	 * Built once: the LOS walk skips any null tile pair, so an empty grid
	 * reads as "nothing can see anything" rather than "nothing is in the
	 * way". Each tile only ever answers the two questions the sight check
	 * asks - its plane and its scene location.
	 */
	private net.runelite.api.Tile[][][] sceneTiles;

	private net.runelite.api.Scene proxyScene()
	{
		Map<String, Object> answers = new HashMap<>();
		answers.put("getTiles", (Answer) args ->
		{
			if (sceneTiles == null)
			{
				int size = net.runelite.api.Perspective.SCENE_SIZE;
				sceneTiles = new net.runelite.api.Tile[4][size][size];
				for (int plane = 0; plane < 4; plane++)
				{
					for (int x = 0; x < size; x++)
					{
						for (int y = 0; y < size; y++)
						{
							Map<String, Object> tile = new HashMap<>();
							tile.put("getPlane", plane);
							tile.put("getSceneLocation",
								new net.runelite.api.Point(x, y));
							tile.put("getWorldLocation", new WorldPoint(x, y, plane));
							// Read fresh, so objects placed after the grid
							// was built still appear; empty rather than null
							// because the errand scanner iterates it bare.
							String key = plane + ":" + x + ":" + y;
							tile.put("getWallObject", (Answer) callArgs -> placedWalls.get(key));
							tile.put("getGameObjects", (Answer) callArgs ->
							{
								List<net.runelite.api.GameObject> here =
									placedObjects.get(key);
								return here == null
									? new net.runelite.api.GameObject[0]
									: here.toArray(new net.runelite.api.GameObject[0]);
							});
							sceneTiles[plane][x][y] =
								proxy(net.runelite.api.Tile.class, tile);
						}
					}
				}
			}
			return sceneTiles;
		});
		answers.put("getBaseX", 0);
		answers.put("getBaseY", 0);
		return proxy(net.runelite.api.Scene.class, answers);
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

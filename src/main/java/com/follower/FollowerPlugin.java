package com.follower;

import com.follower.appearance.AppearanceService;
import com.follower.appearance.CaptureFallback;
import com.follower.appearance.ModelRepository;
import com.follower.appearance.Outfit;
import com.follower.appearance.OutfitParser;
import com.follower.follower.FollowerEntity;
import com.follower.speech.FollowerOverlay;
import com.follower.speech.RuleLoader;
import com.follower.speech.SpeechEngine;
import com.follower.speech.SpeechOutput;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerEvent;
import com.google.inject.Provides;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.kit.KitType;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.PlayerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Follower Buddy",
	description = "A player-model follower that wears any gear and speaks configurable phrases",
	tags = {"follower", "companion", "pet", "player", "model", "chat", "cosmetic"}
)
public class FollowerPlugin extends Plugin
{
	private static final String DATA_DIR_NAME = "follower";
	private static final String EXACT_PALETTE_KEY = "exactPalette";
	private static final String COMMAND = "follower";

	/** ~2 seconds at 600ms per tick, after the world becomes visible. */
	private static final int SPAWN_DELAY_TICKS = 4;

	/** Tiles in one tick above which the movement must have been a teleport. */
	private static final int TELEPORT_TILES = 8;

	/** Game ticks to let the client rebuild a changed player model before reading it. */
	private static final int MODEL_REBUILD_TICKS = 4;



	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private FollowerConfig config;

	@Inject
	private ModelRepository modelRepository;

	@Inject
	private com.follower.appearance.SpotAnimRepository spotAnimRepository;

	@Inject
	private AppearanceService appearanceService;

	@Inject
	private com.follower.appearance.AppearanceComposer appearanceComposer;

	@Inject
	private com.follower.appearance.PaletteHarvest paletteHarvest;

	@Inject
	private com.follower.appearance.ColorHarvester colorHarvester;

	@Inject
	private CaptureFallback captureFallback;

	@Inject
	private FollowerEntity follower;

	@Inject
	private com.follower.follower.FollowDebugOverlay followDebugOverlay;

	@Inject
	private com.follower.speech.FollowerDialog dialog;

	@Inject
	private com.follower.follower.StanceLibrary stanceLibrary;

	@Inject
	private com.follower.follower.WrapTrimStore wrapTrimStore;

	@Inject
	private SpeechEngine speechEngine;

	@Inject
	private RuleLoader ruleLoader;

	@Inject
	private FollowerOverlay overlay;

	@Inject
	private net.runelite.client.game.ItemManager itemManager;

	@Inject
	private net.runelite.client.ui.ClientToolbar clientToolbar;


	private com.follower.ui.FollowerPanel panel;
	private net.runelite.client.ui.NavigationButton navButton;

	private Path dataDir;
	private WorldPoint lastPlayerTile;
	private int lastRegionId = -1;
	private int reloadPollTicks;
	private int spawnDelayTicks;
	private boolean rebuildQueued;
	private boolean watchAnimations;
	private int animTraceRemaining;
	private final List<Integer> animTrace = new ArrayList<>();
	private final List<Integer> playerTrace = new ArrayList<>();
	private final List<String> animTraceMarks = new ArrayList<>();
	private int lastControllerGeneration = -1;

	/**
	 * Frames sampled by ::follower animtrace. Long enough to span a full cycle -
	 * a pose frame can be held for 20-odd renders, so a 12-frame animation runs
	 * about five seconds and a short trace never reaches the wrap.
	 */
	private static final int ANIM_TRACE_FRAMES = 480;
	private final Map<Skill, Integer> knownLevels = new EnumMap<>(Skill.class);

	@Provides
	FollowerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FollowerConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		dataDir = RuneLite.RUNELITE_DIR.toPath().resolve(DATA_DIR_NAME);
		try
		{
			Files.createDirectories(dataDir);
		}
		catch (IOException e)
		{
			log.warn("Could not create {}", dataDir, e);
		}

		modelRepository.load(dataDir);
		spotAnimRepository.load(dataDir);
		ruleLoader.initialise(dataDir);
		stanceLibrary.load(dataDir);
		paletteHarvest.load(dataDir);
		wrapTrimStore.load(dataDir, follower);

		speechEngine.setSink(this::speak);
		applyConfig();
		loadExactPalette();

		overlayManager.add(overlay);
		overlayManager.add(followDebugOverlay);
		overlayManager.add(dialog);
		dialog.register();
		dialog.setFollowerOutfit(() -> OutfitParser.parse(config.customOutfit()));
		addPanel();

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invoke(this::rebuildFollower);
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(overlay);
		overlayManager.remove(followDebugOverlay);
		overlayManager.remove(dialog);
		dialog.unregister();
		dialog.close();
		overlay.clear();

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
			panel = null;
		}

		clientThread.invoke(() ->
		{
			captureFallback.abort();
			colorHarvester.abort();
			follower.despawn();
			appearanceService.invalidate();
		});

		stanceLibrary.save();
		wrapTrimStore.save(follower);
		modelRepository.unload();
		knownLevels.clear();
		lastPlayerTile = null;
		lastRegionId = -1;
	}

	/*
	 * The animation interpolation filter is deliberately NOT touched.
	 *
	 * It is keyed on ANIMATION ID, not on the object being drawn, and the follower
	 * uses the same ids as the player - that is the point of the learned stances. So
	 * any attempt to change interpolation "for the follower" changes it for the
	 * player too, silently disabling RuneLite's animation smoothing for the user.
	 * There is no way to scope it to one object, so it is left alone.
	 */

	// ------------------------------------------------------------------- panel

	private void addPanel()
	{
		panel = new com.follower.ui.FollowerPanel(itemManager, modelRepository,
			this::equipFromPanel, this::clearSlotFromPanel,
			() -> clientThread.invoke(this::copyGearToCustomOutfit),
			this::clearOutfit, this::setGender, this::cycleKit, this::setBodyColor);

		// ImageUtil.loadImageResource THROWS on a missing resource rather than
		// returning null, and an exception here aborts startUp() and makes RuneLite
		// disable the whole plugin. A cosmetic icon must never be able to do that.
		java.awt.image.BufferedImage icon;
		try
		{
			icon = net.runelite.client.util.ImageUtil
				.loadImageResource(FollowerPlugin.class, "/com/follower/panel-icon.png");
		}
		catch (RuntimeException e)
		{
			log.warn("Panel icon missing, falling back to a blank one", e);
			icon = new java.awt.image.BufferedImage(16, 16,
				java.awt.image.BufferedImage.TYPE_INT_ARGB);
		}

		navButton = net.runelite.client.ui.NavigationButton.builder()
			.tooltip("Follower outfit")
			.priority(7)
			.icon(icon)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		syncPanel();
		buildSlotIndexAsync();
	}

	/**
	 * Works out which slot each wearable item belongs to, so the picker can offer
	 * only sensible choices per slot. Runs off the client thread because it touches
	 * several thousand items; the panel stays usable (unfiltered) until it lands.
	 */
	private void buildSlotIndexAsync()
	{
		List<Integer> ids = modelRepository.allItemIds();
		if (ids.isEmpty())
		{
			return;
		}
		indexChunk(ids, 0, new java.util.HashMap<>());
	}

	/** Items per client-thread pass. Small enough not to be felt as a stutter. */
	private static final int SLOT_INDEX_CHUNK = 250;

	private void indexChunk(List<Integer> ids, int start, java.util.Map<Integer, KitType> index)
	{
		// getItemStats -> getItemComposition asserts the client thread, so this cannot
		// run on an executor. invokeLater spreads the work over successive frames.
		clientThread.invokeLater(() ->
		{
			int end = Math.min(start + SLOT_INDEX_CHUNK, ids.size());
			for (int i = start; i < end; i++)
			{
				int itemId = ids.get(i);
				try
				{
					KitType slot = resolveSlot(itemId);
					if (slot != null)
					{
						index.put(itemId, slot);
					}
				}
				catch (RuntimeException e)
				{
					// A single bad item must not abort the whole index.
					log.debug("Could not resolve slot for item {}", itemId, e);
				}
			}

			if (end < ids.size())
			{
				indexChunk(ids, end, index);
				return;
			}

			log.info("Indexed {} of {} wearable items by slot", index.size(), ids.size());
			if (panel != null)
			{
				panel.setSlotIndex(index);
			}
		});
	}

	/** Pushes the current outfit into the panel so it shows what is actually worn. */
	private void syncPanel()
	{
		if (panel == null)
		{
			return;
		}
		panel.setOutfit(OutfitParser.parse(config.customOutfit()));
		panel.setStatus(modelRepository.isLoaded()
			? modelRepository.getStatus()
			: "no model dump loaded");
	}

	/**
	 * Equips an item chosen in the panel. When {@code slot} is null the slot is
	 * resolved from the item's own equipment stats, which is what stops a torso item
	 * being written into the legs slot.
	 */
	private void equipFromPanel(int itemId, KitType slot)
	{
		clientThread.invoke(() ->
		{
			KitType target = slot != null ? slot : resolveSlot(itemId);
			if (target == null)
			{
				sendStatus("Couldn't work out which slot item " + itemId + " goes in.");
				return;
			}

			Outfit outfit = OutfitParser.parse(config.customOutfit());
			outfit.setItem(target, itemId);
			persistOutfit(outfit);

			String name = modelRepository.itemName(itemId);
			sendStatus("Follower now wearing " + (name == null ? "item " + itemId : name)
				+ " (" + target.name().toLowerCase(Locale.ROOT) + ")");
		});
	}

	private void clearSlotFromPanel(KitType slot)
	{
		clientThread.invoke(() ->
		{
			Outfit outfit = OutfitParser.parse(config.customOutfit());
			outfit.clear(slot);
			persistOutfit(outfit);
		});
	}

	/** The body parts whose kits are gender-specific and so need per-gender memory. */
	private static final KitType[] BODY_KIT_PARTS = {
		KitType.HAIR, KitType.JAW, KitType.TORSO, KitType.ARMS,
		KitType.HANDS, KitType.LEGS, KitType.BOOTS,
	};

	private void setGender(int gender)
	{
		clientThread.invoke(() ->
		{
			Outfit outfit = OutfitParser.parse(config.customOutfit());
			if (outfit.getGender() == gender)
			{
				return;
			}

			// Remember the styles of the body being left, resolved so defaults count
			// too - what you SEE is what comes back.
			configManager.setConfiguration(FollowerConfig.GROUP,
				kitMemoryKey(outfit.getGender()), serializeKits(outfit.withDefaultBody()));

			outfit.setGender(gender);
			// Kit ids are gender-specific, so the old ones would render as the wrong
			// body. Drop them, then restore what this gender wore last time; anything
			// not remembered falls back to withDefaultBody() as before.
			for (KitType part : BODY_KIT_PARTS)
			{
				if (outfit.isKit(part))
				{
					outfit.clear(part);
				}
			}
			applyKitMemory(outfit,
				configManager.getConfiguration(FollowerConfig.GROUP, kitMemoryKey(gender)));

			persistOutfit(outfit);
		});
	}

	private static String kitMemoryKey(int gender)
	{
		return gender == 1 ? "bodyKitsFemale" : "bodyKitsMale";
	}

	private static String serializeKits(Outfit resolved)
	{
		StringBuilder sb = new StringBuilder();
		for (KitType part : BODY_KIT_PARTS)
		{
			if (resolved.isKit(part))
			{
				if (sb.length() > 0)
				{
					sb.append(';');
				}
				sb.append(part.name()).append('=').append(resolved.kitId(part));
			}
		}
		// Skin is remembered per gender too, so each body keeps its own tone. The
		// other colours (hair, torso, legs, boots) deliberately carry across.
		sb.append(sb.length() > 0 ? ";" : "").append("SKIN=").append(resolved.getColors()[4]);
		return sb.toString();
	}

	private void applyKitMemory(Outfit outfit, String stored)
	{
		if (stored == null || stored.trim().isEmpty())
		{
			return;
		}
		for (String token : stored.split(";"))
		{
			String[] halves = token.split("=");
			if (halves.length != 2)
			{
				continue;
			}
			try
			{
				if (halves[0].trim().equalsIgnoreCase("SKIN"))
				{
					int index = Integer.parseInt(halves[1].trim());
					if (index >= 0 && index < com.follower.appearance.GamePalette.SKIN.length)
					{
						int[] colors = outfit.getColors();
						colors[4] = index;
						outfit.setColors(colors);
					}
					continue;
				}

				KitType part = KitType.valueOf(halves[0].trim());
				int kitId = Integer.parseInt(halves[1].trim());
				// The dump may have changed since this was saved; only restore kits
				// that still exist, and let defaults cover the rest.
				if (modelRepository.kit(kitId) != null)
				{
					outfit.setKit(part, kitId);
				}
			}
			catch (IllegalArgumentException ignored)
			{
				// Corrupt token; skip it rather than lose the rest.
			}
		}
	}

	/**
	 * Steps a body part to the next/previous kit that exists in the dump. Kits carry
	 * no names in the cache, so they're browsed by eye - the follower rebuilds live,
	 * which makes it its own preview.
	 */
	private void cycleKit(KitType part, int direction)
	{
		clientThread.invoke(() ->
		{
			Outfit outfit = OutfitParser.parse(config.customOutfit()).withDefaultBody();

			// Only kits whose bodyPartId matches this slot AND this body type, so a
			// beard can never land in the boots slot and genders never cross over.
			List<Integer> choices = modelRepository.kitsFor(part, outfit.getGender());
			if (choices.isEmpty())
			{
				sendStatus(modelRepository.hasKitParts()
					? "No styles available for " + part.name().toLowerCase(Locale.ROOT) + "."
					: "Your model dump predates style filtering - re-run tools/cache-dumper.");
				return;
			}

			// Every part always holds a kit. There is no "none": a bare slot leaves a
			// hole in the body, and the clean-shaven look is itself one of the jaw
			// kits rather than the absence of one.
			int index = outfit.isKit(part) ? choices.indexOf(outfit.kitId(part)) : -1;
			int current = Math.max(index, 0);
			int next = Math.floorMod(current + direction, choices.size());

			outfit.setKit(part, choices.get(next));
			persistOutfit(outfit);
			sendStatus(part.name().toLowerCase(Locale.ROOT)
				+ " style " + (next + 1) + " of " + choices.size());
		});
	}

	/** Sets a body-colour palette index from the panel's exact picker. */
	private void setBodyColor(int colorSlot, int index)
	{
		clientThread.invoke(() ->
		{
			Outfit outfit = OutfitParser.parse(config.customOutfit());
			int[] colors = outfit.getColors();
			if (colorSlot < 0 || colorSlot >= colors.length)
			{
				return;
			}

			// If colours copied from the player are active, convert them to their
			// equivalent palette indices first - the pairs ARE table entries, so the
			// reverse lookup is exact and nothing shifts except the picked slot.
			// Without this, exact-pairs mode would bypass the picker's choice.
			java.util.Map<Short, Short> pairs = appearanceComposer.getExactPairs();
			if (!pairs.isEmpty())
			{
				for (int s = 0; s < colors.length; s++)
				{
					Short replacement = pairs.get(com.follower.appearance.GamePalette.find(s));
					short[] table = com.follower.appearance.GamePalette.table(s);
					if (replacement == null || table == null)
					{
						continue;
					}
					for (int i = 0; i < table.length; i++)
					{
						if (table[i] == replacement)
						{
							colors[s] = i;
							break;
						}
					}
				}
				appearanceComposer.setExactPairs(java.util.Collections.emptyMap());
				configManager.unsetConfiguration(FollowerConfig.GROUP, EXACT_PALETTE_KEY);
				sendStatus("Copied colours converted to palette picks - the picker is now in charge.");
			}

			colors[colorSlot] = index;
			outfit.setColors(colors);

			persistOutfit(outfit);
		});
	}

	private void clearOutfit()
	{
		clientThread.invoke(() -> persistOutfit(new Outfit()));
	}

	private void copyGearToCustomOutfit()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}
		persistOutfit(Outfit.from(local.getPlayerComposition()));
		sendStatus("Copied your gear onto the follower.");
	}

	/** Writes the outfit to config and rebuilds. */
	private void persistOutfit(Outfit outfit)
	{
		configManager.setConfiguration(FollowerConfig.GROUP, "customOutfit", outfit.toString());
		appearanceService.invalidate();
		rebuildFollower();
		syncPanel();
	}



	/** Parses "6723,4191" or "6723 4191" into ids, dropping anything unusable. */
	private static int[] parseAnimationIds(String text)
	{
		if (text == null || text.trim().isEmpty())
		{
			return new int[0];
		}

		List<Integer> ids = new ArrayList<>();
		for (String token : text.split("[,\\s]+"))
		{
			String trimmed = token.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}
			try
			{
				int id = Integer.parseInt(trimmed);
				if (id > 0)
				{
					ids.add(id);
				}
			}
			catch (NumberFormatException ignored)
			{
				// Not a number; skip it rather than refusing the whole chain.
			}
		}

		int[] out = new int[ids.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = ids.get(i);
		}
		return out;
	}

	private KitType resolveSlot(int itemId)
	{
		net.runelite.client.game.ItemStats stats = itemManager.getItemStats(itemId);
		if (stats == null || stats.getEquipment() == null)
		{
			return null;
		}

		// Equipment container indices are NOT contiguous and do NOT line up with
		// KitType ordinals (6, 8 and 11 are kit-only slots with no equippable item),
		// so map them explicitly rather than relying on ordinal coincidence.
		switch (stats.getEquipment().getSlot())
		{
			case 0:
				return KitType.HEAD;
			case 1:
				return KitType.CAPE;
			case 2:
				return KitType.AMULET;
			case 3:
				return KitType.WEAPON;
			case 4:
				return KitType.TORSO;
			case 5:
				return KitType.SHIELD;
			case 7:
				return KitType.LEGS;
			case 9:
				return KitType.HANDS;
			case 10:
				return KitType.BOOTS;
			default:
				// Rings and ammo have no worn model; ARMS/HAIR/JAW are body kits.
				return null;
		}
	}

	// --------------------------------------------------------------- lifecycle

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
				// Hold off briefly: at this point the scene is still settling, the
				// camera is snapping into place and collision data may not be ready,
				// so a follower spawned right now appears mid-load and in the wrong
				// spot. Let the world become visible first.
				spawnDelayTicks = SPAWN_DELAY_TICKS;
				rebuildQueued = true;

				// Sync the colour table to the brightness setting as it stands now;
				// the varp listener keeps it matched to slider changes from here.
				clientThread.invokeLater(() ->
				{
					int brightness = client.getVarpValue(BRIGHTNESS_VARP);
					com.follower.ui.GameColourTable.setBrightnessSetting(brightness);
					log.info("Brightness at login: varp {} -> gamma {}",
						brightness, com.follower.ui.GameColourTable.getCurrentGamma());
				});
				break;

			case LOADING:
				// The object is dropped from the rebuilt scene without its active flag
				// changing, so it must be explicitly re-added or it stays invisible.
				//
				// Neither the waypoint queue nor lastPlayerTile is reset here: world
				// points are absolute and survive a chunk load intact. Clearing either
				// stranded the follower on every chunk boundary - no path to walk, and
				// a lost tick of movement - which is what made it stop and then snap.
				// A real teleport is caught by the distance check in updateTrail.
				follower.markNeedsReattach();
				break;

			case LOGIN_SCREEN:
			case HOPPING:
			case CONNECTION_LOST:
				captureFallback.abort();
				follower.despawn();
				appearanceService.invalidate();
				speechEngine.reset();
				overlay.clear();
				knownLevels.clear();
				lastPlayerTile = null;
				lastRegionId = -1;
				break;

			default:
				break;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!FollowerConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		applyConfig();

		String key = event.getKey();
		if ("customOutfit".equals(key) || "verticalOffset".equals(key))
		{
			clientThread.invoke(() ->
			{
				appearanceService.invalidate();
				rebuildFollower();
			});
		}
	}

	/** Restores exact palette pairs saved by ::follower palette, format "find>replace;...". */
	private void loadExactPalette()
	{
		String stored = configManager.getConfiguration(FollowerConfig.GROUP, EXACT_PALETTE_KEY);
		java.util.Map<Short, Short> pairs = new java.util.LinkedHashMap<>();
		if (stored != null && !stored.trim().isEmpty())
		{
			for (String token : stored.split(";"))
			{
				String[] halves = token.split(">");
				if (halves.length != 2)
				{
					continue;
				}
				try
				{
					pairs.put(Short.parseShort(halves[0].trim()), Short.parseShort(halves[1].trim()));
				}
				catch (NumberFormatException ignored)
				{
					// Corrupt token; skip it rather than lose the rest.
				}
			}
		}
		appearanceComposer.setExactPairs(pairs);
		if (!pairs.isEmpty())
		{
			log.info("Restored {} exact palette pairs", pairs.size());
		}
	}

	private void saveExactPalette(java.util.Map<Short, Short> pairs)
	{
		StringBuilder s = new StringBuilder();
		pairs.forEach((find, replace) ->
		{
			if (s.length() > 0)
			{
				s.append(';');
			}
			s.append(find).append('>').append(replace);
		});
		configManager.setConfiguration(FollowerConfig.GROUP, EXACT_PALETTE_KEY, s.toString());
	}

	private void applyConfig()
	{
		follower.setVerticalOffset(config.verticalOffset());
		speechEngine.setDefaultOutput(config.defaultOutput());
		speechEngine.setGlobalCooldownMs(config.globalCooldownMs());
		speechEngine.setMuted(config.muted());
		speechEngine.setDisabledGroups(collectDisabledGroups());
	}

	private Set<String> collectDisabledGroups()
	{
		Set<String> disabled = new HashSet<>();
		if (!config.groupBoss())
		{
			disabled.add("boss");
		}
		if (!config.groupHealth())
		{
			disabled.add("health");
		}
		if (!config.groupArea())
		{
			disabled.add("area");
		}
		if (!config.groupIdle())
		{
			disabled.add("idle");
		}
		for (String token : config.disabledGroups().split(","))
		{
			String trimmed = token.trim().toLowerCase(Locale.ROOT);
			if (!trimmed.isEmpty())
			{
				disabled.add(trimmed);
			}
		}
		return disabled;
	}

	// ---------------------------------------------------------------- follower

	private void rebuildFollower()
	{
		Player local = client.getLocalPlayer();
		if (local == null || local.getWorldLocation() == null)
		{
			return;
		}

		if (shouldHide())
		{
			follower.despawn();
			return;
		}

		Outfit outfit = resolveOutfit();
		appearanceService.request(outfit, com.follower.appearance.ModelSource.DUMP_ONLY, appearance ->
		{
			if (appearance == null)
			{
				log.warn("Could not build a follower model for {}", outfit);
				sendStatus("Couldn't build the follower model. " + modelHint());
				follower.despawn();
				return;
			}

			// Stances follow the weapon the FOLLOWER holds, not the one we hold.
			follower.setWeapon(outfit.isItem(KitType.WEAPON)
				? outfit.itemId(KitType.WEAPON)
				: com.follower.follower.StanceLibrary.UNARMED);


			if (follower.isSpawned())
			{
				follower.setAppearance(appearance);
			}
			else
			{
				follower.setSpawnAnimation(parseAnimationIds(config.spawnAnimation()));

				// Arrive on a free tile behind the player rather than inside them.
				WorldPoint behind = follower.restingTileBehind(local, 2);
				follower.spawn(appearance, behind != null ? behind : local.getWorldLocation());
			}
		});
	}

	private Outfit resolveOutfit()
	{
		List<String> errors = new ArrayList<>();
		Outfit outfit = OutfitParser.parse(config.customOutfit(), errors);
		if (!errors.isEmpty())
		{
			sendStatus("Outfit warnings: " + String.join("; ", errors));
		}
		return outfit;
	}

	private boolean shouldHide()
	{
		if (!config.followEnabled())
		{
			return false;
		}
		return config.hideInPvp() && client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1;
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		appearanceService.tick();
		colorHarvester.tick();
		follower.updateFrame();

		if (animTraceRemaining > 0)
		{
			// Encode the frame with markers, so one trace separates the cases a bare
			// frame index cannot: R = a new controller was installed (a restart, which
			// looks exactly like a wrap otherwise), B = the untransformed base model
			// was handed to the renderer, which would read as a freeze.
			int generation = follower.getControllerGeneration();
			String marker = "";
			if (generation != lastControllerGeneration)
			{
				marker += "R";
				lastControllerGeneration = generation;
			}
			if (follower.isRenderingBaseModel())
			{
				marker += "B";
			}
			animTraceMarks.add(marker);
			animTrace.add(follower.getPoseFrame());

			Player local = client.getLocalPlayer();
			playerTrace.add(local == null ? -1 : local.getPoseAnimationFrame());

			if (--animTraceRemaining == 0)
			{
				reportAnimationTrace();
			}
		}
	}

	/** Static facts about the animation currently driving the follower. */
	private void reportAnimationInfo()
	{
		net.runelite.api.Animation animation = follower.getPoseAnimation();
		if (animation == null)
		{
			sendStatus("No animation on the follower right now (pose "
				+ follower.getActivePose() + ").");
			return;
		}

		int[] lengths = animation.getFrameLengths();
		java.util.function.IntPredicate filter = client.getAnimationInterpolationFilter();

		sendStatus("Animation " + animation.getId() + ": " + animation.getNumFrames()
			+ " frames, duration " + animation.getDuration()
			+ ", frameStep " + animation.getFrameStep()
			+ ", restartMode " + animation.getRestartMode()
			+ (animation.isMayaAnim() ? ", maya" : ""));

		// If this says NO, interpolation isn't reaching us and the raw frame index is
		// being rendered - which is what makes long frames read as a stall.
		sendStatus("Smoothing active: "
			+ (follower.isSmoothingActive(animation) ? "YES" : "NO")
			+ " (the client's own setting)"
			+ (filter == null ? " | no interpolation filter installed" : ""));

		StringBuilder measured = new StringBuilder();
		for (java.util.Map.Entry<Integer, Integer> entry : follower.getMeasuredTrims().entrySet())
		{
			measured.append(measured.length() > 0 ? ", " : "")
				.append(entry.getKey()).append('=').append(entry.getValue());
		}
		sendStatus("Measured trims: " + (measured.length() == 0 ? "none yet" : measured));

		StringBuilder manual = new StringBuilder();
		for (java.util.Map.Entry<Integer, Integer> entry : follower.getWrapTrims().entrySet())
		{
			manual.append(manual.length() > 0 ? ", " : "")
				.append(entry.getKey()).append('=').append(entry.getValue());
		}
		if (manual.length() > 0)
		{
			sendStatus("Manual overrides: " + manual + " (::follower wrapauto to drop them)");
		}

		if (lengths != null)
		{
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < lengths.length && i < 24; i++)
			{
				sb.append(i > 0 ? "," : "").append(lengths[i]);
			}
			sendStatus("Frame lengths: " + sb + (lengths.length > 24 ? ",..." : ""));
		}
	}

	/** The frame sequence actually rendered, so a stall or restart is visible. */
	private void reportAnimationTrace()
	{
		int restarts = 0;
		int baseFrames = 0;
		for (String mark : animTraceMarks)
		{
			if (mark.contains("R"))
			{
				restarts++;
			}
			if (mark.contains("B"))
			{
				baseFrames++;
			}
		}

		sendStatus("FOLLOWER " + summariseTrace(animTrace));
		sendStatus("YOU      " + summariseTrace(playerTrace));
		sendStatus("Controller restarts: " + restarts + " | frames drawn from the base "
			+ "model: " + baseFrames + " of " + animTrace.size());
		sendStatus(restarts > 1
			? "Restarts mean the pose is being reinstalled - that is the pause."
			: (baseFrames > 0
				? "Base-model frames mean the animation dropped out momentarily."
				: "Neither restarts nor dropouts - the pause is not in the animation."));
	}

	/** Collapses a frame sequence into "frame xHeld" runs, with a dwell average. */
	private String summariseTrace(List<Integer> trace)
	{
		StringBuilder sb = new StringBuilder();
		int previous = Integer.MIN_VALUE;
		int run = 0;
		int runs = 0;
		int total = 0;

		for (int frame : trace)
		{
			if (frame == previous)
			{
				run++;
				continue;
			}
			if (previous != Integer.MIN_VALUE)
			{
				if (sb.length() < 90)
				{
					sb.append(previous).append('x').append(run).append(' ');
				}
				runs++;
				total += run;
			}
			previous = frame;
			run = 1;
		}
		if (previous != Integer.MIN_VALUE)
		{
			runs++;
			total += run;
		}

		String average = runs == 0 ? "-" : String.valueOf(Math.round(total / (float) runs));
		return "(avg hold " + average + " renders, " + runs + " changes): " + sb;
	}

	/**
	 * A sample branching conversation, enough to show what the dialog system can
	 * do. Real content will come from the rules file once speech and dialog share
	 * a source.
	 */
	private static java.util.Map<String, com.follower.speech.FollowerDialog.Node> talkScript()
	{
		java.util.Map<String, com.follower.speech.FollowerDialog.Node> script =
			new java.util.LinkedHashMap<>();
		java.util.function.Supplier<com.follower.speech.FollowerDialog.Node> menu =
			() -> com.follower.speech.FollowerDialog.Node.says()
				.choices(
					"How are you doing?", "ask-how",
					"Where did you get that outfit?", "ask-outfit",
					"Stay close, would you?", "ask-close",
					"Never mind.", "bye-you");

		script.put("start", com.follower.speech.FollowerDialog.Node
			.says("Hello! Did you need something?")
			.choices(
				"How are you doing?", "ask-how",
				"Where did you get that outfit?", "ask-outfit",
				"Stay close, would you?", "ask-close",
				"Never mind.", "bye-you"));

		script.put("menu", menu.get());

		script.put("ask-how", com.follower.speech.FollowerDialog.Node
			.you("How are you doing?").then("say-how"));
		script.put("say-how", com.follower.speech.FollowerDialog.Node
			.says("Can't complain!", "Bit of walking, bit of standing about. The usual.")
			.then("menu"));

		script.put("ask-outfit", com.follower.speech.FollowerDialog.Node
			.you("Where did you get that outfit?").then("say-outfit"));
		script.put("say-outfit", com.follower.speech.FollowerDialog.Node
			.says("You picked it out for me, remember?", "I think it suits me.")
			.then("menu"));

		script.put("ask-close", com.follower.speech.FollowerDialog.Node
			.you("Stay close, would you?").then("say-close"));
		script.put("say-close", com.follower.speech.FollowerDialog.Node
			.says("I'm always one step behind you.", "That's rather the point.")
			.then("menu"));

		script.put("bye-you", com.follower.speech.FollowerDialog.Node
			.you("Never mind.").then("bye"));
		script.put("bye", com.follower.speech.FollowerDialog.Node
			.says("Right you are."));

		return script;
	}

	/**
	 * Injects the follower's right-click menu. The game cannot give a client-side
	 * object a native menu, but entries can be added to whatever menu opens while
	 * the mouse is over the follower's projected clickbox. Entries are created
	 * bottom-up: the last one created displays on top.
	 */
	@Subscribe
	public void onMenuOpened(net.runelite.api.events.MenuOpened event)
	{
		if (!follower.isSpawned())
		{
			return;
		}

		if (!follower.isUnderMouse(client.getMouseCanvasPosition()))
		{
			addSendFollowerEntry(event);
			return;
		}

		if (follower.isStaying())
		{
			addFollowerMenuEntry("Follow", follower::resumeFollowing);
		}
		else
		{
			addFollowerMenuEntry("Stay", follower::stayHere);
		}
		addFollowerMenuEntry("Dance", () -> follower.playAnimation(866));
		addFollowerMenuEntry("Wave", () -> follower.playAnimation(863));
		addFollowerMenuEntry("Face-me", () ->
		{
			Player local = client.getLocalPlayer();
			if (local == null)
			{
				return;
			}
			if (!follower.moveToFront(local))
			{
				speak("There's no room in front of you for me to stand!",
					com.follower.speech.SpeechOutput.OVERHEAD, null, -1);
			}
		});
		addFollowerMenuEntry("Talk-to", () -> dialog.start(config.followerName(), talkScript(), "start"));
	}

	/**
	 * Adds a "Send" entry to a ground tile's menu, so the follower can be posed
	 * anywhere by pointing at the spot. SHIFT must be held when the menu opens -
	 * the game's own convention for secondary actions (shift-drop, shift-click
	 * configs) - so ordinary right-clicks stay uncluttered. Created at index 0 -
	 * the BOTTOM of the displayed menu - so it can never steal the left-click
	 * from Walk here. Only menus that actually target the ground (they contain
	 * a Walk here entry) get one.
	 */
	private void addSendFollowerEntry(net.runelite.api.events.MenuOpened event)
	{
		if (!client.isKeyPressed(net.runelite.api.KeyCode.KC_SHIFT))
		{
			return;
		}

		net.runelite.api.Tile sceneTile = client.getSelectedSceneTile();
		if (sceneTile == null)
		{
			return;
		}

		boolean groundMenu = false;
		for (net.runelite.api.MenuEntry entry : event.getMenuEntries())
		{
			if (entry.getType() == net.runelite.api.MenuAction.WALK)
			{
				groundMenu = true;
				break;
			}
		}
		if (!groundMenu)
		{
			return;
		}

		WorldPoint target = sceneTile.getWorldLocation();
		client.getMenu().createMenuEntry(0)
			.setOption("Send")
			.setTarget("<col=ffff00>" + config.followerName() + "</col>")
			.setType(net.runelite.api.MenuAction.RUNELITE)
			.onClick(e -> clientThread.invoke(() ->
			{
				if (!follower.stayAt(target))
				{
					speak("I can't get to that spot from here!",
						com.follower.speech.SpeechOutput.OVERHEAD, null, -1);
				}
			}));
	}

	/**
	 * Adds the follower's default action to the live menu while the mouse is over
	 * it, so the GAME draws the top-left hover hint.
	 *
	 * <p>Drawing that hint as an overlay put our text alongside the client's own
	 * "Walk here" rather than replacing it. The client always labels the topmost
	 * menu entry, so contributing an entry is the only way to own that line - and
	 * it comes out in the client's exact font and colours for free.
	 */
	@Subscribe
	public void onClientTick(net.runelite.api.events.ClientTick event)
	{
		if (client.isMenuOpen() || !follower.isSpawned()
			|| !follower.isUnderMouse(client.getMouseCanvasPosition()))
		{
			return;
		}

		addFollowerMenuEntry("Talk-to", () ->
			dialog.start(config.followerName(), talkScript(), "start"));
	}

	private void addFollowerMenuEntry(String option, Runnable action)
	{
		client.getMenu().createMenuEntry(-1)
			.setOption(option)
			.setTarget("<col=ffff00>" + config.followerName() + "</col>")
			.setType(net.runelite.api.MenuAction.RUNELITE)
			.onClick(e -> clientThread.invoke(action::run));
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged event)
	{
		if (event.getPlayer() == client.getLocalPlayer())
		{
			// Any appearance change might be a new colour worth banking - queue an
			// auto-harvest once the client has rebuilt the model. Duplicate colour
			// sets are deduplicated in the store, so gear swaps cost nothing.
			autoHarvestTicks = MODEL_REBUILD_TICKS;
		}
	}

	/** Ticks until a queued auto-harvest runs; 0 = idle. */
	private int autoHarvestTicks;

	/** Colour indices at the last banked harvest, to skip no-op extractions early. */
	private int[] lastHarvestColors;

	/**
	 * Banks the player's current colours automatically after an appearance change,
	 * so cycling colours at the makeover mage needs no typed commands. Bank-only:
	 * the follower's own colours change only via the explicit palette command,
	 * otherwise it would flicker through every colour being tried on.
	 */
	private void autoHarvest(Player local)
	{
		if (autoHarvestTicks <= 0 || --autoHarvestTicks > 0)
		{
			return;
		}

		PlayerComposition comp = local.getPlayerComposition();
		if (comp == null)
		{
			return;
		}

		int[] colors = comp.getColors();
		if (colors != null && java.util.Arrays.equals(colors, lastHarvestColors))
		{
			return;
		}

		java.util.Map<Short, Short> pairs = new java.util.LinkedHashMap<>();
		List<String> lines = appearanceComposer.comparePalette(
			Outfit.from(comp), local.getModel(), pairs);
		if (pairs.isEmpty())
		{
			// Alignment failed or nothing recoloured; log why, stay quiet in chat.
			lines.forEach(line -> log.debug("auto-harvest: {}", line));
			return;
		}

		lastHarvestColors = colors == null ? null : colors.clone();
		int before = paletteHarvest.size();
		int total = paletteHarvest.record(colors, pairs);
		if (total > before)
		{
			sendStatus("Banked colour set #" + total
				+ " - run '::follower palette' to put this one on the follower.");
		}
	}

	// ------------------------------------------------------------------ ticks

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		// Watchdog: a slaved chain waits on the player's next stage; if that
		// never arrives (desync, logout race), release the follower rather than
		// hold the last frame forever. Stages arrive at most ~4s apart.
		if (follower.isSlavedChainActive()
			&& client.getTickCount() - follower.getSlavedAdvanceTick() > 25)
		{
			follower.endSlavedChain();
		}

		logPoseComparison(local);
		autoHarvest(local);

		if (spawnDelayTicks > 0)
		{
			spawnDelayTicks--;
			lastPlayerTile = local.getWorldLocation();
			return;
		}

		if (rebuildQueued)
		{
			rebuildQueued = false;
			rebuildFollower();
		}

		if (shouldHide())
		{
			if (follower.isSpawned())
			{
				follower.despawn();
			}
		}
		else if (!follower.isSpawned() && appearanceService.getCurrent() != null)
		{
			follower.spawn(appearanceService.getCurrent(), local.getWorldLocation());
		}

		updateTrail(local);

		// Learn weapon stances from everyone on screen, including ourselves.
		stanceLibrary.observe(client);

		if (++reloadPollTicks >= 2)
		{
			reloadPollTicks = 0;
			if (ruleLoader.reloadIfChanged())
			{
				sendStatus("Reloaded " + ruleLoader.getStatus());
				reportRuleErrors();
			}
		}

		speechEngine.refreshContext();

		int region = speechEngine.getContext().getRegionId();
		if (region != lastRegionId)
		{
			int previous = lastRegionId;
			lastRegionId = region;
			if (previous != -1)
			{
				speechEngine.dispatch(TriggerEvent.regionChange(region, previous));
			}
		}

		speechEngine.dispatch(TriggerEvent.tick());
	}

	private void updateTrail(Player local)
	{
		// The follower observes the player's movement itself, per frame - this tick
		// hook only tracks the tile for region-change speech triggers.
		WorldPoint tile = local.getWorldLocation();
		if (tile != null)
		{
			// Walking away ends a conversation, as it does with any real NPC.
			if (dialog.isOpen() && lastPlayerTile != null && !lastPlayerTile.equals(tile))
			{
				dialog.close();
			}
			lastPlayerTile = tile;
		}
	}

	/**
	 * Copies the head angles off a real NPC dialog the moment one opens. The
	 * game's chathead widget carries the exact rotation values it renders with -
	 * reading them beats guessing at authoring conventions. Values are logged so
	 * a confirmed set can be baked in as defaults.
	 */
	@Subscribe
	public void onWidgetLoaded(net.runelite.api.events.WidgetLoaded event)
	{
		int group = event.getGroupId();
		if (group != net.runelite.api.gameval.InterfaceID.CHAT_LEFT
			&& group != net.runelite.api.gameval.InterfaceID.CHAT_RIGHT)
		{
			return;
		}

		// The children are populated after the load event; read them next cycle.
		clientThread.invokeLater(() ->
		{
			for (int child = 0; child < 16; child++)
			{
				net.runelite.api.widgets.Widget widget = client.getWidget(group, child);
				if (widget == null
					|| widget.getType() != net.runelite.api.widgets.WidgetType.MODEL)
				{
					continue;
				}

				// The rotations were sniffed as 0 while real heads clearly render
				// three-quarter turned - so the turn must come from the widget's
				// ANIMATION posing the head model, not from a rotation. Capture
				// everything, especially the animation id.
				java.awt.Rectangle headRect = widget.getBounds();
				// Relative to the CHATBOX widget - the same base the dialog overlay
				// positions against - not the dialog root, which sits inset from it.
				net.runelite.api.widgets.Widget root = client.getWidget(
					net.runelite.api.gameval.InterfaceID.CHATBOX, 0);
				java.awt.Rectangle rootRect = root == null ? null : root.getBounds();

				log.info("CHATHEAD group={} child={} modelId={} modelType={} anim={} "
						+ "yaw={} pitch={} roll={} zoom={} declared={}x{} canvas={} root={}",
					group, child, widget.getModelId(), widget.getModelType(),
					widget.getAnimationId(), widget.getRotationY(), widget.getRotationX(),
					widget.getRotationZ(), widget.getModelZoom(),
					widget.getWidth(), widget.getHeight(), headRect, rootRect);

				// Copy the real head's rectangle, expressed relative to the dialog,
				// so ours sits at the same place and size instead of a guess.
				if (group == net.runelite.api.gameval.InterfaceID.CHAT_LEFT
					&& headRect != null && rootRect != null
					&& headRect.width > 0 && headRect.height > 0)
				{
					dialog.setHeadRect(new java.awt.Rectangle(
						headRect.x - rootRect.x, headRect.y - rootRect.y,
						headRect.width, headRect.height));

					// The dialog interface's inset within the chatbox is the model
					// clip line (the inner parchment edge).
					net.runelite.api.widgets.Widget dialogRoot = client.getWidget(group, 0);
					if (dialogRoot != null && dialogRoot.getBounds() != null)
					{
						dialog.setDialogInsetX(Math.max(0, dialogRoot.getBounds().x - rootRect.x));
						dialog.setDialogInsetY(Math.max(0, dialogRoot.getBounds().y - rootRect.y));
					}
				}
				// Our dialog lays the head out like the NPC side (left), so adopt the
				// NPC dialog's animation the moment one is seen.
				if (group == net.runelite.api.gameval.InterfaceID.CHAT_LEFT)
				{
					if (widget.getAnimationId() > 0)
					{
						dialog.setTalkAnimationId(widget.getAnimationId());
					}
					// rotationZ is the head's actual angle - copy it verbatim.
					dialog.setNpcTurn(widget.getRotationZ() & 0x7ff);
					dialog.setHeadPitch(widget.getRotationX() & 0x7ff);
					dialog.setHeadZoom(Math.max(1, widget.getModelZoom()));
				}
				sendStatus("Sniffed dialog head: animation " + widget.getAnimationId()
					+ " (see log for the full picture).");
				return;
			}
		});
	}

	/**
	 * Combat interrupts a conversation. Covers both directions: a hitsplat on the
	 * player means they were hit, and the player starting an interaction with an
	 * NPC means they attacked.
	 */
	@Subscribe
	public void onInteractingChanged(net.runelite.api.events.InteractingChanged event)
	{
		if (dialog.isOpen()
			&& event.getSource() == client.getLocalPlayer()
			&& event.getTarget() != null)
		{
			dialog.close();
		}
	}

	// ----------------------------------------------------------- game events

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		speechEngine.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, npc.getId(), npc.getName()));
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		speechEngine.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_DESPAWN, npc.getId(), npc.getName()));
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		speechEngine.dispatch(TriggerEvent.chat(event.getMessage(), event.getType().getType()));
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarbitId() != -1)
		{
			speechEngine.dispatch(TriggerEvent.varbit(event.getVarbitId(), event.getValue(), -1));
		}

		// The brightness setting: the chathead's colour table must be on the same
		// gamma as the real chatheads around it, and must follow slider changes.
		if (event.getVarpId() == BRIGHTNESS_VARP)
		{
			com.follower.ui.GameColourTable.setBrightnessSetting(event.getValue());
			log.info("Brightness setting {} -> colour table gamma {}",
				event.getValue(), com.follower.ui.GameColourTable.getCurrentGamma());
		}
	}

	/** The client's brightness setting (1 Dark .. 4 V.Bright, 2 = Normal). */
	private static final int BRIGHTNESS_VARP = 166;

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (event.getActor() != client.getLocalPlayer())
		{
			return;
		}

		int animationId = event.getActor().getAnimation();

		// Also to the log, so id questions ("which animation is the home
		// teleport?") can be answered from a session's log after the fact.
		if (animationId != -1)
		{
			log.info("player animation {}", animationId);
		}

		// A slaved chain (the home teleport) steps stage-for-stage with the
		// PLAYER's sequence: the server cuts each stage short by starting the
		// next, and mirroring that schedule is what removes the freeze between
		// stages. Checked before dispatch so the event that STARTS the chain
		// (the rule fires inside dispatch) cannot also advance it.
		if (follower.isSlavedChainActive())
		{
			if (animationId == -1)
			{
				clientThread.invoke(follower::endSlavedChain);
			}
			else
			{
				clientThread.invoke(follower::advanceSlavedChain);
			}
		}

		// ::follower watch - report ids as they play, so an animation can be
		// identified by performing it rather than guessing from community id lists.
		if (watchAnimations && animationId != -1)
		{
			sendStatus("Animation " + animationId
				+ "  (::follower anim " + animationId + " to replay it)");
		}

		speechEngine.dispatch(TriggerEvent.animation(animationId));
	}

	/**
	 * Graphic mirroring rides on animation mirroring: when a mirror rule fires,
	 * any spotanim the player shows within this window is copied onto the
	 * follower. Windowed rather than read at fire time because the client can
	 * deliver the graphic on a later tick than the animation, and a multi-stage
	 * teleport (home teleport) trickles graphics in for several seconds.
	 */
	private static final int MIRROR_GRAPHIC_WINDOW_TICKS = 12;
	private int mirrorGraphicsUntilTick = -1;

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		if (event.getActor() != client.getLocalPlayer())
		{
			return;
		}

		int graphicId = event.getActor().getGraphic();
		if (graphicId == -1)
		{
			return;
		}

		int graphicHeight = event.getActor().getGraphicHeight();
		log.info("player graphic {} height {}", graphicId, graphicHeight);
		if (watchAnimations)
		{
			sendStatus("Graphic " + graphicId + " at height " + graphicHeight);
		}

		if (client.getTickCount() <= mirrorGraphicsUntilTick)
		{
			mirrorGraphic(graphicId, graphicHeight);
		}
	}

	private void mirrorGraphic(int graphicId, int height)
	{
		com.follower.appearance.SpotAnimRepository.Entry fx = spotAnimRepository.get(graphicId);
		if (fx == null)
		{
			log.info("graphic {} not mirrored: {}", graphicId,
				spotAnimRepository.isLoaded() ? "not in spotanims.json" : spotAnimRepository.getStatus());
			return;
		}
		clientThread.invoke(() -> follower.playSpotAnim(fx, height));
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (event.getActor() == client.getLocalPlayer() && event.getHitsplat().getAmount() > 0)
		{
			speechEngine.dispatch(TriggerEvent.damageTaken(event.getHitsplat().getAmount()));
			dialog.close();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		int level = event.getLevel();
		Integer previous = knownLevels.put(skill, level);
		if (previous != null && level > previous)
		{
			speechEngine.dispatch(TriggerEvent.levelUp(skill.getName(), level));
		}
	}

	// --------------------------------------------------------------- commands

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!COMMAND.equalsIgnoreCase(event.getCommand()))
		{
			return;
		}

		String[] args = event.getArguments();
		String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";

		switch (sub)
		{
			case "reload":
				ruleLoader.reload();
				sendStatus("Rules: " + ruleLoader.getStatus());
				reportRuleErrors();
				break;

			case "copy":
				// Writes your current gear into the follower's outfit, same as the
				// panel's Copy my gear button.
				clientThread.invoke(this::copyGearToCustomOutfit);
				break;

			case "say":
				if (args.length > 1)
				{
					speechEngine.say(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)), null);
				}
				break;

			case "here":
				clientThread.invoke(() ->
				{
					Player local = client.getLocalPlayer();
					if (local != null && appearanceService.getCurrent() != null)
					{
						follower.spawn(appearanceService.getCurrent(), local.getWorldLocation());
					}
				});
				break;

			case "rebuild":
				clientThread.invoke(() ->
				{
					appearanceService.invalidate();
					follower.despawn();
					rebuildFollower();
				});
				break;

			case "priorities":
				// OSRS sorts faces by priority bucket, not by a depth buffer. Two parts
				// that overlap in space are separated by priority alone, so if the
				// composed model carries different priority data than the client's own
				// player model, overlaps resolve differently - which is what a cape
				// poking through a shoulder looks like.
				clientThread.invoke(() -> compareAgainstClient("PROBE"));
				break;

			case "palette":
				// Reads the client's own body-colour palette out of the live player
				// model by differencing, then applies the recovered pairs to the
				// follower. Wear the colours you want copied.
				if (args.length > 1 && args[1].equalsIgnoreCase("clear"))
				{
					clientThread.invoke(() ->
					{
						appearanceComposer.setExactPairs(java.util.Collections.emptyMap());
						configManager.unsetConfiguration(FollowerConfig.GROUP, EXACT_PALETTE_KEY);
						appearanceService.invalidate();
						follower.despawn();
						rebuildFollower();
						sendStatus("Exact palette cleared - picker colours are back in charge.");
					});
					break;
				}
				clientThread.invoke(() ->
				{
					Player me = client.getLocalPlayer();
					PlayerComposition comp = me == null ? null : me.getPlayerComposition();
					if (comp == null)
					{
						sendStatus("No player model to read yet.");
						return;
					}

					java.util.Map<Short, Short> pairs = new java.util.LinkedHashMap<>();
					for (String line : appearanceComposer.comparePalette(
						Outfit.from(comp), me.getModel(), pairs))
					{
						sendStatus(line);
						log.info("PALETTE {}", line);
					}

					if (!pairs.isEmpty())
					{
						appearanceComposer.setExactPairs(pairs);
						saveExactPalette(pairs);

						// Every distinct extraction also banks one entry of the client's
						// hardcoded colour tables - cycling colours at the makeover mage
						// reconstructs them for an exact picker later.
						int banked = paletteHarvest.record(comp.getColors(), pairs);

						appearanceService.invalidate();
						follower.despawn();
						rebuildFollower();
						sendStatus("Applied " + pairs.size() + " exact colour pairs to the "
							+ "follower and saved them (" + banked + " colour set"
							+ (banked == 1 ? "" : "s") + " banked for the picker). "
							+ "'::follower palette clear' to undo.");
					}
				});
				break;

			case "harvest":
				// Steps every colour index of every body slot through the player's
				// composition client-side, banking each extraction. ~107 entries in a
				// few seconds; the character flickers while it runs.
				if (args.length > 1 && args[1].equalsIgnoreCase("stop"))
				{
					clientThread.invoke(colorHarvester::abort);
					break;
				}
				clientThread.invoke(() ->
				{
					if (captureFallback.isBusy())
					{
						sendStatus("Capture in flight - try again in a moment.");
						return;
					}
					colorHarvester.start(this::sendStatus);
				});
				break;

			case "hidden":
				// Shows the game's own wearPos hide rules for the current gear, so a
				// clipping part can be checked against what should be drawn at all.
				clientThread.invoke(() ->
				{
					for (String line : appearanceComposer.describeHidden(
						OutfitParser.parse(config.customOutfit())))
					{
						sendStatus(line);
					}
				});
				break;

			case "height":
				// Live tuning: the right value can only be judged by eye.
				if (args.length > 1)
				{
					try
					{
						int offset = Integer.parseInt(args[1]);
						configManager.setConfiguration(FollowerConfig.GROUP, "verticalOffset", offset);
						clientThread.invoke(() -> follower.setVerticalOffset(offset));
						sendStatus("Height offset " + offset + " (positive lifts).");
					}
					catch (NumberFormatException e)
					{
						sendStatus("Usage: ::follower height <number>");
					}
				}
				break;

			case "fix":
				// Re-adds the object to the scene without a full rebuild or a plugin
				// toggle - the quick way back if the follower ever vanishes.
				clientThread.invoke(() ->
				{
					follower.markNeedsReattach();
					sendStatus("Re-attached the follower to the scene.");
				});
				break;

			case "pitchsweep":
			case "headsweep":
			{
				// Draws eight heads at once, labelled, so the right facing can be
				// picked by eye. Centre and step narrow it: "headsweep 256 32"
				// sweeps finely around a candidate instead of the whole circle.
				int centre = 0;
				int step = 2048 / 8;
				try
				{
					if (args.length > 1)
					{
						centre = Integer.parseInt(args[1]) & 0x7ff;
					}
					if (args.length > 2)
					{
						step = Math.max(1, Integer.parseInt(args[2]));
					}
				}
				catch (NumberFormatException e)
				{
					sendStatus("Usage: ::follower headsweep [centre] [step]");
					break;
				}

				int useCentre = centre;
				int useStep = step;
				boolean pitchAxis = "pitchsweep".equals(sub);
				clientThread.invoke(() -> dialog.showSweep(
					OutfitParser.parse(config.customOutfit()), useCentre, useStep, pitchAxis));
				sendStatus(pitchAxis
					? "Pick the best tilt, then: ::follower head pitch <number>"
					: "Pick the one facing you, then: ::follower head yaw <number>");
				break;
			}

			case "head":
			{
				// The chathead projection is the one thing I cannot check by eye, so
				// every knob is named and tunable live while a dialog is open.
				if (args.length < 3)
				{
					sendStatus("Usage: ::follower head yaw|pitch|crop|talk <value>");
					sendStatus("Currently: yaw " + dialog.getHeadYaw()
						+ ", pitch " + dialog.getHeadPitch()
						+ ", crop " + dialog.getHeadFraction()
						+ ", talk " + dialog.getTalkAnimationId());
					break;
				}

				try
				{
					String knob = args[1].toLowerCase(Locale.ROOT);
					switch (knob)
					{
						case "tune":
							dialog.setTuning(!args[2].equalsIgnoreCase("off"));
							sendStatus(dialog.isTuning()
								? "Head tuning ON - open the dialog, then arrow keys adjust "
									+ "(shift = fine). Values show top-left."
								: "Head tuning off.");
							return;
						case "yaw":
							dialog.setHeadYaw(Integer.parseInt(args[2]) & 0x7ff);
							break;
						case "pitch":
							dialog.setHeadPitch(Integer.parseInt(args[2]) & 0x7ff);
							break;
						case "zoom":
							dialog.setHeadZoom(Math.max(1, Integer.parseInt(args[2])));
							break;
						case "roll":
							dialog.setNpcTurn(Integer.parseInt(args[2]) & 0x7ff);
							break;
						case "cliptop":
							dialog.setClipTopExtra(Integer.parseInt(args[2]));
							break;
						case "crop":
							dialog.setHeadFraction(Double.parseDouble(args[2]));
							break;
						case "talk":
							dialog.setTalkAnimationId(Integer.parseInt(args[2]));
							break;
						default:
							sendStatus("Usage: ::follower head yaw|pitch|crop|talk <value>");
							return;
					}
					sendStatus("Chathead " + knob + " = " + args[2]
						+ " (updates live while a dialog is open).");
				}
				catch (NumberFormatException e)
				{
					sendStatus("Usage: ::follower head yaw|pitch|crop|talk <value>");
				}
				break;
			}

			case "followtrace":
			{
				// Live follow diagnostics: an on-screen state overlay plus FTRACE
				// rows in the client log for offline analysis.
				boolean enable = args.length < 2 || !args[1].equalsIgnoreCase("off");
				clientThread.invoke(() ->
				{
					follower.setDebugEnabled(enable);
					sendStatus(enable
						? "Follow debug ON - overlay top-left, FTRACE rows in the client log."
						: "Follow debug off.");
				});
				break;
			}

			case "wraplerp":
			{
				// The real fix vs the trim workaround, switchable live for comparison.
				boolean enable = args.length < 2 || !args[1].equalsIgnoreCase("off");
				clientThread.invoke(() ->
				{
					follower.setWrapLerp(enable);
					sendStatus(enable
						? "Wrap interpolation ON - the loop boundary is lerped like any other frame."
						: "Wrap interpolation OFF - back to the frame-trim workaround.");
				});
				break;
			}

			case "wrapauto":
				// Drop hand-tuned values and let measurement decide again.
				clientThread.invoke(() ->
				{
					follower.clearWrapTrims();
					sendStatus("Cleared manual trims - each animation is now measured "
						+ "on first use.");
				});
				break;

			case "wrapearly":
			{
				// Frames trimmed from the end of a looping pose. 1 avoids the
				// interpolation dead-end; 0 restores the original behaviour. The
				// residual jump is the cost of the skipped frame, so it is adjustable.
				int trim = 1;
				if (args.length > 1)
				{
					try
					{
						trim = Integer.parseInt(args[1]);
					}
					catch (NumberFormatException e)
					{
						trim = args[1].equalsIgnoreCase("off") ? 0 : 1;
					}
				}

				int value = trim;
				clientThread.invoke(() ->
				{
					// Applies to whatever is playing right now, so each pose can be
					// tuned while you watch it: stand still for the idle, walk for the
					// walk, hold shift-run for the run.
					net.runelite.api.Animation animation = follower.getPoseAnimation();
					if (animation == null)
					{
						sendStatus("No animation playing to tune.");
						return;
					}

					follower.setWrapTrim(animation.getId(), value);
					follower.setDefaultWrapTrim(value);

					int frames = animation.getFrameLengths() == null
						? 0 : animation.getFrameLengths().length;
					sendStatus("Animation " + animation.getId() + " (" + frames
						+ " frames): trim " + value
						+ (value == 0 ? " - original behaviour" : ""));
				});
				break;
			}

			case "pose":
				// Force a looping pose. 808 = unarmed idle (12 frames), 819 = walk.
				// If the pause period follows the animation's length, it is
				// animation-linked; if not, the animation is a red herring.
				if (args.length > 1)
				{
					try
					{
						int id = Integer.parseInt(args[1]);
						clientThread.invoke(() -> follower.setPoseOverride(id));
						sendStatus(id > 0
							? "Pose forced to " + id + " (::follower pose 0 to release)."
							: "Pose released back to the stance library.");
					}
					catch (NumberFormatException e)
					{
						sendStatus("Usage: ::follower pose <id>  (0 to release)");
					}
				}
				break;

			case "animinfo":
				clientThread.invoke(this::reportAnimationInfo);
				break;

			case "animtrace":
				// Samples the frame index every render for a couple of seconds. A stall
				// shows as the same frame repeating; a restart shows as a jump to 0.
				animTraceRemaining = ANIM_TRACE_FRAMES;
				animTrace.clear();
				playerTrace.clear();
				animTraceMarks.clear();
				lastControllerGeneration = follower.getControllerGeneration();
				sendStatus("Tracing frames for ~2s - keep the follower doing the thing "
					+ "that skips.");
				break;

			case "watch":
				watchAnimations = !watchAnimations;
				sendStatus(watchAnimations
					? "Watching your animations. Go and perform the one you want and I'll "
						+ "print its id. ::follower watch again to stop."
					: "Stopped watching animations.");
				break;

			case "anim":
				// Preview animations without editing config and relogging. Accepts a
				// sequence so multi-clip effects can be judged as they'd actually play.
				if (args.length > 1)
				{
					int[] ids = parseAnimationIds(
						String.join(",", java.util.Arrays.copyOfRange(args, 1, args.length)));
					if (ids.length == 0)
					{
						sendStatus("Usage: ::follower anim <id> [id ...]");
						break;
					}
					clientThread.invoke(() -> follower.playAnimations(ids));
					sendStatus("Playing " + java.util.Arrays.toString(ids) + " on the follower.");
				}
				break;

			case "status":
			case "where":
				sendStatus("Models: " + modelRepository.getStatus()
					+ " | Rules: " + ruleLoader.getStatus()
					+ " | Follower " + (follower.isSpawned() ? "spawned" : "not spawned"));
				// Handy when writing regionEnter / inArea rules.
				sendStatus("Region " + speechEngine.getContext().getRegionId()
					+ " at " + speechEngine.getContext().getLocation());
				break;

			default:
				sendStatus("::follower reload | copy | say <text> | here | rebuild | fix | "
					+ "anim <id...> | watch | colours <part> | grabhair | keephair | "
					+ "clearhair | hairbright <n> | highlight <n> | light <a> <c> | "
					+ "height <n> | animinfo | animtrace | status | where");
			// ::follower interp was removed: the interpolation filter is keyed on
			// animation id, so it could not be changed for the follower without
			// changing it for the player too.
				break;
		}
	}


	// ----------------------------------------------------------------- output

	private void speak(String text, SpeechOutput output, SpeechRule rule, int animationId)
	{
		if (!text.isEmpty() && output.showsOverhead())
		{
			overlay.show(text, config.speechDurationMs());
		}

		if (!text.isEmpty() && output.showsChatbox())
		{
			String formatted = new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT)
				.append("[" + config.followerName() + "] ")
				.append(ChatColorType.NORMAL)
				.append(text)
				.build();

			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(formatted)
				.build());
		}

		if (rule != null && rule.hasAnimationChain())
		{
			// A chain rule carries its own per-stage graphics (the home teleport's
			// rune circle), resolved here so the follower plays the whole sequence
			// even when the trigger was a cosmetic override the follower lacks.
			int stages = rule.animations.size();
			int[] chain = new int[stages];
			com.follower.appearance.SpotAnimRepository.Entry[] stageGraphics =
				new com.follower.appearance.SpotAnimRepository.Entry[stages];
			for (int i = 0; i < stages; i++)
			{
				chain[i] = rule.animations.get(i);
				int graphicId = rule.graphics != null && i < rule.graphics.size()
					? rule.graphics.get(i) : -1;
				stageGraphics[i] = graphicId >= 0 ? spotAnimRepository.get(graphicId) : null;
			}
			if (Boolean.TRUE.equals(rule.syncToPlayer))
			{
				clientThread.invoke(() -> follower.startSlavedChain(chain, stageGraphics));
			}
			else
			{
				clientThread.invoke(() -> follower.playAnimations(chain, stageGraphics));
			}
		}
		else if (animationId >= 0)
		{
			clientThread.invoke(() -> follower.playAnimation(animationId));
		}

		// A teleporting follower leaves with its cast: once the mirrored
		// animation finishes, vanish and reappear beside the landed player.
		if (rule != null && Boolean.TRUE.equals(rule.vanishAfter))
		{
			clientThread.invoke(follower::vanishAfterEmote);
		}

		// A mirror rule firing opens the graphic-copy window, and any spotanim
		// already showing on the player (it can precede the animation event) is
		// copied immediately.
		if (rule != null && Boolean.TRUE.equals(rule.mirrorAnimation))
		{
			mirrorGraphicsUntilTick = client.getTickCount() + MIRROR_GRAPHIC_WINDOW_TICKS;
			clientThread.invoke(() ->
			{
				Player local = client.getLocalPlayer();
				if (local != null && local.getGraphic() != -1)
				{
					mirrorGraphic(local.getGraphic(), local.getGraphicHeight());
				}
			});
		}
	}

	private void reportRuleErrors()
	{
		for (String error : ruleLoader.getErrors())
		{
			sendStatus("Rule problem: " + error);
		}
	}

	private String modelHint()
	{
		return modelRepository.isLoaded()
			? "The dump is loaded (" + modelRepository.getStatus() + ") but has no data for these slots."
			: "No equipment-models.json found - run tools/cache-dumper, or set Model source to \"Capture only\".";
	}

	/**
	 * Composes a model from the player's own live equipment and reports it alongside
	 * the model the client built from that same equipment.
	 *
	 * <p>Both sides are the same outfit by construction, which is the only way the
	 * numbers mean anything. Two earlier attempts at this comparison were confounded:
	 * against the player's live model while the follower wore different gear, and via
	 * the capture path, which re-encodes the outfit into the game's {@code 256+kit} /
	 * {@code 2048+item} scheme, which was being written with the wrong item offset.
	 */
	private void compareAgainstClient(String tag)
	{
		try
		{
			Player local = client.getLocalPlayer();
			PlayerComposition composition = local == null ? null : local.getPlayerComposition();
			if (composition == null)
			{
				log.info("{}: no player composition yet", tag);
				return;
			}

			Outfit yours = Outfit.from(composition);
			log.info("{} outfit: {}", tag, describeSlots(yours));
			log.info("{} composed: {}", tag, describePriorities(appearanceComposer.compose(yours)));
			log.info("{} client:   {}", tag, describePriorities(local.getModel()));
			poseProbeTicks = POSE_PROBE_SAMPLES;
		}
		catch (RuntimeException e)
		{
			log.info("{} failed", tag, e);
		}
	}

	/**
	 * Ticks of pose sampling after a model comparison. Long enough to outlast the
	 * spawn emote - a 12-tick window landed entirely inside it and captured nothing
	 * but animation 715 at frame 0.
	 */
	private static final int POSE_PROBE_SAMPLES = 60;

	private int poseProbeTicks;

	/**
	 * Samples the pose animation driving each model.
	 *
	 * <p>With identical geometry proven, a visual difference has to come from the
	 * two being posed differently - either a different animation entirely, or the
	 * same one at a different frame. A cape hangs off the shoulders, so it shows
	 * pose differences that a torso would hide.
	 */
	private void logPoseComparison(Player local)
	{
		if (poseProbeTicks <= 0)
		{
			return;
		}
		poseProbeTicks--;

		net.runelite.api.Animation pose = follower.getPoseAnimation();
		log.info("POSE follower anim={} frame={} | player anim={} frame={}",
			pose == null ? -1 : pose.getId(), follower.getPoseFrame(),
			local.getPoseAnimation(), local.getPoseAnimationFrame());
	}

	/** Compact slot listing, so a comparison can be tied back to actual gear. */
	private String describeSlots(Outfit outfit)
	{
		StringBuilder out = new StringBuilder();
		for (KitType slot : KitType.values())
		{
			if (outfit.getRaw(slot) == 0)
			{
				continue;
			}
			boolean item = outfit.isItem(slot);
			int id = item ? outfit.itemId(slot) : outfit.kitId(slot);
			String name = item ? modelRepository.itemName(id) : null;
			out.append(slot.name().toLowerCase(java.util.Locale.ROOT))
				.append('=')
				.append(item ? "item" : "kit")
				.append(id)
				.append(name == null ? "" : "(" + name + ")")
				.append(' ');
		}
		return out.length() == 0 ? "empty" : out.toString().trim();
	}

	/**
	 * Summarises a model's face render priorities.
	 *
	 * <p>A null array means every face sits in the same bucket and overlaps are
	 * settled by raw depth, which is how a cape ends up drawn over a shoulder it
	 * should sit behind.
	 */
	private String describePriorities(Model model)
	{
		if (model == null)
		{
			return "no model";
		}

		byte[] priorities;
		try
		{
			priorities = model.getFaceRenderPriorities();
		}
		catch (RuntimeException | LinkageError e)
		{
			return "priorities unavailable (" + e.getClass().getSimpleName() + ")";
		}

		if (priorities == null)
		{
			return "no priority array - all faces in one bucket, depth-sorted";
		}

		java.util.Map<Integer, Integer> histogram = new java.util.TreeMap<>();
		for (byte p : priorities)
		{
			histogram.merge((int) p, 1, Integer::sum);
		}

		StringBuilder out = new StringBuilder(priorities.length + " faces:");
		histogram.forEach((priority, count) -> out.append(" p").append(priority).append("x").append(count));
		return out.toString();
	}

	private void sendStatus(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT)
				.append("[" + config.followerName() + "] ")
				.append(ChatColorType.NORMAL)
				.append(message)
				.build())
			.build());
	}
}

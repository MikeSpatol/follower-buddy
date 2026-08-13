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
import com.follower.speech.TriggerContext;
import com.follower.speech.TriggerEvent;
import com.google.inject.Provides;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
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
import net.runelite.api.events.MenuOptionClicked;
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
	private com.follower.ui.GameFontRepository gameFontRepository;

	@Inject
	private net.runelite.client.ui.overlay.tooltip.TooltipManager tooltipManager;

	@Inject
	private net.runelite.client.input.MouseManager mouseManager;

	@Inject
	private net.runelite.client.game.SpriteManager spriteManager;

	/**
	 * The client's red click cross, drawn to its own recipe: on an entity op
	 * the client stamps the click point and animates four sprite frames, one
	 * per 100ms, dead at 400ms, plotted 12 pixels up-left of the click
	 * (crossX - 8 - 4 in its source). Red frames are sprites 519-522.
	 */
	private long crossStartMs;
	private java.awt.Point crossPoint;

	private void showRedCross()
	{
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		if (mouse != null)
		{
			crossPoint = new java.awt.Point(mouse.getX(), mouse.getY());
			crossStartMs = System.currentTimeMillis();
		}
	}

	private final net.runelite.client.ui.overlay.Overlay crossOverlay =
		new net.runelite.client.ui.overlay.Overlay()
	{
		{
			setPosition(net.runelite.client.ui.overlay.OverlayPosition.DYNAMIC);
			setLayer(net.runelite.client.ui.overlay.OverlayLayer.ABOVE_SCENE);
		}

		@Override
		public java.awt.Dimension render(java.awt.Graphics2D graphics)
		{
			long elapsed = System.currentTimeMillis() - crossStartMs;
			if (crossPoint == null || elapsed >= 400)
			{
				return null;
			}
			int frame = (int) (elapsed / 100);
			java.awt.image.BufferedImage sprite = spriteManager.getSprite(
				RED_CLICK_SPRITE_FIRST + frame, 0);
			if (sprite != null)
			{
				// Centre each frame's own image on the click: the cache sprites
				// carry per-frame padding offsets the client's plotSprite
				// applies, but the sprite manager returns them trimmed - a
				// fixed corner offset let the small early frames drift off
				// centre.
				graphics.drawImage(sprite,
					crossPoint.x - sprite.getWidth() / 2,
					crossPoint.y - sprite.getHeight() / 2, null);
			}
			return null;
		}
	};

	/**
	 * Shift + left-click on the follower performs the hover box's action -
	 * Talk-to - like clicking a real NPC. Intercepted directly rather than
	 * trusting the injected menu entry to be present on the click's exact
	 * frame. The click is consumed so it doesn't also walk.
	 */
	private final net.runelite.client.input.MouseAdapter shiftClickAdapter =
		new net.runelite.client.input.MouseAdapter()
	{
		@Override
		public java.awt.event.MouseEvent mousePressed(java.awt.event.MouseEvent event)
		{
			if (!javax.swing.SwingUtilities.isLeftMouseButton(event)
				|| !client.isKeyPressed(net.runelite.api.KeyCode.KC_SHIFT)
				|| client.isMenuOpen()
				|| dialog.isOpen()
				|| !follower.isSpawned()
				|| !follower.isUnderMouse(new net.runelite.api.Point(event.getX(), event.getY())))
			{
				return event;
			}

			crossPoint = event.getPoint();
			crossStartMs = System.currentTimeMillis();
			startTalking();
			event.consume();
			return event;
		}
	};

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
	private com.follower.speech.DialogLoader dialogLoader;

	@Inject
	private FollowerOverlay overlay;

	@Inject
	private net.runelite.client.game.ItemManager itemManager;

	@Inject
	private net.runelite.client.ui.ClientToolbar clientToolbar;


	private com.follower.ui.FollowerPanel panel;
	private net.runelite.client.ui.NavigationButton navButton;
	private com.follower.ui.PhrasesDialog gearPhrasesDialog;
	private com.follower.ui.PhrasesDialog areaPhrasesDialog;
	private com.follower.ui.PhrasesDialog bossPhrasesDialog;
	private com.follower.ui.PhrasesDialog statusPhrasesDialog;
	private com.follower.ui.PhrasesDialog questPhrasesDialog;
	private com.follower.ui.PhrasesDialog errandPhrasesDialog;
	private com.follower.ui.PhrasesDialog combatPhrasesDialog;
	private com.follower.ui.DialogsDialog dialogsDialog;

	@Inject
	private com.google.gson.Gson gson;

	@Inject
	private com.follower.appearance.OutfitProfileStore profileStore;

	private com.follower.follower.ErrandController errands;

	/** The rule-level writing gesture; null until startUp builds it. */
	private com.follower.follower.PropFlourish flourish;

	/** Stands the follower clear of a fight; see SpectateController. */
	private com.follower.follower.SpectateController spectate;

	private Path dataDir;
	private WorldPoint lastPlayerTile;
	private int lastRegionId = -1;
	private int reloadPollTicks;
	private int spawnDelayTicks;
	private boolean rebuildQueued;

	/**
	 * LOGGED_IN fires after EVERY map chunk reload, not just at login - running
	 * across a region boundary goes LOADING -> LOGGED_IN too. Set on the real
	 * logged-out states and consumed once, so the LOGIN trigger and the edge
	 * priming only happen when the player actually logged in, not every time
	 * they jog into a freshly loaded part of the map.
	 */
	private boolean freshLogin;

	/**
	 * Set while a world hop is in flight, so coming back is told apart from
	 * arriving. A hop needs the edges primed - the gear worn and the place
	 * stood in are the same as a moment ago, and without priming every one of
	 * them reads as a fresh rising edge and the follower greets the new world
	 * with a monologue.
	 */
	private boolean hopped;
	private boolean watchAnimations;

	/** ::follower watch all - also report graphics played by other players. */
	private boolean watchOthers;

	/** ::follower chatwatch - print chat messages so a rule can quote them exactly. */
	private boolean watchChat;

	/**
	 * ::follower scan - a tick-by-tick timeline of every animation slot the
	 * player is using.
	 *
	 * <p>{@code watch} only reports the one-shot animation slot, because that is
	 * what AnimationChanged fires for. A pose - standing, walking, or SITTING -
	 * lives in a different slot that raises no event at all, so an emote that
	 * settles into a held position is invisible to it. This samples all of them
	 * every tick and records what changed, which is the only way to see a
	 * sequence's transitions in the order they actually happen.
	 */
	private int scanTicksLeft;
	private int scanAnimation = -2;
	private int scanPose = -2;
	private int scanIdlePose = -2;
	private int scanGraphic = -2;
	private String scanCombat = "";
	private int scanStartTick;
	private final List<String> scanTimeline = new ArrayList<>();

	private void tickScan()
	{
		if (scanTicksLeft <= 0)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		int animation = local.getAnimation();
		int pose = local.getPoseAnimation();
		int idlePose = local.getIdlePoseAnimation();
		net.runelite.api.ActorSpotAnim spot = latestSpotAnim(local);
		int graphic = spot == null ? -1 : spot.getId();
		int at = client.getTickCount() - scanStartTick;

		// Every input the combat check reads, spelled out. refreshCombat wants
		// an NPC, a combat level above zero and a health ratio that is not
		// zero; when it does not fire there is no way to tell which of the
		// three failed without seeing all of them.
		net.runelite.api.Actor target = local.getInteracting();
		String combat = target == null
			? "interacting = none"
			: "interacting = " + target.getName()
				+ " [" + target.getClass().getSimpleName() + "]"
				+ " isNPC=" + (target instanceof NPC)
				+ " level=" + target.getCombatLevel()
				+ " healthRatio=" + target.getHealthRatio();
		if (!combat.equals(scanCombat))
		{
			scanCombat = combat;
			String line = "t+" + at + "  " + combat;
			scanTimeline.add(line);
			log.info("SCAN {}", line);
		}

		if (animation != scanAnimation)
		{
			scanAnimation = animation;
			record(at, "animation", animation);
		}
		if (pose != scanPose)
		{
			scanPose = pose;
			record(at, "pose", pose);
		}
		if (idlePose != scanIdlePose)
		{
			scanIdlePose = idlePose;
			record(at, "idlePose", idlePose);
		}
		if (graphic != scanGraphic)
		{
			scanGraphic = graphic;
			record(at, "graphic", graphic);
		}

		if (--scanTicksLeft == 0)
		{
			log.info("SCAN complete, {} changes:\n  {}",
				scanTimeline.size(), String.join("\n  ", scanTimeline));
			sendStatus("Scan finished - " + scanTimeline.size()
				+ " changes recorded to the log.");
		}
	}

	private void record(int tick, String slot, int value)
	{
		String line = "t+" + tick + "  " + slot + " = " + value;
		scanTimeline.add(line);
		log.info("SCAN {}", line);
	}
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
		gameFontRepository.load(dataDir);
		ruleLoader.initialise(dataDir);
		journal.initialise(dataDir);
		journal.setRegionSource(() -> speechEngine.getContext().getRegionId());
		journal.setEnabled(config.transcriptOn());
		dialogLoader.initialise(dataDir);
		stanceLibrary.load(dataDir);
		paletteHarvest.load(dataDir);
		wrapTrimStore.load(dataDir, follower);
		profileStore.load(dataDir);

		speechEngine.setSink(this::speak);
		applyConfig();
		loadExactPalette();
		renderCallbacks.register(thrallHider);
		// Anything the dump files didn't provide is parsed from the client's
		// own cache; retried from the login states until the indexes exist.
		clientThread.invokeLater(this::ensureCatalogues);
		// The same transient-prop path the dev commands use: overlaid on the
		// outfit at compose time, never persisted. Shared by the errand and
		// the rule-level flourish; both run on the client thread.
		com.follower.follower.ErrandController.Hands hands =
			new com.follower.follower.ErrandController.Hands()
			{
				@Override
				public void hold(int itemId)
				{
					KitType slot = resolveSlot(itemId);
					if (slot == null)
					{
						log.warn("Prop {} has no wearable slot", itemId);
						return;
					}
					propSlot = slot;
					propItemId = itemId;
					rebuildFollower();
				}

				@Override
				public void release()
				{
					// Idempotent: the reset and abort paths call this with
					// nothing held, including once at construction time.
					if (propSlot == null)
					{
						return;
					}
					propSlot = null;
					propItemId = 0;
					rebuildFollower();
				}
			};
		errands = new com.follower.follower.ErrandController(client, follower, config,
			speechEngine::dispatch, spotAnimRepository,
			() -> dialog.isOpen() || follower.isNpcSlaved(), hands);
		flourish = new com.follower.follower.PropFlourish(follower, hands);
		spectate = new com.follower.follower.SpectateController(client, follower, config,
			speechEngine.getContext(), spotAnimRepository, speechEngine::dispatch,
			this::setSpectateDisarmed);

		// On a fresh client boot the LOGIN_SCREEN transition can happen before
		// this plugin subscribes, so the first login would not read as fresh.
		// Enabled mid-session instead, the next LOGGED_IN is a chunk reload,
		// not a login - so only pre-arm when not already in the world.
		freshLogin = client.getGameState() != GameState.LOGGED_IN;

		overlayManager.add(overlay);
		overlayManager.add(followDebugOverlay);
		overlayManager.add(dialog);
		overlayManager.add(crossOverlay);
		dialog.register();
		mouseManager.registerMouseListener(shiftClickAdapter);
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
		journal.flush();
		renderCallbacks.unregister(thrallHider);
		resetThrallQuietly();
		// Before the engine is reset: an orderly shutdown is the one chance to
		// save what the last few ticks counted.
		writeCounters();
		// The engine is a singleton that survives plugin toggles; a stale sink
		// would keep speaking into a dead plugin instance.
		speechEngine.setSink(null);
		speechEngine.reset();
		overlayManager.remove(overlay);
		overlayManager.remove(followDebugOverlay);
		overlayManager.remove(dialog);
		overlayManager.remove(crossOverlay);
		dialog.unregister();
		mouseManager.unregisterMouseListener(shiftClickAdapter);
		dialog.close();
		overlay.clear();
		speechQueue.clear();
		speakingUntilMs = 0;
		if (flourish != null)
		{
			flourish.abort();
		}

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
			panel = null;
		}
		if (dialogsDialog != null)
		{
			dialogsDialog.dispose();
			dialogsDialog = null;
		}
		if (gearPhrasesDialog != null)
		{
			gearPhrasesDialog.dispose();
			gearPhrasesDialog = null;
		}
		if (areaPhrasesDialog != null)
		{
			areaPhrasesDialog.dispose();
			areaPhrasesDialog = null;
		}
		if (bossPhrasesDialog != null)
		{
			bossPhrasesDialog.dispose();
			bossPhrasesDialog = null;
		}
		if (statusPhrasesDialog != null)
		{
			statusPhrasesDialog.dispose();
			statusPhrasesDialog = null;
		}
		if (questPhrasesDialog != null)
		{
			questPhrasesDialog.dispose();
			questPhrasesDialog = null;
		}
		if (errandPhrasesDialog != null)
		{
			errandPhrasesDialog.dispose();
			errandPhrasesDialog = null;
		}
		if (combatPhrasesDialog != null)
		{
			combatPhrasesDialog.dispose();
			combatPhrasesDialog = null;
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
		resetTransientState();
	}

	/**
	 * Clears the flags that describe what the follower is in the MIDDLE of.
	 *
	 * <p>RuneLite builds a plugin once and calls startUp/shutDown on every
	 * toggle, so instance fields outlive a disable. Anything latched here
	 * survives into the next session and cannot be cleared by the thing that
	 * would normally clear it, because that thing is over: switching the plugin
	 * off mid-emote left {@code emoteDisarmed} set, and the follower came back
	 * holding nothing for the rest of the client's life. The same trap holds for
	 * the spectating disarm, the emote hold that pins the follower in place, and
	 * the rest and wander timers.
	 *
	 * <p>The diagnostics go too: a scan or trace left running against a plugin
	 * that is no longer loaded should not resume when it is.
	 */
	private void resetTransientState()
	{
		spectateDisarmed = false;
		emoteDisarmed = false;
		emoteHold = false;
		mirroredPose = 0;
		wasThieving = false;
		resting = false;
		wandered = false;
		wanderCountdown = 0;
		damagedByPlayer.clear();
		mirrorGraphicsUntilTick = -1;
		hoveredThisTick = false;
		hoverTicks = 0;
		hopped = false;
		strandedLandingTicks = 0;

		scanTicksLeft = 0;
		animTraceRemaining = 0;
		poseProbeTicks = 0;
		autoHarvestTicks = 0;
		watchAnimations = false;
		watchOthers = false;
		watchChat = false;
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
		panel.setOnEditPhrases(this::openGearPhrasesDialog);
		panel.setOnEditLocations(this::openAreaPhrasesDialog);
		panel.setOnEditBosses(this::openBossPhrasesDialog);
		panel.setOnEditStatuses(this::openStatusPhrasesDialog);
		panel.setOnEditCombat(this::openCombatPhrasesDialog);
		panel.setOnEditDialogs(this::openDialogsDialog);
		panel.setOnEditQuests(this::openQuestPhrasesDialog);
		panel.setOnEditErrands(this::openErrandPhrasesDialog);
		panel.setOnProfileLoad(this::loadOutfitProfile);
		panel.setOnProfileSave(this::saveOutfitProfile);
		panel.setOnProfileDelete(this::deleteOutfitProfile);
		restoreActiveProfile();

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
		follower.setOnEmoteStateChanged(this::updateEmoteDisarm);
		stanceLibrary.setStyleSource(this::weaponUsage);
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
			slotIndex = index;
			if (panel != null)
			{
				panel.setSlotIndex(index);
			}
		});
	}

	/** Item id -> equipment slot, kept so the stance audit can walk every weapon. */
	private Map<Integer, KitType> slotIndex = java.util.Collections.emptyMap();

	/**
	 * The sit emote's own stand-up, 2.84s, captured alongside the sit itself.
	 * Its partner rather than a generic getting-up animation, so the two read
	 * as one gesture the way they do when a player performs it.
	 */
	private static final int REST_STAND_UP = 10053;

	/** Matches idle-long's five minutes, so the sitting and the "we live here
	 * now" lines arrive as one mood rather than two systems coinciding. */
	private static final int REST_AFTER_TICKS = 500;

	private boolean resting;

	/**
	 * Sits the follower down once the player has been still a long while, and
	 * stands it back up the moment anything happens.
	 *
	 * <p>Runs on the game tick, which is the client thread, so the follower is
	 * driven directly. Everything that owns the follower's feet - thrall mode,
	 * errands, spectating, an open dialog - suppresses it, and the idle
	 * counter resets on any player movement or animation, so combat can never
	 * meet a seated follower.
	 */
	private void updateRest()
	{
		boolean busy = thrallNpc != null
			|| (errands != null && errands.isBusy())
			|| (spectate != null && spectate.isSpectating())
			|| dialog.isOpen();
		boolean wantRest = config.restWhenIdle()
			&& config.restAnimation() > 0
			&& !busy
			&& follower.isSpawned()
			&& follower.isSettled()
			&& speechEngine.getContext().getIdleTicks() >= REST_AFTER_TICKS;
		if (wantRest == resting)
		{
			return;
		}
		resting = wantRest;
		if (wantRest)
		{
			follower.setPoseOverride(config.restAnimation());
		}
		else
		{
			follower.setPoseOverride(0);
			// Cancelled harmlessly by the next movement frame if the follower
			// is already walking, which is the usual reason rest ended.
			follower.playAnimation(REST_STAND_UP);
		}
	}

	/**
	 * Player standing about this long before the follower starts drifting.
	 *
	 * <p>Was thirty seconds, and play testing read that as the follower
	 * getting restless while the player was merely sorting a bank tab. Most
	 * ordinary pauses are under a minute; the drift should start only once
	 * the stop has proven to be a stay.
	 */
	private static final int WANDER_AFTER_TICKS = 80;

	/** Ticks between drifts, picked fresh each time so it never reads as a metronome. */
	private static final int WANDER_MIN_GAP = 25;
	private static final int WANDER_MAX_GAP = 70;

	/** How far from the player a drift may take it. */
	private static final int WANDER_RADIUS = 4;

	/**
	 * How far a drift may take it, scaled by mood.
	 *
	 * <p>This is the honest version of "follows closer when it is unhappy". The
	 * follow distance itself is not touched: following is a one-tile-behind
	 * path model tuned over trace sessions, and the one attempt at re-modelling
	 * it was reverted for feel rather than for numbers. Wandering is the only
	 * time the follower is not already at your heel, so it is the only place
	 * where distance is the follower's choice to make.
	 */
	private int wanderRadius()
	{
		String band = speechEngine.getContext().getMoodBand();
		if ("low".equals(band) || "down".equals(band))
		{
			// Staying close is what low looks like from the outside.
			return Math.max(2, WANDER_RADIUS - 2);
		}
		if ("high".equals(band))
		{
			return WANDER_RADIUS + 2;
		}
		return WANDER_RADIUS;
	}

	private int wanderCountdown;
	private boolean wandered;

	/**
	 * Lets the follower drift about while the player stands still.
	 *
	 * <p>A companion planted on one tile for minutes reads as furniture. Real
	 * people shift about while they wait, so the follower picks somewhere
	 * nearby every twenty to forty seconds and walks over.
	 *
	 * <p>Bounded at both ends. It starts only after the player has genuinely
	 * settled, and stops once the five-minute rest is due, so the two never
	 * argue over the feet - drifting and then sitting down reads as winding
	 * down, which is the intent.
	 *
	 * <p>Anything that owns the follower releases it: the moment wandering is
	 * no longer allowed - the player moves, a fight starts, an errand begins -
	 * the posed destination is dropped and normal following resumes. Without
	 * that the follower would stay parked where it drifted, since a stay
	 * persists by design.
	 */
	private void updateWander()
	{
		int idle = speechEngine.getContext().getIdleTicks();
		boolean busy = thrallNpc != null
			|| (errands != null && errands.isBusy())
			|| (spectate != null && spectate.isSpectating())
			|| dialog.isOpen()
			|| emoteHold
			|| resting;

		// Thieving wanders too, and does not wait to be invited. The idle gate
		// exists so the follower does not drift while the player is busy, but
		// picking pockets is the one activity where being underfoot is the
		// problem - the player is animating constantly, so the idle counter
		// never climbs and the follower would stand there for the whole run.
		boolean thieving = speechEngine.getContext().isInThievingSession();
		boolean canWander = config.wanderWhenIdle()
			&& !busy
			&& follower.isSpawned()
			&& (thieving
				|| (idle >= WANDER_AFTER_TICKS && idle < REST_AFTER_TICKS));

		if (!canWander)
		{
			if (wandered)
			{
				follower.resumeFollowing();
				wandered = false;
			}
			wanderCountdown = 0;
			return;
		}

		if (--wanderCountdown > 0)
		{
			return;
		}
		wanderCountdown = WANDER_MIN_GAP
			+ java.util.concurrent.ThreadLocalRandom.current()
				.nextInt(WANDER_MAX_GAP - WANDER_MIN_GAP);

		Player local = client.getLocalPlayer();
		if (local == null || local.getWorldLocation() == null)
		{
			return;
		}
		WorldPoint from = local.getWorldLocation();
		java.util.concurrent.ThreadLocalRandom random =
			java.util.concurrent.ThreadLocalRandom.current();

		// Something to go and look at, if there is anything. A drift toward a
		// chicken reads as curiosity where the same walk to an empty tile reads
		// as pathing - and that is worth having while thieving too, where the
		// alternative is standing off at a distance staring at the player.
		//
		// The only difference is the near edge: while thieving it must not pick
		// something underfoot, or it would inspect its way straight back into
		// the way.
		int nearest = thieving ? THIEVING_KEEP_MIN : 0;
		if (driftToward(pickDistraction(local, from, nearest)))
		{
			return;
		}

		// Nothing far enough away to be worth looking at, so just keep clear.
		if (thieving)
		{
			driftAwayFrom(from, random);
			return;
		}

		int radius = wanderRadius();

		// A few attempts rather than one: stayAt refuses an unreachable tile,
		// and indoors most of the ring is wall.
		for (int attempt = 0; attempt < 6; attempt++)
		{
			int dx = random.nextInt(-radius, radius + 1);
			int dy = random.nextInt(-radius, radius + 1);
			if (dx == 0 && dy == 0)
			{
				continue;
			}
			WorldPoint target = new WorldPoint(
				from.getX() + dx, from.getY() + dy, from.getPlane());
			if (follower.stayAt(target))
			{
				wandered = true;
				follower.setStayFaceTile(null);
				return;
			}
		}
	}

	/**
	 * How far off the follower keeps while the player works a pocket.
	 *
	 * <p>Far enough to be out from underfoot and out of the way of the click
	 * that matters, near enough to still read as waiting for you rather than
	 * having left.
	 */
	private static final int THIEVING_KEEP_MIN = 4;
	private static final int THIEVING_KEEP_MAX = 7;

	/**
	 * Picks somewhere in a ring around the player and goes there.
	 *
	 * <p>A ring rather than a disc: the whole point is not to end up next to
	 * them again, and a plain random offset would keep choosing tiles that are
	 * technically a drift and practically underfoot.
	 */
	private void driftAwayFrom(WorldPoint from,
		java.util.concurrent.ThreadLocalRandom random)
	{
		for (int attempt = 0; attempt < 10; attempt++)
		{
			double angle = random.nextDouble() * Math.PI * 2;
			int distance = random.nextInt(THIEVING_KEEP_MIN, THIEVING_KEEP_MAX + 1);
			int dx = (int) Math.round(Math.cos(angle) * distance);
			int dy = (int) Math.round(Math.sin(angle) * distance);
			if (dx == 0 && dy == 0)
			{
				continue;
			}

			WorldPoint target = new WorldPoint(
				from.getX() + dx, from.getY() + dy, from.getPlane());
			if (follower.stayAt(target))
			{
				wandered = true;
				follower.setStayFaceTile(null);
				return;
			}
		}
	}

	/**
	 * How far to look for something worth drifting over to.
	 *
	 * <p>Wider than the plain wander radius, because the follower walks to a
	 * tile BESIDE the thing and a nearer ring would only ever find what is
	 * already underfoot. Not much wider though: the walk has to read as going
	 * to look at something rather than as wandering off, and the follower is
	 * still meant to be waiting for you.
	 */
	private static final int DISTRACTION_RADIUS = 6;

	/**
	 * Something nearby worth a look, or null if the place is empty.
	 *
	 * <p>Chosen at random from everything in range rather than by any ranking.
	 * A follower that always makes for the pet is as predictable as one that
	 * always picks an empty tile; what sells it is that the choice is its own.
	 *
	 * <p>Only NPCs and other players, which the client already lists. Scene
	 * objects would need the tile walk the errands do, and are the thing
	 * errands are already for.
	 */
	private WorldPoint pickDistraction(Player local, WorldPoint from, int nearest)
	{
		List<WorldPoint> candidates = new ArrayList<>();

		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			// The thrall is the follower's own body in thrall mode, and
			// wandering is suppressed then anyway.
			if (npc == null || npc == thrallNpc)
			{
				continue;
			}
			addIfNear(candidates, npc.getWorldLocation(), from, nearest);
		}

		for (Player other : client.getTopLevelWorldView().players())
		{
			if (other == null || other == local)
			{
				continue;
			}
			addIfNear(candidates, other.getWorldLocation(), from, nearest);
		}

		return candidates.isEmpty() ? null
			: candidates.get(java.util.concurrent.ThreadLocalRandom.current()
				.nextInt(candidates.size()));
	}

	/**
	 * @param nearest how close a thing may be and still be worth walking to.
	 * Zero normally; while thieving it is the distance the follower is keeping,
	 * so that going to look at something cannot bring it back underfoot.
	 */
	private void addIfNear(List<WorldPoint> into, WorldPoint at, WorldPoint from, int nearest)
	{
		if (at == null || at.getPlane() != from.getPlane())
		{
			return;
		}
		int distance = at.distanceTo(from);
		if (distance <= DISTRACTION_RADIUS && distance >= nearest)
		{
			into.add(at);
		}
	}

	/**
	 * Walks to a tile BESIDE the thing and turns to face it.
	 *
	 * <p>Beside rather than onto: standing inside a cat is the same mistake the
	 * errands avoid, and the follower would be hidden by the overlap rule for
	 * anything person-sized anyway. Facing it is what makes the walk read as
	 * having gone to look at something rather than having stopped near it.
	 *
	 * @return true if it set off
	 */
	private boolean driftToward(WorldPoint thing)
	{
		if (thing == null)
		{
			return false;
		}

		int[][] beside = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
		List<int[]> order = new ArrayList<>(java.util.Arrays.asList(beside));
		java.util.Collections.shuffle(order);

		for (int[] offset : order)
		{
			WorldPoint spot = new WorldPoint(
				thing.getX() + offset[0], thing.getY() + offset[1], thing.getPlane());
			if (follower.stayAt(spot))
			{
				follower.setStayFaceTile(thing);
				wandered = true;
				return true;
			}
		}
		return false;
	}

	/**
	 * Works out how long the follower went without seeing the player, and starts
	 * the clock again.
	 *
	 * <p>Stored in the config rather than a file of its own: it is one number,
	 * RuneLite already persists config per profile, and a whole file for a long
	 * would be more machinery than the fact deserves.
	 *
	 * <p>Kept fresh on a timer as well as here, because a client that crashes
	 * or is killed never gets to write anything on the way out - without that,
	 * every crash would look like an absence of however long the session ran.
	 */
	private void readTimeAway()
	{
		long now = System.currentTimeMillis();
		String stored = config.lastSeenMs();
		long minutes = -1;
		if (stored != null && !stored.isEmpty())
		{
			try
			{
				long then = Long.parseLong(stored.trim());
				// A stored time in the future is a clock change, not an absence.
				minutes = then > now ? -1 : (now - then) / 60000L;
			}
			catch (NumberFormatException e)
			{
				log.debug("last seen was not a number: {}", stored);
			}
		}

		speechEngine.getContext().setMinutesAway(minutes);
		log.debug("Away for {} minutes", minutes);
		touchLastSeen();
	}

	private void touchLastSeen()
	{
		configManager.setConfiguration(FollowerConfig.GROUP, "lastSeenMs",
			Long.toString(System.currentTimeMillis()));
	}

	/**
	 * What the follower has counted, kept between sessions.
	 *
	 * <p>The counts themselves are the point of the feature: "your fiftieth"
	 * means nothing if the fifty were all this afternoon, and everything if
	 * they were the fifty since you met. A session-scoped tally is a scoreboard;
	 * this is a memory.
	 *
	 * <p>One config value holding one JSON object, for the same reason the
	 * last-seen stamp is a config value: RuneLite already persists config per
	 * profile, and a file of our own would be more machinery than this deserves.
	 */
	private static final class SavedCounters
	{
		java.util.Map<String, Integer> tallies;
		java.util.Map<String, Integer> records;
		int sessions;

		/** The incident the follower is still thinking about, and how to say it. */
		String incidentKey;
		String incidentPhrase;
		int incidentCount;

		/**
		 * Where the last death happened. Session-scoped memory made
		 * nearDeathSpot a thing that could only fire on the walk back; kept
		 * between sessions it becomes an anniversary, which is a much better
		 * line - "this is the spot" six weeks later.
		 */
		int deathX;
		int deathY;
		int deathPlane = -1;

		/**
		 * What each place has come to mean, and the one thing worth bringing up
		 * about it. Keyed by region id. Earned rather than rolled, so unlike the
		 * traits blob this one is never regenerated - losing it would have the
		 * follower forget which places it has reason to feel anything about.
		 */
		java.util.Map<Integer, Integer> placeScores;
		java.util.Map<Integer, String> placeMemories;

		/**
		 * Ids of the one-time lines already said. Small and slow-growing: there
		 * are only ever as many entries as there are firsts worth marking.
		 */
		java.util.List<String> spokenOnce;

		/**
		 * The day the follower first met this player, as an epoch day. Written
		 * once and never again: it is the only thing here that would be a lie
		 * if it were ever recalculated.
		 */
		long metOnDay;

		/** What the player was wearing then, so an upgrade has something to beat. */
		int metWearingValue = -1;
	}

	/**
	 * How many counters are kept. Every distinct NPC name the player has ever
	 * killed earns an entry, so this grows for as long as the plugin is used;
	 * without a bound it would grow into the config file forever. The rarest
	 * counts go first, since a count of one is the least likely to be the
	 * subject of a milestone rule.
	 */
	private static final int MAX_COUNTERS = 300;

	private void readCounters()
	{
		metWearingValue = restoreMemory(speechEngine.getContext(), config.counters(),
			gson, metWearingValue);
	}

	/**
	 * Puts a saved blob back into a context, and returns the gear value the
	 * follower first met the player wearing.
	 *
	 * <p>Separated from the config plumbing so the round trip can be tested
	 * without a client. Nothing else in the plugin verifies that what is
	 * written comes back, and the cost of a field written but never read is
	 * silent: the follower simply forgets one thing forever, and the only
	 * symptom is a line that repeats every restart.
	 *
	 * <p>The saved shape stays private - this seam is a string in and a string
	 * out, which is what actually crosses the disk.
	 */
	static int restoreMemory(TriggerContext context, String stored,
		com.google.gson.Gson gson, int fallbackMetWearing)
	{
		if (stored == null || stored.isEmpty())
		{
			return fallbackMetWearing;
		}
		try
		{
			SavedCounters saved = gson.fromJson(stored, SavedCounters.class);
			if (saved == null)
			{
				return fallbackMetWearing;
			}
			context.restoreCounters(saved.tallies, saved.records);
			context.setSessionCount(saved.sessions);
			context.restoreIncident(saved.incidentKey, saved.incidentPhrase,
				saved.incidentCount);
			context.restorePlaces(saved.placeScores, saved.placeMemories);
			context.restoreSpokenOnce(saved.spokenOnce);
			context.setMetOnDay(saved.metOnDay);
			context.setMetWearingValue(saved.metWearingValue);
			if (saved.deathPlane >= 0)
			{
				context.restoreDeathSpot(new WorldPoint(
					saved.deathX, saved.deathY, saved.deathPlane));
			}
			log.debug("Restored {} tallies, {} records, session {}",
				saved.tallies == null ? 0 : saved.tallies.size(),
				saved.records == null ? 0 : saved.records.size(),
				saved.sessions);
			return saved.metWearingValue;
		}
		catch (com.google.gson.JsonSyntaxException e)
		{
			// A corrupt value costs the follower its memory, which is sad but
			// survivable; refusing to start over it would not be.
			log.warn("Stored counters were not readable, starting fresh", e);
			return fallbackMetWearing;
		}
	}

	/**
	 * Saves only if something was actually counted since the last save.
	 *
	 * <p>The blob is nine kilobytes of JSON at the cap, and this runs every
	 * hundred ticks for as long as the client is open. Most of those minutes
	 * contain no kill, no level and no death, and the longest-session record
	 * only moves on a day that beats every previous one - so most of those
	 * writes serialised and stored a value identical to the one already there.
	 */
	private void writeCountersIfChanged()
	{
		if (speechEngine.getContext().isCountersDirty())
		{
			writeCounters();
		}
	}

	private void writeCounters()
	{
		TriggerContext context = speechEngine.getContext();
		configManager.setConfiguration(FollowerConfig.GROUP, "counters",
			snapshotMemory(context, metWearingValue, gson));
		context.clearCountersDirty();
	}

	/** Everything the follower remembers, as it goes to disk. See {@link #restoreMemory}. */
	static String snapshotMemory(TriggerContext context, int metWearing,
		com.google.gson.Gson gson)
	{
		SavedCounters saved = new SavedCounters();
		saved.tallies = trimCounters(context.getTallies());
		saved.records = trimCounters(context.getRecords());
		saved.sessions = context.getSessionCount();
		saved.incidentKey = context.getIncidentKey();
		saved.incidentPhrase = context.getIncidentPhrase();
		saved.incidentCount = context.getIncidentCount();
		saved.placeScores = context.getPlaceScores();
		saved.placeMemories = context.getPlaceMemories();
		saved.spokenOnce = new java.util.ArrayList<>(context.getSpokenOnce());
		saved.metOnDay = context.getMetOnDay();
		saved.metWearingValue = metWearing;
		WorldPoint died = context.getDeathLocation();
		if (died != null)
		{
			saved.deathX = died.getX();
			saved.deathY = died.getY();
			saved.deathPlane = died.getPlane();
		}
		return gson.toJson(saved);
	}

	/**
	 * What this follower likes and dislikes. Rolled once, then kept for good.
	 */
	private static final class SavedTraits
	{
		java.util.List<Integer> liked;
		java.util.List<Integer> disliked;
	}

	/** How many places a follower gets to be fond of, and to grumble about. */
	private static final int LIKED_PLACES = 3;
	private static final int DISLIKED_PLACES = 2;

	/**
	 * Gives the follower its taste, rolling it the first time and reading it
	 * back every time after.
	 *
	 * <p>The pool is the regions the RULE SET already has opinions about, which
	 * means it maintains itself: every area rule added later widens what a
	 * follower can come to love, and no separate list can fall out of date
	 * against the one that matters.
	 */
	private void readTraits()
	{
		String stored = config.traits();
		if (stored != null && !stored.isEmpty())
		{
			try
			{
				SavedTraits saved = gson.fromJson(stored, SavedTraits.class);
				if (saved != null)
				{
					speechEngine.getContext().setTraits(
						new java.util.HashSet<>(
							saved.liked == null ? java.util.Collections.emptyList() : saved.liked),
						new java.util.HashSet<>(
							saved.disliked == null ? java.util.Collections.emptyList() : saved.disliked));
					return;
				}
			}
			catch (com.google.gson.JsonSyntaxException e)
			{
				log.warn("Stored traits were not readable, rolling again", e);
			}
		}

		java.util.Set<Integer> pool = new java.util.TreeSet<>();
		for (SpeechRule rule : ruleLoader.getRules())
		{
			if (rule.when != null)
			{
				rule.when.collectRegions(pool);
			}
		}
		if (pool.size() < LIKED_PLACES + DISLIKED_PLACES)
		{
			// No rules loaded yet, or a rule set with no places in it. Leave it
			// unrolled rather than writing out a taste of one place: the next
			// login will try again with a full set.
			log.debug("Not enough places to roll traits from ({})", pool.size());
			return;
		}

		java.util.List<Integer> shuffled = new java.util.ArrayList<>(pool);
		java.util.Collections.shuffle(shuffled);
		SavedTraits rolled = new SavedTraits();
		rolled.liked = new java.util.ArrayList<>(shuffled.subList(0, LIKED_PLACES));
		rolled.disliked = new java.util.ArrayList<>(
			shuffled.subList(LIKED_PLACES, LIKED_PLACES + DISLIKED_PLACES));

		speechEngine.getContext().setTraits(
			new java.util.HashSet<>(rolled.liked), new java.util.HashSet<>(rolled.disliked));
		configManager.setConfiguration(FollowerConfig.GROUP, "traits", gson.toJson(rolled));
		log.debug("Rolled traits: likes {}, dislikes {}", rolled.liked, rolled.disliked);
	}

	/**
	 * What the player was wearing the day the follower met them, in gp.
	 * -1 until it has been measured, which cannot happen until the composition
	 * is readable - several ticks after login on a slow world.
	 */
	private int metWearingValue = -1;

	/** The worn set last priced, so the prices are not looked up every tick. */
	private java.util.Set<Integer> lastPricedGear = java.util.Collections.emptySet();

	/**
	 * Puts a number on what the player has on, and remembers the first one.
	 *
	 * <p>Only when the worn set actually changes. Item prices come from a
	 * lookup per item and the gear is the same eleven ids tick after tick, so
	 * pricing on the heartbeat would be the most expensive thing in here for no
	 * information at all.
	 *
	 * <p>The first-meeting figure cannot be taken at login: the composition is
	 * not readable for a few ticks, and a follower that recorded zero would
	 * spend the rest of its life claiming you were naked when you met.
	 */
	private void priceWhatYouAreWearing()
	{
		TriggerContext context = speechEngine.getContext();
		java.util.Set<Integer> worn = context.getEquippedItems();
		if (worn.equals(lastPricedGear))
		{
			return;
		}
		lastPricedGear = new java.util.HashSet<>(worn);

		long total = 0;
		for (int id : worn)
		{
			total += Math.max(0, itemManager.getItemPrice(id));
		}
		int value = (int) Math.min(total, Integer.MAX_VALUE);
		context.setWornValue(value);

		if (metWearingValue < 0 && value > 0)
		{
			metWearingValue = value;
			context.setMetWearingValue(value);
			speechEngine.getContext().markCountersDirty();
			log.debug("First seen wearing {} gp of gear", value);
		}
	}

	/**
	 * Writes down the day, once, the first time this follower is ever run.
	 *
	 * <p>Existing players get today, which is a small lie and the only one
	 * available: the alternative is a follower that can never say how long it
	 * has known anybody because it was born before it started counting.
	 */
	private void noteFirstMeeting()
	{
		TriggerContext context = speechEngine.getContext();
		if (context.getMetOnDay() <= 0)
		{
			context.setMetOnDay(java.time.LocalDate.now().toEpochDay());
			log.debug("First meeting recorded as {}", java.time.LocalDate.now());
		}
	}

	private static java.util.Map<String, Integer> trimCounters(java.util.Map<String, Integer> counters)
	{
		// Today's counters are for today. Writing them out would have the
		// follower's summary open with yesterday's afternoon still in it.
		java.util.Map<String, Integer> lasting = new java.util.LinkedHashMap<>();
		counters.forEach((key, value) ->
		{
			if (!key.endsWith(TriggerContext.TODAY))
			{
				lasting.put(key, value);
			}
		});
		counters = lasting;

		if (counters.size() <= MAX_COUNTERS)
		{
			return counters;
		}
		return counters.entrySet().stream()
			.sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
			.limit(MAX_COUNTERS)
			.collect(java.util.stream.Collectors.toMap(
				java.util.Map.Entry::getKey, java.util.Map.Entry::getValue,
				(a, b) -> a, java.util.LinkedHashMap::new));
	}

	/**
	 * How long the follower may be walled off before it gives up and teleports.
	 *
	 * <p>The count only climbs while there is no route AT ALL - the pathfinder
	 * searches the whole loaded scene and settles for the closest reachable
	 * tile, so an empty answer means a sealed room or a locked door rather
	 * than a long way round. Five ticks is three seconds: long enough that
	 * walking briefly out of sight is not a teleport, short enough that the
	 * follower does not stand at a wall looking foolish.
	 */
	private static final int STRANDED_TELEPORT_TICKS = 5;

	/**
	 * The standard teleport cast and its landing, with the swirls that go with
	 * them - the same pair the errand return uses, measured from the cache:
	 * 714 casts at 1.58s, 715 lands at 1.56s, spotanims 111 out and 1299 back.
	 */
	private static final int TELEPORT_CAST_ANIMATION = 714;
	private static final int TELEPORT_ARRIVE_ANIMATION = 715;
	private static final int TELEPORT_CAST_SPOTANIM = 111;
	private static final int TELEPORT_ARRIVE_SPOTANIM = 1299;

	/** Ticks left before the stranded teleport lands, or 0 when none is in flight. */
	private int strandedLandingTicks;

	/**
	 * Teleports the follower to the player when there is genuinely no way to
	 * walk there.
	 *
	 * <p>It used to walk through the wall instead, which is the one thing a
	 * follower must never do: everything else about it is an illusion held up
	 * by obeying the same rules as everyone else, and a model sliding through
	 * stone drops the illusion completely.
	 *
	 * <p>Two stages, like the errand's way home, because a follower blinking
	 * from one side of a wall to the other is barely better. It casts where it
	 * is standing, then lands beside you three ticks later.
	 */
	private void updateStrandedTeleport()
	{
		if (strandedLandingTicks > 0)
		{
			if (--strandedLandingTicks == 0)
			{
				follower.teleportToPlayer();
				follower.playAnimation(TELEPORT_ARRIVE_ANIMATION);
				follower.playSpotAnim(spotAnimRepository.get(TELEPORT_ARRIVE_SPOTANIM), 92);
			}
			return;
		}

		// Anything that has deliberately put the follower somewhere else owns
		// it: a Stay, a Send, an errand, a possessed thrall. Being away from
		// the player is the point of all four, and none of them is stuck.
		if (!follower.isSpawned() || follower.isStaying() || follower.isPosed()
			|| follower.isNpcSlaved() || (errands != null && errands.isBusy()))
		{
			return;
		}

		if (follower.getStrandedTicks() < STRANDED_TELEPORT_TICKS)
		{
			return;
		}

		log.debug("Follower walled off for {} ticks; teleporting",
			follower.getStrandedTicks());
		follower.clearStranded();
		follower.playAnimation(TELEPORT_CAST_ANIMATION);
		follower.playSpotAnim(spotAnimRepository.get(TELEPORT_CAST_SPOTANIM));
		// Gone on the cast's last frame rather than standing there waiting for
		// the landing tick. The cast runs 1.58s and the landing is three ticks
		// out at 1.80s, so without this there is a fifth of a second of the
		// follower standing at the wall in its idle pose, mid-teleport, which
		// is exactly long enough to see. Cleared by teleportToPlayer on
		// arrival, so the duration only has to outlast the gap.
		follower.hideAfterEmote(1200);
		strandedLandingTicks = 3;
	}

	/** Ticks between writing the last-seen stamp: about a minute. */
	private static final int LAST_SEEN_TICKS = 100;

	private int ticksSinceLastSeen;

	/** Minutes this session has run, for the longest-session record. */
	private int sessionMinutes;

	/** Whether the longest-session record has already been remarked on today. */
	private boolean sessionRecordSaid;

	/** Set by the client tick when the mouse is over the follower; read once a game tick. */
	private boolean hoveredThisTick;

	/** Consecutive game ticks the mouse has rested on the follower. */
	private int hoverTicks;

	/**
	 * How often learned animation data is flushed to disk: one minute, so an
	 * unclean exit costs at most that much observation rather than a session.
	 */
	private static final int LEARNING_SAVE_TICKS = 100;

	private int ticksSinceLearningSave;

	/** Pushes the current outfit into the panel so it shows what is actually worn. */
	/** Lazily builds the item-message editor window and fronts it. */
	private void openGearPhrasesDialog()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (gearPhrasesDialog == null)
			{
				gearPhrasesDialog = new com.follower.ui.PhrasesDialog(gson, ruleLoader.getFile(),
					"gear", "Follower Buddy — Item messages",
					"One message per line. Edit, remove or add lines, untick a rule to"
						+ " silence it, then Save — changes reach the follower within a second.",
					false);
			}
			gearPhrasesDialog.open();
		});
	}

	/** Lazily builds the boss-message editor window and fronts it. */
	/** Lazily builds the combat-message editor window and fronts it. */
	private void openCombatPhrasesDialog()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (combatPhrasesDialog == null)
			{
				combatPhrasesDialog = new com.follower.ui.PhrasesDialog(gson, ruleLoader.getFile(),
					"combat", "Follower Buddy — Combat messages",
					"What the follower says while it stands clear and watches you fight."
						+ " One message per line. Edit, remove or add lines, untick a rule to"
						+ " silence it, then Save — changes reach the follower within a second.",
					false);
			}
			combatPhrasesDialog.open();
		});
	}

	private void openBossPhrasesDialog()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (bossPhrasesDialog == null)
			{
				bossPhrasesDialog = new com.follower.ui.PhrasesDialog(gson, ruleLoader.getFile(),
					"boss", "Follower Buddy — Boss messages",
					"One message per line. Each rule fires when that boss appears nearby."
						+ " Edit, remove or add lines, untick a rule to silence it, then Save"
						+ " — changes reach the follower within a second.",
					false);
			}
			bossPhrasesDialog.open();
		});
	}

	/** Lazily builds the status-message editor window and fronts it. */
	private void openStatusPhrasesDialog()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (statusPhrasesDialog == null)
			{
				statusPhrasesDialog = new com.follower.ui.PhrasesDialog(gson, ruleLoader.getFile(),
					"health,idle", "Follower Buddy — Status and idle messages",
					"One message per line. These react to your HP, prayer, poison, venom,"
						+ " skull and run energy, and include the idle chatter for when"
						+ " nothing is happening. Edit, remove or add lines, untick a rule"
						+ " to silence it, then Save — changes reach the follower within"
						+ " a second.",
					false);
			}
			statusPhrasesDialog.open();
		});
	}

	/** Lazily builds the quest-NPC-message editor window and fronts it. */
	private void openQuestPhrasesDialog()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (questPhrasesDialog == null)
			{
				questPhrasesDialog = new com.follower.ui.PhrasesDialog(gson, ruleLoader.getFile(),
					"quest", "Follower Buddy — Quest NPC messages",
					"One message per line. Each rule fires when that quest figure comes"
						+ " within a few tiles of you. Edit, remove or add lines, untick a"
						+ " rule to silence it, then Save — changes reach the follower"
						+ " within a second.",
					false);
			}
			questPhrasesDialog.open();
		});
	}

	/** Lazily builds the errand-message editor window and fronts it. */
	private void openErrandPhrasesDialog()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (errandPhrasesDialog == null)
			{
				errandPhrasesDialog = new com.follower.ui.PhrasesDialog(gson, ruleLoader.getFile(),
					"errand", "Follower Buddy — Errand messages",
					"One message per line. Each errand has a line for setting off and one"
						+ " for coming back. Edit, remove or add lines, untick a rule to"
						+ " silence it, then Save — changes reach the follower within a second.",
					false);
			}
			errandPhrasesDialog.open();
		});
	}

	/** Lazily builds the conversation editor window and fronts it. */
	private void openDialogsDialog()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (dialogsDialog == null)
			{
				dialogsDialog = new com.follower.ui.DialogsDialog(gson, dialogLoader.getFile());
			}
			dialogsDialog.open();
		});
	}

	/** Lazily builds the location-message editor window and fronts it. */
	private void openAreaPhrasesDialog()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (areaPhrasesDialog == null)
			{
				areaPhrasesDialog = new com.follower.ui.PhrasesDialog(gson, ruleLoader.getFile(),
					"area", "Follower Buddy — Location messages",
					"One message per line; region ids are editable and Add location makes a"
						+ " new place. Run ::follower where in game to print the region id you"
						+ " are standing in. Save reaches the follower within a second.",
					true);
			}
			areaPhrasesDialog.open();
		});
	}

	/**
	 * The profile the panel is currently showing. While one is selected every
	 * outfit change is written straight back to it, so the dropdown behaves
	 * like a live wardrobe rather than a pair of load/save buttons.
	 */
	private String activeProfile;

	// The follower is redressed through the same config write the panel uses,
	// so the existing config-changed path does the rebuild and panel sync.
	private void loadOutfitProfile(String name)
	{
		String outfit = profileStore.get(name);
		if (outfit == null)
		{
			return;
		}
		setActiveProfile(name.trim());
		configManager.setConfiguration(FollowerConfig.GROUP, "customOutfit", outfit);
		// A profile with an identical outfit writes no config change, so the
		// panel is refreshed here rather than relying on the config event.
		syncPanel();
		sendStatus("Wearing outfit profile '" + activeProfile + "'");
	}

	private void setActiveProfile(String name)
	{
		activeProfile = name;
		configManager.setConfiguration(FollowerConfig.GROUP, "activeProfile",
			name == null ? "" : name);
	}

	/**
	 * Picks the profile a session starts in: the one last worn, else the first
	 * the user made themselves, else the bare default body. Applied so the
	 * panel and the follower always agree with the dropdown on startup.
	 */
	private void restoreActiveProfile()
	{
		String stored = config.activeProfile();
		String wanted = stored != null && !stored.trim().isEmpty()
			&& profileStore.get(stored.trim()) != null
			? stored.trim()
			: profileStore.firstUserProfile();
		loadOutfitProfile(wanted);
		panel.setProfileNames(profileStore.names(), activeProfile);
	}

	private void saveOutfitProfile(String name)
	{
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		profileStore.put(name, config.customOutfit());
		setActiveProfile(name.trim());
		panel.setProfileNames(profileStore.names(), activeProfile);
		sendStatus("Outfit profile '" + activeProfile + "' created");
	}

	private void deleteOutfitProfile(String name)
	{
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		if (profileStore.isProtected(name))
		{
			sendStatus("'" + name.trim() + "' is a combat-style profile used by thrall mode"
				+ " and cannot be deleted. Edit it instead.");
			return;
		}
		if (!profileStore.remove(name))
		{
			sendStatus("No outfit profile named '" + name.trim() + "'");
			return;
		}
		if (name.trim().equals(activeProfile))
		{
			// Fall back to something real rather than leaving the dropdown
			// pointing at a profile that no longer exists.
			loadOutfitProfile(profileStore.firstUserProfile());
		}
		panel.setProfileNames(profileStore.names(), activeProfile);
		sendStatus("Outfit profile '" + name.trim() + "' deleted");
	}

	/**
	 * Writes the follower's current outfit back to the selected profile. The
	 * content comparison is what makes this safe to call from every panel
	 * sync: loading a profile leaves the two identical and writes nothing,
	 * while an actual edit differs and is saved.
	 */
	private void autoSaveActiveProfile()
	{
		if (activeProfile == null)
		{
			return;
		}
		String stored = profileStore.get(activeProfile);
		if (stored == null)
		{
			activeProfile = null;
			return;
		}
		String current = config.customOutfit();
		if (!stored.equals(current))
		{
			profileStore.put(activeProfile, current);
		}
	}

	private void syncPanel()
	{
		if (panel == null)
		{
			return;
		}
		// Every route to a changed outfit lands here, so this is the one place
		// the active profile needs keeping up to date.
		autoSaveActiveProfile();
		panel.setOutfit(OutfitParser.parse(config.customOutfit()));
		panel.setStatus(modelRepository.isLoaded()
			? modelRepository.getStatus()
			: "reading the game cache...");
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

			// Weapon animations can only be learned by watching a real player
			// hold one (measured: they are not in the cache as data). Say so
			// rather than let an unknown weapon silently stand unarmed.
			if (target == KitType.WEAPON && !stanceLibrary.knows(itemId))
			{
				// The hand-set command is developer-gated, so only offer it to
				// someone who has that switched on - otherwise this reads as
				// advice that does not work.
				sendStatus("No stances learned for that weapon yet - wield it once, or stand"
					+ " near someone who has."
					+ (config.developerMode()
						? " For one you cannot get hold of: ::follower stance " + itemId
							+ " <idle> <walk> <run> [attack]"
						: ""));
			}
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
				sendStatus("No styles available for " + part.name().toLowerCase(Locale.ROOT) + ".");
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

	/**
	 * How a weapon is used, read from its own equipment stats.
	 *
	 * <p>Used to stop the stance library borrowing an attack between weapons
	 * that merely look alike while carried. Bonuses are the game's own
	 * statement of what a weapon is for: whichever of the three it favours is
	 * how it is swung. Handedness comes along too, because style by itself put
	 * a thrown dart's animation on bows - both ranged, nothing alike.
	 *
	 * <p>A weapon with no equipment stats, or none that favour any style,
	 * reports UNKNOWN and the library declines to borrow rather than guess.
	 */
	private int weaponUsage(int itemId)
	{
		net.runelite.client.game.ItemStats stats = itemManager.getItemStats(itemId);
		if (stats == null || stats.getEquipment() == null)
		{
			return com.follower.follower.StanceLibrary.StyleSource.UNKNOWN;
		}
		net.runelite.client.game.ItemEquipmentStats equipment = stats.getEquipment();
		int melee = Math.max(equipment.getAstab(),
			Math.max(equipment.getAslash(), equipment.getAcrush()));
		int ranged = equipment.getArange();
		int magic = equipment.getAmagic();

		int style;
		if (ranged > melee && ranged >= magic)
		{
			style = com.follower.follower.StanceLibrary.StyleSource.RANGED;
		}
		else if (magic > melee && magic > ranged)
		{
			style = com.follower.follower.StanceLibrary.StyleSource.MAGIC;
		}
		else if (melee == 0 && ranged == 0 && magic == 0)
		{
			// A weapon with no offensive bonus at all says nothing about itself.
			return com.follower.follower.StanceLibrary.StyleSource.UNKNOWN;
		}
		else
		{
			style = com.follower.follower.StanceLibrary.StyleSource.MELEE;
		}
		return com.follower.follower.StanceLibrary.StyleSource.packed(
			style, equipment.isTwoHanded());
	}

	private KitType resolveSlot(int itemId)
	{
		net.runelite.client.game.ItemStats stats = itemManager.getItemStats(itemId);
		if (stats != null && stats.getEquipment() != null)
		{
			return slotFromEquipmentIndex(stats.getEquipment().getSlot());
		}

		// The stats database only knows items with combat stats. Purely
		// cosmetic wearables - a plain Scroll, the Book of portraiture - have
		// a worn model and a wear position in the cache but no stats row, so
		// they used to report as "not wearable" while rendering perfectly
		// well. Our own model dump carries the cache's wear position, and it
		// is the same dump the renderer builds from: if the item is in there,
		// the follower can wear it, by construction.
		ModelRepository.Entry entry = modelRepository.item(itemId);
		return entry != null && entry.wp1 != null
			? slotFromEquipmentIndex(entry.wp1)
			: null;
	}

	private static KitType slotFromEquipmentIndex(int slot)
	{
		// Equipment container indices are NOT contiguous and do NOT line up with
		// KitType ordinals (6, 8 and 11 are kit-only slots with no equippable item),
		// so map them explicitly rather than relying on ordinal coincidence.
		switch (slot)
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

				// Belt and braces for a client that skipped the login screen.
				ensureCatalogues();

				// Only on a REAL login - LOGGED_IN also follows every chunk reload.
				if (!freshLogin && hopped)
				{
					// Back from a hop. Nothing to greet and nothing to count -
					// only the baseline to re-take.
					hopped = false;
					speechEngine.primeEdgesOnNextTick();
				}

				if (freshLogin)
				{
					freshLogin = false;
					hopped = false;

					// How long it has been, worked out before the greeting so a
					// rule can choose a different one for a long absence.
					readTimeAway();

					// Everything the follower remembers, also before the
					// greeting: a session count is only worth mentioning as
					// part of hello, and the tallies want to be whole before
					// the first kill of the session lands on top of them.
					readCounters();
					speechEngine.getContext().clearDailyTallies();
					noteFirstMeeting();
					speechEngine.getContext().setNicknames(ruleLoader.getNicknames());
					readTraits();
					TriggerContext counted = speechEngine.getContext();
					counted.setSessionCount(counted.getSessionCount() + 1);
					sessionMinutes = 0;
					sessionRecordSaid = false;
					writeCounters();

					// The LOGIN trigger, so rules can greet: a delayTicks on the rule
					// puts the hello a couple of seconds AFTER the follower's own
					// spawn (which waits out spawnDelayTicks itself).
					speechEngine.dispatch(TriggerEvent.simple(TriggerEvent.Type.LOGIN));

					// The gear you logged in wearing and the place you logged in
					// standing are baseline, not news - the first ticks' evaluation
					// records edges without firing.
					speechEngine.primeEdgesOnNextTick();
				}

				// Sync the colour table to the brightness setting as it stands now;
				// the varp listener keeps it matched to slider changes from here.
				clientThread.invokeLater(() ->
				{
					int brightness = client.getVarpValue(BRIGHTNESS_VARP);
					com.follower.ui.GameColourTable.setBrightnessSetting(brightness);
					log.debug("Brightness at login: varp {} -> gamma {}",
						brightness, com.follower.ui.GameColourTable.getCurrentGamma());
				});
				break;

			case LOADING:
				// For classifying thrall despawns: NPCs dropped around a load
				// are scene shuffling, not deaths.
				ticksSinceLoading = 0;

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

			case LOGGING_IN:
				// Belt and braces for the flag below: LOGGING_IN always precedes
				// a real login and always fires after the plugin subscribed,
				// where LOGIN_SCREEN can predate a freshly booted client's
				// plugins entirely.
				freshLogin = true;
				break;

			case LOGIN_SCREEN:
			case CONNECTION_LOST:
				freshLogin = true;
				tearDownScene();
				// A session really ending. What the follower was feeling, what
				// it was hoping for and the question it was waiting on all
				// belonged to that session.
				speechEngine.reset();
				break;

			case HOPPING:
				// A hop is not a new day. Same player, same tile, same
				// follower, mid-everything - so the mood it is in, the want it
				// is holding you to and the tallies it is keeping all carry
				// across. Only the scene goes.
				//
				// Sharing the branch above cost a want every time somebody
				// hopped worlds on the way somewhere, which at the Grand
				// Exchange is most of the time. It also counted the hop as
				// another session together, so the hundredth day arrived early
				// and by accident.
				hopped = true;
				tearDownScene();
				speechEngine.resetForNewScene();
				break;

			default:
				break;
		}
	}

	/**
	 * Everything that belongs to the scene being left, whether the player is
	 * logging out or only changing worlds. Deliberately holds nothing about
	 * what the follower knows or feels - that is the difference between the two.
	 */
	private void tearDownScene()
	{
		ensureCatalogues();
		resetThrallQuietly();
		// Actor references from a scene that is going away.
		damagedByPlayer.clear();
		if (errands != null)
		{
			errands.reset();
		}
		if (flourish != null)
		{
			// Mid-gesture when the scene went: the scroll must not survive it.
			flourish.abort();
		}
		captureFallback.abort();
		follower.despawn();
		appearanceService.invalidate();
		overlay.clear();
		// Anything still waiting its turn belongs to the scene that just went;
		// it must not surface in the next one.
		speechQueue.clear();
		speakingUntilMs = 0;
		knownLevels.clear();
		lastPlayerTile = null;
		lastRegionId = -1;
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
			// The outfit can change from outside the panel - loading a profile,
			// the chat commands - and the equipment grid and body-kit rows have
			// to follow it, not just the follower.
			syncPanel();
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
		speechEngine.setGlobalCooldownMs(config.chattiness().getGapMs());
		speechEngine.setMuted(config.muted());
		speechEngine.setDisabledGroups(collectDisabledGroups());
		speechEngine.setOnSuppressed(journal::suppressed);
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
		if (!config.groupGear())
		{
			disabled.add("gear");
		}
		if (!config.groupQuest())
		{
			disabled.add("quest");
		}
		if (!config.groupCombat())
		{
			disabled.add("combat");
		}
		if (!config.groupMimic())
		{
			disabled.add("mimic");
		}
		if (!config.groupMemory())
		{
			disabled.add("memory");
		}
		if (!config.groupSouvenir())
		{
			disabled.add("souvenir");
		}
		if (!config.groupBet())
		{
			disabled.add("bet");
		}
		if (!config.groupClock())
		{
			disabled.add("clock");
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

		// A transient prop - a book for the reading animation, a scroll for
		// the scroll one. Overlaid AFTER the outfit resolves so it never
		// touches what the player configured, and skipped in thrall mode,
		// whose override is a shared object and whose conscript has no time
		// to read. The animations themselves hold nothing: the game draws
		// whatever is in your hands, and these hands are ours to fill.
		if (propSlot != null && outfitOverride == null)
		{
			outfit.setItem(propSlot, propItemId);
		}

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

	/**
	 * Validation for the live cache parsers: diffs every entry the offline
	 * dumper produced against the runtime parse of the same cache. Field-level
	 * equality via JSON serialisation - identical output proves the ported
	 * opcode readers byte-exact. Run on a machine that has the dump files.
	 */
	/**
	 * Checks every animation the RULES play against the cache they play from.
	 *
	 * <p>Two failures are possible and neither announces itself. An id that is
	 * not in the cache simply does nothing, and the rule looks broken for no
	 * visible reason. Worse, an id that is an AUTHORED LOOP never reaches the
	 * end its chain is waiting for: {@code playChain} finishes on the animation
	 * controller's callback, which a looping clip never fires, so the follower
	 * is left mid-emote - and since an emote empties its hands, holding nothing
	 * for the rest of the session.
	 *
	 * <p>Only animations a rule PLAYS are checked. The ids in an
	 * {@code animationSelf} trigger are the player's own and are none of the
	 * follower's business, and a pose id (the rest) is applied through the pose
	 * slot, where looping is the whole point.
	 *
	 * @return the number of problems found
	 */
	private int auditRuleAnimations()
	{
		java.util.Map<Integer, java.util.List<String>> played = new java.util.LinkedHashMap<>();
		for (com.follower.speech.SpeechRule rule : ruleLoader.getRules())
		{
			if (rule.animation != null)
			{
				played.computeIfAbsent(rule.animation, id -> new ArrayList<>()).add(rule.id);
			}
			if (rule.animations != null)
			{
				for (Integer id : rule.animations)
				{
					if (id != null)
					{
						played.computeIfAbsent(id, key -> new ArrayList<>()).add(rule.id);
					}
				}
			}
		}

		int problems = 0;
		for (java.util.Map.Entry<Integer, java.util.List<String>> entry : played.entrySet())
		{
			int id = entry.getKey();
			String rules = String.join(", ", entry.getValue());

			net.runelite.api.Animation animation = client.loadAnimation(id);
			if (animation == null)
			{
				log.warn("cachecheck: animation {} is not in the cache, used by {}", id, rules);
				problems++;
				continue;
			}
			if (animation.getFrameStep() >= 0)
			{
				log.warn("cachecheck: animation {} is an authored loop (frameStep {}),"
						+ " used by {} - a chain would never finish and the follower"
						+ " would be left mid-emote holding nothing",
					id, animation.getFrameStep(), rules);
				problems++;
			}
		}

		problems += auditMirroredAnimations();

		log.info("cachecheck: {} animations played by rules, {} problems",
			played.size(), problems);
		return problems;
	}

	/**
	 * Checks the two mimic rules have their animations the right way round.
	 *
	 * <p>They divide by whether an animation loops, and the division is what
	 * makes each one work: a one-shot mirrored as a POSE would freeze on its
	 * last frame until the player moved, and a loop mirrored as an EMOTE would
	 * never reach the finish its chain waits for. Neither says anything when it
	 * goes wrong, so the cache is asked directly.
	 *
	 * @return the number of animations on the wrong side
	 */
	private int auditMirroredAnimations()
	{
		int problems = 0;
		for (com.follower.speech.SpeechRule rule : ruleLoader.getRules())
		{
			if (rule.when == null || rule.when.ids == null)
			{
				continue;
			}
			boolean wantsLoop = Boolean.TRUE.equals(rule.mirrorPose);
			boolean wantsOneShot = Boolean.TRUE.equals(rule.mirrorAnimation);
			if (!wantsLoop && !wantsOneShot)
			{
				continue;
			}

			for (Integer id : rule.when.ids)
			{
				net.runelite.api.Animation animation =
					id == null ? null : client.loadAnimation(id);
				if (animation == null)
				{
					log.warn("cachecheck: {} lists animation {}, which is not in the cache",
						rule.id, id);
					problems++;
					continue;
				}
				boolean loops = animation.getFrameStep() >= 0;
				if (loops != wantsLoop)
				{
					log.warn("cachecheck: {} lists animation {} which {}, but the rule"
							+ " mirrors it as a {}",
						rule.id, id, loops ? "LOOPS" : "is a one-shot",
						wantsLoop ? "held pose" : "one-shot emote");
					problems++;
				}
			}
		}
		return problems;
	}

	private void runCacheCheck()
	{
		try
		{
			java.nio.file.Path modelFile = dataDir.resolve(com.follower.appearance.ModelRepository.FILE_NAME);
			java.nio.file.Path spotFile = dataDir.resolve(com.follower.appearance.SpotAnimRepository.FILE_NAME);
			if (!java.nio.file.Files.isRegularFile(modelFile) || !java.nio.file.Files.isRegularFile(spotFile))
			{
				sendStatus("cachecheck needs the dump files present to compare against");
				return;
			}

			com.follower.appearance.ModelRepository.Dump dump;
			try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(modelFile))
			{
				dump = gson.fromJson(reader, com.follower.appearance.ModelRepository.Dump.class);
			}
			com.google.gson.JsonObject spotRoot;
			try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(spotFile))
			{
				spotRoot = gson.fromJson(reader, com.google.gson.JsonObject.class);
			}
			java.util.Map<String, com.follower.appearance.SpotAnimRepository.Entry> dumpSpots =
				gson.fromJson(spotRoot.get("spotanims"),
					new com.google.gson.reflect.TypeToken<java.util.Map<String,
						com.follower.appearance.SpotAnimRepository.Entry>>() { }.getType());

			int problems = 0;
			problems += diffCatalogue("items", dump.items,
				com.follower.appearance.LiveCacheParser.parseItems(client));
			problems += diffCatalogue("kits", dump.kits,
				com.follower.appearance.LiveCacheParser.parseKits(client));
			problems += diffCatalogue("spotanims", dumpSpots,
				com.follower.appearance.LiveCacheParser.parseSpotAnims(client));

			problems += auditRuleAnimations();

			sendStatus(problems == 0
				? "cachecheck: PERFECT MATCH across items, kits, spotanims and rule animations"
				: "cachecheck: " + problems + " differences - see the client log");
		}
		catch (java.io.IOException | RuntimeException e)
		{
			log.warn("cachecheck failed", e);
			sendStatus("cachecheck failed: " + e.getMessage());
		}
	}

	/** @return the number of differing, missing or extra entries. */
	private int diffCatalogue(String label, java.util.Map<String, ?> fromDump,
		java.util.Map<String, ?> fromLive)
	{
		int problems = 0;
		int logged = 0;
		for (java.util.Map.Entry<String, ?> entry : fromDump.entrySet())
		{
			Object live = fromLive.get(entry.getKey());
			if (live == null)
			{
				problems++;
				if (logged++ < 5)
				{
					log.warn("cachecheck {}: id {} in dump but not live", label, entry.getKey());
				}
				continue;
			}
			String a = gson.toJson(entry.getValue());
			String b = gson.toJson(live);
			if (!a.equals(b))
			{
				problems++;
				if (logged++ < 5)
				{
					log.warn("cachecheck {}: id {} differs\n  dump: {}\n  live: {}",
						label, entry.getKey(), a, b);
				}
			}
		}
		for (String key : fromLive.keySet())
		{
			if (!fromDump.containsKey(key))
			{
				problems++;
				if (logged++ < 5)
				{
					log.warn("cachecheck {}: id {} live but not in dump", label, key);
				}
			}
		}
		log.info("cachecheck {}: dump {}, live {}, {} problems",
			label, fromDump.size(), fromLive.size(), problems);
		return problems;
	}

	/**
	 * Audits the stance library: what it claims must be real, and how much of
	 * the game it actually covers.
	 *
	 * <p>The library can never be PROVEN complete. Weapon stances are not in
	 * the cache - four probes settled that - so the only proof a given weapon
	 * animates correctly is watching a player wield it. What can be settled is
	 * everything short of that, and none of it needs a human to eyeball a
	 * follower:
	 *
	 * <ul>
	 *   <li>Every animation id the library names exists in the cache. A typo
	 *       or a bad outside source shows up here as an id the game does not
	 *       have, and would otherwise be a follower that silently stops
	 *       animating.</li>
	 *   <li>Every weapon in the game either resolves to a stance directly,
	 *       inherits one from its plain version, or is reported by name as
	 *       falling back to unarmed.</li>
	 *   <li>The same for attacks, which resolve through class borrowing and
	 *       otherwise land on the configured default.</li>
	 * </ul>
	 */
	private void runStanceAudit()
	{
		Set<Integer> sequences = com.follower.appearance.LiveCacheParser.sequenceIds(client);
		if (sequences.isEmpty())
		{
			sendStatus("stanceaudit needs the client's cache loaded - log in and try again");
			return;
		}

		int badIds = 0;
		int noDirectionals = 0;
		int withAttack = 0;
		for (Map.Entry<Integer, com.follower.follower.StanceLibrary.Stance> entry
			: stanceLibrary.all().entrySet())
		{
			int weaponId = entry.getKey();
			com.follower.follower.StanceLibrary.Stance stance = entry.getValue();
			String name = weaponId == com.follower.follower.StanceLibrary.UNARMED
				? "(unarmed)" : modelRepository.itemName(weaponId);

			badIds += auditAnimationId(sequences, weaponId, name, "idle", stance.idle);
			badIds += auditAnimationId(sequences, weaponId, name, "walk", stance.walk);
			badIds += auditAnimationId(sequences, weaponId, name, "run", stance.run);
			badIds += auditAnimationId(sequences, weaponId, name, "attack", stance.attack);
			badIds += auditAnimationId(sequences, weaponId, name, "walkBack", stance.walkBack);
			badIds += auditAnimationId(sequences, weaponId, name, "walkLeft", stance.walkLeft);
			badIds += auditAnimationId(sequences, weaponId, name, "walkRight", stance.walkRight);
			badIds += auditAnimationId(sequences, weaponId, name, "turnLeft", stance.turnLeft);
			badIds += auditAnimationId(sequences, weaponId, name, "turnRight", stance.turnRight);

			if (stance.attack > 0)
			{
				withAttack++;
			}
			// No directional poses means the entry was either typed in by hand
			// or observed before those fields existed. Its idle/walk/run may be
			// perfectly good; it just cannot have come from a live sighting
			// recently enough to carry the full set.
			if (stance.walkLeft == 0 && weaponId != com.follower.follower.StanceLibrary.UNARMED)
			{
				noDirectionals++;
			}
		}

		int weapons = 0;
		int direct = 0;
		int inherited = 0;
		int attackOwn = 0;
		int attackFromClass = 0;
		int attackGeneric = 0;
		List<String> uncovered = new ArrayList<>();
		for (Map.Entry<Integer, KitType> entry : slotIndex.entrySet())
		{
			if (entry.getValue() != KitType.WEAPON)
			{
				continue;
			}
			int itemId = entry.getKey();
			weapons++;

			com.follower.follower.StanceLibrary.Stance own = stanceLibrary.describe(itemId);
			boolean hasStance = stanceLibrary.knows(itemId);
			if (own != null)
			{
				direct++;
			}
			else if (hasStance)
			{
				inherited++;
			}
			else
			{
				String name = modelRepository.itemName(itemId);
				uncovered.add((name == null ? "?" : name) + " (" + itemId + ")");
			}

			// Three very different things, worth counting apart. A weapon that
			// was seen swung has its OWN attack. One that shares a class with
			// such a weapon borrows a correct animation for its kind. But a
			// weapon with no stance at all falls back to the unarmed pose set,
			// and the unarmed class is the big default one - so it picks up a
			// GENERIC swing for its combat style. That last is better than a
			// bow doing a sword's slash, and it is not the weapon's own
			// animation. Reporting them as one number flatters the library.
			if (stanceLibrary.attackFor(itemId) > 0)
			{
				if (own != null && own.attack > 0)
				{
					attackOwn++;
				}
				else if (hasStance)
				{
					attackFromClass++;
				}
				else
				{
					attackGeneric++;
				}
			}
		}

		java.util.Collections.sort(uncovered);
		log.info("stanceaudit: {} stances, {} name an animation the cache does not have,"
				+ " {} carry no directional poses, {} have a learned attack",
			stanceLibrary.all().size(), badIds, noDirectionals, withAttack);
		log.info("stanceaudit: {} weapons in the slot index - {} with their own stance,"
				+ " {} inheriting one, {} falling back to unarmed",
			weapons, direct, inherited, uncovered.size());
		log.info("stanceaudit: attacks - {} weapons swing their own learned animation,"
				+ " {} borrow one from their weapon class, {} get a generic swing for their"
				+ " style through the unarmed fallback, {} use the configured default",
			attackOwn, attackFromClass, attackGeneric,
			weapons - attackOwn - attackFromClass - attackGeneric);
		if (!uncovered.isEmpty())
		{
			log.info("stanceaudit: weapons with no stance:\n  {}", String.join("\n  ", uncovered));
		}

		sendStatus(badIds == 0
			? "stanceaudit: every animation id checks out. " + direct + " weapons matched,"
				+ " " + inherited + " inherited, " + uncovered.size() + " unknown; "
				+ (attackOwn + attackFromClass) + " with a real attack - see the log."
			: "stanceaudit: " + badIds + " animation ids do not exist - see the client log");
	}

	/** @return 1 when the id is set but is not a real animation, else 0. */
	private int auditAnimationId(Set<Integer> sequences, int weaponId, String name,
		String field, int animationId)
	{
		if (animationId <= 0 || sequences.contains(animationId))
		{
			return 0;
		}
		log.warn("stanceaudit: {} ({}) has {} = {}, which is not an animation in the cache",
			name == null ? "item" : name, weaponId, field, animationId);
		return 1;
	}

	/**
	 * The offline dumps are optional: whatever they didn't provide is parsed
	 * from the client's own loaded cache. Retried from login-adjacent states
	 * because the cache indexes may not exist when the plugin starts.
	 */
	private void ensureCatalogues()
	{
		if (!modelRepository.isLoaded())
		{
			modelRepository.loadFromClient(client);
			if (modelRepository.isLoaded())
			{
				// The outfit picker's slot filter and status line were built
				// against an empty catalogue; rebuild them on the real one.
				buildSlotIndexAsync();
				syncPanel();
			}
		}
		if (!spotAnimRepository.isLoaded())
		{
			spotAnimRepository.loadFromClient(client);
		}
		if (!kitSelectabilityLoaded)
		{
			// Always from the LIVE cache, even when a dump supplied the
			// entries: the flag is not part of the dump format, and the
			// picker needs it to hide the styles character creation hides.
			java.util.Set<Integer> nonSelectable = new java.util.HashSet<>();
			com.follower.appearance.LiveCacheParser.parseKits(client, nonSelectable);
			if (!nonSelectable.isEmpty())
			{
				modelRepository.setNonSelectableKits(nonSelectable);
				kitSelectabilityLoaded = true;
				log.debug("{} kits flagged non-selectable by the cache", nonSelectable.size());
				logKitCatalogue();
				syncPanel();
			}
		}
	}

	private boolean kitSelectabilityLoaded;

	/** One-line-per-part census of what the picker will offer, for verification. */
	private void logKitCatalogue()
	{
		for (KitType part : new KitType[]{KitType.HAIR, KitType.JAW, KitType.TORSO,
			KitType.ARMS, KitType.HANDS, KitType.LEGS, KitType.BOOTS})
		{
			log.debug("kits offered for {}: male {}, female {}", part,
				modelRepository.kitsFor(part, 0).size(),
				modelRepository.kitsFor(part, 1).size());
		}
	}

	/**
	 * A transient item shown on the follower without touching the saved
	 * outfit. For animations that mime holding something: the reading and
	 * writing animations move the arms and draw nothing, because in the real
	 * game the book comes from the reader's own equipment.
	 */
	private KitType propSlot;
	private int propItemId;

	private Outfit resolveOutfit()
	{
		// Thrall mode dresses the follower without touching the configured
		// outfit, so leaving the mode restores exactly what the player set up.
		if (outfitOverride != null)
		{
			return outfitOverride;
		}
		List<String> errors = new ArrayList<>();
		Outfit outfit = OutfitParser.parse(config.customOutfit(), errors);
		if (!errors.isEmpty())
		{
			sendStatus("Outfit warnings: " + String.join("; ", errors));
		}

		// Hands free: a follower kneeling in prayer should not still be
		// gripping a sword and a shield, and neither should one waving.
		if (spectateDisarmed || emoteDisarmed)
		{
			outfit.clear(KitType.WEAPON);
			outfit.clear(KitType.SHIELD);
		}
		return outfit;
	}

	/** True while the shield channel has the follower's weapon and shield put away. */
	private boolean spectateDisarmed;

	/** True while an emote has them put away. */
	private boolean emoteDisarmed;

	/**
	 * Empties the follower's hands for the duration of an emote.
	 *
	 * <p>Emotes are authored for a player holding nothing: a wave with a
	 * two-handed sword through the arm reads as a fault. Rebuilding the model
	 * mid-emote is safe, which was not obvious - setAppearance re-applies the
	 * pose, but applyPose returns early while an emote owns the animation slot,
	 * so the model is swapped underneath a clip that keeps running.
	 *
	 * <p>Thrall mode is exempt. Its attacks run through the same emote path,
	 * and a thrall swinging an invisible weapon would be worse than the problem
	 * this solves.
	 */
	/**
	 * Set while a {@code holdStill} rule's animation is playing, so the follower
	 * is released the moment it ends - and so idle wandering cannot wander off
	 * mid-celebration.
	 */
	/** Whether a thieving session was running last tick, for the two edges. */
	private boolean wasThieving;

	private boolean emoteHold;

	/**
	 * The looping animation currently being held to match the player's, or 0.
	 *
	 * <p>Held emotes cannot go through the one-shot path: their animation never
	 * finishes, so the chain would wait forever. A pose override loops on its
	 * own and is released when the player's animation changes.
	 */
	private int mirroredPose;

	private void startPoseMirror(int animationId)
	{
		mirroredPose = animationId;
		clientThread.invoke(() -> follower.setPoseOverride(animationId));
		refreshEmoteDisarm(follower.isEmotePlaying());
	}

	private void stopPoseMirror()
	{
		if (mirroredPose == 0)
		{
			return;
		}
		mirroredPose = 0;
		clientThread.invoke(() -> follower.setPoseOverride(0));
		refreshEmoteDisarm(follower.isEmotePlaying());
	}

	private void updateEmoteDisarm(boolean emoting)
	{
		if (!emoting && emoteHold)
		{
			emoteHold = false;
			follower.resumeFollowing();
		}
		refreshEmoteDisarm(emoting);
	}

	/**
	 * Empties the follower's hands while it is emoting AT ALL - a one-shot copy
	 * or a held pose. Both are emotes as far as a player is concerned, so both
	 * put the weapon away.
	 */
	private void refreshEmoteDisarm(boolean emoting)
	{
		boolean wanted = (emoting || mirroredPose != 0) && thrallNpc == null;
		if (wanted == emoteDisarmed)
		{
			return;
		}
		emoteDisarmed = wanted;
		rebuildFollower();
	}

	/**
	 * Puts the follower's weapon and shield away for the duration of the
	 * channel, and hands them back afterwards.
	 *
	 * <p>Done at the START of the spell rather than when the pose begins. The
	 * model has to be rebuilt to change what it is holding, and a rebuild
	 * re-applies the pose from its first frame - which during the kneel would
	 * be the stand-and-kneel-again that took three attempts to get rid of. The
	 * cast is playing at that point and covers it.
	 */
	private void setSpectateDisarmed(boolean disarmed)
	{
		if (spectateDisarmed == disarmed)
		{
			return;
		}
		spectateDisarmed = disarmed;
		clientThread.invoke(this::rebuildFollower);
	}

	// ------------------------------------------------------------- thrall mode

	private NPC thrallNpc;
	private String thrallStyle = "";
	private Outfit outfitOverride;

	/** Countdown to stage two of the exit flourish (snap home + redress). */
	private int thrallExitTicks;

	/** Plays the spawn-in burst a tick late, once the follower stands at the thrall. */
	private int pendingThrallSpawnFxTicks;

	/** Exit motion, and the necromancy shimmer for the return to follower form. */
	private static final int THRALL_EXIT_ANIMATION = 8973;
	private static final int THRALL_RETURN_SPOTANIM = 1290;

	/** Per-style resurrect impact vfx: the burst when a thrall phases in or out. */
	private static int thrallImpactSpotAnim(String style)
	{
		switch (style)
		{
			case "melee":
				return 1905;
			case "ranged":
				return 1904;
			default:
				return 1903;
		}
	}

	@Inject
	private net.runelite.client.callback.RenderCallbackManager renderCallbacks;

	/**
	 * Hides the REAL thrall while the follower stands in for it. Reference
	 * comparison, so it costs nothing while no thrall is possessed.
	 *
	 * <p>This used to be a {@code Hooks.RenderableDrawListener}, with a note
	 * saying there was no replacement registration path yet. There is one now:
	 * {@link net.runelite.client.callback.RenderCallbackManager} takes a
	 * {@link net.runelite.client.callback.RenderCallback}, and {@code addEntity}
	 * is the same question in the same place - return false and the thing is
	 * not drawn.
	 */
	private final net.runelite.client.callback.RenderCallback thrallHider =
		new net.runelite.client.callback.RenderCallback()
		{
			@Override
			public boolean addEntity(net.runelite.api.Renderable renderable, boolean drawingUi)
			{
				return renderable != thrallNpc;
			}
		};

	/**
	 * Thrall NPC ids, matched by ID because the NPCs' cache name is the literal
	 * string "null" (measured live: a summon spawned id 10878 named 'null').
	 * Mapping measured from the companion-pet hub plugin's bytecode and
	 * consistent with our live captures: ghosts (magic) 10878-10880,
	 * skeletons (ranged) 10881-10883, zombies (melee) 10884-10886, one id per
	 * tier (lesser/superior/greater).
	 */
	private static String thrallStyleFor(int npcId)
	{
		if (npcId >= 10884 && npcId <= 10886)
		{
			return "melee";
		}
		if (npcId >= 10881 && npcId <= 10883)
		{
			return "ranged";
		}
		if (npcId >= 10878 && npcId <= 10880)
		{
			return "magic";
		}
		return null;
	}

	/**
	 * A thrall NPC spawning inside the adoption window (a few ticks after the
	 * resurrect cast) and beside the player is OUR summon: possess it. The
	 * window plus proximity keeps other players' thralls out.
	 */
	/**
	 * The varbit behind the thrall spells' 10-second summoning cooldown: it
	 * rises to 1 the moment a thrall is summoned (combat-proof, unlike the
	 * cast animation) and falls back to 0 ten seconds later - measured live,
	 * metronomic 10s drops while the thrall stood healthy. Detection signal
	 * ONLY; it says nothing about the thrall's remaining lifetime.
	 */
	private static final int THRALL_SUMMONED_VARBIT = 12290;

	/**
	 * Possession briefly lost to a scene rebuild: the thrall NPC despawned
	 * during a load, not by expiry, so the follower keeps its outfit and
	 * silently re-possesses the NPC when the scene brings it back.
	 */
	private boolean thrallLimbo;

	/** Ticks spent in limbo; a respawn that never comes ends the possession. */
	private int thrallLimboTicks;

	/** Ticks since the last LOADING state, to classify NPC despawns. */
	private int ticksSinceLoading = 1000;

	private void maybeAdoptThrall(NPC npc)
	{
		if (!config.thrallMode() || npc == thrallNpc)
		{
			return;
		}
		// A fresh summon shows as the cooldown varbit reading 1 (it stays up
		// for ten seconds after any cast - fresh, mid-combat or resummon). A
		// limbo re-acquire may come later than that, so limbo bypasses it and
		// matches purely on thrall id and proximity.
		if (client.getVarbitValue(THRALL_SUMMONED_VARBIT) != 1 && !thrallLimbo)
		{
			return;
		}
		String style = thrallStyleFor(npc.getId());
		if (style == null)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		int distance = local != null && local.getWorldLocation() != null && npc.getWorldLocation() != null
			? npc.getWorldLocation().distanceTo(local.getWorldLocation()) : -1;
		if (distance < 0 || distance > 6)
		{
			return;
		}
		if (thrallNpc != null)
		{
			// A resummon while the old thrall is still in the scene: switch
			// bodies rather than dropping back to a vanilla thrall.
			String previous = thrallStyle;
			switchThrall(npc, style);
			speechEngine.dispatch(TriggerEvent.thrallSwitch(previous, style));
		}
		else if (thrallExitPendingTicks > 0)
		{
			// The old thrall vanished a moment ago and we held the exit back
			// for exactly this: a resummon, not an expiry. Take the new body
			// and acknowledge the change instead of saying goodbye.
			String previous = thrallExitStyle;
			thrallExitPendingTicks = 0;
			switchThrall(npc, style);
			speechEngine.dispatch(TriggerEvent.thrallSwitch(previous, style));
		}
		else if (thrallLimbo)
		{
			// The scene gave the thrall back: same possession, no re-greeting.
			thrallLimbo = false;
			switchThrall(npc, style);
		}
		else
		{
			adoptThrall(npc, style);
		}
	}

	/** Adopts a thrall already in the scene when the varbit beats its spawn event. */
	private void adoptExistingThrall()
	{
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc != null && thrallStyleFor(npc.getId()) != null)
			{
				maybeAdoptThrall(npc);
			}
		}
	}

	private void switchThrall(NPC npc, String style)
	{
		boolean styleChanged = !style.equals(thrallStyle);
		thrallNpc = npc;
		thrallStyle = style;
		thrallExitTicks = 0;
		pendingThrallSpawnFxTicks = 1;
		log.debug("Switching possession to {} thrall (id {})", style, npc.getId());
		if (styleChanged)
		{
			outfitOverride = resolveThrallOutfit(style);
		}
		clientThread.invoke(() ->
		{
			follower.slaveToNpc(npc);
			follower.setThrallCircleFromNpcModel(thrallCircleModelId(npc));
			if (styleChanged)
			{
				rebuildFollower();
			}
		});
	}

	private void adoptThrall(NPC npc, String style)
	{

		thrallNpc = npc;
		thrallStyle = style;
		thrallExitTicks = 0;
		pendingThrallSpawnFxTicks = 1;
		outfitOverride = resolveThrallOutfit(style);
		log.debug("Possessing {} thrall (id {})", style, npc.getId());
		clientThread.invoke(() ->
		{
			follower.slaveToNpc(npc);
			follower.setThrallCircleFromNpcModel(thrallCircleModelId(npc));
			rebuildFollower();
		});
		speechEngine.dispatch(TriggerEvent.thrall(TriggerEvent.Type.THRALL_START, style));
	}

	/**
	 * The thrall's whole body - circle included - is a single model on its
	 * composition (measured: 41985/41989/41988 for melee/ranged/magic).
	 * Reading it live keeps every tier authentic.
	 */
	private static int thrallCircleModelId(NPC npc)
	{
		net.runelite.api.NPCComposition comp = npc.getTransformedComposition() != null
			? npc.getTransformedComposition() : npc.getComposition();
		int[] models = comp == null ? null : comp.getModels();
		return models != null && models.length > 0 ? models[0] : -1;
	}

	/** Ticks left before a vanished thrall counts as gone rather than resummoned. */
	private int thrallExitPendingTicks;
	private String thrallExitStyle = "";

	private void exitThrallMode()
	{
		if (thrallNpc == null && !thrallLimbo)
		{
			return;
		}
		performThrallExit(thrallStyle);
	}

	/** The exit proper: phase out where it stands, then home and redressed. */
	private void performThrallExit(String style)
	{
		thrallExitPendingTicks = 0;
		thrallNpc = null;
		thrallLimbo = false;
		log.debug("Thrall gone; phasing the follower out");

		// Stage one: still in thrall dress at the thrall's last spot, the
		// follower phases out through the same per-style burst a thrall
		// materialises with. Stage two (in onGameTick) snaps it home.
		clientThread.invoke(() ->
		{
			follower.endNpcSlaveHolding();
			follower.playAnimation(THRALL_EXIT_ANIMATION);
			follower.playSpotAnim(spotAnimRepository.get(thrallImpactSpotAnim(style)));
		});
		thrallExitTicks = 4;
		speechEngine.dispatch(TriggerEvent.thrall(TriggerEvent.Type.THRALL_END, style));
	}

	/** Logout/hop teardown: no snap, no message, just clean state. */
	private void resetThrallQuietly()
	{
		pendingThrallSpawnFxTicks = 0;
		thrallExitTicks = 0;
		thrallExitPendingTicks = 0;
		if (thrallNpc != null || thrallLimbo)
		{
			thrallNpc = null;
			thrallLimbo = false;
			outfitOverride = null;
			follower.releaseNpcSlave();
		}
	}

	private Outfit resolveThrallOutfit(String style)
	{
		String profileName = "melee".equals(style) ? config.thrallMeleeProfile()
			: "ranged".equals(style) ? config.thrallRangedProfile()
			: config.thrallMagicProfile();
		String outfit = profileStore.get(profileName);
		if (outfit == null)
		{
			// The configured profile was renamed or deleted; the seeded style
			// profiles are restored on every load, so these always exist.
			outfit = profileStore.get("melee".equals(style) ? "Melee"
				: "ranged".equals(style) ? "Ranged" : "Magic");
		}
		return OutfitParser.parse(outfit == null ? "" : outfit);
	}

	/**
	 * Generic swings, used only until the follower's actual weapon has been
	 * seen used: an unarmed-style slash, a bow shot and a strike spell cast.
	 *
	 * <p>These were three settings once. They stopped making sense when the
	 * stance library learned to match the animation to the weapon in the
	 * follower's hands - a typed-in id could only contradict it, and the honest
	 * answer for an unknown weapon is a plain swing of the right kind rather
	 * than whatever someone last typed.
	 */
	private static final int GENERIC_MELEE_ATTACK = 390;
	private static final int GENERIC_RANGED_ATTACK = 426;
	private static final int GENERIC_MAGIC_ATTACK = 1162;

	/**
	 * What the follower swings, matched to what it is actually holding: the
	 * learned attack animation for its own weapon when one has been observed,
	 * else a generic swing for the style. A scimitar should not slash like a
	 * godsword just because both are melee.
	 */
	private int thrallAttackAnimation()
	{
		int learned = stanceLibrary.attackFor(follower.getWeaponItemId());
		if (learned > 0)
		{
			return learned;
		}
		switch (thrallStyle)
		{
			case "melee":
				return GENERIC_MELEE_ATTACK;
			case "ranged":
				return GENERIC_RANGED_ATTACK;
			default:
				return GENERIC_MAGIC_ATTACK;
		}
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
	/** Shorthand for the conversation script below. */
	private static com.follower.speech.FollowerDialog.Node says(String... pages)
	{
		return com.follower.speech.FollowerDialog.Node.says(pages);
	}

	private static com.follower.speech.FollowerDialog.Node you(String... pages)
	{
		return com.follower.speech.FollowerDialog.Node.you(pages);
	}

	/**
	 * The joke pool, each entry a setup page and a punchline page. Every
	 * conversation draws a random one, and "Got another one?" serves a second,
	 * guaranteed different joke.
	 */
	private static final String[][] JOKES = {
		{"Why did the Wise Old Man rob the bank of Draynor?",
			"Because that's where the money was."},
		{"What do you call an adventurer who takes their whole bank into the Wilderness?",
			"A benefactor."},
		{"Why did the chicken cross the road?",
			"In Lumbridge? It never got the chance."},
		{"I tried talking to a camel in Al Kharid once.",
			"Apparently you need a special amulet. He made his opinion clear without one."},
	};

	/**
	 * What today came to, in the follower's words.
	 *
	 * <p>Built fresh every time the branch is opened. Everything in it is
	 * already counted somewhere - the tallies exist for the milestone lines -
	 * so this is the same memory said as a sentence rather than as a number.
	 *
	 * <p>Only the parts that actually happened are mentioned. A summary that
	 * dutifully reports nought kills and nought deaths reads like a form; one
	 * that leaves those out reads like somebody remembering.
	 */
	private String[] daySummary()
	{
		com.follower.speech.TriggerContext context = speechEngine.getContext();
		return daySummary(
			sessionMinutes,
			context.getTally("kills:today"),
			context.getTally("levels:today"),
			context.getTally("deaths:today"),
			context.getMoodBand(),
			context.hasIncident() ? context.getIncidentPhrase() : null);
	}

	/**
	 * The wording, with the figures already gathered. Split out from its caller
	 * so a test can walk every branch of it - there are sixteen combinations of
	 * what did and did not happen today, and they are all sentences the player
	 * reads.
	 */
	static String[] daySummary(int minutes, int kills, int levels, int deaths,
		String moodBand, String incident)
	{
		java.util.List<String> parts = new java.util.ArrayList<>();

		if (minutes >= 1)
		{
			parts.add(minutes < 60
				? minutes + (minutes == 1 ? " minute" : " minutes")
				: (minutes / 60) + (minutes / 60 == 1 ? " hour" : " hours"));
		}

		if (kills > 0)
		{
			parts.add(kills + (kills == 1 ? " thing killed" : " things killed"));
		}
		if (levels > 0)
		{
			parts.add(levels + (levels == 1 ? " level" : " levels"));
		}
		if (deaths > 0)
		{
			parts.add(deaths + (deaths == 1 ? " death" : " deaths"));
		}

		java.util.List<String> pages = new java.util.ArrayList<>();
		if (parts.isEmpty())
		{
			pages.add("Today's page is blank. Restful, I call that.");
		}
		else
		{
			pages.add("Today's page: " + join(parts) + ".");
		}

		// The mood is the follower's own verdict on all that, which is a
		// different thing from the figures and worth saying separately.
		switch (moodBand == null ? "" : moodBand)
		{
			case "low":
				pages.add("I have had better days, if I am honest.");
				break;
			case "down":
				pages.add("Not our finest, but we are still walking.");
				break;
			case "good":
				pages.add("A good day, that. I would take another like it.");
				break;
			case "high":
				pages.add("One of the good ones. I mean that.");
				break;
			default:
				pages.add("An ordinary day. There is nothing wrong with those.");
				break;
		}

		// And the one thing it has not let go of.
		if (incident != null && !incident.isEmpty())
		{
			pages.add("I am still thinking about " + incident + ".");
		}
		return pages.toArray(new String[0]);
	}

	/** "a, b and c" - the way a person lists things. */
	private static String join(java.util.List<String> parts)
	{
		if (parts.size() == 1)
		{
			return parts.get(0);
		}
		return String.join(", ", parts.subList(0, parts.size() - 1))
			+ " and " + parts.get(parts.size() - 1);
	}

	/** The previous joke, so consecutive draws never repeat. */
	private static int lastJoke = -1;

	/** A random joke from the pool, guaranteed different from the last one told. */
	private static String[] nextJoke()
	{
		int pick;
		do
		{
			pick = java.util.concurrent.ThreadLocalRandom.current().nextInt(JOKES.length);
		}
		while (pick == lastJoke && JOKES.length > 1);
		lastJoke = pick;
		return JOKES[pick];
	}

	/**
	 * The follower's conversation. A hub of branches: who they are, what they
	 * can do (in-character documentation of the plugin's features), small talk,
	 * and adventuring advice - written to the register of a real dialogue,
	 * player interjections and all. Menus stay within the five options the
	 * real chat menu supports. Joke nodes resolve dynamically, re-rolling on
	 * every visit.
	 */
	/**
	 * Opens Talk-to: the conversation the follower is waiting on if it has
	 * asked something, and the everyday script otherwise.
	 *
	 * <p>The override is what makes a question a question rather than a line
	 * that happened to end in a question mark. The follower asked, so the
	 * obvious thing - talk to it - is about what it asked, and the everyday
	 * script waits its turn.
	 */
	private void startTalking()
	{
		String asked = speechEngine.getContext().getAskedTree();
		com.follower.speech.DialogTree tree = dialogLoader.get(asked);
		if (tree != null)
		{
			dialog.startNextTick(config.followerName(),
				com.follower.speech.FollowerDialog.build(tree, this::answerQuestion),
				tree.startId());
			return;
		}
		dialog.startNextTick(config.followerName(),
			talkScript(this::daySummary, this::answerQuestion,
				speechEngine.getContext().getWishLabel(),
				speechEngine.getContext().isWishedItemInBag(),
				speechEngine.getContext().getMoodBand()), "start");
	}

	/**
	 * The player picked a branch that answers. Closes the question and tells
	 * the rules, which is what turns a conversation into a consequence.
	 */
	private void answerQuestion(String answer)
	{
		speechEngine.getContext().noteAnswered();
		speechEngine.dispatch(TriggerEvent.answered(answer));
	}

	/**
	 * The everyday Talk-to conversation.
	 *
	 * <p>Written in Java rather than kept in dialogs.json because it does not
	 * change with play - it is what the follower is, not what it is currently
	 * up to. The trees in dialogs.json are the ones the follower OPENS, which
	 * is a different job.
	 *
	 * <p>Three rules hold this together.
	 *
	 * <p>Every choice leads to a node where the PLAYER says that exact line.
	 * The game works that way and players read it that way: the option is the
	 * sentence you are about to speak, not a summary of it. Jumping straight
	 * to a menu instead reads as the click having gone astray, and an option
	 * whose node says something slightly different reads as a bug, because it
	 * is one. A test enforces the match.
	 *
	 * <p>Nothing here is a feature list: what the follower can do comes out as
	 * habits and grievances, the way a person describes their job, because a
	 * companion reciting its own capabilities is a manual with a face.
	 *
	 * <p>And a branch never replays a line already read - hence the
	 * {@code -menu} nodes, which re-offer the follow-ups without the preamble.
	 *
	 * <p>Package-private so the test can reach it. It is the only speech in the
	 * plugin outside phrases.json, and so the only speech nothing checked.
	 */
	static java.util.Map<String, com.follower.speech.FollowerDialog.Node> talkScript(
		java.util.function.Supplier<String[]> summary,
		java.util.function.Consumer<String> onAnswer,
		String wish,
		boolean wishItemInBag,
		String moodBand)
	{
		java.util.Map<String, com.follower.speech.FollowerDialog.Node> script =
			new java.util.LinkedHashMap<>();

		// The gift option only exists while a wish is open, and it names the
		// thing, because the first design taught us the failure the hard way:
		// "Found you something" with no something reads as a null action, and
		// giving with no wanting before it has no pull. The follower asks for
		// a feather; the option is "Found you that feather."; everything
		// downstream says feather. The script is rebuilt on every Talk-to, so
		// the option appears and disappears with the wish.
		boolean wishing = wish != null && !wish.isEmpty();
		String giftLabel = "Found you that " + wish + ".";

		// When the follower is not itself, "How have you been?" sharpens into
		// "You all right?" - same slot, same spirit, pointed by state. The
		// research calls this answering its mood, and it is the one thing a
		// player can DO about a low band besides witness it.
		boolean lowish = "low".equals(moodBand) || "down".equals(moodBand);
		String howLabel = lowish ? "You all right?" : "How have you been?";
		String howTarget = lowish ? "allright-q" : "how-q";

		script.put("start", wishing
			? says("Yes?").choices(
				"Who are you, exactly?", "who-q",
				"What is it you actually do?", "do-q",
				howLabel, howTarget,
				giftLabel, "gift-q",
				"Never mind.", "bye-q")
			: says("Yes?").choices(
				"Who are you, exactly?", "who-q",
				"What is it you actually do?", "do-q",
				howLabel, howTarget,
				"Let's just talk.", "chat-q",
				"Never mind.", "bye-q"));

		// The returning hub, without the greeting. Five options is the most
		// the box has measured spacing for, so while a wish is open the hub
		// retires who-are-you to make room - somebody coming BACK
		// mid-conversation knows who it is.
		script.put("menu", wishing
			? says().choices(
				"What is it you actually do?", "do-q",
				howLabel, howTarget,
				giftLabel, "gift-q",
				"Let's just talk.", "chat-q",
				"That's all for now.", "done-q")
			: says().choices(
				"Who are you, exactly?", "who-q",
				"What is it you actually do?", "do-q",
				howLabel, howTarget,
				"Let's just talk.", "chat-q",
				"That's all for now.", "done-q"));

		// ------------------------------------------------ answering its mood
		// Asking is itself the kindness: reaching the answer node is what
		// counts (latched on arrival, like every answer), so closing the box
		// early still asked. The lift arrives from the comforted rule; the
		// pages differ by band because "not the best day" and "middling" are
		// different admissions. Either way the usual how-menu follows - a low
		// day does not lock away the notebook.
		if (lowish)
		{
			script.put("allright-q", you("You all right?").then("allright-a"));
			script.put("allright-a", "low".equals(moodBand)
				? says(
					"Honestly? Not the best day in the ledger.",
					"But you noticed. That's worth an entry of its own.")
					.onFinish(() -> onAnswer.accept("comforted"))
					.then("how-menu")
				: says(
					"Middling. The ink's been thicker.",
					"Asking helps. Don't tell anyone I said that.")
					.onFinish(() -> onAnswer.accept("comforted"))
					.then("how-menu"));
		}

		// ------------------------------------------------ the gift
		// Client-side, so nothing real changes hands: the box closes on the
		// handover and the verdict arrives overhead from the gifted-* rules,
		// the shape the hands game uses - fixed text here, consequence from
		// the rules.
		if (wishing)
		{
			// The box branches on the bag, because the first neutral version
			// ("Let's see it, then." either way) read as the follower not
			// looking - and a scribe that does not look is out of character
			// in its own conversation. The bag is read when the script is
			// built; the rules re-check at dispatch, so the rare mid-box drop
			// still gets an honest overhead answer.
			script.put("gift-q", you(giftLabel).then("gift-a"));
			if (wishItemInBag)
			{
				script.put("gift-a", says(
					"Let's see it, then. ...That's the one.")
					.onFinish(() -> onAnswer.accept("gift")));
			}
			else
			{
				// The catch happens HERE, in the conversation, where the claim
				// was made. The overhead coda afterwards is one dry line, not
				// a second telling-off.
				script.put("gift-a", says(
					"You're patting an empty bag. I can hear it from here.",
					"Bring me a real " + wish + " and I'll be delighted.")
					.onFinish(() -> onAnswer.accept("bluff")));
			}
		}

		// Shared closings. Both are spoken, like every other option.
		script.put("bye-q", you("Never mind.").then("bye"));
		script.put("done-q", you("That's all for now.").then("bye"));
		script.put("back-q", you("Back to business.").then("menu"));
		script.put("bye", says("Right you are. One step behind."));

		// ------------------------------------------------ who are you
		script.put("who-q", you("Who are you, exactly?").then("who-a"));
		script.put("who-a", says(
			"Now there's a question.",
			"A scribe. Somebody has to write all this down.")
			.then("who-b"));
		script.put("who-b", says(
			"You dress me, I walk behind you, and I keep quiet about your bank.")
			.choices(
				"Writing what, exactly?", "who-me-q",
				"Do you have a name?", "who-name-q",
				"Don't you get tired of following me?", "who-tired-q",
				"Back to business.", "back-q"));

		script.put("who-menu", says()
			.choices(
				"Writing what, exactly?", "who-me-q",
				"Do you have a name?", "who-name-q",
				"Don't you get tired of following me?", "who-tired-q",
				"Back to business.", "back-q"));

		script.put("who-me-q", you("Writing what, exactly?").then("who-me-a"));
		script.put("who-me-a", says(
			"Everything. Roads, rulers, prices, what lives under things.",
			"It's a long project. Nobody's asked to read it.")
			.then("who-menu"));

		script.put("who-name-q", you("Do you have a name?").then("who-name-a"));
		script.put("who-name-a", says(
			"You gave me one. I'd have chosen differently, but I wasn't asked.",
			"It's grown on me.")
			.then("who-menu"));

		script.put("who-tired-q", you("Don't you get tired of following me?").then("who-tired-a"));
		script.put("who-tired-a", says(
			"Tired? I once watched you stand at a furnace for three hours.",
			"Nothing has tired me since.")
			.then("who-menu"));

		// ------------------------------------------------ what do you do
		script.put("do-q", you("What is it you actually do?").then("do-a"));
		script.put("do-a", says("Walk behind you. It's more involved than it sounds.")
			.then("do-menu"));
		script.put("do-menu", says()
			.choices(
				"Tell me about the walking.", "do-follow-q",
				"What do you do when I'm fighting?", "do-fight-q",
				"And when I'm working?", "do-work-q",
				"Can you dance?", "do-emote-q",
				"That's all I needed.", "do-done-q"));
		script.put("do-done-q", you("That's all I needed.").then("menu"));

		script.put("do-follow-q", you("Tell me about the walking.").then("do-follow-a"));
		script.put("do-follow-a", says(
			"One tile behind. Corners, doorways, stairs, running - I keep up.",
			"Where you go I go, teleports included. Same swirl, half a step later.")
			.then("do-follow-b"));
		script.put("do-follow-b", says(
			"Tell me to Stay and I'll hold the spot until you want me back.",
			"Or shift-click a tile and Send me, and I'll walk there myself.")
			.then("do-menu"));

		script.put("do-fight-q", you("What do you do when I'm fighting?").then("do-fight-a"));
		script.put("do-fight-a", says(
			"Get out of the way, mainly. Then watch.",
			"If something enormous is standing near us I'll mention it first. For all the good that does.")
			.then("do-fight-b"));
		script.put("do-fight-b", says(
			"And when it goes down I make a fuss. You've usually earned it by then.")
			.choices(
				"What about my thralls?", "do-thrall-q",
				"Good to know.", "do-fight-done-q"));
		script.put("do-fight-done-q", you("Good to know.").then("do-menu"));

		// The thrall styles are measured, not guessed: zombie ids are melee,
		// skeleton ranged, ghost magic, and each has its own outfit profile in
		// the settings. Worth saying out loud - the follower turning up to a
		// mage fight in melee gear is the one thing about this that looks
		// broken rather than deliberate.
		script.put("do-thrall-q", you("What about my thralls?").then("do-thrall-a"));
		script.put("do-thrall-a", says(
			"Raise one of those Arceuus things and I'll take its place.",
			"Same walking, same swinging, considerably better dressed.")
			.then("do-thrall-b"));
		script.put("do-thrall-b", says(
			"Three sorts, mind. Zombie, skeleton, ghost - melee, ranged and magic.")
			.then("do-thrall-c"));
		script.put("do-thrall-c", says(
			"Set me an outfit for each in the settings.",
			"Otherwise I turn up to a mage fight holding a greataxe and we both look foolish.")
			.then("do-menu"));

		script.put("do-work-q", you("And when I'm working?").then("do-work-a"));
		script.put("do-work-a", says(
			"Depends what you're at.",
			"If it's pockets, I give you room and keep an eye on the street. I say nothing until you're done.")
			.then("do-work-b"));
		script.put("do-work-b", says(
			"Otherwise I'll find something to look at. I don't need watching every minute.",
			"Now and then I wander off on an errand of my own. I always come back.")
			.then("do-menu"));

		script.put("do-emote-q", you("Can you dance?").then("do-emote-a"));
		script.put("do-emote-a", says(
			"Ask and find out. Right-click me - wave, dance, whatever the moment wants.",
			"Or do one yourself and I'll join in a beat behind. I'm not a mirror. I have timing.")
			.then("do-menu"));

		// ------------------------------------------------ the inner life
		// The everyday entrance; on a lowish day allright-q replaces it (and
		// leads to the same menu), so it is only built when reachable.
		if (!lowish)
		{
			script.put("how-q", you("How have you been?").then("how-a"));
			script.put("how-a", says(
				"That depends on the day. You've given me some days.")
				.then("how-menu"));
		}
		script.put("how-menu", says()
			.choices(
				"How did today go?", "how-today-q",
				"You keep track of things?", "how-count-q",
				"You have moods?", "how-mood-q",
				"Anywhere you'd rather be?", "how-place-q",
				"Back to business.", "back-q"));

		// The one page in the script that is different every time it is read.
		// Everything in it is already counted - the tallies exist for the
		// milestone lines - so this is the same memory said as a sentence
		// rather than as a number, which is what makes it sound like somebody
		// who was there rather than a scoreboard.
		script.put("how-today-q", you("How did today go?").then("how-today-a"));
		script.put("how-today-a", com.follower.speech.FollowerDialog.Node
			.saysDynamic(summary)
			.then("how-menu"));

		script.put("how-count-q", you("You keep track of things?").then("how-count-a"));
		script.put("how-count-a", says(
			"It's the job. Every rat, every level, every time you've gone down in front of me.",
			"It isn't judgement. Somebody should, that's all.")
			.then("how-count-b"));
		script.put("how-count-b", says(
			"I remember it between days, too. You come back and the number's still there.")
			.then("how-menu"));

		script.put("how-mood-q", you("You have moods?").then("how-mood-a"));
		script.put("how-mood-a", says(
			"I do. You'd know if you looked up.",
			"When it's gone well I stand one way. When it hasn't, I stand another.")
			.then("how-mood-b"));
		script.put("how-mood-b", says(
			"It passes. Everything does, if you keep walking.")
			.then("how-menu"));

		script.put("how-place-q", you("Anywhere you'd rather be?").then("how-place-a"));
		script.put("how-place-a", says(
			"There are places I like. I couldn't tell you why.",
			"And one or two I'd sooner we didn't linger in. You'll hear about those.")
			.then("how-place-b"));
		script.put("how-place-b", says(
			"If I ever ask you to take me somewhere, come and talk to me and I'll say where.",
			"I won't ask twice. It's not that sort of arrangement.")
			.then("how-menu"));

		// ------------------------------------------------ small talk
		script.put("chat-q", you("Let's just talk.").then("chat-a"));
		script.put("chat-a", says("My favourite duty.").then("chat-menu"));
		script.put("chat-menu", says()
			.choices(
				"Seen anything interesting?", "chat-seen-q",
				"What do you think of my outfit?", "chat-outfit-q",
				"Any advice?", "advice-q",
				"Tell me a joke.", "chat-joke-q",
				"Back to business.", "back-q"));

		script.put("chat-seen-q", you("Seen anything interesting?").then("chat-seen-a"));
		script.put("chat-seen-a", says(
			"Mostly the back of your head.",
			"It's a fine head. It could carry a better hat.")
			.then("chat-menu"));

		script.put("chat-outfit-q", you("What do you think of my outfit?").then("chat-outfit-a"));
		script.put("chat-outfit-a", says(
			"Anyone who dresses their follower this well clearly has taste.",
			"The rest of your wardrobe I couldn't possibly comment on.")
			.then("chat-menu"));

		// Every visit re-rolls from the pool (never the same joke twice in a
		// row), and the loop lets you keep asking. Both loop options get their
		// own spoken node so the label and the line stay identical.
		script.put("chat-joke-q", you("Tell me a joke.").then("chat-joke-a"));
		script.put("chat-joke-a", com.follower.speech.FollowerDialog.Node
			.saysDynamic(FollowerPlugin::nextJoke)
			.choices(
				"Heh. Got another one?", "chat-joke2-q",
				"That's terrible.", "chat-groan-q"));

		script.put("chat-joke2-q", you("Heh. Got another one?").then("chat-joke2-a"));
		script.put("chat-joke2-a", com.follower.speech.FollowerDialog.Node
			.saysDynamic(FollowerPlugin::nextJoke)
			.choices(
				"Another!", "chat-joke3-q",
				"That's terrible.", "chat-groan-q"));

		script.put("chat-joke3-q", you("Another!").then("chat-joke2-a"));

		script.put("chat-groan-q", you("That's terrible.").then("chat-groan-a"));
		script.put("chat-groan-a", says(
			"I've been saving that one since Lumbridge.",
			"There's more where it came from. Choose your next question carefully.")
			.then("chat-menu"));

		// ------------------------------------------------ advice
		script.put("advice-q", you("Any advice?").then("advice-a"));
		script.put("advice-a", says(
			"Don't take the detour for the cabbage. It is never worth it.")
			.then("advice-b"));
		script.put("advice-b", says(
			"If a stranger offers to trim your armour, he is not a barber.")
			.then("advice-c"));
		script.put("advice-c", says(
			"And bank before you think you need to. A gravestone is not storage.")
			.then("advice-d"));
		script.put("advice-d", says(
			"That's the lot. The rest I've learned to keep to myself.")
			.then("chat-menu"));

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

		// The hover hint injects a Talk-to every client tick so the game draws
		// the top-left text natively; when the menu OPENS that entry is still
		// in it, and adding a fresh set would duplicate it. Strip our own
		// entries first, then rebuild the full menu in NPC order.
		net.runelite.api.MenuEntry[] existing = client.getMenu().getMenuEntries();
		java.util.List<net.runelite.api.MenuEntry> kept = new java.util.ArrayList<>();
		for (net.runelite.api.MenuEntry entry : existing)
		{
			if (entry.getType() != net.runelite.api.MenuAction.RUNELITE
				|| !entry.getTarget().contains(config.followerName()))
			{
				kept.add(entry);
			}
		}
		client.getMenu().setMenuEntries(kept.toArray(new net.runelite.api.MenuEntry[0]));

		// Examine sits where a real NPC's does: below Walk here, above Cancel -
		// index 1 in the bottom-up entry array (Cancel is index 0).
		client.getMenu().createMenuEntry(1)
			.setOption("Examine")
			.setTarget("<col=ffff00>" + config.followerName() + "</col>")
			.setType(net.runelite.api.MenuAction.RUNELITE)
			.onClick(e ->
			{
				showRedCross();
				client.addChatMessage(
					net.runelite.api.ChatMessageType.NPC_EXAMINE, "",
					"A travelling scribe. Always writing something down.", null);
				// Being looked up is worth noticing. The examine text is the
				// game's line about the follower; a rule gets to give the
				// follower's line about being looked up.
				speechEngine.dispatch(TriggerEvent.simple(TriggerEvent.Type.EXAMINED));
			});

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
					com.follower.speech.SpeechOutput.OVERHEAD, null, -1, null);
			}
		});
		addFollowerMenuEntry("Talk-to", this::startTalking);
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

		net.runelite.api.Tile sceneTile = client.getTopLevelWorldView().getSelectedSceneTile();
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
			.onClick(e ->
			{
				showRedCross();
				clientThread.invoke(() ->
				{
					if (!follower.stayAt(target))
					{
						speak("I can't get to that spot from here!",
							com.follower.speech.SpeechOutput.OVERHEAD, null, -1, null);
					}
				});
			});
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
		boolean over = !client.isMenuOpen() && follower.isSpawned()
			&& follower.isUnderMouse(client.getMouseCanvasPosition());

		// Latched for the game tick to read. The clickbox is already projected
		// here to draw the hover hint, so knowing the mouse is resting on the
		// follower costs nothing beyond this line.
		hoveredThisTick |= over;

		if (!over)
		{
			return;
		}

		addFollowerMenuEntry("Talk-to", this::startTalking);

		// Shift-hover: the same little action tooltip a real NPC gets, drawn
		// by RuneLite's own tooltip component so the styling is identical.
		if (client.isKeyPressed(net.runelite.api.KeyCode.KC_SHIFT))
		{
			tooltipManager.add(new net.runelite.client.ui.overlay.tooltip.Tooltip(
				"Talk-to <col=ffff00>" + config.followerName() + "</col>"));
		}
	}

	private void addFollowerMenuEntry(String option, Runnable action)
	{
		client.getMenu().createMenuEntry(-1)
			.setOption(option)
			.setTarget("<col=ffff00>" + config.followerName() + "</col>")
			.setType(net.runelite.api.MenuAction.RUNELITE)
			.onClick(e ->
			{
				// An op on an entity flashes the red click cross, like any NPC.
				showRedCross();
				clientThread.invoke(action::run);
			});
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

		// The dialog's tick gate: queued clicks (open, continue, option,
		// dismiss) resolve here, one tick after the input - the same beat a
		// real dialog takes for its server round trip.
		dialog.tick();

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

		// Persist what was learned without waiting for a clean shutdown. Saving
		// only in shutDown() meant a crash, a force-quit or a killed client threw
		// away the whole session's observations - which is exactly why the
		// library had learned stances but not one attack animation. A sighting
		// of someone else's weapon may never come again, so it is worth a write;
		// wrap trims are deliberately left to shutdown, since they are measured
		// from the animation itself and simply re-measure next run.
		//
		// save() no-ops unless something actually changed, so on the
		// overwhelming majority of ticks this costs a comparison.
		if (++ticksSinceLearningSave >= LEARNING_SAVE_TICKS)
		{
			ticksSinceLearningSave = 0;
			stanceLibrary.save();
		}

		// Same reasoning as the stance flush: a crash never gets to write on
		// the way out, and without this every crash would look like an absence
		// of however long the session had been running.
		// Client ticks run many times per game tick; collapse them into one
		// answer here so "how long have they been looking" counts in the same
		// units as everything else a rule can ask about.
		hoverTicks = hoveredThisTick ? hoverTicks + 1 : 0;
		hoveredThisTick = false;
		speechEngine.getContext().setHoverTicks(hoverTicks);

		updateStrandedTeleport();

		if (++ticksSinceLastSeen >= LAST_SEEN_TICKS)
		{
			ticksSinceLastSeen = 0;
			touchLastSeen();
			// Ride the same timer: the counters are worth exactly as much as
			// the last-seen stamp on a crash, and this is already the "write
			// what would otherwise be lost" tick.
			writeCountersIfChanged();
			journal.flushIfDue();

			// A hundred ticks is a minute, so the timer that saves is also the
			// one that measures how long today has run. Stored every minute so
			// a crash still keeps the mark; said only on the minute it crosses,
			// because a record that re-announces every minute for the rest of a
			// long session stops being a compliment and becomes a clock.
			TriggerContext context = speechEngine.getContext();
			int previousBest = context.getRecord("session");
			context.setSessionMinutes(++sessionMinutes);
			if (context.noteRecord("session", sessionMinutes) && !sessionRecordSaid)
			{
				sessionRecordSaid = true;
				speechEngine.dispatch(
					TriggerEvent.record("session", sessionMinutes, previousBest));
			}
		}

		if (ticksSinceLoading < 1000)
		{
			ticksSinceLoading++;
		}

		// A limbo whose thrall never respawns - it expired during the very
		// load that dropped it - ends the possession after a grace period.
		if (thrallLimbo && ++thrallLimboTicks > 15)
		{
			log.debug("Thrall never respawned after the load; ending possession");
			exitThrallMode();
		}

		// No resummon arrived in the grace window, so the thrall really did
		// expire: say goodbye and go home.
		if (thrallExitPendingTicks > 0 && --thrallExitPendingTicks == 0)
		{
			performThrallExit(thrallExitStyle);
		}

		drainSpeechQueue();
		tickScan();
		expireKillClaims();
		updateWander();
		updateRest();

		if (errands != null)
		{
			errands.tick();
		}
		if (flourish != null)
		{
			flourish.tick();
		}

		// After errands, so an errand in progress owns the feet: a follower
		// halfway to a bank should finish the trip rather than be yanked
		// sideways because something attacked. Thrall mode is in the fight, so
		// it is excluded too.
		if (spectate != null)
		{
			spectate.tick(thrallNpc != null || (errands != null && errands.isBusy()));
		}

		// The spawn-in burst waits one tick so the follower has already been
		// rendered standing at the thrall's spot when it plays.
		if (pendingThrallSpawnFxTicks > 0 && --pendingThrallSpawnFxTicks == 0 && thrallNpc != null)
		{
			clientThread.invoke(() ->
				follower.playSpotAnim(spotAnimRepository.get(thrallImpactSpotAnim(thrallStyle))));
		}

		// Stage two of the exit flourish: home, redressed, shimmering back in.
		if (thrallExitTicks > 0 && --thrallExitTicks == 0)
		{
			outfitOverride = null;
			clientThread.invoke(() ->
			{
				follower.resumeFollowing();
				follower.releaseNpcSlave();
				rebuildFollower();
				follower.playSpotAnim(spotAnimRepository.get(THRALL_RETURN_SPOTANIM));
			});
		}

		if (++reloadPollTicks >= 2)
		{
			reloadPollTicks = 0;
			if (dialogLoader.reloadIfChanged())
			{
				sendStatus("Reloaded " + dialogLoader.getStatus());
				for (String problem : dialogLoader.getErrors())
				{
					sendStatus("Dialog: " + problem);
				}
			}
			if (ruleLoader.reloadIfChanged())
			{
				// A reload resets every rule's edge state, so the gear you are
				// wearing and the place you are standing would read as fresh
				// rising edges on the next tick. Same cure as login: baseline
				// first, react to actual changes after.
				speechEngine.primeEdgesOnNextTick();
				// The rule holding the floor is one of the objects just thrown
				// away, and the exemption that lets it speak is by identity.
				speechEngine.clearFloor();
				speechEngine.getContext().setNicknames(ruleLoader.getNicknames());
				sendStatus("Reloaded " + ruleLoader.getStatus());
				reportRuleErrors();
			}
		}

		speechEngine.refreshContext();

		priceWhatYouAreWearing();

		// Nothing at all while a pocket is being worked. Failing repeatedly is
		// already annoying; a companion remarking on each failure, each hit and
		// each success is worse than one that says nothing, and no amount of
		// tuning individual rules gets there - the answer is silence for the
		// whole session.
		//
		// Both ENDS of it are announced though, which is what makes the silence
		// read as the follower giving you room rather than as it having
		// stopped working. They are dispatched with the mute lifted, because
		// otherwise the start line is swallowed by the silence it announces and
		// the end line by the silence it ends.
		boolean thievingNow = speechEngine.getContext().isInThievingSession();
		if (thievingNow != wasThieving)
		{
			speechEngine.setMuted(config.muted());
			if (thievingNow)
			{
				// Anything queued from before belongs to the moment before the
				// silence; letting it trickle out during it is exactly the
				// chatter this is meant to stop.
				speechQueue.clear();
			}
			speechEngine.dispatch(TriggerEvent.simple(thievingNow
				? TriggerEvent.Type.THIEVING_START
				: TriggerEvent.Type.THIEVING_END));
			wasThieving = thievingNow;
		}
		speechEngine.setMuted(config.muted() || thievingNow);

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
	 * Copies the head angles and layout cells off a real dialog the moment one
	 * opens, keeping the follower's dialog calibrated against whatever the game
	 * currently renders. The measuring dumps that produced the baked-in layout
	 * constants have been retired; only the silent adoption remains.
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

				java.awt.Rectangle headRect = widget.getBounds();
				// Relative to the CHATBOX widget - the same base the dialog overlay
				// positions against - not the dialog root, which sits inset from it.
				net.runelite.api.widgets.Widget root = client.getWidget(
					net.runelite.api.gameval.InterfaceID.CHATBOX, 0);
				java.awt.Rectangle rootRect = root == null ? null : root.getBounds();

				// Copy the real head's rectangle, expressed relative to the dialog,
				// so ours sits at the same place and size instead of a guess.
				if (group == net.runelite.api.gameval.InterfaceID.CHAT_RIGHT
					&& headRect != null && rootRect != null
					&& headRect.width > 0 && headRect.height > 0)
				{
					// The player side's right-hand head cell, kept calibrated.
					dialog.setPlayerHeadRect(new java.awt.Rectangle(
						headRect.x - rootRect.x, headRect.y - rootRect.y,
						headRect.width, headRect.height));
				}
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
		maybeAdoptThrall(npc);

		// A chunk load raises one of these for every NPC in the new scene, and
		// each costs a pass over every rule. Only pay it when something listens.
		if (ruleLoader.listensFor(TriggerEvent.Type.NPC_SPAWN))
		{
			speechEngine.dispatch(
				TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, npc.getId(), npc.getName()));
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		if (npc == thrallNpc)
		{
			// NpcDespawned also fires for every NPC a chunk reload drops from
			// the scene. Classify by WHEN: a despawn during (or just after) a
			// LOADING state is the scene shuffling, not the thrall dying.
			if (client.getGameState() != GameState.LOGGED_IN || ticksSinceLoading <= 2)
			{
				log.debug("Thrall NPC dropped by a scene load; holding possession for its respawn");
				thrallNpc = null;
				thrallLimbo = true;
				thrallLimboTicks = 0;
				clientThread.invoke(follower::releaseNpcSlave);
			}
			else
			{
				// Steady play - but this is ALSO what a resummon looks like: the
				// old thrall is removed the moment the new one is cast, and its
				// despawn reaches us before the new spawn. Saying goodbye here
				// made a resummon speak the farewell line. Hold the exit for a
				// few ticks; if a thrall turns up, it was a switch.
				log.debug("Thrall NPC gone; holding the exit in case this is a resummon");
				thrallExitStyle = thrallStyle;
				thrallExitPendingTicks = 3;
				thrallNpc = null;
				clientThread.invoke(follower::endNpcSlaveHolding);
			}
		}
		// As with spawns: the whole scene despawns on a chunk load, and no
		// bundled rule listens for it at all.
		if (ruleLoader.listensFor(TriggerEvent.Type.NPC_DESPAWN))
		{
			speechEngine.dispatch(
				TriggerEvent.npc(TriggerEvent.Type.NPC_DESPAWN, npc.getId(), npc.getName()));
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		// The follower's own lines land in public chat under its name; feeding
		// them back into the rules would let it react to itself.
		if (config.followerName().equals(event.getName()))
		{
			return;
		}
		// ::follower chatwatch - the same idea as watching animations. A
		// chatMessage rule has to match the game's exact wording, and wording
		// taken from memory or a wiki is how a rule ends up never firing.
		if (watchChat)
		{
			sendStatus("Chat [" + event.getType() + "] " + event.getMessage());
			log.info("WATCH chat [{}] {}", event.getType(), event.getMessage());
		}

		speechEngine.dispatch(TriggerEvent.chat(
			event.getMessage(), event.getType().getType(), event.getName()));
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		// Only pay the full rule pass when something actually listens for
		// varbit EVENTS - varbit floods (login inits) are otherwise free.
		// State-based varbitEquals rules evaluate on the tick heartbeat.
		if (event.getVarbitId() != -1 && ruleLoader.isVarbitEventRules())
		{
			speechEngine.dispatch(TriggerEvent.varbit(event.getVarbitId(), event.getValue(), -1));
		}

		// The summoning-cooldown varbit rising is the RELIABLE summon signal:
		// casting mid combat never surfaces the cast animation (the
		// attack/block cycle owns the animation slot), but this rises
		// regardless. Its FALL is just the ten-second cooldown ending and
		// means nothing for the thrall's life.
		if (event.getVarbitId() == THRALL_SUMMONED_VARBIT && config.thrallMode()
			&& event.getValue() == 1)
		{
			log.debug("Thrall summoned (cooldown varbit up)");
			// The thrall NPC may already be in the scene - spawn-event
			// ordering within the tick is not guaranteed.
			adoptExistingThrall();
		}

		// The brightness setting: the chathead's colour table must be on the same
		// gamma as the real chatheads around it, and must follow slider changes.
		if (event.getVarpId() == BRIGHTNESS_VARP)
		{
			com.follower.ui.GameColourTable.setBrightnessSetting(event.getValue());
			log.debug("Brightness setting {} -> colour table gamma {}",
				event.getValue(), com.follower.ui.GameColourTable.getCurrentGamma());
		}
	}

	/** The client's brightness setting (1 Dark .. 4 V.Bright, 2 = Normal). */
	private static final int BRIGHTNESS_VARP = 166;

	/**
	 * The four red click-cross frames, 519-522 (the old api SpriteID
	 * RED_CLICK_ANIMATION_1..4, deprecated without a gameval equivalent name).
	 */
	private static final int RED_CLICK_SPRITE_FIRST = 519;

	/**
	 * The newest spotanim on an actor - the one a GraphicChanged event is
	 * reporting. Replaces the deprecated single-graphic accessors now that
	 * actors carry a list.
	 */
	private static net.runelite.api.ActorSpotAnim latestSpotAnim(net.runelite.api.Actor actor)
	{
		net.runelite.api.ActorSpotAnim latest = null;
		for (net.runelite.api.ActorSpotAnim spotAnim : actor.getSpotAnims())
		{
			if (latest == null || spotAnim.getStartCycle() > latest.getStartCycle())
			{
				latest = spotAnim;
			}
		}
		return latest;
	}

	/**
	 * Prints every player animation id as it plays. For harvesting animation
	 * ids from the running game - the cache gives animations no names, so the
	 * only honest way to find "the one where you write on a scroll" is to
	 * watch somebody do it. Players only: an NPC animation id cannot play on
	 * the follower's player skeleton, so sniffing NPCs would only collect ids
	 * the follower can never use.
	 */
	private boolean sniffAnims;

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		// Every player in the scene teaches their weapon's attack animation,
		// the same way they teach its walk and idle poses. The follower then
		// swings whatever it is actually holding.
		if (event.getActor() instanceof Player)
		{
			Player animating = (Player) event.getActor();
			stanceLibrary.learnAttack(animating, animating.getAnimation());

			if (sniffAnims && animating.getAnimation() != -1)
			{
				sendStatus((animating == client.getLocalPlayer()
					? "You" : String.valueOf(animating.getName()))
					+ " played animation " + animating.getAnimation());
			}
		}

		// The possessed thrall attacking: the follower answers with the PLAYER
		// attack animation for its style's weapon - NPC animation ids cannot
		// play on a player skeleton.
		if (event.getActor() == thrallNpc && thrallNpc != null
			&& thrallNpc.getAnimation() != -1)
		{
			clientThread.invoke(() ->
			{
				// The swing must already face the enemy when it starts.
				follower.faceThrallTarget(true);
				follower.playAnimation(thrallAttackAnimation());
			});
			return;
		}

		if (event.getActor() != client.getLocalPlayer())
		{
			return;
		}

		int animationId = event.getActor().getAnimation();

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

		// A held emote ends the moment the player's animation becomes anything
		// else, which is the only signal there is that they have stopped.
		if (mirroredPose != 0 && animationId != mirroredPose)
		{
			stopPoseMirror();
		}

		// ::follower watch - report ids as they play, so an animation can be
		// identified by performing it rather than guessing from community id lists.
		if (watchAnimations && animationId != -1)
		{
			sendStatus("Animation " + animationId
				+ "  (::follower anim " + animationId + " to replay it)");
			log.info("WATCH self animation {}", animationId);
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
			// Watching everyone is how a graphic gets identified when you do not
			// own the thing that makes it: stand near someone who does and let
			// them perform it. NPCs count too - plenty of effects render on
			// what is being hit rather than on whoever swung.
			if (watchOthers)
			{
				net.runelite.api.ActorSpotAnim other = latestSpotAnim(event.getActor());
				if (other != null)
				{
					String who = event.getActor() instanceof NPC ? "NPC " : "";
					sendStatus(who + event.getActor().getName() + " graphic " + other.getId()
						+ "  (::follower gfx " + other.getId() + " to replay it)");
					log.info("WATCH {}{} graphic {}", who, event.getActor().getName(),
						other.getId());
				}
			}
			return;
		}

		net.runelite.api.ActorSpotAnim spotAnim = latestSpotAnim(event.getActor());
		if (spotAnim == null)
		{
			return;
		}
		int graphicId = spotAnim.getId();
		int graphicHeight = spotAnim.getHeight();
		if (watchAnimations)
		{
			sendStatus("Graphic " + graphicId + " at height " + graphicHeight
				+ "  (::follower gfx " + graphicId + " to replay, add 'set' to keep it)");
			// Also to the log: chat scrolls away, and a captured id is the
			// whole point of watching.
			log.info("WATCH self graphic {} height {}", graphicId, graphicHeight);
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

	/**
	 * NPCs the player has damaged, against the tick it last happened.
	 *
	 * <p>A death event says an NPC died, never who killed it - and a busy place
	 * is full of deaths that are nothing to do with you. The client does mark
	 * whose damage a hitsplat is ({@link net.runelite.api.Hitsplat#isMine()}),
	 * so a kill is claimed only for something the player actually hit.
	 *
	 * <p>Entries expire, which matters twice: an NPC wounded and abandoned must
	 * not be claimed when something else finishes it minutes later, and holding
	 * actor references for a dead scene would keep it from being collected.
	 */
	private final Map<Actor, Integer> damagedByPlayer = new HashMap<>();

	/** How long a hit stays claimable. Comfortably longer than any kill takes to land. */
	private static final int KILL_CLAIM_TICKS = 30;

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (event.getHitsplat().getAmount() <= 0)
		{
			return;
		}
		if (event.getActor() == client.getLocalPlayer())
		{
			// Being hit is combat even when not hitting back, which the
			// interaction check alone would miss.
			speechEngine.getContext().noteDamageTaken();
			speechEngine.dispatch(TriggerEvent.damageTaken(event.getHitsplat().getAmount()));
			dialog.close();
		}
		else if (event.getActor() instanceof NPC && event.getHitsplat().isMine())
		{
			damagedByPlayer.put(event.getActor(), client.getTickCount());
			// Landing one is combat too. Facing an NPC is not enough on its
			// own any more, so this is what arms a fight the player starts.
			speechEngine.getContext().noteDamageDealt();
			noteRecord("hit", event.getHitsplat().getAmount());
		}
	}

	/**
	 * Files a value against a record and announces it if it beat one.
	 *
	 * <p>Keeping the "did it beat one" answer with the announcement is
	 * deliberate: a record that is stored without being said is invisible, and
	 * one that is said without being stored is said again next time.
	 */
	private void noteRecord(String what, int value)
	{
		TriggerContext context = speechEngine.getContext();
		int previous = context.getRecord(what);
		if (context.noteRecord(what, value))
		{
			speechEngine.dispatch(TriggerEvent.record(what, value, previous));
		}
	}

	/** Drops damage claims that have gone stale, so the map cannot grow without bound. */
	private void expireKillClaims()
	{
		if (damagedByPlayer.isEmpty())
		{
			return;
		}
		int cutoff = client.getTickCount() - KILL_CLAIM_TICKS;
		damagedByPlayer.values().removeIf(tick -> tick < cutoff);
	}

	@Subscribe
	public void onActorDeath(net.runelite.api.events.ActorDeath event)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		if (event.getActor() != local)
		{
			// Someone else's death is only news if the player had a hand in it.
			Integer hit = damagedByPlayer.remove(event.getActor());
			if (hit != null && event.getActor() instanceof NPC)
			{
				NPC npc = (NPC) event.getActor();
				// Counted here, where the kill is known to be the player's, so
				// the tally and the event can never disagree about it. Keyed by
				// name rather than id: a player counts kalphites, not the four
				// ids the game happens to use for them.
				String name = npc.getName() == null ? "" : npc.getName();
				speechEngine.getContext().tally("kills" + TriggerContext.TODAY);
				int count = speechEngine.getContext().tally(
					"kill:" + name.toLowerCase(Locale.ROOT));
				speechEngine.dispatch(TriggerEvent.kill(
					npc.getId(), name, npc.getCombatLevel(), count));
			}
			return;
		}

		// Where, as well as that: the death spot is remembered for the session,
		// and walking back over it later has its own lines.
		speechEngine.getContext().noteDeath(local.getWorldLocation());
		speechEngine.getContext().tallyBoth("deaths");
		speechEngine.dispatch(TriggerEvent.death());
		dialog.close();
	}

	// NpcLootReceived comes from the core LootManager, so it fires regardless
	// of which plugins are enabled. The loot tracker's own LootReceived event
	// would have been silent whenever that plugin was off.
	@Subscribe
	public void onNpcLootReceived(net.runelite.client.events.NpcLootReceived event)
	{
		dispatchLoot(event.getItems());
	}

	@Subscribe
	public void onPlayerLootReceived(net.runelite.client.events.PlayerLootReceived event)
	{
		dispatchLoot(event.getItems());
	}

	private void dispatchLoot(java.util.Collection<net.runelite.client.game.ItemStack> items)
	{
		long total = 0;
		String best = null;
		long bestValue = -1;
		for (net.runelite.client.game.ItemStack stack : items)
		{
			long price = (long) itemManager.getItemPrice(stack.getId()) * stack.getQuantity();
			total += price;
			if (price > bestValue)
			{
				bestValue = price;
				best = itemManager.getItemComposition(stack.getId()).getName();
			}
		}
		if (total > 0)
		{
			int worth = (int) Math.min(total, Integer.MAX_VALUE);
			// Settled BEFORE the loot line, so the follower collects on its
			// prediction rather than remarking on the drop and then
			// remembering it had money on it.
			speechEngine.getContext().settleBet(worth);
			speechEngine.dispatch(TriggerEvent.loot(worth, best));
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
			speechEngine.getContext().tallyBoth("levels");
			speechEngine.dispatch(TriggerEvent.levelUp(skill.getName(), level));
		}
	}

	// --------------------------------------------------------------- commands

	/**
	 * The instruments used to BUILD the plugin, hidden behind the Developer
	 * commands setting.
	 *
	 * <p>Everything here answers a question about the plugin's internals -
	 * which face priority a model part landed in, what the wrap point of an
	 * animation measured at, whether the live cache parse still matches the
	 * offline dump. Left ungated they were a wall of chat text a step away from
	 * anyone who typed the wrong word.
	 *
	 * <p>Commands a player has a reason to run are deliberately NOT here: say,
	 * here, anim, errand, outfit, reload, copy, fix, rebuild, status and where
	 * all stay available.
	 *
	 * <p>{@code watch} and {@code stance} are gated too, despite being useful
	 * to a determined user - one prints an animation id for writing a rule, the
	 * other hand-sets a weapon's animations. Both are authoring tools for
	 * someone extending the plugin rather than playing with it, and every place
	 * the documentation points at them names the setting they need.
	 */
	private static final Set<String> DEVELOPER_COMMANDS = new HashSet<>(java.util.Arrays.asList(
		"priorities", "palette", "harvest", "hidden", "height",
		"pitchsweep", "headsweep", "head", "followtrace",
		"wraplerp", "wrapauto", "wrapearly", "pose",
		"animinfo", "animtrace", "errandscan", "cachecheck", "stanceaudit",
		"watch", "stance", "gfx", "spectate", "shield", "centre", "center", "loot",
		"scan", "heights", "mood", "chatwatch", "fire", "want", "transcript",
		"thieftargets", "sniffanims", "finditem", "prop", "propoffset"));

	/**
	 * Notices when you have clicked the tile the follower is standing on.
	 *
	 * <p>Read from the selected tile rather than from the click's raw
	 * parameters: getSelectedSceneTile is the client's own answer to "which
	 * tile is this", and it is valid for exactly as long as the click is being
	 * handled. A null means the click was not a tile after all, and nothing
	 * happens - being wrong here should cost nothing.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		noteIntentFrom(event.getMenuOption());

		if (event.getMenuAction() != net.runelite.api.MenuAction.WALK
			|| !follower.isSpawned())
		{
			return;
		}
		// WorldView's, not the deprecated Client shortcut: the shortcut just
		// forwards to the top-level view, and it is on its way out.
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		net.runelite.api.Tile clicked = view == null ? null : view.getSelectedSceneTile();
		WorldPoint standing = follower.getWorldLocation();
		if (clicked != null && standing != null
			&& standing.equals(clicked.getWorldLocation()))
		{
			speechEngine.getContext().noteUnderfoot();
		}
	}

	/**
	 * What the player has just said they are about to do.
	 *
	 * <p>Read from the menu option because it is the earliest honest signal
	 * there is. Everything else the follower could watch - the animation, the
	 * hitsplat, the interaction target - arrives after the player has already
	 * committed, and in the pickpocket case the interaction target arrives
	 * FIRST and looks exactly like a fight for the whole walk over.
	 *
	 * <p>Matched on the WORDS rather than on exact strings. An exact list has
	 * to be right about every target in the game and silently covers none of
	 * the ones it missed - and the whole point of the silence is that it should
	 * hold for anything the player is robbing, not just for elves. Anything
	 * offering to pickpocket or steal is thieving; nothing else in the game
	 * words an option that way.
	 *
	 * <p>{@code ::follower thieftargets} lists what the NPCs around you
	 * actually offer, so this can be checked rather than believed.
	 */
	private void noteIntentFrom(String option)
	{
		if (option == null || option.isEmpty())
		{
			return;
		}
		TriggerContext context = speechEngine.getContext();
		if (isThievingOption(option))
		{
			context.noteThievingIntent();
		}
		else if (option.equalsIgnoreCase("Attack"))
		{
			context.noteFightIntent();
		}
	}

	/** Whether a menu option means "I am about to take that". */
	static boolean isThievingOption(String option)
	{
		if (option == null)
		{
			return false;
		}
		// The game colours menu text with tags; strip them before matching.
		String plain = option.replaceAll("<[^>]*>", "").toLowerCase(Locale.ROOT);
		return plain.contains("pickpocket") || plain.contains("steal");
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!COMMAND.equalsIgnoreCase(event.getCommand()))
		{
			return;
		}

		String[] args = event.getArguments();
		String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";

		if (DEVELOPER_COMMANDS.contains(sub) && !config.developerMode())
		{
			sendStatus("'" + sub + "' is a developer command - turn on Developer commands"
				+ " in the plugin settings to use it.");
			return;
		}

		switch (sub)
		{
			case "reload":
				ruleLoader.reload();
				speechEngine.getContext().setNicknames(ruleLoader.getNicknames());
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
				// poking through a shoulder looks like. The earlier probe showed the
				// COMPOSED model matching the client exactly; the per-stage lines
				// below chase the remaining suspect - the RENDER model, after the
				// animation pipeline has had its way with it.
				clientThread.invoke(() ->
				{
					compareAgainstClient("PROBE");
					Player me = client.getLocalPlayer();
					logPriorities("player live model",
						me == null ? null : me.getModel());
					logPriorities("follower base model", follower.getBaseModel());
					logPriorities("follower render model", follower.getRenderModel());

					// The model actually DRAWN each frame is the animated temp the
					// animation pipeline produces, not the base we set - if the
					// temp loses the priority array, the engine falls back to its
					// simple depth path whose insertion-order ties are exactly the
					// torso-over-cape / hair-over-hat symptom.
					net.runelite.api.Model base = follower.getBaseModel();
					net.runelite.api.Animation idle =
						client.loadAnimation(com.follower.follower.PlayerPose.IDLE);
					if (base != null && idle != null)
					{
						net.runelite.api.Model temp =
							client.applyTransformations(base, idle, 0, null, 0);
						logPriorities("animated temp", temp);
						if (temp != null)
						{
							logPriorities("detached merge of temp",
								client.mergeModels(new net.runelite.api.Model[]{temp}, 1));
						}
					}
				});
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

			case "finditem":
			{
				// Wearable items by name, from the same index the outfit picker
				// uses. For finding the follower a book to hold: animations
				// carry no prop, so the prop has to be an item, and items DO
				// have names to search.
				if (args.length < 2)
				{
					sendStatus("Usage: ::follower finditem <name fragment>");
					break;
				}
				String fragment = String.join(" ",
					java.util.Arrays.copyOfRange(args, 1, args.length))
					.toLowerCase(Locale.ROOT);
				int shown = 0;
				int matches = 0;
				for (Map.Entry<Integer, KitType> entry : slotIndex.entrySet())
				{
					String name = modelRepository.itemName(entry.getKey());
					if (name == null || !name.toLowerCase(Locale.ROOT).contains(fragment))
					{
						continue;
					}
					matches++;
					if (shown < 15)
					{
						shown++;
						sendStatus(entry.getKey() + "  " + name + "  ("
							+ entry.getValue().name().toLowerCase(Locale.ROOT) + ")");
					}
				}
				sendStatus(matches == 0
					? "No wearable item matches '" + fragment + "'."
					: matches + " wearable match(es)." + (matches > shown
						? " Showing " + shown + "; narrow the name." : "")
					+ " Try one with ::follower prop <id>.");
				break;
			}

			case "prop":
			{
				// A transient held item, for pairing with pose/anim: prop the
				// book, play the reading animation, judge the pair together.
				if (args.length < 2)
				{
					sendStatus("Usage: ::follower prop <itemId>  (0 to clear)");
					break;
				}
				try
				{
					int itemId = Integer.parseInt(args[1]);
					if (itemId <= 0)
					{
						propSlot = null;
						propItemId = 0;
						// A nudge belongs to the item it was eyeballed for.
						appearanceComposer.setItemAdjustment(-1, 0, 0, 0);
						clientThread.invoke(this::rebuildFollower);
						sendStatus("Prop cleared.");
						break;
					}
					clientThread.invoke(() ->
					{
						KitType slot = resolveSlot(itemId);
						if (slot == null)
						{
							sendStatus("Item " + itemId + " is not wearable, so it"
								+ " cannot be held. ::follower finditem to hunt.");
							return;
						}
						propSlot = slot;
						propItemId = itemId;
						appearanceComposer.setItemAdjustment(-1, 0, 0, 0);
						rebuildFollower();
						String name = modelRepository.itemName(itemId);
						sendStatus("Propped " + (name == null ? "item " + itemId : name)
							+ " in the " + slot.name().toLowerCase(Locale.ROOT)
							+ " slot. Pair it: ::follower pose <animId>."
							+ " ::follower prop 0 to clear.");
					});
				}
				catch (NumberFormatException e)
				{
					sendStatus("Usage: ::follower prop <itemId>  (0 to clear)");
				}
				break;
			}

			case "propoffset":
			{
				// The nudging half of the prop workflow. The item model's place
				// on the body is baked into its vertices and authored for a
				// different pose, so a prop that sits wrong under the reading
				// animation is corrected by eye: nudge, look, nudge again.
				// y is vertical and NEGATIVE is up; a tile is 128 units, so
				// useful steps are 4 to 16.
				if (propSlot == null)
				{
					sendStatus("No prop is held. ::follower prop <itemId> first.");
					break;
				}
				if (args.length < 4)
				{
					sendStatus("Usage: ::follower propoffset <x> <y> <z>   (0 0 0"
						+ " to reset; negative y is up; currently "
						+ appearanceComposer.describeItemAdjustment() + ")");
					break;
				}
				try
				{
					float x = Float.parseFloat(args[1]);
					float y = Float.parseFloat(args[2]);
					float z = Float.parseFloat(args[3]);
					appearanceComposer.setItemAdjustment(propItemId, x, y, z);
					clientThread.invoke(this::rebuildFollower);
					sendStatus("Prop nudged: " + appearanceComposer.describeItemAdjustment()
						+ ". Keep the pose running and adjust until it sits right.");
				}
				catch (NumberFormatException e)
				{
					sendStatus("Usage: ::follower propoffset <x> <y> <z>");
				}
				break;
			}

			case "sniffanims":
				sniffAnims = !sniffAnims;
				sendStatus(sniffAnims
					? "Printing every player animation with its id. Do the thing"
						+ " (read a clue, use a lectern) or stand near someone doing"
						+ " it, then preview with ::follower pose <id>."
						+ " ::follower sniffanims again to stop."
					: "Stopped sniffing animations.");
				break;

			case "chatwatch":
				watchChat = !watchChat;
				sendStatus(watchChat
					? "Printing every chat message with its type. Do the thing you"
						+ " want a rule for and copy the wording exactly."
						+ " ::follower chatwatch again to stop."
					: "Stopped watching chat.");
				break;

			case "watch":
				watchAnimations = !watchAnimations;
				// "all" widens it to everyone in the scene, for identifying an
				// effect you cannot produce yourself.
				watchOthers = watchAnimations && args.length > 1
					&& "all".equalsIgnoreCase(args[1]);
				sendStatus(watchAnimations
					? "Watching " + (watchOthers ? "everyone's" : "your")
						+ " animations and graphics. Perform the one you want and I'll "
						+ "print its id. ::follower watch again to stop."
					: "Stopped watching.");
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

			case "errand":
			{
				if (errands != null)
				{
					errands.force(args.length > 1 ? args[1] : null);
					sendStatus("Errand forced" + (args.length > 1 ? ": " + args[1] : ""));
				}
				break;
			}

			case "errandscan":
			{
				if (errands != null)
				{
					errands.debugScan();
					sendStatus("Errand scan logged");
				}
				break;
			}

			case "shield":
			{
				// A stored setting always beats a changed default, so a shipped
				// default that arrives after someone has touched the setting
				// would never reach them. This clears the six back to what the
				// plugin ships, and with no argument just reports them.
				String[] keys = {
					"spectateShieldAnimation", "spectateShieldGraphic",
					"spectateShieldChannelStart", "spectateShieldChannelAnimation",
					"spectateShieldChannelGraphic", "spectateShieldChannelEnd",
					"spectateShieldEndAnimation", "spectateShieldEndGraphic",
				};
				if (args.length > 1 && "defaults".equalsIgnoreCase(args[1]))
				{
					for (String key : keys)
					{
						configManager.unsetConfiguration(FollowerConfig.GROUP, key);
					}
					sendStatus("Shield settings reset to the shipped defaults.");
				}
				sendStatus("Shield: summon " + config.spectateShieldAnimation()
					+ "/" + config.spectateShieldGraphic()
					+ " | sit " + config.spectateShieldChannelStart()
					+ " | channel " + config.spectateShieldChannelAnimation()
					+ "/" + config.spectateShieldChannelGraphic()
					+ " | stand " + config.spectateShieldChannelEnd()
					+ " | finish " + config.spectateShieldEndAnimation()
					+ "/" + config.spectateShieldEndGraphic()
					+ "  (animation/graphic)");
				break;
			}

			case "heights":
			{
				clientThread.invoke(() ->
				{
					for (String line : follower.describeHeights())
					{
						sendStatus(line);
						log.info("HEIGHTS {}", line);
					}
				});
				break;
			}

			case "centre":
			case "center":
			{
				// Measures how far an animation throws the model off its tile,
				// so a correction can be a number rather than a guess. Run it
				// while the pose in question is playing.
				// ::follower centre <n> nudges the correction live, so the last
				// few units can be dialled in by eye without a rebuild.
				if (args.length > 1)
				{
					try
					{
						int bias = Integer.parseInt(args[1]);
						clientThread.invoke(() -> follower.setRecentreBias(bias));
						// The sign reads backwards from the field it feeds:
						// recentreForward is negative while kneeling, so ADDING
						// to it shortens the correction and lets the model sit
						// further forward.
						sendStatus("Recentre bias " + bias
							+ " units (negative pulls it further back, positive lets it forward).");
					}
					catch (NumberFormatException e)
					{
						sendStatus("Numbers only: ::follower centre <units>");
					}
					break;
				}

				clientThread.invoke(() ->
				{
					int[] centre = follower.modelCentre();
					// Labelled with the pose it belongs to: a reading is
					// meaningless without knowing what was playing, and a
					// standing baseline is needed to tell the animation's
					// displacement from the model's own asymmetry.
					int pose = follower.getActivePose();
					sendStatus(centre == null
						? "No model to measure."
						: "Pose " + pose + ": centre x=" + centre[0] + " z=" + centre[1]
							+ " (128 = one tile), bias " + follower.getRecentreBias());
					if (centre != null)
					{
						log.info("MODEL CENTRE pose={} x={} z={}", pose, centre[0], centre[1]);
					}
				});
				break;
			}

			case "scan":
			{
				int seconds = 15;
				if (args.length > 1)
				{
					try
					{
						seconds = Integer.parseInt(args[1]);
					}
					catch (NumberFormatException e)
					{
						sendStatus("Numbers only: ::follower scan [seconds]");
						break;
					}
				}
				scanTicksLeft = Math.max(1, seconds) * 5 / 3;
				scanStartTick = client.getTickCount();
				scanTimeline.clear();
				// -2 rather than -1: -1 is a real value for "no animation", and
				// starting there would swallow the first change.
				scanAnimation = -2;
				scanPose = -2;
				scanIdlePose = -2;
				scanGraphic = -2;
				scanCombat = "";
				sendStatus("Scanning your animation slots for " + seconds
					+ "s - perform it now.");
				break;
			}

			case "loot":
			{
				// Fakes a loot drop, because the real thing cannot be tested on
				// demand: the reaction fires on kill loot, which means waiting
				// for an actual 100k+ drop. Dropping an item yourself fires no
				// loot event at all - deliberately, or the follower would cheer
				// every time you rearranged your bank.
				if (args.length < 2)
				{
					sendStatus("::follower loot <value> [item name] - fake a drop, e.g."
						+ " ::follower loot 2500000 Dragon warhammer");
					break;
				}
				try
				{
					int value = Integer.parseInt(args[1]);
					String item = args.length > 2
						? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
						: "Coins";
					speechEngine.dispatch(TriggerEvent.loot(value, item));
					sendStatus("Faked a " + item + " drop worth " + value + ".");
				}
				catch (NumberFormatException e)
				{
					sendStatus("Numbers only: ::follower loot <value> [item name]");
				}
				break;
			}

			case "spectate":
			{
				if (spectate == null)
				{
					sendStatus("Spectating is not running.");
					break;
				}
				String state = spectate.describe(
					thrallNpc != null || (errands != null && errands.isBusy()));
				sendStatus(state);
				log.info("{}", state);
				break;
			}

			case "gfx":
			{
				// Auditions a spotanim on the follower. "What a protective
				// shield looks like" has no answer in the cache to measure, so
				// the shield effect is chosen by eye and this is how.
				if (args.length < 2)
				{
					sendStatus("::follower gfx <graphicId> - play a spotanim on the follower");
					sendStatus("::follower gfx <graphicId> set - and keep it as the shield");
					break;
				}
				try
				{
					int id = Integer.parseInt(args[1]);
					com.follower.appearance.SpotAnimRepository.Entry fx = spotAnimRepository.get(id);
					if (fx == null)
					{
						sendStatus("No graphic " + id + " in the cache.");
						break;
					}
					clientThread.invoke(() -> follower.playSpotAnim(fx));

					// Auditioning is only useful if the winner can be kept
					// without going hunting through the settings. All three
					// stages are set together: "the shield particle" is one
					// idea, and the stages differ by animation, not by effect.
					// Per-stage graphics remain available in the settings.
					if (args.length > 2 && "set".equalsIgnoreCase(args[2]))
					{
						configManager.setConfiguration(FollowerConfig.GROUP,
							"spectateShieldGraphic", id);
						configManager.setConfiguration(FollowerConfig.GROUP,
							"spectateShieldChannelGraphic", id);
						configManager.setConfiguration(FollowerConfig.GROUP,
							"spectateShieldEndGraphic", id);
						sendStatus("Graphic " + id
							+ " is now the shield effect for summon, channel and finish.");
					}
					else
					{
						sendStatus("Playing graphic " + id + " on the follower."
							+ " Add 'set' to keep it as the shield.");
					}
				}
				catch (NumberFormatException e)
				{
					sendStatus("Numbers only: ::follower gfx <graphicId> [set]");
				}
				break;
			}

			case "cachecheck":
			{
				clientThread.invokeLater(this::runCacheCheck);
				break;
			}

			case "stanceaudit":
			{
				clientThread.invokeLater(this::runStanceAudit);
				break;
			}

			case "stance":
			{
				// Weapon animations are not in the cache anywhere (measured -
				// see tools/cache-dumper probes), so a weapon nobody can be
				// seen wielding has to be entered by hand.
				if (args.length < 2)
				{
					sendStatus("::follower stance <weaponId> - shows what is known");
					sendStatus("::follower stance <weaponId> <idle> <walk> <run> [attack]"
						+ " - sets it by hand");
					break;
				}
				try
				{
					int weaponId = Integer.parseInt(args[1]);
					if (args.length < 5)
					{
						com.follower.follower.StanceLibrary.Stance known =
							stanceLibrary.describe(weaponId);
						String name = modelRepository.itemName(weaponId);
						sendStatus(known == null
							? (name == null ? "Item " + weaponId : name)
								+ ": no stance known - it will stand unarmed"
							: (name == null ? "Item " + weaponId : name)
								+ ": idle " + known.idle + ", walk " + known.walk
								+ ", run " + known.run
								+ ", attack " + (known.attack > 0 ? known.attack : "unknown"));
						break;
					}
					stanceLibrary.setManual(weaponId,
						Integer.parseInt(args[2]), Integer.parseInt(args[3]),
						Integer.parseInt(args[4]),
						args.length > 5 ? Integer.parseInt(args[5]) : 0);
					stanceLibrary.save();
					clientThread.invoke(this::rebuildFollower);
					sendStatus("Stance set for item " + weaponId + " and saved.");
				}
				catch (NumberFormatException e)
				{
					sendStatus("Numbers only: ::follower stance <weaponId> <idle> <walk> <run> [attack]");
				}
				break;
			}

			case "outfit":
			{
				if (args.length < 2)
				{
					sendStatus("Profiles: " + (profileStore.names().isEmpty()
						? "(none saved)" : String.join(", ", profileStore.names())));
					break;
				}
				// Profile names may contain spaces; the args past "outfit" are one name.
				StringBuilder joined = new StringBuilder();
				for (int i = 1; i < args.length; i++)
				{
					if (joined.length() > 0)
					{
						joined.append(' ');
					}
					joined.append(args[i]);
				}
				loadOutfitProfile(joined.toString());
				break;
			}

			// Mood is felt through what the follower says rather than shown, so
			// this is the only way to see the number behind it.
			case "thieftargets":
			{
				// Answers "does the silence cover this target" without having
				// to rob one and watch. Reads the options the game itself
				// hands out, so it cannot drift from what the player sees.
				java.util.Map<String, String> found = new java.util.TreeMap<>();
				int scanned = 0;
				for (NPC npc : client.getTopLevelWorldView().npcs())
				{
					net.runelite.api.NPCComposition composition =
						npc.getTransformedComposition() != null
							? npc.getTransformedComposition() : npc.getComposition();
					if (composition == null || composition.getActions() == null)
					{
						continue;
					}
					scanned++;
					for (String action : composition.getActions())
					{
						if (isThievingOption(action))
						{
							found.put(composition.getName() + " - " + action,
								"id " + composition.getId());
						}
					}
				}
				sendStatus("Scanned " + scanned + " NPCs nearby.");
				if (found.isEmpty())
				{
					sendStatus("None of them offer anything worth stealing.");
				}
				for (java.util.Map.Entry<String, String> e : found.entrySet())
				{
					sendStatus("  " + e.getKey() + "  (" + e.getValue() + ")");
				}
				break;
			}

			case "transcript":
			{
				if (args.length > 1 && "stats".equalsIgnoreCase(args[1]))
				{
					for (String line : journal.summary())
					{
						sendStatus(line);
					}
					break;
				}
				boolean on = journal.toggle();
				configManager.setConfiguration(FollowerConfig.GROUP, "transcriptOn", on);
				sendStatus("Transcript " + (on ? "on" : "off")
					+ " - " + journal.getFile());
				if (on)
				{
					sendStatus("'::follower transcript stats' for the session so far.");
				}
				break;
			}

			case "mood":
			{
				com.follower.speech.TriggerContext context = speechEngine.getContext();
				if (args.length > 1)
				{
					try
					{
						int to = Integer.parseInt(args[1]);
						context.adjustMood(to - context.getMood());
					}
					catch (NumberFormatException e)
					{
						sendStatus("::follower mood [0-100]");
						break;
					}
				}
				sendStatus("Mood " + context.getMood() + " (" + context.getMoodBand() + ")");

				// The other half of how much gets said, and the half with no
				// visible symptom: a follower resting after a burst and a
				// follower with nothing to say look exactly alike.
				com.follower.speech.SpeechDirector director = speechEngine.getDirector();
				long now = System.currentTimeMillis();
				sendStatus(String.format("Pace: intensity %.1f, %s%s",
					director.getIntensity(),
					director.isRelaxing(now)
						? "resting for another " + (director.relaxRemainingMs(now) / 1000) + "s"
						: "listening",
					director.isSettlingIn() ? ", still settling in" : ""));
				break;
			}

			// Fires a rule out of nowhere. Several of the newer ones only
			// happen on a small roll after a minute of standing still, or on
			// the hundredth login, and waiting for those in a live client is
			// hoping rather than testing.
			case "fire":
			{
				if (args.length < 2)
				{
					sendStatus("::follower fire <rule-id> - try ask-outing, want-fulfilled, examined");
					break;
				}
				String ruleId = args[1];
				if (speechEngine.force(ruleId))
				{
					sendStatus("Fired '" + ruleId + "'");
				}
				else
				{
					sendStatus("No rule called '" + ruleId + "'");
				}
				break;
			}

			// What the follower is currently hoping for, which is otherwise
			// only visible by going there and seeing whether it notices.
			case "want":
			{
				com.follower.speech.TriggerContext context = speechEngine.getContext();
				if (!context.isWanting())
				{
					sendStatus("Not hoping for anything. Question window "
						+ (context.isAwaitingAnswer() ? "OPEN - answer yes or no" : "closed"));
					break;
				}
				int wanted = context.getWantRegion();
				int here = context.getRegionId();
				sendStatus("Wants " + context.getWantLabel() + " = region " + wanted
					+ ". You are in region " + here
					+ (wanted == here ? " - MATCH, it should fire this tick"
					: " - no match, keep going")
					+ ". " + (context.getWantTicksLeft() / 100) + " minutes left.");
				break;
			}

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
				sendStatus("::follower say <text> | here | anim <id...> | errand | "
					+ "outfit <name> | copy | reload | rebuild | fix | status | where");
				if (config.developerMode())
				{
					sendStatus("Developer: watch | stance <weaponId> [idle walk run attack] | "
						+ "priorities | palette | harvest | hidden | height <n> | "
						+ "pitchsweep | headsweep | head <...> | followtrace | "
						+ "wraplerp | wrapauto | wrapearly | pose <id> | animinfo | "
						+ "animtrace | errandscan | cachecheck | stanceaudit | "
						+ "mood [0-100] | chatwatch | sniffanims | finditem <name> | "
						+ "prop <itemId> | propoffset <x y z> | fire <rule-id> | want");
				}
			// ::follower interp was removed: the interpolation filter is keyed on
			// animation id, so it could not be changed for the follower without
			// changing it for the player too.
				break;
		}
	}


	// ----------------------------------------------------------------- output

	/**
	 * One line waiting its turn, kept whole so the overhead text, the chatbox
	 * mirror and any animation stay together.
	 */
	private static final class Utterance
	{
		private final String text;
		private final SpeechOutput output;
		private final SpeechRule rule;
		private final int animationId;
		private final Runnable onSaid;
		private final long queuedAtMs;

		Utterance(String text, SpeechOutput output, SpeechRule rule, int animationId,
			Runnable onSaid)
		{
			this.text = text;
			this.output = output;
			this.rule = rule;
			this.animationId = animationId;
			this.onSaid = onSaid;
			this.queuedAtMs = System.currentTimeMillis();
		}
	}

	private final java.util.ArrayDeque<Utterance> speechQueue = new java.util.ArrayDeque<>();

	/** When the line currently being spoken stops occupying the overhead box. */
	/**
	 * A record of what was actually said, and of what was held back. Off by
	 * default and behind the developer flag: it writes a file about the
	 * player's session, which is a surprising thing for a plugin to do
	 * unasked.
	 */
	private final com.follower.speech.SpeechJournal journal =
		new com.follower.speech.SpeechJournal();

	private long speakingUntilMs;

	/**
	 * How many lines may wait. Two rules firing together is the case worth
	 * handling - walking into a boss both spots it and starts a fight - while a
	 * deep backlog would have the follower narrating minutes late.
	 */
	private static final int SPEECH_QUEUE_LIMIT = 3;

	/** Nothing holds the overhead, or the queue behind it, longer than this. */
	private static final long MAX_SPEECH_MS = 12_000L;

	/** A breath between lines, so two in a row do not read as one. */
	private static final long SPEECH_GAP_MS = 400;

	/**
	 * A queued line older than this is dropped rather than said out of its
	 * moment.
	 *
	 * <p>Derived rather than chosen, because the obvious number is wrong. This
	 * has to exceed the longest a single line can occupy the overhead, or a
	 * line queued behind a long one is discarded before the floor it is waiting
	 * for ever frees - and the queue exists precisely for two rules firing at
	 * once, which is when that happens.
	 *
	 * <p>It used to be a flat twelve seconds, which was fine while every line
	 * was shown for four. Reading time made the display scale with the line and
	 * capped it at twelve, so the two numbers met exactly: a maximum-length line
	 * holds the floor for {@value #MAX_SPEECH_MS} plus a breath, and anything
	 * behind it aged out with milliseconds to spare. Setting the minimum
	 * display time high enough was sufficient to stop the queue delivering
	 * anything at all, ever, using nothing but a slider the plugin offers.
	 */
	private static final long SPEECH_STALE_MS = MAX_SPEECH_MS + SPEECH_GAP_MS + 3_000L;

	/**
	 * Queues a line rather than letting it stamp over whatever is being said.
	 *
	 * <p>Rules fire independently, so arriving at a boss can trigger the sighting
	 * and the start of the fight within a tick of each other. The overhead box
	 * holds one message, so the second used to replace the first mid-word and
	 * both were lost.
	 */
	private void speak(String text, SpeechOutput output, SpeechRule rule, int animationId,
		Runnable onSaid)
	{
		// A silent rule - an emote mirror, a fidget - contends for nothing:
		// the queue exists to serialise the overhead text box, and holding an
		// emote back until a line finishes would have the follower wave at
		// something you did five seconds ago.
		if (text.isEmpty())
		{
			speakNow(new Utterance(text, output, rule, animationId, onSaid));
			return;
		}

		long now = System.currentTimeMillis();
		if (now < speakingUntilMs)
		{
			if (speechQueue.size() < SPEECH_QUEUE_LIMIT)
			{
				speechQueue.addLast(new Utterance(text, output, rule, animationId, onSaid));
			}
			return;
		}
		speakNow(new Utterance(text, output, rule, animationId, onSaid));
	}

	/**
	 * Starts the next queued line once the current one has had its time.
	 * Called every game tick.
	 */
	private void drainSpeechQueue()
	{
		long now = System.currentTimeMillis();
		while (!speechQueue.isEmpty()
			&& now - speechQueue.peekFirst().queuedAtMs > SPEECH_STALE_MS)
		{
			// Said too late is worse than not said: a boss sighting a dozen
			// seconds after the fact is just noise.
			speechQueue.removeFirst();
		}
		if (speechQueue.isEmpty() || now < speakingUntilMs)
		{
			return;
		}
		speakNow(speechQueue.removeFirst());
	}

	/**
	 * How long a line needs to be on screen to be read.
	 *
	 * <p>A fixed duration is the wrong model for text of varying length. All
	 * the guidance the speech rules were written against is about recorded
	 * VOICE, which sets its own pace; ours is text on a timer, where the
	 * constraint is reading speed. Subtitling practice puts that at about 17
	 * characters a second, and against a flat four seconds 94 of the shipped
	 * lines could not be read in the time they were shown - the longest needing
	 * six.
	 *
	 * <p>Never shorter than the configured minimum, so nothing that reads
	 * comfortably today gets snatched away; longer only where the line earns
	 * it. Capped, because a runaway line should not hold the floor all day -
	 * the queue waits on this too.
	 */
	private long readingTimeMs(String text)
	{
		int cps = Math.max(1, config.readingSpeed());
		long needed = (long) text.length() * 1000L / cps;
		return Math.min(MAX_SPEECH_MS, Math.max(config.speechDurationMs(), needed));
	}

	private void speakNow(Utterance utterance)
	{
		String text = utterance.text;
		SpeechOutput output = utterance.output;
		SpeechRule rule = utterance.rule;
		int animationId = utterance.animationId;

		// The engine's said-conditional state latches here and nowhere else:
		// this is the single door every utterance that survives the queue goes
		// through, so a line the queue drops - stale, displaced, cleared with
		// the scene - opens nothing. A wish nobody heard used to leave a gift
		// option sitting in the Talk-to box with no ask behind it.
		if (utterance.onSaid != null)
		{
			utterance.onSaid.run();
		}

		journal.spoke(rule, text);

		if (!text.isEmpty() && output.showsOverhead())
		{
			long showFor = readingTimeMs(text);
			overlay.show(text, showFor);
			speakingUntilMs = System.currentTimeMillis() + showFor + SPEECH_GAP_MS;
		}

		if (!text.isEmpty() && config.mirrorToChat())
		{
			// Every spoken line lands in the chatbox as PUBLIC CHAT under the
			// follower's name - "Name: message" in the game's own chat colours
			// - exactly as a real player's overhead words mirror into chat.
			clientThread.invoke(() -> client.addChatMessage(
				ChatMessageType.PUBLICCHAT, config.followerName(), text, null));
		}

		// The writing flourish: the scroll comes out as the line is said, so
		// the claim and the act happen together. Best effort - mid-errand or
		// otherwise spoken for, the words stand alone, which still reads fine;
		// the gesture without the words never happens the other way round.
		if (rule != null && rule.prop != null && flourish != null
			&& !text.isEmpty()
			&& follower.isSpawned() && !follower.isStaying()
			&& !follower.isNpcSlaved() && !errands.isBusy() && !dialog.isOpen())
		{
			clientThread.invoke(() -> flourish.start(
				rule.prop.item == null ? 0 : rule.prop.item,
				rule.prop.pose == null ? 0 : rule.prop.pose,
				rule.prop.ticks == null ? 8 : rule.prop.ticks));
		}

		// A held emote takes the pose path instead of playing anything: the
		// follower matches the loop for as long as the player keeps it up.
		if (rule != null && Boolean.TRUE.equals(rule.mirrorPose)
			&& utterance.animationId > 0)
		{
			startPoseMirror(utterance.animationId);
			return;
		}

		// Plant it first, so the animation starts against a follower that is
		// already stopped rather than one that stops a tick into the clip.
		if (rule != null && Boolean.TRUE.equals(rule.holdStill) && rule.hasAnimationAction())
		{
			emoteHold = true;
			clientThread.invoke(follower::stayHere);
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
				net.runelite.api.ActorSpotAnim active = local == null ? null : latestSpotAnim(local);
				if (active != null)
				{
					mirrorGraphic(active.getId(), active.getHeight());
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
			net.runelite.api.Model composed = appearanceComposer.compose(yours);
			net.runelite.api.Model clients = local.getModel();
			log.info("{} composed: {}", tag, describePriorities(composed));
			log.info("{} client:   {}", tag, describePriorities(clients));

			// Identical histograms can still hide two rendering-relevant
			// differences: a transparency array (which reroutes the model onto
			// the GPU's alpha path, drawn without depth writes) and FACE ORDER
			// (equal-priority coincident faces tie-break by draw order).
			if (composed != null && clients != null)
			{
				log.info("{} alpha: composed={} trans={} | client={} trans={}", tag,
					composed.getFaceTransparencies() == null ? "null"
						: composed.getFaceTransparencies().length + " entries",
					composed.getTransparency(),
					clients.getFaceTransparencies() == null ? "null"
						: clients.getFaceTransparencies().length + " entries",
					clients.getTransparency());

				byte[] composedPriorities = composed.getFaceRenderPriorities();
				byte[] clientPriorities = clients.getFaceRenderPriorities();
				if (composedPriorities != null && clientPriorities != null
					&& composedPriorities.length == clientPriorities.length)
				{
					int firstMismatch = -1;
					for (int i = 0; i < composedPriorities.length; i++)
					{
						if (composedPriorities[i] != clientPriorities[i])
						{
							firstMismatch = i;
							break;
						}
					}
					log.info("{} face order: {}", tag, firstMismatch < 0
						? "priorities IDENTICAL index-for-index"
						: "first priority mismatch at face " + firstMismatch
							+ " (composed " + composedPriorities[firstMismatch]
							+ " vs client " + clientPriorities[firstMismatch] + ")");
				}

				// The last uncompared dimension: the GEOMETRY. The client
				// translates each worn part by its wear offsets before merging;
				// a part sitting even a couple of units off pokes through its
				// neighbour on ANY renderer. Compare the unposed vertices.
				logVertexDiff(tag, composed, clients);
			}
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

	/**
	 * Diffs the composed model's vertices against the client's own, posed with
	 * the player's CURRENT pose so like compares with like. The client model's
	 * vertices are snapshotted FIRST - it and applyTransformations can share
	 * the same animation scratch buffer, and posing ours would overwrite it.
	 */
	private void logVertexDiff(String tag, net.runelite.api.Model composed,
		net.runelite.api.Model clientModel)
	{
		int count = clientModel.getVerticesCount();
		float[] snapX = java.util.Arrays.copyOf(clientModel.getVerticesX(), count);
		float[] snapY = java.util.Arrays.copyOf(clientModel.getVerticesY(), count);
		float[] snapZ = java.util.Arrays.copyOf(clientModel.getVerticesZ(), count);

		Player local = client.getLocalPlayer();
		net.runelite.api.Model posed = composed;
		if (local != null)
		{
			net.runelite.api.Animation pose = client.loadAnimation(local.getPoseAnimation());
			if (pose != null)
			{
				net.runelite.api.Model tmp = client.applyTransformations(
					composed, pose, local.getPoseAnimationFrame(), null, 0);
				if (tmp != null)
				{
					posed = tmp;
				}
			}
		}

		if (posed.getVerticesCount() != count)
		{
			log.info("{} vertices: counts differ - composed {} vs client {}",
				tag, posed.getVerticesCount(), count);
			return;
		}

		float[] vx = posed.getVerticesX();
		float[] vy = posed.getVerticesY();
		float[] vz = posed.getVerticesZ();
		float maxDx = 0;
		float maxDy = 0;
		float maxDz = 0;
		int differing = 0;
		int worst = -1;
		float worstDistance = 0;
		for (int i = 0; i < count; i++)
		{
			float dx = Math.abs(vx[i] - snapX[i]);
			float dy = Math.abs(vy[i] - snapY[i]);
			float dz = Math.abs(vz[i] - snapZ[i]);
			maxDx = Math.max(maxDx, dx);
			maxDy = Math.max(maxDy, dy);
			maxDz = Math.max(maxDz, dz);
			float distance = dx + dy + dz;
			if (distance > 1f)
			{
				differing++;
				if (distance > worstDistance)
				{
					worstDistance = distance;
					worst = i;
				}
			}
		}

		log.info("{} vertices: {} total, {} differ by >1 unit, max |dx|={} |dy|={} |dz|={}, "
				+ "worst vertex {} (composed {},{},{} vs client {},{},{})",
			tag, count, differing, maxDx, maxDy, maxDz, worst,
			worst < 0 ? 0 : vx[worst], worst < 0 ? 0 : vy[worst], worst < 0 ? 0 : vz[worst],
			worst < 0 ? 0 : snapX[worst], worst < 0 ? 0 : snapY[worst], worst < 0 ? 0 : snapZ[worst]);
	}

	/** One line per model: face count and a render-priority histogram, or "no data". */
	private void logPriorities(String label, net.runelite.api.Model model)
	{
		if (model == null)
		{
			sendStatus(label + ": no model");
			log.info("priorities {}: no model", label);
			return;
		}

		byte[] priorities = model.getFaceRenderPriorities();
		if (priorities == null)
		{
			sendStatus(label + ": " + model.getFaceCount() + " faces, NO priority data");
			log.info("priorities {}: {} faces, NO priority data", label, model.getFaceCount());
			return;
		}

		java.util.Map<Integer, Integer> histogram = new java.util.TreeMap<>();
		for (byte priority : priorities)
		{
			histogram.merge((int) priority, 1, Integer::sum);
		}
		String alpha = model.getFaceTransparencies() == null
			? "no alpha array" : "ALPHA ARRAY (" + model.getFaceTransparencies().length + ")";
		sendStatus(label + ": " + model.getFaceCount() + " faces, priorities " + histogram
			+ ", " + alpha);
		log.info("priorities {}: {} faces, priorities {}, {}",
			label, model.getFaceCount(), histogram, alpha);
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

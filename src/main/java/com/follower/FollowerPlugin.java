package com.follower;

import com.google.common.base.Strings;
import com.google.inject.Provides;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.Actor;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.kit.KitType;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.events.*;
import net.runelite.client.events.*;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.*;
import net.runelite.client.chat.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.*;
import net.runelite.client.ui.overlay.*;
import lombok.Setter;
import com.google.gson.Gson;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.*;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.Overlay;
import com.follower.StanceLibrary.StyleSource;
import com.follower.TriggerEvent.Type;
import java.util.Map.Entry;
import static java.lang.System.*;
import static com.follower.TriggerEvent.Type.*;
import static java.lang.Math.*;
import static net.runelite.api.kit.KitType.*;

@Slf4j
@PluginDescriptor(
	name = "Follower Buddy",
	description = "A companion that follows you in any gear you like and talks about what you're doing. Dress it in the Follower Buddy side panel.",
	tags = {"follower", "companion", "pet", "player", "model", "chat", "cosmetic", "outfit"}
)
public class FollowerPlugin extends Plugin
{




	@Inject
	Client client;

	@Inject
	ClientThread clientThread;

	@Inject
	OverlayManager overlayManager;

	@Inject
	ChatMessageManager chatMessageManager;

	@Inject
	ConfigManager configManager;

	@Inject
	FollowerConfig config;

	@Inject
	ModelRepository modelRepository;

	@Inject
	SpotAnimRepository spotAnimRepository;

	@Inject
	GameFontRepository gameFontRepository;

	@Inject
	net.runelite.client.ui.overlay.tooltip.TooltipManager tooltipManager;

	@Inject
	net.runelite.client.input.MouseManager mouseManager;

	@Inject
	net.runelite.client.game.SpriteManager spriteManager;

	@Inject
	net.runelite.client.game.chatbox.ChatboxPanelManager chatboxPanelManager;

	/**
	 * The client's red click cross, drawn to its own recipe: on an entity op
	 * the client stamps the click point and animates four sprite frames, one
	 * per 100ms, dead at 400ms, plotted 12 pixels up-left of the click
	 * (crossX - 8 - 4 in its source). Red frames are sprites 519-522.
	 */
	long crossStartMs;
	java.awt.Point crossPoint;

	void showRedCross()
	{
		var mouse = client.getMouseCanvasPosition();
		if (mouse != null)
		{
			crossPoint = new java.awt.Point(mouse.getX(), mouse.getY());
			crossStartMs = currentTimeMillis();
		}
	}

	final Overlay crossOverlay =
		new Overlay()
	{
		{
			setPosition(net.runelite.client.ui.overlay.OverlayPosition.DYNAMIC);
			setLayer(net.runelite.client.ui.overlay.OverlayLayer.ABOVE_SCENE);
		}

		@Override
		public java.awt.Dimension render(java.awt.Graphics2D graphics)
		{
			long elapsed = currentTimeMillis() - crossStartMs;
			if (crossPoint == null || elapsed >= 400)
				return null;
			int frame = (int) (elapsed / 100);
			var sprite = spriteManager.getSprite(
				// RED_CLICK_SPRITE_FIRST: The four red click-cross frames, 519-522 (the old api SpriteID
				// RED_CLICK_ANIMATION_1..4, deprecated without a gameval equivalent name).
				519 /* RED_CLICK_SPRITE_FIRST */ + frame, 0);
			if (sprite != null)
				// Centre each frame's own image on the click: the cache sprites
				// carry per-frame padding offsets the client's plotSprite
				// applies, but the sprite manager returns them trimmed - a
				// fixed corner offset let the small early frames drift off
				// centre.
				graphics.drawImage(sprite,
					crossPoint.x - sprite.getWidth() / 2,
					crossPoint.y - sprite.getHeight() / 2, null);
			return null;
		}
	};

	/**
	 * Shift + left-click on the follower performs the hover box's action -
	 * Talk-to - like clicking a real NPC. Intercepted directly rather than
	 * trusting the injected menu entry to be present on the click's exact
	 * frame. The click is consumed so it doesn't also walk.
	 */
	final MouseAdapter shiftClickAdapter =
		new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			if (!SwingUtilities.isLeftMouseButton(event)
				|| !client.isKeyPressed(KeyCode.KC_SHIFT)
				|| client.isMenuOpen()
				|| dialog.isOpen()
				|| !follower.isSpawned()
				|| !follower.isUnderMouse(new Point(event.getX(), event.getY()))
				|| !offersWalkHere(client.getMenu().getMenuEntries()))
				return event;

			crossPoint = event.getPoint();
			crossStartMs = currentTimeMillis();
			// The mouse is heard on the Swing thread, and the box reads the
			// bag for a wished item, which only the client thread may. Asked
			// again there: a second click can queue before the first opens it.
			clientThread.invoke(() ->
			{
				if (started && !dialog.isOpen())
					startTalking();
			});
			event.consume();
			return event;
		}
	};

	@Inject
	AppearanceService appearanceService;

	@Inject
	FollowerEntity follower;

	@Inject
	FollowerDialog dialog;



	@Inject
	StanceLibrary stanceLibrary;

	@Inject
	WrapTrimStore wrapTrimStore;

	@Inject
	SpeechEngine speechEngine;

	@Inject
	RuleLoader ruleLoader;

	@Inject
	DialogLoader dialogLoader;

	@Inject
	FollowerOverlay overlay;

	@Inject
	net.runelite.client.game.ItemManager itemManager;

	@Inject
	net.runelite.client.ui.ClientToolbar clientToolbar;


	FollowerPanel panel;
	NavigationButton navButton;
	PhrasesDialog messagesDialog;
	MemoryDialog memoryDialog;

	@Inject
	Gson gson;

	@Inject
	OutfitProfileStore profileStore;

	ErrandController errands;

	/** The rule-level writing gesture; null until startUp builds it. */
	PropFlourish flourish;

	/** Stands the follower clear of a fight; see SpectateController. */
	SpectateController spectate;

	/** The skilling company: the seat, the borrowed tool, the session the rules read. */
	SkillingController skilling;

	/** The moments: a level, the pet, second wind, a stranger for the player. */
	Moments moments;

	/** Where the follower looks when nothing else owns its head. */
	Gaze gaze;

	/** What it notices that the player has not, and goes to look at. */
	Noticing noticing;

	/** The beat before yes: a Send with a misgiving, the late step aside. */
	Will will;

	/** The player's agility obstacles, recorded for the follower to take after them. */
	CrossingWatch crossingWatch;

	/** Walkable ground nobody walks on: the lit fires in the scene. */
	HotGround hotGround;

	Path dataDir;
	WorldPoint lastPlayerTile;
	int lastRegionId = -1;
	/** The player-commanded stay state last handed to onStayStateChanged. */
	boolean stayReported;
	/**
	 * A park the player gave - right-click Stay, a Send, Follow me off - held
	 * until they say Follow. Nothing of the follower's own takes the feet
	 * from it: the wander, the seat, the moments, the spectator and the
	 * errands all read it as their feet taken.
	 */
	boolean parkedByPlayer;
	/**
	 * A park given while something of the follower's own had the feet: that
	 * stands down, and the follower is parked again once it has let go.
	 */
	boolean parkWhenFree;
	/**
	 * Where a Send given then pointed, walked to once free. Null for a Stay,
	 * which parks where the follower then stands.
	 */
	WorldPoint sentWhenFree;
	/** Told as the player-commanded stay starts or ends; the side panel's button reads it. */
	@Setter
	Consumer<Boolean> onStayStateChanged;
	int reloadPollTicks,
		spawnDelayTicks;
	boolean rebuildQueued;

	/** Ticks until the one-time naming prompt opens; 0 = nothing pending. */
	int namePromptDelayTicks;

	/** Times the naming prompt closed unanswered this session; one re-offer. */
	int nameOfferRetries;

	/** Whether the follower was absent (thrall-only mode) last tick. */
	boolean wasAbsent;

	/** The track playing last tick; null until the first read seeds it. */
	String lastMusicTrack;

	/** Whether the bank was open last tick, for the close-is-a-boundary watch. */
	boolean wasBankOpen;

	/** True once the player has walked out of sight of a parked follower. */
	boolean leftTheFollowerParked;

	/** Ten seconds after the first spawn, so the arrival isn't talked over. */
	static final int NAME_PROMPT_DELAY_TICKS = 17;

	/**
	 * LOGGED_IN fires after EVERY map chunk reload, not just at login - running
	 * across a region boundary goes LOADING -> LOGGED_IN too. Set on the real
	 * logged-out states and consumed once, so the LOGIN trigger and the edge
	 * priming only happen when the player actually logged in, not every time
	 * they jog into a freshly loaded part of the map.
	 */
	boolean freshLogin;

	/**
	 * Set while a world hop is in flight, so coming back is told apart from
	 * arriving. A hop needs the edges primed - the gear worn and the place
	 * stood in are the same as a moment ago, and without priming every one of
	 * them reads as a fresh rising edge and the follower greets the new world
	 * with a monologue.
	 */
	boolean hopped;
	final Map<Skill, Integer> knownLevels = new EnumMap<>(Skill.class);

	/** Last XP seen per skill; a rise is a drop, whatever the level did. */
	final Map<Skill, Integer> knownXp = new EnumMap<>(Skill.class);

	/**
	 * The engine's context now. Forget everything and a profile switch give
	 * the engine a new one, so it is read each time and never kept.
	 */
	TriggerContext context()
	{
		return speechEngine.getContext();
	}

	/** Hands an event to the speech engine. */
	void dispatch(TriggerEvent event)
	{
		speechEngine.dispatch(event);
	}

	/** Stores one of the plugin's settings. */
	void setConfig(String key, Object value)
	{
		configManager.setConfiguration(FollowerConfig.GROUP, key, value);
	}

	@Provides
	FollowerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FollowerConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		// The controllers (errands, skilling, moments, gaze, ...) are built
		// below and never set back to null. RuneLite registers the event
		// handlers only after this returns, so the handlers and the ticks use
		// them without a null check. What this method or shutDown can reach
		// keeps its checks: shutDown also runs when startUp throws part-way.

		dataDir = RuneLite.RUNELITE_DIR.toPath().resolve("follower" /* DATA_DIR_NAME */);
		try
		{
			Files.createDirectories(dataDir);
		}
		catch (IOException e)
		{
			log.warn("Could not create {}", dataDir, e);
		}

		gameFontRepository.load();
		// With merging: an existing install's files gain the rules and trees
		// this build added, which otherwise never reach it (the starter files
		// are only written when missing - a tester's file from 1.0.0 had no
		// emote mirroring at all, and it looked broken rather than absent).
		ruleLoader.initialise(dataDir, true);
		// Chat is not up yet; what the files got wrong is told at the login.
		// A fresh start tells every problem again, the outfit's included.
		loadErrorsReported = false;
		lastOutfitWarning = null;
		buildFailureTold = false;
		dialogLoader.initialise(dataDir, true);
		stanceLibrary.load(dataDir);
		wrapTrimStore.load(dataDir, follower);
		loadOutfitProfiles();

		speechEngine.setSink(this::speak);
		// Lines are fitted to the overhead in the font it is drawn in.
		speechEngine.setOverheadWidth(gameFontRepository.getByName("b12_full")::stringWidth);
		applyConfig();
		renderCallbacks.register(thrallHider);
		// Anything the dump files didn't provide is parsed from the client's
		// own cache; retried from the login states until the indexes exist.
		clientThread.invokeLater(this::ensureCatalogues);
		// The same transient-prop path the dev commands use: overlaid on the
		// outfit at compose time, never persisted. Shared by the errand, the
		// rule-level flourish and the minutes-taking; all on the client thread.
		hands = new ErrandController.Hands()
			{
				@Override
				public void hold(int itemId)
				{
					var slot = handSlot(itemId);
					if (slot == null)
					{
						log.warn("Prop {} has no wearable slot", itemId);
						return;
					}
					propHands = null;
					propSlot = slot;
					propItemId = itemId;
					takeOut(true);
				}

				@Override
				public void hold(int leftItemId, int rightItemId)
				{
					// Dressed as the film dresses a pose: the left hand's thing
					// in the shield slot, the right's in the weapon slot,
					// whatever each is worn as.
					if (handSlot(rightItemId, false) == null)
					{
						log.warn("Prop {} has no worn model", rightItemId);
						return;
					}
					propSlot = null;
					propItemId = 0;
					propHands = new int[]{leftItemId, rightItemId};
					// No reach for the pair: the writing pose lifts them from
					// the waist itself (its frames 0-3), and a bend to the floor
					// first was a second take-out that ate most of a short note.
					takeOut(false);
				}

				/**
				 * Into the hand, behind the reach that puts it there so the
				 * thing does not simply appear: the reach first, and the prop
				 * at the bottom of it. An emote already playing owns the body
				 * and is left alone. A rebuild mid-emote leaves the emote be
				 * (the pose waits on it), so the prop can come in partway.
				 */
				void takeOut(boolean reach)
				{
					int[] clip = reach ? parseAnimationIds(config.reachAnimation()) : new int[0];
					if (clip.length == 0 || follower.isEmotePlaying())
					{
						propBehindReach = false;
						rebuildFollower();
						return;
					}
					propBehindReach = true;
					follower.playAnimations(clip);
					follower.atEmoteFraction(REACH_BOTTOM, () ->
					{
						if (propBehindReach)
						{
							propBehindReach = false;
							rebuildFollower();
						}
					});
				}

				@Override
				public void release()
				{
					// Idempotent: the reset and abort paths call this with
					// nothing held, including once at construction time.
					if (propSlot == null && propHands == null)
						return;
					boolean writing = propHands != null;
					propSlot = null;
					propItemId = 0;
					propHands = null;
					propBehindReach = false;
					// Let go by a stop, the model data is already unloaded and a
					// rebuild could only fail, out loud; the stop despawns anyway.
					// The scroll on its way down keeps its props to the end of
					// the lowering, or they would vanish at the top of it; and
					// to the end of the nod up from the notes, the same way.
					if (started && (follower.isPuttingAway() || writing && follower.isEmotePlaying()))
					{
						follower.atEmoteFraction(1, () ->
						{
							if (started)
								rebuildFollower();
						});
					}
					else if (started)
						rebuildFollower();
				}

				@Override
				public boolean isHolding()
				{
					return propSlot != null || propHands != null;
				}

				@Override
				public void wear(int itemId)
				{
					// The second prop is anything but the hand, so a hat can be
					// worn over a held tool. Something that resolves to the hand
					// belongs to hold(), and is declined here rather than fought over.
					var slot = resolveSlot(itemId);
					if (slot == null || slot == WEAPON || slot == SHIELD)
						return;
					wornSlot = slot;
					wornItemId = itemId;
					rebuildFollower();
				}

				@Override
				public void takeOff()
				{
					if (wornSlot == null)
						return;
					wornSlot = null;
					wornItemId = 0;
					// As release: after a stop there is nothing to rebuild.
					if (started)
						rebuildFollower();
				}

				@Override
				public boolean isWearing()
				{
					return wornSlot != null;
				}
			};
		// Before every mover: the feet consult this on each step they plan.
		hotGround = new HotGround(client);
		follower.setHotGround(hotGround::hot);
		errands = new ErrandController(client, follower, config,
			speechEngine::dispatch, spotAnimRepository, this::errandBusy, hands);
		flourish = new PropFlourish(follower, hands);
		// A SUPPLIER, not the context itself: the engine replaces its context
		// on every reset, and a controller holding the original object read a
		// context that had stopped being refreshed - inCombat false forever,
		// spectating dead after the first relog. See the field note in
		// SpectateController.
		spectate = new SpectateController(client, follower, config,
			speechEngine::getContext, spotAnimRepository, speechEngine::dispatch,
			this::setSpectateDisarmed);
		skilling = new SkillingController(client, follower, config,
			hands, speechEngine::dispatch,
			() -> context().isInCombat(),
			this::seatFeetTaken,
			() -> client.getWidget(InterfaceID.BANKMAIN, 0) != null);
		// The prayer at the altar and the fire line borrow two of the errands.
		skilling.setErrands(errands::force);
		// The seat is taken sooner for the half hour after a favour.
		skilling.setGrateful(() -> context().isGrateful());
		// And in return no errand of the follower's own starts mid-session,
		// or in a scene or the quiet after one.
		errands.setSession(() -> skilling.isWorking() || sceneQuiet());
		errands.setOnForcedMiss(key -> sendStatus("That errand can't start here." /* ERRAND_MISSED */));
		moments = new Moments(client, follower, skilling,
			this::momentFeetTaken,
			() -> context().isInCombat(),
			() -> context().getDaysKnown(),
			flourish,
			() -> context().isGrateful(),
			() -> parkedByPlayer);
		// The head: the cursor, the camera and the look round. Busy is anything
		// that owns the head or the feet; the seat's own looks are its own.
		gaze = new Gaze(client, follower, config,
			client::getCameraYaw,
			() -> context().getIdleTicks(),
			() -> ownBusiness() || dialog.isOpen() || resting
				|| context().isInCombat() || sceneQuiet()
				,
			() -> thingsNearby(Gaze.AROUND_FAR + 2),
			new java.util.Random());
		// The eyes for things: the walk is the moments', the feet theirs.
		noticing = new Noticing(client, follower, config, moments,
			speechEngine::dispatch,
			() -> ownBusiness() || dialog.isOpen()
				|| context().isInCombat() || sceneQuiet()
				,
			() -> context().getIdleTicks(),
			this::landmarksNearby);
		// A park that is plainly a park - a wander, a player's Stay - may be
		// stepped out of; a seat, a moment or an errand has its own reasons.
		will = new Will(client, follower, speechEngine::dispatch,
			() -> !ownBusiness() && !sceneHolds());
		crossingWatch = new CrossingWatch(follower);
		follower.setOnCrossed(crossing -> dispatch(
			TriggerEvent.crossed(crossing.isFell())));
		follower.setOnAgilitySnap(crossingWatch::forgetMissed);
		// Later in the frame, and only if still out: a despawn lets a crossing go too.
		follower.setOnCrossingHands(() -> clientThread.invokeLater(() ->
		{
			if (follower.isSpawned())
				rebuildFollower();
		}));

		// On a fresh client boot the LOGIN_SCREEN transition can happen before
		// this plugin subscribes, so the first login would not read as fresh.
		// Enabled mid-session instead, the next LOGGED_IN is a chunk reload,
		// not a login - so only pre-arm when not already in the world.
		freshLogin = client.getGameState() != GameState.LOGGED_IN;

		overlayManager.add(overlay);
		overlayManager.add(dialog);
		overlayManager.add(crossOverlay);
		dialog.register();
		// A callback that remembers a quest together asks whether the follower
		// saw it finish: the day, kept per account, or null for one finished
		// before it came along or while it was parked elsewhere.
		speechEngine.setQuestWitness(quest ->
		{
			var seen = context().questSeenFinishedOn(accountKey(quest));
			return seen == null || seen == QUEST_NOT_SEEN ? null : seen;
		});
		mouseManager.registerMouseListener(shiftClickAdapter);
		dialog.setFollowerOutfit(() -> OutfitParser.parse(config.customOutfit()));
		addPanel();

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			// Every rule starts with its edges cleared and no login is coming
			// to prime them, so the gear worn and the place stood in would rise
			// as news on the first tick. They are baseline, as at a login. Set
			// here, before any tick, rather than in the restore below, which a
			// tick can beat.
			speechEngine.primeEdgesOnNextTick();

			// Enabled while already in the world, so no login is coming and the
			// one path that restores the follower's memory would never run.
			// That alone would be forgetfulness; what made it destructive is
			// that the periodic writer then saved the BLANK context straight
			// over the stored one, erasing every tally, record, place opinion
			// and once-ever line. Toggling a plugin is ordinary play.
			//
			// The session count is deliberately left alone: a toggle is not a
			// new session, and the greeting belongs to a real login.
			clientThread.invoke(() ->
			{
				readCounters();
				knowThePlayer();
				rebuildFollower();
			});
		}
		started = true;
	}

	@Override
	protected void shutDown() throws Exception
	{
		// First, so work already queued for the client thread finds it gone.
		started = false;
		renderCallbacks.unregister(thrallHider);
		// Before the engine is reset: an orderly shutdown is the one chance to
		// save what the last few ticks counted.
		writeCounters();
		// The engine is a singleton that survives plugin toggles; a stale sink
		// would keep speaking into a dead plugin instance.
		speechEngine.setSink(null);
		speechEngine.reset();
		// As at a logout: the blank left behind is not the stored memory, and
		// a toggle off and on again at the login screen would save it there.
		memoryProfileId = -1;
		overlayManager.remove(overlay);
		overlayManager.remove(dialog);
		overlayManager.remove(crossOverlay);
		dialog.unregister();
		mouseManager.unregisterMouseListener(shiftClickAdapter);
		overlay.clear();
		speechQueue.clear();
		cutByTheScene = null;
		speakingUntilMs = 0;

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
			panel = null;
		}
		if (messagesDialog != null)
		{
			messagesDialog.dispose();
			messagesDialog = null;
		}
		// Left open, its Forget everything still ran for a stopped plugin (QA3).
		if (memoryDialog != null)
		{
			memoryDialog.dispose();
			memoryDialog = null;
		}

		// RuneLite calls this on the Swing thread, and letting go of the
		// follower reads the client, which only the client thread may: the
		// sketch's scroll, put away from here, threw and left the reading pose
		// on for good, and the rest of this never ran. So all of it goes over,
		// with the controllers of this session, not any a quick re-enable
		// has built since.
		var flourishNow = flourish;
		var skillingNow = skilling;
		var momentsNow = moments;
		var errandsNow = errands;
		clientThread.invoke(() ->
		{
			resetThrallQuietly();
			// An answer latched in the box is told to the rules, which may
			// read the client.
			dialog.close();
			if (flourishNow != null)
				flourishNow.abort();
			if (skillingNow != null)
				skillingNow.reset();
			if (momentsNow != null)
				momentsNow.reset();
			if (errandsNow != null)
				errandsNow.reset();
			// The scroll must not survive a disable either.
			endMinutes(false);
			// The follower outlives a disable, and so does a pose left on it by
			// what the resets above do not own: a mirrored loop, the rest, the
			// shield's channel. The new session would never know to take it off.
			follower.setPoseOverride(0);
			follower.despawn();
			appearanceService.invalidate();
		});

		stanceLibrary.save();
		wrapTrimStore.save(follower);
		modelRepository.unload();
		// RuneLite keeps a plugin switched off, and with it all it holds:
		// these come back at the next startUp and login (perf audit, Oct 2026).
		spotAnimRepository.unload();
		knownLevels.clear();
		knownXp.clear();
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
	void resetTransientState()
	{
		spectateDisarmed = false;
		emoteDisarmed = false;
		emoteHold = false;
		mirroredPose = 0;
		loopHands = null;
		pendingMime = 0;
		pendingOneShot = false;
		copyAtWork = false;
		wasThieving = false;
		resting = false;
		wandered = false;
		wanderCountdown = 0;
		parkedByPlayer = false;
		parkWhenFree = false;
		sentWhenFree = null;
		damagedByPlayer.clear();
		mirrorGraphicsUntilTick = -1;
		hoveredThisTick = false;
		hoverTicks = 0;
		hopped = false;
		if (gaze != null)
			gaze.reset();
		if (noticing != null)
			noticing.reset();
		if (will != null)
			will.reset();
		strandedLandingTicks = 0;
		sceneOn = false;
		sceneGoneTicks = 0;
		quietUntilTick = 0;
		speechEngine.setSceneQuiet(false);
		speechEngine.setInCutscene(false);
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

	void addPanel()
	{
		panel = new FollowerPanel(itemManager, modelRepository,
			this::equipFromPanel, this::clearSlotFromPanel,
			() -> clientThread.invoke(this::copyGearToCustomOutfit),
			this::clearOutfit, this::setGender, this::cycleKit, this::setBodyColor,
			config.panelFolds());
		// The panel's Mute and the settings' Mute are the same config key,
		// synced both ways, so they can never disagree. Chattiness, the
		// chatbox mirror, errands, the thrall profiles and the open sections
		// work the same way.
		panel.setOnSetting(this::setConfig);
		panel.setOnEditMessages(this::openMessagesDialog);
		panel.setOnShowMemory(this::openMemoryDialog);
		panel.setSetting("muted", config.muted());
		panel.setSetting("chattiness", config.chattiness());
		panel.setSetting("mirrorToChat", config.mirrorToChat());
		panel.setSetting("errandsEnabled", config.errandsEnabled());
		panel.setThrallProfiles(config.thrallMeleeProfile(),
			config.thrallRangedProfile(), config.thrallMagicProfile());
		panel.setProfileProtected(profileStore::isProtected);
		panel.setOnFollowEnabled(this::followFromPanel);
		panel.setStaying(isStaying());
		setOnStayStateChanged(staying ->
		{
			if (panel != null)
				panel.setStaying(staying);
		});
		panel.setOnComeHere(this::placeHere);
		panel.setFollowerName(followerName());
		// The too-close fire variant is the scheduler's own roll, not a
		// choice to offer.
		var errandNames =
			ErrandController.displayNames();
		errandNames.remove("firedeath");
		panel.setErrands(errandNames);
		panel.setOnErrand(key -> clientThread.invoke(() -> forceErrand(key)));
		panel.setOnFix(() ->
		{
			reattach();
			rebuildFromOutfit();
		});
		panel.setOnReload(() -> clientThread.invoke(this::reloadFiles));
		panel.setCommands(commandLines());
		panel.setRegionId(lastRegionId);
		// On the client thread, as the outfit's own callbacks are: the active
		// profile and the autosave that reads it live there (QA pass 4).
		panel.setOnProfileLoad(name -> clientThread.invoke(() -> loadOutfitProfile(name)));
		panel.setOnProfileSave(name -> clientThread.invoke(() -> saveOutfitProfile(name)));
		panel.setOnProfileDelete(name -> clientThread.invoke(() -> deleteOutfitProfile(name)));
		restoreActiveProfile();

		// ImageUtil.loadImageResource THROWS on a missing resource rather than
		// returning null, and an exception here aborts startUp() and makes RuneLite
		// disable the whole plugin. A cosmetic icon must never be able to do that.
		BufferedImage icon;
		try
		{
			icon = net.runelite.client.util.ImageUtil
				.loadImageResource(FollowerPlugin.class, "/com/follower/panel-icon.png");
		}
		catch (RuntimeException e)
		{
			log.warn("Panel icon missing, falling back to a blank one", e);
			icon = new BufferedImage(16, 16,
				BufferedImage.TYPE_INT_ARGB);
		}

		navButton = NavigationButton.builder()
			.tooltip("Follower Buddy")
			.priority(7)
			.icon(icon)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		syncPanel();
		follower.setOnEmoteStateChanged(this::updateEmoteDisarm);
		follower.setOnEmoteClip(this::dressEmoteHands);
		follower.setOnSnapped(this::followerSnapped);
		follower.setDrawn(actor -> renderCallbacks.addEntity(actor, false));
		stanceLibrary.setStyleSource(this::weaponUsage);
		buildSlotIndexAsync();
	}

	/**
	 * Works out which slot each wearable item belongs to, so the picker can offer
	 * only sensible choices per slot. Runs off the client thread because it touches
	 * several thousand items; the panel stays usable (unfiltered) until it lands.
	 */
	void buildSlotIndexAsync()
	{
		var ids = modelRepository.allItemIds();
		if (ids.isEmpty())
			return;
		indexChunk(ids, 0, new HashMap<>());
	}

	void indexChunk(List<Integer> ids, int start, Map<Integer, KitType> index)
	{
		// getItemStats -> getItemComposition asserts the client thread, so this cannot
		// run on an executor. invokeLater spreads the work over successive frames.
		clientThread.invokeLater(() ->
		{
			// SLOT_INDEX_CHUNK: Items per client-thread pass. Small enough not to be felt as a stutter.
			int end = min(start + 250 /* SLOT_INDEX_CHUNK */, ids.size());
			for (int i = start; i < end; i++)
			{
				int itemId = ids.get(i);
				try
				{
					var slot = resolveSlot(itemId);
					if (slot != null)
						index.put(itemId, slot);
				}
				catch (RuntimeException e)
				{
					// A single bad item must not abort the whole index.
				}
			}

			if (end < ids.size())
			{
				indexChunk(ids, end, index);
				return;
			}

			if (index.isEmpty())
				// The item stats were not ready. This runs during login, and an
				// index of nothing would leave the picker unfiltered for the whole
				// session, so it is dropped and ensureCatalogues asks again.
				return;
			slotIndex = index;
			if (panel != null)
				panel.setSlotIndex(index);
		});
	}

	/** Item id -> equipment slot, kept so the stance audit can walk every weapon. */
	Map<Integer, KitType> slotIndex = Collections.emptyMap();

	/**
	 * The sit emote's own stand-up, captured alongside the sit itself: its
	 * second half, about a second, the whole emote being 2.84s that starts
	 * standing. Its partner rather than a generic getting-up animation, so
	 * the two read as one gesture the way they do when a player performs it.
	 */
	static final int REST_STAND_UP = FollowerEntity.STAND_UP;

	/** Matches idle-long's five minutes, so the sitting and the "we live here
	 * now" lines arrive as one mood rather than two systems coinciding. */
	static final int REST_AFTER_TICKS = 500;

	boolean resting;

	/** The sit the rest put on: the one it lets go of, even if the Rest pose
	 * setting has changed since. */
	int restPose;

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
	/**
	 * Something has the follower already: thrall work, an errand, the
	 * spectator or the dialog box. The rest, the minute-taking, the welfare
	 * check and the wander each start from this and add their own.
	 */
	boolean occupied()
	{
		return thrallNpc != null
			|| errands.isBusy()
			|| spectate.isSpectating()
			|| dialog.isOpen();
	}

	void updateRest()
	{
		boolean busy = occupied()
			// A long dialog must not sit the minute-taker down mid-sentence.
			|| minutesState != 0;
		boolean wantRest = config.restWhenIdle()
			&& config.restAnimation() > 0
			&& !busy
			&& follower.isSpawned()
			&& follower.isSettled()
			&& context().getIdleTicks() >= REST_AFTER_TICKS;
		if (wantRest == resting)
			return;
		resting = wantRest;
		sitOrStand(wantRest, config.restAnimation());
	}

	/**
	 * Sits down on the Rest pose, or lets go of the sit it put on. Pulled out
	 * of updateRest so a test can change the setting mid-sit.
	 */
	void sitOrStand(boolean sit, int restAnimation)
	{
		if (sit)
		{
			restPose = restAnimation;
			follower.setPoseOverride(restPose);
		}
		else
			letGoOfTheSit(follower, restPose);
	}

	/**
	 * Lets go of the sit, and stands up only from the sit. The rest lets go
	 * a tick after whatever ended it, and an errand with no trip can start
	 * on the seated follower in that tick: its reading pose, or its bend,
	 * is on by then and keeps going.
	 */
	static void letGoOfTheSit(FollowerEntity follower, int sitPose)
	{
		boolean sitting = follower.getPoseOverride() == sitPose && !follower.isEmotePlaying();
		follower.clearPoseOverride(sitPose);
		if (sitting)
			// Cancelled harmlessly by the next movement frame if the follower
			// is already walking, which is the usual reason rest ended.
			follower.playAnimation(REST_STAND_UP);
	}

	// --------------------------------------------------------- taking minutes

	/** The shared prop surface, kept so the minutes-taking can borrow it. */
	ErrandController.Hands hands;

	/** 0 = not taking minutes, 1 = walking to the flank, 2 = writing. */
	int minutesState;

	/** Whether the current dialog already rolled for minutes, win or lose. */
	boolean minutesConsidered;

	/** Consecutive ticks with no dialog widget up, to ride out page flips. */
	int dialogGoneTicks = 1000;

	long lastMinutesMs;

	/**
	 * Whether a meeting is written up rather than only attended: now and
	 * then, and only into an empty hand. A tool kept between trees is still
	 * in it after the work stops, and the scroll would take its place.
	 */
	static boolean minutesWritten(boolean handFull, long sinceLastMs, int roll)
	{
		// MINUTES_COOLDOWN_MS: A meeting is minuted at most this often.
		return !handFull && sinceLastMs >= 480_000L /* MINUTES_COOLDOWN_MS */ && roll < 35;
	}

	/** A cutscene seen, and not yet three clean ticks gone (noticeTheScene). */
	boolean sceneOn;
	int sceneGoneTicks;

	/**
	 * How long the follower keeps quiet after a scene ends, in ticks: fifteen
	 * seconds. Play test, 9 Oct (Children of the Sun): lines landed straight
	 * after the scene, and the ask was a little quiet once a cutscene is over.
	 */
	static final int SCENE_QUIET_TICKS = 25;

	/** The tick the quiet after the last scene ends. */
	int quietUntilTick;

	/**
	 * A line a starting scene cut off just after it went up, said again once
	 * the scene and its quiet are over. QA, 15 Oct: the line was gone a tick
	 * or two after it appeared, and a once line - every quest-moment draft -
	 * was spent all the same. Only a once line or one that waits its turn,
	 * the lines that do not come round again; anything else is let go, as
	 * before. Behaviour QA, 16 Oct: a bet's payoff, a waits line, was cut 1.2 s
	 * in and lost.
	 *
	 * <p>Cut before it could be read: the time the line takes to read, and
	 * never less than this. QA pass 2: a runner's door click let the window's
	 * scene cut the hideout line 2.4 s into the 3.5 s it takes to read.
	 */
	static final long SCENE_CUT_GRACE_MS = 2_000L;

	/** The line on the overhead now, and when it went up. */
	Utterance onScreen;
	long onScreenSinceMs;

	/** The line held over the scene, and for how many ticks it has been. */
	Utterance cutByTheScene;
	int cutHeldTicks;

	/** Whether a line up this long, from this rule, taking this long to read, is said again after the scene. */
	static boolean saysAgainAfterTheScene(SpeechRule rule, long upForMs, long toReadMs)
	{
		return rule != null && (rule.isOnce() || rule.waitsItsTurn())
			&& upForMs < max(SCENE_CUT_GRACE_MS, toReadMs);
	}

	/**
	 * Whether a cutscene has the camera, or had it under three ticks ago: a beat
	 * between two shots is not the end of the scene. While it does, the follower
	 * is an extra: it keeps to where it stands, gives way to the cast, looks
	 * where the player looks and says nothing. It still mirrors an emote - the
	 * mimic is the part that reads best.
	 */
	boolean inCutscene()
	{
		return sceneOn;
	}

	/**
	 * A scene, or the quiet after one: nothing is said that the player did not
	 * ask for, and nothing of its own is started - no errand, look round,
	 * noticing or moment. Play test, 13 Oct: hold the activities through the
	 * quiet too, not only the words.
	 */
	boolean sceneQuiet()
	{
		return inCutscene() || client.getTickCount() < quietUntilTick;
	}

	/** Whether the scene keeps the follower: not the player's park or thrall work. */
	boolean sceneHolds()
	{
		return inCutscene() && !parkedByPlayer && !thrallBusiness();
	}

	/**
	 * Reads the cutscene once a tick, before the load wait, so a scene that
	 * opens with an area load is one from its first tick. Play test, 9 Oct
	 * (Children of the Sun): the follower walked to the player's shoulder and
	 * back on every line of the scene and stood in the cast.
	 */
	void noticeTheScene()
	{
		boolean up = client.getVarbitValue(VarbitID.CUTSCENE_STATUS) != 0;
		sceneGoneTicks = up ? 0 : min(sceneGoneTicks + 1, 1000);
		boolean on = up || (sceneOn && sceneGoneTicks <= 2);
		if (on != sceneOn)
		{
			sceneOn = on;
			if (on)
			{
				long now = currentTimeMillis();
				cutByTheScene = onScreen != null && now < speakingUntilMs
					&& saysAgainAfterTheScene(onScreen.rule, now - onScreenSinceMs,
						timeToReadMs(onScreen.text)) ? onScreen : null;
				cutHeldTicks = 0;
				// What was about to be said belongs to the moment before.
				speechQueue.clear();
				overlay.clear();
				speakingUntilMs = 0;
			}
			else
			{
				if (!parkedByPlayer && !thrallBusiness())
					follower.resumeFollowing();
				// Not off on a wander on the idle time the scene ran up.
				wanderCountdown = WANDER_MIN_GAP;
				quietUntilTick = client.getTickCount() + SCENE_QUIET_TICKS;
			}
		}
		follower.setWatchingWithThePlayer(sceneHolds());
		speechEngine.setSceneQuiet(sceneQuiet());
		speechEngine.setInCutscene(inCutscene());

		// Through the engine's remark, so the mute still holds. The words
		// only: the rule's own business ran when the line first went up.
		// Kept through a conversation too, as the engine keeps a line that
		// waits, rather than said into it and dropped.
		if (cutByTheScene != null && (sceneQuiet() || inConversation()))
		{
			// SCENE_CUT_KEPT_TICKS: As long as the engine holds an occasion for a scene: five minutes.
			if (++cutHeldTicks > 500 /* SCENE_CUT_KEPT_TICKS */)
				cutByTheScene = null;
		}
		else if (cutByTheScene != null)
		{
			// Not when its quest's step has moved on since, as the engine lets
			// a line still waiting go. Behaviour QA, 16 Oct: the hideout line
			// was said again after the scene that moved the step, and the next
			// step's moment came 6 s behind it. The step is read from the
			// game; no event plays a part in it.
			if (cutByTheScene.rule.when.questStepHolds(context(), TriggerEvent.tick()))
				speechEngine.remark(cutByTheScene.text);
			cutByTheScene = null;
		}
	}

	/** Whether any NPC-dialog interface is up (heads or the options menu). */
	boolean npcDialogOpen()
	{
		return client.getWidget(InterfaceID.CHAT_LEFT, 0) != null
			|| client.getWidget(InterfaceID.CHAT_RIGHT, 0) != null
			|| client.getWidget(InterfaceID.CHATMENU, 0) != null;
	}

	/** An NPC dialogue up, or gone under three ticks: the reading the rules get, page flips ridden out. */
	boolean inConversation()
	{
		return npcDialogOpen() || dialogGoneTicks <= 2;
	}

	/**
	 * Whether a line is kept in the queue through a conversation rather than
	 * said into it and dropped: a line that waits its turn, as the engine
	 * keeps it. The engine hands one over only with no box up, but the queue
	 * can hold it a few seconds behind a long line, and the player can start
	 * talking to someone in that time.
	 */
	static boolean keptForTheBox(SpeechRule rule, boolean conversation)
	{
		return conversation && rule != null && rule.waitsItsTurn();
	}

	/**
	 * Attends the player's meetings. When an NPC dialog opens the follower
	 * takes a spot over the player's shoulder and looks at whoever they are
	 * talking to, and stays there until the box closes. Occasionally it also
	 * takes the scroll out and writes: the errand's own documenting stance,
	 * solemnly applied to your chat with a banker, which is the whole joke -
	 * so no line is needed, though the rare one rides the MINUTES_TAKEN event
	 * that fires when a written meeting closes.
	 *
	 * <p>The spot is taken every time; the writing is one roll per dialog
	 * with a long cooldown. Everything that owns the feet (thrall work,
	 * errands, spectating, a player-commanded Stay, combat, a work session)
	 * declines both outright.
	 */
	void updateMinutes()
	{
		boolean open = npcDialogOpen();
		if (open)
			dialogGoneTicks = 0;
		else if (dialogGoneTicks < 1000)
			dialogGoneTicks++;
		// Page flips and choice menus drop the widget for a beat; only three
		// clean ticks of absence mean the meeting is really over.
		boolean over = !open && dialogGoneTicks > 2;

		boolean busy = occupied()
			|| skilling.isWorking()
			|| context().isInCombat()
			// A scene's lines are its own: no trip to the shoulder for each.
			|| inCutscene();

		var local = client.getLocalPlayer();
		switch (minutesState)
		{
			case 0:
				if (over)
					minutesConsidered = false;
				else if (open && !minutesConsidered)
				{
					minutesConsidered = true;
					// The wander's drift gives way to the shoulder; any other
					// stay is somebody's, the player's first of all.
					if (!busy && (!follower.isStaying() || wandered) && follower.isSpawned()
						&& local != null && standOverTheShoulder(local))
					{
						// Over the shoulder every time; the scroll now and then.
						boolean writes = minutesWritten(hands.isHolding(),
							currentTimeMillis() - lastMinutesMs,
							ThreadLocalRandom.current().nextInt(100));
						if (writes)
							lastMinutesMs = currentTimeMillis();
						minutesState = writes ? 1 : 3;
					}
				}
				break;

			case 1:
				if (over || busy || local == null)
					// The meeting ended before it got there; nothing was held.
					endMinutes(false);
				else if (follower.isStaying() && follower.isSettled())
				{
					hands.hold(ErrandController.QUILL_PROP,
						ErrandController.SCROLL_PROP);
					follower.setPoseOverride(
						ErrandController.WRITE_SCROLL_POSE);
					minutesState = 2;
				}
				break;

			case 2:
			case 3:
				if (busy || local == null
					|| follower.getWorldLocation() == null
					|| local.getWorldLocation() == null
					|| follower.getWorldLocation().distanceTo(local.getWorldLocation()) > 8)
					endMinutes(false);
				else if (over)
					// Only a written meeting is minuted; attending is not.
					endMinutes(minutesState == 2);
				break;

			default:
				break;
		}
	}

	/**
	 * Over the shoulder: a tile diagonally behind the player, on either side,
	 * facing whoever the player is talking to. The NPC itself when the client
	 * still says who that is; otherwise the tile the player is turned toward,
	 * which is where the NPC stood when the box opened. Behind first, then the
	 * flanks, then straight behind, whichever the ground allows.
	 */
	boolean standOverTheShoulder(Player local)
	{
		var p = local.getWorldLocation();
		if (p == null)
			return false;
		WorldPoint at = null;
		if (local.getInteracting() instanceof NPC)
			at = local.getInteracting().getWorldLocation();
		int fx,
			fy;
		if (at != null && at.getPlane() == p.getPlane() && !at.equals(p))
		{
			fx = Integer.signum(at.getX() - p.getX());
			fy = Integer.signum(at.getY() - p.getY());
		}
		else
		{
			double radians = (local.getCurrentOrientation() / 2048.0) * 2.0 * PI;
			fx = (int) round(-sin(radians));
			fy = (int) round(-cos(radians));
			at = p.dx(fx).dy(fy);
		}
		// The two perpendiculars, in a random order so there is no favourite
		// shoulder.
		int sx = -fy,
			sy = fx;
		if (ThreadLocalRandom.current().nextBoolean())
		{
			sx = -sx;
			sy = -sy;
		}
		WorldPoint[] spots = {
			p.dx(-fx + sx).dy(-fy + sy),
			p.dx(-fx - sx).dy(-fy - sy),
			p.dx(sx).dy(sy),
			p.dx(-sx).dy(-sy),
			p.dx(-fx).dy(-fy),
		};
		for (var spot : spots)
		{
			if (!spot.equals(at) && follower.stayAt(spot))
			{
				follower.setStayFaceTile(at);
				return true;
			}
		}
		return false;
	}

	/**
	 * Ends the minute-taking, on completion and every abort path alike. The
	 * event only fires when the meeting was actually written through.
	 */
	void endMinutes(boolean wrote)
	{
		if (minutesState == 2)
		{
			follower.setPoseOverride(0);
			hands.release();
		}
		if (minutesState != 0)
			follower.resumeFollowing();
		minutesState = 0;
		if (wrote)
			dispatch(TriggerEvent.simple(MINUTES_TAKEN));
	}

	/**
	 * The follower put back beside the player by a teleport or a jump past
	 * its leash, in the frame it happens: an errand and the minutes end there
	 * rather than on the next tick, so the scroll is away before it is shown
	 * beside the landed player, and the snap no longer defeats the minutes'
	 * own leash.
	 */
	void followerSnapped()
	{
		if (errands != null && errands.isBusy())
			errands.reset();
		if (minutesState != 0)
			endMinutes(false);
	}
	// --------------------------------------------------------- welfare check

	/** Whether it came round this absence; cleared when the player returns. */
	boolean welfareChecked;

	/**
	 * Leave the keyboard long enough and the follower walks round in front of
	 * your character and peers at you - and if the rest pose is due, sits
	 * back down there, facing you, which is better than anything this method
	 * could stage on purpose (updateRest re-evaluates on its own). The check
	 * itself is silent: the attention damper is already holding speech while
	 * nobody is there to read it. The payoff line rides ATTENDED_AGAIN, which
	 * fires the moment the camera moves again.
	 */
	void updateWelfare()
	{
		int unattended = context().getUnattendedTicks();
		if (welfareChecked)
		{
			if (unattended == 0)
			{
				welfareChecked = false;
				dispatch(
					TriggerEvent.simple(ATTENDED_AGAIN));
			}
			return;
		}
		// WELFARE_AFTER_TICKS: How long the camera goes untouched before it comes round to look.
		if (unattended < 600 /* WELFARE_AFTER_TICKS */)
			return;
		boolean busy = occupied()
			|| minutesState != 0
			|| follower.isStaying()
			|| skilling.isWorking()
			|| context().isInCombat();
		var local = client.getLocalPlayer();
		if (busy || local == null || !follower.isSpawned())
			return;
		if (follower.moveToFront(local))
			welfareChecked = true;
	}

	/**
	 * Player standing about this long before the follower starts drifting.
	 *
	 * <p>Was thirty seconds, and play testing read that as the follower
	 * getting restless while the player was merely sorting a bank tab. Most
	 * ordinary pauses are under a minute; the drift should start only once
	 * the stop has proven to be a stay.
	 */
	/** The default of the Wander after setting, in ticks; the setting is in seconds. */
	static final int WANDER_AFTER_TICKS = 80;

	/** Ticks between drifts, picked fresh each time so it never reads as a metronome. */
	static final int WANDER_MIN_GAP = 25;

	/** How far from the player a drift may take it. */
	static final int WANDER_RADIUS = 4;

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
	int wanderRadius()
	{
		if (moments.keepsClose())
			// The ten minutes after a pet: it stays by the new arrival.
			return max(2, WANDER_RADIUS - 2);
		String band = context().getMoodBand();
		if ("low".equals(band) || "down".equals(band))
			// Staying close is what low looks like from the outside.
			return max(2, WANDER_RADIUS - 2);
		if ("high".equals(band))
			return WANDER_RADIUS + 2;
		return WANDER_RADIUS;
	}

	int wanderCountdown;
	boolean wandered;

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
	void updateWander()
	{
		int idle = context().getIdleTicks();
		boolean busy = occupied()
			|| emoteHold
			|| skilling.holdsTheFeet()
			|| moments.holdsTheFeet()
			|| inCutscene()
			|| resting
			// A long dialog must not walk the minute-taker off the shoulder.
			|| minutesState != 0
			|| parkedByPlayer;

		// Thieving wanders too, and does not wait to be invited. The idle gate
		// exists so the follower does not drift while the player is busy, but
		// picking pockets is the one activity where being underfoot is the
		// problem - the player is animating constantly, so the idle counter
		// never climbs and the follower would stand there for the whole run.
		boolean thieving = context().isInThievingSession();
		boolean canWander = wanderAllowed(config.wanderWhenIdle(), busy,
			follower.isSpawned(), thieving, idle, config.wanderAfterSeconds() * 100 / 60);

		if (!canWander)
		{
			if (wandered)
			{
				// Only its own drift is let go of. Something that took the
				// feet from it since - a moment, the minutes, the seat - owns
				// that stay now and hands it back itself.
				if (!feetBorrowed())
					follower.resumeFollowing();
				wandered = false;
			}
			wanderCountdown = 0;
			return;
		}

		if (--wanderCountdown > 0)
			return;
		wanderCountdown = WANDER_MIN_GAP
			+ ThreadLocalRandom.current()
				.nextInt(70 /* WANDER_MAX_GAP */ - WANDER_MIN_GAP);

		var local = client.getLocalPlayer();
		if (local == null || local.getWorldLocation() == null)
			return;
		var from = local.getWorldLocation();
		var random =
			ThreadLocalRandom.current();

		// Something to go and look at, if there is anything. A drift toward a
		// chicken reads as curiosity where the same walk to an empty tile reads
		// as pathing - and that is worth having while thieving too, where the
		// alternative is standing off at a distance staring at the player.
		//
		// The only difference is the near edge: while thieving it must not pick
		// something underfoot, or it would inspect its way straight back into
		// the way.
		int nearest = thieving ? THIEVING_KEEP_MIN : 0;
		if (driftToward(pickDistraction(from, nearest)))
			return;

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
			int dx = random.nextInt(-radius, radius + 1),
				dy = random.nextInt(-radius, radius + 1);
			if (dx == 0 && dy == 0)
				continue;
			if (wanderTo(from.dx(dx).dy(dy), null))
				return;
		}
	}

	/**
	 * How far off the follower keeps while the player works a pocket.
	 *
	 * <p>Far enough to be out from underfoot and out of the way of the click
	 * that matters, near enough to still read as waiting for you rather than
	 * having left.
	 */
	static final int THIEVING_KEEP_MIN = 4;

	/**
	 * Picks somewhere in a ring around the player and goes there.
	 *
	 * <p>A ring rather than a disc: the whole point is not to end up next to
	 * them again, and a plain random offset would keep choosing tiles that are
	 * technically a drift and practically underfoot.
	 */
	void driftAwayFrom(WorldPoint from,
		ThreadLocalRandom random)
	{
		for (int attempt = 0; attempt < 10; attempt++)
		{
			double angle = random.nextDouble() * PI * 2;
			int distance = random.nextInt(THIEVING_KEEP_MIN, 7 /* THIEVING_KEEP_MAX */ + 1),
				dx = (int) round(cos(angle) * distance),
				dy = (int) round(sin(angle) * distance);
			if (dx == 0 && dy == 0)
				continue;

			if (wanderTo(from.dx(dx).dy(dy), null))
				return;
		}
	}

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
	WorldPoint pickDistraction(WorldPoint from, int nearest)
	{
		// DISTRACTION_RADIUS: How far to look for something worth drifting over to.
		// <p>Wider than the plain wander radius, because the follower walks to a
		// tile BESIDE the thing and a nearer ring would only ever find what is
		// already underfoot. Not much wider though: the walk has to read as going
		// to look at something rather than as wandering off, and the follower is
		// still meant to be waiting for you.
		var candidates = peopleNear(from, 6 /* DISTRACTION_RADIUS */, nearest);
		return candidates.isEmpty() ? null
			: candidates.get(ThreadLocalRandom.current()
				.nextInt(candidates.size()));
	}

	/**
	 * The tiles of the NPCs and other players within so many tiles of the
	 * follower, nearest first, for the look round. The same population as
	 * the wander's distraction and for the same reason: they are what the
	 * client already lists.
	 */
	List<WorldPoint> thingsNearby(int radius)
	{
		var from = follower.getWorldLocation();
		var found = peopleNear(from, radius, 1);
		found.sort(Comparator.comparingInt(a -> a.distanceTo(from)));
		return found;
	}

	/**
	 * The tiles of the NPCs and the other players from {@code nearest} to
	 * {@code radius} tiles of {@code from}, on its plane, in the order the
	 * client lists them; none with no tile or no scene. Not the thrall: it is
	 * the follower's own body in thrall mode.
	 */
	List<WorldPoint> peopleNear(WorldPoint from, int radius, int nearest)
	{
		List<WorldPoint> found = new ArrayList<>();
		var view = client.getTopLevelWorldView();
		var local = client.getLocalPlayer();
		if (from == null || view == null)
			return found;
		for (var npc : view.npcs())
		{
			if (npc != null && npc != thrallNpc)
				addIfNear(found, npc.getWorldLocation(), from, radius, nearest);
		}
		for (var other : view.players())
		{
			if (other != null && other != local)
				addIfNear(found, other.getWorldLocation(), from, radius, nearest);
		}
		return found;
	}

	/**
	 * The signposts, statues and plaques within a few tiles of the player,
	 * name and tile. A seven by seven scan of the scene, run only while the
	 * player has stood still a while, so its cost is nothing in play.
	 */
	List<Entry<String, WorldPoint>> landmarksNearby()
	{
		List<Entry<String, WorldPoint>> found = new ArrayList<>();
		var local = client.getLocalPlayer();
		var lp = local == null ? null : local.getLocalLocation();
		var view = client.getTopLevelWorldView();
		if (lp == null || view == null || view.getScene() == null)
			return found;
		var tiles = view.getScene().getTiles();
		int plane = view.getPlane(),
			reach = Noticing.LANDMARK_WITHIN + 1;
		for (int dx = -reach; dx <= reach; dx++)
		{
			for (int dy = -reach; dy <= reach; dy++)
			{
				int x = lp.getSceneX() + dx,
					y = lp.getSceneY() + dy;
				if (plane < 0 || plane >= tiles.length || x < 0 || y < 0
					|| x >= tiles[plane].length || y >= tiles[plane][x].length
					|| tiles[plane][x][y] == null)
					continue;
				for (var object : tiles[plane][x][y].getGameObjects())
				{
					if (object == null)
						continue;
					String name = ErrandController.objectName(
						client, object.getId());
					if (Noticing.isLandmark(name))
						found.add(new AbstractMap.SimpleImmutableEntry<>(
							name, object.getWorldLocation()));
				}
			}
		}
		return found;
	}

	/**
	 * What of the follower's own has its feet or holds it still: thrall
	 * work, an errand, the spectator's stand, an emote held, the minutes, the
	 * seat, a moment or a flourish. The head, the eyes and the will each stand
	 * down for it. Not occupied(): the spectator here is only its stand, and
	 * the dialog box is each caller's own business.
	 */
	boolean ownBusiness()
	{
		return thrallNpc != null
			|| errands.isBusy()
			|| spectate.holdsTheFeet()
			|| emoteHold || minutesState != 0
			|| skilling.holdsTheFeet()
			|| moments.holdsTheFeet()
			|| flourish.isActive();
	}

	/**
	 * @param nearest how close a thing may be and still be worth walking to.
	 * Zero normally; while thieving it is the distance the follower is keeping,
	 * so that going to look at something cannot bring it back underfoot.
	 */
	static void addIfNear(List<WorldPoint> into, WorldPoint at, WorldPoint from,
		int radius, int nearest)
	{
		if (at != null && at.getPlane() == from.getPlane())
		{
			int distance = at.distanceTo(from);
			if (distance <= radius && distance >= nearest)
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
	boolean driftToward(WorldPoint thing)
	{
		if (thing == null)
			return false;

		int[][] beside = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
		List<int[]> order = new ArrayList<>(Arrays.asList(beside));
		Collections.shuffle(order);

		for (int[] offset : order)
		{
			if (wanderTo(thing.dx(offset[0]).dy(offset[1]), thing))
				return true;
		}
		return false;
	}

	/**
	 * Parks on the spot, if the ground lets it, as the follower's own drift,
	 * facing the given tile, or nothing in particular for null.
	 */
	boolean wanderTo(WorldPoint spot, WorldPoint face)
	{
		if (!follower.stayAt(spot))
			return false;
		wandered = true;
		follower.setStayFaceTile(face);
		return true;
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
	void readTimeAway()
	{
		long now = currentTimeMillis();
		String stored = config.lastSeenMs();
		long minutes = -1;
		if (!Strings.isNullOrEmpty(stored))
		{
			try
			{
				long then = Long.parseLong(stored.trim());
				// A stored time in the future is a clock change, not an absence.
				minutes = then > now ? -1 : (now - then) / 60000L;
			}
			catch (NumberFormatException e)
			{
			}
		}

		context().setMinutesAway(minutes);
		touchLastSeen();
	}

	void touchLastSeen()
	{
		if (!onMemoryProfile())
			return;
		setConfig("lastSeenMs",
			Long.toString(currentTimeMillis()));
	}

	void readCounters()
	{
		metWearingValue = restoreMemory(context(), config.counters(),
			gson, metWearingValue);
		memoryProfileId = currentProfileId();
		outfitProfileId = memoryProfileId;
	}

	/**
	 * The RuneLite profile the follower's memory was read from, or -1 while
	 * it holds none: before anything has been read, and after a logout or
	 * a shutdown.
	 *
	 * <p>Config is per profile, and a profile switch announces every changed
	 * key one by one with the NEW profile already current. Anything that
	 * wrote on those events, or on the periodic save, or on a shutdown
	 * caused by the switch, wrote the OLD profile's state into the new one -
	 * and the outfit auto-save did it the other way round, filing the new
	 * profile's stale outfit under the old profile's outfit name. Play
	 * report (issue #1): a brief switch and back lost the memory and put a
	 * two-day-old outfit on. So every write of state checks it is still on
	 * the profile the state came from, and a switch re-reads from the new one.
	 */
	long memoryProfileId = -1;

	/**
	 * The same, for the outfit, and never let go of. A logout drops the
	 * memory but not the outfit, which is still worn and still this
	 * profile's: an edit made at the login screen belongs under its name.
	 */
	long outfitProfileId = -1;

	long currentProfileId()
	{
		var profile = configManager.getProfile();
		return profile == null ? -1 : profile.getId();
	}

	/** Whether the follower's state belongs to the profile that is current now. */
	boolean onMemoryProfile()
	{
		return belongsToProfile(memoryProfileId, currentProfileId());
	}

	/** State read from one profile is never written into another, and unread state is never written at all. */
	static boolean belongsToProfile(long readFrom, long current)
	{
		return readFrom >= 0 && readFrom == current;
	}

	/**
	 * Between startUp and shutDown. Work queued for the client thread can
	 * run after a stop on the Swing thread, and must then do nothing.
	 */
	volatile boolean started;

	/**
	 * The RuneLite profile changed under the plugin. The changed keys have
	 * already been announced and applied; what the follower knows and wears
	 * belongs to the profile it was read from, so it is read again from this
	 * one rather than written over it. A switch is a session boundary: the
	 * old profile's mood, wants and counters go with it.
	 */
	@Subscribe
	void onProfileChanged(ProfileChanged event)
	{
		clientThread.invoke(() ->
		{
			// Switched to a profile where the plugin is off, the stop runs on
			// the Swing thread and can come first. This then read that
			// profile's memory into the engine the stop had just cleared, and
			// the next start read its own on top: two memories added together.
			if (!started)
				return;
			// Lines the old profile's follower queued go with it: delivered,
			// a wish or a want they ask would open in the new one's memory.
			speechQueue.clear();
			cutByTheScene = null;
			speakingUntilMs = 0;
			speechEngine.forget();
			// The reset clears every rule's edge, so the gear worn and the
			// place stood in would rise as news on the next tick. Same cure
			// as login: they are baseline.
			if (switchTakesTheBaseline(freshLogin))
				speechEngine.primeEdgesOnNextTick();
			metWearingValue = -1;
			readCounters();
			knowThePlayer();
			// This profile's own outfits, read before its outfit is restored
			// from them: the last profile's list must not dress this one.
			loadOutfitProfiles();
			if (panel != null)
				restoreActiveProfile();
			appearanceService.invalidate();
			rebuildFollower();
			syncPanel();
			// An open Memory window shows this profile's memory, which is the
			// one its Forget everything would wipe (QA3).
			var rows = memoryRows();
			SwingUtilities.invokeLater(() ->
			{
				if (memoryDialog != null && memoryDialog.isVisible())
					memoryDialog.show(rows);
			});
		});
	}

	/**
	 * Whether a profile switch, or Forget, takes the baseline itself. Not
	 * while a login is still to come: that login says hello first and primes
	 * after, and a window opened at the login screen, with no ticks there to
	 * run it down, was still open for the hello and swallowed it.
	 */
	static boolean switchTakesTheBaseline(boolean loginComing)
	{
		return !loginComing;
	}

	/**
	 * Whether a login reads the follower's memory in. Not when it already
	 * holds this profile's, which only a switch at the login screen leaves
	 * it doing: the tallies and the line wear merge by adding, and a second
	 * read doubled them.
	 */
	static boolean loginReadsTheMemory(long readFrom, long current)
	{
		return !belongsToProfile(readFrom, current);
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
		Gson gson, int fallbackMetWearing)
	{
		try
		{
			return context.restoreMemory(stored, gson, fallbackMetWearing);
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
	void writeCountersIfChanged()
	{
		if (context().isCountersDirty())
			writeCounters();
	}

	void writeCounters()
	{
		if (!onMemoryProfile())
			return;
		var context = context();
		setConfig("counters",
			snapshotMemory(context, metWearingValue, gson));
		context.clearCountersDirty();
	}

	/** Everything the follower remembers, as it goes to disk. See {@link #restoreMemory}. */
	static String snapshotMemory(TriggerContext context, int metWearing,
		Gson gson)
	{
		return context.snapshotMemory(gson, metWearing);
	}

	/**
	 * What the player was wearing the day the follower met them, in gp.
	 * -1 until it has been measured, which cannot happen until the composition
	 * is readable - several ticks after login on a slow world.
	 */
	int metWearingValue = -1;

	/** The worn set last priced, so the prices are not looked up every tick. */
	Set<Integer> lastPricedGear = Collections.emptySet();

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
	void priceWhatYouAreWearing()
	{
		var context = context();
		var worn = context.getEquippedItems();
		if (worn.equals(lastPricedGear))
			return;
		lastPricedGear = new HashSet<>(worn);

		long total = 0;
		for (int id : worn)
			total += max(0, itemManager.getItemPrice(id));
		int value = (int) min(total, Integer.MAX_VALUE);
		context.setWornValue(value);

		if (metWearingValue < 0 && value > 0)
		{
			metWearingValue = value;
			context.setMetWearingValue(value);
			context().markCountersDirty();
		}
	}

	/** The rule files' nicknames and region names, into the context now. */
	void giveTheNames()
	{
		context().setRegionNames(ruleLoader.getRegionNames());
	}

	/** The follower meets the player: the first day, the names and its traits. */
	void knowThePlayer()
	{
		noteFirstMeeting();
		giveTheNames();
	}

	/**
	 * Writes down the day, once, the first time this follower is ever run.
	 *
	 * <p>Existing players get today, which is a small lie and the only one
	 * available: the alternative is a follower that can never say how long it
	 * has known anybody because it was born before it started counting.
	 */
	void noteFirstMeeting()
	{
		var context = context();
		if (context.getMetOnDay() <= 0)
			context.setMetOnDay(LocalDate.now().toEpochDay());
	}

	static final int TELEPORT_ARRIVE_ANIMATION = 715,
		TELEPORT_CAST_SPOTANIM = 111;

	/** Ticks left before the stranded teleport lands, or 0 when none is in flight. */
	int strandedLandingTicks;

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
	void updateStrandedTeleport()
	{
		if (strandedLandingTicks > 0)
		{
			if (--strandedLandingTicks == 0)
			{
				follower.teleportToPlayer();
				follower.playAnimation(TELEPORT_ARRIVE_ANIMATION);
				follower.playSpotAnim(spotAnimRepository.get(1299 /* TELEPORT_ARRIVE_SPOTANIM */),
					FollowerEntity.TELEPORT_GRAPHIC_HEIGHT);
			}
			return;
		}

		// Anything that has deliberately put the follower somewhere else owns
		// it: a Stay, a Send, an errand, a possessed thrall. Being away from
		// the player is the point of all four, and none of them is stuck.
		if (!follower.isSpawned() || follower.isStaying() || follower.isPosed()
			|| follower.isNpcSlaved() || errands.isBusy())
			return;
		// Agility too: a follower a floor below on a rooftop course, or
		// waiting on an obstacle about to be queued, is behind, not stuck.
		if (follower.isDoingAgility() || follower.isHoldingForAgility())
		{
			follower.clearStranded();
			return;
		}

		// STRANDED_TELEPORT_TICKS: How long the follower may be walled off before it gives up and teleports.
		// <p>The count only climbs while there is no route AT ALL - the pathfinder
		// searches the whole loaded scene and settles for the closest reachable
		// tile, so an empty answer means a sealed room or a locked door rather
		// than a long way round. Five ticks is three seconds: long enough that
		// walking briefly out of sight is not a teleport, short enough that the
		// follower does not stand at a wall looking foolish.
		if (follower.getStrandedTicks() < 5 /* STRANDED_TELEPORT_TICKS */)
			return;

		follower.clearStranded();
		// TELEPORT_CAST_ANIMATION: The standard teleport cast and its landing, with the swirls that go with
		// them - the same pair the errand return uses, measured from the cache:
		// 714 casts at 1.58s, 715 lands at 1.56s, spotanims 111 out and 1299 back.
		follower.playAnimation(714 /* TELEPORT_CAST_ANIMATION */);
		follower.playSpotAnim(spotAnimRepository.get(TELEPORT_CAST_SPOTANIM),
			FollowerEntity.TELEPORT_GRAPHIC_HEIGHT);
		// Gone on the cast's last frame rather than standing there waiting for
		// the landing tick. The cast runs 1.58s and the landing is three ticks
		// out at 1.80s, so without this there is a fifth of a second of the
		// follower standing at the wall in its idle pose, mid-teleport, which
		// is exactly long enough to see. Cleared by teleportToPlayer on
		// arrival, so the duration only has to outlast the gap.
		follower.hideAfterEmote(1200);
		strandedLandingTicks = 3;
	}

	int ticksSinceLastSeen;

	/** Minutes this session has run, for the longest-session record. */
	int sessionMinutes;

	/** Whether the longest-session record has already been remarked on today. */
	boolean sessionRecordSaid;

	/** Set by the client tick when the mouse is over the follower; read once a game tick. */
	boolean hoveredThisTick;

	/** Consecutive game ticks the mouse has rested on the follower. */
	int hoverTicks;

	int ticksSinceLearningSave;

	/** Pushes the current outfit into the panel so it shows what is actually worn. */
	/**
	 * Lazily builds the one message-editor window and fronts it. Every rule
	 * group the panel used to spread over seven buttons lives here behind a
	 * category dropdown; only Locations is structural (regions editable,
	 * rules addable).
	 */
	void openMessagesDialog()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (messagesDialog == null)
				messagesDialog = new PhrasesDialog(gson, ruleLoader.getFile(),
					PhrasesDialog.categories(),
					// The tick loop keeps lastRegionId current; reading a
					// slightly stale int from the EDT is harmless.
					() -> lastRegionId);
			messagesDialog.open();
		});
	}

	/** The Memory window's rows: what the follower remembers now, read on the client thread. */
	List<String[]> memoryRows()
	{
		return MemoryDialog.summarise(context(),
			ruleLoader.getRegionNames(), this::accountKey);
	}

	/**
	 * Opens the memory window (R20) on a snapshot taken on the client thread,
	 * so the Swing side never reads live maps the game loop is writing.
	 */
	void openMemoryDialog()
	{
		clientThread.invoke(() ->
		{
			var rows = memoryRows();
			SwingUtilities.invokeLater(() ->
			{
				if (memoryDialog == null)
					memoryDialog = new MemoryDialog(this::forgetEverything);
				memoryDialog.show(rows);
			});
		});
	}

	/**
	 * The eraser behind the memory window's one button: every stored blob
	 * unset and the context rebuilt blank, so the follower meets you again as
	 * a stranger from this very moment. The rolled traits go too - a re-roll
	 * at the next login gives the stranger a fresh temperament - and the
	 * one-time arrival arc is unspent, so it can introduce itself again,
	 * which is both the charm and the proof the wipe worked.
	 */
	void forgetEverything()
	{
		clientThread.invoke(() ->
		{
			if (!started)
				return;
			configManager.unsetConfiguration(FollowerConfig.GROUP, "counters");
			configManager.unsetConfiguration(FollowerConfig.GROUP, "traits");
			configManager.unsetConfiguration(FollowerConfig.GROUP, "lastSeenMs");
			// Lines queued by the follower that no longer exists: saying them
			// would be strange enough, but their delivery latches - a queued
			// wish ask, say - would run against the blank context and hand
			// the brand-new stranger an open wish nobody heard asked.
			speechQueue.clear();
			cutByTheScene = null;
			speakingUntilMs = 0;
			speechEngine.forget();
			// The reset clears every rule's edge, so the gear worn and the
			// place stood in would rise as news on the next tick. Same cure
			// as a switch: baseline, unless a login is coming to take its own.
			// The stranger still introduces itself at the next login.
			if (switchTakesTheBaseline(freshLogin))
				speechEngine.primeEdgesOnNextTick();
			giveTheNames();
			metWearingValue = -1;
			sessionMinutes = 0;
			sessionRecordSaid = false;
			knownLevels.clear();
			sendStatus("Forgotten. All of it. Clean page.");

			var rows = memoryRows();
			SwingUtilities.invokeLater(() ->
			{
				if (memoryDialog != null)
					memoryDialog.show(rows);
			});
		});
	}

	/**
	 * The profile the panel is currently showing. While one is selected every
	 * outfit change is written straight back to it, so the dropdown behaves
	 * like a live wardrobe rather than a pair of load/save buttons.
	 */
	String activeProfile;

	// The follower is redressed through the same config write the panel uses,
	// so the existing config-changed path does the rebuild and panel sync.
	void loadOutfitProfile(String name)
	{
		loadOutfitProfile(name, false);
	}

	/**
	 * {@code quiet} is for the profile a session starts in: restoring it on
	 * every start and profile switch is not news, and on a fresh install it was
	 * the first thing the plugin ever said. A profile the player picks says so.
	 */
	void loadOutfitProfile(String name, boolean quiet)
	{
		String outfit = profileStore.get(name);
		if (outfit == null)
			return;
		setActiveProfile(name.trim());
		setConfig("customOutfit", outfit);
		// A profile with an identical outfit writes no config change, so the
		// panel is refreshed here rather than relying on the config event.
		syncPanel();
		// And the dropdown, which a profile worn by chat command left on the
		// old one, while the edits after it saved into this one (QA pass 4).
		if (panel != null)
			panel.setProfileNames(profileStore.names(), activeProfile);
		if (!quiet)
			sendStatus("Wearing outfit profile '" + activeProfile + "'.");
	}

	void setActiveProfile(String name)
	{
		activeProfile = name;
		setConfig("activeProfile",
			Strings.nullToEmpty(name));
	}

	/**
	 * This RuneLite profile's own outfit profiles, kept in its settings. One
	 * list shared by every profile let two of them on the same name write over
	 * each other's outfit, and a switch put the other profile's on this one.
	 */
	void loadOutfitProfiles()
	{
		profileStore.load(dataDir, config.outfitProfiles(), config.activeProfile(), config.customOutfit(),
			json -> setConfig("outfitProfiles", json));
		outfitListProfileId = currentProfileId();
	}

	/**
	 * The RuneLite profile the outfit list was read for. A switch announces
	 * the new profile's settings before the switch itself, while the list is
	 * still the last profile's; a thrall outfit named only in the new one's
	 * list is not unknown, just not read yet.
	 */
	long outfitListProfileId = -1;

	/**
	 * Picks the profile a session starts in: the one last worn, else the first
	 * the user made themselves, else the bare default body. Applied so the
	 * panel and the follower always agree with the dropdown on startup.
	 */
	void restoreActiveProfile()
	{
		String stored = config.activeProfile();
		String wanted = stored != null && !stored.trim().isEmpty()
			&& profileStore.get(stored.trim()) != null
			? stored.trim()
			: profileStore.firstUserProfile();
		loadOutfitProfile(wanted, true);
		panel.setProfileNames(profileStore.names(), activeProfile);
	}

	void saveOutfitProfile(String name)
	{
		if (name == null || name.trim().isEmpty())
			return;
		// The thrall looks are worn by name and nothing restores a changed
		// one; New profile... over one would lose it (QA3).
		if (profileStore.isProtected(name))
		{
			sendStatus("'" + name.trim() + Resources.text("builtInReplace"));
			return;
		}
		boolean replacing = profileStore.get(name.trim()) != null;
		profileStore.put(name, config.customOutfit());
		setActiveProfile(name.trim());
		panel.setProfileNames(profileStore.names(), activeProfile);
		sendStatus("Outfit profile '" + activeProfile + (replacing ? "' replaced." : "' created."));
	}

	void deleteOutfitProfile(String name)
	{
		if (name == null || name.trim().isEmpty())
			return;
		if (profileStore.isProtected(name))
		{
			sendStatus("'" + name.trim() + Resources.text("builtInDelete"));
			return;
		}
		if (!profileStore.remove(name))
		{
			sendStatus("No outfit profile named '" + name.trim() + "'.");
			return;
		}
		if (name.trim().equals(activeProfile))
			// Fall back to something real rather than leaving the dropdown
			// pointing at a profile that no longer exists.
			loadOutfitProfile(profileStore.firstUserProfile());
		panel.setProfileNames(profileStore.names(), activeProfile);
		sendStatus("Outfit profile '" + name.trim() + "' deleted.");
	}

	/**
	 * Writes the follower's current outfit back to the selected profile. The
	 * content comparison is what makes this safe to call from every panel
	 * sync: loading a profile leaves the two identical and writes nothing,
	 * while an actual edit differs and is saved.
	 */
	void autoSaveActiveProfile()
	{
		if (activeProfile == null)
			return;
		String stored = profileStore.get(activeProfile);
		if (stored == null)
		{
			activeProfile = null;
			return;
		}
		// Mid-switch the outfit key already belongs to the other RuneLite
		// profile; filing it under this profile's outfit name is how a
		// two-day-old outfit came back (issue #1).
		if (!belongsToProfile(outfitProfileId, currentProfileId()))
			return;
		String current = config.customOutfit();
		if (!stored.equals(current))
			profileStore.put(activeProfile, current);
	}

	void syncPanel()
	{
		if (panel == null)
			return;
		// Every route to a changed outfit lands here, so this is the one place
		// the active profile needs keeping up to date.
		autoSaveActiveProfile();
		panel.setOutfit(OutfitParser.parse(config.customOutfit()));
		// Once the catalogue is in, nothing to say: the item and kit counts
		// were developer detail in front of every player (::follower status
		// still prints them).
		panel.setStatus(modelRepository.isLoaded()
			? ""
			: "reading the game cache...");
	}

	/**
	 * Equips an item chosen in the panel. When {@code slot} is null the slot is
	 * resolved from the item's own equipment stats, which is what stops a torso item
	 * being written into the legs slot.
	 */
	void equipFromPanel(int itemId, KitType slot)
	{
		clientThread.invoke(() ->
		{
			var target = slot != null ? slot : resolveSlot(itemId);
			if (target == null)
			{
				sendStatus("Couldn't work out which slot item " + itemId + " goes in.");
				return;
			}

			var outfit = OutfitParser.parse(config.customOutfit());
			outfit.setItem(target, itemId);
			persistOutfit(outfit);

			String name = modelRepository.itemName(itemId);
			sendStatus("Now wearing " + (name == null ? "item " + itemId : name)
				+ " (" + target.name().toLowerCase(Locale.ROOT) + ").");

			// Weapon animations can only be learned by watching a real player
			// hold one (measured: they are not in the cache as data). Say so
			// rather than let an unknown weapon silently stand unarmed.
			if (target == WEAPON && !stanceLibrary.knows(itemId))
				sendStatus("No stances learned for that weapon yet. Wield it once, or stand near someone who has.");
		});
	}

	void clearSlotFromPanel(KitType slot)
	{
		clientThread.invoke(() ->
		{
			var outfit = OutfitParser.parse(config.customOutfit());
			outfit.clear(slot);
			persistOutfit(outfit);
		});
	}

	void setGender(int gender)
	{
		clientThread.invoke(() ->
		{
			var outfit = OutfitParser.parse(config.customOutfit());
			if (outfit.getGender() == gender)
				return;

			// Remember the styles of the body being left, resolved so defaults count
			// too - what you SEE is what comes back.
			setConfig(
				kitMemoryKey(outfit.getGender()), serializeKits(outfit.withDefaultBody()));

			outfit.setGender(gender);
			// Kit ids are gender-specific, so the old ones would render as the wrong
			// body. Drop them, then restore what this gender wore last time; anything
			// not remembered falls back to withDefaultBody() as before.
			for (var part : ModelRepository.BODY_PARTS)
			{
				if (outfit.isKit(part))
					outfit.clear(part);
			}
			applyKitMemory(outfit,
				configManager.getConfiguration(FollowerConfig.GROUP, kitMemoryKey(gender)));

			persistOutfit(outfit);
		});
	}

	static String kitMemoryKey(int gender)
	{
		return gender == 1 ? "bodyKitsFemale" : "bodyKitsMale";
	}

	static String serializeKits(Outfit resolved)
	{
		var sb = new StringBuilder();
		for (var part : ModelRepository.BODY_PARTS)
		{
			if (resolved.isKit(part))
			{
				if (sb.length() > 0)
					sb.append(';');
				sb.append(part.name()).append('=').append(resolved.kitId(part));
			}
		}
		// Skin is remembered per gender too, so each body keeps its own tone. The
		// other colours (hair, torso, legs, boots) deliberately carry across.
		sb.append(sb.length() > 0 ? ";" : "").append("SKIN=").append(resolved.getColors()[4]);
		return sb.toString();
	}

	void applyKitMemory(Outfit outfit, String stored)
	{
		if (stored == null || stored.trim().isEmpty())
			return;
		for (String token : stored.split(";"))
		{
			var halves = token.split("=");
			if (halves.length != 2)
				continue;
			try
			{
				if (halves[0].trim().equalsIgnoreCase("SKIN"))
				{
					int index = Integer.parseInt(halves[1].trim());
					if (index >= 0 && index < GamePalette.SKIN.length)
					{
						int[] colors = outfit.getColors();
						colors[4] = index;
						outfit.setColors(colors);
					}
					continue;
				}

				var part = KitType.valueOf(halves[0].trim());
				int kitId = Integer.parseInt(halves[1].trim());
				// The dump may have changed since this was saved; only restore kits
				// that still exist, and let defaults cover the rest.
				if (modelRepository.kit(kitId) != null)
					outfit.setKit(part, kitId);
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
	void cycleKit(KitType part, int direction)
	{
		clientThread.invoke(() ->
		{
			var outfit = OutfitParser.parse(config.customOutfit()).withDefaultBody();

			// Only kits whose bodyPartId matches this slot AND this body type, so a
			// beard can never land in the boots slot and genders never cross over.
			var choices = modelRepository.kitsFor(part, outfit.getGender());
			if (choices.isEmpty())
			{
				sendStatus("No styles available for " + part.name().toLowerCase(Locale.ROOT) + ".");
				return;
			}

			// Every part always holds a kit. There is no "none": a bare slot leaves a
			// hole in the body, and the clean-shaven look is itself one of the jaw
			// kits rather than the absence of one.
			int index = outfit.isKit(part) ? choices.indexOf(outfit.kitId(part)) : -1,
				current = max(index, 0),
				next = floorMod(current + direction, choices.size());

			outfit.setKit(part, choices.get(next));
			persistOutfit(outfit);
			String partName = part.name().toLowerCase(Locale.ROOT);
			sendStatus(Character.toUpperCase(partName.charAt(0)) + partName.substring(1)
				+ " style " + (next + 1) + " of " + choices.size() + ".");
		});
	}

	/** Sets a body-colour palette index from the panel's exact picker. */
	void setBodyColor(int colorSlot, int index)
	{
		clientThread.invoke(() ->
		{
			var outfit = OutfitParser.parse(config.customOutfit());
			int[] colors = outfit.getColors();
			if (colorSlot < 0 || colorSlot >= colors.length)
				return;

			colors[colorSlot] = index;
			outfit.setColors(colors);

			persistOutfit(outfit);
		});
	}

	void clearOutfit()
	{
		clientThread.invoke(() -> persistOutfit(new Outfit()));
	}

	void copyGearToCustomOutfit()
	{
		var local = client.getLocalPlayer();
		if (local == null)
			return;
		persistOutfit(Outfit.from(local.getPlayerComposition()));
		sendStatus("Copied your gear onto the follower.");
	}

	/** Writes the outfit to config and rebuilds. */
	void persistOutfit(Outfit outfit)
	{
		setConfig("customOutfit", outfit.toString());
		appearanceService.invalidate();
		rebuildFollower();
		syncPanel();
	}



	/** Parses "6723,4191" or "6723 4191" into ids, dropping anything unusable. */
	/**
	 * The arrival effect, played the tick the follower appears.
	 *
	 * <p>Resolved here rather than held on the entity, because there are three
	 * ways to spawn - the queued rebuild a login takes, the tick loop's
	 * fallback, and ::follower here - and only one of them was ever configured.
	 * A spotanim cached on a field is only as good as the path that set it,
	 * which is why the animation arrived without its particle. Every teleport
	 * elsewhere in the plugin resolves at the moment of use; this now does too.
	 */
	SpotAnimRepository.Entry spawnGraphic()
	{
		int[] ids = parseAnimationIds(config.spawnGraphic());
		if (ids.length == 0)
			return null;
		var fx = spotAnimRepository.get(ids[0]);
		return fx;
	}

	static int[] parseAnimationIds(String text)
	{
		if (text == null)
			return new int[0];
		return Arrays.stream(text.split("[,\\s]+")).mapToInt(token ->
		{
			try
			{
				return Integer.parseInt(token.trim());
			}
			catch (NumberFormatException ignored)
			{
				// Not a number, or nothing at all; skip it rather than
				// refusing the whole chain.
				return 0;
			}
		}).filter(id -> id > 0).toArray();
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
	int weaponUsage(int itemId)
	{
		var stats = itemManager.getItemStats(itemId);
		if (stats == null || stats.getEquipment() == null)
			return StyleSource.UNKNOWN;
		var equipment = stats.getEquipment();
		int melee = max(equipment.getAstab(),
			max(equipment.getAslash(), equipment.getAcrush()));
		int ranged = equipment.getArange(),
			magic = equipment.getAmagic();

		int style;
		if (ranged > melee && ranged >= magic)
			style = StyleSource.RANGED;
		else if (magic > melee && magic > ranged)
			style = StyleSource.MAGIC;
		else if (melee == 0 && ranged == 0 && magic == 0)
			// A weapon with no offensive bonus at all says nothing about itself.
			return StyleSource.UNKNOWN;
		else
			style = StyleSource.MELEE;
		return StyleSource.packed(
			style, equipment.isTwoHanded());
	}

	KitType resolveSlot(int itemId)
	{
		var stats = itemManager.getItemStats(itemId);
		if (stats != null && stats.getEquipment() != null)
			return slotFromEquipmentIndex(stats.getEquipment().getSlot());

		// The stats database only knows items with combat stats. Purely
		// cosmetic wearables - a plain Scroll, the Book of portraiture - have
		// a worn model and a wear position in the cache but no stats row, so
		// they used to report as "not wearable" while rendering perfectly
		// well. Our own model dump carries the cache's wear position, and it
		// is the same dump the renderer builds from: if the item is in there,
		// the follower can wear it, by construction.
		var entry = modelRepository.item(itemId);
		return entry != null && entry.wp1 != null
			? slotFromEquipmentIndex(entry.wp1)
			: null;
	}

	/**
	 * Where a prop goes when it is HELD: its wear slot, or the hand for a tool
	 * the game never lets anyone wear. A fishing rod, a net, a harpoon, a rake
	 * have a worn model but no wear position - the game puts them in the hand
	 * for the work animation only - so the follower fished with nothing in
	 * its hands ("Prop 307 has no wearable slot").
	 */
	KitType handSlot(int itemId)
	{
		var slot = resolveSlot(itemId);
		if (slot != null)
			return slot;
		return handSlot(itemId, false);
	}

	/**
	 * Where an animation's own prop goes: the hand it is named for, whatever
	 * the item is worn as. The game draws an animation's left-hand item in the
	 * shield slot and its right-hand item in the weapon slot, so the quill of
	 * the charting write is in the shield slot and its scroll in the weapon
	 * slot, not both fighting over the weapon hand. Null for an item with no
	 * worn model: the game draws nothing in that hand.
	 */
	KitType handSlot(int itemId, boolean leftHand)
	{
		var entry = modelRepository.item(itemId);
		if (entry == null || !hasWornModel(entry))
			return null;
		return leftHand ? SHIELD : WEAPON;
	}

	static boolean hasWornModel(ModelRepository.Entry entry)
	{
		for (int[] models : new int[][]{entry.m, entry.f})
		{
			if (models == null)
				continue;
			for (int model : models)
			{
				if (model >= 0)
					return true;
			}
		}
		return false;
	}

	static KitType slotFromEquipmentIndex(int slot)
	{
		// Equipment container indices are NOT contiguous and do NOT line up with
		// KitType ordinals (6, 8 and 11 are kit-only slots with no equippable item),
		// so map them explicitly rather than relying on ordinal coincidence.
		// Rings and ammo have no worn model; ARMS/HAIR/JAW are body kits.
		return slot >= 0 && slot < KIT_BY_EQUIPMENT_INDEX.length ? KIT_BY_EQUIPMENT_INDEX[slot] : null;
	}

	/** The worn slot of each equipment container index; null where there is no worn model. */
	static final KitType[] KIT_BY_EQUIPMENT_INDEX = {HEAD, CAPE, AMULET,
		WEAPON, TORSO, SHIELD, null, LEGS, null, HANDS, BOOTS};

	// --------------------------------------------------------------- lifecycle

	@Subscribe
	void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
				// Hold off briefly: at this point the scene is still settling, the
				// camera is snapping into place and collision data may not be ready,
				// so a follower spawned right now appears mid-load and in the wrong
				// spot. Let the world become visible first.
				// SPAWN_DELAY_TICKS: ~2 seconds at 600ms per tick, after the world becomes visible.
				spawnDelayTicks = 4 /* SPAWN_DELAY_TICKS */;
				rebuildQueued = true;

				// The GE replay arrives between here and the first tick.
				geOffersSettled = false;

				// Belt and braces for a client that skipped the login screen.
				ensureCatalogues();

				// Only on a REAL login - LOGGED_IN also follows every chunk reload.
				if (!freshLogin && hopped)
				{
					// Back from a hop. Nothing to greet and nothing to count -
					// only the baseline to re-take.
					hopped = false;
					speechEngine.primeEdgesOnNextTick();

					// The one thing a hop actually changes is everyone - said
					// once the priming above has taken its baseline. It cannot
					// go either side of that gate: inside the window the winner
					// is discarded and the line is dead, and immediately before
					// it the scene's cleared edges all rise at once, so a piece
					// of worn gear outranks the hop and remarks on itself as if
					// you had just put it on. Waiting costs two ticks and wins
					// the pass outright.
					speechEngine.dispatchAfterPriming(
						TriggerEvent.worldHop(client.getWorld()));
				}

				if (freshLogin)
				{
					freshLogin = false;
					hopped = false;
					reportLoadErrors();

					// How long it has been, worked out before the greeting so a
					// rule can choose a different one for a long absence.
					// Everything the follower remembers, also before the
					// greeting: a session count is only worth mentioning as
					// part of hello, and the tallies want to be whole before
					// the first kill of the session lands on top of them.
					// Read first, so the profile the stamp below is written to
					// is known to be the one it came from. Not again after a
					// switch at the login screen, which has already read it.
					if (loginReadsTheMemory(memoryProfileId, currentProfileId()))
						readCounters();
					// Cooldowns kept through the logout are this account's.
					speechEngine.loggedInAs(client.getAccountHash());
					readTimeAway();
					context().clearDailyTallies();
					knowThePlayer();
					var counted = context();
					counted.setSessionCount(counted.getSessionCount() + 1);
					sessionMinutes = 0;
					sessionRecordSaid = false;
					writeCounters();

					// The gear you logged in wearing and the place you logged in
					// standing are baseline, not news - the first ticks' evaluation
					// records edges without firing.
					speechEngine.primeEdgesOnNextTick();

					// The LOGIN trigger, so rules can greet: a delayTicks on the rule
					// puts the hello a couple of seconds AFTER the follower's own
					// spawn (which waits out spawnDelayTicks itself). Raised once
					// the prime has taken its baseline, as the world hop is: QA,
					// 15 Oct, dispatched before it, logging in poisoned or in the
					// Wilderness had that standing state rise on the login pass,
					// outrank the greeting, and take the hello.
					speechEngine.dispatchAfterPriming(TriggerEvent.simple(LOGIN));

					// The world literally changes under everyone's feet once a
					// week; a scribe keeping maps would notice. Seeded silently
					// on the first ever run, like the personal bests, so a new
					// install does not announce an update that is not one.
					int revision = client.getRevision();
					if (config.lastRevision() > 0 && revision != config.lastRevision())
						speechEngine.dispatchAfterPriming(
							TriggerEvent.simple(GAME_UPDATE));
					if (revision != config.lastRevision())
						setConfig(
							"lastRevision", revision);
				}

				// Sync the colour table to the brightness setting as it stands now;
				// the varp listener keeps it matched to slider changes from here.
				clientThread.invokeLater(() ->
				{
					int brightness = client.getVarpValue(BRIGHTNESS_VARP);
					GameColourTable.setBrightnessSetting(brightness);
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
				// The scene is rebuilt from here; its fires are announced
				// again as it loads, so the old set is only ever stale.
				hotGround.clear();
				// Objects and NPCs dropped around a load are shuffling,
				// not a tree falling; the company ignores them for a beat.
				skilling.sceneLoading();
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
				// Save BEFORE the reset drops the context: a normal logout does
				// not go through shutDown, so everything counted since the last
				// hundred-tick flush died here - the kill that just happened,
				// the death that usually prompts the logout, and any once-ever
				// line said in that window, which then said itself again.
				writeCountersIfChanged();
				// A session really ending. What the follower was feeling, what
				// it was hoping for and the question it was waiting on all
				// belonged to that session.
				speechEngine.reset();
				// The blank left behind is nobody's memory until a login reads
				// one back. Switching the plugin off here saved it over the
				// real one, and the follower met you again as a stranger.
				memoryProfileId = -1;
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
	void tearDownScene()
	{
		hotGround.clear();
		ensureCatalogues();
		resetThrallQuietly();
		// Actor references from a scene that is going away.
		damagedByPlayer.clear();
		errands.reset();
		// Mid-gesture when the scene went: the scroll must not survive it.
		flourish.abort();
		// The session, the seat and the borrowed tool all belonged to it.
		skilling.reset();
		moments.reset();
		// What was noticed, looked at and hesitated over was all in this
		// scene. No despawn comes for a scene thrown away, so a drop or a star
		// left behind was walked to and named in the next world.
		noticing.reset();
		gaze.reset();
		will.reset();
		// The conversation too. Left open at a logout it took the presses in
		// the chatbox at the login screen, and at the next login the first
		// step closed it and gave the last session's answer to this one.
		// Closed here, the answer lands in the session it was chosen in, and
		// what it says goes with the scene, as every line in flight does.
		dialog.close();
		// Same for the minute-taking: the meeting went with the scene.
		endMinutes(false);
		// A held copy (a sit, a dance loop) too. Its pose override outlives
		// the body, and the next one held it at every stop, weapon away.
		if (mirroredPose != 0)
		{
			follower.clearPoseOverride(mirroredPose);
			mirroredPose = 0;
			loopHands = null;
			emoteDisarmed = false;
		}
		pendingMime = 0;
		copyAtWork = false;
		welfareChecked = false;
		follower.despawn();
		appearanceService.invalidate();
		overlay.clear();
		// Anything still waiting its turn belongs to the scene that just went;
		// it must not surface in the next one.
		speechQueue.clear();
		cutByTheScene = null;
		speakingUntilMs = 0;
		knownLevels.clear();
		knownXp.clear();
		lastPlayerTile = null;
		lastRegionId = -1;
		// A park goes with the scene, as one already taken does with the
		// despawn: a Send still waiting for the feet was walked to after the
		// next spawn, from the last world or the last session. Follow me off
		// parks the follower again as it spawns.
		parkedByPlayer = false;
		parkWhenFree = false;
		sentWhenFree = null;
		// A logout tab still up when the next scene starts is not a goodbye.
		logoutTabOpen = true;
		sceneOn = false;
		sceneGoneTicks = 0;
		quietUntilTick = 0;
		speechEngine.setSceneQuiet(false);
		speechEngine.setInCutscene(false);
	}

	@Subscribe
	void onConfigChanged(ConfigChanged event)
	{
		if (!FollowerConfig.GROUP.equals(event.getGroup()))
			return;

		applyConfig();

		String key = event.getKey();
		if (panel != null)
		{
			if ("muted".equals(key))
				panel.setSetting(key, config.muted());
			if ("chattiness".equals(key))
				panel.setSetting(key, config.chattiness());
			if ("mirrorToChat".equals(key))
				panel.setSetting(key, config.mirrorToChat());
			if ("errandsEnabled".equals(key))
				panel.setSetting(key, config.errandsEnabled());
			if ("followerName".equals(key))
				panel.setFollowerName(followerName());
		}
		// The Follow me toggle, finally wired to its own description ("stays
		// where it was last placed"): off parks the follower through the same
		// stay the right-click op uses, on releases it. Applied on the toggle
		// EDGE only, so it never fights the systems that borrow the feet -
		// errands, spectating, the minute-taking - and a leash snap from
		// walking a scene away still brings it along rather than stranding it.
		if ("followEnabled".equals(key))
		{
			boolean wantFollow = config.followEnabled();
			clientThread.invoke(() -> applyFollowEnabled(wantFollow));
		}
		if ("thrallMeleeProfile".equals(key) || "thrallRangedProfile".equals(key)
			|| "thrallMagicProfile".equals(key))
		{
			// A typed name that matches nothing falls back to the ready-made
			// profile in silence; say so here, where the typing happened.
			String typed = "thrallMeleeProfile".equals(key) ? config.thrallMeleeProfile()
				: "thrallRangedProfile".equals(key) ? config.thrallRangedProfile()
				: config.thrallMagicProfile();
			String name = typed == null ? "" : typed.trim();
			if (!name.isEmpty() && profileStore.get(name) == null
				&& belongsToProfile(outfitListProfileId, currentProfileId()))
				sendStatus(unknownProfileMessage(name));
			if (panel != null)
				panel.setThrallProfiles(config.thrallMeleeProfile(),
					config.thrallRangedProfile(), config.thrallMagicProfile());
		}
		// Thrall only turned off brings the follower back now and in its own
		// outfit: the mode's login built it no model, and its exit left the
		// thrall's behind. Turned on, the same rebuild hides it.
		boolean outfitChanged = "customOutfit".equals(key) || "verticalOffset".equals(key);
		if (outfitChanged || "thrallOnly".equals(key))
		{
			clientThread.invoke(() ->
			{
				appearanceService.invalidate();
				rebuildFollower();
			});
		}
		if (outfitChanged)
			// The outfit can change from outside the panel - loading a profile,
			// the chat commands - and the equipment grid and body-kit rows have
			// to follow it, not just the follower.
			syncPanel();
	}

	/**
	 * Whether a Follow me value needs applying: only when the player's park is
	 * not already in that state, so the right-click ops' own write changes
	 * nothing. The player's park, not any stay: the wander, the seat and an
	 * errand park the follower too, and Stay here pressed over one of them
	 * has to take.
	 */
	static boolean followToggleNeedsAction(boolean followEnabled, boolean parkedByPlayer)
	{
		return followEnabled == parkedByPlayer;
	}

	/**
	 * Applies a Follow me value on the client thread. The right-click Stay
	 * and Follow ops move the follower and then write this same setting;
	 * only a value it is not already living gets applied, so that write is
	 * a no-op.
	 */
	void applyFollowEnabled(boolean wantFollow)
	{
		if (followToggleNeedsAction(wantFollow, parkedByPlayer))
		{
			if (wantFollow)
				followAgain();
			else
			{
				follower.stayHere();
				parkedByThePlayer(null);
			}
		}
	}

	/**
	 * A park the player gave has just been taken, and is theirs until they
	 * say Follow. The wander's drift is handed over rather than let go under
	 * it. Anything else of the follower's own that has the feet reads this
	 * park as its feet taken and stands down, and the follower is parked
	 * again as it lets go.
	 *
	 * @param sentTo the tile a Send settled on, or null for a Stay
	 */
	void parkedByThePlayer(WorldPoint sentTo)
	{
		parkedByPlayer = true;
		wandered = false;
		parkWhenFree = feetBorrowed();
		sentWhenFree = parkWhenFree ? sentTo : null;
	}

	/**
	 * Where a follower that was left waiting arrives again: the tile it was
	 * left on, when that is on the player's floor and within the reach a Send
	 * has, since any further and its leash would snap it straight back to
	 * them. Null when it should arrive beside the player.
	 */
	static WorldPoint parkToReturnTo(String kept, WorldPoint player)
	{
		if (kept == null || player == null)
			return null;
		var parts = kept.split(",");
		if (parts.length != 3)
			return null;
		WorldPoint post;
		try
		{
			post = new WorldPoint(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
				Integer.parseInt(parts[2].trim()));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
		if (post.getPlane() != player.getPlane()
			|| post.distanceTo(player) > FollowerEntity.STAY_LEASH - 2)
			return null;
		return post;
	}

	/**
	 * What to keep of the player's park across a relog: its tile while it
	 * holds, what was kept while it waits to be taken again, and nothing once
	 * it is over. Only while the follower stands in the world: the teardown
	 * that ends a scene ends the park with it, and that is not the player
	 * saying Follow.
	 */
	static String parkToKeep(boolean spawned, boolean parkedByPlayer, boolean waitingToPark,
		WorldPoint stayTile, String kept)
	{
		if (!spawned)
			return kept;
		if (!parkedByPlayer)
			return "";
		if (waitingToPark || stayTile == null)
			return kept;
		return stayTile.getX() + "," + stayTile.getY() + "," + stayTile.getPlane();
	}

	/** Once a tick, after the park is kept: records where it holds, for the next spawn. */
	void rememberThePark()
	{
		String kept = config.parkedAt();
		String now = parkToKeep(follower.isSpawned(), parkedByPlayer, parkWhenFree,
			follower.isStaying() ? follower.getStayTile() : null, kept);
		if (!now.equals(kept))
			setConfig("parkedAt", now);
	}

	/** The player said Follow: the park is over, whoever had the feet. */
	void followAgain()
	{
		parkedByPlayer = false;
		parkWhenFree = false;
		sentWhenFree = null;
		follower.resumeFollowing();
	}

	/**
	 * Once a tick, after everything that borrows the feet. A park given while
	 * one of them had them is taken again once it has let go: a Stay where
	 * the follower then stands, which is beside the player when what let go
	 * sent it home, and a Send on its tile. Walking a Stay back to where it
	 * was given, after an errand or thrall work snapped it home, read as the
	 * follower popping over and trotting off again. A park the follower was
	 * taken off by force - the leash, a teleport, thrall work - is over,
	 * since those bring it along to the player.
	 */
	void keepThePlayersPark()
	{
		if (parkWhenFree && !feetBorrowed())
		{
			parkWhenFree = false;
			if (!follower.isStaying()
				&& (sentWhenFree == null || !follower.stayAt(sentWhenFree)))
				follower.stayHere();
			sentWhenFree = null;
		}
		if (!parkWhenFree && !follower.isStaying())
			parkedByPlayer = false;
	}

	/**
	 * Whether something of the follower's own has its feet: thrall work, an
	 * errand, the spectator, a planted emote, the minutes, the seat, a moment
	 * or a write-up. Each hands them back itself when it is done.
	 */
	boolean feetBorrowed()
	{
		return thrallBusiness()
			|| (errands != null && errands.isBusy())
			|| (spectate != null && spectate.holdsTheFeet())
			|| emoteHold || minutesState != 0
			|| (skilling != null && skilling.holdsTheFeet())
			|| (moments != null && moments.holdsTheFeet())
			|| (flourish != null && flourish.isActive());
	}

	/**
	 * What an errand stands aside for. Minutes-taking counts: an errand must
	 * not set off with the scroll already out over somebody's meeting. Nor
	 * during a work session, where the seat has the feet, nor while the seat
	 * is kept through a bank trip after one. And one under way stands down
	 * for a park the player gave, or for a scene being filmed, which may be
	 * a walk with Follow me and no park at all, or for a sketch, or for a
	 * cutscene.
	 */
	boolean errandBusy()
	{
		return dialog.isOpen() || follower.isNpcSlaved() || minutesState != 0
			|| (skilling != null && skilling.blocksErrands())
			|| (moments != null && moments.holdsTheFeet())
			|| parkedByPlayer
			|| inCutscene() || follower.isDoingAgility()
			// ERRANDS_AFTER_AGILITY_MS: Agility XP this recently keeps errands off: on a course the run back
			// to the first obstacle is not agility, and an errand begun there turns
			// the watch off and misses the first climb.
			|| currentTimeMillis() - lastAgilityXpMs < 60_000L /* ERRANDS_AFTER_AGILITY_MS */;
	}

	long lastAgilityXpMs;

	/**
	 * What beats the seat. Thrall work and the spectator, over their whole
	 * ownership window - the exit flourish included - and a park the player
	 * gave, which no work nearby takes the follower off. And a cutscene: a
	 * seat taken again after the scene's catch-up walked it back and forth.
	 */
	boolean seatFeetTaken()
	{
		return thrallBusiness() || (spectate != null && spectate.holdsTheFeet())
			|| parkedByPlayer || inCutscene() || follower.isDoingAgility();
	}

	/**
	 * What beats a moment. The moments excuse the seat for themselves; thrall
	 * work, the spectator and an errand under way beat them. A park the
	 * player gave is handed to them apart: it beats everything that walks or
	 * plants the follower, so a level is clapped where it stands and the news
	 * is nodded at, but not the wave at the logout tab, which moves nothing.
	 * A scene being filmed beats them all, the wave included. The clap and
	 * the nod are played where the follower stands, past this gate, so in a
	 * take the level and the news are not handed to the moments at all. A
	 * sketch beats them too: a walk off would leave the picture it is in.
	 * And a cutscene and the quiet after it: no peering at the cast as it
	 * turns to the player, and nothing begun the moment the scene lets go.
	 */
	boolean momentFeetTaken()
	{
		return thrallBusiness() || (spectate != null && spectate.holdsTheFeet())
			|| (errands != null && errands.isBusy())
			|| sceneQuiet() || follower.isDoingAgility();
	}

	/**
	 * What the spectator keeps out of the way for: thrall work and its whole
	 * exit, an errand in progress, a scene being filmed, and a cutscene, a
	 * boss's intro among them. A park the player gave is handed to it apart:
	 * the fight is still announced at both ends, but watched from the post.
	 */
	boolean spectateBusy()
	{
		return thrallBusiness()
			|| errands.isBusy()
			|| inCutscene()
			|| follower.isDoingAgility();
	}

	/**
	 * The side panel's Stay here / Follow me button: writes Follow me, the
	 * one record of follow versus stay. A Send parks the follower without
	 * touching the setting, so the write can be a no-op that raises no
	 * change event; then the move is applied here instead.
	 */
	void followFromPanel(boolean enabled)
	{
		if (config.followEnabled() == enabled)
		{
			clientThread.invoke(() -> applyFollowEnabled(enabled));
			return;
		}
		setFollowEnabled(enabled);
	}

	/** The answer to a profile name nothing is saved under; the thrall settings share it. */
	String unknownProfileMessage(String name)
	{
		return "No outfit profile named '" + name + "'. Saved: "
			+ (profileStore.names().isEmpty()
			? "(none)" : String.join(", ", profileStore.names()));
	}

	/** Whether the follower is parked by the player: Follow me off, a right-click Stay or a Send. */
	boolean isStaying()
	{
		return stayReported;
	}

	void applyConfig()
	{
		follower.setVerticalOffset(config.verticalOffset());
		speechEngine.setDefaultOutput(config.defaultOutput());
		speechEngine.setGlobalCooldownMs(config.chattiness().getGapMs());
		speechEngine.setMuted(config.muted());
		speechEngine.setDisabledGroups(collectDisabledGroups());
	}

	Set<String> collectDisabledGroups()
	{
		Set<String> disabled = new HashSet<>();
		if (!config.groupBoss())
			disabled.add("boss");
		if (!config.groupHealth())
			disabled.add("health");
		if (!config.groupArea())
			disabled.add("area");
		if (!config.groupIdle())
			disabled.add("idle");
		if (!config.groupGear())
			disabled.add("gear");
		if (!config.groupQuest())
			disabled.add("quest");
		if (!config.groupCombat())
			disabled.add("combat");
		if (!config.groupMimic())
			disabled.add("mimic");
		if (!config.groupMemory())
			disabled.add("memory");
		if (!config.groupClock())
			disabled.add("clock");
		if (!config.groupReactions())
			disabled.add("reactions");
		// Copying agility, the follower falls after the player does, and
		// agility-fell says so once it has. fail-slip, on the player's own
		// 'You slip', would make that two remarks about one fall.
		if (config.copiesAgility())
			disabled.add("fail-slip");
		for (String token : config.disabledGroups().split(","))
		{
			String trimmed = token.trim().toLowerCase(Locale.ROOT);
			if (!trimmed.isEmpty())
				disabled.add(trimmed);
		}
		return disabled;
	}

	// ---------------------------------------------------------------- follower

	void rebuildFollower()
	{
		var local = client.getLocalPlayer();
		if (local == null || local.getWorldLocation() == null)
			return;

		if (shouldHide())
		{
			follower.despawn();
			return;
		}

		// The prop is skipped in thrall mode, whose override is a shared object
		// and whose conscript has no time to read.
		// Nor a prop still behind its reach: it comes in at the bottom of it.
		boolean propShown = outfitOverride == null && !propBehindReach;
		var outfit = dressForRender(resolveOutfit(),
			spectateDisarmed ||
			emoteDisarmed,
			propShown ? propSlot : null, propItemId,
			outfitOverride == null ? wornSlot : null, wornItemId);
		dressForPose(outfit, propShown ? propHands : null);
		dressForPose(outfit, outfitOverride == null ? emoteHands : null);
		dressForPose(outfit, outfitOverride == null && emoteHands == null ? loopHands : null);
		dressForPose(outfit, outfitOverride == null ? follower.getCrossingHands() : null);

		appearanceService.request(outfit, appearance ->
		{
			if (appearance == null)
			{
				log.warn("Could not build a follower model for {}", outfit);
				if (!buildFailureTold)
				{
					buildFailureTold = true;
					sendStatus("Couldn't build the follower model. " + modelHint());
				}
				follower.despawn();
				return;
			}
			buildFailureTold = false;
			// The dialogue heads are asked for with each look, so a model the
			// client has never drawn has landed by the first Talk-to.
			dialog.fetchHeads();

			// Stances follow the weapon the FOLLOWER holds, not the one we hold.
			follower.setWeapon(outfit.isItem(WEAPON)
				? outfit.itemId(WEAPON)
				: StanceLibrary.UNARMED);
			// A staff is swung one-handed when the other hand holds a shield.
			followerHoldsShield = outfit.isItem(SHIELD);


			if (follower.isSpawned())
				follower.setAppearance(appearance);
			else
			{
				if (thrallNpc != null)
				{
					// Materialising as the thrall (thrall-only mode): on the
					// thrall's tile, with the thrall's own burst and no arrival
					// puff or spawn animation of its own.
					follower.setSpawnAnimation(new int[0]);
					var at = thrallNpc.getWorldLocation();
					follower.spawn(appearance, at != null ? at : local.getWorldLocation(), null, 0);
					return;
				}
				follower.setSpawnAnimation(parseAnimationIds(config.spawnAnimation()));
				spawnFollower(appearance, local, true);
			}
		});
	}

	/**
	 * Puts the follower in the world: back on the post it was left waiting
	 * on, else on a free tile behind the player rather than inside them (when
	 * {@code behind}), else on the player's tile. The queued rebuild a login
	 * takes spawns behind; the tick's bare fallback does not.
	 */
	void spawnFollower(ComposedAppearance appearance, Player local,
		boolean behind)
	{
		var post = config.followEnabled() ? null
			: parkToReturnTo(config.parkedAt(), local.getWorldLocation());
		var free = post != null ? post : behind ? follower.restingTileBehind(local, 2) : null;
		follower.spawn(appearance, free != null ? free : local.getWorldLocation(),
			spawnGraphic(), config.spawnGraphicHeight(), post != null);

		// The very first spawn ever is the one moment naming is cheap and
		// natural, and both spawns can be it: the queued rebuild a login
		// takes, and the tick's fallback.
		if (!config.namePrompted())
			namePromptDelayTicks = NAME_PROMPT_DELAY_TICKS;
		maybeSendCommandHint();

		// Spawned with Follow me off: parked from the first tile, not
		// following until somebody notices the toggle.
		if (!config.followEnabled())
		{
			follower.stayHere();
			parkedByThePlayer(null);
		}
	}

	/**
	 * The offline dumps are optional: whatever they didn't provide is parsed
	 * from the client's own loaded cache. Retried from login-adjacent states
	 * because the cache indexes may not exist when the plugin starts.
	 */
	void ensureCatalogues()
	{
		if (modelRepository.isLoaded() && slotIndex.isEmpty())
			// An index that came back empty on an earlier pass, from a login
			// that beat the item stats to it.
			buildSlotIndexAsync();
		// Read under a dump too, so what the game added since the dump is there.
		if (!modelRepository.hasReadTheLiveCache())
		{
			modelRepository.loadFromClient(client);
			if (modelRepository.hasReadTheLiveCache())
			{
				// The outfit picker's slot filter and status line were built
				// against an empty catalogue; rebuild them on the real one.
				buildSlotIndexAsync();
				syncPanel();
			}
		}
		if (!spotAnimRepository.hasReadTheLiveCache())
			spotAnimRepository.loadFromClient(client);
		if (!kitSelectabilityLoaded)
		{
			// Always from the LIVE cache, even when a dump supplied the
			// entries: the flag is not part of the dump format, and the
			// picker needs it to hide the styles character creation hides.
			Set<Integer> nonSelectable = new HashSet<>();
			LiveCacheParser.parseKits(client, nonSelectable);
			if (!nonSelectable.isEmpty())
			{
				modelRepository.setNonSelectableKits(nonSelectable);
				kitSelectabilityLoaded = true;
				syncPanel();
			}
		}
	}

	boolean kitSelectabilityLoaded;


	/**
	 * A transient item shown on the follower without touching the saved
	 * outfit. For animations that mime holding something: the reading and
	 * writing animations move the arms and draw nothing, because in the real
	 * game the book comes from the reader's own equipment.
	 */
	KitType propSlot;
	int propItemId;

	/**
	 * A thing in each hand instead, left then right, as an animation names
	 * them - the quill and the scroll of the writing pose - or null. Dressed
	 * as the film dresses a pose's hands.
	 */
	int[] propHands;

	/** A prop taken out but not yet shown: the reach for it is still going down. */
	boolean propBehindReach;

	/** How far into the reach the prop comes into the hand: the bottom of the bend. */
	static final double REACH_BOTTOM = 0.5;

	/** The second transient prop, worn rather than held: a borrowed hat. */
	KitType wornSlot;
	int wornItemId;

	Outfit resolveOutfit()
	{
		// Thrall mode dresses the follower without touching the configured
		// outfit, so leaving the mode restores exactly what the player set up.
		if (outfitOverride != null)
			return outfitOverride;
		List<String> errors = new ArrayList<>();
		var outfit = OutfitParser.parse(config.customOutfit(), errors);
		String warning = errors.isEmpty() ? null : "Outfit warnings: " + String.join("; ", errors);
		if (warning != null && !warning.equals(lastOutfitWarning))
			sendStatus(warning);
		lastOutfitWarning = warning;
		return outfit;
	}

	/**
	 * The outfit warning last told, and whether a failed build has been. Every
	 * settings change and outfit edit rebuilds the follower, so one bad outfit
	 * string repeated its warning on each unrelated tweak (QA, 15 Oct). Told
	 * again only when it changes, or comes back after a clean build.
	 */
	String lastOutfitWarning;
	boolean buildFailureTold;

	/**
	 * The last two steps before the model is built: the disarm, then the prop.
	 *
	 * <p>Hands free first: a follower kneeling in prayer should not still be
	 * gripping a sword and a shield, and neither should one waving. Then the
	 * transient prop - a book for the reading animation, a scroll for the
	 * scroll one, a borrowed axe for a work session - overlaid AFTER the
	 * outfit resolves so it never touches what the player configured. The
	 * order is the point: the disarm empties the outfit's own weapon slot and
	 * the prop fills it again, which is what keeps a borrowed tool in hand
	 * through a mirrored emote. The animations themselves hold nothing: the
	 * game draws whatever is in your hands, and these hands are ours to fill.
	 *
	 * @param propSlot the slot the prop takes, or null for no prop
	 */
	static Outfit dressForRender(Outfit outfit, boolean disarmed, KitType propSlot, int propItemId)
	{
		return dressForRender(outfit, disarmed, propSlot, propItemId, null, 0);
	}

	/**
	 * As above, with a second prop that is worn rather than held - the hat of
	 * a skilling outfit over the tool - laid on after the hand's.
	 */
	static Outfit dressForRender(Outfit outfit, boolean disarmed, KitType propSlot, int propItemId,
		KitType wornSlot, int wornItemId)
	{
		if (disarmed)
		{
			outfit.clear(WEAPON);
			outfit.clear(SHIELD);
		}
		if (propSlot != null)
		{
			// A prop in the weapon hand takes the shield with it. Nobody reads
			// a scroll or swings a borrowed axe with a shield still strapped on,
			// and the hand the prop goes in is the one the shield argues with.
			if (propSlot == WEAPON)
				outfit.clear(SHIELD);
			outfit.setItem(propSlot, propItemId);
		}
		if (wornSlot != null)
			outfit.setItem(wornSlot, wornItemId);
		return outfit;
	}

	/**
	 * The film's pose dressed as the game dresses it, laid on last: each hand
	 * as its animation names it, left then right. -1 keeps what the hand
	 * holds, Peer's sword and all; any other negative empties it; an item goes
	 * in that hand's slot, or empties it when the item has no worn model,
	 * since the game draws nothing there either. An item goes in as a prop,
	 * drawn whatever the gear hides, as the game draws it.
	 *
	 * @param hands left and right, -1 to keep, or null
	 *              to leave the outfit as it is
	 */
	void dressForPose(Outfit outfit, int[] hands)
	{
		if (hands == null)
			return;
		dressHand(outfit, hands[0], true);
		dressHand(outfit, hands[1], false);
	}

	void dressHand(Outfit outfit, int item, boolean leftHand)
	{
		if (item == -1)
			return;
		var slot = item >= 0 ? handSlot(item, leftHand) : null;
		if (slot == null)
		{
			outfit.clear(leftHand ? SHIELD : WEAPON);
			return;
		}
		outfit.setProp(slot, item);
	}

	/** True while the shield channel has the follower's weapon and shield put away. */
	boolean spectateDisarmed;

	/** True while an emote has them put away. */
	boolean emoteDisarmed;

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
	boolean wasThieving;

	boolean emoteHold;

	/**
	 * The looping animation currently being held to match the player's, or 0.
	 *
	 * <p>Held emotes cannot go through the one-shot path: their animation never
	 * finishes, so the chain would wait forever. A pose override loops on its
	 * own and is released when the player's animation changes.
	 */
	int mirroredPose;

	/** A copy waiting on the follower to walk over to the work first. */
	int pendingMime;

	/** Whether the waiting copy is a one-shot - a hammer blow - rather than a held loop. */
	boolean pendingOneShot;

	/** Whether the copy under way is of the work, and so lasts only while beside it. */
	boolean copyAtWork;

	/**
	 * What a held emote loop names for the hands (Explore's sextant), kept
	 * apart from emoteHands, which its entry clip's end takes off.
	 */
	int[] loopHands;

	void startPoseMirror(int animationId)
	{
		mirroredPose = animationId;
		clientThread.invoke(() -> follower.setPoseOverride(animationId));
		// Not a copy of the work, which keeps to the tools it borrowed.
		loopHands = copyAtWork ? null : follower.emoteHandItems(animationId);
		boolean disarmed = emoteDisarmed;
		refreshEmoteDisarm(follower.isEmotePlaying());
		if (loopHands != null && disarmed == emoteDisarmed)
			rebuildFollower();
	}

	void stopPoseMirror()
	{
		pendingMime = 0;
		copyAtWork = false;
		// Whatever it walked over for is finished; back to the seat.
		skilling.dropTheJoin();
		if (mirroredPose == 0)
			return;
		int held = mirroredPose;
		mirroredPose = 0;
		// Only its own: a moment that stepped in may have put a pose of its own on.
		clientThread.invoke(() -> follower.clearPoseOverride(held));
		boolean handsBack = loopHands != null;
		loopHands = null;
		boolean disarmed = emoteDisarmed;
		refreshEmoteDisarm(follower.isEmotePlaying());
		if (handsBack && disarmed == emoteDisarmed)
			rebuildFollower();
	}

	/** How long a rule's gesture said on the move waits for the follower to stand. */
	static final int EMOTE_WAIT_TICKS = 3;

	void updateEmoteDisarm(boolean emoting)
	{
		if (!emoting && emoteHold)
		{
			emoteHold = false;
			follower.resumeFollowing();
		}
		// The emote's own things go with it, in the same rebuild as the
		// weapon coming back when there is one.
		boolean handsBack = !emoting && emoteHands != null;
		emoteHands = emoting ? emoteHands : null;
		clipKeepsHands = emoting && clipKeepsHands;
		boolean disarmed = emoteDisarmed;
		refreshEmoteDisarm(emoting);
		if (handsBack && disarmed == emoteDisarmed)
			rebuildFollower();
	}

	/**
	 * What the emote playing names for each hand, left then right, or null.
	 * The game draws an animation's own hand items whatever is worn: the
	 * charting quill and scroll, the home teleport's chalk stick, a teleport
	 * scroll, a smith's hammer. Dressed as the film dresses a pose.
	 */
	int[] emoteHands;

	/**
	 * Clips that keep both hands as they are, as the game plays them: the
	 * block keeps weapon and shield up, the Ancient and Arceuus teleports the
	 * staff. Not the mood idles, keep-handed too, where a kiteshield would
	 * cross the face.
	 */
	static final Set<Integer> KEEPS_HANDS = Set.of(
		AnimationID.HUMAN_UNARMEDBLOCK,
		AnimationID.ZAROS_VERTICAL_CASTING,
		AnimationID.HUMAN_SPELLCAST_ARCEUUS_TELEPORT);

	/** Whether the emote clip on now is one of those. */
	boolean clipKeepsHands;

	/** Each clip of an emote, as it goes on: dresses the hands it names. */
	void dressEmoteHands(int clip)
	{
		if (clipKeepsHands != KEEPS_HANDS.contains(clip))
		{
			clipKeepsHands = !clipKeepsHands;
			refreshEmoteDisarm(follower.isEmotePlaying());
		}
		int[] hands = thrallNpc == null ? follower.emoteHandItems(clip) : null;
		if (Arrays.equals(hands, emoteHands))
			return;
		emoteHands = hands;
		rebuildFollower();
	}

	/**
	 * Empties the follower's hands while it is emoting AT ALL - a one-shot copy
	 * or a held pose. Both are emotes as far as a player is concerned, so both
	 * put the weapon away.
	 */
	void refreshEmoteDisarm(boolean emoting)
	{
		boolean wanted = (emoting && !clipKeepsHands || mirroredPose != 0
			|| skilling != null && skilling.worksBareHanded()) && thrallNpc == null;
		if (wanted == emoteDisarmed)
			return;
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
	void setSpectateDisarmed(boolean disarmed)
	{
		if (spectateDisarmed == disarmed)
			return;
		spectateDisarmed = disarmed;
		clientThread.invoke(this::rebuildFollower);
	}

	// ------------------------------------------------------------- thrall mode

	NPC thrallNpc;
	String thrallStyle = "";
	Outfit outfitOverride;

	/** Countdown to stage two of the exit flourish (snap home + redress). */
	int thrallExitTicks;

	/** Plays the spawn-in burst a tick late, once the follower stands at the thrall. */
	int pendingThrallSpawnFxTicks;

	/** Per-style resurrect impact vfx: the burst when a thrall phases in or out. */
	static int thrallImpactSpotAnim(String style)
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
	net.runelite.client.callback.RenderCallbackManager renderCallbacks;

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
	final RenderCallback thrallHider =
		new RenderCallback()
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
	static String thrallStyleFor(int npcId)
	{
		return npcId >= 10878 && npcId <= 10886
			? new String[]{"magic", "ranged", "melee"}[(npcId - 10878) / 3] : null;
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
	static final int THRALL_SUMMONED_VARBIT = 12290;

	/**
	 * Possession briefly lost to a scene rebuild: the thrall NPC despawned
	 * during a load, not by expiry, so the follower keeps its outfit and
	 * silently re-possesses the NPC when the scene brings it back.
	 */
	boolean thrallLimbo;

	/** Ticks spent in limbo; a respawn that never comes ends the possession. */
	int thrallLimboTicks;

	/** Ticks since the last LOADING state, to classify NPC despawns. */
	int ticksSinceLoading = 1000;

	void maybeAdoptThrall(NPC npc)
	{
		if (!replacesThralls(config.thrallMode(), config.thrallOnly()) || npc == thrallNpc)
			return;
		// A fresh summon shows as the cooldown varbit reading 1 (it stays up
		// for ten seconds after any cast - fresh, mid-combat or resummon). A
		// limbo re-acquire may come later than that, so limbo bypasses it and
		// matches purely on thrall id and proximity.
		if (client.getVarbitValue(THRALL_SUMMONED_VARBIT) != 1 && !thrallLimbo)
			return;
		String style = thrallStyleFor(npc.getId());
		if (style == null)
			return;
		var local = client.getLocalPlayer();
		int distance = local != null && local.getWorldLocation() != null && npc.getWorldLocation() != null
			? npc.getWorldLocation().distanceTo(local.getWorldLocation()) : -1;
		if (distance < 0 || distance > 6)
			return;
		if (thrallNpc != null)
		{
			// A resummon while the old thrall is still in the scene: switch
			// bodies rather than dropping back to a vanilla thrall.
			String previous = thrallStyle;
			switchThrall(npc, style);
			dispatch(TriggerEvent.thrallSwitch(previous, style));
		}
		else if (thrallExitPendingTicks > 0)
		{
			// The old thrall vanished a moment ago and we held the exit back
			// for exactly this: a resummon, not an expiry. Take the new body
			// and acknowledge the change instead of saying goodbye.
			String previous = thrallExitStyle;
			thrallExitPendingTicks = 0;
			switchThrall(npc, style);
			dispatch(TriggerEvent.thrallSwitch(previous, style));
		}
		else if (thrallLimbo)
		{
			// The scene gave the thrall back: same possession, no re-greeting.
			thrallLimbo = false;
			switchThrall(npc, style);
		}
		else
			adoptThrall(npc, style);
	}

	/** Adopts a thrall already in the scene when the varbit beats its spawn event. */
	void adoptExistingThrall()
	{
		var view = client.getTopLevelWorldView();
		if (view == null)
			return;
		for (var npc : view.npcs())
		{
			if (npc != null && thrallStyleFor(npc.getId()) != null)
				maybeAdoptThrall(npc);
		}
	}

	void switchThrall(NPC npc, String style)
	{
		boolean styleChanged = !style.equals(thrallStyle);
		thrallNpc = npc;
		thrallStyle = style;
		thrallExitTicks = 0;
		pendingThrallSpawnFxTicks = 1;
		if (styleChanged)
			outfitOverride = resolveThrallOutfit(style);
		clientThread.invoke(() ->
		{
			follower.slaveToNpc(npc);
			follower.setThrallCircleFromNpcModel(thrallCircleModelId(npc));
			if (styleChanged)
				rebuildFollower();
		});
	}

	void adoptThrall(NPC npc, String style)
	{

		thrallNpc = npc;
		thrallStyle = style;
		thrallExitTicks = 0;
		pendingThrallSpawnFxTicks = 1;
		outfitOverride = resolveThrallOutfit(style);
		clientThread.invoke(() ->
		{
			follower.slaveToNpc(npc);
			follower.setThrallCircleFromNpcModel(thrallCircleModelId(npc));
			rebuildFollower();
		});
		announceSummon(style);
		maybeSendThrallHint();
	}

	/**
	 * Replace thralls is on by default, so a player who wanted their own
	 * thrall is told once, at the first summon taken, how to have it back.
	 */
	void maybeSendThrallHint()
	{
		// Thrall only replaces them too, so with it ticked the hint names both.
		if (!replacesThralls(config.thrallMode(), config.thrallOnly()) || config.thrallHintShown())
			return;
		setConfig("thrallHintShown", true);
		sendStatus(config.thrallOnly()
			? "Untick Thrall only and Replace thralls in settings to keep your own."
			: "Untick Replace thralls in settings to keep your own.");
	}

	/**
	 * The hello. In thrall-only mode the summon ends an absence the tick keeps
	 * muted, but it arrives between ticks, before the tick can lift that mute;
	 * so the mute is set again here from the thrall just taken, the way the
	 * thieving lines are said with theirs lifted.
	 */
	void announceSummon(String style)
	{
		speechEngine.setMuted(config.muted() || wasThieving
			|| absentUntilSummoned(config.thrallOnly(), thrallBusiness()) || thrallQuiet());
		dispatch(TriggerEvent.thrall(THRALL_START, style));
	}

	/**
	 * The thrall's whole body - circle included - is a single model on its
	 * composition (measured: 41985/41989/41988 for melee/ranged/magic).
	 * Reading it live keeps every tier authentic.
	 */
	static int thrallCircleModelId(NPC npc)
	{
		var comp = npc.getTransformedComposition() != null
			? npc.getTransformedComposition() : npc.getComposition();
		int[] models = comp == null ? null : comp.getModels();
		return models != null && models.length > 0 ? models[0] : -1;
	}

	/** Ticks left before a vanished thrall counts as gone rather than resummoned. */
	int thrallExitPendingTicks;
	String thrallExitStyle = "";

	void exitThrallMode()
	{
		if (thrallNpc == null && !thrallLimbo)
			return;
		performThrallExit(thrallStyle);
	}

	/** The exit proper: phase out where it stands, then home and redressed. */
	void performThrallExit(String style)
	{
		thrallExitPendingTicks = 0;
		thrallNpc = null;
		thrallLimbo = false;

		// Stage one: still in thrall dress at the thrall's last spot, the
		// follower phases out through the same per-style burst a thrall
		// materialises with. Stage two (in onGameTick) snaps it home.
		clientThread.invoke(() ->
		{
			follower.endNpcSlaveHolding();
			// THRALL_EXIT_ANIMATION: Exit motion, and the necromancy shimmer for the return to follower form.
			follower.playAnimation(8973 /* THRALL_EXIT_ANIMATION */);
			follower.playSpotAnim(spotAnimRepository.get(thrallImpactSpotAnim(style)));
		});
		thrallExitTicks = 4;
		dispatch(TriggerEvent.thrall(THRALL_END, style));
	}

	/** Logout/hop teardown: no snap, no message, just clean state. */
	void resetThrallQuietly()
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

	Outfit resolveThrallOutfit(String style)
	{
		String profileName = "melee".equals(style) ? config.thrallMeleeProfile()
			: "ranged".equals(style) ? config.thrallRangedProfile()
			: config.thrallMagicProfile();
		String outfit = profileStore.get(profileName);
		if (outfit == null)
			// The configured profile was renamed or deleted; the seeded style
			// profiles are restored on every load, so these always exist.
			outfit = profileStore.get("melee".equals(style) ? "Melee"
				: "ranged".equals(style) ? "Ranged" : "Magic");
		return OutfitParser.parse(outfit == null ? "" : outfit);
	}

	/** Whether the follower's current outfit has something in the shield slot. */
	boolean followerHoldsShield;

	/**
	 * What the follower swings, matched to what it is actually holding: its
	 * own weapon's swing for the kind of blow the player's style deals, when
	 * that is known, else a generic swing for the style. A scimitar should not
	 * slash like a godsword just because both are melee.
	 */
	int thrallAttackAnimation()
	{
		return thrallSwing(stanceLibrary, thrallStyle, follower.getWeaponItemId(),
			client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY), attackStyle(), followerHoldsShield);
	}

	/**
	 * {@link #thrallSwing(String, int, int)} for the follower's weapon: the
	 * kind of blow the player's style deals with their own weapon category,
	 * thrown with the follower's weapon, one-handed when the follower holds a
	 * shield.
	 *
	 * @param weaponCategory the player's weapon category (VarbitID.COMBAT_WEAPON_CATEGORY)
	 * @param style the player's selected style button (COM_MODE)
	 * @param shield whether the follower's outfit has something in the shield slot
	 */
	static int thrallSwing(StanceLibrary library, String thrallStyle,
		int weapon, int weaponCategory, int style, boolean shield)
	{
		int kind = library.attackKind(weaponCategory, style);
		return thrallSwing(thrallStyle, library.castFor(weapon),
			StanceLibrary.withShield(
				library.attackFor(weapon, style, kind), shield));
	}

	/**
	 * The swing for a thrall's style. A magic thrall casts: a staff's own
	 * swing is its melee bash, and the shipped stances know that bash for
	 * every staff, so the weapon's attack would have the caster club its
	 * target. Melee and ranged take the weapon's swing, else a plain one.
	 *
	 * @param cast a cast that goes with the weapon, or 0
	 * @param swing the weapon's swing, or 0
	 */
	static int thrallSwing(String thrallStyle, int cast, int swing)
	{
		if (!"melee".equals(thrallStyle) && !"ranged".equals(thrallStyle))
			return cast > 0 ? cast : 1162 /* GENERIC_MAGIC_ATTACK */;
		if (swing > 0)
			return swing;
		// GENERIC_MELEE_ATTACK: Generic swings, used only until the follower's actual weapon has been
		// seen used: an unarmed-style slash, a bow shot and a strike spell cast.
		// <p>These were three settings once. They stopped making sense when the
		// stance library learned to match the animation to the weapon in the
		// follower's hands - a typed-in id could only contradict it, and the honest
		// answer for an unknown weapon is a plain swing of the right kind rather
		// than whatever someone last typed.
		return "melee".equals(thrallStyle) ? 390 /* GENERIC_MELEE_ATTACK */ : 426 /* GENERIC_RANGED_ATTACK */;
	}

	/**
	 * Whether the follower should not exist right now. Only the PvP hide
	 * says so: a cosmetic second player in the Wilderness reads as another
	 * person to everyone deciding whether to fight you.
	 *
	 * <p>This used to bail out first when Follow me was off, which silently
	 * disabled the PvP hide for anyone who parked their follower - the two
	 * settings are about different things, and neither may veto the other.
	 */
	boolean shouldHide()
	{
		// The setting says the Wilderness and other PvP areas: PVP_AREA_CLIENT
		// is the game's own flag for the rest, a PvP world outside its safe
		// zones among them. QA2, 15 Oct: only the Wilderness was read.
		return (config.hideInPvp() && (client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1
			|| client.getVarbitValue(VarbitID.PVP_AREA_CLIENT) == 1))
			|| absentUntilSummoned(config.thrallOnly(), thrallBusiness());
	}

	/**
	 * Whether a thrall has the follower in any way: possessed, in limbo
	 * across a load, waiting out a resummon, or mid-exit.
	 */
	boolean thrallBusiness()
	{
		return thrallNpc != null || thrallLimbo || thrallExitPendingTicks > 0 || thrallExitTicks > 0;
	}

	/**
	 * Thrall only: the follower exists while a thrall does and not otherwise.
	 * Some people want the thrall transmog and nothing else, and the honest
	 * shape of that is absence rather than a mute follower at their heel.
	 */
	static boolean absentUntilSummoned(boolean thrallOnly, boolean thrallBusiness)
	{
		return thrallOnly && !thrallBusiness;
	}

	/**
	 * Whether a summon is taken at all. Thrall only is the replacement and
	 * nothing else, so it brings the replacement with it; otherwise, ticked
	 * with Replace thralls off, it hides the follower until a summon that is
	 * never taken.
	 */
	static boolean replacesThralls(boolean thrallMode, boolean thrallOnly)
	{
		return thrallMode || thrallOnly;
	}

	/**
	 * Whether the follower keeps quiet as the thrall: Mute the thrall asks for
	 * silence from the hello to the goodbye, the phase-out included. Otherwise
	 * the thrall talks whenever the follower may.
	 */
	static boolean thrallKeepsQuiet(boolean muteThrall, boolean thrallBusiness)
	{
		return muteThrall && thrallBusiness;
	}

	boolean thrallQuiet()
	{
		return thrallKeepsQuiet(config.muteThrall(), thrallBusiness());
	}

	/**
	 * Once a frame: the player read for agility, and what the follower does
	 * with it. Only while the follower is out with them and nobody else has
	 * it: not parked by the player (a Stay means no agility), not thrall
	 * work, not a film, an errand or a sketch. Losing any of those lets the
	 * queue go, finishing the crossing under way. A cutscene only pauses
	 * the watching: the scene moves the player as it likes, and what is
	 * queued waits for it to end.
	 */
	void watchForAgility()
	{
		var state = client.getGameState();
		if (state == GameState.LOADING)
			// A rebuild: the recording carries on after it, in world units.
			return;
		var local = client.getLocalPlayer();
		boolean watching = config.copiesAgility() && local != null && state == GameState.LOGGED_IN
			&& follower.isSpawned() && !parkedByPlayer && !thrallBusiness()
			&& !errands.isBusy();
		if (!watching)
		{
			crossingWatch.reset();
			if (follower.isDoingAgility() || follower.isHoldingForAgility())
				follower.clearCrossings();
			return;
		}
		if (inCutscene())
		{
			crossingWatch.reset();
			return;
		}
		crossingWatch.sample(CrossingWatch.Sample.read(local,
			currentTimeMillis()));
	}


	@Subscribe
	void onBeforeRender(BeforeRender event)
	{
		// Before the follower's frame: a crossing seen this frame holds it
		// before the teleport rule can snap it after a player on the roof.
		watchForAgility();
		follower.updateFrame();
		shiftHoverTooltip();
	}
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
	String[] daySummary()
	{
		var context = context();
		return daySummary(talk(),
			sessionMinutes,
			context.getTally("kills:today"),
			context.getTally("levels:today"),
			context.getTally("deaths:today"),
			context.getMoodBand(),
			context.hasIncident() && !TriggerContext.isVague(context.getIncidentPhrase())
				? context.getIncidentPhrase() + context.getMemoryWhere()
				+ ", " + context.getMemoryWhen(currentTimeMillis()) : null);
	}

	/**
	 * The wording, with the figures already gathered. Split out from its caller
	 * so a test can walk every branch of it - there are sixteen combinations of
	 * what did and did not happen today, and they are all sentences the player
	 * reads.
	 */
	static String[] daySummary(Talk talk, int minutes, int kills, int levels, int deaths,
		String moodBand, String incident)
	{
		List<String> parts = new ArrayList<>();

		if (minutes >= 1)
		{
			// A scribe does not round 95 minutes down to "1 hour" - the
			// half hour happened and it was there for it.
			int hours = minutes / 60,
				rest = minutes % 60;
			parts.add(hours == 0
				? minutes + (minutes == 1 ? " minute" : " minutes")
				: hours + (hours == 1 ? " hour" : " hours")
					+ (rest == 0 ? "" : " and " + rest
						+ (rest == 1 ? " minute" : " minutes")));
		}

		if (kills > 0)
			parts.add(kills + (kills == 1 ? " thing killed" : " things killed"));
		if (levels > 0)
			parts.add(levels + (levels == 1 ? " level" : " levels"));
		if (deaths > 0)
			parts.add(deaths + (deaths == 1 ? " death" : " deaths"));

		List<String> pages = new ArrayList<>();
		if (parts.isEmpty())
			pages.add(Resources.text("blankPage"));
		else
			pages.add("Today's page: " + join(parts) + ".");

		// The mood is the follower's own verdict on all that, which is a
		// different thing from the figures and worth saying separately: the
		// band's line from talk.json, or the ordinary one for any other band.
		pages.add(talk.moods.getOrDefault(moodBand, talk.moods.get("ordinary")));

		// And the one thing it has not let go of.
		if (!Strings.isNullOrEmpty(incident))
			pages.add("I'm still thinking about " + incident + ".");
		return pages.toArray(new String[0]);
	}

	/** "a, b and c" - the way a person lists things. */
	static String join(List<String> parts)
	{
		if (parts.size() == 1)
			return parts.get(0);
		return String.join(", ", parts.subList(0, parts.size() - 1))
			+ " and " + parts.get(parts.size() - 1);
	}

	/** The previous joke, so consecutive draws never repeat. */
	static int lastJoke = -1;

	/** A random joke from the pool, guaranteed different from the last one told. */
	static String[] nextJoke(List<String[]> jokes)
	{
		int pick;
		do
		{
			pick = ThreadLocalRandom.current().nextInt(jokes.size());
		}
		while (pick == lastJoke && jokes.size() > 1);
		lastJoke = pick;
		return jokes.get(pick);
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
	void startTalking()
	{
		// Opening the box is presence too, even before anything is chosen.
		context().noteAttention();
		// A bubble still up when the box opens is two mouths at once; the
		// queue is likewise held while the box is open (see drainSpeechQueue).
		overlay.clear();
		// And again a tick before the box draws them, for a look changed since.
		dialog.fetchHeads();
		String asked = context().getAskedTree();
		var tree = dialogLoader.get(asked);
		if (tree != null)
		{
			dialog.startNextTick(followerName(),
				FollowerDialog.build(tree, this::answerQuestion, speechEngine::fill),
				tree.startId());
			return;
		}
		dialog.startNextTick(followerName(),
			talkScript(talk(), this::daySummary, this::answerQuestion,
				context().getMoodBand()), "start");
	}

	/**
	 * The player picked a branch that answers. Closes the question and tells
	 * the rules, which is what turns a conversation into a consequence.
	 */
	void answerQuestion(String answer)
	{
		var context = context();
		String tree = context.getAskedTree();
		context.noteAnswered();
		dispatch(TriggerEvent.answered(answer, tree));
	}

	/**
	 * The everyday Talk-to conversation, built from talk.json for what holds
	 * right now: whether a wish is open and the bag has the thing, the mood
	 * band, and which features this build has. The file's note says what
	 * keeps the conversation together and how a node names what it needs.
	 *
	 * <p>Package-private so the test can reach it. It is the only speech in the
	 * plugin outside phrases.json, and so the only speech nothing checked.
	 */
	static Map<String, FollowerDialog.Node> talkScript(Talk talk,
		java.util.function.Supplier<String[]> summary,
		Consumer<String> onAnswer,
		String moodBand)
	{
		Set<String> facts = new HashSet<>(Arrays.asList(
			"low".equals(moodBand) || "down".equals(moodBand) ? "lowish" : "",
			"errands",
			"thrall-outfits",
			moodBand));
		var tree = new DialogTree();
		tree.nodes = new ArrayList<>();
		for (var line : talk.nodes)
		{
			// "!fact" holds while the fact does not.
			if (line.when == null || Arrays.stream(line.when.split(" "))
				.allMatch(fact -> facts.contains(fact.replace("!", "")) != fact.startsWith("!")))
				tree.nodes.add(line);
		}
		var script = FollowerDialog.build(tree,
			onAnswer);
		// The pages made fresh on every visit: what today came to, and a joke.
		script.get("how-today-a").dynamic(summary);
		script.get("chat-joke-a").dynamic(() -> nextJoke(talk.jokes));
		script.get("chat-joke2-a").dynamic(() -> nextJoke(talk.jokes));
		return script;
	}

	/** talk.json: the Talk-to script's nodes, its joke pool and the day summary's moods. */
	static class Talk
	{
		/** Each a setup page and a punchline page. */
		List<String[]> jokes;

		/** What the day summary says of each mood band, and of any other ("ordinary"). */
		Map<String, String> moods;

		List<Line> nodes;

		/** A node, built only while the facts its {@code when} names hold. */
		static class Line extends DialogTree.DialogNode
		{
			String when;
		}
	}

	/** talk.json, read from the jar on the first Talk-to. */
	Talk talk;

	Talk talk()
	{
		if (talk == null)
		{
			try (java.io.Reader in = Resources.reader("talk.json"))
			{
				talk = gson.fromJson(in, Talk.class);
			}
			catch (IOException e)
			{
				throw new java.io.UncheckedIOException(e);
			}
		}
		return talk;
	}

	/**
	 * Injects the follower's right-click menu. The game cannot give a client-side
	 * object a native menu, but entries can be added to whatever menu opens while
	 * the mouse is over the follower's projected clickbox. Entries are created
	 * bottom-up: the last one created displays on top.
	 */
	@Subscribe
	void onMenuOpened(MenuOpened event)
	{
		if (!follower.isSpawned())
			return;

		if (!follower.isUnderMouse(client.getMouseCanvasPosition()))
		{
			addSendFollowerEntry(event);
			return;
		}

		// Over an interface drawn on top of the follower - the bank, the
		// inventory, the chatbox - the menu is the interface's.
		if (!offersWalkHere(event.getMenuEntries()))
			return;

		// The hover hint injects a Talk-to every client tick so the game draws
		// the top-left text natively; when the menu OPENS that entry is still
		// in it, and adding a fresh set would duplicate it. Strip our own
		// entries first, then rebuild the full menu in NPC order.
		client.getMenu().setMenuEntries(Arrays.stream(client.getMenu().getMenuEntries())
			.filter(entry -> entry.getType() != MenuAction.RUNELITE
				|| !followerTarget().equals(entry.getTarget()))
			.toArray(MenuEntry[]::new));

		// Examine sits where a real NPC's does: below Walk here, above Cancel -
		// index 1 in the bottom-up entry array (Cancel is index 0).
		addFollowerMenuEntry(1, "Examine", () ->
		{
			client.addChatMessage(
				net.runelite.api.ChatMessageType.NPC_EXAMINE, "",
				"A travelling scribe. Always writing something down.", null);
			// Being looked up is worth noticing. The examine text is the
			// game's line about the follower; a rule gets to give the
			// follower's line about being looked up.
			dispatch(TriggerEvent.simple(EXAMINED));
		});

		addFollowerMenuEntry("Dance", () -> follower.playAnimation(866));
		addFollowerMenuEntry("Wave", () -> follower.playAnimation(863));
		addFollowerMenuEntry("Step-in-front", () ->
		{
			var local = client.getLocalPlayer();
			if (local == null)
				return;
			if (!follower.moveToFront(local))
				speak(Resources.text("noRoomInFront"),
					SpeechOutput.OVERHEAD, null, -1, null);
		});
		// Second from the top, under Talk-to: the control a new player needs
		// most, not buried under the emotes. The player's own park decides, not
		// any stay: standing clear to watch a fight, the seat and the wander hold
		// the follower too, and a Stay given over one of them has to be on offer.
		if (parkedByPlayer)
		{
			addFollowerMenuEntry("Follow-me", () ->
			{
				followAgain();
				setFollowEnabled(true);
			});
		}
		else
		{
			addFollowerMenuEntry("Stay", () ->
			{
				follower.stayHere();
				parkedByThePlayer(null);
				setFollowEnabled(false);
				// Stay is the moment positional control has been asked for, so
				// the one place Send is taught is the first of these clicks.
				maybeSendSendHint();
			});
		}
		addFollowerMenuEntry(talkOption(), this::startTalking);
	}

	/**
	 * "Talk-to" sharpens into "Answer" while a question is waiting, the way a
	 * real NPC's default op turns quest-specific. The overhead line that asked
	 * scrolls away in seconds; the hover hint is the durable sign that the
	 * follower is waiting on you, and it explains why the dialog that opens
	 * is not the everyday one.
	 */
	String talkOption()
	{
		return context().getAskedTree().isEmpty()
			? "Talk-to" : "Answer";
	}

	/**
	 * Adds a "Send" entry to a ground tile's menu, so the follower can be posed
	 * anywhere by pointing at the spot. SHIFT must be held when the menu opens -
	 * the game's own convention for secondary actions (shift-drop, shift-click
	 * configs) - so ordinary right-clicks stay uncluttered. Created at index 1 -
	 * just above Cancel, which the game keeps last - so it can never steal the
	 * left-click from Walk here. Only menus that actually target the ground (they contain
	 * a Walk here entry) get one.
	 */
	void addSendFollowerEntry(MenuOpened event)
	{
		if (!client.isKeyPressed(KeyCode.KC_SHIFT))
			return;

		var view = client.getTopLevelWorldView();
		if (view == null)
			return;
		var sceneTile = view.getSelectedSceneTile();
		if (sceneTile == null)
			return;

		if (!offersWalkHere(event.getMenuEntries()))
			return;

		var target = sceneTile.getWorldLocation();
		addFollowerMenuEntry(1, "Send", () -> sendTo(target));
	}

	/**
	 * Whether a menu is the game scene's: the game offers Walk here only over
	 * the 3D view. The follower's clickbox is a projection, and it goes on
	 * under the bank, the inventory or the chatbox drawn over it. QA, 15 Oct:
	 * read through them, Talk-to took the left-click from a bank item, the
	 * follower's options sat above the item's own, and shift-click drop was
	 * swallowed. Send has always asked this; the hover entry, the right-click
	 * options and shift-click ask it too.
	 */
	static boolean offersWalkHere(MenuEntry[] entries)
	{
		return entries != null && Arrays.stream(entries)
			.anyMatch(entry -> entry != null && entry.getType() == MenuAction.WALK);
	}

	/** The Send op's move, on the client thread. */
	void sendTo(WorldPoint target)
	{
		// Through the will, which stops at an edge it has misgivings
		// about before going on.
		boolean sent = will.send(target);
		if (!sent)
			speak("I can't get to that spot from here.",
				SpeechOutput.OVERHEAD, null, -1, null);
		else
			// Where the Send ends, not where it pauses: let go by something
			// that had the feet, the pause is over, and the follower parked on
			// the edge stayed three tiles short of a monster for good.
			parkedByThePlayer(will.endsAt());
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
	void onClientTick(net.runelite.api.events.ClientTick event)
	{
		gaze.noteHover(Gaze.read(client, follower));

		boolean over = !client.isMenuOpen() && follower.isSpawned()
			&& follower.isUnderMouse(client.getMouseCanvasPosition())
			&& offersWalkHere(client.getMenu().getMenuEntries());

		// Latched for the game tick to read. The clickbox is already projected
		// here to draw the hover hint, so knowing the mouse is resting on the
		// follower costs nothing beyond this line.
		hoveredThisTick |= over;

		// Over a booth, a tree or an NPC the left-click is theirs: the entry
		// goes on top of the menu, and the follower walks at the heel, so it
		// would take that click all day.
		if (!over || !onlyGroundUnder(client.getMenu().getMenuEntries()))
			return;

		addFollowerMenuEntry(talkOption(), this::startTalking);
	}

	/**
	 * Whether nothing but bare ground is under the mouse: Walk here, Cancel
	 * and plugins' own entries, and no NPC, object, player or item op. QA,
	 * 15 Oct: the hover Talk-to took the left-click from a bank booth, a tree
	 * and an NPC to attack, the annoyance the game's own "Move follower
	 * options lower down" setting exists for with real pets.
	 */
	static boolean onlyGroundUnder(MenuEntry[] entries)
	{
		return entries != null && Arrays.stream(entries)
			.allMatch(entry -> entry == null || GROUND_OPS.contains(entry.getType()));
	}

	/** Walk here, Cancel and the plugins' own entries: what bare ground offers. */
	static final Set<MenuAction> GROUND_OPS = java.util.EnumSet.of(
		MenuAction.WALK, MenuAction.CANCEL, MenuAction.RUNELITE);

	/**
	 * Shift-hover: the same little action tooltip a real NPC gets, drawn by
	 * RuneLite's own tooltip component so the styling is identical. Added
	 * every frame, because the tooltips are cleared after every frame drawn;
	 * added once a client tick it was missing from every frame past fifty a
	 * second, and flickered (play test, 8 Oct).
	 */
	void shiftHoverTooltip()
	{
		if (client.isKeyPressed(KeyCode.KC_SHIFT) && !client.isMenuOpen()
			&& follower.isSpawned() && follower.isUnderMouse(client.getMouseCanvasPosition())
			&& offersWalkHere(client.getMenu().getMenuEntries()))
			tooltipManager.add(new net.runelite.client.ui.overlay.tooltip.Tooltip(
				talkOption() + " <col=ffff00>" + followerName() + "</col>"));
	}

	void addFollowerMenuEntry(String option, Runnable action)
	{
		addFollowerMenuEntry(-1, option, action);
	}

	/**
	 * An entry on the follower at {@code index}: -1 on top, 1 just above
	 * Cancel. Menu clicks come on the client thread, where the action then
	 * runs straight away.
	 */
	void addFollowerMenuEntry(int index, String option, Runnable action)
	{
		client.getMenu().createMenuEntry(index)
			.setOption(option)
			.setTarget(followerTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e ->
			{
				// An op on an entity flashes the red click cross, like any NPC.
				showRedCross();
				clientThread.invoke(action::run);
			});
	}

	// ------------------------------------------------------------------ ticks

	@Subscribe
	void onGameTick(GameTick event)
	{
		var local = client.getLocalPlayer();
		if (local == null)
			return;
		if (pendingMime != 0)
		{
			// Arrived beside the work: now the copying reads as joining in.
			if (!skilling.joiningIn())
				pendingMime = 0;
			else if (!pendingOneShot && local.getAnimation() != pendingMime)
				// Stopped while it walked over, and no animation event said so.
				stopPoseMirror();
			else if (skilling.joinedIn())
			{
				int copy = pendingMime;
				pendingMime = 0;
				copyAtWork = true;
				if (pendingOneShot)
					clientThread.invoke(() -> follower.playAnimation(copy));
				else
					startPoseMirror(copy);
			}
		}

		// The dialog's tick gate: queued clicks (open, continue, option,
		// dismiss) resolve here, one tick after the input - the same beat a
		// real dialog takes for its server round trip.
		dialog.tick();

		// Watchdog: a slaved chain waits on the player's next stage; if that
		// never arrives (desync, logout race), release the follower rather than
		// hold the last frame forever. Stages arrive at most ~4s apart. A cast
		// never seen to finish keeps the follower rather than vanishing it.
		if (follower.isSlavedChainActive()
			&& client.getTickCount() - follower.getSlavedAdvanceTick() > 25)
			follower.cancelEmote();


		noticeTheScene();
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
				follower.despawn();
		}
		else if (appearanceService.partsArrived())
			// A model the client has never drawn is downloaded on the first ask,
			// and the look built meanwhile goes without it: built again once it
			// lands, ahead of the bare respawn below, which would bring back the
			// unfinished one. Public QA, 27 Oct: a new shirt and legs on a fresh
			// install left the follower without a torso or legs for a while.
			rebuildFollower();
		else if (!follower.isSpawned() && appearanceService.getCurrent() != null)
			// Back on its post if it was left waiting, as the rebuild does.
			spawnFollower(appearanceService.getCurrent(), local, false);

		updateTrail(local);
		// Where the follower stands, for the addressed condition.
		context().setFollowerLocation(follower.getWorldLocation());

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
		// LEARNING_SAVE_TICKS: How often learned animation data is flushed to disk: one minute, so an
		// unclean exit costs at most that much observation rather than a session.
		if (++ticksSinceLearningSave >= 100 /* LEARNING_SAVE_TICKS */)
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
		context().setHoverTicks(hoverTicks);

		updateStrandedTeleport();

		// LAST_SEEN_TICKS: Ticks between writing the last-seen stamp: about a minute.
		if (++ticksSinceLastSeen >= 100 /* LAST_SEEN_TICKS */)
		{
			ticksSinceLastSeen = 0;
			touchLastSeen();
			// Ride the same timer: the counters are worth exactly as much as
			// the last-seen stamp on a crash, and this is already the "write
			// what would otherwise be lost" tick.
			writeCountersIfChanged();

			// A hundred ticks is a minute, so the timer that saves is also the
			// one that measures how long today has run. Stored every minute so
			// a crash still keeps the mark; said only on the minute it crosses,
			// because a record that re-announces every minute for the rest of a
			// long session stops being a compliment and becomes a clock.
			var context = context();
			int previousBest = context.getRecord("session");
			context.setSessionMinutes(++sessionMinutes);
			if (context.noteRecord("session", sessionMinutes) && !sessionRecordSaid)
			{
				sessionRecordSaid = true;
				dispatch(
					TriggerEvent.record("session", sessionMinutes, previousBest));
			}
		}

		if (ticksSinceLoading < 1000)
			ticksSinceLoading++;
		// A tick means the login's offer replay and any channel roster replay
		// have been delivered; what arrives from here on is news.
		geOffersSettled = true;
		if (channelSettleTicks > 0)
			channelSettleTicks--;

		// A limbo whose thrall never respawns - it expired during the very
		// load that dropped it - ends the possession after a grace period.
		if (thrallLimbo && ++thrallLimboTicks > 15)
			exitThrallMode();

		// No resummon arrived in the grace window, so the thrall really did
		// expire: say goodbye and go home.
		if (thrallExitPendingTicks > 0 && --thrallExitPendingTicks == 0)
			performThrallExit(thrallExitStyle);

		if (namePromptDelayTicks > 0 && --namePromptDelayTicks == 0
			&& follower.isSpawned())
		{
			// A chatbox input dies to almost any other interface, and ten
			// seconds after first spawn is exactly when a new user is still
			// clicking around. The one naming moment is not spent while the
			// bank or another input is up - the countdown re-arms instead.
			if (client.getWidget(InterfaceID.BANKMAIN, 0) != null
				|| chatboxPanelManager.getCurrentInput() != null)
				namePromptDelayTicks = NAME_PROMPT_DELAY_TICKS;
			else
				offerName();
		}

		drainSpeechQueue();

		commitAttackSighting();
		expireKillClaims();
		updateWander();
		gaze.tick();
		follower.setFollowDistance(config.followDistance());
		// The shoulder is for open ground and nothing else going on: not a
		// fight, not a pocket being worked, not thrall work.
		follower.setWalksBeside(config.walksBeside() && thrallNpc == null
			&& !context().isInCombat()
			&& !context().isInThievingSession()
			&& !skilling.isWorking());
		updateMinutes();
		updateWelfare();
		updateRest();

		errands.tick();
		flourish.tick();
		// Before the spectator, which beats it: on the tick a fight is seen
		// the seat is let go first and the stand-clear takes the feet after.
		skilling.tick();
		skilling.describeTo(context());
		// Picking by hand at a patch: the hands emptied of its own gear.
		refreshEmoteDisarm(follower.isEmotePlaying());
		// A copy of the work lasts while the follower is beside it: moved
		// off it, or the work over, and the copy stops there and then,
		// rather than carrying on wherever it was taken. A one-shot is
		// over when its swing is, and back to the seat it goes.
		if (copyAtWork && (!skilling.stillJoined()
			|| (mirroredPose == 0 && !follower.isEmotePlaying())))
			stopPoseMirror();
		// After the seat, which it excuses itself from.
		moments.tick();
		// After the moments, whose feet it borrows for the walk.
		noticing.tick();
		will.tick();

		// After errands, so an errand in progress owns the feet: a follower
		// halfway to a bank should finish the trip rather than be yanked
		// sideways because something attacked. Thrall mode is in the fight, so
		// it is excluded too - and so is the whole EXIT from it: thrallNpc
		// goes null ticks before the goodbye flourish and the snap home have
		// finished with the feet, and a spectate started in that window had
		// its seat wiped by the exit machinery. Play report: fresh out of
		// thrall mode, wards at the player's side, no stepping away.
		spectate.tick(spectateBusy(), parkedByPlayer);
		// After everything that borrows the feet, so a park the player gave
		// is taken again on the tick they are let go.
		keepThePlayersPark();
		rememberThePark();
		noticeTheLogoutTab();

		// The spawn-in burst waits one tick so the follower has already been
		// rendered standing at the thrall's spot when it plays.
		if (pendingThrallSpawnFxTicks > 0 && --pendingThrallSpawnFxTicks == 0 && thrallNpc != null)
			clientThread.invoke(() ->
				follower.playSpotAnim(spotAnimRepository.get(thrallImpactSpotAnim(thrallStyle))));

		// Stage two of the exit flourish: home, redressed, shimmering back in.
		// In thrall-only mode there is no home to go to: the phase-out was
		// the whole exit, and the follower is simply gone until the next
		// summon.
		if (thrallExitTicks > 0 && --thrallExitTicks == 0)
		{
			outfitOverride = null;
			if (config.thrallOnly())
			{
				clientThread.invoke(() ->
				{
					follower.releaseNpcSlave();
					follower.despawn();
				});
			}
			else
			{
				clientThread.invoke(() ->
				{
					follower.resumeFollowing();
					follower.releaseNpcSlave();
					rebuildFollower();
					follower.playSpotAnim(spotAnimRepository.get(1290 /* THRALL_RETURN_SPOTANIM */));
				});
			}
		}

		if (++reloadPollTicks >= 2)
		{
			reloadPollTicks = 0;
			if (dialogLoader.reloadIfChanged())
			{
				sendStatus("Reloaded " + dialogLoader.getStatus() + ".");
				for (String problem : dialogLoader.getErrors())
					sendStatus("Problem in dialogs.json: " + problem);
			}
			if (ruleLoader.reloadIfChanged())
			{
				// The rule holding the floor is one of the objects just thrown
				// away, and the exemption that lets it speak is by identity.
				speechEngine.clearFloor();
				giveTheNames();
				sendStatus("Reloaded " + ruleLoader.getStatus() + ".");
				reportRuleErrors();

				// In-world, phrases.json IS the scribe's notebook, so an edit
				// arriving from outside is somebody writing in it. Panel saves
				// count too - that is also the player in its book. The rules
				// carry long cooldowns so an editing session earns one remark,
				// not a heckle per save.
				//
				// A reload resets every rule's edge state, so the gear you are
				// wearing and the place you are standing would read as fresh
				// rising edges on the next tick. Same cure as login: baseline
				// first, react to actual changes after.
				speechEngine.primeEdgesOnNextTick();

				// And the remark waits for that baseline, or the re-risen gear
				// and area rules outrank it and speak in its place.
				speechEngine.dispatchAfterPriming(
					TriggerEvent.simple(NOTES_EDITED));
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
		boolean thievingNow = context().isInThievingSession();
		if (thievingNow != wasThieving)
		{
			speechEngine.setMuted(config.muted() || thrallQuiet());
			if (thievingNow)
			{
				// Anything queued from before belongs to the moment before the
				// silence; letting it trickle out during it is exactly the
				// chatter this is meant to stop.
				speechQueue.clear();
				cutByTheScene = null;
			}
			dispatch(TriggerEvent.simple(thievingNow
				? THIEVING_START
				: THIEVING_END));
			wasThieving = thievingNow;
		}
		// Absent in thrall-only mode is absent: nothing said, and nothing kept
		// back to be said the moment a thrall appears.
		boolean absentNow = absentUntilSummoned(config.thrallOnly(), thrallBusiness());
		if (absentNow && !wasAbsent)
		{
			speechQueue.clear();
			cutByTheScene = null;
		}
		wasAbsent = absentNow;
		speechEngine.setMuted(config.muted() || thievingNow || absentNow || thrallQuiet());

		// The bank has no close event of its own; watched by widget presence.
		// Closing it is an ending (R6) - the restock is done, the breather is
		// real - and it is the one boundary no dispatched event can supply.
		boolean bankOpen = client.getWidget(
			InterfaceID.BANKMAIN, 0) != null;
		if (wasBankOpen && !bankOpen)
			context().noteBoundary("bank");
		wasBankOpen = bankOpen;

		// The local song: the music tab's now-playing text is script-updated
		// on every track change whether or not the tab is fronted. The first
		// read after a login is baseline, not news, same as the region you
		// logged in standing.
		var nowPlaying = client.getWidget(
			InterfaceID.Music.NOW_PLAYING_TEXT);
		String track = nowPlaying == null || nowPlaying.getText() == null
			? null : nowPlaying.getText().replaceAll("<[^>]*>", "").trim();
		if (!Strings.isNullOrEmpty(track))
		{
			if (lastMusicTrack != null && !track.equals(lastMusicTrack))
				dispatch(TriggerEvent.trackChange(track));
			lastMusicTrack = track;
		}

		// An NPC dialogue up, or gone for under three ticks: the same reading
		// the minutes take, so a page flip does not count as the box closing.
		context().setDialogueOpen(npcDialogOpen() || dialogGoneTicks <= 2);
		// And who is in it, so a line about them held through the talk is
		// still said once the player walks on from them. Not a cutscene's
		// speakers: nobody the player walked up to.
		var speaker = client.getWidget(
			InterfaceID.ChatLeft.NAME);
		if (speaker != null && speaker.getText() != null && !inCutscene())
			context().noteSpeaker(
				net.runelite.client.util.Text.removeTags(speaker.getText()));

		// A player-commanded Stay (R25): the follower's own machinery also
		// parks it - errands, spectating, thrall work, and the WANDER, which
		// drifts via the same stayAt - and only this side can tell the
		// difference, so the context is fed the distinction. The wander was
		// missed at first, and the transcript showed the follower taking
		// the post thirty times in an afternoon of its own pottering.
		boolean stayCommanded = stayIsPlayerCommanded(follower.isStaying(),
			wandered, thrallNpc != null,
			errands.isBusy(),
			spectate.holdsTheFeet(),
			emoteHold, minutesState != 0,
			skilling.holdsTheFeet()
				|| moments.holdsTheFeet()
				// A write-up plants the follower for the length of the notes.
				|| flourish.isActive()
				// A scene holds it where it stands.
				|| sceneHolds());
		context().setFollowerStaying(stayCommanded);
		if (stayCommanded != stayReported)
		{
			stayReported = stayCommanded;
			if (onStayStateChanged != null)
				onStayStateChanged.accept(stayCommanded);
		}

		// An absence ending is a boundary too: the player went out of sight
		// of the parked follower and has now come back to it.
		if (stayCommanded)
		{
			WorldPoint parked = follower.getWorldLocation(),
				player = local.getWorldLocation();
			int apart = parked == null || player == null
				|| parked.getPlane() != player.getPlane()
				? Integer.MAX_VALUE : parked.distanceTo(player);
			if (apart > 12)
				leftTheFollowerParked = true;
			else if (leftTheFollowerParked && apart <= 3)
			{
				leftTheFollowerParked = false;
				context().noteBoundary("reunion");
			}
		}
		else
			leftTheFollowerParked = false;

		int region = context().getRegionId();
		if (region != lastRegionId)
		{
			int previous = lastRegionId;
			lastRegionId = region;
			if (panel != null)
				panel.setRegionId(region);
			if (previous != -1)
			{
				dispatch(TriggerEvent.regionChange(region, previous));
				// Somewhere new: once the player settles, the follower may go
				// and nose at something (the explore errand's arrival trigger).
				errands.noticeArrival();
			}
		}

		dispatch(TriggerEvent.tick());

	}

	void updateTrail(Player local)
	{
		// The follower observes the player's movement itself, per frame - this tick
		// hook only tracks the tile for region-change speech triggers.
		var tile = local.getWorldLocation();
		if (tile != null)
		{
			// Walking away ends a conversation, as it does with any real NPC.
			if (dialog.isOpen() && lastPlayerTile != null && !lastPlayerTile.equals(tile))
				dialog.close();
			lastPlayerTile = tile;
		}
	}

	/**
	 * Copies the head angles and layout cells off a real dialog the moment one
	 * opens, keeping the follower's dialog calibrated against whatever the game
	 * currently renders. The measuring dumps that produced the baked-in layout
	 * constants have been retired; only the silent adoption remains.
	 */
	/**
	 * The logout tab, opened: the last thing on screen before the session
	 * ends, so a turn to face them and a wave, the rare line to go with it,
	 * and a last look at the quests, so one finished just now is dated before
	 * the session ends. The panel is loaded once, at login, and a tab press
	 * only shows it, so the load never came with the press (play test, 29
	 * Sep): it is watched once a tick instead, as the panel coming into view.
	 */
	void noticeTheLogoutTab()
	{
		var panel =
			client.getWidget(InterfaceID.LOGOUT, 0);
		boolean open = panel != null && !panel.isHidden(),
			opened = open && !logoutTabOpen;
		logoutTabOpen = open;
		logoutTabTicks = ticksOnShow(logoutTabTicks, open, opened);
		if (logoutTabTicks == FAREWELL_AFTER_TICKS)
		{
			moments.farewell();
			dispatch(TriggerEvent.simple(FAREWELL));
		}
	}

	/**
	 * Whether the logout tab was on show at the last look. True between
	 * sessions, so a tab still up as one starts is not taken for a goodbye.
	 */
	boolean logoutTabOpen = true;

	/** Ticks the logout panel has been on show since it was opened, or 0. */
	int logoutTabTicks;

	/**
	 * The goodbye waits a tick with the panel still up. Behaviour QA5, 16-20
	 * Oct: all four farewells came as the panel opened on the way to the
	 * World Switcher, 20 s before a hop, and none before a logout. Going on
	 * to the switcher takes the panel down, and the goodbye with it.
	 */
	static final int FAREWELL_AFTER_TICKS = 2;

	static int ticksOnShow(int was, boolean open, boolean opened)
	{
		return !open ? 0 : opened ? 1 : was > 0 ? was + 1 : 0;
	}

	@Subscribe
	void onWidgetLoaded(net.runelite.api.events.WidgetLoaded event)
	{
		int group = event.getGroupId();

		// The quest reward scroll - the one moment the game hands the player
		// a literal scroll, witnessed by a professional.
		if (group == InterfaceID.QUESTSCROLL)
		{
			noteQuestScroll();
			return;
		}

		if (group != InterfaceID.CHAT_LEFT
			&& group != InterfaceID.CHAT_RIGHT)
			return;

		// The children are populated after the load event; read them next cycle.
		clientThread.invokeLater(() ->
		{
			for (int child = 0; child < 16; child++)
			{
				var widget = client.getWidget(group, child);
				if (widget == null
					|| widget.getType() != net.runelite.api.widgets.WidgetType.MODEL)
					continue;

				var headRect = widget.getBounds();
				// Relative to the CHATBOX widget - the same base the dialog overlay
				// positions against - not the dialog root, which sits inset from it.
				var root = client.getWidget(
					InterfaceID.CHATBOX, 0);
				var rootRect = root == null ? null : root.getBounds();

				// Copy the real head's rectangle, expressed relative to the dialog,
				// so ours sits at the same place and size instead of a guess. The
				// group is one of the two chat sides: the NPC's on the left.
				boolean npcSide = group == InterfaceID.CHAT_LEFT;
				if (headRect != null && rootRect != null
					&& headRect.width > 0 && headRect.height > 0)
				{
					var cell = new Rectangle(
						headRect.x - rootRect.x, headRect.y - rootRect.y,
						headRect.width, headRect.height);
					if (!npcSide)
						// The player side's right-hand head cell, kept calibrated.
						dialog.setPlayerHeadRect(cell);
					else
					{
						dialog.setHeadRect(cell);

						// The dialog interface's inset within the chatbox is the model
						// clip line (the inner parchment edge).
						var dialogRoot = client.getWidget(group, 0);
						if (dialogRoot != null && dialogRoot.getBounds() != null)
						{
							dialog.setDialogInsetX(max(0, dialogRoot.getBounds().x - rootRect.x));
							dialog.setDialogInsetY(max(0, dialogRoot.getBounds().y - rootRect.y));
						}
					}
				}
				// Our dialog lays the head out like the NPC side (left), so adopt a
				// human NPC head's framing; rotationZ is the head's actual angle.
				if (npcSide)
					dialog.adoptNpcHead(widget.getAnimationId(), widget.getRotationZ(),
						widget.getRotationX(), widget.getModelZoom());
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
	void onInteractingChanged(net.runelite.api.events.InteractingChanged event)
	{
		if (dialog.isOpen()
			&& event.getSource() == client.getLocalPlayer()
			&& event.getTarget() != null)
			dialog.close();
	}

	// ----------------------------------------------------------- game events

	@Subscribe
	void onNpcSpawned(NpcSpawned event)
	{
		var npc = event.getNpc();
		// Not written up here: a spawn is anything loaded within about 15
		// tiles, through walls and floors. See noteCreaturesInSight.
		maybeAdoptThrall(npc);
		skilling.npcSpawned(npc);
		if (config.noticesThings())
			moments.npcSpawned(npc);

		// A chunk load raises one of these for every NPC in the new scene, and
		// each costs a pass over every rule. Only pay it when a rule names it.
		var spawn = TriggerEvent.npc(NPC_SPAWN, npc.getId(), npc.getName());
		if (ruleLoader.answersSpawn(spawn))
			dispatch(spawn);
	}

	@Subscribe
	void onNpcDespawned(NpcDespawned event)
	{
		var npc = event.getNpc();
		if (npc == thrallNpc)
		{
			// NpcDespawned also fires for every NPC a chunk reload drops from
			// the scene. Classify by WHEN: a despawn during (or just after) a
			// LOADING state is the scene shuffling, not the thrall dying.
			if (client.getGameState() != GameState.LOGGED_IN || ticksSinceLoading <= 2)
			{
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
				thrallExitStyle = thrallStyle;
				thrallExitPendingTicks = 3;
				thrallNpc = null;
				clientThread.invoke(follower::endNpcSlaveHolding);
			}
		}
		skilling.npcDespawned(npc);
		moments.npcDespawned(npc);
		// As with spawns: the whole scene despawns on a chunk load, and no
		// bundled rule listens for it at all.
		if (ruleLoader.listensFor(NPC_DESPAWN))
			dispatch(
				TriggerEvent.npc(NPC_DESPAWN, npc.getId(), npc.getName()));
	}

	@Subscribe
	void onGameObjectSpawned(net.runelite.api.events.GameObjectSpawned event)
	{
		noteObjectChange(event.getGameObject(), true);
	}

	@Subscribe
	void onGameObjectDespawned(net.runelite.api.events.GameObjectDespawned event)
	{
		noteObjectChange(event.getGameObject(), false);
	}

	/**
	 * A ground item appeared. Only a session cares, and only about a nest at
	 * the player's feet; the name is looked up on the client thread, where
	 * the event arrives.
	 */
	@Subscribe
	void onItemSpawned(net.runelite.api.events.ItemSpawned event)
	{
		if (event.getItem() == null || event.getTile() == null)
			return;
		// A chunk load raises one of these per item already lying about;
		// none of those is a drop the player just walked away from.
		if (ticksSinceLoading > 2)
		{
			int id = event.getItem().getId();
			noticing.itemSpawned(event.getItem(), event.getTile().getWorldLocation(),
				itemManager.getItemComposition(id).getName(),
				(long) itemManager.getItemPrice(id) * event.getItem().getQuantity());
		}
		if (!skilling.isWorking())
			return;
		skilling.itemSpawned(itemManager.getItemComposition(event.getItem().getId()).getName(),
			event.getTile().getWorldLocation());
	}

	@Subscribe
	void onItemDespawned(net.runelite.api.events.ItemDespawned event)
	{
		if (event.getItem() != null && event.getTile() != null)
			noticing.itemDespawned(event.getItem(), event.getTile().getWorldLocation());
	}

	/**
	 * Scenery coming and going, handed to the skilling company by footprint.
	 * A chunk load raises one of these per object in the scene, so a despawn
	 * is only handed over during a session, and a spawn only while the
	 * company keeps company at all - it wants the trap the player just laid
	 * and the fire they just lit, session or none - and the company itself
	 * drops everything a load shuffles and keeps only what touches its tiles.
	 */
	void noteObjectChange(GameObject object, boolean spawned)
	{
		if (object == null)
			return;
		// The fires, first and ungated: a chunk load raises one of these per
		// object in the scene, and that IS how the set is built - the tiles
		// the follower walks round have to be known before it takes a step,
		// whatever the company settings say.
		var box = footprint(object, object.getPlane());
		if (box != null)
		{
			if (spawned)
				hotGround.objectSpawned(object.getId(), box[0], box[1]);
			else
				hotGround.objectDespawned(object.getId(), box[0], box[1]);
		}

		// A star landing. Names are only looked up outside a chunk load,
		// which raises one of these per object in the scene.
		if (ticksSinceLoading > 2)
		{
			String name = ErrandController.objectName(client, object.getId());
			if (spawned)
				noticing.objectSpawned(name, object.getWorldLocation());
			else
				noticing.objectDespawned(name, object.getWorldLocation());
		}
		if (box == null || (spawned ? !config.keepsCompany() : !skilling.isWorking()))
			return;
		if (spawned)
			skilling.objectSpawned(object.getId(), box[0], box[1]);
		else
			skilling.objectDespawned(object.getId(), box[0], box[1]);
	}

	/** A scenery object's footprint as world tiles, {min, max}, or null off the scene. */
	WorldPoint[] footprint(GameObject object, int plane)
	{
		var view = client.getTopLevelWorldView();
		Point lo = object.getSceneMinLocation(),
			hi = object.getSceneMaxLocation();
		return view == null || lo == null || hi == null ? null : new WorldPoint[]{
			WorldPoint.fromScene(view, lo.getX(), lo.getY(), plane),
			WorldPoint.fromScene(view, hi.getX(), hi.getY(), plane)};
	}

	@Subscribe
	void onChatMessage(ChatMessage event)
	{
		// The follower's own lines land in public chat under its name; feeding
		// them back into the rules would let it react to itself.
		if (followerName().equals(event.getName()))
			return;
		// Nor its status lines, which are CONSOLE lines, as are the echoes
		// below: with chatwatch on, each echo was itself echoed, endlessly.
		// The game says nothing a rule listens for as CONSOLE; that is
		// RuneLite's and the plugins' own channel.
		if (event.getType() == ChatMessageType.CONSOLE)
			return;
		// Nor the game's word for a death there: the death rule listens for it.
		// DEATH_MESSAGE: What the game says at a death, which the death rule answers.
		if (!(context().isSafeDeathHere() && event.getMessage().startsWith("Oh dear, you are dead" /* DEATH_MESSAGE */)))
			dispatch(TriggerEvent.chat(
				event.getMessage(), event.getType().getType(), event.getName()));

		if (skilling != null)
			// The stun, the empty rune pouch, the slayer task: silent reactions,
			// and none in a take, where they would turn and gesture over the pose.
			skilling.noteChatMessage(event.getMessage(), event.getType());
		if (config.noticesThings())
			// The pet drop: the walk over to see it.
			moments.noteChatMessage(event.getMessage(), event.getType());

		// Two game messages worth parsing rather than merely matching: the
		// tail is a NAME a line can say, which a plain chatMessage rule can
		// see but never extract. The wordings are the ones the client's own
		// plugins match (crowdsourcing music, screenshot), not from memory.
		if (event.getType() == ChatMessageType.GAMEMESSAGE)
		{
			String parsed = parseTailAfter(event.getMessage(),
				"You have unlocked a new music track:");
			if (parsed != null)
			{
				context().tally("music");
				dispatch(TriggerEvent.musicUnlock(parsed));
			}
			parsed = parseTailAfter(event.getMessage(),
				"New item added to your collection log:");
			if (parsed != null)
			{
				context().tally("collections");
				dispatch(TriggerEvent.collectionAdd(parsed));
				if (config.noticesThings())
					// A first-ever thing is news whatever it sells for; it is
					// in the bag, so the write-up faces the player.
					moments.noteworthy(Moments.Tier.MEDIUM, null);
			}
			noteKillCount(event.getMessage());
		}
	}

	/**
	 * The game's own count after a boss: "Your Vorkath kill count is: 41.",
	 * "Your completed Chambers of Xeric count is: 5.", "Your Barrows chest
	 * count is: 12." The account's number rather than the follower's tally,
	 * which is what lets it say "your forty-first" on the day they met.
	 */
	static final Pattern KILL_COUNT = Pattern.compile(
		"^Your (?:completed |subdued )?(.+?)(?: kill| chest| completion| success)? count is:? ?([\\d,]+)");

	/** The boss and the number out of a kill-count message, or null for any other message. */
	static Entry<String, Integer> parseKillCount(String message)
	{
		if (message == null)
			return null;
		var m = KILL_COUNT.matcher(message.replaceAll("<[^>]*>", "").trim());
		if (!m.find())
			return null;
		String boss = m.group(1).trim();
		// The agility courses and the herbiboar are counted in the same words,
		// "Your Varrock Rooftop lap count is: 25.", and a lap is not a kill:
		// the boss lines made it "That makes 25 Varrock Rooftop lap."
		if (boss.endsWith(" lap") || boss.endsWith(" harvest") || boss.endsWith(" Total Ticket"))
			return null;
		try
		{
			return new AbstractMap.SimpleImmutableEntry<>(
				boss, Integer.parseInt(m.group(2).replace(",", "")));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	void noteKillCount(String message)
	{
		var parsed = parseKillCount(message);
		if (parsed == null)
			return;
		String boss = parsed.getKey();
		int count = parsed.getValue();
		var context = context();
		// A record rather than a tally: the game's count only ever goes up,
		// and what the follower keeps is the highest it has heard. The game
		// keeps it per account, so the follower does too.
		String bare = "kc:" + boss.toLowerCase(Locale.ROOT),
			key = accountKey(bare);
		int last = context.getRecord(key);
		context.noteRecord(key, count);
		context.noteKillCountName(boss);
		// The count kept before counts were per account was this account's
		// once this one has reached it, and it has done its job: kept, it
		// would stand in for an alt that never killed the boss, for good.
		if (!key.equals(bare) && count >= context.getRecord(bare))
			context.forgetRecord(bare);
		dispatch(TriggerEvent.killCount(boss, count, last));
	}

	/**
	 * The text after a known game-message prefix, stripped of colour tags and
	 * trailing punctuation; null when the message is not that one. The game's
	 * own @tag@ colours too: every music unlock came as "@mes_hl_red@Expanse",
	 * and was said that way, tag and all.
	 */
	static String parseTailAfter(String message, String prefix)
	{
		if (message == null)
			return null;
		String plain = message.replaceAll("<[^>]*>|@[a-z0-9_]+@", "").trim();
		if (!plain.startsWith(prefix))
			return null;
		String tail = plain.substring(prefix.length()).replaceAll("[.!]+$", "").trim();
		return tail.isEmpty() ? null : tail;
	}

	@Subscribe
	void onVarbitChanged(VarbitChanged event)
	{
		// A scene's first tick is read on the game tick, after this tick's
		// messages: quiet from the varbit itself, so a line fired alongside
		// it is held. noticeTheScene still owns the edges.
		if (event.getVarbitId() == VarbitID.CUTSCENE_STATUS
			&& event.getValue() != 0)
			speechEngine.setSceneQuiet(true);

		// Only pay the full rule pass when something actually listens for
		// varbit EVENTS - varbit floods (login inits) are otherwise free.
		// State-based varbitEquals rules evaluate on the tick heartbeat.
		if (event.getVarbitId() != -1 && ruleLoader.isVarbitEventRules())
			dispatch(TriggerEvent.varbit(event.getVarbitId(), event.getValue(), -1));

		// The summoning-cooldown varbit rising is the RELIABLE summon signal:
		// casting mid combat never surfaces the cast animation (the
		// attack/block cycle owns the animation slot), but this rises
		// regardless. Its FALL is just the ten-second cooldown ending and
		// means nothing for the thrall's life.
		if (event.getVarbitId() == THRALL_SUMMONED_VARBIT
			&& replacesThralls(config.thrallMode(), config.thrallOnly())
			&& event.getValue() == 1)
			// The thrall NPC may already be in the scene - spawn-event
			// ordering within the tick is not guaranteed.
			adoptExistingThrall();

		// The brightness setting: the chathead's colour table must be on the same
		// gamma as the real chatheads around it, and must follow slider changes.
		if (event.getVarpId() == BRIGHTNESS_VARP)
			GameColourTable.setBrightnessSetting(event.getValue());
	}

	/**
	 * Reads the quest name off the reward scroll's title ("You have completed
	 * The Restless Ghost!"). The children are populated after the load event,
	 * so it is read next cycle, like the chathead calibration.
	 */
	void noteQuestScroll()
	{
		clientThread.invokeLater(() ->
		{
			var title = client.getWidget(
				InterfaceID.Questscroll.QUEST_TITLE);
			if (title == null || title.getText() == null)
				return;
			String text = title.getText().replaceAll("<[^>]*>", "").trim(),
				prefix = "You have completed ";
			if (!text.startsWith(prefix))
				// Speedruns and oddities use their own scrolls; a completion
				// this could not read is better silent than misquoted.
				return;
			String quest = text.substring(prefix.length())
				.replaceAll("[.!]+$", "").trim();
			if (quest.isEmpty())
				return;
			context().tally("quests");
			context().noteDoing("finishing " + quest);
			noteQuestFinish(quest, isStaying() ? QUEST_NOT_SEEN : LocalDate.now().toEpochDay());
			dispatch(TriggerEvent.questComplete(quest));
		});
	}
	
	/** Recorded for a quest finished while the follower was parked elsewhere. */
	static final long QUEST_NOT_SEEN = -1L;
	
	/** The quest the scroll names, by its constant, dated or marked missed for this account. */
	void noteQuestFinish(String questName, long epochDay)
	{
		for (var quest : Quest.values())
		{
			if (quest.getName().equalsIgnoreCase(questName))
			{
				context().noteQuestSeenFinished(accountKey(quest.name()), epochDay);
				return;
			}
		}
	}
	
	/** The key a quest's day, or a boss's kill count, is kept under: per account, where the memory is per profile. */
	String accountKey(String key)
	{
		String who = configManager.getRSProfileKey();
		return Strings.isNullOrEmpty(who) ? key : key + "@" + who;
	}

	/**
	 * Whether the Grand Exchange offer replay at login has passed. Logging in
	 * fires one offer-changed per slot for offers that were already sitting
	 * there; reacting to those would report last week's flips as news. Armed
	 * by the first game tick, cleared whenever the game state churns.
	 */
	boolean geOffersSettled;

	@Subscribe
	void onGrandExchangeOfferChanged(net.runelite.api.events.GrandExchangeOfferChanged event)
	{
		if (!geOffersSettled)
			return;
		var offer = event.getOffer();
		if (offer == null)
			return;
		boolean bought = offer.getState() == GrandExchangeOfferState.BOUGHT;
		if (!bought && offer.getState() != GrandExchangeOfferState.SOLD)
			return;
		// The clerk receives the telegram wherever you are - that is the joke.
		String item = itemManager.getItemComposition(offer.getItemId()).getName();
		dispatch(TriggerEvent.geOffer(bought, item, offer.getSpent()));
	}

	/**
	 * Ticks left of ignoring channel joins. Joining a channel replays a join
	 * for every member already in it - a roster, not an arrival - so the
	 * window after the channel itself changes is swallowed, the same
	 * settle-delay trick the NPC spawn flood uses.
	 */
	int channelSettleTicks;

	@Subscribe
	void onFriendsChatChanged(net.runelite.api.events.FriendsChatChanged event)
	{
		channelSettleTicks = 5;
	}

	@Subscribe
	void onClanChannelChanged(net.runelite.api.events.ClanChannelChanged event)
	{
		channelSettleTicks = 5;
	}

	@Subscribe
	void onFriendsChatMemberJoined(net.runelite.api.events.FriendsChatMemberJoined event)
	{
		dispatchChannel(CHANNEL_JOIN, event.getMember().getName());
	}

	@Subscribe
	void onFriendsChatMemberLeft(net.runelite.api.events.FriendsChatMemberLeft event)
	{
		dispatchChannel(CHANNEL_LEAVE, event.getMember().getName());
	}

	@Subscribe
	void onClanMemberJoined(net.runelite.api.events.ClanMemberJoined event)
	{
		dispatchChannel(CHANNEL_JOIN, event.getClanMember().getName());
	}

	@Subscribe
	void onClanMemberLeft(net.runelite.api.events.ClanMemberLeft event)
	{
		dispatchChannel(CHANNEL_LEAVE, event.getClanMember().getName());
	}

	void dispatchChannel(Type type, String who)
	{
		if (channelSettleTicks > 0 || who == null)
			return;
		// Display names arrive padded with non-breaking spaces.
		dispatch(TriggerEvent.channel(type, who.replace(' ', ' ').trim()));
	}

	/**
	 * NPC overheads never reach the chatbox, so no chat rule can see them -
	 * this is the only road to a scribe taking dictation from a town crier.
	 * Guarded like the spawn flood: busy scenes shout constantly, and the
	 * pass over every rule is only paid when a rule actually listens.
	 */
	@Subscribe
	void onOverheadTextChanged(net.runelite.api.events.OverheadTextChanged event)
	{
		if (!(event.getActor() instanceof NPC)
			|| !ruleLoader.listensFor(NPC_SHOUT))
			return;
		var npc = (NPC) event.getActor();
		// An NPC with no name of its own answers "null", and the follower
		// took dictation from it: "Noted, null. Volume down next time."
		if (npc.getName() == null || "null".equals(npc.getName())
			|| event.getOverheadText() == null)
			return;
		dispatch(TriggerEvent.npcShout(npc.getName(), event.getOverheadText()));
	}

	/**
	 * Which attack style the player has selected, as the game numbers them.
	 *
	 * <p>Deliberately not translated into stab, slash or crush: what a style
	 * number means depends on the weapon type, and the follower only ever
	 * needs the animation that goes with the style, never its name.
	 */
	int attackStyle()
	{
		return client.getVarpValue(net.runelite.api.gameval.VarPlayerID.COM_MODE);
	}

	/** The client's brightness setting (1 Dark .. 4 V.Bright, 2 = Normal). */
	static final int BRIGHTNESS_VARP = 166;

	/**
	 * The newest spotanim on an actor - the one a GraphicChanged event is
	 * reporting. Replaces the deprecated single-graphic accessors now that
	 * actors carry a list.
	 */
	static ActorSpotAnim latestSpotAnim(Actor actor)
	{
		ActorSpotAnim latest = null;
		for (var spotAnim : actor.getSpotAnims())
		{
			if (latest == null || spotAnim.getStartCycle() > latest.getStartCycle())
				latest = spotAnim;
		}
		return latest;
	}

	@Subscribe
	void onAnimationChanged(AnimationChanged event)
	{
		// Every player in the scene teaches their weapon's attack animation,
		// the same way they teach its walk and idle poses. The follower then
		// swings whatever it is actually holding.
		if (event.getActor() instanceof Player)
		{
			var animating = (Player) event.getActor();
			noteAttackSighting(animating);
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

		if (event.getActor() instanceof Player)
			// Any player's: the proof that an animation is safe on a player
			// skeleton is a player playing it.
			skilling.notePlayerAnimation(event.getActor().getAnimation());

		if (event.getActor() != client.getLocalPlayer())
			return;

		int animationId = event.getActor().getAnimation();

		// Proof that a work click turned into work, or that it goes on.
		skilling.noteAnimation(animationId);

		// A slaved chain (the home teleport) steps stage-for-stage with the
		// PLAYER's sequence: the server cuts each stage short by starting the
		// next, and mirroring that schedule is what removes the freeze between
		// stages. Checked before dispatch so the event that STARTS the chain
		// (the rule fires inside dispatch) cannot also advance it.
		if (follower.isSlavedChainActive())
		{
			if (animationId == -1)
				clientThread.invoke(follower::slavedPlayerStopped);
			else
				clientThread.invoke(follower::advanceSlavedChain);
		}

		// A held emote ends the moment the player's animation becomes anything
		// else, which is the only signal there is that they have stopped.
		if (mirroredPose != 0 && animationId != mirroredPose)
			stopPoseMirror();
		// Stopped before the follower got over there: no copy on arrival.
		else if (pendingMime != 0 && !pendingOneShot && animationId != pendingMime)
			stopPoseMirror();

		// The follower's tile as of this event, not the last tick's: an emote
		// done at it is judged on where it is.
		context().setFollowerLocation(follower.getWorldLocation());
		dispatch(TriggerEvent.animation(animationId));
	}

	/**
	 * Graphic mirroring rides on animation mirroring: when a mirror rule fires,
	 * any spotanim the player shows within this window is copied onto the
	 * follower. Windowed rather than read at fire time because the client can
	 * deliver the graphic on a later tick than the animation, and a multi-stage
	 * teleport (home teleport) trickles graphics in for several seconds.
	 */
	static final int MIRROR_GRAPHIC_WINDOW_TICKS = 12;
	int mirrorGraphicsUntilTick = -1;

	@Subscribe
	void onGraphicChanged(GraphicChanged event)
	{
		if (event.getActor() != client.getLocalPlayer())
			return;

		var spotAnim = latestSpotAnim(event.getActor());
		if (spotAnim == null)
			return;
		int graphicId = spotAnim.getId(),
			graphicHeight = spotAnim.getHeight();
		if (skilling != null)
			// The thieving stun's stars, which land before its message.
			skilling.noteGraphic(graphicId);
		if (takesMirroredGraphic(client.getTickCount()))
			mirrorGraphic(graphicId, graphicHeight);
	}

	/**
	 * Whether a graphic on the player now is the cast's to copy: within the
	 * window a copying rule opened, and only the first, so one cast copies
	 * one graphic.
	 */
	boolean takesMirroredGraphic(int tick)
	{
		if (tick > mirrorGraphicsUntilTick)
			return false;
		mirrorGraphicsUntilTick = -1;
		return true;
	}

	void mirrorGraphic(int graphicId, int height)
	{
		var fx = spotAnimRepository.get(graphicId);
		if (fx == null)
			return;
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
	final Map<Actor, Integer> damagedByPlayer = new HashMap<>();

	@Subscribe
	void onHitsplatApplied(HitsplatApplied event)
	{
		// Recorded before the size check, and for anybody: a block animation
		// plays on a zero as readily as on a forty, and it is the tell that an
		// animation was a defence rather than a swing.
		if (event.getActor() instanceof Player)
			playersHit.put(event.getActor(), client.getTickCount());

		if (event.getHitsplat().getAmount() <= 0)
			return;
		if (event.getActor() == client.getLocalPlayer())
		{
			// Being hit is combat even when not hitting back, which the
			// interaction check alone would miss.
			context().noteDamageTaken();
			dispatch(TriggerEvent.damageTaken(event.getHitsplat().getAmount()));
			if (CrossingWatch.isFallDamage(event.getHitsplat().getHitsplatType()))
				crossingWatch.noteDamage(currentTimeMillis());
			dialog.close();
		}
		else if (event.getActor() instanceof NPC && event.getHitsplat().isMine())
		{
			damagedByPlayer.put(event.getActor(), client.getTickCount());
			// Landing one is combat too. Facing an NPC is not enough on its
			// own any more, so this is what arms a fight the player starts.
			context().noteDamageDealt();
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
	void noteRecord(String what, int value)
	{
		var context = context();
		int previous = context.getRecord(what);
		if (context.noteRecord(what, value))
			dispatch(TriggerEvent.record(what, value, previous));
	}

	/** Drops damage claims that have gone stale, so the map cannot grow without bound. */
	/**
	 * Players hit recently, by tick. A player's block animation fires when
	 * they TAKE a hit, and nothing in the animation itself says which of the
	 * two it was.
	 */
	final Map<Actor, Integer> playersHit =
		new HashMap<>();

	/** The most recent animation seen on a player, held for one tick. */
	Player sightedPlayer;
	int sightedAnimation,
		sightedTick;

	/**
	 * Notes an animation that MIGHT be an attack worth learning.
	 *
	 * <p>Held rather than learned, because the two animations a fighting
	 * player produces are indistinguishable at this moment: swinging and being
	 * swung at both animate, and both leave {@code getInteracting} set. Taught
	 * on the spot, the library happily filed a shortbow's attack as the
	 * unarmed block, and the thrall then "shot" by flinching - play report,
	 * and true of the magic staff as well.
	 *
	 * <p>A hitsplat on the same player in the same tick is what tells them
	 * apart, and it can arrive either side of the animation, so the decision
	 * waits a tick for both to be in. Only the latest sighting is kept: an
	 * attack repeats every few ticks all fight long, so one missed is free,
	 * and a wrong one learned is not.
	 */
	void noteAttackSighting(Player animating)
	{
		if (animating.getAnimation() <= 0 || animating.getInteracting() == null)
			return;
		sightedPlayer = animating;
		sightedAnimation = animating.getAnimation();
		sightedTick = client.getTickCount();
	}

	/**
	 * Whether a sighted animation was a SWING, as against something else the
	 * player happens to be doing while an interaction is still set.
	 *
	 * <p>Interaction alone was the whole test, and interaction is sticky: it
	 * survives the fight that set it. Walk away from an NPC, start mining, and
	 * the pickaxe's 30-frame swing gets filed as that weapon's attack. It did -
	 * one mining animation ended up as the learned attack of six pickaxes and,
	 * because whatever was equipped at the time takes the blame, a shortbow.
	 * The follower then shot its bow by mining with it.
	 *
	 * <p>This is the lesson {@code TriggerContext} already learned and wrote
	 * down: "a fight has two sides or it has blows". For the local player the
	 * context can simply be asked. For anybody else there are no varps to read,
	 * so the evidence is their target wearing a health bar, which only happens
	 * in combat.
	 *
	 * @param targetHealthRatio the interaction target's, or -1 for no target
	 */
	static boolean sightingIsASwing(boolean local, boolean inCombat, int targetHealthRatio)
	{
		return local ? inCombat : targetHealthRatio > 0;
	}

	/** Commits a held sighting once the tick it belongs to is complete. */
	void commitAttackSighting()
	{
		if (sightedPlayer != null && sightedTick < client.getTickCount())
		{
			var hit = playersHit.get(sightedPlayer);
			var target = sightedPlayer.getInteracting();
			boolean swing = sightingIsASwing(
				sightedPlayer == client.getLocalPlayer(),
				context().isInCombat(),
				target == null ? -1 : target.getHealthRatio());
			if ((hit == null || hit < sightedTick) && swing)
				// Only the local player's selected style is knowable - varps
				// are theirs alone - so everyone else teaches the generic
				// swing and your own play teaches the styled ones.
				stanceLibrary.learnAttack(sightedPlayer, sightedAnimation,
					sightedPlayer == client.getLocalPlayer() ? attackStyle() : -1);
			sightedPlayer = null;
		}

		if (!playersHit.isEmpty())
		{
			int cutoff = client.getTickCount() - 3;
			playersHit.values().removeIf(tick -> tick < cutoff);
		}
	}

	void expireKillClaims()
	{
		if (damagedByPlayer.isEmpty())
			return;
		// KILL_CLAIM_TICKS: How long a hit stays claimable. Comfortably longer than any kill takes to land.
		int cutoff = client.getTickCount() - 30 /* KILL_CLAIM_TICKS */;
		damagedByPlayer.values().removeIf(tick -> tick < cutoff);
	}

	@Subscribe
	void onActorDeath(net.runelite.api.events.ActorDeath event)
	{
		var local = client.getLocalPlayer();
		if (local == null)
			return;

		if (event.getActor() != local)
		{
			// Someone else's death is only news if the player had a hand in it.
			var hit = damagedByPlayer.remove(event.getActor());
			if (hit != null && event.getActor() instanceof NPC)
			{
				var npc = (NPC) event.getActor();
				// Counted here, where the kill is known to be the player's, so
				// the tally and the event can never disagree about it. Keyed by
				// name rather than id: a player counts kalphites, not the four
				// ids the game happens to use for them.
				String name = Strings.nullToEmpty(npc.getName());
				// tallyBoth, like deaths and levels: the LIFETIME key is what the
				// kill challenge counts against and what earns the nickname, and
				// writing only the daily one left both permanently unreachable -
				// every wager failed on its deadline however well it went.
				context().tallyBoth("kills");
				int count = context().tally(
					"kill:" + name.toLowerCase(Locale.ROOT));
				dispatch(TriggerEvent.kill(
					npc.getId(), name, npc.getCombatLevel(), count));
			}
			return;
		}

		// A death a minigame gives back is no death: no grief, no memory and
		// no mark (the user, 20 Oct, after Castle Wars was mourned for days).
		if (!context().isSafeDeathHere())
		{
			// Where, as well as that: the death spot is remembered for the session,
			// and walking back over it later has its own lines.
			context().noteDeath(local.getWorldLocation());
			context().tallyBoth("deaths");
			dispatch(TriggerEvent.death());
		}
		dialog.close();
	}

	// NpcLootReceived comes from the core LootManager, so it fires regardless
	// of which plugins are enabled. The loot tracker's own LootReceived event
	// would have been silent whenever that plugin was off.
	@Subscribe
	void onNpcLootReceived(net.runelite.client.events.NpcLootReceived event)
	{
		dispatchLoot(event.getItems(),
			event.getNpc() == null ? null : event.getNpc().getWorldLocation());
	}

	@Subscribe
	void onPlayerLootReceived(net.runelite.client.events.PlayerLootReceived event)
	{
		dispatchLoot(event.getItems(),
			event.getPlayer() == null ? null : event.getPlayer().getWorldLocation());
	}

	/** @param at where the drop landed, for the body to look at; null if unknown */
	void dispatchLoot(java.util.Collection<ItemStack> items,
		WorldPoint at)
	{
		long total = 0;
		String best = null;
		long bestValue = -1;
		for (var stack : items)
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
			int worth = (int) min(total, Integer.MAX_VALUE);
			dispatch(TriggerEvent.loot(worth, best));
			if (config.noticesThings())
				// The body's share: a glance, the notes, or the walk over.
				moments.noteworthy(Moments.tierFor(total), at);
		}
	}

	@Subscribe
	void onStatChanged(StatChanged event)
	{
		var skill = event.getSkill();
		int level = event.getLevel();
		var previous = knownLevels.put(skill, level);
		if (previous != null && level > previous)
		{
			var context = context();
			context.tallyBoth("levels");
			// The wider ledger, for the milestone rules: where the total and
			// the lowest skill stood before this level and after it, how many
			// skills are maxed now, and how long this one took.
			int total = 0,
				base = Integer.MAX_VALUE,
				previousBase = Integer.MAX_VALUE,
				masteries = 0;
			for (var each : Skill.values())
			{
				int real = each == skill ? level : client.getRealSkillLevel(each);
				total += real;
				base = min(base, real);
				previousBase = min(previousBase, each == skill ? previous : real);
				if (real >= 99)
					masteries++;
			}
			long now = currentTimeMillis(),
				sinceAny = context.sinceAnyLevel(now),
				sinceLast = context.noteLevel(skill.getName(), level, now);
			dispatch(TriggerEvent.levelUp(skill.getName(), level,
				total, total - (level - previous), base, previousBase, masteries,
				sinceLast, sinceAny));
			if (config.noticesThings())
				// The clap, or the walk over for it; the line is the rules'.
				moments.levelUp(level);
		}

		// A rise in XP is a drop, whatever the level did. The work session
		// counts it and the rules hear of it on the tick it lands; the first
		// reading after a login is baseline, like the level above.
		int xp = event.getXp();
		var previousXp = knownXp.put(skill, xp);
		if (previousXp != null && xp > previousXp)
		{
			if (skill == Skill.AGILITY)
			{
				// What tells a climb from a ladder, and rebuilds an obstacle missed.
				crossingWatch.noteAgilityXp(currentTimeMillis());
				lastAgilityXpMs = currentTimeMillis();
				// Not while the watch cannot read them: as a greegree monkey on
				// Ape Atoll nothing is copied, and the follower joins them as before.
				if (config.copiesAgility() && !parkedByPlayer && CrossingWatch.Sample
					.read(client.getLocalPlayer(), currentTimeMillis()) != null)
					// Whatever the player is on is about to be queued: no snap meanwhile.
					follower.holdForAgility();
			}
			int count = skilling.noteXpDrop(skill);
			dispatch(TriggerEvent.xpDrop(skill.getName(), count));
		}
	}

	// --------------------------------------------------------------- commands

	/** The errand keys ::follower errand accepts, read from the enum itself. */
	static final List<String> ERRAND_KEYS =
		ErrandController.keys();

	/**
	 * The errand keys a player is shown: all but firedeath, which stays
	 * accepted but unlisted, as in the panel's dropdown, so the joke is not
	 * spoiled by a list.
	 */
	static final String LISTED_ERRAND_KEYS = ERRAND_KEYS.stream()
		.filter(k -> !"firedeath".equals(k))
		.collect(Collectors.joining("|"));

	/**
	 * The two answers that keep "Will run the errand at the next quiet
	 * moment" honest: errands are off, or the one asked for could not start
	 * where the follower is. Wording awaits approval (fx/chat-text.json).
	 */
	static final String ERRANDS_OFF = Resources.text("errandsOff");

	/**
	 * The everyday commands as (command, description) pairs, from
	 * commands.tsv. The help printer and the side panel's Commands section
	 * both read this list, so the two cannot drift apart.
	 */
	static final List<Entry<String, String>> EVERYDAY_COMMANDS =
		Collections.unmodifiableList(Resources.rows("commands.tsv").stream()
			.map(row -> command(row[0].replace("{errands}", LISTED_ERRAND_KEYS.replace("|", " | ")), row[1]))
			.collect(Collectors.toList()));

	static Entry<String, String> command(String name, String effect)
	{
		return new AbstractMap.SimpleImmutableEntry<>(name, effect);
	}

	/** The panel's command listing: the everyday commands. */
	List<String> commandLines()
	{
		List<String> lines = new ArrayList<>();
		lines.add("Type ::follower and one of:");
		for (var entry : EVERYDAY_COMMANDS)
			lines.add(entry.getKey() + " - " + entry.getValue());
		return lines;
	}

	/** ::follower reload: re-reads the phrase and sound files from disk. */
	void reloadFiles()
	{
		ruleLoader.reload();
		giveTheNames();
		sendStatus("Rules: " + ruleLoader.getStatus());
		reportRuleErrors();
	}

	/** ::follower here: puts the follower at the player's feet. */
	void placeHere()
	{
		clientThread.invoke(() ->
		{
			var local = client.getLocalPlayer();
			if (local != null && appearanceService.getCurrent() != null)
			{
				follower.spawn(appearanceService.getCurrent(), local.getWorldLocation(),
					spawnGraphic(), config.spawnGraphicHeight());
				sendStatus("Here I am.");
			}
			else
				sendStatus(Resources.text("noModel"));
		});
	}

	/** ::follower rebuild: rebuilds the model from the current outfit. */
	void rebuildFromOutfit()
	{
		clientThread.invoke(() ->
		{
			appearanceService.invalidate();
			follower.despawn();
			rebuildFollower();
			sendStatus("Rebuilt from the current outfit.");
		});
	}

	/**
	 * ::follower fix: re-adds the object to the scene without a full rebuild
	 * or a plugin toggle - the quick way back if the follower ever vanishes.
	 */
	void reattach()
	{
		clientThread.invoke(() ->
		{
			follower.markNeedsReattach();
			sendStatus("Re-attached the follower to the scene.");
		});
	}

	/**
	 * ::follower errand: runs the named errand at the next quiet moment, or
	 * the next due one when the key is null. The side panel's Go now button
	 * calls this too, so both print the same status.
	 */
	void forceErrand(String key)
	{
		if (errands == null)
			return;
		// Validated here, because force() stores any string and the
		// scheduler only ever matches real keys - a typo used to
		// claim success and then nothing happened, forever.
		if (key != null && !ERRAND_KEYS.contains(key))
		{
			sendStatus("No errand called '" + key + "'. Try: "
				+ LISTED_ERRAND_KEYS.replace("|", ", "));
			return;
		}
		// The controller does nothing while errands are off, so a queued
		// key would be a promise nobody keeps.
		if (!config.errandsEnabled())
		{
			sendStatus(ERRANDS_OFF);
			return;
		}
		errands.force(key);
		sendStatus("Will run the " + (key == null ? "next" : key)
			+ " errand at the next quiet moment.");
	}

	/**
	 * Writes the Follow me setting, the one record of follow versus stay. The
	 * caller has already moved the follower, so onConfigChanged finds nothing
	 * left to apply.
	 */
	void setFollowEnabled(boolean enabled)
	{
		setConfig("followEnabled", enabled);
	}

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
	void onMenuOptionClicked(MenuOptionClicked event)
	{
		noteIntentFrom(event.getMenuOption());
		noteExamine(event);
		skilling.noteMenuOption(
			SkillingController.plainOption(event.getMenuOption()),
			SkillingController.plainOption(event.getMenuTarget()));
		noteWorkClick(event);

		if (event.getMenuAction() != MenuAction.WALK
			|| !follower.isSpawned())
			return;
		// WorldView's, not the deprecated Client shortcut: the shortcut just
		// forwards to the top-level view, and it is on its way out.
		var view = client.getTopLevelWorldView();
		var clicked = view == null ? null : view.getSelectedSceneTile();
		var standing = follower.getWorldLocation();
		if (clicked != null && standing != null
			&& standing.equals(clicked.getWorldLocation()))
			context().noteUnderfoot();
	}

	/**
	 * Co-attention: when the player Examines something, the follower turns to
	 * look at the same thing - a field scribe's whole job is looking at what
	 * its companion looks at. The head-turn IS the reaction; a spoken note is
	 * the rare garnish, left to the rules.
	 */
	void noteExamine(MenuOptionClicked event)
	{
		if (!follower.isSpawned())
			return;
		String target = null;
		WorldPoint at = null;
		boolean creature = false;
		var view = client.getTopLevelWorldView();
		switch (event.getMenuAction())
		{
			case EXAMINE_NPC:
			{
				var npc = event.getMenuEntry().getNpc();
				if (npc != null)
				{
					target = npc.getName();
					at = npc.getWorldLocation();
					creature = true;
				}
				break;
			}
			case EXAMINE_OBJECT:
			{
				// Through the errand's resolver: most scenery is a multiloc
				// whose base definition is named "null" until the impostor is
				// asked, and reading the base name alone was quietly dropping
				// the line for a large share of everything examinable.
				target = ErrandController.objectName(
					client, event.getId());
				// Named as a creature is, a common noun after "the": this statue.
				creature = true;
				if (view != null)
					at = WorldPoint.fromScene(view,
						event.getParam0(), event.getParam1(), view.getPlane());
				break;
			}
			case EXAMINE_ITEM_GROUND:
			{
				target = itemManager.getItemComposition(event.getId()).getName();
				if (view != null)
					at = WorldPoint.fromScene(view,
						event.getParam0(), event.getParam1(), view.getPlane());
				break;
			}
			case CC_OP_LOW_PRIORITY:
			{
				// Everything examined out of a bag - inventory, bank,
				// equipment, shop - arrives as a widget op rather than as
				// EXAMINE_ITEM, which is why none of it ever reached a line.
				// The action alone is not enough here the way it is above: a
				// low-priority widget op is every other such op too, so the
				// word is the discriminator, exactly as RuneLite's own examine
				// does it.
				if (!"Examine".equals(event.getMenuOption()))
					return;
				int itemId = event.getItemId();
				if (itemId <= 0)
				{
					// The event's own id is the widget's, not the item's, so
					// the id comes off the slot that was clicked.
					var parent =
						client.getWidget(event.getParam1());
					var slot = parent == null
						? null : parent.getChild(event.getParam0());
					itemId = slot == null ? -1 : slot.getItemId();
				}
				if (itemId > 0)
					target = itemManager.getItemComposition(itemId).getName();
				// No tile, deliberately: the thing is in a bag rather than in
				// the world, so there is nowhere to turn and look. The line
				// still lands; only the glance is skipped.
				break;
			}
			default:
				return;
		}
		if (at != null)
			follower.glanceAt(at, 2400);
		// Multiloc objects answer "null" until their impostor resolves; a
		// glance without a line is fine, a line naming "null" is not.
		if (target != null && !target.isEmpty() && !"null".equals(target))
		{
			String named = target.replaceAll("<[^>]*>", "");
			dispatch(creature ? TriggerEvent.examinedCreature(named)
				: TriggerEvent.examinedThing(named));
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
	void noteIntentFrom(String option)
	{
		if (Strings.isNullOrEmpty(option))
			return;
		var context = context();
		if (isThievingOption(option))
			context.noteThievingIntent();
		else if (option.equalsIgnoreCase("Attack"))
			context.noteFightIntent();
	}

	/** Whether a menu option means "I am about to take that". */
	static boolean isThievingOption(String option)
	{
		if (option == null)
			return false;
		// The game colours menu text with tags; strip them before matching.
		String plain = option.replaceAll("<[^>]*>", "").toLowerCase(Locale.ROOT);
		return plain.contains("pickpocket") || plain.contains("steal");
	}

	/**
	 * The player clicked a work option - Chop down, Mine, Fish - on scenery
	 * or an NPC. The skilling company reads the click first, as the thieving
	 * intent does: it names the target before a single swing. Scenery is
	 * looked up on its own tile for the true footprint, since the click
	 * carries one tile of a two-by-two tree.
	 */
	void noteWorkClick(MenuOptionClicked event)
	{
		if (!SkillingController.isWorkOption(event.getMenuOption()))
			return;
		var view = client.getTopLevelWorldView();
		if (view == null)
			return;
		switch (event.getMenuAction())
		{
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case WIDGET_TARGET_ON_GAME_OBJECT:
			{
				int id = event.getId(),
					plane = view.getPlane(),
					sx = event.getParam0(),
					sy = event.getParam1();
				WorldPoint min = WorldPoint.fromScene(view, sx, sy, plane),
					max = min;
				Tile[][][] tiles = view.getScene() == null
					? null : view.getScene().getTiles();
				if (tiles != null && plane >= 0 && plane < tiles.length && sx >= 0 && sy >= 0
					&& sx < tiles[plane].length && sy < tiles[plane][sx].length
					&& tiles[plane][sx][sy] != null)
				{
					for (var object : tiles[plane][sx][sy].getGameObjects())
					{
						WorldPoint[] box = object == null || object.getId() != id ? null
							: footprint(object, plane);
						if (box != null)
						{
							min = box[0];
							max = box[1];
							break;
						}
					}
				}
				skilling.noteClick(event.getMenuOption(),
					ErrandController.objectName(client, id), id, min, max, null);
				break;
			}
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
			case WIDGET_TARGET_ON_NPC:
			{
				var npc = event.getMenuEntry() == null ? null : event.getMenuEntry().getNpc();
				var at = npc == null ? null : npc.getWorldLocation();
				if (at == null)
					return;
				skilling.noteClick(event.getMenuOption(), npc.getName(), npc.getId(), at,
					SkillingController.farCorner(npc, at), npc);
				break;
			}
			default:
				break;
		}
	}

	@Subscribe
	void onCommandExecuted(CommandExecuted event)
	{
		if (!"follower" /* COMMAND */.equalsIgnoreCase(event.getCommand()))
			return;

		var args = event.getArguments();
		String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";

		switch (sub)
		{
			case "reload":
				reloadFiles();
				break;

			case "copy":
				// Writes your current gear into the follower's outfit, same as the
				// panel's Copy my gear button.
				clientThread.invoke(this::copyGearToCustomOutfit);
				break;

			case "say":
				if (args.length > 1)
					speechEngine.say(String.join(" ", Arrays.copyOfRange(args, 1, args.length)), null);
				else
					sendStatus("Usage: ::follower say <text>");
				break;

			case "here":
				placeHere();
				break;

			case "rebuild":
				rebuildFromOutfit();
				break;

			case "height":
				// Live tuning: the right value can only be judged by eye.
				if (args.length > 1)
				{
					try
					{
						// The setting's own range; its box clamps only what it shows (QA3).
						int asked = Integer.parseInt(args[1]),
							offset = max(-64, min(64, asked));
						setConfig("verticalOffset", offset);
						clientThread.invoke(() -> follower.setVerticalOffset(offset));
						sendStatus("Height offset " + offset
							+ (asked > offset ? ", the most it goes" : asked < offset ? ", the least it goes" : "")
							+ " (positive lifts).");
					}
					catch (NumberFormatException e)
					{
						sendStatus("Usage: ::follower height <number>");
					}
				}
				else
					sendStatus("Usage: ::follower height <number>");
				break;

			case "fix":
				reattach();
				break;

			case "errand":
				forceErrand(args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null);
				break;

			case "outfit":
			{
				if (args.length < 2)
				{
					sendStatus("Profiles: " + (profileStore.names().isEmpty()
						? "(none saved)" : String.join(", ", profileStore.names())));
					break;
				}
				// Profile names may contain spaces; the args past "outfit" are one name.
				var joined = new StringBuilder();
				for (int i = 1; i < args.length; i++)
				{
					if (joined.length() > 0)
						joined.append(' ');
					joined.append(args[i]);
				}
				// loadOutfitProfile stays silent on a miss because the startup
				// restore path also calls it; a typed name deserves an answer.
				if (profileStore.get(joined.toString()) == null)
				{
					sendStatus(unknownProfileMessage(joined.toString()));
					break;
				}
				loadOutfitProfile(joined.toString());
				break;
			}

			case "status":
				sendStatus("Models: " + modelRepository.getStatus()
					+ " | Rules: " + ruleLoader.getStatus()
					+ " | Follower " + (follower.isSpawned() ? "spawned" : "not spawned"));
				sendStatus("Region " + context().getRegionId()
					+ atTile(context().getLocation()));
				break;

			case "where":
				// Split from status: every place that advertises this command
				// promises the region id, which used to arrive buried after a
				// model report nobody asked for.
				sendStatus("You're in region " + context().getRegionId()
					+ onTile(context().getLocation())
					+ Resources.text("useRegion"));
				break;

			default:
				// One wrong letter used to print the whole list, pushing the
				// game's own chat off the screen; the list is for asking.
				if (!"help".equals(sub))
				{
					sendStatus("Unknown command '" + sub + "'. Type ::follower for the list.");
					break;
				}
				sendStatus("::follower commands:");
				for (var entry : EVERYDAY_COMMANDS)
					sendStatus("  " + entry.getKey() + " - " + entry.getValue());
				sendStatus("More in the Follower Buddy side panel.");
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
	static class Utterance
	{
		final String text;
		final SpeechOutput output;
		final SpeechRule rule;
		final int animationId;
		final Runnable onSaid;
		long queuedAtMs;

		Utterance(String text, SpeechOutput output, SpeechRule rule, int animationId,
			Runnable onSaid)
		{
			this.text = text;
			this.output = output;
			this.rule = rule;
			this.animationId = animationId;
			this.onSaid = onSaid;
			this.queuedAtMs = currentTimeMillis();
		}
	}

	final ArrayDeque<Utterance> speechQueue = new ArrayDeque<>();

	/** When the line currently being spoken stops occupying the overhead box. */
	long speakingUntilMs;

	/**
	 * How many lines may wait. Two rules firing together is the case worth
	 * handling - walking into a boss both spots it and starts a fight - while a
	 * deep backlog would have the follower narrating minutes late.
	 */
	static final int SPEECH_QUEUE_LIMIT = 3;

	/** Nothing holds the overhead, or the queue behind it, longer than this. */
	static final long MAX_SPEECH_MS = 12_000L;

	/** A breath between lines, so two in a row do not read as one. */
	static final long SPEECH_GAP_MS = 400;

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
	static final long SPEECH_STALE_MS = MAX_SPEECH_MS + SPEECH_GAP_MS + 3_000L;

	/**
	 * Queues a line rather than letting it stamp over whatever is being said.
	 *
	 * <p>Rules fire independently, so arriving at a boss can trigger the sighting
	 * and the start of the fight within a tick of each other. The overhead box
	 * holds one message, so the second used to replace the first mid-word and
	 * both were lost.
	 */
	void speak(String text, SpeechOutput output, SpeechRule rule, int animationId,
		Runnable onSaid)
	{
		// Nor a silent rule's gesture while a sketch is under way: an idle
		// fidget played over the scroll, and could be in the picture. It is
		// body language, not worth playing late, so it goes. So in its own
		// Talk-to box: behaviour QA4, 16 Oct, it yawned, shrugged and struck
		// its distant pose while its chathead was talking to you.
		if (rule != null && text.isEmpty() && dialog.isOpen())
			return;
		// A silent rule - an emote mirror, a fidget - contends for nothing:
		// the queue exists to serialise the overhead text box, and holding an
		// emote back until a line finishes would have the follower wave at
		// something you did five seconds ago.
		if (text.isEmpty())
		{
			speakNow(new Utterance(text, output, rule, animationId, onSaid));
			return;
		}
		// Posing for a sketch, the overhead is the count's, and a line said
		// now would be in the picture. Held, as in a conversation, and said
		// once the scroll is away. So in the follower's own Talk-to box:
		// behaviour QA, 16 Oct, a line went overhead with the box open,
		// two mouths at once.
		if (dialog.isOpen())
		{
			if (speechQueue.size() < SPEECH_QUEUE_LIMIT)
				speechQueue.addLast(new Utterance(text, output, rule, animationId, onSaid));
			return;
		}

		long now = currentTimeMillis();
		if (now < speakingUntilMs || keptForTheBox(rule, inConversation()))
		{
			if (speechQueue.size() < SPEECH_QUEUE_LIMIT)
				speechQueue.addLast(new Utterance(text, output, rule, animationId, onSaid));
			return;
		}
		speakNow(new Utterance(text, output, rule, animationId, onSaid));
	}

	/**
	 * Starts the next queued line once the current one has had its time.
	 * Called every game tick.
	 */
	void drainSpeechQueue()
	{
		long now = currentTimeMillis();
		// A line that waits its turn is kept through a conversation - an
		// NPC's, or the follower's own box - and its staleness counts from the
		// box closing. Every such line in the queue, not only the first:
		// behaviour QA, 16 Oct, the second was dropped as stale as the box
		// closed, and one kept through the follower's own box aged out in it.
		boolean box = dialog.isOpen() || inConversation();
		for (var queued : speechQueue)
		{
			if (keptForTheBox(queued.rule, box))
				queued.queuedAtMs = now;
		}
		// Nothing overhead while the follower is talking to you in the box:
		// a bubble above its head mid-conversation is two mouths at once.
		// Held, not dropped - the stale sweep below decides what survives
		// a long conversation, same as any other wait.
		if (dialog.isOpen())
			return;
		// Nor into an NPC conversation, a line that waits and what is behind
		// it: said into the box it would be dropped.
		var next = speechQueue.peekFirst();
		if (next != null && keptForTheBox(next.rule, inConversation()))
			return;
		while (!speechQueue.isEmpty()
			&& now - speechQueue.peekFirst().queuedAtMs > SPEECH_STALE_MS)
			// Said too late is worse than not said: a boss sighting a dozen
			// seconds after the fact is just noise.
			speechQueue.removeFirst();
		if (speechQueue.isEmpty() || now < speakingUntilMs)
			return;
		var line = speechQueue.removeFirst();
		// Kept waiting here, as in the engine, the moment it was about can
		// pass: behaviour QA4, 16 Oct, Hans was announced as the follower's
		// own box closed, 24 s after he had gone. Let go unsaid.
		if (line.rule != null && line.rule.when != null
			&& !line.rule.when.standingHolds(context(), TriggerEvent.tick()))
			return;
		speakNow(line);
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
	long readingTimeMs(String text)
	{
		return min(MAX_SPEECH_MS, max(config.speechDurationMs(), timeToReadMs(text)));
	}

	/** How long the line itself takes to read at the player's speed, with no minimum. */
	long timeToReadMs(String text)
	{
		return (long) text.length() * 1000L / max(1, config.readingSpeed());
	}

	/**
	 * Whether a line goes to the chatbox: the mirror setting alone decides,
	 * as it did in 1.4.0. A rule's output only chooses the overhead, so with
	 * the setting off a "both" line stays out of the chatbox.
	 */
	static boolean postsToChatbox(boolean mirrorToChat, SpeechOutput output)
	{
		return mirrorToChat;
	}

	void speakNow(Utterance utterance)
	{
		var output = utterance.output;
		var rule = utterance.rule;
		int animationId = utterance.animationId;

		// Somebody else has the floor. While an NPC dialogue is up the words
		// are dropped outright - not deferred, since a remark about the tree
		// two minutes after the tree is worse than no remark - and nothing
		// downstream of the words happens either: no latch, no journal entry,
		// no sound. The body carries on, which is why an emote still mirrors.
		boolean hushed = !utterance.text.isEmpty() && npcDialogOpen();
		String text = hushed ? "" : utterance.text;

		// The engine's said-conditional state latches here and nowhere else:
		// this is the single door every utterance that survives the queue goes
		// through, so a line the queue drops - stale, displaced, cleared with
		// the scene - opens nothing. A wish nobody heard used to leave a gift
		// option sitting in the Talk-to box with no ask behind it.
		if (utterance.onSaid != null && !hushed)
			utterance.onSaid.run();

		if (!text.isEmpty() && output.showsOverhead())
		{
			long showFor = readingTimeMs(text);
			overlay.show(text, showFor);
			onScreen = utterance;
			onScreenSinceMs = currentTimeMillis();
			speakingUntilMs = currentTimeMillis() + showFor + SPEECH_GAP_MS;
		}

		if (!text.isEmpty() && postsToChatbox(config.mirrorToChat(), output))
			// Every spoken line lands in the chatbox as PUBLIC CHAT under the
			// follower's name - "Name: message" in the game's own chat colours
			// - exactly as a real player's overhead words mirror into chat.
			clientThread.invoke(() -> client.addChatMessage(
				ChatMessageType.PUBLICCHAT, followerName(), text, null));

		// The writing flourish: the scroll comes out as the line is said, so
		// the claim and the act happen together. Best effort - mid-errand or
		// otherwise spoken for, the words stand alone, which still reads fine;
		// the gesture without the words never happens the other way round.
		// Sat on the rest, a rule's gesture had the follower pop up off the
		// seat with no stand-up, and sit down again after it (QA, 15 Oct).
		// The words come and the body stays seated. Not a copy of the
		// player's own animation: that ends the rest a tick later anyway.
		boolean pose = rule != null && Boolean.TRUE.equals(rule.mirrorPose),
			hold = rule != null && Boolean.TRUE.equals(rule.holdStill),
			vanish = rule != null && Boolean.TRUE.equals(rule.vanishAfter),
			seated = resting && rule != null && !Boolean.TRUE.equals(rule.mirrorAnimation) && !pose;

		if (rule != null && rule.prop != null && flourish != null
			&& !text.isEmpty() && !seated
			&& follower.isSpawned() && !follower.isStaying()
			&& !follower.isNpcSlaved() && !errands.isBusy() && !dialog.isOpen())
			clientThread.invoke(() -> flourish.start(
				rule.prop.left == null ? 0 : rule.prop.left,
				rule.prop.item == null ? 0 : rule.prop.item,
				rule.prop.pose == null ? 0 : rule.prop.pose,
				rule.prop.ticks == null ? 8 : rule.prop.ticks));

		if (seated)
			return;

		// A copy of the work is made from beside the WORK, not from the
		// seat: the seat is placed against the PLAYER, so a chop copied from
		// it is an axe swung at open air two tiles from the trunk. The walk
		// over is the controller's; the copy waits here until it has got
		// there, and is refused outright when there is no work to stand at.
		boolean atTheWork = rule != null && Boolean.TRUE.equals(rule.atTheWork) && animationId > 0;
		if (atTheWork)
		{
			// A loop is copied only while the player is still at it: the rule
			// can fire on the last few ticks' memory of the swing, after the
			// tree has fallen and the player has stopped.
			var local = client.getLocalPlayer();
			boolean stopped = pose && (local == null || local.getAnimation() != animationId);
			if (stopped || skilling == null || !skilling.joinIn())
			{
				if (skilling != null && skilling.joiningIn())
				{
					pendingMime = animationId;
					pendingOneShot = !pose;
				}
				return;
			}
			copyAtWork = true;
		}

		// A held emote takes the pose path instead of playing anything: the
		// follower matches the loop for as long as the player keeps it up.
		if (pose && animationId > 0)
		{
			startPoseMirror(animationId);
			return;
		}

		// Plant it first, so the animation starts against a follower that is
		// already stopped rather than one that stops a tick into the clip.
		// Not at the work, where the walk over already holds the feet, and
		// not on a park the player gave: the clip plays at the post, and the
		// plant's release at its end would hand the follower back to following.
		if (hold && rule.hasAnimationAction()
			&& !atTheWork && !parkedByPlayer)
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
			var stageGraphics =
				new SpotAnimRepository.Entry[stages];
			for (int i = 0; i < stages; i++)
			{
				chain[i] = rule.animations.get(i);
				int graphicId = rule.graphics != null && i < rule.graphics.size()
					? rule.graphics.get(i) : -1;
				stageGraphics[i] = graphicId >= 0 ? spotAnimRepository.get(graphicId) : null;
			}
			if (Boolean.TRUE.equals(rule.syncToPlayer))
				clientThread.invoke(() -> follower.startSlavedChain(chain, stageGraphics));
			else
				clientThread.invoke(() -> follower.playAnimations(chain, stageGraphics));
		}
		else if (animationId >= 0)
		{
			// Said on the move, the gesture was cancelled on its first frame:
			// moving drops an emote. Entering a place, a crowd, a pet in range
			// and a greeting typed mid-walk all usually come on the move, so
			// the line was said and the body did nothing (QA, 15 Oct). It
			// waits for the follower to stand, a few ticks at most.
			boolean waits = rule != null && !hold && !atTheWork && !vanish;
			clientThread.invoke(() ->
			{
				if (waits)
					follower.playWhenStill(animationId, EMOTE_WAIT_TICKS);
				else
					follower.playAnimation(animationId);
			});
		}

		// A teleporting follower leaves with its cast: once the mirrored
		// animation finishes, vanish and reappear beside the landed player.
		if (vanish)
			clientThread.invoke(follower::vanishAfterEmote);

		// A rule that copies graphics opens the graphic-copy window, and any
		// spotanim already showing on the player (it can precede the animation
		// event) is copied immediately.
		if (rule != null && Boolean.TRUE.equals(rule.copiesGraphics))
		{
			mirrorGraphicsUntilTick = client.getTickCount() + MIRROR_GRAPHIC_WINDOW_TICKS;
			clientThread.invoke(() ->
			{
				var local = client.getLocalPlayer();
				var active = local == null ? null : latestSpotAnim(local);
				if (active != null && takesMirroredGraphic(client.getTickCount()))
					mirrorGraphic(active.getId(), active.getHeight());
			});
		}
	}

	/**
	 * Whether the problems the rule and dialog files had when the plugin
	 * started have been told. Read at startup, before there is a chat to
	 * tell them in, so a phrases.json with a syntax error left the follower
	 * silent and nothing said why (QA, 15 Oct). Once per start, at the login.
	 */
	boolean loadErrorsReported;

	void reportLoadErrors()
	{
		if (loadErrorsReported)
			return;
		loadErrorsReported = true;
		reportRuleErrors();
		for (String problem : dialogLoader.getErrors())
			sendStatus("Problem in dialogs.json: " + problem);
	}

	void reportRuleErrors()
	{
		for (String error : ruleLoader.getErrors())
			sendStatus("Problem in phrases.json: " + error);
		if (ruleLoader.getRules().isEmpty())
			sendStatus(Resources.text("phrasesUnreadable"));
	}

	String modelHint()
	{
		return modelRepository.isLoaded()
			? "The item list is loaded (" + modelRepository.getStatus() + ") but has no data for these slots."
			: Resources.text("cacheUnread");
	}

	/**
	 * Whether the follower may drift off on its own. The idle window opens
	 * only once the player's stop has proven to be a stay (the threshold the
	 * play report "they start to wander too soon" set) and closes again when
	 * the rest takes over; thieving overrides the window entirely, because
	 * there the follower being underfoot IS the problem and the player never
	 * reads as idle. Extracted after the wander bit the stay rules, so the
	 * gate has a name and a test rather than living inline in the glue.
	 */
	static boolean wanderAllowed(boolean enabled, boolean busy, boolean spawned,
		boolean thieving, int idleTicks, int afterTicks)
	{
		return enabled && !busy && spawned
			&& (thieving
				|| (idleTicks >= max(1, afterTicks) && idleTicks < REST_AFTER_TICKS));
	}

	/**
	 * Whether a stay belongs to the PLAYER rather than to the follower's own
	 * machinery. Every exclusion here is a system that parks the follower
	 * through the same stayAt: the errands, the fight spectating, thrall
	 * conscription, the idle wander (which had the follower announcing "this
	 * spot is mine now" thirty times in an afternoon of its own drifting),
	 * and the emote hold - a holdStill rule plants the follower before its
	 * animation so the clip is not cut off by walking, and every teleport
	 * mirror, kill cheer and celebration carries one. Play report: "when I
	 * teleport, the follower says a line like it was told to stay." The
	 * minute-taking parks it through the same stayAt as the rest of them, and
	 * so does the skilling company's seat.
	 */
	static boolean stayIsPlayerCommanded(boolean staying, boolean wandered,
		boolean thrall,
		boolean errandBusy,
		boolean spectating,
		boolean emoteHold,
		boolean takingMinutes, boolean keepingCompany)
	{
		return staying && !wandered && !thrall
			&& !errandBusy
			&& !spectating
			&& !emoteHold && !takingMinutes && !keepingCompany;
	}

	/**
	 * The one-time naming prompt, a few ticks after the first spawn ever.
	 *
	 * <p>A follower called "Follower" is a stranger; a name is the cheapest
	 * decision a player can make about it, and delayed customisation is a
	 * named churn cause. One chatbox prompt, skippable with Esc, and the
	 * pointer at the setting rides along either way so the name stays
	 * findable once the prompt is gone.
	 */
	void offerName()
	{
		final boolean[] named = {false};
		final boolean[] done = {false};
		chatboxPanelManager.openTextInput(Resources.text("namePrompt"))
			.value("")
			.charValidator(c -> nameChar((char) c))
			.onDone((String answer) ->
			{
				done[0] = true;
				String name = cleanFollowerName(answer);
				if (!name.isEmpty())
				{
					named[0] = true;
					setConfig(
						"followerName", name);
					clientThread.invoke(() ->
						speechEngine.say(name + ". I'll answer to that.",
							SpeechOutput.OVERHEAD));
					// The 20-char cap in cleanFollowerName is silent; a pasted
					// longer name should hear where the rest went.
					if (name.length() == 20 && answer != null
						&& answer.trim().length() > 20)
						sendStatus(Resources.text("nameTooLong") + name + "' it is.");
					sendStatus(Resources.text("nameLater"));
				}
			})
			// Every way the input can end lands here (Enter runs onDone first,
			// then this). Marked here rather than at the offer, so a prompt
			// the client never got to close does not count as offered.
			.onClose(() ->
			{
				setConfig(
					"namePrompted", true);
				if (!done[0] && nameOfferRetries++ == 0)
					// Closed without Enter: Esc, or destroyed by another
					// interface opening. The client routes both through the
					// same close (verified against the client's chatbox
					// manager), so the callbacks cannot tell an ambushed
					// prompt from a declined one - one quiet re-offer covers
					// the ambush without nagging the decliner.
					namePromptDelayTicks = NAME_PROMPT_DELAY_TICKS;
				else if (!named[0])
					sendStatus(Resources.text("noName"));
			})
			.build();
	}

	/**
	 * " at (x, y, plane)" for where and status, or nothing before a place is
	 * known. The tile went in raw, as "WorldPoint(x=3222, y=3218, plane=0)",
	 * or "at null" (QA, 15 Oct).
	 */
	static String atTile(WorldPoint tile)
	{
		return tile == null ? ""
			: " at (" + tile.getX() + ", " + tile.getY() + ", " + tile.getPlane() + ")";
	}

	/** " (x, y, floor p)" for where, or nothing before a place is known. */
	static String onTile(WorldPoint tile)
	{
		return tile == null ? ""
			: " (" + tile.getX() + ", " + tile.getY() + ", floor " + tile.getPlane() + ")";
	}

	/**
	 * A letter or digit the game's fonts can draw - they stop at Latin-1, so
	 * a Cyrillic or Greek name would come out blank over the follower's head
	 * - or a space, apostrophe or hyphen.
	 */
	static boolean nameChar(char c)
	{
		return (Character.isLetterOrDigit(c) && c <= 0xFF) || c == ' ' || c == '\'' || c == '-';
	}

	/**
	 * The follower's name as everything shows it. The settings box takes any
	 * text, and only the chatbox prompt used to clean it: a blank name gave a
	 * "[] " chat prefix and menu options with no target, and tags went
	 * straight into the menu targets.
	 */
	String followerName()
	{
		return displayName(config.followerName());
	}

	/** A name cleaned as a typed one is, or the default when nothing is left of it. */
	static String displayName(String raw)
	{
		String name = cleanFollowerName(raw);
		return name.isEmpty() ? "Follower" : name;
	}

	/**
	 * The target on every menu entry the follower adds, and the one way they
	 * are told apart from other plugins' entries: by equality, since a name
	 * merely contained in a target matched anything, and a blank one matched
	 * every entry on the menu.
	 */
	String followerTarget()
	{
		return "<col=ffff00>" + followerName() + "</col>";
	}

	/**
	 * What survives of a typed name: the allowed characters, single spaces,
	 * at most twenty of them. The chatbox validator already refuses most of
	 * this, but pasted text does not go through it, and the menu target and
	 * dialog header downstream trust the name to hold no tags.
	 */
	static String cleanFollowerName(String raw)
	{
		if (raw == null)
			return "";
		var kept = new StringBuilder();
		for (int i = 0; i < raw.length(); i++)
		{
			char c = raw.charAt(i);
			if (nameChar(c))
				kept.append(c);
		}
		String name = kept.toString().trim().replaceAll(" {2,}", " ");
		if (name.length() > 20)
			name = name.substring(0, 20).trim();
		return name;
	}

	/**
	 * Two chat lines, once ever, at the very first spawn: that the follower is
	 * client-side (the one fact a Hub user most needs and nothing in game ever
	 * states), and that ::follower exists (nothing else in game names it).
	 */
	void maybeSendCommandHint()
	{
		if (config.firstSpawnNoticeShown())
			return;
		setConfig("firstSpawnNoticeShown", true);
		sendStatus("Only you can see " + followerName()
			+ Resources.text("onlyYourScreen"));
		sendStatus("Type ::follower in chat for commands.");
	}

	/**
	 * Send is unreachable by exploration (it needs Shift held before the menu
	 * opens), so it is taught once, at the first Stay - the moment the user
	 * has just demonstrated they want positional control.
	 */
	void maybeSendSendHint()
	{
		if (config.sendHintShown())
			return;
		setConfig("sendHintShown", true);
		sendStatus(Resources.text("sendHint")
			+ followerName() + " there.");
	}

	void sendStatus(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT)
				.append("[" + followerName() + "] ")
				.append(ChatColorType.NORMAL)
				.append(message)
				.build())
			.build());
	}
}

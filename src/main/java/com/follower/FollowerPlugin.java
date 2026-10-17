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
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
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
	description = "A companion that follows you in any gear you like and talks about"
		+ " what you're doing. Dress it in the Follower Buddy side panel.",
	tags = {"follower", "companion", "pet", "player", "model", "chat", "cosmetic", "outfit"}
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

	/** How often the chronicle looks at quest states while the book is shut: six seconds. */
	private static final int CHRONICLE_LOOK_TICKS = 10;

	/** How long a page written waits for the follower to have the floor before it goes unsaid: a minute. */
	static final int PAGE_NEWS_TICKS = 100;

	/**
	 * The page news's wait for a gap this long, in ticks: a minute, or two
	 * gaps and a little over when they are longer. Quests QA, 16 Oct: at
	 * Minimal the news needs 45 s of quiet after the quest's own line, so
	 * any other line in that minute, or a low mood, lost every quest's page.
	 */
	public static int pageNewsTicks(long gapMs)
	{
		return Math.max(PAGE_NEWS_TICKS, (int) (2 * gapMs / 600L) + 20);
	}

	/** A page written, waiting to be mentioned. */
	static final class PageNews
	{
		final TriggerEvent event;
		/** When its minute began: the writing, or the close of a dialogue it waited out. */
		int tick;
		/** For a place's page, the regions it is about; null for news that holds anywhere. */
		final java.util.List<Integer> where;

		PageNews(TriggerEvent event, int tick)
		{
			this(event, tick, null);
		}

		PageNews(TriggerEvent event, int tick, java.util.List<Integer> where)
		{
			this.event = event;
			this.tick = tick;
			this.where = where;
		}
	}

	/**
	 * Pages written and not yet mentioned, oldest first. Said the moment they
	 * were written they met the reaction to the same moment and lost - the
	 * boss's own line, a place's arrival line - so they wait for quiet.
	 */
	private final java.util.ArrayDeque<PageNews> pageNews = new java.util.ArrayDeque<>();



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
	private com.follower.sound.VoiceLibrary voices;

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

	@Inject
	private net.runelite.client.game.chatbox.ChatboxPanelManager chatboxPanelManager;

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
				|| !follower.isUnderMouse(new net.runelite.api.Point(event.getX(), event.getY()))
				|| !offersWalkHere(client.getMenu().getMenuEntries()))
			{
				return event;
			}

			crossPoint = event.getPoint();
			crossStartMs = System.currentTimeMillis();
			// The mouse is heard on the Swing thread, and the box reads the
			// bag for a wished item, which only the client thread may. Asked
			// again there: a second click can queue before the first opens it.
			clientThread.invoke(() ->
			{
				if (started && !dialog.isOpen())
				{
					startTalking();
				}
			});
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
	private com.follower.chronicle.ChronicleLoader chronicle;

	@Inject
	private com.follower.chronicle.ChronicleWindow chronicleWindow;

	/** Which chronicle pages can be read; built on the memory in startUp. */
	private com.follower.chronicle.ChronicleProgress chronicleProgress;

	/** The scribe's sketches of places, and the habit of making them. */
	private com.follower.chronicle.SketchBook sketchBook;
	private com.follower.chronicle.SketchController sketching;

	@Inject
	private net.runelite.client.ui.DrawManager drawManager;

	@Inject
	private java.util.concurrent.ScheduledExecutorService executor;

	@Inject
	private com.follower.follower.StanceLibrary stanceLibrary;

	@Inject
	private com.follower.follower.WrapTrimStore wrapTrimStore;

	@Inject
	private com.follower.speech.ConversationStore conversationStore;

	@Inject
	private net.runelite.client.input.KeyManager keyManager;

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
	private com.follower.ui.PhrasesDialog messagesDialog;
	private com.follower.ui.VoiceDialog voiceDialog;
	private com.follower.ui.DialogsDialog dialogsDialog;
	private com.follower.ui.MemoryDialog memoryDialog;

	@Inject
	private com.google.gson.Gson gson;

	@Inject
	private com.follower.appearance.OutfitProfileStore profileStore;

	private com.follower.follower.ErrandController errands;

	/** The rule-level writing gesture; null until startUp builds it. */
	private com.follower.follower.PropFlourish flourish;

	/** Stands the follower clear of a fight; see SpectateController. */
	private com.follower.follower.SpectateController spectate;

	/** The skilling company: the seat, the borrowed tool, the session the rules read. */
	private com.follower.follower.SkillingController skilling;

	/** The moments: a level, the pet, second wind, a stranger for the player. */
	private com.follower.follower.Moments moments;

	/** Where the follower looks when nothing else owns its head. */
	private com.follower.follower.Gaze gaze;

	/** What it notices that the player has not, and goes to look at. */
	private com.follower.follower.Noticing noticing;

	/** The beat before yes: a Send with a misgiving, the late step aside. */
	private com.follower.follower.Will will;

	/** The player's agility obstacles, recorded for the follower to take after them. */
	private com.follower.follower.CrossingWatch crossingWatch;

	/** Walkable ground nobody walks on: the lit fires in the scene. */
	private com.follower.follower.HotGround hotGround;

	private Path dataDir;
	private WorldPoint lastPlayerTile;
	private int lastRegionId = -1;
	/** The player-commanded stay state last handed to onStayStateChanged. */
	private boolean stayReported;
	/**
	 * A park the player gave - right-click Stay, a Send, Follow me off - held
	 * until they say Follow. Nothing of the follower's own takes the feet
	 * from it: the wander, the seat, the moments, the spectator and the
	 * errands all read it as their feet taken.
	 */
	private boolean parkedByPlayer;
	/**
	 * A park given while something of the follower's own had the feet: that
	 * stands down, and the follower is parked again once it has let go.
	 */
	private boolean parkWhenFree;
	/**
	 * Where a Send given then pointed, walked to once free. Null for a Stay,
	 * which parks where the follower then stands.
	 */
	private WorldPoint sentWhenFree;
	/** Told as the player-commanded stay starts or ends; the side panel's button reads it. */
	private java.util.function.Consumer<Boolean> onStayStateChanged;
	private int reloadPollTicks;
	private int spawnDelayTicks;
	private boolean rebuildQueued;

	/** Ticks until the one-time naming prompt opens; 0 = nothing pending. */
	private int namePromptDelayTicks;

	/** Times the naming prompt closed unanswered this session; one re-offer. */
	private int nameOfferRetries;

	/** Whether a wish was open last tick, to spot the moment one opens. */
	private boolean wasWishing;

	/** Whether the follower was absent (thrall-only mode) last tick. */
	private boolean wasAbsent;

	/** The track playing last tick; null until the first read seeds it. */
	private String lastMusicTrack;

	/** Whether the bank was open last tick, for the close-is-a-boundary watch. */
	private boolean wasBankOpen;

	/** True once the player has walked out of sight of a parked follower. */
	private boolean leftTheFollowerParked;

	/** Ten seconds after the first spawn, so the arrival isn't talked over. */
	private static final int NAME_PROMPT_DELAY_TICKS = 17;

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

	/** ::follower examinewatch - prints what every Examine click resolved to. */
	private boolean watchExamine;

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

	/** Last XP seen per skill; a rise is a drop, whatever the level did. */
	private final Map<Skill, Integer> knownXp = new EnumMap<>(Skill.class);

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
		chronicle.load(dataDir);
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
		voices.load(dataDir);
		journal.initialise(dataDir);
		journal.setRegionSource(() -> speechEngine.getContext().getRegionId());
		journal.setEnabled(config.transcriptOn());
		dialogLoader.initialise(dataDir, true);
		stanceLibrary.load(dataDir);
		// One-time repair: until the observer learned to tell a swing from a
		// flinch, any block seen on a fighting player was filed as that
		// weapon's attack - so a shortbow "shot" by blocking. The bad entries
		// are already on disk and nothing else would ever overwrite them.
		// Second repair, same shape as the first and for the neighbouring
		// reason: the flinch guard stopped blocks being filed as swings, and
		// left skilling animations doing it. Everything learned before the
		// fight check went in was observed under a test that could not tell a
		// swing from a pickaxe, so all of it is suspect and cheap to reobserve.
		if (!config.attacksRelearned() || !config.attacksRelearnedSkilling())
		{
			stanceLibrary.clearLearnedAttacks();
			// Written NOW, not at the next clean shutdown. The flag below says
			// the repair has happened, and a client that is killed before it
			// saves would leave that promise sitting over an unrepaired file
			// with nothing left to run the repair again.
			stanceLibrary.save();
			configManager.setConfiguration(FollowerConfig.GROUP, "attacksRelearned", true);
			configManager.setConfiguration(
				FollowerConfig.GROUP, "attacksRelearnedSkilling", true);
		}
		paletteHarvest.load(dataDir);
		wrapTrimStore.load(dataDir, follower);
		conversationStore.load(dataDir);
		loadOutfitProfiles();

		speechEngine.setSink(this::speak);
		applyConfig();
		loadExactPalette();
		renderCallbacks.register(thrallHider);
		// Anything the dump files didn't provide is parsed from the client's
		// own cache; retried from the login states until the indexes exist.
		clientThread.invokeLater(this::ensureCatalogues);
		// The same transient-prop path the dev commands use: overlaid on the
		// outfit at compose time, never persisted. Shared by the errand, the
		// rule-level flourish and the minutes-taking; all on the client thread.
		hands = new com.follower.follower.ErrandController.Hands()
			{
				@Override
				public void hold(int itemId)
				{
					KitType slot = handSlot(itemId);
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
				private void takeOut(boolean reach)
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
					{
						return;
					}
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
							{
								rebuildFollower();
							}
						});
					}
					else if (started)
					{
						rebuildFollower();
					}
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
					KitType slot = resolveSlot(itemId);
					if (slot == null || slot == KitType.WEAPON || slot == KitType.SHIELD)
					{
						log.debug("Prop {} is not something to wear", itemId);
						return;
					}
					wornSlot = slot;
					wornItemId = itemId;
					rebuildFollower();
				}

				@Override
				public void takeOff()
				{
					if (wornSlot == null)
					{
						return;
					}
					wornSlot = null;
					wornItemId = 0;
					// As release: after a stop there is nothing to rebuild.
					if (started)
					{
						rebuildFollower();
					}
				}

				@Override
				public boolean isWearing()
				{
					return wornSlot != null;
				}
			};
		// Before every mover: the feet consult this on each step they plan.
		hotGround = new com.follower.follower.HotGround(client);
		follower.setHotGround(hotGround::hot);
		errands = new com.follower.follower.ErrandController(client, follower, config,
			speechEngine::dispatch, spotAnimRepository, this::errandBusy, hands);
		flourish = new com.follower.follower.PropFlourish(follower, hands);
		// A SUPPLIER, not the context itself: the engine replaces its context
		// on every reset, and a controller holding the original object read a
		// context that had stopped being refreshed - inCombat false forever,
		// spectating dead after the first relog. See the field note in
		// SpectateController.
		spectate = new com.follower.follower.SpectateController(client, follower, config,
			speechEngine::getContext, spotAnimRepository, speechEngine::dispatch,
			this::setSpectateDisarmed);
		skilling = new com.follower.follower.SkillingController(client, follower, config,
			hands, speechEngine::dispatch,
			() -> speechEngine.getContext().isInCombat(),
			this::seatFeetTaken,
			() -> client.getWidget(net.runelite.api.gameval.InterfaceID.BANKMAIN, 0) != null);
		// The prayer at the altar and the fire line borrow two of the errands.
		skilling.setErrands(errands::force);
		// The seat is taken sooner for the half hour after a favour.
		skilling.setGrateful(() -> speechEngine.getContext().isGrateful());
		// And in return no errand of the follower's own starts mid-session,
		// or in a scene or the quiet after one.
		errands.setSession(() -> skilling.isWorking() || sceneQuiet());
		errands.setOnForcedMiss(key -> sendStatus(ERRAND_MISSED));
		moments = new com.follower.follower.Moments(client, follower, skilling,
			this::momentFeetTaken,
			() -> speechEngine.getContext().isInCombat(),
			() -> speechEngine.getContext().getDaysKnown(),
			flourish,
			() -> speechEngine.getContext().isGrateful(),
			() -> parkedByPlayer);
		// The head: the cursor, the camera and the look round. Busy is anything
		// that owns the head or the feet; the seat's own looks are its own.
		gaze = new com.follower.follower.Gaze(client, follower, config,
			client::getCameraYaw,
			() -> speechEngine.getContext().getIdleTicks(),
			() -> thrallNpc != null || errands.isBusy() || spectate.holdsTheFeet()
				|| dialog.isOpen() || emoteHold || minutesState != 0 || resting
				|| skilling.holdsTheFeet() || moments.holdsTheFeet() || flourish.isActive()
				|| speechEngine.getContext().isInCombat() || sceneQuiet() || filming
				|| (sketching != null && sketching.isSketching()),
			() -> speechEngine.getContext().feelsAbout("disliked") ? "disliked"
				: speechEngine.getContext().feelsAbout("liked") ? "liked" : "",
			() -> thingsNearby(com.follower.follower.Gaze.AROUND_FAR + 2),
			new java.util.Random());
		// The eyes for things: the walk is the moments', the feet theirs.
		noticing = new com.follower.follower.Noticing(client, follower, config, moments,
			speechEngine::dispatch,
			() -> thrallNpc != null || errands.isBusy() || spectate.holdsTheFeet()
				|| dialog.isOpen() || emoteHold || minutesState != 0
				|| skilling.holdsTheFeet() || moments.holdsTheFeet() || flourish.isActive()
				|| speechEngine.getContext().isInCombat() || sceneQuiet() || filming
				|| (sketching != null && sketching.isSketching()),
			() -> speechEngine.getContext().getIdleTicks(),
			this::landmarksNearby);
		// A park that is plainly a park - a wander, a player's Stay - may be
		// stepped out of; a seat, a moment or an errand has its own reasons,
		// and so does a scene being filmed.
		will = new com.follower.follower.Will(client, follower, speechEngine::dispatch,
			() -> thrallNpc == null && !errands.isBusy() && !spectate.holdsTheFeet()
				&& !skilling.holdsTheFeet() && !moments.holdsTheFeet() && !flourish.isActive()
				&& minutesState == 0 && !emoteHold && !filming && !sceneHolds());
		crossingWatch = new com.follower.follower.CrossingWatch(follower);
		follower.setOnCrossed(crossing -> speechEngine.dispatch(
			TriggerEvent.crossed(crossing.isFell())));
		agilityTrace.initialise(dataDir);
		crossingWatch.setTrace(agilityTrace::event);
		follower.setAgilityTrace(agilityTrace::event);
		follower.setOnAgilitySnap(crossingWatch::forgetMissed);
		// Later in the frame, and only if still out: a despawn lets a crossing go too.
		follower.setOnCrossingHands(() -> clientThread.invokeLater(() ->
		{
			if (follower.isSpawned())
			{
				rebuildFollower();
			}
		}));
		filmSet = new com.follower.follower.FilmSet(follower, this::rebuildFollower,
			(graphicId, height) -> follower.playSpotAnim(spotAnimRepository.get(graphicId), height));
		conversation = new com.follower.speech.ConversationPlayer(conversationStage());

		// On a fresh client boot the LOGIN_SCREEN transition can happen before
		// this plugin subscribes, so the first login would not read as fresh.
		// Enabled mid-session instead, the next LOGGED_IN is a chunk reload,
		// not a login - so only pre-arm when not already in the world.
		freshLogin = client.getGameState() != GameState.LOGGED_IN;

		overlayManager.add(overlay);
		overlayManager.add(followDebugOverlay);
		overlayManager.add(dialog);
		overlayManager.add(chronicleWindow);
		overlayManager.add(crossOverlay);
		dialog.register();
		keyManager.registerKeyListener(escapeStopsTheConversation);
		// The chronicle reads quests from the game and the rest from the
		// follower's memory, so it is built on the same context the memory
		// lives in and is saved with it.
		chronicleProgress = new com.follower.chronicle.ChronicleProgress(client, chronicle,
			() -> speechEngine.getContext());
		chronicleWindow.attach(chronicleProgress);
		// A page written is a page the follower mentions, once it has the
		// floor: its own rules say what, from the page's kind and name, or
		// "quest" for a page a quest opened, which the follower has not seen.
		chronicleProgress.setOnWritten((entry, byQuest) -> pageNews.add(new PageNews(
			TriggerEvent.pageWritten(byQuest ? "quest" : entry.kindOrEmpty(), entry.name),
			client.getTickCount(),
			!byQuest && "location".equals(entry.kindOrEmpty()) && entry.unlock != null
				? entry.unlock.regions : null)));
		// Quest pages are per account, where the memory is per profile.
		chronicleProgress.setAccount(() -> configManager.getRSProfileKey());
		// A callback that remembers a quest together asks the book whether
		// the follower was there for it.
		speechEngine.setQuestWitness(chronicleProgress::witnessedOn);
		// The book is the follower's, and signed with its name.
		chronicleWindow.setOwner(config::followerName);
		sketchBook = new com.follower.chronicle.SketchBook(dataDir);
		chronicleWindow.attach(sketchBook);
		// The scribe sketches the places it has pages for, when you pose for
		// it: never mid-fight, mid-errand, mid-conversation, or with any
		// window open over the view, since the view is the picture.
		sketching = new com.follower.chronicle.SketchController(client, follower, hands, chronicle,
			sketchBook, drawManager, executor,
			() -> thrallNpc != null || errands.isBusy() || spectate.holdsTheFeet()
				|| dialog.isOpen() || chronicleWindow.isOpen() || emoteHold || minutesState != 0
				|| skilling.holdsTheFeet() || moments.holdsTheFeet() || flourish.isActive()
				|| speechEngine.getContext().isInCombat() || inCutscene() || windowOverTheView(client)
				|| filming,
			() -> speechEngine.getContext().getIdleTicks(),
			() -> speechEngine.getContext().getRegionId(),
			() -> client.getLocalPlayer() == null ? null : client.getLocalPlayer().getWorldLocation(),
			() -> client.getLocalPlayer() == null ? -1 : client.getLocalPlayer().getAnimation(),
			// Through the engine, so the mute and the output setting hold.
			speechEngine::remark,
			// The same, ahead of what waited through the pose.
			this::sketchSaysNext,
			// The count was asked for, so it is shown whatever the mute.
			this::sketchCount);
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			sketchBook.openFor(configManager.getRSProfileKey());
		}
		chronicleWindow.register();
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
				if (chronicleProgress != null)
				{
					// A tick can land before this restore, on the blank memory;
					// anything the book made of that is taken stock of again.
					chronicleProgress.reset();
				}
				pageNews.clear();
				noteFirstMeeting();
				speechEngine.getContext().setNicknames(ruleLoader.getNicknames());
				speechEngine.getContext().setRegionNames(ruleLoader.getRegionNames());
				readTraits();
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
		journal.flush();
		agilityTrace.flush();
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
		overlayManager.remove(followDebugOverlay);
		overlayManager.remove(dialog);
		overlayManager.remove(chronicleWindow);
		overlayManager.remove(crossOverlay);
		dialog.unregister();
		keyManager.unregisterKeyListener(escapeStopsTheConversation);
		chronicleWindow.unregister();
		chronicleWindow.close();
		mouseManager.unregisterMouseListener(shiftClickAdapter);
		overlay.clear();
		speechQueue.clear();
		cutByTheScene = null;
		speakingUntilMs = 0;
		voices.close();

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
		if (messagesDialog != null)
		{
			messagesDialog.dispose();
			messagesDialog = null;
		}
		if (voiceDialog != null)
		{
			voiceDialog.dispose();
			voiceDialog = null;
		}
		// Left open, its Forget everything still ran for a stopped plugin (QA3).
		if (memoryDialog != null)
		{
			memoryDialog.dispose();
			memoryDialog = null;
		}
		// Disposing is not closing, so no cut is sent: the block below takes
		// the film's pose off with every other, and the scene's flags go with
		// the transient state at the end.
		if (filmDialog != null)
		{
			filmDialog.dispose();
			filmDialog = null;
		}

		// RuneLite calls this on the Swing thread, and letting go of the
		// follower reads the client, which only the client thread may: the
		// sketch's scroll, put away from here, threw and left the reading pose
		// on for good, and the rest of this never ran. So all of it goes over,
		// with the controllers of this session, not any a quick re-enable
		// has built since.
		com.follower.follower.PropFlourish flourishNow = flourish;
		com.follower.follower.SkillingController skillingNow = skilling;
		com.follower.follower.Moments momentsNow = moments;
		com.follower.chronicle.SketchController sketchingNow = sketching;
		com.follower.follower.ErrandController errandsNow = errands;
		clientThread.invoke(() ->
		{
			resetThrallQuietly();
			// An answer latched in the box is told to the rules, which may
			// read the client.
			dialog.close();
			if (flourishNow != null)
			{
				flourishNow.abort();
			}
			if (skillingNow != null)
			{
				skillingNow.reset();
			}
			if (momentsNow != null)
			{
				momentsNow.reset();
			}
			if (sketchingNow != null)
			{
				sketchingNow.reset();
			}
			if (errandsNow != null)
			{
				errandsNow.reset();
			}
			// The scroll must not survive a disable either.
			endMinutes(false);
			// The follower outlives a disable, and so does a pose left on it by
			// what the resets above do not own: a mirrored loop, the rest, the
			// shield's channel. The new session would never know to take it off.
			follower.setPoseOverride(0);
			captureFallback.abort();
			colorHarvester.abort();
			follower.despawn();
			appearanceService.invalidate();
			chronicleWindow.forgetPortraits();
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
	private void resetTransientState()
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
		{
			gaze.reset();
		}
		if (noticing != null)
		{
			noticing.reset();
		}
		if (will != null)
		{
			will.reset();
		}
		strandedLandingTicks = 0;
		filming = false;
		filmParked = false;

		scanTicksLeft = 0;
		animTraceRemaining = 0;
		poseProbeTicks = 0;
		autoHarvestTicks = 0;
		watchAnimations = false;
		watchOthers = false;
		watchChat = false;
		watchExamine = false;
		cutsceneForced = false;
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

	private void addPanel()
	{
		panel = new com.follower.ui.FollowerPanel(itemManager, modelRepository,
			this::equipFromPanel, this::clearSlotFromPanel,
			() -> clientThread.invoke(this::copyGearToCustomOutfit),
			this::clearOutfit, this::setGender, this::cycleKit, this::setBodyColor,
			config.panelFolds());
		panel.setOnFoldsChanged(open -> configManager.setConfiguration(
			FollowerConfig.GROUP, "panelFolds", open));
		panel.setOnEditMessages(this::openMessagesDialog);
		panel.setOnEditDialogs(this::openDialogsDialog);
		panel.setOnShowMemory(this::openMemoryDialog);
		panel.setOnAttachSounds(this::openSoundsDialog);
		panel.setOnShareLines(this::shareLinesFromPanel);
		// The panel's Mute and the settings' Mute are the same config key,
		// synced both ways, so they can never disagree. Chattiness, the
		// chatbox mirror, errands and the thrall profiles work the same way.
		panel.setOnMute(muted -> configManager.setConfiguration(
			FollowerConfig.GROUP, "muted", muted));
		panel.setMuted(config.muted());
		panel.setOnChattiness(chattiness -> configManager.setConfiguration(
			FollowerConfig.GROUP, "chattiness", chattiness));
		panel.setChattiness(config.chattiness());
		panel.setOnMirrorToChat(on -> configManager.setConfiguration(
			FollowerConfig.GROUP, "mirrorToChat", on));
		panel.setMirrorToChat(config.mirrorToChat());
		panel.setOnErrandsEnabled(on -> configManager.setConfiguration(
			FollowerConfig.GROUP, "errandsEnabled", on));
		panel.setErrandsEnabled(config.errandsEnabled());
		panel.setOnThrallProfile((style, name) -> configManager.setConfiguration(
			FollowerConfig.GROUP, "thrall" + Character.toUpperCase(style.charAt(0))
				+ style.substring(1) + "Profile", name));
		panel.setThrallProfiles(config.thrallMeleeProfile(),
			config.thrallRangedProfile(), config.thrallMagicProfile());
		panel.setProfileProtected(profileStore::isProtected);
		panel.setOnFollowEnabled(this::followFromPanel);
		panel.setStaying(isStaying());
		setOnStayStateChanged(staying ->
		{
			if (panel != null)
			{
				panel.setStaying(staying);
			}
		});
		panel.setOnComeHere(this::placeHere);
		panel.setOnFilm(this::openFilmDialog);
		panel.setFollowerName(followerName());
		// The too-close fire variant is the scheduler's own roll, not a
		// choice to offer.
		java.util.Map<String, String> errandNames =
			com.follower.follower.ErrandController.displayNames();
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
			if (index.isEmpty())
			{
				// The item stats were not ready. This runs during login, and an
				// index of nothing would leave the picker unfiltered for the whole
				// session, so it is dropped and ensureCatalogues asks again.
				return;
			}
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
	 * The sit emote's own stand-up, captured alongside the sit itself: its
	 * second half, about a second, the whole emote being 2.84s that starts
	 * standing. Its partner rather than a generic getting-up animation, so
	 * the two read as one gesture the way they do when a player performs it.
	 */
	static final int REST_STAND_UP = com.follower.follower.FollowerEntity.STAND_UP;

	/** Matches idle-long's five minutes, so the sitting and the "we live here
	 * now" lines arrive as one mood rather than two systems coinciding. */
	private static final int REST_AFTER_TICKS = 500;

	private boolean resting;

	/** The sit the rest put on: the one it lets go of, even if the Rest pose
	 * setting has changed since. */
	private int restPose;

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
			|| dialog.isOpen()
			// A long dialog must not sit the minute-taker down mid-sentence.
			|| minutesState != 0
			// Nor sit down over a pose the film has it holding.
			|| filming;
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
		{
			letGoOfTheSit(follower, restPose);
		}
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
		{
			// Cancelled harmlessly by the next movement frame if the follower
			// is already walking, which is the usual reason rest ended.
			follower.playAnimation(REST_STAND_UP);
		}
	}

	// --------------------------------------------------------- taking minutes

	/** The shared prop surface, kept so the minutes-taking can borrow it. */
	private com.follower.follower.ErrandController.Hands hands;

	/** 0 = not taking minutes, 1 = walking to the flank, 2 = writing. */
	private int minutesState;

	/** Whether the current dialog already rolled for minutes, win or lose. */
	private boolean minutesConsidered;

	/** Consecutive ticks with no dialog widget up, to ride out page flips. */
	private int dialogGoneTicks = 1000;

	/** A meeting is minuted at most this often. */
	private static final long MINUTES_COOLDOWN_MS = 480_000;
	private long lastMinutesMs;

	/**
	 * Whether a meeting is written up rather than only attended: now and
	 * then, and only into an empty hand. A tool kept between trees is still
	 * in it after the work stops, and the scroll would take its place.
	 */
	static boolean minutesWritten(boolean handFull, long sinceLastMs, int roll)
	{
		return !handFull && sinceLastMs >= MINUTES_COOLDOWN_MS && roll < 35;
	}

	/**
	 * ::follower force cutscene: held on as if one were playing, until turned
	 * off, until Developer commands are, or until the scene goes, as a real
	 * one does.
	 */
	private boolean cutsceneForced;

	/** A cutscene seen, and not yet three clean ticks gone (noticeTheScene). */
	private boolean sceneOn;
	private int sceneGoneTicks;

	/**
	 * How long the follower keeps quiet after a scene ends, in ticks: fifteen
	 * seconds. Play test, 9 Oct (Children of the Sun): lines landed straight
	 * after the scene, and the ask was a little quiet once a cutscene is over.
	 */
	static final int SCENE_QUIET_TICKS = 25;

	/** The tick the quiet after the last scene ends. */
	private int quietUntilTick;

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

	/** As long as the engine holds an occasion for a scene: five minutes. */
	static final int SCENE_CUT_KEPT_TICKS = 500;

	/** The line on the overhead now, and when it went up. */
	private Utterance onScreen;
	private long onScreenSinceMs;

	/** The line held over the scene, and for how many ticks it has been. */
	private Utterance cutByTheScene;
	private int cutHeldTicks;

	/** Whether a line up this long, from this rule, taking this long to read, is said again after the scene. */
	static boolean saysAgainAfterTheScene(SpeechRule rule, long upForMs, long toReadMs)
	{
		return rule != null && (rule.isOnce() || rule.waitsItsTurn())
			&& upForMs < Math.max(SCENE_CUT_GRACE_MS, toReadMs);
	}

	/**
	 * Whether a cutscene has the camera, or had it under three ticks ago: a beat
	 * between two shots is not the end of the scene. While it does, the follower
	 * is an extra: it keeps to where it stands, gives way to the cast, looks
	 * where the player looks and says nothing. It still mirrors an emote - the
	 * mimic is the part that reads best.
	 */
	private boolean inCutscene()
	{
		return cutsceneForced || sceneOn;
	}

	/**
	 * A scene, or the quiet after one: nothing is said that the player did not
	 * ask for, and nothing of its own is started - no errand, look round,
	 * noticing or moment. Play test, 13 Oct: hold the activities through the
	 * quiet too, not only the words.
	 */
	private boolean sceneQuiet()
	{
		return inCutscene() || client.getTickCount() < quietUntilTick;
	}

	/** Whether the scene keeps the follower: not the player's park, a film's, or thrall work. */
	private boolean sceneHolds()
	{
		return inCutscene() && !parkedByPlayer && !filming && !thrallBusiness();
	}

	/**
	 * Reads the cutscene once a tick, before the load wait, so a scene that
	 * opens with an area load is one from its first tick. Play test, 9 Oct
	 * (Children of the Sun): the follower walked to the player's shoulder and
	 * back on every line of the scene and stood in the cast.
	 */
	private void noticeTheScene()
	{
		boolean up = cutsceneForced
			|| client.getVarbitValue(net.runelite.api.gameval.VarbitID.CUTSCENE_STATUS) != 0;
		sceneGoneTicks = up ? 0 : Math.min(sceneGoneTicks + 1, 1000);
		boolean on = up || (sceneOn && sceneGoneTicks <= 2);
		if (on != sceneOn)
		{
			sceneOn = on;
			// The cause at the start: at the end a forced scene's force is
			// already off.
			journal.scene(on, !on ? "-" : cutsceneForced ? "forced" : "542");
			if (on)
			{
				long now = System.currentTimeMillis();
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
				if (!parkedByPlayer && !filming && !thrallBusiness())
				{
					follower.resumeFollowing();
				}
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
		if (cutByTheScene != null && (sceneQuiet() || filming || inConversation()))
		{
			if (++cutHeldTicks > SCENE_CUT_KEPT_TICKS)
			{
				cutByTheScene = null;
			}
		}
		else if (cutByTheScene != null)
		{
			// Not when its quest's step has moved on since, as the engine lets
			// a line still waiting go. Behaviour QA, 16 Oct: the hideout line
			// was said again after the scene that moved the step, and the next
			// step's moment came 6 s behind it. The step is read from the
			// game; no event plays a part in it.
			if (cutByTheScene.rule.when.questStepHolds(speechEngine.getContext(), TriggerEvent.tick()))
			{
				speechEngine.remark(cutByTheScene.text);
			}
			cutByTheScene = null;
		}
	}

	/** Whether any NPC-dialog interface is up (heads or the options menu). */
	private boolean npcDialogOpen()
	{
		return client.getWidget(net.runelite.api.gameval.InterfaceID.CHAT_LEFT, 0) != null
			|| client.getWidget(net.runelite.api.gameval.InterfaceID.CHAT_RIGHT, 0) != null
			|| client.getWidget(net.runelite.api.gameval.InterfaceID.CHATMENU, 0) != null;
	}

	/** An NPC dialogue up, or gone under three ticks: the reading the rules get, page flips ridden out. */
	private boolean inConversation()
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
	private void updateMinutes()
	{
		boolean open = npcDialogOpen() || pretendMeeting();
		if (open)
		{
			dialogGoneTicks = 0;
		}
		else if (dialogGoneTicks < 1000)
		{
			dialogGoneTicks++;
		}
		// Page flips and choice menus drop the widget for a beat; only three
		// clean ticks of absence mean the meeting is really over.
		boolean over = !open && dialogGoneTicks > 2;

		boolean busy = thrallNpc != null
			|| (errands != null && errands.isBusy())
			|| (spectate != null && spectate.isSpectating())
			|| dialog.isOpen()
			|| (skilling != null && skilling.isWorking())
			|| speechEngine.getContext().isInCombat()
			// A scene's lines are its own: no trip to the shoulder for each.
			|| inCutscene();

		Player local = client.getLocalPlayer();
		switch (minutesState)
		{
			case 0:
				if (over)
				{
					minutesConsidered = false;
				}
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
							System.currentTimeMillis() - lastMinutesMs,
							java.util.concurrent.ThreadLocalRandom.current().nextInt(100));
						if (writes)
						{
							lastMinutesMs = System.currentTimeMillis();
						}
						minutesState = writes ? 1 : 3;
					}
				}
				break;

			case 1:
				if (over || busy || local == null)
				{
					// The meeting ended before it got there; nothing was held.
					endMinutes(false);
				}
				else if (follower.isStaying() && follower.isSettled())
				{
					hands.hold(com.follower.follower.ErrandController.QUILL_PROP,
						com.follower.follower.ErrandController.SCROLL_PROP);
					follower.setPoseOverride(
						com.follower.follower.ErrandController.WRITE_SCROLL_POSE);
					minutesState = 2;
				}
				break;

			case 2:
			case 3:
				if (busy || local == null
					|| follower.getWorldLocation() == null
					|| local.getWorldLocation() == null
					|| follower.getWorldLocation().distanceTo(local.getWorldLocation()) > 8)
				{
					endMinutes(false);
				}
				else if (over)
				{
					// Only a written meeting is minuted; attending is not.
					endMinutes(minutesState == 2);
				}
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
	private boolean standOverTheShoulder(Player local)
	{
		WorldPoint p = local.getWorldLocation();
		if (p == null)
		{
			return false;
		}
		WorldPoint at = null;
		if (local.getInteracting() instanceof NPC)
		{
			at = local.getInteracting().getWorldLocation();
		}
		int fx;
		int fy;
		if (at != null && at.getPlane() == p.getPlane() && !at.equals(p))
		{
			fx = Integer.signum(at.getX() - p.getX());
			fy = Integer.signum(at.getY() - p.getY());
		}
		else
		{
			double radians = (local.getCurrentOrientation() / 2048.0) * 2.0 * Math.PI;
			fx = (int) Math.round(-Math.sin(radians));
			fy = (int) Math.round(-Math.cos(radians));
			at = new WorldPoint(p.getX() + fx, p.getY() + fy, p.getPlane());
		}
		// The two perpendiculars, in a random order so there is no favourite
		// shoulder.
		int sx = -fy;
		int sy = fx;
		if (java.util.concurrent.ThreadLocalRandom.current().nextBoolean())
		{
			sx = -sx;
			sy = -sy;
		}
		WorldPoint[] spots = {
			new WorldPoint(p.getX() - fx + sx, p.getY() - fy + sy, p.getPlane()),
			new WorldPoint(p.getX() - fx - sx, p.getY() - fy - sy, p.getPlane()),
			new WorldPoint(p.getX() + sx, p.getY() + sy, p.getPlane()),
			new WorldPoint(p.getX() - sx, p.getY() - sy, p.getPlane()),
			new WorldPoint(p.getX() - fx, p.getY() - fy, p.getPlane()),
		};
		for (WorldPoint spot : spots)
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
	private void endMinutes(boolean wrote)
	{
		if (minutesState == 2)
		{
			follower.setPoseOverride(0);
			hands.release();
		}
		if (minutesState != 0)
		{
			follower.resumeFollowing();
		}
		minutesState = 0;
		shoulderForcedTicks = 0;
		if (wrote)
		{
			speechEngine.dispatch(TriggerEvent.simple(TriggerEvent.Type.MINUTES_TAKEN));
		}
	}

	/**
	 * The follower put back beside the player by a teleport or a jump past
	 * its leash, in the frame it happens: an errand and the minutes end there
	 * rather than on the next tick, so the scroll is away before it is shown
	 * beside the landed player, and the snap no longer defeats the minutes'
	 * own leash.
	 */
	private void followerSnapped()
	{
		if (errands != null && errands.isBusy())
		{
			errands.reset();
		}
		if (minutesState != 0)
		{
			endMinutes(false);
		}
	}

	/** How long the meeting ::follower force shoulder pretends lasts, at most: twelve seconds. */
	static final int SHOULDER_FORCE_TICKS = 20;

	/** The pretend meeting's ticks left, and the tile the player stood on for it. */
	private int shoulderForcedTicks;
	private WorldPoint shoulderForcedAt;

	/**
	 * ::follower force shoulder: the shoulder taken as if an NPC dialog had
	 * just opened, through the same step the minutes take for a real one,
	 * and let go as a real one is once the pretend meeting ends: at the
	 * player's first step off the tile, or after SHOULDER_FORCE_TICKS. The
	 * gates are the real ones: a Stay, a fight, anything with the feet. The
	 * scroll stays on its own roll.
	 */
	String forceShoulder()
	{
		Player local = client.getLocalPlayer();
		if (minutesState != 0)
		{
			return "Already over your shoulder.";
		}
		if (local == null || local.getWorldLocation() == null)
		{
			return "No player to stand behind.";
		}
		shoulderForcedTicks = SHOULDER_FORCE_TICKS;
		shoulderForcedAt = local.getWorldLocation();
		minutesConsidered = false;
		updateMinutes();
		if (minutesState != 0)
		{
			return "Over your shoulder, as for a meeting, until you step off or twelve seconds pass.";
		}
		shoulderForcedTicks = 0;
		return follower.isStaying() && !wandered ? "The follower is staying put."
			: "The follower is busy, or there is no ground over your shoulder.";
	}

	/**
	 * Whether the meeting ::follower force shoulder pretends is still on,
	 * counted down once a call: over at the player's first step off the
	 * tile they stood on for it, or when its time runs out.
	 */
	private boolean pretendMeeting()
	{
		if (shoulderForcedTicks <= 0)
		{
			return false;
		}
		Player local = client.getLocalPlayer();
		WorldPoint at = local == null ? null : local.getWorldLocation();
		if (--shoulderForcedTicks <= 0 || at == null || !at.equals(shoulderForcedAt))
		{
			shoulderForcedTicks = 0;
			return false;
		}
		return true;
	}

	// --------------------------------------------------------- welfare check

	/** How long the camera goes untouched before it comes round to look. */
	private static final int WELFARE_AFTER_TICKS = 600;

	/** Whether it came round this absence; cleared when the player returns. */
	private boolean welfareChecked;

	/**
	 * Leave the keyboard long enough and the follower walks round in front of
	 * your character and peers at you - and if the rest pose is due, sits
	 * back down there, facing you, which is better than anything this method
	 * could stage on purpose (updateRest re-evaluates on its own). The check
	 * itself is silent: the attention damper is already holding speech while
	 * nobody is there to read it. The payoff line rides ATTENDED_AGAIN, which
	 * fires the moment the camera moves again.
	 */
	private void updateWelfare()
	{
		int unattended = speechEngine.getContext().getUnattendedTicks();
		if (welfareChecked)
		{
			if (unattended == 0)
			{
				welfareChecked = false;
				speechEngine.dispatch(
					TriggerEvent.simple(TriggerEvent.Type.ATTENDED_AGAIN));
			}
			return;
		}
		if (unattended < WELFARE_AFTER_TICKS)
		{
			return;
		}
		boolean busy = thrallNpc != null
			|| (errands != null && errands.isBusy())
			|| (spectate != null && spectate.isSpectating())
			|| dialog.isOpen()
			|| minutesState != 0
			|| follower.isStaying()
			|| (skilling != null && skilling.isWorking())
			|| speechEngine.getContext().isInCombat();
		Player local = client.getLocalPlayer();
		if (busy || local == null || !follower.isSpawned())
		{
			return;
		}
		if (follower.moveToFront(local))
		{
			welfareChecked = true;
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
	/** The default of the Wander after setting, in ticks; the setting is in seconds. */
	static final int WANDER_AFTER_TICKS = 80;

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
		if (moments != null && moments.keepsClose())
		{
			// The ten minutes after a pet: it stays by the new arrival.
			return Math.max(2, WANDER_RADIUS - 2);
		}
		// Taste before mood: somewhere it dislikes it keeps close whatever
		// it is feeling, and somewhere it likes it strays a tile further.
		if (speechEngine.getContext().feelsAbout("disliked"))
		{
			return Math.max(2, WANDER_RADIUS - 2);
		}
		if (speechEngine.getContext().feelsAbout("liked"))
		{
			return WANDER_RADIUS + 1;
		}
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
		// A sketch under way: no drift starts, and none is let go of. A drift
		// let go of walked the scribe back to you mid-count with the scroll
		// out, asked for where it had drifted to; the release below runs once
		// the scroll is away.
		if (sketching != null && sketching.isSketching())
		{
			return;
		}
		int idle = speechEngine.getContext().getIdleTicks();
		boolean busy = thrallNpc != null
			|| (errands != null && errands.isBusy())
			|| (spectate != null && spectate.isSpectating())
			|| dialog.isOpen()
			|| emoteHold
			|| (skilling != null && skilling.holdsTheFeet())
			|| (moments != null && moments.holdsTheFeet())
			|| inCutscene()
			|| resting
			// A long dialog must not walk the minute-taker off the shoulder.
			|| minutesState != 0
			|| parkedByPlayer
			// The park covers a scene held still; this covers one walked with
			// Follow me, where the player stops to type a line.
			|| filming;

		// Thieving wanders too, and does not wait to be invited. The idle gate
		// exists so the follower does not drift while the player is busy, but
		// picking pockets is the one activity where being underfoot is the
		// problem - the player is animating constantly, so the idle counter
		// never climbs and the follower would stand there for the whole run.
		boolean thieving = speechEngine.getContext().isInThievingSession();
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
				{
					follower.resumeFollowing();
				}
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
		// Somewhere it dislikes there is no going to look at things: it
		// keeps to the player's far side from whatever is nearest.
		if (!thieving && speechEngine.getContext().feelsAbout("disliked"))
		{
			List<WorldPoint> things = thingsNearby(DISTRACTION_RADIUS);
			if (!things.isEmpty() && keepToTheFarSide(from, things.get(0)))
			{
				return;
			}
		}
		else if (driftToward(pickDistraction(local, from, nearest)))
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
	 * The tile beside the player on the side away from the thing, or the
	 * next one out. Where the follower stands somewhere it would rather not
	 * be: close, and with the player between it and whatever that is.
	 */
	private boolean keepToTheFarSide(WorldPoint player, WorldPoint thing)
	{
		int dx = Integer.signum(player.getX() - thing.getX());
		int dy = Integer.signum(player.getY() - thing.getY());
		if (dx == 0 && dy == 0)
		{
			dy = 1;
		}
		for (int step = 1; step <= 2; step++)
		{
			WorldPoint spot = new WorldPoint(player.getX() + dx * step,
				player.getY() + dy * step, player.getPlane());
			if (follower.stayAt(spot))
			{
				wandered = true;
				follower.setStayFaceTile(null);
				return true;
			}
		}
		return false;
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
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return null;
		}

		for (NPC npc : view.npcs())
		{
			// The thrall is the follower's own body in thrall mode, and
			// wandering is suppressed then anyway.
			if (npc == null || npc == thrallNpc)
			{
				continue;
			}
			addIfNear(candidates, npc.getWorldLocation(), from, nearest);
		}

		for (Player other : view.players())
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
	 * The tiles of the NPCs and other players within so many tiles of the
	 * follower, nearest first, for the look round. The same population as
	 * the wander's distraction and for the same reason: they are what the
	 * client already lists.
	 */
	private List<WorldPoint> thingsNearby(int radius)
	{
		List<WorldPoint> found = new ArrayList<>();
		WorldPoint from = follower.getWorldLocation();
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		Player local = client.getLocalPlayer();
		if (from == null || view == null)
		{
			return found;
		}
		for (NPC npc : view.npcs())
		{
			if (npc != null && npc != thrallNpc)
			{
				addIfNear(found, npc.getWorldLocation(), from, radius, 1);
			}
		}
		for (Player other : view.players())
		{
			if (other != null && other != local)
			{
				addIfNear(found, other.getWorldLocation(), from, radius, 1);
			}
		}
		found.sort((a, b) -> Integer.compare(a.distanceTo(from), b.distanceTo(from)));
		return found;
	}

	/**
	 * The signposts, statues and plaques within a few tiles of the player,
	 * name and tile. A seven by seven scan of the scene, run only while the
	 * player has stood still a while, so its cost is nothing in play.
	 */
	private List<java.util.Map.Entry<String, WorldPoint>> landmarksNearby()
	{
		List<java.util.Map.Entry<String, WorldPoint>> found = new ArrayList<>();
		Player local = client.getLocalPlayer();
		net.runelite.api.coords.LocalPoint lp = local == null ? null : local.getLocalLocation();
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (lp == null || view == null || view.getScene() == null)
		{
			return found;
		}
		net.runelite.api.Tile[][][] tiles = view.getScene().getTiles();
		int plane = view.getPlane();
		int reach = com.follower.follower.Noticing.LANDMARK_WITHIN + 1;
		for (int dx = -reach; dx <= reach; dx++)
		{
			for (int dy = -reach; dy <= reach; dy++)
			{
				int x = lp.getSceneX() + dx;
				int y = lp.getSceneY() + dy;
				if (plane < 0 || plane >= tiles.length || x < 0 || y < 0
					|| x >= tiles[plane].length || y >= tiles[plane][x].length
					|| tiles[plane][x][y] == null)
				{
					continue;
				}
				for (net.runelite.api.GameObject object : tiles[plane][x][y].getGameObjects())
				{
					if (object == null)
					{
						continue;
					}
					String name = com.follower.follower.ErrandController.objectName(
						client, object.getId());
					if (com.follower.follower.Noticing.isLandmark(name))
					{
						found.add(new java.util.AbstractMap.SimpleImmutableEntry<>(
							name, object.getWorldLocation()));
					}
				}
			}
		}
		return found;
	}

	private static void addIfNear(List<WorldPoint> into, WorldPoint at, WorldPoint from,
		int radius, int nearest)
	{
		if (at != null && at.getPlane() == from.getPlane())
		{
			int distance = at.distanceTo(from);
			if (distance <= radius && distance >= nearest)
			{
				into.add(at);
			}
		}
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
		if (!onMemoryProfile())
		{
			return;
		}
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
		/** Each boss's name as the game writes it, by its lower-case kill count key. */
		java.util.Map<String, String> killCountNames;
		int sessions;

		/** The incident the follower is still thinking about, and how to say it. */
		String incidentKey;
		String incidentPhrase;
		int incidentCount;
		/** Where (a region id), when (epoch ms) and who it was with. */
		int incidentWhere;
		long incidentWhenMs;
		String incidentWho;

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
		/** And the day (an epoch day) each was said; missing from older saves. */
		java.util.Map<String, Long> spokenOnceOn;
		/** Quests the follower saw finish, by quest and account, and the day. */
		java.util.Map<String, Long> questsSeenFinished;
		/** Chronicle pages written so far, by entry id. */
		java.util.List<String> chronicled;
		/** And when (an epoch day) and where each was written, where known. */
		java.util.Map<String, Long> chronicledOn;
		java.util.Map<String, String> chronicledAt;

		/**
		 * How worn each line is - times actually said, keyed by the template's
		 * hash so the blob stays small. Capped at 500 entries by the context,
		 * least-worn dropped first.
		 */
		java.util.Map<String, Integer> lineWear;

		/**
		 * The day the follower first met this player, as an epoch day. Written
		 * once and never again: it is the only thing here that would be a lie
		 * if it were ever recalculated.
		 */
		long metOnDay;

		/** What the player was wearing then, so an upgrade has something to beat. */
		int metWearingValue = -1;

		/** When the follower last asked for something, so the next ask waits a day. */
		long lastWishMs;

		/** When each skill last went up, so the next level knows how long it took. */
		java.util.Map<String, Long> levelStamps;

		/** What the two of them were doing when the session ended, for the greeting. */
		String lastThing;

		/** The name the player agreed to be called, and the ones they turned down. */
		String nickname;
		java.util.List<String> refusedNicknames;
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
	private long memoryProfileId = -1;

	/**
	 * The same, for the outfit, and never let go of. A logout drops the
	 * memory but not the outfit, which is still worn and still this
	 * profile's: an edit made at the login screen belongs under its name.
	 */
	private long outfitProfileId = -1;

	private long currentProfileId()
	{
		net.runelite.client.config.ConfigProfile profile = configManager.getProfile();
		return profile == null ? -1 : profile.getId();
	}

	/** Whether the follower's state belongs to the profile that is current now. */
	private boolean onMemoryProfile()
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
	private volatile boolean started;

	/**
	 * The RuneLite profile changed under the plugin. The changed keys have
	 * already been announced and applied; what the follower knows and wears
	 * belongs to the profile it was read from, so it is read again from this
	 * one rather than written over it. A switch is a session boundary: the
	 * old profile's mood, wants and counters go with it.
	 */
	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		clientThread.invoke(() ->
		{
			// Switched to a profile where the plugin is off, the stop runs on
			// the Swing thread and can come first. This then read that
			// profile's memory into the engine the stop had just cleared, and
			// the next start read its own on top: two memories added together.
			if (!started)
			{
				return;
			}
			log.debug("Profile changed to {}; re-reading the follower's memory", currentProfileId());
			// Lines the old profile's follower queued go with it: delivered,
			// a wish or a want they ask would open in the new one's memory.
			speechQueue.clear();
			cutByTheScene = null;
			speakingUntilMs = 0;
			speechEngine.reset();
			// The reset clears every rule's edge, so the gear worn and the
			// place stood in would rise as news on the next tick. Same cure
			// as login: they are baseline.
			if (switchTakesTheBaseline(freshLogin))
			{
				speechEngine.primeEdgesOnNextTick();
			}
			metWearingValue = -1;
			readCounters();
			if (chronicleProgress != null)
			{
				// Another profile's memory: its pages are taken stock of, not
				// compared with the last profile's and announced.
				chronicleProgress.reset();
			}
			pageNews.clear();
			noteFirstMeeting();
			speechEngine.getContext().setNicknames(ruleLoader.getNicknames());
				speechEngine.getContext().setRegionNames(ruleLoader.getRegionNames());
			readTraits();
			// This profile's own outfits, read before its outfit is restored
			// from them: the last profile's list must not dress this one.
			loadOutfitProfiles();
			if (panel != null)
			{
				restoreActiveProfile();
			}
			appearanceService.invalidate();
			rebuildFollower();
			syncPanel();
			// An open Memory window shows this profile's memory, which is the
			// one its Forget everything would wipe (QA3).
			java.util.List<String[]> rows =
				com.follower.ui.MemoryDialog.summarise(speechEngine.getContext(),
					ruleLoader.getRegionNames(), chronicleProgress::accountKey);
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				if (memoryDialog != null && memoryDialog.isVisible())
				{
					memoryDialog.show(rows);
				}
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
			context.restoreKillCountNames(saved.killCountNames);
			context.setSessionCount(saved.sessions);
			context.restoreIncident(saved.incidentKey, saved.incidentPhrase,
				saved.incidentCount, saved.incidentWhere, saved.incidentWhenMs, saved.incidentWho);
			context.restorePlaces(saved.placeScores, saved.placeMemories);
			context.restoreSpokenOnce(saved.spokenOnce, saved.spokenOnceOn);
			context.restoreQuestsSeenFinished(saved.questsSeenFinished);
			context.restoreChronicled(saved.chronicled);
			context.restoreChronicledStamps(saved.chronicledOn, saved.chronicledAt);
			context.restoreLineWear(saved.lineWear);
			context.setMetOnDay(saved.metOnDay);
			context.setLastWishMs(saved.lastWishMs);
			context.setMetWearingValue(saved.metWearingValue);
			context.restoreLevelStamps(saved.levelStamps);
			context.restoreLastThing(saved.lastThing);
			context.restoreNickname(saved.nickname, saved.refusedNicknames);
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
		if (!onMemoryProfile())
		{
			log.debug("Not writing the memory: it belongs to another profile");
			return;
		}
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
		saved.killCountNames = context.getKillCountNames().isEmpty() ? null
			: new java.util.HashMap<>(context.getKillCountNames());
		saved.sessions = context.getSessionCount();
		saved.incidentKey = context.getIncidentKey();
		saved.incidentPhrase = context.getIncidentPhrase();
		saved.incidentCount = context.getIncidentCount();
		saved.incidentWhere = context.getIncidentWhere();
		saved.incidentWhenMs = context.getIncidentWhenMs();
		saved.incidentWho = context.getIncidentWho().isEmpty() ? null : context.getIncidentWho();
		saved.placeScores = context.getPlaceScores();
		saved.placeMemories = context.getPlaceMemories();
		saved.spokenOnce = new java.util.ArrayList<>(context.getSpokenOnce());
		saved.spokenOnceOn = context.getSpokenOnceOn().isEmpty() ? null
			: new java.util.HashMap<>(context.getSpokenOnceOn());
		saved.questsSeenFinished = context.getQuestsSeenFinished().isEmpty() ? null
			: new java.util.HashMap<>(context.getQuestsSeenFinished());
		saved.chronicled = new java.util.ArrayList<>(context.getChronicled());
		saved.chronicledOn = context.getChronicledOn().isEmpty() ? null
			: new java.util.HashMap<>(context.getChronicledOn());
		saved.chronicledAt = context.getChronicledAt().isEmpty() ? null
			: new java.util.HashMap<>(context.getChronicledAt());
		saved.lineWear = context.getLineWear();
		saved.metOnDay = context.getMetOnDay();
		saved.lastWishMs = context.getLastWishMs();
		saved.metWearingValue = metWearing;
		saved.levelStamps = context.getLevelStamps().isEmpty() ? null
			: new java.util.HashMap<>(context.getLevelStamps());
		saved.lastThing = context.getLastThing().isEmpty() ? null : context.getLastThing();
		saved.nickname = context.getNickname().isEmpty() ? null : context.getNickname();
		saved.refusedNicknames = context.getRefusedNicknames().isEmpty() ? null
			: new java.util.ArrayList<>(new java.util.TreeSet<>(context.getRefusedNicknames()));
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
					applyCoreTastes(saved);
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
		applyCoreTastes(rolled);
		configManager.setConfiguration(FollowerConfig.GROUP, "traits", gson.toJson(rolled));
		log.debug("Rolled traits: likes {}, dislikes {}", rolled.liked, rolled.disliked);
	}

	/**
	 * The core tastes (R23) are the FIRST entry of each rolled list: the blob
	 * stores ordered lists, so the same two regions come back on every
	 * restore, including from blobs written before cores existed. One like
	 * and one grudge the earned record can never argue away.
	 */
	private void applyCoreTastes(SavedTraits traits)
	{
		speechEngine.getContext().setCoreTastes(
			traits.liked == null || traits.liked.isEmpty() ? 0 : traits.liked.get(0),
			traits.disliked == null || traits.disliked.isEmpty() ? 0 : traits.disliked.get(0));
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
				follower.playSpotAnim(spotAnimRepository.get(TELEPORT_ARRIVE_SPOTANIM),
					FollowerEntity.TELEPORT_GRAPHIC_HEIGHT);
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
		// Agility too: a follower a floor below on a rooftop course, or
		// waiting on an obstacle about to be queued, is behind, not stuck.
		if (follower.isDoingAgility() || follower.isHoldingForAgility())
		{
			follower.clearStranded();
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
	/**
	 * Lazily builds the one message-editor window and fronts it. Every rule
	 * group the panel used to spread over seven buttons lives here behind a
	 * category dropdown; only Locations is structural (regions editable,
	 * rules addable).
	 */
	private void openMessagesDialog()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (messagesDialog == null)
			{
				messagesDialog = new com.follower.ui.PhrasesDialog(gson, ruleLoader.getFile(),
					java.util.Arrays.asList(
						new com.follower.ui.PhrasesDialog.Category("Items", "gear",
							"Said when you equip something it has an opinion on, and when your"
								+ " bag is nearly full.",
							false),
						new com.follower.ui.PhrasesDialog.Category("Locations", "area",
							"Said on entering a place, and about places it likes, dislikes or"
								+ " remembers. Region ids are editable and Add location makes a new one.",
							true),
						new com.follower.ui.PhrasesDialog.Category("Bosses", "boss",
							"Said when that boss appears nearby, and at boss kills and kill counts.",
							false),
						new com.follower.ui.PhrasesDialog.Category("Combat", "combat",
							"Said while it stands clear and watches you fight, and when something"
								+ " big comes near.",
							false),
						new com.follower.ui.PhrasesDialog.Category("Statuses", "health",
							"Your HP, prayer, poison, venom, skull and run energy.",
							false),
						new com.follower.ui.PhrasesDialog.Category("Idle chatter", "idle",
							"Small talk while nothing much is happening.",
							false),
						new com.follower.ui.PhrasesDialog.Category("Quest NPCs", "quest",
							"Said near famous quest figures, at moments partway through some"
								+ " quests, and when you finish one.",
							false),
						new com.follower.ui.PhrasesDialog.Category("Errands", "errand",
							"Each errand has a line for setting off and one for coming"
								+ " back. Go on errands in settings turns them off.",
							false),
						new com.follower.ui.PhrasesDialog.Category("Mimicry", "mimic",
							"Said now and then while it copies your emotes, spells or teleports,"
								+ " or eats when you eat.",
							false),
						new com.follower.ui.PhrasesDialog.Category("Reminiscing", "memory",
							"Things that happened before, the outings it was and was not"
								+ " taken on, and how long it has known you.",
							false),
						new com.follower.ui.PhrasesDialog.Category("Souvenirs", "souvenir",
							"Things it picks up, carries about and loses.",
							false),
						new com.follower.ui.PhrasesDialog.Category("Predictions", "bet",
							"Its calls on the next drop and its bets on you, and how it takes"
								+ " being wrong.",
							false),
						new com.follower.ui.PhrasesDialog.Category("The hour and the session", "clock",
							"What time it is where you are, how long you have been playing,"
								+ " and the odd date.",
							false),
						new com.follower.ui.PhrasesDialog.Category("Reactions", "reactions",
							"Deaths, drops, thieving, level-ups, being thanked or looked"
								+ " at, and what goes on in the chat.",
							false),
						new com.follower.ui.PhrasesDialog.Category("Thrall", "thrall",
							"Said as it takes a thrall's place and when the thrall's time"
								+ " runs out. Mute the thrall in settings silences them.",
							false),
						new com.follower.ui.PhrasesDialog.Category("Everything else", "misc",
							"Hellos, goodbyes, milestones and the rest. To silence them all,"
								+ " add misc to Silence groups or rule ids by name in settings.",
							false)),
					// The tick loop keeps lastRegionId current; reading a
					// slightly stale int from the EDT is harmless.
					() -> lastRegionId);
				messagesDialog.setOnShare(() -> shareLines(messagesDialog));
				messagesDialog.setOnEditSounds((title, lines) ->
				{
					if (voiceDialog == null)
					{
						voiceDialog = new com.follower.ui.VoiceDialog(
							voices, config::voiceVolume);
					}
					voiceDialog.open(title, lines);
				});
			}
			messagesDialog.open();
		});
	}

	/**
	 * Opens the memory window (R20) on a snapshot taken on the client thread,
	 * so the Swing side never reads live maps the game loop is writing.
	 */
	private void openMemoryDialog()
	{
		clientThread.invoke(() ->
		{
			java.util.List<String[]> rows =
				com.follower.ui.MemoryDialog.summarise(speechEngine.getContext(),
					ruleLoader.getRegionNames(), chronicleProgress::accountKey);
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				if (memoryDialog == null)
				{
					memoryDialog = new com.follower.ui.MemoryDialog(this::forgetEverything);
				}
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
	private void forgetEverything()
	{
		clientThread.invoke(() ->
		{
			if (!started)
			{
				return;
			}
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
			speechEngine.reset();
			// The reset clears every rule's edge, so the gear worn and the
			// place stood in would rise as news on the next tick. Same cure
			// as a switch: baseline, unless a login is coming to take its own.
			// The stranger still introduces itself at the next login.
			if (switchTakesTheBaseline(freshLogin))
			{
				speechEngine.primeEdgesOnNextTick();
			}
			speechEngine.getContext().setNicknames(ruleLoader.getNicknames());
				speechEngine.getContext().setRegionNames(ruleLoader.getRegionNames());
			metWearingValue = -1;
			sessionMinutes = 0;
			sessionRecordSaid = false;
			knownLevels.clear();
			// The Chronicle goes too (the user, 16 Oct): its dates live in the
			// memory just erased, and its drawings are torn out here.
			sketchBook.forgetAll();
			sendStatus("Forgotten. All of it. Clean page.");

			java.util.List<String[]> rows =
				com.follower.ui.MemoryDialog.summarise(speechEngine.getContext(),
					ruleLoader.getRegionNames(), chronicleProgress::accountKey);
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				if (memoryDialog != null)
				{
					memoryDialog.show(rows);
				}
			});
		});
	}

	/** The Sounds window on its own, from the panel: the folder, and how to attach a file. */
	private void openSoundsDialog()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (voiceDialog == null)
			{
				voiceDialog = new com.follower.ui.VoiceDialog(voices, config::voiceVolume);
			}
			voiceDialog.open();
		});
	}

	// ------------------------------------------------------------------ filming

	/**
	 * Whether a scene is being filmed: the follower stands where it is told,
	 * strikes the poses it is given and says only the lines typed for it.
	 * Nothing of its own - no chatter, errands, wander, rest, sketch, look
	 * round, noticing, moments, step-aside or copied emote.
	 */
	private boolean filming;

	/**
	 * Whether the film parked the follower, rather than finding it parked by
	 * the player. Only then does the end of the scene hand it back to following.
	 */
	private boolean filmParked;

	/** The film's hold on the follower's pose and facing; built in startUp. */
	private com.follower.follower.FilmSet filmSet;

	private com.follower.ui.FilmDialog filmDialog;

	/** The film window, from the panel's Film a scene or ::follower film. */
	private void openFilmDialog()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (filmDialog == null)
			{
				filmDialog = new com.follower.ui.FilmDialog(filmCrew(), clientThread::invoke,
					conversationStore, this::followerName);
			}
			filmDialog.open();
		});
	}

	/**
	 * The scene starts: the follower stops where it stands and goes quiet.
	 * Anything waiting to be said goes, as at a silence. The park is the
	 * player's own, so everything that reads one stands down for it.
	 */
	private void startFilming()
	{
		filming = true;
		speechQueue.clear();
		cutByTheScene = null;
		overlay.clear();
		// A scroll still out is put away before the film puts anything on.
		// Left to run out, its end takes off the pose the film has put on
		// since, and hands the follower back to following, facing and all.
		if (flourish != null)
		{
			flourish.abort();
		}
		if (skilling != null)
		{
			skilling.putTheLedgerAway();
		}
		// A sketch being posed for goes too, without a word: its count and
		// its line after would be said over the take.
		if (sketching != null)
		{
			sketching.abort();
		}
		// A Send paused at a monster's edge would go on into the take.
		if (will != null)
		{
			will.reset();
		}
		if (!parkedByPlayer)
		{
			follower.stayHere();
			parkForTheFilm(null);
		}
	}

	/**
	 * The scene is over: the film's pose and facing are let go of, and the
	 * follower follows again if the film was what parked it. A park the
	 * player gave before filming is theirs, and stays.
	 */
	private void endFilming()
	{
		// A conversation can play without the follower out, so before the
		// check: whatever ends the scene ends it too.
		if (conversation != null)
		{
			conversation.stop(null);
		}
		if (!filming)
		{
			return;
		}
		filming = false;
		filmSet.letGo();
		if (filmParked && config.followEnabled())
		{
			followAgain();
		}
		filmParked = false;
	}

	/**
	 * Every film action starts here, so a scene that a logout or a hop ended
	 * starts again with the next press in the window. A press queued just
	 * before a stop finds the plugin off, as a queued shift-click does.
	 *
	 * @return why nothing can be filmed, or null
	 */
	private String ensureFilming()
	{
		if (!started || !follower.isSpawned())
		{
			return "The follower is not out right now.";
		}
		if (!filming)
		{
			startFilming();
		}
		return null;
	}

	/**
	 * A park for the film, given as the player's own, which everything that
	 * would take the feet reads as theirs taken. Marked as the film's when
	 * the follower had been following, so the end of the scene hands it back.
	 */
	private void parkForTheFilm(WorldPoint sentTo)
	{
		if (!parkedByPlayer)
		{
			filmParked = true;
		}
		parkedByThePlayer(sentTo);
	}

	/** Choosing a facing holds it still, or the follow would turn it straight back. */
	private void holdStillForTheFilm()
	{
		if (!follower.isStaying())
		{
			follower.stayHere();
			parkForTheFilm(null);
		}
	}

	/** A film action once the scene is sure to be under way. */
	private String film(Runnable action)
	{
		String refused = ensureFilming();
		if (refused == null)
		{
			action.run();
		}
		return refused;
	}

	/** A word for the film window's status line, from the client thread. */
	private void tellTheFilm(String text)
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (filmDialog != null)
			{
				filmDialog.status(text);
			}
		});
	}

	/** What the film window asks for, each on the client thread. */
	private com.follower.ui.FilmDialog.Crew filmCrew()
	{
		return new com.follower.ui.FilmDialog.Crew()
		{
			@Override
			public String start()
			{
				return ensureFilming();
			}

			@Override
			public String hold(int animationId)
			{
				return film(() ->
				{
					filmSet.hold(animationId);
					refreshEmoteDisarm(follower.isEmotePlaying());
				});
			}

			@Override
			public String once(int animationId)
			{
				return film(() -> filmSet.once(animationId));
			}

			@Override
			public String again()
			{
				String refused = ensureFilming();
				if (refused != null)
				{
					return refused;
				}
				return filmSet.again() ? null : "Again repeats the last [once] pose. Play one first.";
			}

			@Override
			public String standNormally()
			{
				return film(() ->
				{
					filmSet.standNormally();
					refreshEmoteDisarm(follower.isEmotePlaying());
				});
			}

			@Override
			public int lastPlayerAnimation()
			{
				return filmSet.getLastPlayerAnimation();
			}

			@Override
			public String face(int orientation)
			{
				return film(() ->
				{
					holdStillForTheFilm();
					filmSet.face(orientation);
				});
			}

			@Override
			public String faceMe()
			{
				return film(() ->
				{
					holdStillForTheFilm();
					filmSet.faceMe();
				});
			}

			@Override
			public String faceCamera()
			{
				return film(() ->
				{
					holdStillForTheFilm();
					filmSet.face(com.follower.follower.FilmSet.cameraFacing(client.getCameraYaw()));
				});
			}

			@Override
			public String holdHere()
			{
				return film(() ->
				{
					follower.stayHere();
					parkForTheFilm(null);
					filmSet.reaim();
				});
			}

			@Override
			public String followMe()
			{
				// As right-click Follow: the park is over, whoever gave it.
				// The facing went with the stay.
				return film(() ->
				{
					followAgain();
					setFollowEnabled(true);
					filmSet.faceMe();
				});
			}

			@Override
			public String followerSays(String text)
			{
				String refused = ensureFilming();
				if (refused != null)
				{
					return refused;
				}
				if (npcDialogOpen())
				{
					return "It keeps quiet while a dialogue box is open.";
				}
				// Straight away, over whatever is up. No rule, so the film's
				// own gate lets it through, with its voice and the chatbox
				// mirror as any line has them.
				speechQueue.clear();
				cutByTheScene = null;
				speakingUntilMs = 0;
				speak(text, com.follower.speech.SpeechOutput.OVERHEAD, null, -1, null);
				return null;
			}

			@Override
			public String playerSays(String text)
			{
				Player me = client.getLocalPlayer();
				if (me == null)
				{
					return "You are not in the game.";
				}
				// Over the player's own head whether the follower is out or not.
				ensureFilming();
				filmSet.playerSays(me, text, readingTimeMs(text));
				return null;
			}

			@Override
			public String play(com.follower.speech.Conversation script)
			{
				if (client.getLocalPlayer() == null)
				{
					return "You are not in the game.";
				}
				// The follower holds still and quiet for it, as for any line.
				// A conversation of the player's lines alone plays without it.
				String refused = ensureFilming();
				if (refused != null && script.followerSpeaks())
				{
					return refused;
				}
				return conversation.play(script, System.currentTimeMillis());
			}

			@Override
			public String stopConversation()
			{
				if (!conversation.isPlaying())
				{
					return "Nothing is playing.";
				}
				conversation.stop("Stopped.");
				return null;
			}

			@Override
			public void cut()
			{
				// The pose's props go with it, and its own gear comes back.
				endFilming();
			}
		};
	}

	/** The film window's conversation, played; built in startUp. */
	private com.follower.speech.ConversationPlayer conversation;

	/**
	 * The player's written chat line last put in the chatbox, until the
	 * chatbox hands it back to onChatMessage: written, not said, so no rule
	 * hears it. QA2, 15 Oct: "hello" in a take set off greet-back.
	 */
	private String scriptedChat;

	/** Esc in the game stops a conversation playing, and is the conversation's while one is. */
	private final net.runelite.client.input.KeyListener escapeStopsTheConversation =
		new net.runelite.client.input.KeyListener()
	{
		@Override
		public void keyTyped(java.awt.event.KeyEvent e)
		{
		}

		@Override
		public void keyReleased(java.awt.event.KeyEvent e)
		{
		}

		@Override
		public void keyPressed(java.awt.event.KeyEvent e)
		{
			com.follower.speech.ConversationPlayer playing = conversation;
			if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE && playing != null && playing.isPlaying())
			{
				clientThread.invoke(() -> playing.stop("Stopped."));
				e.consume();
			}
		}
	};

	/** Where the conversation plays: the dialogue box, the chatbox and the overheads. */
	private com.follower.speech.ConversationPlayer.Stage conversationStage()
	{
		return new com.follower.speech.ConversationPlayer.Stage()
		{
			@Override
			public boolean gameDialogueOpen()
			{
				// The game's other boxes over the chatbox too: a message, an
				// item, two items, a level-up. QA2, 15 Oct: a written line
				// was drawn over one and took its "Click here to continue".
				// QA3: and Make-X, the destroy question, the graphic box and
				// a chatbox prompt such as Enter amount.
				return npcDialogOpen()
					|| client.getWidget(net.runelite.api.gameval.InterfaceID.MESSAGEBOX, 0) != null
					|| client.getWidget(net.runelite.api.gameval.InterfaceID.OBJECTBOX, 0) != null
					|| client.getWidget(net.runelite.api.gameval.InterfaceID.OBJECTBOX_DOUBLE, 0) != null
					|| client.getWidget(net.runelite.api.gameval.InterfaceID.LEVELUP_DISPLAY, 0) != null
					|| client.getWidget(net.runelite.api.gameval.InterfaceID.SKILLMULTI, 0) != null
					|| client.getWidget(net.runelite.api.gameval.InterfaceID.CONFIRMDESTROY, 0) != null
					|| client.getWidget(net.runelite.api.gameval.InterfaceID.GRAPHICBOX, 0) != null
					|| client.getVarcIntValue(net.runelite.api.gameval.VarClientID.MESLAYERMODE) != 0;
			}

			@Override
			public boolean talkToOpen()
			{
				return dialog.isOpen();
			}

			@Override
			public void showBox(com.follower.speech.Conversation.Line line)
			{
				String[] pages = dialog.pagesFor(com.follower.speech.Conversation.shown(line));
				com.follower.speech.FollowerDialog.Node node =
					line.speaker == com.follower.speech.Conversation.Speaker.YOU
						? com.follower.speech.FollowerDialog.Node.you(pages)
						: com.follower.speech.FollowerDialog.Node.says(pages);
				// A bubble still up is two mouths at once, as at a Talk-to.
				overlay.clear();
				dialog.showScripted(followerName(), node.face(line.expression.animations()));
			}

			@Override
			public int boxPage()
			{
				return dialog.pageShown();
			}

			@Override
			public String boxPageText()
			{
				return dialog.pageText();
			}

			@Override
			public boolean boxContinued()
			{
				return dialog.wasContinued();
			}

			@Override
			public void continueBox()
			{
				dialog.continueNow();
			}

			@Override
			public void closeBox()
			{
				dialog.close();
			}

			@Override
			public void say(com.follower.speech.Conversation.Line line, long ms)
			{
				if (line.speaker == com.follower.speech.Conversation.Speaker.FOLLOWER)
				{
					// Straight away, over whatever is up, and the floor held
					// for as long as it is.
					speechQueue.clear();
					cutByTheScene = null;
					speakingUntilMs = System.currentTimeMillis() + ms + SPEECH_GAP_MS;
				}
				else
				{
					// Before it lands: the chatbox hands it straight to onChatMessage.
					scriptedChat = com.follower.speech.ConversationPlayer.inChat(line);
				}
				com.follower.speech.ConversationPlayer.sayInChat(client, filmSet, followerName(),
					overlay::show, line, ms);
			}

			@Override
			public void unsay(com.follower.speech.Conversation.Line line)
			{
				if (line.speaker == com.follower.speech.Conversation.Speaker.FOLLOWER)
				{
					overlay.clear();
					return;
				}
				Player me = client.getLocalPlayer();
				if (me != null)
				{
					me.setOverheadCycle(0);
				}
			}

			@Override
			public long readingTimeMs(String text)
			{
				return FollowerPlugin.this.readingTimeMs(text);
			}

			@Override
			public void tell(String text)
			{
				tellTheFilm(text);
			}
		};
	}

	/**
	 * The Share choice: export what the player has written, or take on a
	 * pack. The Messages window's Share button and the panel's Share lines
	 * button both run it; each settles unsaved edits before calling.
	 */
	private void shareLines(java.awt.Component parent)
	{
		Object[] choices = {"Export mine", "Import a pack", "Cancel"};
		int picked = javax.swing.JOptionPane.showOptionDialog(parent,
			"Share the lines you have written, and the sounds attached to them."
				+ "\n\nExporting writes a .zip holding only what you have added,"
				+ "\nnot the lines that ship with the plugin."
				+ "\n\nImporting adds somebody else's to yours. Nothing is replaced.",
			"Share", javax.swing.JOptionPane.DEFAULT_OPTION,
			javax.swing.JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
		// The packs are read and written on the executor: up to 64 MB of
		// sounds held the event thread, and with it the game's input (QA pass 4).
		if (picked == 0)
		{
			java.io.File target = choosePack(true, parent);
			if (target != null)
			{
				executor.execute(() -> exportPack(target, parent));
			}
		}
		else if (picked == 1)
		{
			java.io.File source = choosePack(false, parent);
			if (source != null)
			{
				executor.execute(() -> importPack(source, parent));
			}
		}
	}

	/** A message box, shown on the event thread from wherever it is said. */
	private static void tell(java.awt.Component parent, String message, String title, int kind)
	{
		javax.swing.SwingUtilities.invokeLater(() ->
			javax.swing.JOptionPane.showMessageDialog(parent, message, title, kind));
	}

	/** Share lines from the panel, unless the Messages window holds edits the file does not. */
	private void shareLinesFromPanel()
	{
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			if (messagesDialog != null && messagesDialog.isDirty())
			{
				sendStatus("Save or discard the edits in the Messages window first.");
				return;
			}
			shareLines(panel);
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
		loadOutfitProfile(name, false);
	}

	/**
	 * {@code quiet} is for the profile a session starts in: restoring it on
	 * every start and profile switch is not news, and on a fresh install it was
	 * the first thing the plugin ever said. A profile the player picks says so.
	 */
	private void loadOutfitProfile(String name, boolean quiet)
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
		// And the dropdown, which a profile worn by chat command left on the
		// old one, while the edits after it saved into this one (QA pass 4).
		if (panel != null)
		{
			panel.setProfileNames(profileStore.names(), activeProfile);
		}
		if (!quiet)
		{
			sendStatus("Wearing outfit profile '" + activeProfile + "'.");
		}
	}

	private void setActiveProfile(String name)
	{
		activeProfile = name;
		configManager.setConfiguration(FollowerConfig.GROUP, "activeProfile",
			name == null ? "" : name);
	}

	/**
	 * This RuneLite profile's own outfit profiles, kept in its settings. One
	 * list shared by every profile let two of them on the same name write over
	 * each other's outfit, and a switch put the other profile's on this one.
	 */
	private void loadOutfitProfiles()
	{
		profileStore.load(dataDir, config.outfitProfiles(), config.activeProfile(), config.customOutfit(),
			json -> configManager.setConfiguration(FollowerConfig.GROUP, "outfitProfiles", json));
		outfitListProfileId = currentProfileId();
	}

	/**
	 * The RuneLite profile the outfit list was read for. A switch announces
	 * the new profile's settings before the switch itself, while the list is
	 * still the last profile's; a thrall outfit named only in the new one's
	 * list is not unknown, just not read yet.
	 */
	private long outfitListProfileId = -1;

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
		loadOutfitProfile(wanted, true);
		panel.setProfileNames(profileStore.names(), activeProfile);
	}

	private void saveOutfitProfile(String name)
	{
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		// The thrall looks are worn by name and nothing restores a changed
		// one; New profile... over one would lose it (QA3).
		if (profileStore.isProtected(name))
		{
			sendStatus("'" + name.trim() + "' is one of the built-in looks"
				+ " and cannot be replaced. Pick it and edit it instead.");
			return;
		}
		boolean replacing = profileStore.get(name.trim()) != null;
		profileStore.put(name, config.customOutfit());
		setActiveProfile(name.trim());
		panel.setProfileNames(profileStore.names(), activeProfile);
		sendStatus("Outfit profile '" + activeProfile + (replacing ? "' replaced." : "' created."));
	}

	private void deleteOutfitProfile(String name)
	{
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		if (profileStore.isProtected(name))
		{
			sendStatus("'" + name.trim() + "' is one of the built-in looks"
				+ " and cannot be deleted. Edit it instead.");
			return;
		}
		if (!profileStore.remove(name))
		{
			sendStatus("No outfit profile named '" + name.trim() + "'.");
			return;
		}
		if (name.trim().equals(activeProfile))
		{
			// Fall back to something real rather than leaving the dropdown
			// pointing at a profile that no longer exists.
			loadOutfitProfile(profileStore.firstUserProfile());
		}
		panel.setProfileNames(profileStore.names(), activeProfile);
		sendStatus("Outfit profile '" + name.trim() + "' deleted.");
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
		// Mid-switch the outfit key already belongs to the other RuneLite
		// profile; filing it under this profile's outfit name is how a
		// two-day-old outfit came back (issue #1).
		if (!belongsToProfile(outfitProfileId, currentProfileId()))
		{
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
			sendStatus("Now wearing " + (name == null ? "item " + itemId : name)
				+ " (" + target.name().toLowerCase(Locale.ROOT) + ").");

			// Weapon animations can only be learned by watching a real player
			// hold one (measured: they are not in the cache as data). Say so
			// rather than let an unknown weapon silently stand unarmed.
			if (target == KitType.WEAPON && !stanceLibrary.knows(itemId))
			{
				// The hand-set command is developer-gated, so only offer it to
				// someone who has that switched on - otherwise this reads as
				// advice that does not work.
				sendStatus("No stances learned for that weapon yet. Wield it once, or stand"
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
			String partName = part.name().toLowerCase(Locale.ROOT);
			sendStatus(Character.toUpperCase(partName.charAt(0)) + partName.substring(1)
				+ " style " + (next + 1) + " of " + choices.size() + ".");
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
				sendStatus("Copied colours converted to palette picks. The picker's in charge now.");
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
	private com.follower.appearance.SpotAnimRepository.Entry spawnGraphic()
	{
		int[] ids = parseAnimationIds(config.spawnGraphic());
		if (ids.length == 0)
		{
			return null;
		}
		com.follower.appearance.SpotAnimRepository.Entry fx = spotAnimRepository.get(ids[0]);
		if (fx == null)
		{
			log.debug("Spawn graphic {} is not in the spotanim repository", ids[0]);
		}
		return fx;
	}

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

	/**
	 * Where a prop goes when it is HELD: its wear slot, or the hand for a tool
	 * the game never lets anyone wear. A fishing rod, a net, a harpoon, a rake
	 * have a worn model but no wear position - the game puts them in the hand
	 * for the work animation only - so the follower fished with nothing in
	 * its hands ("Prop 307 has no wearable slot").
	 */
	private KitType handSlot(int itemId)
	{
		KitType slot = resolveSlot(itemId);
		if (slot != null)
		{
			return slot;
		}
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
	private KitType handSlot(int itemId, boolean leftHand)
	{
		ModelRepository.Entry entry = modelRepository.item(itemId);
		if (entry == null || !hasWornModel(entry))
		{
			return null;
		}
		return leftHand ? KitType.SHIELD : KitType.WEAPON;
	}

	private static boolean hasWornModel(ModelRepository.Entry entry)
	{
		for (int[] models : new int[][]{entry.m, entry.f})
		{
			if (models == null)
			{
				continue;
			}
			for (int model : models)
			{
				if (model >= 0)
				{
					return true;
				}
			}
		}
		return false;
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
					{
						readCounters();
					}
					readTimeAway();
					if (sketchBook != null)
					{
						sketchBook.openFor(configManager.getRSProfileKey());
					}
					if (chronicleProgress != null)
					{
						// A new session, perhaps another account: what the book
						// can read is taken stock of afresh, not compared with
						// the last character's.
						chronicleProgress.reset();
					}
					pageNews.clear();
					speechEngine.getContext().clearDailyTallies();
					noteFirstMeeting();
					speechEngine.getContext().setNicknames(ruleLoader.getNicknames());
				speechEngine.getContext().setRegionNames(ruleLoader.getRegionNames());
					readTraits();
					TriggerContext counted = speechEngine.getContext();
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
					speechEngine.dispatchAfterPriming(TriggerEvent.simple(TriggerEvent.Type.LOGIN));

					// The world literally changes under everyone's feet once a
					// week; a scribe keeping maps would notice. Seeded silently
					// on the first ever run, like the personal bests, so a new
					// install does not announce an update that is not one.
					int revision = client.getRevision();
					if (config.lastRevision() > 0 && revision != config.lastRevision())
					{
						speechEngine.dispatchAfterPriming(
							TriggerEvent.simple(TriggerEvent.Type.GAME_UPDATE));
					}
					if (revision != config.lastRevision())
					{
						configManager.setConfiguration(FollowerConfig.GROUP,
							"lastRevision", revision);
					}
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
				if (hotGround != null)
				{
					// The scene is rebuilt from here; its fires are announced
					// again as it loads, so the old set is only ever stale.
					hotGround.clear();
				}
				if (skilling != null)
				{
					// Objects and NPCs dropped around a load are shuffling,
					// not a tree falling; the company ignores them for a beat.
					skilling.sceneLoading();
				}
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
				if (sketching != null)
				{
					sketching.reset();
				}
				if (chronicleProgress != null)
				{
					chronicleProgress.reset();
				}
				pageNews.clear();
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
	private void tearDownScene()
	{
		if (hotGround != null)
		{
			hotGround.clear();
		}
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
		if (skilling != null)
		{
			// The session, the seat and the borrowed tool all belonged to it.
			skilling.reset();
		}
		if (moments != null)
		{
			moments.reset();
		}
		// What was noticed, looked at and hesitated over was all in this
		// scene. No despawn comes for a scene thrown away, so a drop or a star
		// left behind was walked to and named in the next world.
		if (noticing != null)
		{
			noticing.reset();
		}
		if (gaze != null)
		{
			gaze.reset();
		}
		if (will != null)
		{
			will.reset();
		}
		// The conversation too. Left open at a logout it took the presses in
		// the chatbox at the login screen, and at the next login the first
		// step closed it and gave the last session's answer to this one.
		// Closed here, the answer lands in the session it was chosen in, and
		// what it says goes with the scene, as every line in flight does.
		dialog.close();
		// Same for the minute-taking: the meeting went with the scene.
		endMinutes(false);
		// And the film. Its window stays open, and the next press in it
		// starts the scene again in the new one. The window says so, or it
		// would go on saying a scene is under way while the follower is back
		// to its own business.
		if (filming)
		{
			tellTheFilm("Posing ended when you left the world. Press anything here to start again.");
		}
		endFilming();
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
		captureFallback.abort();
		follower.despawn();
		appearanceService.invalidate();
		overlay.clear();
		// The book shuts with the scene, as the game's own windows do. Left
		// open at a logout it was not drawn, but it still took the presses
		// where it had been, over the login screen's buttons.
		chronicleWindow.close();
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
		// A forced cutscene ends with the scene, as a real one does. Left on,
		// it held the follower still through logouts and hops with nothing
		// to say so.
		cutsceneForced = false;
		sceneOn = false;
		sceneGoneTicks = 0;
		quietUntilTick = 0;
		speechEngine.setSceneQuiet(false);
		speechEngine.setInCutscene(false);
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
		// The force hold goes with the commands that set it: once they are
		// off, ::follower force cutscene off is refused, and nothing else
		// would ever let it go.
		if ("developerMode".equals(key) && !config.developerMode())
		{
			cutsceneForced = false;
		}
		if ("muted".equals(key) && panel != null)
		{
			panel.setMuted(config.muted());
		}
		if (panel != null)
		{
			if ("chattiness".equals(key))
			{
				panel.setChattiness(config.chattiness());
			}
			if ("mirrorToChat".equals(key))
			{
				panel.setMirrorToChat(config.mirrorToChat());
			}
			if ("errandsEnabled".equals(key))
			{
				panel.setErrandsEnabled(config.errandsEnabled());
			}
			if ("followerName".equals(key))
			{
				panel.setFollowerName(followerName());
			}
			if ("developerMode".equals(key))
			{
				panel.setCommands(commandLines());
			}
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
			{
				sendStatus(unknownProfileMessage(name));
			}
			if (panel != null)
			{
				panel.setThrallProfiles(config.thrallMeleeProfile(),
					config.thrallRangedProfile(), config.thrallMagicProfile());
			}
		}
		if ("thrallOnly".equals(key))
		{
			// Turned off, the follower comes back now and in its own outfit:
			// the mode's login built it no model, and its exit left the
			// thrall's behind. Turned on, the same rebuild hides it.
			clientThread.invoke(() ->
			{
				appearanceService.invalidate();
				rebuildFollower();
			});
		}
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
	private void applyFollowEnabled(boolean wantFollow)
	{
		if (followToggleNeedsAction(wantFollow, parkedByPlayer))
		{
			if (wantFollow)
			{
				followAgain();
			}
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
	private void parkedByThePlayer(WorldPoint sentTo)
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
		{
			return null;
		}
		String[] parts = kept.split(",");
		if (parts.length != 3)
		{
			return null;
		}
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
		{
			return null;
		}
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
		{
			return kept;
		}
		if (!parkedByPlayer)
		{
			return "";
		}
		if (waitingToPark || stayTile == null)
		{
			return kept;
		}
		return stayTile.getX() + "," + stayTile.getY() + "," + stayTile.getPlane();
	}

	/** Once a tick, after the park is kept: records where it holds, for the next spawn. */
	private void rememberThePark()
	{
		String kept = config.parkedAt();
		String now = parkToKeep(follower.isSpawned(), parkedByPlayer, parkWhenFree,
			follower.isStaying() ? follower.getStayTile() : null, kept);
		if (!now.equals(kept))
		{
			configManager.setConfiguration(FollowerConfig.GROUP, "parkedAt", now);
		}
	}

	/** The player said Follow: the park is over, whoever had the feet. */
	private void followAgain()
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
	private void keepThePlayersPark()
	{
		if (parkWhenFree && !feetBorrowed())
		{
			parkWhenFree = false;
			if (!follower.isStaying()
				&& (sentWhenFree == null || !follower.stayAt(sentWhenFree)))
			{
				follower.stayHere();
			}
			sentWhenFree = null;
		}
		if (!parkWhenFree && !follower.isStaying())
		{
			parkedByPlayer = false;
		}
	}

	/**
	 * Whether something of the follower's own has its feet: thrall work, an
	 * errand, the spectator, a planted emote, the minutes, the seat, a moment
	 * or a write-up. Each hands them back itself when it is done.
	 */
	private boolean feetBorrowed()
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
	private boolean errandBusy()
	{
		return dialog.isOpen() || follower.isNpcSlaved() || minutesState != 0
			|| (skilling != null && skilling.blocksErrands())
			|| (moments != null && moments.holdsTheFeet())
			|| parkedByPlayer || filming
			|| (sketching != null && sketching.isSketching())
			|| inCutscene() || follower.isDoingAgility()
			|| System.currentTimeMillis() - lastAgilityXpMs < ERRANDS_AFTER_AGILITY_MS;
	}

	/**
	 * Agility XP this recently keeps errands off: on a course the run back
	 * to the first obstacle is not agility, and an errand begun there turns
	 * the watch off and misses the first climb.
	 */
	private static final long ERRANDS_AFTER_AGILITY_MS = 60_000;
	private long lastAgilityXpMs;

	/**
	 * What beats the seat. Thrall work and the spectator, over their whole
	 * ownership window - the exit flourish included - and a park the player
	 * gave, which no work nearby takes the follower off. And a cutscene: a
	 * seat taken again after the scene's catch-up walked it back and forth.
	 */
	private boolean seatFeetTaken()
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
	private boolean momentFeetTaken()
	{
		return thrallBusiness() || (spectate != null && spectate.holdsTheFeet())
			|| (errands != null && errands.isBusy()) || filming
			|| (sketching != null && sketching.isSketching())
			|| sceneQuiet() || follower.isDoingAgility();
	}

	/**
	 * What the spectator keeps out of the way for: thrall work and its whole
	 * exit, an errand in progress, a scene being filmed, and a cutscene, a
	 * boss's intro among them. A park the player gave is handed to it apart:
	 * the fight is still announced at both ends, but watched from the post.
	 */
	private boolean spectateBusy()
	{
		return thrallBusiness() || (errands != null && errands.isBusy()) || filming || inCutscene()
			|| follower.isDoingAgility();
	}

	/**
	 * The side panel's Stay here / Follow me button: writes Follow me, the
	 * one record of follow versus stay. A Send parks the follower without
	 * touching the setting, so the write can be a no-op that raises no
	 * change event; then the move is applied here instead.
	 */
	private void followFromPanel(boolean enabled)
	{
		if (config.followEnabled() == enabled)
		{
			clientThread.invoke(() -> applyFollowEnabled(enabled));
			return;
		}
		setFollowEnabled(enabled);
	}

	/** The answer to a profile name nothing is saved under; the thrall settings share it. */
	private String unknownProfileMessage(String name)
	{
		return "No outfit profile named '" + name + "'. Saved: "
			+ (profileStore.names().isEmpty()
			? "(none)" : String.join(", ", profileStore.names()));
	}

	/** Whether the follower is parked by the player: Follow me off, a right-click Stay or a Send. */
	public boolean isStaying()
	{
		return stayReported;
	}

	/** Called on the client thread as the player-commanded stay starts or ends. */
	public void setOnStayStateChanged(java.util.function.Consumer<Boolean> listener)
	{
		onStayStateChanged = listener;
	}

	/** The region the player stands in, the id the Locations rules use; -1 before the first tick. */
	public int currentRegionId()
	{
		return lastRegionId;
	}

	/** Restores exact palette pairs saved by ::follower palette, format "find>replace;...". */
	private void loadExactPalette()
	{
		String stored = config.exactPalette();
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
		if (!config.groupReactions())
		{
			disabled.add("reactions");
		}
		// Copying agility, the follower falls after the player does, and
		// agility-fell says so once it has. fail-slip, on the player's own
		// 'You slip', would make that two remarks about one fall.
		if (config.copiesAgility())
		{
			disabled.add("fail-slip");
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

		// The prop is skipped in thrall mode, whose override is a shared object
		// and whose conscript has no time to read. So are the film's pose's
		// hands, which stand in for the emote disarm while they are on.
		int[] filmHands = outfitOverride == null && filmSet != null ? filmSet.hands() : null;
		// Nor a prop still behind its reach: it comes in at the bottom of it.
		boolean propShown = outfitOverride == null && !propBehindReach;
		Outfit outfit = dressForRender(resolveOutfit(), spectateDisarmed || emoteDisarmed && filmHands == null,
			propShown ? propSlot : null, propItemId,
			outfitOverride == null ? wornSlot : null, wornItemId);
		dressForPose(outfit, propShown ? propHands : null);
		dressForPose(outfit, outfitOverride == null ? emoteHands : null);
		dressForPose(outfit, outfitOverride == null && emoteHands == null ? loopHands : null);
		dressForPose(outfit, outfitOverride == null ? follower.getCrossingHands() : null);
		dressForPose(outfit, filmHands);

		appearanceService.request(outfit, com.follower.appearance.ModelSource.DUMP_ONLY, appearance ->
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

			// Stances follow the weapon the FOLLOWER holds, not the one we hold.
			follower.setWeapon(outfit.isItem(KitType.WEAPON)
				? outfit.itemId(KitType.WEAPON)
				: com.follower.follower.StanceLibrary.UNARMED);
			// A staff is swung one-handed when the other hand holds a shield.
			followerHoldsShield = outfit.isItem(KitType.SHIELD);


			if (follower.isSpawned())
			{
				follower.setAppearance(appearance);
			}
			else
			{
				if (thrallNpc != null)
				{
					// Materialising as the thrall (thrall-only mode): on the
					// thrall's tile, with the thrall's own burst and no arrival
					// puff or spawn animation of its own.
					follower.setSpawnAnimation(new int[0]);
					WorldPoint at = thrallNpc.getWorldLocation();
					follower.spawn(appearance, at != null ? at : local.getWorldLocation(), null, 0);
					return;
				}
				follower.setSpawnAnimation(parseAnimationIds(config.spawnAnimation()));

				// Arrive on a free tile behind the player rather than inside
				// them, or back on the post it was left waiting on.
				WorldPoint post = config.followEnabled() ? null
					: parkToReturnTo(config.parkedAt(), local.getWorldLocation());
				WorldPoint behind = post != null ? post : follower.restingTileBehind(local, 2);
				follower.spawn(appearance, behind != null ? behind : local.getWorldLocation(),
					spawnGraphic(), config.spawnGraphicHeight(), post != null);

				// The very first spawn ever is the one moment naming is cheap
				// and natural. THIS is the spawn a login takes - the queued
				// rebuild - not the bare auto-spawn fallback below in the tick
				// loop, which is why the countdown is armed in both places.
				if (!config.namePrompted())
				{
					namePromptDelayTicks = NAME_PROMPT_DELAY_TICKS;
				}
				maybeSendCommandHint();

				// Spawned with Follow me off: parked from the first tile, not
				// following until somebody notices the toggle.
				if (!config.followEnabled())
				{
					follower.stayHere();
					parkedByThePlayer(null);
				}
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
		if (modelRepository.isLoaded() && slotIndex.isEmpty())
		{
			// An index that came back empty on an earlier pass, from a login
			// that beat the item stats to it.
			buildSlotIndexAsync();
		}
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

	/**
	 * A thing in each hand instead, left then right, as an animation names
	 * them - the quill and the scroll of the writing pose - or null. Dressed
	 * as the film dresses a pose's hands.
	 */
	private int[] propHands;

	/** A prop taken out but not yet shown: the reach for it is still going down. */
	private boolean propBehindReach;

	/** How far into the reach the prop comes into the hand: the bottom of the bend. */
	static final double REACH_BOTTOM = 0.5;

	/** The second transient prop, worn rather than held: a borrowed hat. */
	private KitType wornSlot;
	private int wornItemId;

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
		String warning = errors.isEmpty() ? null : "Outfit warnings: " + String.join("; ", errors);
		if (warning != null && !warning.equals(lastOutfitWarning))
		{
			sendStatus(warning);
		}
		lastOutfitWarning = warning;
		return outfit;
	}

	/**
	 * The outfit warning last told, and whether a failed build has been. Every
	 * settings change and outfit edit rebuilds the follower, so one bad outfit
	 * string repeated its warning on each unrelated tweak (QA, 15 Oct). Told
	 * again only when it changes, or comes back after a clean build.
	 */
	private String lastOutfitWarning;
	private boolean buildFailureTold;

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
			outfit.clear(KitType.WEAPON);
			outfit.clear(KitType.SHIELD);
		}
		if (propSlot != null)
		{
			// A prop in the weapon hand takes the shield with it. Nobody reads
			// a scroll or swings a borrowed axe with a shield still strapped on,
			// and the hand the prop goes in is the one the shield argues with.
			if (propSlot == KitType.WEAPON)
			{
				outfit.clear(KitType.SHIELD);
			}
			outfit.setItem(propSlot, propItemId);
		}
		if (wornSlot != null)
		{
			outfit.setItem(wornSlot, wornItemId);
		}
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
	 * @param hands from {@link com.follower.follower.FilmSet#hands()}, or null
	 *              to leave the outfit as it is
	 */
	private void dressForPose(Outfit outfit, int[] hands)
	{
		if (hands == null)
		{
			return;
		}
		dressHand(outfit, hands[0], true);
		dressHand(outfit, hands[1], false);
	}

	private void dressHand(Outfit outfit, int item, boolean leftHand)
	{
		if (item == com.follower.follower.FilmSet.KEEP)
		{
			return;
		}
		KitType slot = item >= 0 ? handSlot(item, leftHand) : null;
		if (slot == null)
		{
			outfit.clear(leftHand ? KitType.SHIELD : KitType.WEAPON);
			return;
		}
		outfit.setProp(slot, item);
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

	/** A copy waiting on the follower to walk over to the work first. */
	private int pendingMime;

	/** Whether the waiting copy is a one-shot - a hammer blow - rather than a held loop. */
	private boolean pendingOneShot;

	/** Whether the copy under way is of the work, and so lasts only while beside it. */
	private boolean copyAtWork;

	/**
	 * What a held emote loop names for the hands (Explore's sextant), kept
	 * apart from emoteHands, which its entry clip's end takes off.
	 */
	private int[] loopHands;

	private void startPoseMirror(int animationId)
	{
		mirroredPose = animationId;
		clientThread.invoke(() -> follower.setPoseOverride(animationId));
		// Not a copy of the work, which keeps to the tools it borrowed.
		loopHands = copyAtWork ? null : follower.emoteHandItems(animationId);
		boolean disarmed = emoteDisarmed;
		refreshEmoteDisarm(follower.isEmotePlaying());
		if (loopHands != null && disarmed == emoteDisarmed)
		{
			rebuildFollower();
		}
	}

	private void stopPoseMirror()
	{
		pendingMime = 0;
		copyAtWork = false;
		if (skilling != null)
		{
			// Whatever it walked over for is finished; back to the seat.
			skilling.dropTheJoin();
		}
		if (mirroredPose == 0)
		{
			return;
		}
		int held = mirroredPose;
		mirroredPose = 0;
		// Only its own: a moment that stepped in may have put a pose of its own on.
		clientThread.invoke(() -> follower.clearPoseOverride(held));
		boolean handsBack = loopHands != null;
		loopHands = null;
		boolean disarmed = emoteDisarmed;
		refreshEmoteDisarm(follower.isEmotePlaying());
		if (handsBack && disarmed == emoteDisarmed)
		{
			rebuildFollower();
		}
	}

	/** How long a rule's gesture said on the move waits for the follower to stand. */
	static final int EMOTE_WAIT_TICKS = 3;

	private void updateEmoteDisarm(boolean emoting)
	{
		if (!emoting && emoteHold)
		{
			emoteHold = false;
			follower.resumeFollowing();
		}
		if (!emoting && filmSet != null)
		{
			// A one-shot of the film's is over: its props come off.
			filmSet.onceOver();
		}
		// The emote's own things go with it, in the same rebuild as the
		// weapon coming back when there is one.
		boolean handsBack = !emoting && emoteHands != null;
		emoteHands = emoting ? emoteHands : null;
		clipKeepsHands = emoting && clipKeepsHands;
		boolean disarmed = emoteDisarmed;
		refreshEmoteDisarm(emoting);
		if (handsBack && disarmed == emoteDisarmed)
		{
			rebuildFollower();
		}
	}

	/**
	 * What the emote playing names for each hand, left then right, or null.
	 * The game draws an animation's own hand items whatever is worn: the
	 * charting quill and scroll, the home teleport's chalk stick, a teleport
	 * scroll, a smith's hammer. Dressed as the film dresses a pose.
	 */
	private int[] emoteHands;

	/**
	 * Clips that keep both hands as they are, as the game plays them: the
	 * block keeps weapon and shield up, the Ancient and Arceuus teleports the
	 * staff. Not the mood idles, keep-handed too, where a kiteshield would
	 * cross the face.
	 */
	private static final Set<Integer> KEEPS_HANDS = Set.of(
		net.runelite.api.gameval.AnimationID.HUMAN_UNARMEDBLOCK,
		net.runelite.api.gameval.AnimationID.ZAROS_VERTICAL_CASTING,
		net.runelite.api.gameval.AnimationID.HUMAN_SPELLCAST_ARCEUUS_TELEPORT);

	/** Whether the emote clip on now is one of those. */
	private boolean clipKeepsHands;

	/** Each clip of an emote, as it goes on: dresses the hands it names. */
	private void dressEmoteHands(int clip)
	{
		if (clipKeepsHands != KEEPS_HANDS.contains(clip))
		{
			clipKeepsHands = !clipKeepsHands;
			refreshEmoteDisarm(follower.isEmotePlaying());
		}
		int[] hands = thrallNpc == null ? follower.emoteHandItems(clip) : null;
		if (java.util.Arrays.equals(hands, emoteHands))
		{
			return;
		}
		emoteHands = hands;
		rebuildFollower();
	}

	/**
	 * Empties the follower's hands while it is emoting AT ALL - a one-shot copy
	 * or a held pose. Both are emotes as far as a player is concerned, so both
	 * put the weapon away. Not the film's poses, which hold what the game's own
	 * animation names for each hand, or keep what is there.
	 */
	private void refreshEmoteDisarm(boolean emoting)
	{
		boolean wanted = (emoting && !clipKeepsHands || mirroredPose != 0
			|| skilling != null && skilling.worksBareHanded()) && thrallNpc == null
			&& (filmSet == null || !filmSet.isPosing());
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
		if (!replacesThralls(config.thrallMode(), config.thrallOnly()) || npc == thrallNpc)
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
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return;
		}
		for (NPC npc : view.npcs())
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
		announceSummon(style);
		maybeSendThrallHint();
	}

	/**
	 * Replace thralls is on by default, so a player who wanted their own
	 * thrall is told once, at the first summon taken, how to have it back.
	 */
	private void maybeSendThrallHint()
	{
		// Thrall only replaces them too, so with it ticked the hint names both.
		if (!replacesThralls(config.thrallMode(), config.thrallOnly()) || config.thrallHintShown())
		{
			return;
		}
		configManager.setConfiguration(FollowerConfig.GROUP, "thrallHintShown", true);
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
	private void announceSummon(String style)
	{
		speechEngine.setMuted(config.muted() || wasThieving
			|| absentUntilSummoned(config.thrallOnly(), thrallBusiness()) || thrallQuiet());
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

	/** Whether the follower's current outfit has something in the shield slot. */
	private boolean followerHoldsShield;

	/**
	 * What the follower swings, matched to what it is actually holding: its
	 * own weapon's swing for the kind of blow the player's style deals, when
	 * that is known, else a generic swing for the style. A scimitar should not
	 * slash like a godsword just because both are melee.
	 */
	private int thrallAttackAnimation()
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
	static int thrallSwing(com.follower.follower.StanceLibrary library, String thrallStyle,
		int weapon, int weaponCategory, int style, boolean shield)
	{
		int kind = library.attackKind(weaponCategory, style);
		return thrallSwing(thrallStyle, library.castFor(weapon),
			com.follower.follower.StanceLibrary.withShield(
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
		{
			return cast > 0 ? cast : GENERIC_MAGIC_ATTACK;
		}
		if (swing > 0)
		{
			return swing;
		}
		return "melee".equals(thrallStyle) ? GENERIC_MELEE_ATTACK : GENERIC_RANGED_ATTACK;
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
	private boolean shouldHide()
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
	private boolean thrallBusiness()
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

	private boolean thrallQuiet()
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
	private void watchForAgility()
	{
		if (crossingWatch == null)
		{
			return;
		}
		agilityFramesThisTick++;
		GameState state = client.getGameState();
		if (state == GameState.LOADING)
		{
			// A rebuild: the recording carries on after it, in world units.
			return;
		}
		Player local = client.getLocalPlayer();
		boolean watching = config.copiesAgility() && local != null && state == GameState.LOGGED_IN
			&& follower.isSpawned() && !parkedByPlayer && !thrallBusiness() && !filming
			&& (errands == null || !errands.isBusy())
			&& (sketching == null || !sketching.isSketching());
		if (!watching)
		{
			crossingWatch.reset();
			if (follower.isDoingAgility() || follower.isHoldingForAgility())
			{
				follower.clearCrossings();
			}
			return;
		}
		if (inCutscene())
		{
			crossingWatch.reset();
			return;
		}
		crossingWatch.sample(com.follower.follower.CrossingWatch.Sample.read(local,
			System.currentTimeMillis()));
	}

	/** The agility trace, written while Developer commands is on. */
	private final com.follower.follower.AgilityTrace agilityTrace = new com.follower.follower.AgilityTrace();

	/** Agility XP, and the local player's last hitsplat type, since the last tick line. */
	private int agilityXpThisTick;
	private int agilityHitsplatThisTick = -1;

	/** Where the player was drawn at the last tick line, for its moving column. */
	private net.runelite.api.coords.LocalPoint agilityTraceLast;

	/** Frames drawn since the last game tick, and when that was: the trace's fps, which the glide depends on. */
	private int agilityFramesThisTick;
	private long agilityTickMs;

	/** An odd pose or movement this recently still counts as agility about, for the trace. */
	private static final long AGILITY_TRACE_NEAR_MS = 3000;

	/**
	 * Once a game tick: a line of the agility trace, when Developer commands
	 * is on and there is agility about - something watched that has moved
	 * the player (not a skilling loop or an emote), a crossing queued, the
	 * follower holding for one, agility XP this tick, or an odd pose or move
	 * in the last few seconds. Nothing at all otherwise, so a day's file is
	 * the laps and little else.
	 */
	private void traceAgilityTick()
	{
		agilityTrace.setEnabled(config.developerMode());
		int xp = agilityXpThisTick;
		int hit = agilityHitsplatThisTick;
		agilityXpThisTick = 0;
		agilityHitsplatThisTick = -1;
		long now = System.currentTimeMillis();
		int fps = agilityTickMs > 0 && now > agilityTickMs
			? (int) Math.round(agilityFramesThisTick * 1000.0 / (now - agilityTickMs)) : 0;
		agilityFramesThisTick = 0;
		agilityTickMs = now;
		Player local = client.getLocalPlayer();
		if (!agilityTrace.isEnabled() || local == null || crossingWatch == null)
		{
			return;
		}
		net.runelite.api.coords.LocalPoint at = local.getLocalLocation();
		boolean moving = at != null && agilityTraceLast != null && !at.equals(agilityTraceLast);
		agilityTraceLast = at;
		if (crossingWatch.isWatchingMovement() || follower.isDoingAgility() || follower.isHoldingForAgility()
			|| xp > 0 || crossingWatch.wasOddWithin(now, AGILITY_TRACE_NEAR_MS))
		{
			int graphic = -1;
			if (local.getSpotAnims() != null)
			{
				for (net.runelite.api.ActorSpotAnim spotAnim : local.getSpotAnims())
				{
					graphic = spotAnim.getId();
					break;
				}
			}
			agilityTrace.tick(String.format("t%d %dfps | %s pose %d anim %d gfx %d %s xp+%d hit %s | %s | %s q%d %s",
				client.getTickCount(), fps,
				com.follower.follower.CrossingWatch.describe(local.getWorldLocation()),
				local.getPoseAnimation(), local.getAnimation(), graphic, moving ? "moving" : "still",
				xp, hit < 0 ? "-" : String.valueOf(hit),
				crossingWatch.describe(now),
				com.follower.follower.CrossingWatch.describe(follower.getWorldLocation()),
				follower.getCrossingsQueued(), follower.describeAgility()));
		}
		agilityTrace.flushIfDue();
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		appearanceService.tick();
		colorHarvester.tick();
		// Before the follower's frame: a crossing seen this frame holds it
		// before the teleport rule can snap it after a player on the roof.
		watchForAgility();
		follower.updateFrame();
		shiftHoverTooltip();
		if (filmSet != null)
		{
			// A held film pose's props come off for a walk and back on after it.
			filmSet.frame();
		}

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
			context.hasIncident() ? context.getIncidentPhrase() + context.getMemoryWhere()
				+ ", " + context.getMemoryWhen(System.currentTimeMillis()) : null);
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
			// A scribe does not round 95 minutes down to "1 hour" - the
			// half hour happened and it was there for it.
			int hours = minutes / 60;
			int rest = minutes % 60;
			parts.add(hours == 0
				? minutes + (minutes == 1 ? " minute" : " minutes")
				: hours + (hours == 1 ? " hour" : " hours")
					+ (rest == 0 ? "" : " and " + rest
						+ (rest == 1 ? " minute" : " minutes")));
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
				pages.add("I've had better days, if I'm honest.");
				break;
			case "down":
				pages.add("Not our finest, but we're still walking.");
				break;
			case "good":
				pages.add("A good day, that. I'd take another like it.");
				break;
			case "high":
				pages.add("One of the good ones. I mean that.");
				break;
			default:
				pages.add("An ordinary day. There's nothing wrong with those.");
				break;
		}

		// And the one thing it has not let go of.
		if (incident != null && !incident.isEmpty())
		{
			pages.add("I'm still thinking about " + incident + ".");
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
		// A conversation playing has the box; Talk-to waits for its end.
		if (conversation != null && conversation.isPlaying())
		{
			tellTheFilm("Talk-to is off until the scene is over.");
			return;
		}
		// Opening the box is presence too, even before anything is chosen.
		speechEngine.getContext().noteAttention();
		// A bubble still up when the box opens is two mouths at once; the
		// queue is likewise held while the box is open (see drainSpeechQueue).
		overlay.clear();
		String asked = speechEngine.getContext().getAskedTree();
		com.follower.speech.DialogTree tree = dialogLoader.get(asked);
		if (tree != null)
		{
			dialog.startNextTick(followerName(),
				com.follower.speech.FollowerDialog.build(tree, this::answerQuestion, speechEngine::fill),
				tree.startId());
			return;
		}
		dialog.startNextTick(followerName(),
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
		TriggerContext context = speechEngine.getContext();
		String tree = context.getAskedTree();
		// The nickname is the one question whose answer changes the follower's
		// own state rather than the rules'; settled before the rules hear, so
		// the thank-you can say the name.
		if (NICKNAME_TREE.equals(tree))
		{
			if ("yes".equals(answer))
			{
				context.acceptNickname(context.getEarnedNickname());
			}
			else if ("no".equals(answer))
			{
				context.refuseNickname(context.getEarnedNickname());
			}
		}
		context.noteAnswered();
		speechEngine.dispatch(TriggerEvent.answered(answer, tree));
	}

	/** The tree in dialogs.json that asks whether the earned name may be used. */
	static final String NICKNAME_TREE = "nickname-offer";

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

		// While a wish is open the five-slot box retires who-are-you, not the
		// small talk: who-a stays reachable from the returning hub later, but
		// the jokes and advice are the perishable first-hour charm, and a
		// 45-minute wish window used to wall them off from the opening screen.
		script.put("start", wishing
			? says("Yes?").choices(
				"What is it you actually do?", "do-q",
				howLabel, howTarget,
				giftLabel, "gift-q",
				"Let's just talk.", "chat-q",
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
				// Second page, and it earns its click: nothing is consumed,
				// so the player walks away still holding the thing they just
				// handed over, and a handover that leaves the bag untouched
				// reads as the gift not having registered. The follower
				// trading its worn one back is the honest account of what the
				// bag will show - the count does not move because a swap does
				// not move it - and it is truer to a scribe than pretending
				// to pocket something it cannot pocket.
				script.put("gift-a", says(
					"Let's see it, then. ...That's the one.",
					"Take my old one in trade. Still good for your crafting,"
						+ " so your bag comes out even.")
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
		// Retired entirely while a wish is open: neither hub offers it then
		// (the five-slot cap spends its seat on the gift), and a node nothing
		// leads to is dialogue nobody will ever read - the reachability test
		// says exactly that.
		if (!wishing)
		{
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
				"Whatever you've been calling me. I'd have picked my own, but nobody asked.",
				"It's grown on me.")
				.then("who-menu"));

			script.put("who-tired-q", you("Don't you get tired of following me?").then("who-tired-a"));
			script.put("who-tired-a", says(
				"Tired? I once watched you stand at a furnace for three hours.",
				"Nothing has tired me since.")
				.then("who-menu"));
		}

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
			"One tile behind. Corners, doorways, stairs, running: I keep up.",
			"Where you go I go, teleports included. Same swirl, half a step later.")
			.then("do-follow-b"));
		script.put("do-follow-b", says(
			"Tell me to Stay and I'll hold the spot until you want me back.",
			"Or hold Shift, right-click a tile and pick Send. I'll walk there myself.")
			.then("do-menu"));

		script.put("do-fight-q", you("What do you do when I'm fighting?").then("do-fight-a"));
		script.put("do-fight-a", says(
			"Get out of the way, mainly. Then watch.",
			"If something enormous is near, I mention it first. For all the good that does.")
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
			"Same walking, same swinging, far better dressed.")
			.then("do-thrall-b"));
		script.put("do-thrall-b", says(
			"Three sorts, mind. Zombie, skeleton and ghost, for melee, ranged and magic.")
			.then("do-thrall-c"));
		script.put("do-thrall-c", says(
			"Set me an outfit for each in the settings.",
			"Otherwise I turn up to a mage fight holding a greataxe and we both look foolish.")
			.then("do-menu"));

		script.put("do-work-q", you("And when I'm working?").then("do-work-a"));
		script.put("do-work-a", says(
			"Depends what you're at.",
			"If it's pockets, I give you room and watch the street until you're done.")
			.then("do-work-b"));
		script.put("do-work-b", says(
			"Trees, rocks, fishing spots. I set up beside you and do a bit myself.",
			"Now and then I wander off on an errand of my own. I always come back.")
			.then("do-menu"));

		script.put("do-emote-q", you("Can you dance?").then("do-emote-a"));
		script.put("do-emote-a", says(
			"Ask and find out. Right-click me for a wave or a dance, whatever the moment wants.",
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
			"Anyone who dresses their follower this well has taste.",
			"The rest of your wardrobe I couldn't comment on.")
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
			"A stranger offering to trim your armour is no barber.")
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

		// Over an interface drawn on top of the follower - the bank, the
		// inventory, the chatbox - the menu is the interface's.
		if (!offersWalkHere(event.getMenuEntries()))
		{
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
				|| !followerTarget().equals(entry.getTarget()))
			{
				kept.add(entry);
			}
		}
		client.getMenu().setMenuEntries(kept.toArray(new net.runelite.api.MenuEntry[0]));

		// Examine sits where a real NPC's does: below Walk here, above Cancel -
		// index 1 in the bottom-up entry array (Cancel is index 0).
		client.getMenu().createMenuEntry(1)
			.setOption("Examine")
			.setTarget(followerTarget())
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

		addFollowerMenuEntry("Dance", () -> follower.playAnimation(866));
		addFollowerMenuEntry("Wave", () -> follower.playAnimation(863));
		addFollowerMenuEntry("Step-in-front", () ->
		{
			Player local = client.getLocalPlayer();
			if (local == null)
			{
				return;
			}
			if (!follower.moveToFront(local))
			{
				speak("There's no room in front of you for me to stand.",
					com.follower.speech.SpeechOutput.OVERHEAD, null, -1, null);
			}
		});
		// Only where a page has a plate for the place, so the option showing
		// is itself the sign a sketch can be made here; not over one already
		// under way, nor in a scene being filmed.
		if (sketching != null && !filming && sketching.canSketchNow())
		{
			addFollowerMenuEntry("Sketch", this::askForSketch);
		}
		addFollowerMenuEntry("Read-chronicle", chronicleWindow::openNextTick);
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
	private String talkOption()
	{
		return speechEngine.getContext().getAskedTree().isEmpty()
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
	private void addSendFollowerEntry(net.runelite.api.events.MenuOpened event)
	{
		if (!client.isKeyPressed(net.runelite.api.KeyCode.KC_SHIFT))
		{
			return;
		}

		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return;
		}
		net.runelite.api.Tile sceneTile = view.getSelectedSceneTile();
		if (sceneTile == null)
		{
			return;
		}

		if (!offersWalkHere(event.getMenuEntries()))
		{
			return;
		}

		WorldPoint target = sceneTile.getWorldLocation();
		client.getMenu().createMenuEntry(1)
			.setOption("Send")
			.setTarget(followerTarget())
			.setType(net.runelite.api.MenuAction.RUNELITE)
			.onClick(e ->
			{
				showRedCross();
				clientThread.invoke(() -> sendTo(target));
			});
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
	static boolean offersWalkHere(net.runelite.api.MenuEntry[] entries)
	{
		if (entries == null)
		{
			return false;
		}
		for (net.runelite.api.MenuEntry entry : entries)
		{
			if (entry != null && entry.getType() == net.runelite.api.MenuAction.WALK)
			{
				return true;
			}
		}
		return false;
	}

	/** The Send op's move, on the client thread. */
	private void sendTo(WorldPoint target)
	{
		if (filming)
		{
			// Straight there, with no beat before it: the scene has a
			// director. A refusal goes to the window, out of the shot.
			if (!follower.stayAt(target))
			{
				tellTheFilm("It can't get to that spot from here.");
				return;
			}
			parkForTheFilm(follower.getStayTile());
			filmSet.reaim();
			return;
		}
		// Through the will, which stops at an edge it has misgivings
		// about before going on.
		boolean sent = will != null ? will.send(target) : follower.stayAt(target);
		if (!sent)
		{
			speak("I can't get to that spot from here.",
				com.follower.speech.SpeechOutput.OVERHEAD, null, -1, null);
		}
		else
		{
			// Where the Send ends, not where it pauses: let go by something
			// that had the feet, the pause is over, and the follower parked on
			// the edge stayed three tiles short of a monster for good.
			parkedByThePlayer(will != null ? will.endsAt() : follower.getStayTile());
		}
	}

	/**
	 * Right-click Sketch and ::follower sketch, on the client thread. A
	 * refusal is one line over its head, as Step-in-front's is; in a scene
	 * being filmed it goes to the chat instead, out of the shot. Asked again
	 * mid-sketch, as only the command can be, nothing is said: the sketch
	 * under way is the answer, and a refusal held through the count was said
	 * after the picture, as if the sketch just taken had been refused.
	 */
	private void askForSketch()
	{
		if (sketching == null || sketching.isSketching())
		{
			return;
		}
		String refused = sketching.askForSketch();
		if (refused == null)
		{
			return;
		}
		if (filming)
		{
			sendStatus(refused);
		}
		else
		{
			speak(refused, com.follower.speech.SpeechOutput.OVERHEAD, null, -1, null);
		}
	}

	/**
	 * A line of the sketch's count shows at least this long, and is gone
	 * before the next, so the count reads as a count.
	 */
	private static final long SKETCH_COUNT_MS = 1_100L;

	/**
	 * The sketch's count, straight over the follower's head: asked for, so
	 * the mute does not hold it back, and holding the floor as a line does,
	 * so nothing queued lands on it. Null clears it, for the picture.
	 */
	private void sketchCount(String line)
	{
		if (line == null)
		{
			overlay.clear();
			return;
		}
		// No count runs in a scene, which puts the scroll away: what would be
		// said is the word for the sketch broken off, over the scene.
		if (inCutscene())
		{
			return;
		}
		long showFor = Math.min(MAX_SPEECH_MS, Math.max(SKETCH_COUNT_MS,
			(long) line.length() * 1000L / Math.max(1, config.readingSpeed())));
		overlay.show(line, showFor);
		speakingUntilMs = System.currentTimeMillis() + showFor + SPEECH_GAP_MS;
	}

	/**
	 * The sketch's word after the picture, through the engine so the mute
	 * and the output setting hold, and next in line. "There." still has the
	 * floor when it comes, and queued behind the lines held through the pose
	 * it was said seconds late, after some unrelated remark, or dropped with
	 * three waiting. The queue is emptied for the moment it goes in, and
	 * what waited goes back in behind it, in its order.
	 */
	private void sketchSaysNext(String line)
	{
		List<Utterance> waiting = new ArrayList<>(speechQueue);
		speechQueue.clear();
		speechEngine.remark(line);
		speechQueue.addAll(waiting);
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
		if (conversation != null)
		{
			conversation.update(System.currentTimeMillis());
		}
		if (gaze != null)
		{
			gaze.noteHover(com.follower.follower.Gaze.read(client, follower));
		}

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
		{
			return;
		}

		addFollowerMenuEntry(talkOption(), this::startTalking);
	}

	/**
	 * Whether nothing but bare ground is under the mouse: Walk here, Cancel
	 * and plugins' own entries, and no NPC, object, player or item op. QA,
	 * 15 Oct: the hover Talk-to took the left-click from a bank booth, a tree
	 * and an NPC to attack, the annoyance the game's own "Move follower
	 * options lower down" setting exists for with real pets.
	 */
	static boolean onlyGroundUnder(net.runelite.api.MenuEntry[] entries)
	{
		if (entries == null)
		{
			return false;
		}
		for (net.runelite.api.MenuEntry entry : entries)
		{
			if (entry == null)
			{
				continue;
			}
			net.runelite.api.MenuAction type = entry.getType();
			if (type != net.runelite.api.MenuAction.WALK
				&& type != net.runelite.api.MenuAction.CANCEL
				&& type != net.runelite.api.MenuAction.RUNELITE)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Shift-hover: the same little action tooltip a real NPC gets, drawn by
	 * RuneLite's own tooltip component so the styling is identical. Added
	 * every frame, because the tooltips are cleared after every frame drawn;
	 * added once a client tick it was missing from every frame past fifty a
	 * second, and flickered (play test, 8 Oct).
	 */
	private void shiftHoverTooltip()
	{
		if (client.isKeyPressed(net.runelite.api.KeyCode.KC_SHIFT) && !client.isMenuOpen()
			&& follower.isSpawned() && follower.isUnderMouse(client.getMouseCanvasPosition())
			&& offersWalkHere(client.getMenu().getMenuEntries()))
		{
			tooltipManager.add(new net.runelite.client.ui.overlay.tooltip.Tooltip(
				talkOption() + " <col=ffff00>" + followerName() + "</col>"));
		}
	}

	/**
	 * Whether an interface - the bank, a shop, a quest journal, the world
	 * map - is open over the game view: a slot of whichever layout is in use
	 * has something in it. The bank and its like open in the main modal slot;
	 * the world map, the settings and the collection log in the floater slot,
	 * which stays open as you walk; and the world map made full screen in a
	 * layout of its own. A sketch of the view with the bank in it is a sketch
	 * of the bank - and the Falador sketch came out a picture of the world
	 * map, tooltip and all, while only the main modal slot was looked in.
	 */
	static boolean windowOverTheView(Client client)
	{
		int[] slots = {
			net.runelite.api.gameval.InterfaceID.Toplevel.MAINMODAL,
			net.runelite.api.gameval.InterfaceID.ToplevelOsrsStretch.MAINMODAL,
			net.runelite.api.gameval.InterfaceID.ToplevelPreEoc.MAINMODAL,
			net.runelite.api.gameval.InterfaceID.Toplevel.FLOATER,
			net.runelite.api.gameval.InterfaceID.ToplevelOsrsStretch.FLOATER,
			net.runelite.api.gameval.InterfaceID.ToplevelPreEoc.FLOATER,
			net.runelite.api.gameval.InterfaceID.ToplevelDisplay.DISPLAYMODAL,
		};
		for (int slot : slots)
		{
			net.runelite.api.widgets.Widget modal = client.getWidget(slot);
			if (modal == null || modal.isHidden())
			{
				continue;
			}
			net.runelite.api.widgets.Widget[] inside = modal.getNestedChildren();
			if (inside != null && inside.length > 0)
			{
				return true;
			}
		}
		return false;
	}

	private void addFollowerMenuEntry(String option, Runnable action)
	{
		client.getMenu().createMenuEntry(-1)
			.setOption(option)
			.setTarget(followerTarget())
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

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		// An account's first login makes its profile after the login itself,
		// so the book opened then had nowhere to keep what it drew. A logout
		// brings no profile and is left alone: a sketch still being drawn
		// belongs in the folder it was begun for.
		if (sketchBook != null && event.getNewProfile() != null)
		{
			sketchBook.openFor(event.getNewProfile());
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
		// Said only to a developer: the palette command it points at is one of
		// theirs, and to anybody else it is advice that does not work, about
		// a harvest they never asked for (QA, 15 Oct).
		if (total > before && config.developerMode())
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
		traceAgilityTick();
		noteCreaturesInSight(local);
		if (pendingMime != 0)
		{
			// Arrived beside the work: now the copying reads as joining in.
			if (skilling == null || !skilling.joiningIn())
			{
				pendingMime = 0;
			}
			else if (!pendingOneShot && local.getAnimation() != pendingMime)
			{
				// Stopped while it walked over, and no animation event said so.
				stopPoseMirror();
			}
			else if (skilling.joinedIn())
			{
				int copy = pendingMime;
				pendingMime = 0;
				copyAtWork = true;
				if (pendingOneShot)
				{
					clientThread.invoke(() -> follower.playAnimation(copy));
				}
				else
				{
					startPoseMirror(copy);
				}
			}
		}
		if (config.developerMode())
		{
			// Ground truth for ::follower boat: tiles the player has actually
			// stood on are walkable whatever the collision map says.
			if (walked.size() > 4000)
			{
				walked.clear();
			}
			walked.add(local.getWorldLocation());
		}

		// The dialog's tick gate: queued clicks (open, continue, option,
		// dismiss) resolve here, one tick after the input - the same beat a
		// real dialog takes for its server round trip.
		dialog.tick();
		chronicleWindow.tick();
		if (sketching != null)
		{
			sketching.tick();
		}
		// Every tick while the book is open, and every few otherwise, so a
		// quest begun or finished is seen soon after and its pages dated then
		// and there.
		if (chronicleProgress != null
			&& (chronicleWindow.isOpen() || client.getTickCount() % CHRONICLE_LOOK_TICKS == 0))
		{
			chronicleProgress.refresh(client.getTickCount());
		}

		// Watchdog: a slaved chain waits on the player's next stage; if that
		// never arrives (desync, logout race), release the follower rather than
		// hold the last frame forever. Stages arrive at most ~4s apart. A cast
		// never seen to finish keeps the follower rather than vanishing it.
		if (follower.isSlavedChainActive()
			&& client.getTickCount() - follower.getSlavedAdvanceTick() > 25)
		{
			follower.cancelEmote();
		}

		logPoseComparison(local);
		autoHarvest(local);

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
			{
				follower.despawn();
			}
		}
		else if (!follower.isSpawned() && appearanceService.getCurrent() != null)
		{
			// Back on its post if it was left waiting, as the rebuild does.
			WorldPoint post = config.followEnabled() ? null
				: parkToReturnTo(config.parkedAt(), local.getWorldLocation());
			follower.spawn(appearanceService.getCurrent(), post != null ? post : local.getWorldLocation(),
				spawnGraphic(), config.spawnGraphicHeight(), post != null);

			if (!config.namePrompted())
			{
				namePromptDelayTicks = NAME_PROMPT_DELAY_TICKS;
			}
			maybeSendCommandHint();

			if (!config.followEnabled())
			{
				follower.stayHere();
				parkedByThePlayer(null);
			}
		}

		updateTrail(local);
		// Where the follower stands, for the addressed condition.
		speechEngine.getContext().setFollowerLocation(follower.getWorldLocation());

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
		// A tick means the login's offer replay and any channel roster replay
		// have been delivered; what arrives from here on is news.
		geOffersSettled = true;
		if (channelSettleTicks > 0)
		{
			channelSettleTicks--;
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

		if (namePromptDelayTicks > 0 && --namePromptDelayTicks == 0
			&& follower.isSpawned())
		{
			// A chatbox input dies to almost any other interface, and ten
			// seconds after first spawn is exactly when a new user is still
			// clicking around. The one naming moment is not spent while the
			// bank or another input is up - the countdown re-arms instead.
			if (client.getWidget(net.runelite.api.gameval.InterfaceID.BANKMAIN, 0) != null
				|| chatboxPanelManager.getCurrentInput() != null)
			{
				namePromptDelayTicks = NAME_PROMPT_DELAY_TICKS;
			}
			else
			{
				offerName();
			}
		}

		drainSpeechQueue();

		// The first wish that ever opens gets one out-of-character line naming
		// the handover mechanism - the wish lines themselves stay in voice and
		// never explain the interface. The wish latches when its line is
		// actually displayed (inside the drain above), so the rising edge
		// lands here the same tick.
		boolean wishingNow = !speechEngine.getContext().getWishLabel().isEmpty();
		// While a question waits, Answer opens the question and not Talk-to,
		// where the handover is; the edge is held until it is answered (QA3).
		if (speechEngine.getContext().getAskedTree().isEmpty())
		{
			if (wishingNow && !wasWishing && !config.wishHintShown())
			{
				configManager.setConfiguration(FollowerConfig.GROUP, "wishHintShown", true);
				sendStatus("When you have the " + speechEngine.getContext().getWishLabel()
					+ ", right-click " + followerName()
					+ " and choose " + talkOption() + " to hand it over.");
			}
			wasWishing = wishingNow;
		}
		tickScan();
		commitAttackSighting();
		expireKillClaims();
		updateWander();
		if (gaze != null)
		{
			gaze.tick();
		}
		follower.setFollowDistance(config.followDistance());
		// The shoulder is for open ground and nothing else going on: not a
		// fight, not a pocket being worked, not thrall work.
		follower.setWalksBeside(config.walksBeside() && thrallNpc == null
			&& !speechEngine.getContext().isInCombat()
			&& !speechEngine.getContext().isInThievingSession()
			&& (skilling == null || !skilling.isWorking()));
		updateMinutes();
		updateWelfare();
		updateRest();

		if (errands != null)
		{
			errands.tick();
		}
		if (flourish != null)
		{
			flourish.tick();
		}
		// Before the spectator, which beats it: on the tick a fight is seen
		// the seat is let go first and the stand-clear takes the feet after.
		if (skilling != null)
		{
			skilling.tick();
			skilling.describeTo(speechEngine.getContext());
			// Picking by hand at a patch: the hands emptied of its own gear.
			refreshEmoteDisarm(follower.isEmotePlaying());
			// A copy of the work lasts while the follower is beside it: moved
			// off it, or the work over, and the copy stops there and then,
			// rather than carrying on wherever it was taken. A one-shot is
			// over when its swing is, and back to the seat it goes.
			if (copyAtWork && (!skilling.stillJoined()
				|| (mirroredPose == 0 && !follower.isEmotePlaying())))
			{
				stopPoseMirror();
			}
		}
		if (moments != null)
		{
			// After the seat, which it excuses itself from.
			moments.tick();
		}
		if (noticing != null)
		{
			// After the moments, whose feet it borrows for the walk.
			noticing.tick();
		}
		if (will != null)
		{
			will.tick();
		}

		// After errands, so an errand in progress owns the feet: a follower
		// halfway to a bank should finish the trip rather than be yanked
		// sideways because something attacked. Thrall mode is in the fight, so
		// it is excluded too - and so is the whole EXIT from it: thrallNpc
		// goes null ticks before the goodbye flourish and the snap home have
		// finished with the feet, and a spectate started in that window had
		// its seat wiped by the exit machinery. Play report: fresh out of
		// thrall mode, wards at the player's side, no stepping away.
		if (spectate != null)
		{
			spectate.tick(spectateBusy(), parkedByPlayer);
		}
		// After everything that borrows the feet, so a park the player gave
		// is taken again on the tick they are let go.
		keepThePlayersPark();
		rememberThePark();
		noticeTheLogoutTab();

		// The spawn-in burst waits one tick so the follower has already been
		// rendered standing at the thrall's spot when it plays.
		if (pendingThrallSpawnFxTicks > 0 && --pendingThrallSpawnFxTicks == 0 && thrallNpc != null)
		{
			clientThread.invoke(() ->
				follower.playSpotAnim(spotAnimRepository.get(thrallImpactSpotAnim(thrallStyle))));
		}

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
					follower.playSpotAnim(spotAnimRepository.get(THRALL_RETURN_SPOTANIM));
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
				{
					sendStatus("Problem in dialogs.json: " + problem);
				}
			}
			if (ruleLoader.reloadIfChanged())
			{
				// The rule holding the floor is one of the objects just thrown
				// away, and the exemption that lets it speak is by identity.
				speechEngine.clearFloor();
				speechEngine.getContext().setNicknames(ruleLoader.getNicknames());
				speechEngine.getContext().setRegionNames(ruleLoader.getRegionNames());
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
					TriggerEvent.simple(TriggerEvent.Type.NOTES_EDITED));
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
			speechEngine.setMuted(config.muted() || thrallQuiet());
			if (thievingNow)
			{
				// Anything queued from before belongs to the moment before the
				// silence; letting it trickle out during it is exactly the
				// chatter this is meant to stop.
				speechQueue.clear();
				cutByTheScene = null;
			}
			speechEngine.dispatch(TriggerEvent.simple(thievingNow
				? TriggerEvent.Type.THIEVING_START
				: TriggerEvent.Type.THIEVING_END));
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
		// A take is a silence too. Muted, a rule's line is held back in the
		// engine, as under the Mute, rather than counted as said and then
		// dropped at the film's gate on its way out.
		speechEngine.setMuted(config.muted() || thievingNow || absentNow || thrallQuiet() || filming);

		// The bank has no close event of its own; watched by widget presence.
		// Closing it is an ending (R6) - the restock is done, the breather is
		// real - and it is the one boundary no dispatched event can supply.
		boolean bankOpen = client.getWidget(
			net.runelite.api.gameval.InterfaceID.BANKMAIN, 0) != null;
		if (wasBankOpen && !bankOpen)
		{
			speechEngine.getContext().noteBoundary("bank");
		}
		wasBankOpen = bankOpen;

		// The local song: the music tab's now-playing text is script-updated
		// on every track change whether or not the tab is fronted. The first
		// read after a login is baseline, not news, same as the region you
		// logged in standing.
		net.runelite.api.widgets.Widget nowPlaying = client.getWidget(
			net.runelite.api.gameval.InterfaceID.Music.NOW_PLAYING_TEXT);
		String track = nowPlaying == null || nowPlaying.getText() == null
			? null : nowPlaying.getText().replaceAll("<[^>]*>", "").trim();
		if (track != null && !track.isEmpty())
		{
			if (lastMusicTrack != null && !track.equals(lastMusicTrack))
			{
				speechEngine.dispatch(TriggerEvent.trackChange(track));
			}
			lastMusicTrack = track;
		}

		// An NPC dialogue up, or gone for under three ticks: the same reading
		// the minutes take, so a page flip does not count as the box closing.
		speechEngine.getContext().setDialogueOpen(npcDialogOpen() || dialogGoneTicks <= 2);
		// And who is in it, so a line about them held through the talk is
		// still said once the player walks on from them. Not a cutscene's
		// speakers: nobody the player walked up to.
		net.runelite.api.widgets.Widget speaker = client.getWidget(
			net.runelite.api.gameval.InterfaceID.ChatLeft.NAME);
		if (speaker != null && speaker.getText() != null && !inCutscene())
		{
			speechEngine.getContext().noteSpeaker(
				net.runelite.client.util.Text.removeTags(speaker.getText()));
		}

		// A player-commanded Stay (R25): the follower's own machinery also
		// parks it - errands, spectating, thrall work, and the WANDER, which
		// drifts via the same stayAt - and only this side can tell the
		// difference, so the context is fed the distinction. The wander was
		// missed at first, and the transcript showed the follower taking
		// the post thirty times in an afternoon of its own pottering.
		boolean stayCommanded = stayIsPlayerCommanded(follower.isStaying(),
			wandered, thrallNpc != null,
			errands != null && errands.isBusy(),
			spectate != null && spectate.holdsTheFeet(),
			emoteHold, minutesState != 0,
			(skilling != null && skilling.holdsTheFeet())
				|| (moments != null && moments.holdsTheFeet())
				// A write-up plants the follower for the length of the notes.
				|| (flourish != null && flourish.isActive())
				// A scene holds it where it stands.
				|| sceneHolds());
		speechEngine.getContext().setFollowerStaying(stayCommanded);
		if (stayCommanded != stayReported)
		{
			stayReported = stayCommanded;
			if (onStayStateChanged != null)
			{
				onStayStateChanged.accept(stayCommanded);
			}
		}

		// An absence ending is a boundary too: the player went out of sight
		// of the parked follower and has now come back to it.
		if (stayCommanded)
		{
			WorldPoint parked = follower.getWorldLocation();
			WorldPoint player = local.getWorldLocation();
			int apart = parked == null || player == null
				|| parked.getPlane() != player.getPlane()
				? Integer.MAX_VALUE : parked.distanceTo(player);
			if (apart > 12)
			{
				leftTheFollowerParked = true;
			}
			else if (leftTheFollowerParked && apart <= 3)
			{
				leftTheFollowerParked = false;
				speechEngine.getContext().noteBoundary("reunion");
			}
		}
		else
		{
			leftTheFollowerParked = false;
		}

		int region = speechEngine.getContext().getRegionId();
		if (region != lastRegionId)
		{
			int previous = lastRegionId;
			lastRegionId = region;
			if (chronicleProgress != null)
			{
				chronicleProgress.noteRegion(region);
			}
			if (panel != null)
			{
				panel.setRegionId(region);
			}
			if (previous != -1)
			{
				speechEngine.dispatch(TriggerEvent.regionChange(region, previous));
				// Somewhere new: once the player settles, the follower may go
				// and nose at something (the explore errand's arrival trigger).
				if (errands != null)
				{
					errands.noticeArrival();
				}
			}
		}

		speechEngine.dispatch(TriggerEvent.tick());

		// A page written gets its mention after this tick's own events have
		// been heard, and only when nothing else is being said. The dialogue
		// as the rules read it, page flips ridden out: on the raw box the news
		// went out on a flip and sat over the conversation (QA, 15 Oct).
		PageNews first = pageNews.peek();
		TriggerEvent news = nextPageNews(pageNews, client.getTickCount(), speechEngine.isQuiet(),
			inConversation() || sceneQuiet() || (first != null && speechEngine.isPageLineCooling(first.event)),
			pageNewsTicks(speechEngine.moodScaledGap()), lastRegionId);
		if (news != null)
		{
			speechEngine.dispatch(news);
		}
	}

	/**
	 * The page to mention now, or null: the oldest still inside its minute,
	 * once the follower has the floor. Play test, 7 Oct: a quest begun in
	 * conversation writes its pages with the box still up, and a line said
	 * over a dialogue is dropped, so starting Priest in Peril with King Roald
	 * was never mentioned. While a dialogue is up the news waits, and its
	 * minute starts again when the box closes. A cutscene and the quiet after
	 * it hold it the same way: a page a scene writes is mentioned after.
	 * Its minute is {@code window} ticks, as {@link #pageNewsTicks} has it.
	 *
	 * <p>A place's page is news only while the player is there. Text QA4,
	 * 16 Oct: at Minimal it was mostly said after they had left, asking them
	 * to pose for a sketch they could no longer take.
	 */
	static TriggerEvent nextPageNews(java.util.Deque<PageNews> news, int now, boolean quiet,
		boolean waits, int window, int region)
	{
		news.removeIf(page -> page.where != null && !page.where.contains(region));
		if (waits)
		{
			for (PageNews waiting : news)
			{
				waiting.tick = now;
			}
			return null;
		}
		while (!news.isEmpty() && now - news.peek().tick > window)
		{
			news.poll();
		}
		return !news.isEmpty() && quiet ? news.poll().event : null;
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
			if (chronicleWindow.isOpen() && lastPlayerTile != null && !lastPlayerTile.equals(tile))
			{
				chronicleWindow.close();
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
	/**
	 * The logout tab, opened: the last thing on screen before the session
	 * ends, so a turn to face them and a wave, the rare line to go with it,
	 * and a last look at the quests, so one finished just now is dated before
	 * the session ends. The panel is loaded once, at login, and a tab press
	 * only shows it, so the load never came with the press (play test, 29
	 * Sep): it is watched once a tick instead, as the panel coming into view.
	 */
	private void noticeTheLogoutTab()
	{
		net.runelite.api.widgets.Widget panel =
			client.getWidget(net.runelite.api.gameval.InterfaceID.LOGOUT, 0);
		boolean open = panel != null && !panel.isHidden();
		boolean opened = open && !logoutTabOpen;
		logoutTabOpen = open;
		if (!opened)
		{
			return;
		}
		if (moments != null)
		{
			moments.farewell();
		}
		speechEngine.dispatch(TriggerEvent.simple(TriggerEvent.Type.FAREWELL));
		if (chronicleProgress != null)
		{
			chronicleProgress.refresh(client.getTickCount());
		}
	}

	/**
	 * Whether the logout tab was on show at the last look. True between
	 * sessions, so a tab still up as one starts is not taken for a goodbye.
	 */
	private boolean logoutTabOpen = true;

	@Subscribe
	public void onWidgetLoaded(net.runelite.api.events.WidgetLoaded event)
	{
		int group = event.getGroupId();

		// The quest reward scroll - the one moment the game hands the player
		// a literal scroll, witnessed by a professional.
		if (group == net.runelite.api.gameval.InterfaceID.QUESTSCROLL)
		{
			noteQuestScroll();
			return;
		}

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
				// Our dialog lays the head out like the NPC side (left), so adopt a
				// human NPC head's framing; rotationZ is the head's actual angle.
				if (group == net.runelite.api.gameval.InterfaceID.CHAT_LEFT)
				{
					dialog.adoptNpcHead(widget.getAnimationId(), widget.getRotationZ(),
						widget.getRotationX(), widget.getModelZoom());
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
		// Talked to, fought, or set upon: met, however far off it stands.
		Player me = client.getLocalPlayer();
		if (chronicleProgress != null && me != null)
		{
			if (event.getSource() == me && event.getTarget() instanceof NPC)
			{
				chronicleProgress.noteNpc(event.getTarget().getName());
			}
			else if (event.getTarget() == me && event.getSource() instanceof NPC)
			{
				chronicleProgress.noteNpc(event.getSource().getName());
			}
		}
		if (dialog.isOpen()
			&& event.getSource() == client.getLocalPlayer()
			&& event.getTarget() != null)
		{
			dialog.close();
		}
	}

	/** How close a creature has to be, in clear sight, to count as met: eight tiles. */
	static final int MEET_TILES = 8;

	/**
	 * Play test, 7 Oct: a page was written for every creature loaded within
	 * about 15 tiles, through walls and floors, so walking in to see King
	 * Roald wrote the Monk of Zamorak's from its cage under the stairs,
	 * unseen. Met now means close, on the same floor, with nothing in the
	 * way, or talked to, fought or examined.
	 */
	static boolean closeEnoughToMeet(int creaturePlane, int myPlane, int distance)
	{
		return creaturePlane == myPlane && distance <= MEET_TILES;
	}

	/** Writes up the creatures with a page waiting that are in clear sight now. */
	private void noteCreaturesInSight(Player me)
	{
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		net.runelite.api.coords.WorldArea mine = me.getWorldArea();
		if (chronicleProgress == null || view == null || mine == null)
		{
			return;
		}
		for (NPC npc : view.npcs())
		{
			if (npc == null || !chronicleProgress.wantsToMeet(npc.getName()))
			{
				continue;
			}
			net.runelite.api.coords.WorldArea theirs = npc.getWorldArea();
			if (theirs != null && closeEnoughToMeet(theirs.getPlane(), mine.getPlane(), theirs.distanceTo(mine))
				&& theirs.hasLineOfSightTo(view, mine))
			{
				chronicleProgress.noteNpc(npc.getName());
			}
		}
	}

	// ----------------------------------------------------------- game events

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		// Not written up here: a spawn is anything loaded within about 15
		// tiles, through walls and floors. See noteCreaturesInSight.
		maybeAdoptThrall(npc);
		if (skilling != null)
		{
			skilling.npcSpawned(npc);
		}
		if (moments != null && config.noticesThings())
		{
			moments.npcSpawned(npc);
		}

		// A chunk load raises one of these for every NPC in the new scene, and
		// each costs a pass over every rule. Only pay it when a rule names it.
		TriggerEvent spawn = TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, npc.getId(), npc.getName());
		if (ruleLoader.answersSpawn(spawn))
		{
			speechEngine.dispatch(spawn);
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
		if (skilling != null)
		{
			skilling.npcDespawned(npc);
		}
		if (moments != null)
		{
			moments.npcDespawned(npc);
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
	public void onGameObjectSpawned(net.runelite.api.events.GameObjectSpawned event)
	{
		noteObjectChange(event.getGameObject(), true);
	}

	@Subscribe
	public void onGameObjectDespawned(net.runelite.api.events.GameObjectDespawned event)
	{
		noteObjectChange(event.getGameObject(), false);
	}

	/**
	 * A ground item appeared. Only a session cares, and only about a nest at
	 * the player's feet; the name is looked up on the client thread, where
	 * the event arrives.
	 */
	@Subscribe
	public void onItemSpawned(net.runelite.api.events.ItemSpawned event)
	{
		if (event.getItem() == null || event.getTile() == null)
		{
			return;
		}
		// A chunk load raises one of these per item already lying about;
		// none of those is a drop the player just walked away from.
		if (noticing != null && ticksSinceLoading > 2)
		{
			int id = event.getItem().getId();
			noticing.itemSpawned(event.getItem(), event.getTile().getWorldLocation(),
				itemManager.getItemComposition(id).getName(),
				(long) itemManager.getItemPrice(id) * event.getItem().getQuantity());
		}
		if (skilling == null || !skilling.isWorking())
		{
			return;
		}
		skilling.itemSpawned(itemManager.getItemComposition(event.getItem().getId()).getName(),
			event.getTile().getWorldLocation());
	}

	@Subscribe
	public void onItemDespawned(net.runelite.api.events.ItemDespawned event)
	{
		if (noticing != null && event.getItem() != null && event.getTile() != null)
		{
			noticing.itemDespawned(event.getItem(), event.getTile().getWorldLocation());
		}
	}

	/**
	 * Scenery coming and going, handed to the skilling company by footprint.
	 * A chunk load raises one of these per object in the scene, so a despawn
	 * is only handed over during a session, and a spawn only while the
	 * company keeps company at all - it wants the trap the player just laid
	 * and the fire they just lit, session or none - and the company itself
	 * drops everything a load shuffles and keeps only what touches its tiles.
	 */
	private void noteObjectChange(net.runelite.api.GameObject object, boolean spawned)
	{
		// The fires, first and ungated: a chunk load raises one of these per
		// object in the scene, and that IS how the set is built - the tiles
		// the follower walks round have to be known before it takes a step,
		// whatever the company settings say.
		if (hotGround != null && object != null)
		{
			net.runelite.api.WorldView fires = client.getTopLevelWorldView();
			net.runelite.api.Point from = object.getSceneMinLocation();
			net.runelite.api.Point to = object.getSceneMaxLocation();
			if (fires != null && from != null && to != null)
			{
				WorldPoint low = WorldPoint.fromScene(fires, from.getX(), from.getY(),
					object.getPlane());
				WorldPoint high = WorldPoint.fromScene(fires, to.getX(), to.getY(),
					object.getPlane());
				if (spawned)
				{
					hotGround.objectSpawned(object.getId(), low, high);
				}
				else
				{
					hotGround.objectDespawned(object.getId(), low, high);
				}
			}
		}

		// A star landing. Names are only looked up outside a chunk load,
		// which raises one of these per object in the scene.
		if (noticing != null && object != null && ticksSinceLoading > 2)
		{
			String name = com.follower.follower.ErrandController.objectName(client, object.getId());
			if (spawned)
			{
				noticing.objectSpawned(name, object.getWorldLocation());
			}
			else
			{
				noticing.objectDespawned(name, object.getWorldLocation());
			}
		}
		if (skilling == null || object == null
			|| (spawned ? !config.keepsCompany() : !skilling.isWorking()))
		{
			return;
		}
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		net.runelite.api.Point lo = object.getSceneMinLocation();
		net.runelite.api.Point hi = object.getSceneMaxLocation();
		if (view == null || lo == null || hi == null)
		{
			return;
		}
		WorldPoint min = WorldPoint.fromScene(view, lo.getX(), lo.getY(), object.getPlane());
		WorldPoint max = WorldPoint.fromScene(view, hi.getX(), hi.getY(), object.getPlane());
		if (spawned)
		{
			skilling.objectSpawned(object.getId(), min, max);
		}
		else
		{
			skilling.objectDespawned(object.getId(), min, max);
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		// The follower's own lines land in public chat under its name; feeding
		// them back into the rules would let it react to itself.
		if (followerName().equals(event.getName()))
		{
			return;
		}
		// Nor the player's lines written in the film window and played back.
		if (event.getType() == ChatMessageType.PUBLICCHAT && scriptedChat != null
			&& scriptedChat.equals(event.getMessage()))
		{
			scriptedChat = null;
			return;
		}
		// Nor its status lines, which are CONSOLE lines, as are the echoes
		// below: with chatwatch on, each echo was itself echoed, endlessly.
		// The game says nothing a rule listens for as CONSOLE; that is
		// RuneLite's and the plugins' own channel.
		if (event.getType() == ChatMessageType.CONSOLE)
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

		if (skilling != null && !filming)
		{
			// The stun, the empty rune pouch, the slayer task: silent reactions,
			// and none in a take, where they would turn and gesture over the pose.
			skilling.noteChatMessage(event.getMessage(), event.getType());
		}
		if (moments != null && config.noticesThings())
		{
			// The pet drop: the walk over to see it.
			moments.noteChatMessage(event.getMessage(), event.getType());
		}

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
				speechEngine.getContext().tally("music");
				speechEngine.dispatch(TriggerEvent.musicUnlock(parsed));
			}
			parsed = parseTailAfter(event.getMessage(),
				"New item added to your collection log:");
			if (parsed != null)
			{
				speechEngine.getContext().tally("collections");
				speechEngine.dispatch(TriggerEvent.collectionAdd(parsed));
				if (moments != null && config.noticesThings() && !filming)
				{
					// A first-ever thing is news whatever it sells for; it is
					// in the bag, so the write-up faces the player.
					moments.noteworthy(com.follower.follower.Moments.Tier.MEDIUM, null);
				}
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
	private static final java.util.regex.Pattern KILL_COUNT = java.util.regex.Pattern.compile(
		"^Your (?:completed |subdued )?(.+?)(?: kill| chest| completion| success)? count is:? ?([\\d,]+)");

	/** The boss and the number out of a kill-count message, or null for any other message. */
	static java.util.Map.Entry<String, Integer> parseKillCount(String message)
	{
		if (message == null)
		{
			return null;
		}
		java.util.regex.Matcher m = KILL_COUNT.matcher(message.replaceAll("<[^>]*>", "").trim());
		if (!m.find())
		{
			return null;
		}
		String boss = m.group(1).trim();
		// The agility courses and the herbiboar are counted in the same words,
		// "Your Varrock Rooftop lap count is: 25.", and a lap is not a kill:
		// the boss lines made it "That makes 25 Varrock Rooftop lap."
		if (boss.endsWith(" lap") || boss.endsWith(" harvest") || boss.endsWith(" Total Ticket"))
		{
			return null;
		}
		try
		{
			return new java.util.AbstractMap.SimpleImmutableEntry<>(
				boss, Integer.parseInt(m.group(2).replace(",", "")));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private void noteKillCount(String message)
	{
		java.util.Map.Entry<String, Integer> parsed = parseKillCount(message);
		if (parsed == null)
		{
			return;
		}
		String boss = parsed.getKey();
		int count = parsed.getValue();
		TriggerContext context = speechEngine.getContext();
		// A record rather than a tally: the game's count only ever goes up,
		// and what the follower keeps is the highest it has heard. The game
		// keeps it per account, so the follower does too.
		String bare = "kc:" + boss.toLowerCase(Locale.ROOT);
		String key = chronicleProgress == null ? bare : chronicleProgress.accountKey(bare);
		int last = context.getRecord(key);
		context.noteRecord(key, count);
		context.noteKillCountName(boss);
		// The count kept before counts were per account was this account's
		// once this one has reached it, and it has done its job: kept, it
		// would stand in for an alt that never killed the boss, for good.
		if (!key.equals(bare) && count >= context.getRecord(bare))
		{
			context.forgetRecord(bare);
		}
		speechEngine.dispatch(TriggerEvent.killCount(boss, count, last));
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
		{
			return null;
		}
		String plain = message.replaceAll("<[^>]*>|@[a-z0-9_]+@", "").trim();
		if (!plain.startsWith(prefix))
		{
			return null;
		}
		String tail = plain.substring(prefix.length()).replaceAll("[.!]+$", "").trim();
		return tail.isEmpty() ? null : tail;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		// A scene's first tick is read on the game tick, after this tick's
		// messages: quiet from the varbit itself, so a line fired alongside
		// it is held. noticeTheScene still owns the edges.
		if (event.getVarbitId() == net.runelite.api.gameval.VarbitID.CUTSCENE_STATUS
			&& event.getValue() != 0)
		{
			speechEngine.setSceneQuiet(true);
		}

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
		if (event.getVarbitId() == THRALL_SUMMONED_VARBIT
			&& replacesThralls(config.thrallMode(), config.thrallOnly())
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

	/**
	 * Reads the quest name off the reward scroll's title ("You have completed
	 * The Restless Ghost!"). The children are populated after the load event,
	 * so it is read next cycle, like the chathead calibration.
	 */
	private void noteQuestScroll()
	{
		if (chronicleProgress != null)
		{
			chronicleProgress.refresh(client.getTickCount());
		}
		clientThread.invokeLater(() ->
		{
			net.runelite.api.widgets.Widget title = client.getWidget(
				net.runelite.api.gameval.InterfaceID.Questscroll.QUEST_TITLE);
			if (title == null || title.getText() == null)
			{
				return;
			}
			String text = title.getText().replaceAll("<[^>]*>", "").trim();
			String prefix = "You have completed ";
			if (!text.startsWith(prefix))
			{
				// Speedruns and oddities use their own scrolls; a completion
				// this could not read is better silent than misquoted.
				log.debug("Quest scroll title in an unexpected shape: {}", text);
				return;
			}
			String quest = text.substring(prefix.length())
				.replaceAll("[.!]+$", "").trim();
			if (quest.isEmpty())
			{
				return;
			}
			speechEngine.getContext().tally("quests");
			speechEngine.getContext().noteDoing("finishing " + quest);
			if (chronicleProgress != null && isStaying())
			{
				// Parked elsewhere by the player, it did not see this one.
				chronicleProgress.missedQuestFinish(quest);
			}
			else if (chronicleProgress != null)
			{
				chronicleProgress.sawQuestFinish(quest);
			}
			speechEngine.dispatch(TriggerEvent.questComplete(quest));
		});
	}

	/**
	 * Whether the Grand Exchange offer replay at login has passed. Logging in
	 * fires one offer-changed per slot for offers that were already sitting
	 * there; reacting to those would report last week's flips as news. Armed
	 * by the first game tick, cleared whenever the game state churns.
	 */
	private boolean geOffersSettled;

	@Subscribe
	public void onGrandExchangeOfferChanged(net.runelite.api.events.GrandExchangeOfferChanged event)
	{
		if (!geOffersSettled)
		{
			return;
		}
		net.runelite.api.GrandExchangeOffer offer = event.getOffer();
		if (offer == null)
		{
			return;
		}
		boolean bought = offer.getState() == net.runelite.api.GrandExchangeOfferState.BOUGHT;
		if (!bought && offer.getState() != net.runelite.api.GrandExchangeOfferState.SOLD)
		{
			return;
		}
		// The clerk receives the telegram wherever you are - that is the joke.
		String item = itemManager.getItemComposition(offer.getItemId()).getName();
		speechEngine.dispatch(TriggerEvent.geOffer(bought, item, offer.getSpent()));
	}

	/**
	 * Ticks left of ignoring channel joins. Joining a channel replays a join
	 * for every member already in it - a roster, not an arrival - so the
	 * window after the channel itself changes is swallowed, the same
	 * settle-delay trick the NPC spawn flood uses.
	 */
	private int channelSettleTicks;

	@Subscribe
	public void onFriendsChatChanged(net.runelite.api.events.FriendsChatChanged event)
	{
		channelSettleTicks = 5;
	}

	@Subscribe
	public void onClanChannelChanged(net.runelite.api.events.ClanChannelChanged event)
	{
		channelSettleTicks = 5;
	}

	@Subscribe
	public void onFriendsChatMemberJoined(net.runelite.api.events.FriendsChatMemberJoined event)
	{
		dispatchChannel(TriggerEvent.Type.CHANNEL_JOIN, event.getMember().getName());
	}

	@Subscribe
	public void onFriendsChatMemberLeft(net.runelite.api.events.FriendsChatMemberLeft event)
	{
		dispatchChannel(TriggerEvent.Type.CHANNEL_LEAVE, event.getMember().getName());
	}

	@Subscribe
	public void onClanMemberJoined(net.runelite.api.events.ClanMemberJoined event)
	{
		dispatchChannel(TriggerEvent.Type.CHANNEL_JOIN, event.getClanMember().getName());
	}

	@Subscribe
	public void onClanMemberLeft(net.runelite.api.events.ClanMemberLeft event)
	{
		dispatchChannel(TriggerEvent.Type.CHANNEL_LEAVE, event.getClanMember().getName());
	}

	private void dispatchChannel(TriggerEvent.Type type, String who)
	{
		if (channelSettleTicks > 0 || who == null)
		{
			return;
		}
		// Display names arrive padded with non-breaking spaces.
		speechEngine.dispatch(TriggerEvent.channel(type, who.replace(' ', ' ').trim()));
	}

	/**
	 * NPC overheads never reach the chatbox, so no chat rule can see them -
	 * this is the only road to a scribe taking dictation from a town crier.
	 * Guarded like the spawn flood: busy scenes shout constantly, and the
	 * pass over every rule is only paid when a rule actually listens.
	 */
	@Subscribe
	public void onOverheadTextChanged(net.runelite.api.events.OverheadTextChanged event)
	{
		if (!(event.getActor() instanceof NPC)
			|| !ruleLoader.listensFor(TriggerEvent.Type.NPC_SHOUT))
		{
			return;
		}
		NPC npc = (NPC) event.getActor();
		// An NPC with no name of its own answers "null", and the follower
		// took dictation from it: "Noted, null. Volume down next time."
		if (npc.getName() == null || "null".equals(npc.getName())
			|| event.getOverheadText() == null)
		{
			return;
		}
		speechEngine.dispatch(TriggerEvent.npcShout(npc.getName(), event.getOverheadText()));
	}

	/**
	 * What {@code ::follower stance <weaponId>} reports: the weapon's own
	 * stance, and the swing the follower actually uses with it. That swing can
	 * be the shipped one while the weapon's learned record knows none.
	 */
	static String stanceStatus(com.follower.follower.StanceLibrary library, String name, int weaponId)
	{
		com.follower.follower.StanceLibrary.Stance known = library.describe(weaponId);
		int attack = library.attackFor(weaponId);
		return known == null
			? (name == null ? "Item " + weaponId : name)
				+ ": no stance known - it will stand unarmed"
			: (name == null ? "Item " + weaponId : name)
				+ ": idle " + known.idle + ", walk " + known.walk
				+ ", run " + known.run
				+ ", attack " + (attack > 0 ? attack : "unknown");
	}

	/**
	 * Which attack style the player has selected, as the game numbers them.
	 *
	 * <p>Deliberately not translated into stab, slash or crush: what a style
	 * number means depends on the weapon type, and the follower only ever
	 * needs the animation that goes with the style, never its name.
	 */
	private int attackStyle()
	{
		return client.getVarpValue(net.runelite.api.gameval.VarPlayerID.COM_MODE);
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
			noteAttackSighting(animating);

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

		if (skilling != null && event.getActor() instanceof Player)
		{
			// Any player's: the proof that an animation is safe on a player
			// skeleton is a player playing it.
			skilling.notePlayerAnimation(event.getActor().getAnimation());
		}

		if (event.getActor() != client.getLocalPlayer())
		{
			return;
		}

		int animationId = event.getActor().getAnimation();
		// The last thing the player did, for the film window's Copy my emote.
		filmSet.notePlayerAnimation(animationId);

		if (skilling != null)
		{
			// Proof that a work click turned into work, or that it goes on.
			skilling.noteAnimation(animationId);
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
				clientThread.invoke(follower::slavedPlayerStopped);
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
		// Stopped before the follower got over there: no copy on arrival.
		else if (pendingMime != 0 && !pendingOneShot && animationId != pendingMime)
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

		// An emote while it sketches is the pose: not copied over the scroll,
		// and not answered while the view is being taken.
		if (sketching != null && sketching.isSketching())
		{
			return;
		}

		// The follower's tile as of this event, not the last tick's: an emote
		// done at it is judged on where it is.
		speechEngine.getContext().setFollowerLocation(follower.getWorldLocation());
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
		if (skilling != null && !filming)
		{
			// The thieving stun's stars, which land before its message.
			skilling.noteGraphic(graphicId);
		}
		if (watchAnimations)
		{
			sendStatus("Graphic " + graphicId + " at height " + graphicHeight
				+ "  (::follower gfx " + graphicId + " to replay, add 'set' to keep it)");
			// Also to the log: chat scrolls away, and a captured id is the
			// whole point of watching.
			log.info("WATCH self graphic {} height {}", graphicId, graphicHeight);
		}

		if (takesMirroredGraphic(client.getTickCount()))
		{
			mirrorGraphic(graphicId, graphicHeight);
		}
	}

	/**
	 * Whether a graphic on the player now is the cast's to copy: within the
	 * window a copying rule opened, and only the first, so one cast copies
	 * one graphic.
	 */
	boolean takesMirroredGraphic(int tick)
	{
		if (tick > mirrorGraphicsUntilTick)
		{
			return false;
		}
		mirrorGraphicsUntilTick = -1;
		return true;
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
		// Recorded before the size check, and for anybody: a block animation
		// plays on a zero as readily as on a forty, and it is the tell that an
		// animation was a defence rather than a swing.
		if (event.getActor() instanceof Player)
		{
			playersHit.put(event.getActor(), client.getTickCount());
		}
		if (event.getActor() == client.getLocalPlayer())
		{
			// For the agility trace, zeros included: which types a fall is.
			agilityHitsplatThisTick = event.getHitsplat().getHitsplatType();
		}

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
			if (crossingWatch != null
				&& com.follower.follower.CrossingWatch.isFallDamage(event.getHitsplat().getHitsplatType()))
			{
				crossingWatch.noteDamage(System.currentTimeMillis());
			}
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
	/**
	 * Players hit recently, by tick. A player's block animation fires when
	 * they TAKE a hit, and nothing in the animation itself says which of the
	 * two it was.
	 */
	private final java.util.Map<net.runelite.api.Actor, Integer> playersHit =
		new java.util.HashMap<>();

	/** The most recent animation seen on a player, held for one tick. */
	private Player sightedPlayer;
	private int sightedAnimation;
	private int sightedTick;

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
	private void noteAttackSighting(Player animating)
	{
		if (animating.getAnimation() <= 0 || animating.getInteracting() == null)
		{
			return;
		}
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
	private void commitAttackSighting()
	{
		if (sightedPlayer != null && sightedTick < client.getTickCount())
		{
			Integer hit = playersHit.get(sightedPlayer);
			net.runelite.api.Actor target = sightedPlayer.getInteracting();
			boolean swing = sightingIsASwing(
				sightedPlayer == client.getLocalPlayer(),
				speechEngine.getContext().isInCombat(),
				target == null ? -1 : target.getHealthRatio());
			if ((hit == null || hit < sightedTick) && swing)
			{
				// Only the local player's selected style is knowable - varps
				// are theirs alone - so everyone else teaches the generic
				// swing and your own play teaches the styled ones.
				stanceLibrary.learnAttack(sightedPlayer, sightedAnimation,
					sightedPlayer == client.getLocalPlayer() ? attackStyle() : -1);
			}
			sightedPlayer = null;
		}

		if (!playersHit.isEmpty())
		{
			int cutoff = client.getTickCount() - 3;
			playersHit.values().removeIf(tick -> tick < cutoff);
		}
	}

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
				// tallyBoth, like deaths and levels: the LIFETIME key is what the
				// kill challenge counts against and what earns the nickname, and
				// writing only the daily one left both permanently unreachable -
				// every wager failed on its deadline however well it went.
				speechEngine.getContext().tallyBoth("kills");
				int count = speechEngine.getContext().tally(
					"kill:" + name.toLowerCase(Locale.ROOT));
				speechEngine.dispatch(TriggerEvent.kill(
					npc.getId(), name, npc.getCombatLevel(), count));
				if (chronicleProgress != null)
				{
					chronicleProgress.noteNpc(name);
				}
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
		dispatchLoot(event.getItems(),
			event.getNpc() == null ? null : event.getNpc().getWorldLocation());
	}

	@Subscribe
	public void onPlayerLootReceived(net.runelite.client.events.PlayerLootReceived event)
	{
		dispatchLoot(event.getItems(),
			event.getPlayer() == null ? null : event.getPlayer().getWorldLocation());
	}

	/** @param at where the drop landed, for the body to look at; null if unknown */
	private void dispatchLoot(java.util.Collection<net.runelite.client.game.ItemStack> items,
		WorldPoint at)
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
			if (moments != null && config.noticesThings() && !filming)
			{
				// The body's share: a glance, the notes, or the walk over.
				moments.noteworthy(com.follower.follower.Moments.tierFor(total), at);
			}
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
			TriggerContext context = speechEngine.getContext();
			context.tallyBoth("levels");
			// The wider ledger, for the milestone rules: where the total and
			// the lowest skill stood before this level and after it, how many
			// skills are maxed now, and how long this one took.
			int total = 0;
			int base = Integer.MAX_VALUE;
			int previousBase = Integer.MAX_VALUE;
			int masteries = 0;
			for (Skill each : Skill.values())
			{
				int real = each == skill ? level : client.getRealSkillLevel(each);
				total += real;
				base = Math.min(base, real);
				previousBase = Math.min(previousBase, each == skill ? previous : real);
				if (real >= 99)
				{
					masteries++;
				}
			}
			long now = System.currentTimeMillis();
			long sinceAny = context.sinceAnyLevel(now);
			long sinceLast = context.noteLevel(skill.getName(), level, now);
			speechEngine.dispatch(TriggerEvent.levelUp(skill.getName(), level,
				total, total - (level - previous), base, previousBase, masteries,
				sinceLast, sinceAny));
			if (moments != null && config.noticesThings() && !filming)
			{
				// The clap, or the walk over for it; the line is the rules'.
				moments.levelUp(level);
			}
		}

		// A rise in XP is a drop, whatever the level did. The work session
		// counts it and the rules hear of it on the tick it lands; the first
		// reading after a login is baseline, like the level above.
		int xp = event.getXp();
		Integer previousXp = knownXp.put(skill, xp);
		if (previousXp != null && xp > previousXp)
		{
			if (skill == Skill.AGILITY && crossingWatch != null)
			{
				// What tells a climb from a ladder, and rebuilds an obstacle missed.
				crossingWatch.noteAgilityXp(System.currentTimeMillis());
				lastAgilityXpMs = System.currentTimeMillis();
				agilityXpThisTick += xp - previousXp;
				agilityTrace.event("xp +" + (xp - previousXp));
				// Not while the watch cannot read them: as a greegree monkey on
				// Ape Atoll nothing is copied, and the follower joins them as before.
				if (config.copiesAgility() && !parkedByPlayer && com.follower.follower.CrossingWatch.Sample
					.read(client.getLocalPlayer(), System.currentTimeMillis()) != null)
				{
					// Whatever the player is on is about to be queued: no snap meanwhile.
					follower.holdForAgility();
				}
			}
			int count = skilling == null ? 0 : skilling.noteXpDrop(skill);
			speechEngine.dispatch(TriggerEvent.xpDrop(skill.getName(), count));
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
	 * here, anim, gfx, pose, height, errand, outfit, reload, copy, fix,
	 * rebuild, status and where all stay available.
	 *
	 * <p>{@code watch} and {@code stance} are gated too, despite being useful
	 * to a determined user - one prints an animation id for writing a rule, the
	 * other hand-sets a weapon's animations. Both are authoring tools for
	 * someone extending the plugin rather than playing with it, and every place
	 * the documentation points at them names the setting they need.
	 */
	/** Tiles the player has stood on, for the boat dump; developer mode only. */
	private final Set<WorldPoint> walked = new HashSet<>();

	private static final Set<String> DEVELOPER_COMMANDS = new HashSet<>(java.util.Arrays.asList(
		"priorities", "palette", "harvest", "hidden",
		"pitchsweep", "headsweep", "head", "followtrace",
		"wraplerp", "wrapauto", "wrapearly",
		"animinfo", "animtrace", "errandscan", "cachecheck", "stanceaudit", "fires",
		"watch", "stance", "spectate", "shield", "centre", "center", "loot",
		"scan", "heights", "mood", "chatwatch", "examinewatch", "fire", "want", "transcript",
		"boat", "force",
		"thieftargets", "sniffanims", "finditem", "prop", "propoffset"));

	/** The errand keys ::follower errand accepts, read from the enum itself. */
	private static final java.util.List<String> ERRAND_KEYS =
		com.follower.follower.ErrandController.keys();

	/**
	 * The errand keys a player is shown: all but firedeath, which stays
	 * accepted but unlisted, as in the panel's dropdown, so the joke is not
	 * spoiled by a list.
	 */
	private static final String LISTED_ERRAND_KEYS = ERRAND_KEYS.stream()
		.filter(k -> !"firedeath".equals(k))
		.collect(java.util.stream.Collectors.joining("|"));

	/**
	 * The two answers that keep "Will run the errand at the next quiet
	 * moment" honest: errands are off, or the one asked for could not start
	 * where the follower is. Wording awaits approval (fx/chat-text.json).
	 */
	static final String ERRANDS_OFF = "Errands are off. Tick Go on errands under Errands to send it on one.";
	static final String ERRAND_MISSED = "That errand can't start here.";

	/**
	 * The everyday commands as (command, description) pairs. The help printer
	 * and the side panel's Commands section both read this list, so the two
	 * cannot drift apart.
	 */
	public static final java.util.List<java.util.Map.Entry<String, String>> EVERYDAY_COMMANDS =
		java.util.Collections.unmodifiableList(java.util.Arrays.asList(
			command("say <text>", "make the follower say it"),
			command("here", "place the follower at your feet"),
			command("fix", "re-attach it if it vanished"),
			command("rebuild", "rebuild its model from the outfit"),
			command("copy", "dress it in your gear (also a panel button)"),
			command("outfit <name>", "wear a saved profile (also in the panel)"),
			command("anim <id...>", "preview an animation on it"),
			command("gfx <id> [height]", "preview a graphic on it, that far up its body"),
			command("pose <id>", "hold a looping pose, 0 to release"),
			command("posing", "the Advanced posing window: pose it, give it lines, or play out a scene"),
			command("sketch", "pose for the chronicle's sketch of this place, as right-click Sketch"),
			command("height <n>", "lift or lower it"),
			// Spaced, so the list wraps between keys rather than mid-word.
			command("errand [" + LISTED_ERRAND_KEYS.replace("|", " | ") + "]", "send it on an errand now"),
			command("reload", "re-read the phrase and sound files"),
			command("status", "models, rules and spawn state"),
			command("where", "your region id, for Locations in the Messages window")));

	private static java.util.Map.Entry<String, String> command(String name, String effect)
	{
		return new java.util.AbstractMap.SimpleImmutableEntry<>(name, effect);
	}

	/**
	 * The gated commands with their one-line effects, for the panel's
	 * Commands section once Developer commands is on. The same table as
	 * docs/DEVELOPMENT.md; keep the two together.
	 */
	private static final java.util.List<java.util.Map.Entry<String, String>> DEVELOPER_COMMAND_HELP =
		java.util.Collections.unmodifiableList(java.util.Arrays.asList(
			command("watch [all]", "print the ids of your animations and graphics; watch all adds everyone's"),
			command("stance <weaponId> [idle walk run attack]", "show or hand-set a weapon's animations"),
			command("hidden", "which slots each equipped item covers"),
			command("palette", "copy your exact body colours onto it; palette clear drops them"),
			command("harvest", "extract the client's colour tables; harvest stop aborts"),
			command("followtrace", "live follow-state overlay and log rows; off to stop"),
			command("animinfo", "frame count, duration and interpolation of the playing animation"),
			command("animtrace", "trace its and your pose frames side by side for about 8s"),
			command("wrapearly <n>", "override the loop trim of the playing animation"),
			command("wrapauto", "drop manual trims and let measurement decide"),
			command("boat", "dump the world views, collision and objects around you"),
			command("cachecheck", "diff the live cache parse against the offline dump"),
			command("stanceaudit", "check every stance animation id and report weapon coverage"),
			command("chatwatch", "print every chat line with its type, for chatMessage rules"),
			command("mood [0-100]", "read or set the mood"),
			command("fire <rule-id>", "say a rule now, whatever its conditions"),
			command("force <what> [args]", "a feature's body now: look, notice, hesitate, shoulder,"
				+ " cutscene, wave, drop, sketch"),
			command("want", "what it is hoping for, and whether a question is open")));

	/**
	 * The force-paths, as (usage, effect) pairs: each feature's body
	 * behaviour now, through its own code, for the play session that would
	 * otherwise wait on luck. ::follower force lists them; ForceCommandsTest
	 * holds each to a case in forceNow, a place in the help above and a row
	 * in docs/DEVELOPMENT.md.
	 */
	static final java.util.List<java.util.Map.Entry<String, String>> FORCE_COMMANDS =
		java.util.Collections.unmodifiableList(java.util.Arrays.asList(
			command("look [camera|cursor]", "the look round now, or a look where the camera or cursor is"),
			command("notice", "the best thing in view noticed now: the walk, the look and the line"),
			command("hesitate", "the next Send hesitates over any NPC beside its spot"),
			command("shoulder", "the dialogue shoulder, as if an NPC dialogue had just opened"),
			command("cutscene on|off", "hold the cutscene behaviour as if one were playing"),
			command("wave", "the logout wave now"),
			command("drop <gp> [item name]", "the drop ladder's reaction to a drop at your feet"),
			command("sketch", "the chronicle sketch of this view now; an old one is kept as .bak")));

	/** The panel's command listing: the everyday commands, and the developer ones once they are on. */
	private java.util.List<String> commandLines()
	{
		java.util.List<String> lines = new java.util.ArrayList<>();
		lines.add("Type ::follower and one of:");
		for (java.util.Map.Entry<String, String> entry : EVERYDAY_COMMANDS)
		{
			lines.add(entry.getKey() + " - " + entry.getValue());
		}
		if (config.developerMode())
		{
			lines.add("");
			lines.add("Developer commands:");
			for (java.util.Map.Entry<String, String> entry : DEVELOPER_COMMAND_HELP)
			{
				lines.add(entry.getKey() + " - " + entry.getValue());
			}
		}
		return lines;
	}

	/** ::follower reload: re-reads the phrase and sound files from disk. */
	private void reloadFiles()
	{
		ruleLoader.reload();
		speechEngine.getContext().setNicknames(ruleLoader.getNicknames());
				speechEngine.getContext().setRegionNames(ruleLoader.getRegionNames());
		// The sounds file is one of ours on disk too, and the command
		// reads as "re-read my files". Leaving it out meant an
		// attachment edited by hand - or removed by hand - only took
		// effect when the Sounds window happened to be opened, which
		// is a strange place to hide a reload.
		voices.reload();
		sendStatus("Rules: " + ruleLoader.getStatus()
			+ "; sounds: " + voices.attachmentCount() + " attached");
		reportRuleErrors();
	}

	/** ::follower here: puts the follower at the player's feet. */
	private void placeHere()
	{
		clientThread.invoke(() ->
		{
			Player local = client.getLocalPlayer();
			if (local != null && appearanceService.getCurrent() != null)
			{
				follower.spawn(appearanceService.getCurrent(), local.getWorldLocation(),
					spawnGraphic(), config.spawnGraphicHeight());
				sendStatus("Here I am.");
			}
			else
			{
				sendStatus("No follower model to place. Try ::follower rebuild.");
			}
		});
	}

	/** ::follower rebuild: rebuilds the model from the current outfit. */
	private void rebuildFromOutfit()
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
	private void reattach()
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
	public void forceErrand(String key)
	{
		if (errands == null)
		{
			return;
		}
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
	 * ::follower force: one force-path now, on the client thread, and one
	 * line saying what it did or why it could not. Each goes through the
	 * feature's own code and skips only what leaves it to luck - a timer, a
	 * roll, a cooldown - never the gates that keep it safe, which stay the
	 * feature's own: a fight, a Stay, a follower already busy.
	 */
	String forceNow(String[] args)
	{
		String what = args[1].toLowerCase(Locale.ROOT);
		String arg = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : null;
		java.util.List<String> names = new java.util.ArrayList<>();
		for (java.util.Map.Entry<String, String> entry : FORCE_COMMANDS)
		{
			names.add(entry.getKey().split(" ")[0]);
		}
		if (!names.contains(what))
		{
			return "No force called '" + what + "'. Try: " + String.join(", ", names);
		}
		// The hold and the note for the next Send move nothing now. The rest
		// move the body, which has to be out, and is not theirs in a fight.
		if (!"cutscene".equals(what) && !"hesitate".equals(what))
		{
			if (!follower.isSpawned())
			{
				return "The follower is not out.";
			}
			if (speechEngine.getContext().isInCombat())
			{
				return "Not during a fight.";
			}
		}
		switch (what)
		{
			case "look":
				return gaze.forceLook(arg);
			case "notice":
				return noticing.forceNotice();
			case "hesitate":
				will.forceHesitate();
				return "The next Send hesitates over any NPC beside its spot, as over a strong one.";
			case "shoulder":
				return forceShoulder();
			case "cutscene":
				if ("on".equals(arg) || "off".equals(arg))
				{
					cutsceneForced = "on".equals(arg);
					return cutsceneForced
						? "Holding the cutscene: it keeps its place and looks where you look."
						: "The cutscene hold is off.";
				}
				return "::follower force cutscene on|off - the hold is " + (cutsceneForced ? "on." : "off.");
			case "wave":
				return moments.forceFarewell() ? "A wave, as at the logout tab." : "The follower is busy.";
			case "drop":
				return forceDrop(args);
			case "sketch":
				return sketching.forceSketch();
			default:
				// Listed and never dispatched; ForceCommandsTest keeps it from shipping.
				return "The force '" + what + "' is not wired up.";
		}
	}

	/**
	 * ::follower force drop: the drop ladder's reaction to a drop of that
	 * worth at the player's feet, as the loot would get once out of the
	 * fight's grace, and its words as ::follower loot says them. The bet
	 * is left unsettled: nothing was really won.
	 */
	private String forceDrop(String[] args)
	{
		int value;
		try
		{
			value = Integer.parseInt(args.length > 2 ? args[2] : "");
		}
		catch (NumberFormatException e)
		{
			return "::follower force drop <gp> [item name] - e.g. ::follower force drop 2500000 Dragon warhammer";
		}
		String item = args.length > 3
			? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length))
			: "Coins";
		Player local = client.getLocalPlayer();
		WorldPoint feet = local == null ? null : local.getWorldLocation();
		com.follower.follower.Moments.Tier tier = com.follower.follower.Moments.tierFor(value);
		speechEngine.dispatch(TriggerEvent.loot(value, item));
		String did = tier == null ? null : moments.forceNoteworthy(tier, feet);
		return item + " worth " + value + " at your feet: "
			+ (tier == null ? "too small for the ladder, so nothing." : did == null ? "nothing." : did + ".");
	}

	/**
	 * Writes the Follow me setting, the one record of follow versus stay. The
	 * caller has already moved the follower, so onConfigChanged finds nothing
	 * left to apply.
	 */
	private void setFollowEnabled(boolean enabled)
	{
		configManager.setConfiguration(FollowerConfig.GROUP, "followEnabled", enabled);
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
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Examined: met, at any distance.
		if (chronicleProgress != null && event.getMenuAction() == net.runelite.api.MenuAction.EXAMINE_NPC
			&& event.getMenuEntry().getNpc() != null)
		{
			chronicleProgress.noteNpc(event.getMenuEntry().getNpc().getName());
		}
		noteIntentFrom(event.getMenuOption());
		noteExamine(event);
		speechEngine.getContext().noteMenuOption(
			com.follower.follower.SkillingController.plainOption(event.getMenuOption()));
		if (skilling != null)
		{
			skilling.noteMenuOption(
				com.follower.follower.SkillingController.plainOption(event.getMenuOption()),
				com.follower.follower.SkillingController.plainOption(event.getMenuTarget()));
		}
		noteWorkClick(event);

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
	 * Co-attention: when the player Examines something, the follower turns to
	 * look at the same thing - a field scribe's whole job is looking at what
	 * its companion looks at. The head-turn IS the reaction; a spoken note is
	 * the rare garnish, left to the rules.
	 */
	private void noteExamine(MenuOptionClicked event)
	{
		if (!follower.isSpawned())
		{
			return;
		}
		String target = null;
		WorldPoint at = null;
		boolean creature = false;
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (watchExamine)
		{
			sendStatus("Examine: action " + event.getMenuAction()
				+ ", option '" + event.getMenuOption() + "', id " + event.getId());
		}
		switch (event.getMenuAction())
		{
			case EXAMINE_NPC:
			{
				NPC npc = event.getMenuEntry().getNpc();
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
				target = com.follower.follower.ErrandController.objectName(
					client, event.getId());
				if (view != null)
				{
					at = WorldPoint.fromScene(view,
						event.getParam0(), event.getParam1(), view.getPlane());
				}
				break;
			}
			case EXAMINE_ITEM_GROUND:
			{
				target = itemManager.getItemComposition(event.getId()).getName();
				if (view != null)
				{
					at = WorldPoint.fromScene(view,
						event.getParam0(), event.getParam1(), view.getPlane());
				}
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
				{
					return;
				}
				int itemId = event.getItemId();
				if (itemId <= 0)
				{
					// The event's own id is the widget's, not the item's, so
					// the id comes off the slot that was clicked.
					net.runelite.api.widgets.Widget parent =
						client.getWidget(event.getParam1());
					net.runelite.api.widgets.Widget slot = parent == null
						? null : parent.getChild(event.getParam0());
					itemId = slot == null ? -1 : slot.getItemId();
				}
				if (itemId > 0)
				{
					target = itemManager.getItemComposition(itemId).getName();
				}
				// No tile, deliberately: the thing is in a bag rather than in
				// the world, so there is nowhere to turn and look. The line
				// still lands; only the glance is skipped.
				break;
			}
			default:
				return;
		}
		if (at != null)
		{
			follower.glanceAt(at, 2400);
		}
		// Multiloc objects answer "null" until their impostor resolves; a
		// glance without a line is fine, a line naming "null" is not.
		if (watchExamine)
		{
			sendStatus("   resolved to '" + target + "'"
				+ (at == null ? ", no tile (no glance)" : " at " + at));
		}
		if (target != null && !target.isEmpty() && !"null".equals(target))
		{
			String named = target.replaceAll("<[^>]*>", "");
			speechEngine.dispatch(creature ? TriggerEvent.examinedCreature(named)
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

	/**
	 * The player clicked a work option - Chop down, Mine, Fish - on scenery
	 * or an NPC. The skilling company reads the click first, as the thieving
	 * intent does: it names the target before a single swing. Scenery is
	 * looked up on its own tile for the true footprint, since the click
	 * carries one tile of a two-by-two tree.
	 */
	private void noteWorkClick(MenuOptionClicked event)
	{
		if (skilling == null
			|| !com.follower.follower.SkillingController.isWorkOption(event.getMenuOption()))
		{
			return;
		}
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return;
		}
		switch (event.getMenuAction())
		{
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case WIDGET_TARGET_ON_GAME_OBJECT:
			{
				int id = event.getId();
				int plane = view.getPlane();
				int sx = event.getParam0();
				int sy = event.getParam1();
				WorldPoint min = WorldPoint.fromScene(view, sx, sy, plane);
				WorldPoint max = min;
				net.runelite.api.Tile[][][] tiles = view.getScene() == null
					? null : view.getScene().getTiles();
				if (tiles != null && plane >= 0 && plane < tiles.length && sx >= 0 && sy >= 0
					&& sx < tiles[plane].length && sy < tiles[plane][sx].length
					&& tiles[plane][sx][sy] != null)
				{
					for (net.runelite.api.GameObject object : tiles[plane][sx][sy].getGameObjects())
					{
						if (object != null && object.getId() == id
							&& object.getSceneMinLocation() != null
							&& object.getSceneMaxLocation() != null)
						{
							min = WorldPoint.fromScene(view, object.getSceneMinLocation().getX(),
								object.getSceneMinLocation().getY(), plane);
							max = WorldPoint.fromScene(view, object.getSceneMaxLocation().getX(),
								object.getSceneMaxLocation().getY(), plane);
							break;
						}
					}
				}
				skilling.noteClick(event.getMenuOption(),
					com.follower.follower.ErrandController.objectName(client, id), id, min, max, null);
				break;
			}
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
			case WIDGET_TARGET_ON_NPC:
			{
				NPC npc = event.getMenuEntry() == null ? null : event.getMenuEntry().getNpc();
				WorldPoint at = npc == null ? null : npc.getWorldLocation();
				if (at == null)
				{
					return;
				}
				int size = npc.getComposition() == null ? 1
					: Math.max(1, npc.getComposition().getSize());
				skilling.noteClick(event.getMenuOption(), npc.getName(), npc.getId(), at,
					new WorldPoint(at.getX() + size - 1, at.getY() + size - 1, at.getPlane()), npc);
				break;
			}
			default:
				break;
		}
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
			sendStatus("'" + sub + "' is a developer command. Turn on Developer commands"
				+ " in the plugin settings to use it."
				// The campfire errand shares the name; don't send somebody
				// chasing it into developer mode by accident.
				+ ("fire".equals(sub) ? " Looking for the campfire errand?"
					+ " That's ::follower errand fire." : ""));
			return;
		}

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
				{
					speechEngine.say(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)), null);
				}
				else
				{
					sendStatus("Usage: ::follower say <text>");
				}
				break;

			case "here":
				placeHere();
				break;

			case "posing":
				openFilmDialog();
				break;

			case "sketch":
				clientThread.invoke(this::askForSketch);
				break;

			case "rebuild":
				rebuildFromOutfit();
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
						// The setting's own range; its box clamps only what it shows (QA3).
						int asked = Integer.parseInt(args[1]);
						int offset = Math.max(-64, Math.min(64, asked));
						configManager.setConfiguration(FollowerConfig.GROUP, "verticalOffset", offset);
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
				{
					sendStatus("Usage: ::follower height <number>");
				}
				break;

			case "fix":
				reattach();
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
					sendStatus("Usage: ::follower head yaw|pitch|roll|zoom|crop|cliptop|talk|tune <value>");
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
							sendStatus("Usage: ::follower head yaw|pitch|roll|zoom|crop|cliptop|talk|tune <value>");
							return;
					}
					sendStatus("Chathead " + knob + " = " + args[2]
						+ " (updates live while a dialog is open).");
				}
				catch (NumberFormatException e)
				{
					sendStatus("Usage: ::follower head yaw|pitch|roll|zoom|crop|cliptop|talk|tune <value>");
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
				else
				{
					// A bare call answers with its usage, as the others do.
					sendStatus("Usage: ::follower pose <id>  (0 to release)");
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
				sendStatus("Tracing frames for about 8s. Keep the follower doing the thing "
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
						propBehindReach = false;
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

			case "examinewatch":
				watchExamine = !watchExamine;
				sendStatus(watchExamine
					? "Printing what every Examine click resolves to. Examine"
						+ " something. ::follower examinewatch again to stop."
					: "Stopped watching examines.");
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
					? "Watching your animations and " + (watchOthers ? "everyone's " : "")
						+ "graphics. Perform the one you want and its id is printed here."
						+ " ::follower watch again to stop."
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
				else
				{
					sendStatus("Usage: ::follower anim <id> [id ...]");
				}
				break;

			case "errand":
				forceErrand(args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null);
				break;

			case "errandscan":
			{
				if (errands != null)
				{
					errands.debugScan();
					sendStatus("Errand scan logged.");
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
					sendStatus("::follower gfx <graphicId> <height> - that far up its body");
					sendStatus("::follower gfx <graphicId> set - and keep it as the shield");
					break;
				}
				try
				{
					int id = Integer.parseInt(args[1]);
					// The third word keeps it as the shield, or lifts it: a
					// graphic built to sit on the body - a teleport's swirl, a
					// book's turning page - shows at the feet without the
					// height the game sends with it, which watch prints.
					boolean keep = args.length > 2 && "set".equalsIgnoreCase(args[2]);
					int height = args.length > 2 && !keep ? Integer.parseInt(args[2]) : 0;
					com.follower.appearance.SpotAnimRepository.Entry fx = spotAnimRepository.get(id);
					if (fx == null)
					{
						sendStatus("No graphic " + id + " in the cache.");
						break;
					}
					clientThread.invoke(() -> follower.playSpotAnim(fx, height));

					// Auditioning is only useful if the winner can be kept
					// without going hunting through the settings. All three
					// stages are set together: "the shield particle" is one
					// idea, and the stages differ by animation, not by effect.
					// Per-stage graphics remain available in the settings.
					if (keep)
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
					else if (height != 0)
					{
						sendStatus("Playing graphic " + id + " on the follower at height " + height + ".");
					}
					else
					{
						sendStatus("Playing graphic " + id + " on the follower."
							+ " Add 'set' to keep it as the shield.");
					}
				}
				catch (NumberFormatException e)
				{
					sendStatus("Numbers only: ::follower gfx <graphicId> [height|set]");
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
						sendStatus(stanceStatus(stanceLibrary, modelRepository.itemName(weaponId), weaponId));
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

			// Mood is felt through what the follower says rather than shown, so
			// this is the only way to see the number behind it.
			case "thieftargets":
			{
				// Answers "does the silence cover this target" without having
				// to rob one and watch. Reads the options the game itself
				// hands out, so it cannot drift from what the player sees.
				java.util.Map<String, String> found = new java.util.TreeMap<>();
				int scanned = 0;
				net.runelite.api.WorldView view = client.getTopLevelWorldView();
				for (NPC npc : view == null
					? java.util.Collections.<NPC>emptyList() : view.npcs())
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
					sendStatus("Fired '" + ruleId + "'.");
				}
				else
				{
					sendStatus("No rule called '" + ruleId + "'");
				}
				break;
			}

			// Fire says a rule's words; this does a feature's body, through
			// the feature's own code, for the ones that wait on luck.
			case "force":
			{
				if (args.length < 2)
				{
					sendStatus("::follower force <what>:");
					for (java.util.Map.Entry<String, String> entry : FORCE_COMMANDS)
					{
						sendStatus("  " + entry.getKey() + " - " + entry.getValue());
					}
					break;
				}
				clientThread.invoke(() -> sendStatus(forceNow(args)));
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
				sendStatus("Models: " + modelRepository.getStatus()
					+ " | Rules: " + ruleLoader.getStatus()
					+ " | Follower " + (follower.isSpawned() ? "spawned" : "not spawned"));
				// The skilling state is a developer's: raw item ids and names.
				if (config.developerMode())
				{
					sendStatus("Skilling: " + skilling.describe());
				}
				sendStatus("Region " + speechEngine.getContext().getRegionId()
					+ atTile(speechEngine.getContext().getLocation()));
				break;

			case "boat":
				dumpBoat();
				break;

			case "fires":
			{
				// Stand where the follower is misbehaving and run this. It
				// answers the only question that matters: does the follower
				// KNOW about the fire it is standing in.
				clientThread.invoke(() ->
				{
					net.runelite.api.Player me = client.getLocalPlayer();
					WorldPoint here = me == null ? null : me.getWorldLocation();
					for (String line : hotGround.describe(here, 12))
					{
						sendStatus(line);
						log.info("FIRES {}", line);
					}
					for (String line : follower.describeFeet())
					{
						sendStatus(line);
						log.info("FIRES {}", line);
					}
					if (skilling != null)
					{
						sendStatus("Skilling: " + skilling.describe());
						log.info("FIRES skilling {}", skilling.describe());
					}
				});
				break;
			}

			case "where":
				// Split from status: every place that advertises this command
				// promises the region id, which used to arrive buried after a
				// model report nobody asked for.
				sendStatus("You're in region " + speechEngine.getContext().getRegionId()
					+ onTile(speechEngine.getContext().getLocation())
					+ ". Use that number under Locations in the Messages window.");
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
				for (java.util.Map.Entry<String, String> entry : EVERYDAY_COMMANDS)
				{
					sendStatus("  " + entry.getKey() + " - " + entry.getValue());
				}
				sendStatus("More in the Follower Buddy side panel.");
				if (config.developerMode())
				{
					sendStatus("Developer: watch | stance <weaponId> [idle walk run attack] | "
						+ "priorities | palette | harvest | hidden | "
						+ "pitchsweep | headsweep | head <...> | followtrace | boat | "
						+ "wraplerp | wrapauto | wrapearly | animinfo | "
						+ "animtrace | errandscan | cachecheck | stanceaudit | examinewatch | "
						+ "fires | "
						+ "mood [0-100] | chatwatch | sniffanims | finditem <name> | "
						+ "prop <itemId> | propoffset <x y z> | fire <rule-id> | force <what> | want | "
						+ "spectate | shield | centre [units] | loot <value> | "
						+ "scan | heights | transcript | thieftargets");
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
		private long queuedAtMs;

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
		if (cutForTheFilm(filming, rule))
		{
			return;
		}
		// Nor a silent rule's gesture while a sketch is under way: an idle
		// fidget played over the scroll, and could be in the picture. It is
		// body language, not worth playing late, so it goes. So in its own
		// Talk-to box: behaviour QA4, 16 Oct, it yawned, shrugged and struck
		// its distant pose while its chathead was talking to you.
		if (rule != null && text.isEmpty()
			&& ((sketching != null && sketching.isSketching()) || dialog.isOpen()))
		{
			return;
		}
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
		if ((sketching != null && sketching.isPosing()) || dialog.isOpen())
		{
			if (speechQueue.size() < SPEECH_QUEUE_LIMIT)
			{
				speechQueue.addLast(new Utterance(text, output, rule, animationId, onSaid));
			}
			return;
		}

		long now = System.currentTimeMillis();
		if (now < speakingUntilMs || keptForTheBox(rule, inConversation()))
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
		// A line that waits its turn is kept through a conversation - an
		// NPC's, or the follower's own box - and its staleness counts from the
		// box closing. Every such line in the queue, not only the first:
		// behaviour QA, 16 Oct, the second was dropped as stale as the box
		// closed, and one kept through the follower's own box aged out in it.
		boolean box = dialog.isOpen() || inConversation();
		for (Utterance queued : speechQueue)
		{
			if (keptForTheBox(queued.rule, box))
			{
				queued.queuedAtMs = now;
			}
		}
		// Nothing overhead while the follower is talking to you in the box:
		// a bubble above its head mid-conversation is two mouths at once.
		// Held, not dropped - the stale sweep below decides what survives
		// a long conversation, same as any other wait.
		if (dialog.isOpen())
		{
			return;
		}
		// Nor over a sketch's count, nor into its picture: held the same way.
		if (sketching != null && sketching.isPosing())
		{
			return;
		}
		// Nor into an NPC conversation, a line that waits and what is behind
		// it: said into the box it would be dropped.
		Utterance next = speechQueue.peekFirst();
		if (next != null && keptForTheBox(next.rule, inConversation()))
		{
			return;
		}
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
		Utterance line = speechQueue.removeFirst();
		// Kept waiting here, as in the engine, the moment it was about can
		// pass: behaviour QA4, 16 Oct, Hans was announced as the follower's
		// own box closed, 24 s after he had gone. Let go unsaid.
		if (line.rule != null && line.rule.when != null
			&& !line.rule.when.standingHolds(speechEngine.getContext(), TriggerEvent.tick()))
		{
			return;
		}
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
	private long readingTimeMs(String text)
	{
		return Math.min(MAX_SPEECH_MS, Math.max(config.speechDurationMs(), timeToReadMs(text)));
	}

	/** How long the line itself takes to read at the player's speed, with no minimum. */
	private long timeToReadMs(String text)
	{
		return (long) text.length() * 1000L / Math.max(1, config.readingSpeed());
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

	/**
	 * Whether a line is cut while a scene is filmed: anything a rule says,
	 * and with it the rule's emote copies, plants and chains, which all come
	 * through here. A line with no rule is the player's own - typed in the
	 * film window, or ::follower say - and is said.
	 */
	static boolean cutForTheFilm(boolean filming, SpeechRule rule)
	{
		return filming && rule != null;
	}

	private void speakNow(Utterance utterance)
	{
		SpeechOutput output = utterance.output;
		SpeechRule rule = utterance.rule;
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
		{
			utterance.onSaid.run();
		}

		if (!hushed)
		{
			journal.spoke(rule, text);
		}

		// The line's own voice, if the player has given it one. After the
		// journal so a sounded line is recorded like any other, and before
		// the text so the mouth and the words start together.
		// Played off the client thread: opening the file and the audio line
		// is disk and driver work, enough to drop a frame (QA pass 4).
		if (!text.isEmpty() && config.voiceEnabled() && voices.has(text))
		{
			int volume = config.voiceVolume();
			executor.execute(() -> voices.play(text, volume));
		}

		if (!text.isEmpty() && output.showsOverhead())
		{
			long showFor = readingTimeMs(text);
			overlay.show(text, showFor);
			onScreen = utterance;
			onScreenSinceMs = System.currentTimeMillis();
			speakingUntilMs = System.currentTimeMillis() + showFor + SPEECH_GAP_MS;
		}

		if (!text.isEmpty() && postsToChatbox(config.mirrorToChat(), output))
		{
			// Every spoken line lands in the chatbox as PUBLIC CHAT under the
			// follower's name - "Name: message" in the game's own chat colours
			// - exactly as a real player's overhead words mirror into chat.
			clientThread.invoke(() -> client.addChatMessage(
				ChatMessageType.PUBLICCHAT, followerName(), text, null));
		}

		// The writing flourish: the scroll comes out as the line is said, so
		// the claim and the act happen together. Best effort - mid-errand or
		// otherwise spoken for, the words stand alone, which still reads fine;
		// the gesture without the words never happens the other way round.
		// Sat on the rest, a rule's gesture had the follower pop up off the
		// seat with no stand-up, and sit down again after it (QA, 15 Oct).
		// The words come and the body stays seated. Not a copy of the
		// player's own animation: that ends the rest a tick later anyway.
		boolean seated = resting && rule != null && !Boolean.TRUE.equals(rule.mirrorAnimation)
			&& !Boolean.TRUE.equals(rule.mirrorPose);

		if (rule != null && rule.prop != null && flourish != null
			&& !text.isEmpty() && !seated
			&& follower.isSpawned() && !follower.isStaying()
			&& !follower.isNpcSlaved() && !errands.isBusy() && !dialog.isOpen())
		{
			clientThread.invoke(() -> flourish.start(
				rule.prop.left == null ? 0 : rule.prop.left,
				rule.prop.item == null ? 0 : rule.prop.item,
				rule.prop.pose == null ? 0 : rule.prop.pose,
				rule.prop.ticks == null ? 8 : rule.prop.ticks));
		}

		if (seated)
		{
			return;
		}

		// A copy of the work is made from beside the WORK, not from the
		// seat: the seat is placed against the PLAYER, so a chop copied from
		// it is an axe swung at open air two tiles from the trunk. The walk
		// over is the controller's; the copy waits here until it has got
		// there, and is refused outright when there is no work to stand at.
		boolean atTheWork = rule != null && Boolean.TRUE.equals(rule.atTheWork)
			&& utterance.animationId > 0;
		if (atTheWork)
		{
			// A loop is copied only while the player is still at it: the rule
			// can fire on the last few ticks' memory of the swing, after the
			// tree has fallen and the player has stopped.
			Player local = client.getLocalPlayer();
			boolean stopped = Boolean.TRUE.equals(rule.mirrorPose)
				&& (local == null || local.getAnimation() != utterance.animationId);
			if (stopped || skilling == null || !skilling.joinIn())
			{
				if (skilling != null && skilling.joiningIn())
				{
					pendingMime = utterance.animationId;
					pendingOneShot = !Boolean.TRUE.equals(rule.mirrorPose);
				}
				return;
			}
			copyAtWork = true;
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
		// Not at the work, where the walk over already holds the feet, and
		// not on a park the player gave: the clip plays at the post, and the
		// plant's release at its end would hand the follower back to following.
		if (rule != null && Boolean.TRUE.equals(rule.holdStill) && rule.hasAnimationAction()
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
			// Said on the move, the gesture was cancelled on its first frame:
			// moving drops an emote. Entering a place, a crowd, a pet in range
			// and a greeting typed mid-walk all usually come on the move, so
			// the line was said and the body did nothing (QA, 15 Oct). It
			// waits for the follower to stand, a few ticks at most.
			boolean waits = rule != null && !Boolean.TRUE.equals(rule.holdStill) && !atTheWork
				&& !Boolean.TRUE.equals(rule.vanishAfter);
			clientThread.invoke(() ->
			{
				if (waits)
				{
					follower.playWhenStill(animationId, EMOTE_WAIT_TICKS);
				}
				else
				{
					follower.playAnimation(animationId);
				}
			});
		}

		// A teleporting follower leaves with its cast: once the mirrored
		// animation finishes, vanish and reappear beside the landed player.
		if (rule != null && Boolean.TRUE.equals(rule.vanishAfter))
		{
			clientThread.invoke(follower::vanishAfterEmote);
		}

		// A rule that copies graphics opens the graphic-copy window, and any
		// spotanim already showing on the player (it can precede the animation
		// event) is copied immediately.
		if (rule != null && Boolean.TRUE.equals(rule.copiesGraphics))
		{
			mirrorGraphicsUntilTick = client.getTickCount() + MIRROR_GRAPHIC_WINDOW_TICKS;
			clientThread.invoke(() ->
			{
				Player local = client.getLocalPlayer();
				net.runelite.api.ActorSpotAnim active = local == null ? null : latestSpotAnim(local);
				if (active != null && takesMirroredGraphic(client.getTickCount()))
				{
					mirrorGraphic(active.getId(), active.getHeight());
				}
			});
		}
	}

	/**
	 * Whether the problems the rule and dialog files had when the plugin
	 * started have been told. Read at startup, before there is a chat to
	 * tell them in, so a phrases.json with a syntax error left the follower
	 * silent and nothing said why (QA, 15 Oct). Once per start, at the login.
	 */
	private boolean loadErrorsReported;

	private void reportLoadErrors()
	{
		if (loadErrorsReported)
		{
			return;
		}
		loadErrorsReported = true;
		reportRuleErrors();
		for (String problem : dialogLoader.getErrors())
		{
			sendStatus("Problem in dialogs.json: " + problem);
		}
	}

	private void reportRuleErrors()
	{
		for (String error : ruleLoader.getErrors())
		{
			sendStatus("Problem in phrases.json: " + error);
		}
		if (ruleLoader.getRules().isEmpty())
		{
			sendStatus("Couldn't read phrases.json, so the follower is quiet until it's fixed.");
		}
	}

	private String modelHint()
	{
		return modelRepository.isLoaded()
			? "The item list is loaded (" + modelRepository.getStatus() + ") but has no data for these slots."
			: "The game cache hasn't been read yet. Log in, then try ::follower rebuild.";
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
				|| (idleTicks >= Math.max(1, afterTicks) && idleTicks < REST_AFTER_TICKS));
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
		boolean thrall, boolean errandBusy, boolean spectating, boolean emoteHold,
		boolean takingMinutes, boolean keepingCompany)
	{
		return staying && !wandered && !thrall && !errandBusy && !spectating
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
	private void offerName()
	{
		final boolean[] named = {false};
		final boolean[] done = {false};
		chatboxPanelManager.openTextInput("Your new follower is waiting. What will you call it?")
			.value("")
			.charValidator(c -> nameChar((char) c))
			.onDone((String answer) ->
			{
				done[0] = true;
				String name = cleanFollowerName(answer);
				if (!name.isEmpty())
				{
					named[0] = true;
					configManager.setConfiguration(FollowerConfig.GROUP,
						"followerName", name);
					clientThread.invoke(() ->
						speechEngine.say(name + ". I'll answer to that.",
							com.follower.speech.SpeechOutput.OVERHEAD));
					// The 20-char cap in cleanFollowerName is silent; a pasted
					// longer name should hear where the rest went.
					if (name.length() == 20 && answer != null
						&& answer.trim().length() > 20)
					{
						sendStatus("Twenty characters is all a name can carry,"
							+ " so '" + name + "' it is.");
					}
					sendStatus("You can change the name any time in the"
						+ " plugin's settings, under Follower.");
				}
			})
			// Every way the input can end lands here (Enter runs onDone first,
			// then this). Marked here rather than at the offer, so a prompt
			// the client never got to close does not count as offered.
			.onClose(() ->
			{
				configManager.setConfiguration(FollowerConfig.GROUP,
					"namePrompted", true);
				if (!done[0] && nameOfferRetries++ == 0)
				{
					// Closed without Enter: Esc, or destroyed by another
					// interface opening. The client routes both through the
					// same close (verified against the client's chatbox
					// manager), so the callbacks cannot tell an ambushed
					// prompt from a declined one - one quiet re-offer covers
					// the ambush without nagging the decliner.
					namePromptDelayTicks = NAME_PROMPT_DELAY_TICKS;
				}
				else if (!named[0])
				{
					sendStatus("No name for now, then. You can change the name"
						+ " any time in the plugin's settings, under Follower.");
				}
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
	private String followerTarget()
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
		{
			return "";
		}
		StringBuilder kept = new StringBuilder();
		for (int i = 0; i < raw.length(); i++)
		{
			char c = raw.charAt(i);
			if (nameChar(c))
			{
				kept.append(c);
			}
		}
		String name = kept.toString().trim().replaceAll(" {2,}", " ");
		if (name.length() > 20)
		{
			name = name.substring(0, 20).trim();
		}
		return name;
	}

	/**
	 * Two chat lines, once ever, at the very first spawn: that the follower is
	 * client-side (the one fact a Hub user most needs and nothing in game ever
	 * states), and that ::follower exists (nothing else in game names it).
	 */
	private void maybeSendCommandHint()
	{
		if (config.firstSpawnNoticeShown())
		{
			return;
		}
		configManager.setConfiguration(FollowerConfig.GROUP, "firstSpawnNoticeShown", true);
		sendStatus("Only you can see " + followerName()
			+ ". Everything it does happens on your screen, and nothing is sent to the game.");
		sendStatus("Type ::follower in chat for commands.");
	}

	/**
	 * Send is unreachable by exploration (it needs Shift held before the menu
	 * opens), so it is taught once, at the first Stay - the moment the user
	 * has just demonstrated they want positional control.
	 */
	private void maybeSendSendHint()
	{
		if (config.sendHintShown())
		{
			return;
		}
		configManager.setConfiguration(FollowerConfig.GROUP, "sendHintShown", true);
		sendStatus("You can also hold Shift and right-click any tile to Send "
			+ followerName() + " there.");
	}

	// ---------------------------------------------------------------- sharing

	/** A file chooser pointed at the follower's own folder, for packs. */
	private java.io.File choosePack(boolean saving, java.awt.Component parent)
	{
		javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(dataDir.toFile());
		chooser.setDialogTitle(saving ? "Save your pack" : "Open a pack");
		chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
			"Follower Buddy pack (*.zip)", "zip"));
		int answer = saving
			? chooser.showSaveDialog(parent)
			: chooser.showOpenDialog(parent);
		if (answer != javax.swing.JFileChooser.APPROVE_OPTION)
		{
			return null;
		}
		java.io.File picked = chooser.getSelectedFile();
		if (saving && !picked.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".zip"))
		{
			picked = new java.io.File(picked.getParentFile(), picked.getName() + ".zip");
		}
		return picked;
	}

	/** Writes everything the player has added to a pack they can hand over. */
	private void exportPack(java.io.File target, java.awt.Component parent)
	{
		try
		{
			com.google.gson.JsonObject mine = gson.fromJson(
				new String(java.nio.file.Files.readAllBytes(ruleLoader.getFile()),
					java.nio.charset.StandardCharsets.UTF_8),
				com.google.gson.JsonObject.class);
			com.google.gson.JsonObject shipped =
				com.follower.share.PackFile.shippedRules(gson);
			if (shipped == null)
			{
				// No defaults to diff against means everything would look like
				// the player's own work, and the pack would carry all two
				// thousand shipped lines. Better to write nothing.
				tell(parent,
					"The plugin's own lines could not be read, so there is nothing"
						+ " to compare yours against. No pack was written.",
					"Pack not saved", javax.swing.JOptionPane.ERROR_MESSAGE);
				return;
			}

			com.follower.share.PackFile.Summary made = com.follower.share.PackFile.export(
				target.toPath(), mine, shipped, voices, gson, followerName());

			if (made.isEmpty())
			{
				tell(parent,
					"There is nothing of your own to share yet. Add a line or a rule"
						+ " in the Messages window first, and it will go in the pack.",
					"Nothing to share", javax.swing.JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			tell(parent,
				"Saved " + target.getName() + " with " +
					+ made.rules + " rule(s), " + made.lines + " line(s), "
					+ made.sounds + " sound(s)."
					+ (made.problems.isEmpty() ? ""
					: System.lineSeparator() + System.lineSeparator() + String.join(System.lineSeparator(), made.problems)),
				"Pack saved", javax.swing.JOptionPane.INFORMATION_MESSAGE);
		}
		catch (Exception e)
		{
			log.warn("Could not write the pack", e);
			tell(parent,
				"That pack could not be written: " + e.getMessage(),
				"Pack not saved", javax.swing.JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Takes on somebody else's pack, after showing exactly what it holds. The
	 * file is read and checked in full before a single line of it is kept.
	 */
	private void importPack(java.io.File source, java.awt.Component parent)
	{
		try
		{
			com.follower.share.PackFile.Loaded loaded =
				com.follower.share.PackFile.read(source.toPath(), gson);
			javax.swing.SwingUtilities.invokeLater(() -> takePack(loaded, parent));
		}
		catch (Exception e)
		{
			log.warn("Could not read the pack", e);
			tell(parent, "That pack could not be read: " + e.getMessage(),
				"Pack not imported", javax.swing.JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * The read pack, asked about and added, on the event thread: the Messages
	 * window saves the same file there, so nothing can save between this
	 * reading the file and writing it back.
	 */
	private void takePack(com.follower.share.PackFile.Loaded loaded, java.awt.Component parent)
	{
		// Edits begun while the pack was read would save over what it adds.
		if (messagesDialog != null && messagesDialog.isDirty())
		{
			sendStatus("Save or discard the edits in the Messages window first.");
			return;
		}
		try
		{
			if (loaded.summary.isEmpty())
			{
				javax.swing.JOptionPane.showMessageDialog(parent,
					"There was nothing usable in that pack."
						+ (loaded.summary.problems.isEmpty() ? ""
						: System.lineSeparator() + System.lineSeparator() + String.join(System.lineSeparator(), loaded.summary.problems)),
					"Nothing to import", javax.swing.JOptionPane.WARNING_MESSAGE);
				return;
			}

			String from = loaded.summary.author == null || loaded.summary.author.isEmpty()
				? "" : " from " + loaded.summary.author;
			int confirm = javax.swing.JOptionPane.showConfirmDialog(parent,
				"This pack" + from + " holds:" + System.lineSeparator()
					+ System.lineSeparator() + "  " + loaded.summary.rules + " rule(s)"
					+ System.lineSeparator() + "  " + loaded.summary.lines + " line(s)"
					+ System.lineSeparator() + "  " + loaded.summary.sounds + " sound file(s), "
					+ (loaded.summary.bytes / 1024) + " KB"
					+ System.lineSeparator() + System.lineSeparator() + "They are added to yours. Nothing you have"
					+ " written is replaced."
					+ (loaded.summary.problems.isEmpty() ? ""
					: System.lineSeparator() + System.lineSeparator() + "Left out:" + System.lineSeparator()
						+ String.join(System.lineSeparator(), loaded.summary.problems)),
				"Import this pack?", javax.swing.JOptionPane.OK_CANCEL_OPTION);
			if (confirm != javax.swing.JOptionPane.OK_OPTION)
			{
				return;
			}

			com.google.gson.JsonObject mine = gson.fromJson(
				new String(java.nio.file.Files.readAllBytes(ruleLoader.getFile()),
					java.nio.charset.StandardCharsets.UTF_8),
				com.google.gson.JsonObject.class);

			com.follower.share.PackFile.Summary took = com.follower.share.PackFile.apply(
				loaded, mine, voices.getSoundsFolder(), voices);
			com.follower.share.PackFile.save(ruleLoader.getFile(), mine,
				gson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create());

			javax.swing.JOptionPane.showMessageDialog(parent,
				"Taken on " + took.lines + " new line(s) across " + took.rules
					+ " rule(s), and " + took.sounds + " sound file(s)."
					+ System.lineSeparator() + System.lineSeparator() + "The follower has them already."
					+ (took.problems.isEmpty() ? ""
					: System.lineSeparator() + System.lineSeparator() + "Left out:"
						+ System.lineSeparator()
						+ String.join(System.lineSeparator(), took.problems)),
				"Pack imported", javax.swing.JOptionPane.INFORMATION_MESSAGE);
			if (messagesDialog != null && messagesDialog.isVisible())
			{
				// The file has grown underneath the window; show what it says now.
				messagesDialog.open();
			}
		}
		catch (Exception e)
		{
			log.warn("Could not read the pack", e);
			javax.swing.JOptionPane.showMessageDialog(parent,
				"That pack could not be read: " + e.getMessage(),
				"Pack not imported", javax.swing.JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Developer dump of the world views around the player, for boats: which
	 * view the player stands in, what its collision map says for the nine by
	 * nine tiles around them, read the way the follower reads it, against the
	 * sea map underneath, every object on those tiles, and the tiles the
	 * player has stood on since developer mode went on. Written to the client
	 * log. A walked tile marked blocked means the map is being misread.
	 */
	private void dumpBoat()
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			sendStatus("Not logged in.");
			return;
		}
		WorldPoint at = me.getWorldLocation();
		net.runelite.api.WorldView top = client.getTopLevelWorldView();
		StringBuilder out = new StringBuilder();
		out.append("BOAT player at ").append(at).append(" local ").append(me.getLocalLocation())
			.append("\n  player view: ").append(describeView(me.getWorldView()))
			.append("\n  found view : ").append(describeView(client.findWorldViewFromWorldPoint(at)))
			.append("\n  top view   : ").append(describeView(top))
			.append("\n  follower at ").append(follower.getWorldLocation())
			.append("\n  flags array: ").append(describeFlags(client.findWorldViewFromWorldPoint(at), at.getPlane()))
			.append("\n  your tile in the scene array: ").append(describeTile(client.findWorldViewFromWorldPoint(at), at))
			.append("\n  walked tiles remembered: ").append(walked.size())
			.append("\n  grid: P you, . open, # blocked, o walked+open, X walked+BLOCKED, w wall bits, x other bits, - not in view, ? no data\n");
		int lines = 0;
		for (int dy = 4; dy >= -4; dy--)
		{
			StringBuilder row = new StringBuilder();
			StringBuilder sea = new StringBuilder();
			for (int dx = -4; dx <= 4; dx++)
			{
				WorldPoint t = new WorldPoint(at.getX() + dx, at.getY() + dy, at.getPlane());
				char c = flagSymbol(client.findWorldViewFromWorldPoint(t), t, dx == 0 && dy == 0);
				if (c != 'P' && walked.contains(t))
				{
					c = c == '#' ? 'X' : c == '.' ? 'o' : c;
				}
				row.append(c);
				sea.append(flagSymbol(top, t, dx == 0 && dy == 0));
			}
			out.append("  ").append(row).append("   sea: ").append(sea).append('\n');
			lines++;
		}
		for (int dy = 4; dy >= -4; dy--)
		{
			for (int dx = -4; dx <= 4; dx++)
			{
				WorldPoint t = new WorldPoint(at.getX() + dx, at.getY() + dy, at.getPlane());
				net.runelite.api.WorldView v = client.findWorldViewFromWorldPoint(t);
				net.runelite.api.coords.LocalPoint lp = v == null ? null : net.runelite.api.coords.LocalPoint.fromWorld(v, t);
				if (lp == null)
				{
					continue;
				}
				StringBuilder objs = new StringBuilder();
				net.runelite.api.Tile[][][] tiles = v.getScene() == null ? null : v.getScene().getTiles();
				net.runelite.api.Tile tile = tiles == null || t.getPlane() >= tiles.length
					|| lp.getSceneX() >= tiles[t.getPlane()].length
					|| lp.getSceneY() >= tiles[t.getPlane()][lp.getSceneX()].length
					? null : tiles[t.getPlane()][lp.getSceneX()][lp.getSceneY()];
				if (tile != null)
				{
					if (tile.getGameObjects() != null)
					{
						for (net.runelite.api.GameObject g : tile.getGameObjects())
						{
							if (g != null)
							{
								objs.append(" obj ").append(g.getId()).append(' ').append(objectName(g.getId()))
									.append(' ').append(g.sizeX()).append('x').append(g.sizeY());
							}
						}
					}
					if (tile.getWallObject() != null)
					{
						objs.append(" wall ").append(tile.getWallObject().getId());
					}
					if (tile.getGroundObject() != null)
					{
						objs.append(" ground ").append(tile.getGroundObject().getId());
					}
					if (tile.getDecorativeObject() != null)
					{
						objs.append(" decor ").append(tile.getDecorativeObject().getId());
					}
				}
				Integer flags = flagsIn(v, lp, t.getPlane());
				if (objs.length() > 0 || (flags != null && flags != 0))
				{
					out.append("  (").append(dx).append(',').append(dy).append(") view ").append(v.getId())
						.append(" scene ").append(lp.getSceneX()).append(',').append(lp.getSceneY())
						.append(" flags ").append(flags == null ? "none" : "0x" + Integer.toHexString(flags))
						.append(objs).append('\n');
					lines++;
				}
			}
		}
		log.info(out.toString());
		sendStatus("Boat dump written to the client log, " + lines + " lines.");
	}

	private String objectName(int id)
	{
		try
		{
			net.runelite.api.ObjectComposition def = client.getObjectDefinition(id);
			return def == null ? "?" : def.getName();
		}
		catch (Exception e)
		{
			return "?";
		}
	}

	private static String describeFlags(net.runelite.api.WorldView v, int plane)
	{
		if (v == null || v.getCollisionMaps() == null || plane >= v.getCollisionMaps().length
			|| v.getCollisionMaps()[plane] == null)
		{
			return "none";
		}
		int[][] flags = v.getCollisionMaps()[plane].getFlags();
		StringBuilder edge = new StringBuilder();
		for (int x = 0; x < flags.length && x < 12; x++)
		{
			edge.append(" 0x").append(Integer.toHexString(flags[x][Math.min(10, flags[x].length - 1)]));
		}
		return flags.length + "x" + (flags.length == 0 ? 0 : flags[0].length)
			+ " for a view " + v.getSizeX() + "x" + v.getSizeY()
			+ " | raw row [x][10] by x:" + edge;
	}

	private static String describeTile(net.runelite.api.WorldView v, WorldPoint at)
	{
		net.runelite.api.coords.LocalPoint lp = v == null ? null
			: net.runelite.api.coords.LocalPoint.fromWorld(v, at);
		if (lp == null || v.getScene() == null || v.getScene().getTiles() == null)
		{
			return "unresolved";
		}
		net.runelite.api.Tile[][][] tiles = v.getScene().getTiles();
		net.runelite.api.Tile tile = at.getPlane() < tiles.length
			&& lp.getSceneX() < tiles[at.getPlane()].length
			&& lp.getSceneY() < tiles[at.getPlane()][lp.getSceneX()].length
			? tiles[at.getPlane()][lp.getSceneX()][lp.getSceneY()] : null;
		return "array " + tiles.length + "x" + tiles[0].length + "x" + tiles[0][0].length
			+ " looked up [" + at.getPlane() + "][" + lp.getSceneX() + "][" + lp.getSceneY() + "] -> "
			+ (tile == null ? "null" : "scene " + tile.getSceneLocation() + " world " + tile.getWorldLocation()
				+ " plane " + tile.getPlane());
	}

	private static String describeView(net.runelite.api.WorldView v)
	{
		if (v == null)
		{
			return "null";
		}
		net.runelite.api.CollisionData[] maps = v.getCollisionMaps();
		return "id " + v.getId() + (v.isTopLevel() ? " top" : " sub") + " base " + v.getBaseX() + ","
			+ v.getBaseY() + " size " + v.getSizeX() + "x" + v.getSizeY() + " plane " + v.getPlane()
			+ " maps " + (maps == null ? "null" : String.valueOf(maps.length));
	}

	/** The follower's own read, inset and all, so the dump shows what it sees. */
	private static Integer flagsIn(net.runelite.api.WorldView v, net.runelite.api.coords.LocalPoint lp, int plane)
	{
		return com.follower.follower.FollowerEntity.sceneFlags(v, plane, lp.getSceneX(), lp.getSceneY());
	}

	private static char flagSymbol(net.runelite.api.WorldView v, WorldPoint t, boolean me)
	{
		if (v == null)
		{
			return '?';
		}
		net.runelite.api.coords.LocalPoint lp = net.runelite.api.coords.LocalPoint.fromWorld(v, t);
		if (lp == null)
		{
			return '-';
		}
		Integer flags = flagsIn(v, lp, t.getPlane());
		if (flags == null)
		{
			return '?';
		}
		if (me)
		{
			return 'P';
		}
		if ((flags & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0)
		{
			return '#';
		}
		if ((flags & 0xFF) != 0)
		{
			return 'w';
		}
		return flags == 0 ? '.' : 'x';
	}

	private void sendStatus(String message)
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

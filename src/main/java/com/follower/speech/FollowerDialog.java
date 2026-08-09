package com.follower.speech;

import com.follower.follower.FollowerEntity;
import com.follower.ui.ChatheadRenderer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * A conversation with the follower, drawn over the chatbox in the game's own
 * dialog style: chathead, speaker name, click-to-continue pages, and "Select an
 * Option" menus that branch.
 *
 * <p>Drawn rather than driven through the game's dialog interface because the
 * real dialog widgets only exist while the game itself has a conversation open,
 * and no script exists for a plugin to open one. The chathead is genuine even so
 * - {@link ChatheadRenderer} projects the follower's actual model - which is the
 * part a widget could never have shown, since a widget takes a model id and the
 * follower's model is composed in memory.
 */
@Singleton
@lombok.extern.slf4j.Slf4j
public class FollowerDialog extends Overlay
{
	/** One step of a conversation: some pages, then either options or a jump. */
	public static final class Node
	{
		private final String[] pages;
		private final boolean playerSpeaking;
		private String[] optionText = new String[0];
		private String[] optionNext = new String[0];
		private String next;

		/** Resolved fresh on every ENTRY to the node - a re-rolled joke, say. */
		private java.util.function.Supplier<String[]> dynamicPages;

		/** Latched when the node is reached, run when the conversation ENDS. */
		private Runnable onFinish;

		private Node(boolean playerSpeaking, String[] pages)
		{
			this.playerSpeaking = playerSpeaking;
			this.pages = pages;
		}

		/** The follower speaks these pages. */
		public static Node says(String... pages)
		{
			return new Node(false, pages);
		}

		/** The follower speaks pages resolved fresh each visit. */
		public static Node saysDynamic(java.util.function.Supplier<String[]> pages)
		{
			Node node = new Node(false, new String[0]);
			node.dynamicPages = pages;
			return node;
		}

		/** The player speaks these pages, with their own head and name. */
		public static Node you(String... pages)
		{
			return new Node(true, pages);
		}

		/** Continue to another node once the pages are done. */
		public Node then(String nodeId)
		{
			this.next = nodeId;
			return this;
		}

		/**
		 * Do something once the conversation is over, if this node was reached.
		 *
		 * <p>How a conversation reaches back out: reaching the node under "Go on
		 * then" is what tells the rules the player said yes.
		 *
		 * <p>Latched on arrival but run at the END, which is the whole point.
		 * The follower's reply to its own question goes overhead, and firing it
		 * the instant the branch is picked puts it above a dialog box the
		 * player is still reading - so the one line they were waiting for is
		 * the one they miss. Latched rather than run at the end unconditionally
		 * so that closing the box early still counts: the player answered, and
		 * whether they read the rest of it is their business.
		 */
		public Node onFinish(Runnable action)
		{
			this.onFinish = action;
			return this;
		}

		/** Offer choices once the pages are done; pairs of label and target id. */
		public Node choices(String... labelThenTarget)
		{
			int count = labelThenTarget.length / 2;
			optionText = new String[count];
			optionNext = new String[count];
			for (int i = 0; i < count; i++)
			{
				optionText[i] = labelThenTarget[i * 2];
				optionNext[i] = labelThenTarget[i * 2 + 1];
			}
			return this;
		}
	}

	/**
	 * Turns a loaded {@link DialogTree} into a runnable conversation.
	 *
	 * <p>The everyday talk script is built in Java and stays that way - it does
	 * not change, so a file would buy nothing. These are the ones the follower
	 * OPENS, and they are content: whoever writes them should not need a
	 * compiler, and the one thing they must be able to do from the file is say
	 * which branch counts as the answer.
	 *
	 * @param onAnswer handed "yes" or "no" the moment a branch carrying one is
	 * reached
	 */
	public static Map<String, Node> build(DialogTree tree,
		java.util.function.Consumer<String> onAnswer)
	{
		Map<String, Node> script = new LinkedHashMap<>();
		if (tree == null || tree.nodes == null)
		{
			return script;
		}

		for (DialogTree.DialogNode source : tree.nodes)
		{
			if (source == null || source.id == null)
			{
				continue;
			}

			String[] pages = source.pages().toArray(new String[0]);
			Node node = source.isPlayerSpeaking() ? Node.you(pages) : Node.says(pages);

			if (source.choices != null && !source.choices.isEmpty())
			{
				String[] pairs = new String[source.choices.size() * 2];
				for (int i = 0; i < source.choices.size(); i++)
				{
					DialogTree.DialogChoice choice = source.choices.get(i);
					pairs[i * 2] = choice.label;
					pairs[i * 2 + 1] = choice.next;
				}
				node.choices(pairs);
			}
			else if (source.next != null)
			{
				node.then(source.next);
			}

			if (source.answer != null && onAnswer != null)
			{
				String answer = source.answer;
				node.onFinish(() -> onAnswer.accept(answer));
			}

			script.put(source.id, node);
		}
		return script;
	}

	/**
	 * Height of the chat filter buttons along the bottom of the chatbox. The real
	 * dialog stops above them - they stay clickable while an NPC is talking - so
	 * this is subtracted from the drawing area rather than covering them.
	 */
	private static final int BUTTON_STRIP = 23;

	private static final Color PARCHMENT = new Color(0xC8, 0xB8, 0x8F);
	private static final Color TEXT = Color.BLACK;

	/**
	 * The speaker's name is the client's DARKRED (0x800000). The continue prompt
	 * is full blue - the client source says DARKBLUE, but that is the 2004-era
	 * value; the modern client renders it brighter, and side-by-side screenshots
	 * agreed, so the screenshots win.
	 */
	private static final Color NAME = new Color(0x80, 0x00, 0x00);
	private static final Color CONTINUE = new Color(0x00, 0x00, 0xFF);
	private static final Color HOVER = Color.WHITE;

	/**
	 * The dialog's text cells, sniffed off the LIVE widgets (2026-08-04): both
	 * speech dialogs share rows name=23, body=39..106, continue=103 in a
	 * 380-wide centred column - at x=115 on the NPC side, x=24 on the player
	 * side (whose head swaps to the right at (433,59)). Everything renders in
	 * the game's font id 497. Coordinates relative to the chatbox widget.
	 */
	private static final int DIALOG_FONT_ID = 497;

	/**
	 * The chatbox background sprite, 1017 (the old api SpriteID.CHATBOX,
	 * deprecated without a matching gameval name).
	 */
	private static final int CHATBOX_SPRITE = 1017;
	private static final int TEXT_WIDTH = 380;
	private static final int NPC_TEXT_X = 115;
	private static final int PLAYER_TEXT_X = 24;
	private static final int NAME_TOP = 23;
	private static final int BODY_TOP = 39;
	private static final int BODY_HEIGHT = 67;
	private static final int CONTINUE_TOP = 103;
	private static final int ROW_HEIGHT = 17;

	/**
	 * The option menu's cells, same source: header "Select an option" (dark
	 * red, lowercase o - the widget's own text) at (20,22,479,20), flanked by
	 * the sword ornaments - sprite 302 on the left at (92,24), its mirror 301
	 * on the right at (370,24). Option spacing varies with the COUNT, measured
	 * across three-, four- and five-option menus: step = 36 - 4n (24/20/16),
	 * cells capped at 20 tall, first tops 46/39/40.
	 */
	private static final int OPTION_X = 20;
	private static final int OPTION_WIDTH = 479;
	private static final int OPTION_HEADER_TOP = 22;
	private static final int SWORD_LEFT_SPRITE = 302;
	private static final int SWORD_RIGHT_SPRITE = 301;
	private static final int SWORD_LEFT_X = 92;
	private static final int SWORD_RIGHT_X = 370;
	private static final int SWORD_Y = 24;

	/**
	 * Option menu vertical layout, MEASURED for every count the real menu
	 * shows (2/3/4/5 options): steps 32/24/20/16, cell heights 24/20/20/16,
	 * first tops 52/46/39/40. The block always centres on row 80 (the 39 is
	 * the game's own rounding); the fallback uses that centring.
	 */
	private static int optionStep(int options)
	{
		switch (options)
		{
			case 2:
				return 32;
			case 3:
				return 24;
			case 4:
				return 20;
			default:
				return 16;
		}
	}

	private static int optionCellHeight(int options)
	{
		return options <= 2 ? 24 : options >= 5 ? 16 : 20;
	}

	private static int optionFirstTop(int options)
	{
		switch (options)
		{
			case 2:
				return 52;
			case 3:
				return 46;
			case 4:
				return 39;
			case 5:
				return 40;
			default:
				int step = optionStep(options);
				int cell = optionCellHeight(options);
				return 80 - ((options - 1) * step + cell) / 2;
		}
	}

	/**
	 * Body line spacing, MEASURED per line count from the live widget's
	 * lineHeight: one line 16, two lines 28, three lines 20. Not derived -
	 * the game genuinely spaces a two-line message wider than a three-line one.
	 */
	private static int measuredLineHeight(int lines)
	{
		switch (lines)
		{
			case 2:
				return 28;
			case 3:
				return 20;
			default:
				return 16;
		}
	}

	/**
	 * The game's actual dialog font: RuneStar's pixel-perfect recreation of the
	 * cache's Plain 12, rebuilt glyph for glyph from the font data. RuneLite's
	 * bundled runescape.ttf is its own looser recreation, which is why the text
	 * never quite matched a real dialog. Sized 16pt: the TTF is authored so 16pt
	 * reproduces the native pixel size.
	 *
	 * <p>Only reached when the font dump fails to load, which for a bundled
	 * resource means a broken jar. The Bold 12 companion was loaded here too and
	 * never drawn with - the overhead line's own fallback uses RuneLite's bold
	 * font - so it and its resource are gone.
	 */
	private static final java.awt.Font PLAIN_12 = loadFont("RuneScape-Plain-12.ttf");

	private static java.awt.Font loadFont(String resource)
	{
		try (java.io.InputStream in =
			FollowerDialog.class.getResourceAsStream("/com/follower/" + resource))
		{
			if (in != null)
			{
				java.awt.Font font = java.awt.Font
					.createFont(java.awt.Font.TRUETYPE_FONT, in).deriveFont(16f);
				log.info("Loaded dialog font {} -> {}", resource, font.getFontName());
				return font;
			}
			log.warn("Dialog font {} missing from the jar; falling back to RuneLite's", resource);
		}
		catch (java.io.IOException | java.awt.FontFormatException e)
		{
			log.warn("Dialog font {} failed to load; falling back to RuneLite's", resource, e);
		}
		return FontManager.getRunescapeFont();
	}

	private final Client client;
	private final FollowerEntity follower;
	private final MouseManager mouseManager;
	private final SpriteManager spriteManager;
	private final net.runelite.client.callback.ClientThread clientThread;
	private final com.follower.appearance.AppearanceComposer composer;
	private final net.runelite.client.input.KeyManager keyManager;
	private final com.follower.ui.GameFontRepository gameFonts;

	/**
	 * Head-tuning mode: while on and a dialog is open, the arrow keys nudge the
	 * head live - left/right for yaw, up/down for tilt, shift for fine steps. A
	 * keyboard was needed because the chatbox input is underneath the dialog, so
	 * commands cannot be typed while the thing being tuned is visible.
	 */
	@lombok.Setter
	@lombok.Getter
	private boolean tuning;

	private final net.runelite.client.input.KeyListener keyAdapter =
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
			if (!open || !tuning)
			{
				return;
			}

			int step = e.isShiftDown() ? 4 : 16;
			switch (e.getKeyCode())
			{
				case java.awt.event.KeyEvent.VK_LEFT:
					npcTurn = (npcTurn - step) & 0x7ff;
					break;
				case java.awt.event.KeyEvent.VK_RIGHT:
					npcTurn = (npcTurn + step) & 0x7ff;
					break;
				case java.awt.event.KeyEvent.VK_UP:
					headPitch = (headPitch - step) & 0x7ff;
					break;
				case java.awt.event.KeyEvent.VK_DOWN:
					headPitch = (headPitch + step) & 0x7ff;
					break;
				default:
					return;
			}
			e.consume();
		}
	};

	/**
	 * Where the dialog interface sits inside the chatbox widget - the model clips
	 * at the INTERFACE edge (the inner parchment), not the chatbox's outer frame.
	 * Measured (7, 6) off a live dialog; the sniffer keeps it calibrated.
	 */
	@lombok.Setter
	private int dialogInsetX = 7;

	@lombok.Setter
	private int dialogInsetY = 6;

	/**
	 * Extra pixels the crown is cut below the interface edge. The client clips to
	 * the interface rect exactly (drawInterface sets the clip to the component's
	 * own bounds), so in principle this is zero - but our head projects slightly
	 * larger than the real one, so it reaches higher above the same anchor and
	 * needs the difference taken off. 14 confirmed by eye against a real dialog;
	 * still tunable via {@code ::follower head cliptop <n>}.
	 */
	@lombok.Setter
	@lombok.Getter
	private int clipTopExtra = 14;

	/** Supplies the follower's outfit so its real chathead can be composed. */
	@lombok.Setter
	private java.util.function.Supplier<com.follower.appearance.Outfit> followerOutfit;

	private String followerName = "Follower";
	private Map<String, Node> script = new LinkedHashMap<>();
	private Node node;
	private int page;
	private boolean open;

	/**
	 * The tick gate: dialog input resolves ON the game tick, never instantly.
	 * A real dialog's click travels to the server and takes effect when the
	 * next tick processes it - opening, advancing, choosing and dismissing all
	 * carry that beat, and the continue line shows the client's own
	 * "Please wait..." (in its base colour, not hover white) while one is in
	 * flight. Extra clicks while waiting are ignored, as the real client does.
	 */
	private Runnable pendingAction;
	private boolean awaitingContinue;

	/** The clicked option's index while its tick is in flight; it reads "Please wait..." */
	private int pendingOptionIndex = -1;

	/** Runs the queued input, if any. Called once per game tick by the plugin. */
	public void tick()
	{
		if (pendingAction == null)
		{
			return;
		}
		Runnable action = pendingAction;
		pendingAction = null;
		awaitingContinue = false;
		pendingOptionIndex = -1;
		action.run();
	}

	/** Opens the conversation on the NEXT game tick, like a real Talk-to. */
	public void startNextTick(String speakerName, Map<String, Node> conversation, String startId)
	{
		pendingAction = () -> start(speakerName, conversation, startId);
	}

	private Rectangle bounds = new Rectangle();
	private Rectangle[] optionBounds = new Rectangle[0];
	private Rectangle continueBounds = new Rectangle();

	private BufferedImage head;
	private boolean headIsPlayer;

	/**
	 * Head angles, copied live from a real NPC dialog's widget whenever one opens
	 * - the game publishes its exact rotationY (facing) and rotationX (tilt)
	 * there, which beats guessing. Defaults are the pre-sniff guess; the sniffed
	 * values overwrite them for the session and are reported so they can be baked.
	 */
	@lombok.Setter
	@lombok.Getter
	private int headYaw = ChatheadRenderer.GAME_YAW;

	/** Camera pitch, not a model tilt - see ChatheadRenderer. */
	@lombok.Setter
	@lombok.Getter
	private int headPitch = ChatheadRenderer.GAME_PITCH;

	@lombok.Setter
	@lombok.Getter
	private int headZoom = ChatheadRenderer.GAME_ZOOM;

	/**
	 * The chathead's real angle: rotationZ. The game mirrors it between the two
	 * dialog sides - the NPC side (which our layout copies) uses 1882, the player
	 * side 166 - so the follower and the player face each other, as they do in a
	 * real conversation.
	 */
	@lombok.Setter
	@lombok.Getter
	private int npcTurn = ChatheadRenderer.GAME_TURN_NPC;

	/**
	 * The real chathead's rectangle within the dialog, copied off a live NPC
	 * dialog widget. Null until one has been seen, in which case the fallback
	 * below is used.
	 */
	@lombok.Setter
	private java.awt.Rectangle headRect;

	/**
	 * The PLAYER side's head cell, measured off a live player dialog: the head
	 * sits on the RIGHT at (433,59) 32x32 - centre (449,75) - mirroring the
	 * NPC side. Kept calibrated by the sniffer whenever a real one opens.
	 */
	@lombok.Setter
	private java.awt.Rectangle playerHeadRect = new java.awt.Rectangle(433, 59, 32, 32);

	/**
	 * The client anchors a widget model at the widget's CENTRE (the origin point)
	 * and lets the geometry overflow, clipped by the chat surface - the declared
	 * widget is 32x32 while the head drawn around it is far larger, and tall hair
	 * cuts exactly at the dialog's top edge. So the canvas spans from the
	 * dialog's top down past the chin, the model is anchored at the measured
	 * origin, and the blit clips at the dialog bounds like the real surface.
	 */
	private int originX()
	{
		return headRect == null ? 62 : headRect.x + headRect.width / 2;
	}

	private int originY()
	{
		return headRect == null ? 69 : headRect.y + headRect.height / 2;
	}

	/** Wide enough for any hairstyle; the sides clip at the dialog if needed. */
	private int headWidth()
	{
		return 150;
	}

	/** From the dialog's top edge down past the chin. */
	private int headHeight()
	{
		return originY() + 46;
	}

	private int headTurn()
	{
		return headIsPlayer ? ChatheadRenderer.GAME_TURN_PLAYER : npcTurn;
	}

	@lombok.Setter
	@lombok.Getter
	private double headFraction = 0.26;

	private final MouseAdapter clickAdapter = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			// Left button only: a right-click opens a menu without committing to
			// anything, so it must not dismiss the conversation - the same way a
			// real dialog survives right-clicking around it.
			if (!open || !javax.swing.SwingUtilities.isLeftMouseButton(event))
			{
				return event;
			}

			// Clicking away from the box dismisses it, as any click that starts
			// another action does in game - ON the tick that action processes.
			// The click is NOT consumed: it still walks you there or opens what
			// you aimed at.
			if (!bounds.contains(event.getPoint()))
			{
				pendingAction = FollowerDialog.this::close;
				return event;
			}

			if (sweepMode)
			{
				close();
				event.consume();
				return event;
			}

			// One input in flight at a time - the real client ignores clicks
			// while its "Please wait..." is up.
			if (pendingAction != null)
			{
				event.consume();
				return event;
			}

			// Only clicks inside the box are ours; everything else must pass
			// through to the game untouched.
			if (optionsVisible())
			{
				for (int i = 0; i < optionBounds.length; i++)
				{
					if (optionBounds[i] != null && optionBounds[i].contains(event.getPoint()))
					{
						String target = node.optionNext[i];
						pendingOptionIndex = i;
						pendingAction = () -> goTo(target);
						break;
					}
				}
			}
			else
			{
				awaitingContinue = true;
				pendingAction = FollowerDialog.this::advance;
			}

			event.consume();
			return event;
		}
	};

	@Inject
	public FollowerDialog(Client client, FollowerEntity follower,
		MouseManager mouseManager, SpriteManager spriteManager,
		net.runelite.client.callback.ClientThread clientThread,
		com.follower.appearance.AppearanceComposer composer,
		net.runelite.client.input.KeyManager keyManager,
		com.follower.ui.GameFontRepository gameFonts)
	{
		this.client = client;
		this.follower = follower;
		this.mouseManager = mouseManager;
		this.spriteManager = spriteManager;
		this.clientThread = clientThread;
		this.composer = composer;
		this.keyManager = keyManager;
		this.gameFonts = gameFonts;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	public void register()
	{
		mouseManager.registerMouseListener(clickAdapter);
		keyManager.registerKeyListener(keyAdapter);
	}

	public void unregister()
	{
		mouseManager.unregisterMouseListener(clickAdapter);
		keyManager.unregisterKeyListener(keyAdapter);
	}

	public boolean isOpen()
	{
		return open;
	}

	/**
	 * Whatever the conversation reached that should happen once it is over.
	 * Held rather than run on arrival so the follower's spoken reply lands
	 * after the box is gone rather than over the top of it.
	 */
	private Runnable pendingFinish;

	private void latch(Node reached)
	{
		if (reached != null && reached.onFinish != null)
		{
			pendingFinish = reached.onFinish;
		}
	}

	/**
	 * Test seams. Reaching a node the real way needs a follower to face the
	 * player and a client to compose a chathead from, neither of which exists
	 * headlessly - and the behaviour worth pinning down here is only which
	 * moment the answer is delivered at. These touch the same fields and the
	 * same {@link #latch} and {@link #close} the real path does.
	 */
	void openForTest(Map<String, Node> conversation)
	{
		script = conversation;
		open = true;
		pendingFinish = null;
	}

	void reachForTest(String nodeId)
	{
		latch(script.get(nodeId));
	}

	public void close()
	{
		open = false;
		head = null;
		chatheadBase = null;
		talkAnimation = null;
		sweepMode = false;
		sweep = null;
		// An interrupt-close cancels any input still in flight, or a stale
		// queued action would fire a tick after the box is gone.
		pendingAction = null;
		awaitingContinue = false;
		pendingOptionIndex = -1;

		// Cleared before it runs: whatever it does, it must not be able to
		// arrive here a second time.
		Runnable finish = pendingFinish;
		pendingFinish = null;
		if (finish != null)
		{
			finish.run();
		}
	}

	/** Starts a conversation at {@code startId}. */
	public void start(String speakerName, Map<String, Node> conversation, String startId)
	{
		followerName = speakerName;
		script = conversation;
		open = true;
		// A fresh conversation inherits nothing from the last one; a leftover
		// would fire on the wrong ending entirely.
		pendingFinish = null;
		goTo(startId);
	}

	/** A one-off line with no branching. */
	public void say(String speakerName, String... pages)
	{
		Map<String, Node> single = new LinkedHashMap<>();
		single.put("start", Node.says(pages));
		start(speakerName, single, "start");
	}

	/** The current node's pages, resolved per ENTRY so dynamic nodes re-roll. */
	private String[] pages = new String[0];

	private void goTo(String nodeId)
	{
		node = nodeId == null ? null : script.get(nodeId);
		if (node == null)
		{
			close();
			return;
		}

		latch(node);

		pages = node.dynamicPages != null ? node.dynamicPages.get() : node.pages;
		page = 0;
		follower.facePlayer();
		refreshHead();
	}

	private void advance()
	{
		page++;
		if (page < pages.length || optionsVisible())
		{
			return;
		}
		if (node.next != null)
		{
			goTo(node.next);
		}
		else
		{
			close();
		}
	}

	private boolean optionsVisible()
	{
		return node != null && page >= pages.length && node.optionText.length > 0;
	}

	/** The composed dialogue-head model of the current speaker; null = fallback. */
	private net.runelite.api.Model chatheadBase;

	/**
	 * Angle-picker mode: the head drawn at every 45 degrees at once, labelled, so
	 * the right facing can be chosen by eye instead of guessed one value per
	 * rebuild. The game's own dialogs use yaw 0, but that is the client widget
	 * renderer's zero, which need not be this renderer's.
	 */
	private BufferedImage[] sweep;
	private boolean sweepMode;

	private static final int SWEEP_COUNT = 8;
	private static final int SWEEP_SIZE = 58;

	/** Yaw of each sweep cell, so the labels and the picked value always agree. */
	private int[] sweepYaws = new int[0];

	/**
	 * Builds the angle picker centred on {@code centre}, {@code step} apart, so it
	 * can go from a coarse eight-way sweep down to a fine one around a candidate.
	 * Client thread only - it composes a model.
	 */
	public void showSweep(com.follower.appearance.Outfit outfit, int centre, int step,
		boolean pitchAxis)
	{
		chatheadBase = outfit == null ? null : composer.composeChathead(outfit);
		net.runelite.api.Model crop = follower.getBaseModel();

		sweep = new BufferedImage[SWEEP_COUNT];
		sweepYaws = new int[SWEEP_COUNT];
		sweepIsPitch = pitchAxis;

		for (int i = 0; i < SWEEP_COUNT; i++)
		{
			int value = (centre + (i - SWEEP_COUNT / 2) * step) & 0x7ff;
			sweepYaws[i] = value;

			int yaw = pitchAxis ? headYaw : value;
			int pitch = pitchAxis ? value : headPitch;

			sweep[i] = chatheadBase != null
				? ChatheadRenderer.render(chatheadBase, null, SWEEP_SIZE, SWEEP_SIZE,
					yaw, pitch, headZoom, 1.0)
				: ChatheadRenderer.render(crop, null, SWEEP_SIZE, SWEEP_SIZE,
					yaw, pitch, headZoom, headFraction);
		}

		sweepMode = true;
		open = true;
	}

	private boolean sweepIsPitch;

	private void drawSweep(Graphics2D graphics)
	{
		graphics.setFont(PLAIN_12);
		int columns = 4;
		int cellW = bounds.width / columns;
		int rowH = (bounds.height - 18) / 2;

		for (int i = 0; i < SWEEP_COUNT; i++)
		{
			int cx = bounds.x + (i % columns) * cellW;
			int cy = bounds.y + (i / columns) * rowH + 6;

			if (sweep[i] != null)
			{
				graphics.drawImage(sweep[i], cx + (cellW - SWEEP_SIZE) / 2, cy, null);
			}
			drawCentred(graphics, String.valueOf(sweepYaws[i]),
				cx, cellW, cy + SWEEP_SIZE + 11, TEXT);
		}

		drawCentred(graphics, sweepIsPitch
				? "Best tilt? ::follower head pitch <number>"
				: "Best facing? ::follower head yaw <number>",
			bounds.x, bounds.width, bounds.y + bounds.height - 4, CONTINUE);
	}

	/** The talking animation state, advanced on the wall clock while drawing. */
	private net.runelite.api.Animation talkAnimation;
	private int talkFrame;
	private long talkCarryMs;
	private long lastTalkMs;

	/** The chathead talking sequence; tunable, since ids are folklore. */
	@lombok.Setter
	@lombok.Getter
	private int talkAnimationId = 588;

	/**
	 * Rebuilds the head for whoever is speaking, on the client thread - model
	 * loading is client-thread-only, and clicks that change speaker arrive on the
	 * AWT thread.
	 *
	 * <p>Prefers the game's own dialogue-head models (helm head variant, hair and
	 * jaw chatheads, coloured through the same palette as the body); falls back to
	 * cropping the body model when the dump has no head data for the outfit.
	 */
	private void refreshHead()
	{
		boolean player = node.playerSpeaking;
		headIsPlayer = player;

		clientThread.invoke(() ->
		{
			chatheadBase = null;
			head = null;
			talkAnimation = null;
			talkFrame = 0;
			talkCarryMs = 0;
			lastTalkMs = System.currentTimeMillis();

			com.follower.appearance.Outfit outfit = null;
			net.runelite.api.Model cropModel = null;

			if (player)
			{
				Player local = client.getLocalPlayer();
				if (local != null && local.getPlayerComposition() != null)
				{
					outfit = com.follower.appearance.Outfit.from(local.getPlayerComposition());
					// The live actor model is shared and recycled, so on the fallback
					// path it is rendered to an image immediately and never held.
					cropModel = local.getModel();
				}
			}
			else
			{
				outfit = followerOutfit == null ? null : followerOutfit.get();
				cropModel = follower.getBaseModel();
			}

			if (outfit != null)
			{
				chatheadBase = composer.composeChathead(outfit);
			}
			if (chatheadBase == null)
			{
				head = ChatheadRenderer.render(cropModel, null, headWidth(), headHeight(),
					headTurn(), headPitch, headZoom, headFraction, headWidth() / 2, originY());
			}
		});
	}

	/**
	 * The current animated head image, posed at the talking animation's current
	 * frame. Called from the overlay render pass, which runs on the client thread,
	 * so posing here is legal. The posed model comes back on the client's shared
	 * scratch buffer and is rendered to pixels immediately, never held.
	 */
	private BufferedImage animatedHead()
	{
		if (chatheadBase == null)
		{
			return head;
		}

		if (talkAnimation == null)
		{
			talkAnimation = client.loadAnimation(talkAnimationId);
			if (talkAnimation == null || talkAnimation.getFrameLengths() == null)
			{
				talkAnimation = null;
				return ChatheadRenderer.render(chatheadBase, null, headWidth(), headHeight(),
					headTurn(), headPitch, headZoom, 1.0, headWidth() / 2, originY());
			}
		}

		// Advance on the wall clock: frame lengths are in 20ms client cycles.
		int[] lengths = talkAnimation.getFrameLengths();
		long now = System.currentTimeMillis();
		talkCarryMs += Math.min(now - lastTalkMs, 600);
		lastTalkMs = now;
		while (talkCarryMs >= lengths[talkFrame] * 20L)
		{
			talkCarryMs -= lengths[talkFrame] * 20L;
			talkFrame = (talkFrame + 1) % lengths.length;
		}

		net.runelite.api.Model posed = client.applyTransformations(
			chatheadBase, talkAnimation, talkFrame, null, 0);
		return ChatheadRenderer.render(posed == null ? chatheadBase : posed,
			chatheadBase, headWidth(), headHeight(), headTurn(), headPitch, headZoom, 1.0, headWidth() / 2, originY());
	}

	private String speakerName()
	{
		if (!headIsPlayer)
		{
			return followerName;
		}
		Player local = client.getLocalPlayer();
		return local == null || local.getName() == null ? "You" : local.getName();
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!open || (node == null && !sweepMode))
		{
			return null;
		}

		Widget chat = client.getWidget(InterfaceID.CHATBOX, 0);
		Rectangle area = chat != null && !chat.isHidden()
			? chat.getBounds()
			: new Rectangle(0, client.getCanvasHeight() - 165, 519, 165);
		if (area.width <= 0 || area.height <= BUTTON_STRIP)
		{
			return null;
		}

		// Stop short of the filter buttons, as the game's own dialog does - and
		// because bounds is also the click region, this leaves them clickable.
		bounds = new Rectangle(area.x, area.y, area.width, area.height - BUTTON_STRIP);

		// The game draws its text with a bitmap font and no antialiasing. Leaving
		// AA on softens and visibly thickens every glyph, which is what made this
		// box read as "bolder" than a real one even with the right typeface.
		graphics.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
			java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
			java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);

		drawBackground(graphics);

		if (sweepMode)
		{
			drawSweep(graphics);
			return null;
		}

		if (optionsVisible())
		{
			drawOptions(graphics);
		}
		else
		{
			drawSpeech(graphics);
		}

		if (tuning)
		{
			graphics.setFont(PLAIN_12);
			graphics.setColor(HOVER);
			graphics.drawString("turn " + npcTurn + "  pitch " + headPitch
				+ "  (arrows adjust, shift = fine)", bounds.x + 6, bounds.y + 12);
		}

		return null;
	}

	private void drawBackground(Graphics2D graphics)
	{
		BufferedImage sprite = spriteManager.getSprite(CHATBOX_SPRITE, 0);
		if (sprite != null)
		{
			graphics.drawImage(sprite, bounds.x, bounds.y, bounds.width, bounds.height, null);
		}
		else
		{
			// The sprite loads asynchronously; a matched fill covers the first frames.
			graphics.setColor(PARCHMENT);
			graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
		}
	}

	private void drawSpeech(Graphics2D graphics)
	{
		BufferedImage face = animatedHead();

		// The canvas is anchored so the model's origin lands at the measured
		// widget centre, its top row IS the dialog's top row, and the blit clips
		// at the dialog bounds - so tall hair is cut exactly where the real chat
		// surface cuts it. The PLAYER'S head anchors at the measured right-side
		// cell, the way a real player dialog mirrors the NPC one.
		int headCentreX = headIsPlayer
			? playerHeadRect.x + playerHeadRect.width / 2
			: originX();
		if (face != null)
		{
			// Clip at the dialog INTERFACE edge - the inner parchment - which is
			// the surface the real client cuts models at. Clipping at the outer
			// chatbox frame let hair run about six pixels too high, over the
			// border, which a real head never does.
			java.awt.Shape oldClip = graphics.getClip();
			int clipTop = bounds.y + dialogInsetY + clipTopExtra;
			graphics.setClip(bounds.x + dialogInsetX, clipTop,
				bounds.width - dialogInsetX * 2, bounds.y + bounds.height - clipTop);
			graphics.drawImage(face,
				bounds.x + headCentreX - face.getWidth() / 2, bounds.y, null);
			graphics.setClip(oldClip);
		}

		// The measured text column: 380 wide at x=115 beside an NPC head on the
		// left, x=24 with the player's head on the right.
		com.follower.ui.GameFont font = gameFonts.get(DIALOG_FONT_ID);
		int cellX = bounds.x + (headIsPlayer ? PLAYER_TEXT_X : NPC_TEXT_X);

		drawCell(graphics, font, speakerName(), cellX, TEXT_WIDTH,
			bounds.y + NAME_TOP, NAME.getRGB() & 0xFFFFFF);

		String[] wrapped = wrap(graphics, font, pages[page], TEXT_WIDTH);
		int lineHeight = measuredLineHeight(wrapped.length);
		int top = bounds.y + BODY_TOP + (BODY_HEIGHT - wrapped.length * lineHeight) / 2;
		for (String line : wrapped)
		{
			drawCell(graphics, font, line, cellX, TEXT_WIDTH, top, 0x000000);
			top += lineHeight;
		}

		// The client's own in-flight text: after a continue click, the prompt
		// reads "Please wait..." in its BASE colour (no hover white) until the
		// tick lands.
		String prompt = awaitingContinue ? "Please wait..." : "Click here to continue";
		int promptTop = bounds.y + CONTINUE_TOP;
		int promptWidth = textWidth(graphics, font, prompt);
		continueBounds = new Rectangle(
			cellX + (TEXT_WIDTH - promptWidth) / 2, promptTop, promptWidth, ROW_HEIGHT);

		drawCell(graphics, font, prompt, cellX, TEXT_WIDTH, promptTop,
			!awaitingContinue && hovered(continueBounds) ? 0xFFFFFF : 0x0000FF);
	}

	/**
	 * One line centred in a measured cell: the game's own glyphs when the font
	 * dump is loaded, the bundled TTF otherwise. {@code top} is the line's top
	 * edge - the glyphs' baked offsets place them within it, exactly as the
	 * client's font renderer does.
	 */
	private void drawCell(Graphics2D graphics, com.follower.ui.GameFont font,
		String text, int cellX, int cellWidth, int top, int rgb)
	{
		if (font != null)
		{
			font.drawTop(graphics, text,
				cellX + (cellWidth - font.stringWidth(text)) / 2, top, rgb, false);
			return;
		}
		graphics.setFont(PLAIN_12);
		graphics.setColor(new Color(rgb));
		int width = graphics.getFontMetrics().stringWidth(text);
		graphics.drawString(text, cellX + (cellWidth - width) / 2, top + 13);
	}

	private int textWidth(Graphics2D graphics, com.follower.ui.GameFont font, String text)
	{
		if (font != null)
		{
			return font.stringWidth(text);
		}
		graphics.setFont(PLAIN_12);
		return graphics.getFontMetrics().stringWidth(text);
	}

	/** True when the mouse is inside {@code area} on the canvas. */
	private boolean hovered(Rectangle area)
	{
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		return mouse != null && area.contains(mouse.getX(), mouse.getY());
	}

	private void drawOptions(Graphics2D graphics)
	{
		com.follower.ui.GameFont font = gameFonts.get(DIALOG_FONT_ID);
		int cellX = bounds.x + OPTION_X;

		drawCell(graphics, font, "Select an option", cellX, OPTION_WIDTH,
			bounds.y + OPTION_HEADER_TOP, NAME.getRGB() & 0xFFFFFF);

		// The game's own sword ornaments beside the header, at their measured
		// cells - sprite 302 left, mirror 301 right.
		BufferedImage swordLeft = spriteManager.getSprite(SWORD_LEFT_SPRITE, 0);
		BufferedImage swordRight = spriteManager.getSprite(SWORD_RIGHT_SPRITE, 0);
		if (swordLeft != null)
		{
			graphics.drawImage(swordLeft, bounds.x + SWORD_LEFT_X, bounds.y + SWORD_Y, null);
		}
		if (swordRight != null)
		{
			graphics.drawImage(swordRight, bounds.x + SWORD_RIGHT_X, bounds.y + SWORD_Y, null);
		}

		int count = node.optionText.length;
		int step = optionStep(count);
		int cellHeight = optionCellHeight(count);
		optionBounds = new Rectangle[count];
		int top = bounds.y + optionFirstTop(count);
		for (int i = 0; i < count; i++)
		{
			Rectangle row = new Rectangle(cellX, top, OPTION_WIDTH, cellHeight);
			optionBounds[i] = row;

			// Options are vertically centred in their cell (yAlign=1); the
			// 16-tall cell is the font's own line box, so only taller cells
			// push the text down. A clicked option shows the client's own
			// "Please wait..." until the tick lands - and unlike the continue
			// button (whose colour the client forces to base), options keep
			// their hover white while waiting.
			String label = i == pendingOptionIndex ? "Please wait..." : node.optionText[i];
			drawCell(graphics, font, label, cellX, OPTION_WIDTH,
				top + (cellHeight - 16) / 2, hovered(row) ? 0xFFFFFF : 0x000000);
			top += step;
		}
	}

	/**
	 * Crisp flat text, centred in its column. Deliberately NO shadow: dialog text
	 * on parchment has none in the real client, and a shadow visibly fattens the
	 * glyphs - it was what made this box read "bolder" than a real one.
	 */
	private void drawCentred(Graphics2D graphics, String text,
		int left, int width, int y, Color color)
	{
		int textWidth = graphics.getFontMetrics().stringWidth(text);
		graphics.setColor(color);
		graphics.drawString(text, left + (width - textWidth) / 2, y);
	}

	/**
	 * Greedy word wrap measured with the GAME's own advances when available -
	 * wrap decisions have to agree with the renderer or lines land wrong. A
	 * single word longer than the column is left to overflow.
	 */
	private String[] wrap(Graphics2D graphics, com.follower.ui.GameFont font,
		String text, int maxWidth)
	{
		java.util.List<String> out = new java.util.ArrayList<>();
		StringBuilder line = new StringBuilder();

		for (String word : text.split(" "))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (textWidth(graphics, font, candidate) > maxWidth && line.length() > 0)
			{
				out.add(line.toString());
				line = new StringBuilder(word);
			}
			else
			{
				line = new StringBuilder(candidate);
			}
		}
		if (line.length() > 0)
		{
			out.add(line.toString());
		}
		return out.toArray(new String[0]);
	}
}

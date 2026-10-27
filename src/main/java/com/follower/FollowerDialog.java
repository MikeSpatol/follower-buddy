package com.follower;

import java.awt.*;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.Map;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.Player;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.Overlay;
import lombok.*;
import java.util.List;
import java.util.function.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.runelite.api.Model;
import net.runelite.client.callback.ClientThread;

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
class FollowerDialog extends Overlay
{
	/** One step of a conversation: some pages, then either options or a jump. */
	static class Node
	{
		final String[] pages;
		@Getter
		final boolean playerSpeaking;
		String[] optionText = new String[0],
			optionNext = new String[0];
		String next;

		/** Resolved fresh on every ENTRY to the node - a re-rolled joke, say. */
		Supplier<String[]> dynamicPages;

		/** Latched when the node is reached, run when the conversation ENDS. */
		Runnable onFinish;

		Node(boolean playerSpeaking, String[] pages)
		{
			this.playerSpeaking = playerSpeaking;
			this.pages = pages;
		}

		/** The follower speaks these pages. */
		static Node says(String... pages)
		{
			return new Node(false, pages);
		}

		/** Pages resolved fresh on each visit instead of the fixed ones. */
		Node dynamic(Supplier<String[]> pages)
		{
			dynamicPages = pages;
			return this;
		}

		/** The player speaks these pages, with their own head and name. */
		static Node you(String... pages)
		{
			return new Node(true, pages);
		}

		/** Continue to another node once the pages are done. */
		Node then(String nodeId)
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
		Node onFinish(Runnable action)
		{
			this.onFinish = action;
			return this;
		}

		/**
		 * Runs the finish action now, if there is one. For the lint that
		 * cross-checks every answer a conversation can produce against the
		 * rules listening for one - the talk script's answers only exist
		 * inside these runnables, so the only honest way to enumerate them is
		 * to run them against a recording consumer.
		 */
		void runFinish()
		{
			if (onFinish != null)
				onFinish.run();
		}

		/** The pages this node speaks, and the labels of any choices it offers. */
		List<String> getLines()
		{
			List<String> lines = new ArrayList<>(
				Arrays.asList(pages));
			lines.addAll(Arrays.asList(optionText));
			return lines;
		}

		/** Every node id this one can lead to, by continuing or by choice. */
		List<String> getTargets()
		{
			List<String> found = new ArrayList<>(
				Arrays.asList(optionNext));
			if (next != null)
				found.add(next);
			return found;
		}

		/** The choice labels, in the order they are offered. */
		List<String> getOptionLabels()
		{
			return Arrays.asList(optionText);
		}

		/** Where each choice leads, parallel to {@link #getOptionLabels()}. */
		List<String> getOptionTargets()
		{
			return Arrays.asList(optionNext);
		}

		/** The pages only, without the choice labels. */
		List<String> getPages()
		{
			return Arrays.asList(dynamicPages != null ? dynamicPages.get() : pages);
		}

		/** Offer choices once the pages are done; pairs of label and target id. */
		Node choices(String... labelThenTarget)
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
	 * <p>The everyday talk script (talk.json, bundled) is built through here
	 * too. These are the ones the follower OPENS, and they are content:
	 * whoever writes them should not need a compiler, and the one thing they
	 * must be able to do from the file is say which branch counts as the
	 * answer.
	 *
	 * @param onAnswer handed "yes" or "no" the moment a branch carrying one is
	 * reached
	 */
	static Map<String, Node> build(DialogTree tree,
		Consumer<String> onAnswer)
	{
		return build(tree, onAnswer, UnaryOperator.identity());
	}

	/**
	 * As {@link #build(DialogTree, java.util.function.Consumer)}, with every
	 * page and option passed through {@code fill}, so a tree can say the
	 * {candidate} its question is about.
	 */
	static Map<String, Node> build(DialogTree tree,
		Consumer<String> onAnswer, UnaryOperator<String> fill)
	{
		Map<String, Node> script = new LinkedHashMap<>();
		if (tree == null || tree.nodes == null)
			return script;

		for (var source : tree.nodes)
		{
			if (source == null || source.id == null)
				continue;

			var pages = source.pages().stream().map(fill).toArray(String[]::new);
			var node = source.isPlayerSpeaking() ? Node.you(pages) : Node.says(pages);

			if (source.choices != null && !source.choices.isEmpty())
			{
				var pairs = new String[source.choices.size() * 2];
				for (int i = 0; i < source.choices.size(); i++)
				{
					var choice = source.choices.get(i);
					pairs[i * 2] = fill.apply(choice.label);
					pairs[i * 2 + 1] = choice.next;
				}
				node.choices(pairs);
			}
			else if (source.next != null)
				node.then(source.next);

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
	static final int BUTTON_STRIP = 23;

	static final Color PARCHMENT = new Color(0xC8, 0xB8, 0x8F);

	/**
	 * The speaker's name is the client's DARKRED (0x800000). The continue prompt
	 * is full blue - the client source says DARKBLUE, but that is the 2004-era
	 * value; the modern client renders it brighter, and side-by-side screenshots
	 * agreed, so the screenshots win.
	 */
	static final Color NAME = new Color(0x80, 0x00, 0x00);

	/**
	 * The dialog's text cells, sniffed off the LIVE widgets (2026-08-04): both
	 * speech dialogs share rows name=23, body=39..106, continue=103 in a
	 * 380-wide centred column - at x=115 on the NPC side, x=24 on the player
	 * side (whose head swaps to the right at (433,59)). Everything renders in
	 * the game's font id 497. Coordinates relative to the chatbox widget.
	 */
	static final int DIALOG_FONT_ID = 497;

	static final int TEXT_WIDTH = 380,
		ROW_HEIGHT = 17;

	static final int OPTION_WIDTH = 479,
		SWORD_Y = 24;

	/**
	 * Option menu vertical layout, MEASURED for every count the real menu
	 * shows (2/3/4/5 options): steps 32/24/20/16, cell heights 24/20/20/16,
	 * first tops 52/46/39/40. The block always centres on row 80 (the 39 is
	 * the game's own rounding); the fallback uses that centring.
	 */
	static int optionStep(int options)
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

	static int optionCellHeight(int options)
	{
		return options <= 2 ? 24 : options >= 5 ? 16 : 20;
	}

	static int optionFirstTop(int options)
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
				int step = optionStep(options),
					cell = optionCellHeight(options);
				return 80 - ((options - 1) * step + cell) / 2;
		}
	}

	/**
	 * Body line spacing, MEASURED per line count from the live widget's
	 * lineHeight: one line 16, two lines 28, three lines 20. Not derived -
	 * the game genuinely spaces a two-line message wider than a three-line one.
	 */
	static int measuredLineHeight(int lines)
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

	final Client client;
	final FollowerEntity follower;
	final MouseManager mouseManager;
	final SpriteManager spriteManager;
	final ClientThread clientThread;
	final AppearanceComposer composer;
	final GameFontRepository gameFonts;

	/**
	 * Where the dialog interface sits inside the chatbox widget - the model clips
	 * at the INTERFACE edge (the inner parchment), not the chatbox's outer frame.
	 * Measured (7, 6) off a live dialog; the sniffer keeps it calibrated.
	 */
	@Setter
	int dialogInsetX = 7;

	@Setter
	int dialogInsetY = 6;

	/**
	 * Extra pixels the crown is cut below the interface edge. The client clips to
	 * the interface rect exactly (drawInterface sets the clip to the component's
	 * own bounds), so in principle this is zero - but our head projects slightly
	 * larger than the real one, so it reaches higher above the same anchor and
	 * needs the difference taken off. 14 confirmed by eye against a real dialog;
	 * still tunable via {@code ::follower head cliptop <n>}.
	 */
	int clipTopExtra = 14;

	/** Supplies the follower's outfit so its real chathead can be composed. */
	@Setter
	Supplier<Outfit> followerOutfit;

	/**
	 * The speaker's look, and the head models it went without because the
	 * client was still fetching them: a model the client has never drawn
	 * comes from the game's servers, and the first ask gets nothing. Public
	 * QA, 27 Oct, on a fresh install: the head was missing for a whole node
	 * and only sometimes there on the next Talk-to.
	 */
	Outfit headOutfit;
	final List<Integer> headDownloading = new ArrayList<>();

	/**
	 * Until when a head still missing a part is held back rather than drawn
	 * without it, as the game draws no chathead until all of it is there; 0
	 * once it is drawn. A part that never lands is gone without after that.
	 */
	long headHeldUntil;

	String followerName = "Follower";
	Map<String, Node> script = new LinkedHashMap<>();
	Node node;
	int page;
	@Getter
	boolean open;

	/**
	 * The tick gate: dialog input resolves ON the game tick, never instantly.
	 * A real dialog's click travels to the server and takes effect when the
	 * next tick processes it - opening, advancing, choosing and dismissing all
	 * carry that beat, and the continue line shows the client's own
	 * "Please wait..." (in its base colour, not hover white) while one is in
	 * flight. Extra clicks while waiting are ignored, as the real client does.
	 */
	Runnable pendingAction;
	boolean awaitingContinue;

	/** The clicked option's index while its tick is in flight; it reads "Please wait..." */
	int pendingOptionIndex = -1;

	/** Runs the queued input, if any. Called once per game tick by the plugin. */
	void tick()
	{
		if (pendingAction == null)
			return;
		var action = pendingAction;
		clearInput();
		action.run();
	}

	/** Drops any input still in flight. */
	void clearInput()
	{
		pendingAction = null;
		awaitingContinue = false;
		pendingOptionIndex = -1;
	}

	/** Opens the conversation on the NEXT game tick, like a real Talk-to. */
	void startNextTick(String speakerName, Map<String, Node> conversation, String startId)
	{
		pendingAction = () -> start(speakerName, conversation, startId);
	}

	Rectangle bounds = new Rectangle();
	Rectangle[] optionBounds = new Rectangle[0];
	Rectangle continueBounds = new Rectangle();

	BufferedImage head;
	boolean headIsPlayer;

	/**
	 * Head angles, copied live from a real NPC dialog's widget whenever one opens
	 * - the game publishes its exact rotationY (facing) and rotationX (tilt)
	 * there, which beats guessing. Defaults are the pre-sniff guess; the sniffed
	 * values overwrite them for the session and are reported so they can be baked.
	 */
	/** Camera pitch, not a model tilt - see ChatheadRenderer. */
	@Getter
	int headPitch = ChatheadRenderer.GAME_PITCH;

	@Getter
	int headZoom = ChatheadRenderer.GAME_ZOOM;

	/**
	 * The chathead's real angle: rotationZ. The game mirrors it between the two
	 * dialog sides - the NPC side (which our layout copies) uses 1882, the player
	 * side 166 - so the follower and the player face each other, as they do in a
	 * real conversation.
	 */
	@Getter
	int npcTurn = ChatheadRenderer.GAME_TURN_NPC;

	/**
	 * The real chathead's rectangle within the dialog, copied off a live NPC
	 * dialog widget. Null until one has been seen, in which case the fallback
	 * below is used.
	 */
	@Setter
	Rectangle headRect;

	/**
	 * The PLAYER side's head cell, measured off a live player dialog: the head
	 * sits on the RIGHT at (433,59) 32x32 - centre (449,75) - mirroring the
	 * NPC side. Kept calibrated by the sniffer whenever a real one opens.
	 */
	@Setter
	Rectangle playerHeadRect = new Rectangle(433, 59, 32, 32);

	/**
	 * The client anchors a widget model at the widget's CENTRE (the origin point)
	 * and lets the geometry overflow, clipped by the chat surface - the declared
	 * widget is 32x32 while the head drawn around it is far larger, and tall hair
	 * cuts exactly at the dialog's top edge. So the canvas spans from the
	 * dialog's top down past the chin, the model is anchored at the measured
	 * origin, and the blit clips at the dialog bounds like the real surface.
	 */
	int originX()
	{
		return headRect == null ? 62 : headRect.x + headRect.width / 2;
	}

	int originY()
	{
		return headRect == null ? 69 : headRect.y + headRect.height / 2;
	}

	/** Wide enough for any hairstyle; the sides clip at the dialog if needed. */
	int headWidth()
	{
		return 150;
	}

	/** From the dialog's top edge down past the chin. */
	int headHeight()
	{
		return originY() + 46;
	}

	int headTurn()
	{
		return headIsPlayer ? ChatheadRenderer.GAME_TURN_PLAYER : npcTurn;
	}

	double headFraction = 0.26;

	final MouseAdapter clickAdapter = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			// Left button only: a right-click opens a menu without committing to
			// anything, so it must not dismiss the conversation - the same way a
			// real dialog survives right-clicking around it.
			if (!open || !javax.swing.SwingUtilities.isLeftMouseButton(event))
				return event;

			// Clicking away from the box dismisses it, as any click that starts
			// another action does in game - ON the tick that action processes.
			// The click is NOT consumed: it still walks you there or opens what
			// you aimed at.
			if (!bounds.contains(event.getPoint()))
			{
				pendingAction = FollowerDialog.this::close;
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
	FollowerDialog(Client client, FollowerEntity follower,
		MouseManager mouseManager, SpriteManager spriteManager,
		ClientThread clientThread,
		AppearanceComposer composer,
		net.runelite.client.input.KeyManager keyManager,
		GameFontRepository gameFonts)
	{
		this.client = client;
		this.follower = follower;
		this.mouseManager = mouseManager;
		this.spriteManager = spriteManager;
		this.clientThread = clientThread;
		this.composer = composer;
		this.gameFonts = gameFonts;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	void register()
	{
		mouseManager.registerMouseListener(clickAdapter);
	}

	void unregister()
	{
		mouseManager.unregisterMouseListener(clickAdapter);
	}

	/**
	 * Whatever the conversation reached that should happen once it is over.
	 * Held rather than run on arrival so the follower's spoken reply lands
	 * after the box is gone rather than over the top of it.
	 */
	Runnable pendingFinish;

	void latch(Node reached)
	{
		if (reached != null && reached.onFinish != null)
			pendingFinish = reached.onFinish;
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

	void close()
	{
		open = false;
		head = null;
		chatheadBase = null;
		talkAnimation = null;
		headOutfit = null;
		headDownloading.clear();
		// An interrupt-close cancels any input still in flight, or a stale
		// queued action would fire a tick after the box is gone.
		clearInput();

		// Cleared before it runs: whatever it does, it must not be able to
		// arrive here a second time.
		var finish = pendingFinish;
		pendingFinish = null;
		if (finish != null)
			finish.run();
	}

	/** Starts a conversation at {@code startId}. */
	void start(String speakerName, Map<String, Node> conversation, String startId)
	{
		followerName = speakerName;
		script = conversation;
		open = true;
		// A fresh conversation inherits nothing from the last one; a leftover
		// would fire on the wrong ending entirely.
		pendingFinish = null;
		goTo(startId);
	}

	/** The current node's pages, resolved per ENTRY so dynamic nodes re-roll. */
	String[] pages = new String[0];

	void goTo(String nodeId)
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

	void advance()
	{
		page++;
		if (page < pages.length || optionsVisible())
			return;
		if (node.next != null)
			goTo(node.next);
		else
			close();
	}

	boolean optionsVisible()
	{
		return node != null && page >= pages.length && node.optionText.length > 0;
	}

	/** The composed dialogue-head model of the current speaker; null = fallback. */
	Model chatheadBase;

	/** The talking animation state, advanced on the wall clock while drawing. */
	Animation talkAnimation;
	/** The id {@link #talkAnimation} was loaded for. */
	int talkLoadedId = -1;
	/** The page an expression was started on: each page starts its face again, as each box does in game. */
	int talkPage = -1,
		talkFrame;
	long talkCarryMs,
		lastTalkMs;

	/**
	 * The last head drawn, and what it was drawn from. A talk frame is held
	 * for several frames of the overlay, and posing and rasterising the head
	 * again for each was a new 150 px image every frame the box was open.
	 */
	BufferedImage lastFace;
	Model lastFaceBase;
	Animation lastFaceAnimation;
	int[] lastFacePose;

	/**
	 * A chathead talking sequence forced by {@code ::follower head talk}, for
	 * testing. 0, the default, plays the neutral face for the lines on show.
	 */
	@Getter
	int talkAnimationId;
	
	/** The neutral talk, CHATNEU1-4, for a box of one to four lines. */
	static final int[] NEUTRAL_TALK = {588, 589, 590, 591};
	
	/**
	 * The clip a box plays: a forced one, else the neutral face, at the
	 * game's length for the lines on show.
	 */
	static int talkClip(int forced, int lines)
	{
		int[] clips = forced > 0 ? new int[]{forced} : NEUTRAL_TALK;
		return clips[Math.max(1, Math.min(clips.length, lines)) - 1];
	}

	/**
	 * The frame after this one, wrapping as the game wraps a clip: back by
	 * its frame step, so a box left open loops the talk's tail and does not
	 * start the mouth again from the top. A clip with no step goes back to 0.
	 */
	static int nextTalkFrame(int frame, int frames, int frameStep)
	{
		int next = frame + 1;
		if (next >= frames)
		{
			next -= frameStep;
			if (next < 0 || next >= frames)
				next = 0;
		}
		return next;
	}

	/**
	 * Takes a live NPC dialog's head turn, pitch and zoom, but only off the
	 * human head's neutral talk (CHATNEU1-4): any other clip may belong to
	 * another skeleton or a boss's or pet's framing, which would bend or
	 * resize the follower's head. The NPC's clip itself is never adopted.
	 */
	void adoptNpcHead(int animationId, int turn, int pitch, int zoom)
	{
		for (int neutral : NEUTRAL_TALK)
		{
			if (animationId == neutral)
			{
				npcTurn = turn & 0x7ff;
				headPitch = pitch & 0x7ff;
				headZoom = Math.max(1, zoom);
				return;
			}
		}
	}

	/**
	 * Rebuilds the head for whoever is speaking, on the client thread - model
	 * loading is client-thread-only, and clicks that change speaker arrive on the
	 * AWT thread.
	 *
	 * <p>Prefers the game's own dialogue-head models (helm head variant, hair and
	 * jaw chatheads, coloured through the same palette as the body); falls back to
	 * cropping the body model when the dump has no head data for the outfit.
	 */
	void refreshHead()
	{
		boolean player = node.playerSpeaking;
		headIsPlayer = player;

		clientThread.invoke(() ->
		{
			chatheadBase = null;
			head = null;
			restartTalk();

			Outfit outfit = null;
			Model cropModel = null;

			if (player)
			{
				var local = client.getLocalPlayer();
				if (local != null && local.getPlayerComposition() != null)
				{
					outfit = Outfit.from(local.getPlayerComposition());
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

			headOutfit = outfit;
			headDownloading.clear();
			headHeldUntil = System.currentTimeMillis() + 3000L /* HEAD_WAIT_MS */;
			if (outfit != null)
				composeHead();
			// Not while a part is on its way: the head comes when it lands.
			if (chatheadBase == null && headDownloading.isEmpty())
				head = renderHead(cropModel, null, headFraction);
		});
	}

	/**
	 * Composes the speaker's head, holding it back while a part is still
	 * downloading. The render asks again as the parts land, so the head is
	 * there the frame the last one does, on the node it was missing from.
	 */
	void composeHead()
	{
		headDownloading.clear();
		var built = composer.composeChathead(headOutfit, headDownloading);
		if (headDownloading.isEmpty() || System.currentTimeMillis() >= headHeldUntil)
		{
			headHeldUntil = 0;
			chatheadBase = built;
		}
	}

	/**
	 * Asks for both heads' models without drawing them, so a part the client
	 * has never drawn is on its way before the box needs it: the follower's
	 * and the player's look as they are now. Client thread only.
	 */
	void fetchHeads()
	{
		if (followerOutfit != null)
			composer.composeChathead(followerOutfit.get(), null);
		var local = client.getLocalPlayer();
		if (local != null && local.getPlayerComposition() != null)
			composer.composeChathead(Outfit.from(local.getPlayerComposition()), null);
	}

	/** Starts the talking face again from its first frame. */
	void restartTalk()
	{
		talkAnimation = null;
		talkFrame = 0;
		talkCarryMs = 0;
		lastTalkMs = System.currentTimeMillis();
	}

	/** The head at the box's size and angles, framed by {@code base} when it is given. */
	BufferedImage renderHead(Model model, Model base, double fraction)
	{
		return ChatheadRenderer.render(model, base, headWidth(), headHeight(),
			headTurn(), headPitch, headZoom, fraction, headWidth() / 2, originY());
	}

	/**
	 * The current animated head image, posed at the talking animation's current
	 * frame. Called from the overlay render pass, which runs on the client thread,
	 * so posing here is legal. The posed model comes back on the client's shared
	 * scratch buffer and is rendered to pixels immediately, never held.
	 */
	BufferedImage animatedHead(int lines)
	{
		// A part landed, or the wait for one is over: the head again, whole or
		// as near as it gets. Nothing is asked once every part is there.
		if (!headDownloading.isEmpty() && (composer.anyArrived(headDownloading)
			|| headHeldUntil != 0 && System.currentTimeMillis() >= headHeldUntil))
		{
			composeHead();
			restartTalk();
		}
		if (chatheadBase == null)
			return head;

		int wanted = talkClip(talkAnimationId, lines);
		if (talkAnimation != null && (wanted != talkLoadedId || page != talkPage))
			restartTalk();
		if (talkAnimation == null)
		{
			talkLoadedId = wanted;
			talkPage = page;
			talkAnimation = client.loadAnimation(wanted);
			if (talkAnimation == null || talkAnimation.getFrameLengths() == null)
			{
				talkAnimation = null;
				return renderHead(chatheadBase, null, 1.0);
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
			talkFrame = nextTalkFrame(talkFrame, lengths.length, talkAnimation.getFrameStep());
		}

		int[] pose = {talkFrame, headTurn(), headPitch, headZoom, headWidth(), headHeight(), originY()};
		if (lastFace != null && lastFaceBase == chatheadBase && lastFaceAnimation == talkAnimation
			&& Arrays.equals(pose, lastFacePose))
			return lastFace;
		var posed = client.applyTransformations(
			chatheadBase, talkAnimation, talkFrame, null, 0);
		lastFace = renderHead(posed == null ? chatheadBase : posed, chatheadBase, 1.0);
		lastFaceBase = chatheadBase;
		lastFaceAnimation = talkAnimation;
		lastFacePose = pose;
		return lastFace;
	}

	String speakerName()
	{
		if (!headIsPlayer)
			return followerName;
		var local = client.getLocalPlayer();
		return local == null || local.getName() == null ? "You" : local.getName();
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!open || node == null)
			return null;

		var chat = client.getWidget(InterfaceID.CHATBOX, 0);
		var area = chat != null && !chat.isHidden()
			? chat.getBounds()
			: new Rectangle(0, client.getCanvasHeight() - 165, 519, 165);
		if (area.width <= 0 || area.height <= BUTTON_STRIP)
			return null;

		// Stop short of the filter buttons, as the game's own dialog does - and
		// because bounds is also the click region, this leaves them clickable.
		bounds = new Rectangle(area.x, area.y, area.width, area.height - BUTTON_STRIP);

		// The game draws its text with a bitmap font and no antialiasing. Leaving
		// AA on softens and visibly thickens every glyph, which is what made this
		// box read as "bolder" than a real one even with the right typeface.
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
			RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
			RenderingHints.VALUE_ANTIALIAS_OFF);

		drawBackground(graphics);

		if (optionsVisible())
			drawOptions(graphics);
		else
			drawSpeech(graphics);

		return null;
	}

	void drawBackground(Graphics2D graphics)
	{
		// CHATBOX_SPRITE: The chatbox background sprite, 1017 (the old api SpriteID.CHATBOX,
		// deprecated without a matching gameval name).
		var sprite = spriteManager.getSprite(1017 /* CHATBOX_SPRITE */, 0);
		if (sprite != null)
			graphics.drawImage(sprite, bounds.x, bounds.y, bounds.width, bounds.height, null);
		else
		{
			// The sprite loads asynchronously; a matched fill covers the first frames.
			graphics.setColor(PARCHMENT);
			graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
		}
	}

	void drawSpeech(Graphics2D graphics)
	{
		var font = gameFonts.get(DIALOG_FONT_ID);
		var wrapped = wrap(pages[page], font::stringWidth, TEXT_WIDTH);
		var face = animatedHead(wrapped.length);

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
			var oldClip = graphics.getClip();
			int clipTop = bounds.y + dialogInsetY + clipTopExtra;
			graphics.setClip(bounds.x + dialogInsetX, clipTop,
				bounds.width - dialogInsetX * 2, bounds.y + bounds.height - clipTop);
			graphics.drawImage(face,
				bounds.x + headCentreX - face.getWidth() / 2, bounds.y, null);
			graphics.setClip(oldClip);
		}

		// The measured text column: 380 wide at x=115 beside an NPC head on the
		// left, x=24 with the player's head on the right.
		int cellX = bounds.x + (headIsPlayer ? 24 /* PLAYER_TEXT_X */ : 115 /* NPC_TEXT_X */);

		drawCell(graphics, font, speakerName(), cellX, TEXT_WIDTH,
			bounds.y + 23 /* NAME_TOP */, NAME.getRGB() & 0xFFFFFF);

		int lineHeight = measuredLineHeight(wrapped.length),
			top = bounds.y + 39 /* BODY_TOP */ + (67 /* BODY_HEIGHT */ - wrapped.length * lineHeight) / 2;
		for (String line : wrapped)
		{
			drawCell(graphics, font, line, cellX, TEXT_WIDTH, top, 0x000000);
			top += lineHeight;
		}

		// The client's own in-flight text: after a continue click, the prompt
		// reads "Please wait..." in its BASE colour (no hover white) until the
		// tick lands.
		String prompt = awaitingContinue ? "Please wait..." : "Click here to continue";
		int promptTop = bounds.y + 103 /* CONTINUE_TOP */,
			promptWidth = font.stringWidth(prompt);
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
	void drawCell(Graphics2D graphics, GameFont font,
		String text, int cellX, int cellWidth, int top, int rgb)
	{
			font.drawTop(graphics, text,
				cellX + (cellWidth - font.stringWidth(text)) / 2, top, rgb, false);
	}

	/** True when the mouse is inside {@code area} on the canvas. */
	boolean hovered(Rectangle area)
	{
		var mouse = client.getMouseCanvasPosition();
		return mouse != null && area.contains(mouse.getX(), mouse.getY());
	}

	void drawOptions(Graphics2D graphics)
	{
		var font = gameFonts.get(DIALOG_FONT_ID);
		// OPTION_X: The option menu's cells, same source: header "Select an option" (dark
		// red, lowercase o - the widget's own text) at (20,22,479,20), flanked by
		// the sword ornaments - sprite 302 on the left at (92,24), its mirror 301
		// on the right at (370,24). Option spacing varies with the COUNT, measured
		// across three-, four- and five-option menus: step = 36 - 4n (24/20/16),
		// cells capped at 20 tall, first tops 46/39/40.
		int cellX = bounds.x + 20 /* OPTION_X */;

		drawCell(graphics, font, "Select an option", cellX, OPTION_WIDTH,
			bounds.y + 22 /* OPTION_HEADER_TOP */, NAME.getRGB() & 0xFFFFFF);

		// The game's own sword ornaments beside the header, at their measured
		// cells - sprite 302 left, mirror 301 right.
		BufferedImage swordLeft = spriteManager.getSprite(302 /* SWORD_LEFT_SPRITE */, 0),
			swordRight = spriteManager.getSprite(301 /* SWORD_RIGHT_SPRITE */, 0);
		if (swordLeft != null)
			graphics.drawImage(swordLeft, bounds.x + 92 /* SWORD_LEFT_X */, bounds.y + SWORD_Y, null);
		if (swordRight != null)
			graphics.drawImage(swordRight, bounds.x + 370 /* SWORD_RIGHT_X */, bounds.y + SWORD_Y, null);

		int count = node.optionText.length,
			step = optionStep(count),
			cellHeight = optionCellHeight(count);
		optionBounds = new Rectangle[count];
		int top = bounds.y + optionFirstTop(count);
		for (int i = 0; i < count; i++)
		{
			var row = new Rectangle(cellX, top, OPTION_WIDTH, cellHeight);
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
	 * The wrap itself, against any measure. A line break typed into the text
	 * starts a new line, as the game's own break tag does, and a blank one
	 * is kept. A word wider than the column alone, such as a pasted link,
	 * is cut where it stops fitting and carried on to the next line; QA2,
	 * 15 Oct: left whole, it ran over the chathead and out of the box.
	 */
	static String[] wrap(String text, java.util.function.ToIntFunction<String> width, int maxWidth)
	{
		List<String> out = new ArrayList<>();
		for (String paragraph : text.split("\n", -1))
		{
			var line = new StringBuilder();
			for (String word : paragraph.split(" "))
			{
				String candidate = line.length() == 0 ? word : line + " " + word;
				if (width.applyAsInt(candidate) > maxWidth && line.length() > 0)
				{
					out.add(line.toString());
					line = new StringBuilder(word);
				}
				else
					line = new StringBuilder(candidate);
				while (line.length() > 1 && width.applyAsInt(line.toString()) > maxWidth)
				{
					int cut = line.length() - 1;
					while (cut > 1 && width.applyAsInt(line.substring(0, cut)) > maxWidth)
						cut--;
					out.add(line.substring(0, cut));
					line = new StringBuilder(line.substring(cut));
				}
			}
			if (line.length() > 0 || (paragraph.isEmpty() && text.contains("\n")))
				out.add(line.toString());
		}
		return out.toArray(new String[0]);
	}
}

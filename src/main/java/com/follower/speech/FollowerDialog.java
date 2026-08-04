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
import net.runelite.api.SpriteID;
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
	 * Vertical layout, measured off a real dialog screenshot: name baseline 32px
	 * from the top, continue baseline 23px above the button strip, and the body
	 * block centred between them at 16px per line.
	 */
	private static final int NAME_BASELINE = 32;
	private static final int CONTINUE_RISE = 23;
	private static final int LINE_SPACING = 16;

	/**
	 * The game's actual dialog fonts: RuneStar's pixel-perfect recreations of the
	 * cache's Plain 12 and Bold 12, rebuilt glyph for glyph from the font data.
	 * RuneLite's bundled runescape.ttf is its own looser recreation, which is why
	 * the text never quite matched a real dialog. Sized 16pt: these TTFs are
	 * authored so 16pt reproduces the native pixel size.
	 */
	private static final java.awt.Font PLAIN_12 = loadFont("RuneScape-Plain-12.ttf");
	private static final java.awt.Font BOLD_12 = loadFont("RuneScape-Bold-12.ttf");

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
			// another action does in game. The click is NOT consumed - it still
			// walks you there or opens what you aimed at.
			if (!bounds.contains(event.getPoint()))
			{
				close();
				return event;
			}

			if (sweepMode)
			{
				close();
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
						goTo(node.optionNext[i]);
						break;
					}
				}
			}
			else
			{
				advance();
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
		net.runelite.client.input.KeyManager keyManager)
	{
		this.client = client;
		this.follower = follower;
		this.mouseManager = mouseManager;
		this.spriteManager = spriteManager;
		this.clientThread = clientThread;
		this.composer = composer;
		this.keyManager = keyManager;
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

	public void close()
	{
		open = false;
		head = null;
		chatheadBase = null;
		talkAnimation = null;
		sweepMode = false;
		sweep = null;
	}

	/** Starts a conversation at {@code startId}. */
	public void start(String speakerName, Map<String, Node> conversation, String startId)
	{
		followerName = speakerName;
		script = conversation;
		open = true;
		goTo(startId);
	}

	/** A one-off line with no branching. */
	public void say(String speakerName, String... pages)
	{
		Map<String, Node> single = new LinkedHashMap<>();
		single.put("start", Node.says(pages));
		start(speakerName, single, "start");
	}

	private void goTo(String nodeId)
	{
		node = nodeId == null ? null : script.get(nodeId);
		if (node == null)
		{
			close();
			return;
		}

		page = 0;
		follower.facePlayer();
		refreshHead();
	}

	private void advance()
	{
		page++;
		if (page < node.pages.length || optionsVisible())
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
		return node != null && page >= node.pages.length && node.optionText.length > 0;
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
		BufferedImage sprite = spriteManager.getSprite(SpriteID.CHATBOX, 0);
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
		// surface cuts it.
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
				bounds.x + originX() - face.getWidth() / 2, bounds.y, null);
			graphics.setClip(oldClip);
		}

		// Text centres across the FULL dialog width, head overlapping - compare
		// any real dialog: the name sits at the box's centre, not the text
		// column's.
		int textLeft = bounds.x;
		int textWidth = bounds.width;

		// The name uses the real b12 - now that it IS the real b12 (RuneStar's
		// pixel recreation), not RuneLite's much heavier bold TTF.
		graphics.setFont(PLAIN_12);
		drawCentred(graphics, speakerName(), textLeft, textWidth,
			bounds.y + NAME_BASELINE, NAME);

		int continueBaseline = bounds.y + bounds.height - CONTINUE_RISE;

		graphics.setFont(PLAIN_12);
		String[] wrapped = wrap(graphics, node.pages[page], textWidth - 8);

		// The body block sits centred between the name and the continue line, the
		// way the real dialog spaces a message of any length.
		int blockCentre = (bounds.y + NAME_BASELINE + continueBaseline) / 2;
		int y = blockCentre - (wrapped.length - 1) * LINE_SPACING / 2 + 4;
		for (String line : wrapped)
		{
			drawCentred(graphics, line, textLeft, textWidth, y, TEXT);
			y += LINE_SPACING;
		}

		String prompt = "Click here to continue";
		int promptWidth = graphics.getFontMetrics().stringWidth(prompt);
		continueBounds = new Rectangle(
			textLeft + (textWidth - promptWidth) / 2, continueBaseline - 12,
			promptWidth, 16);

		drawCentred(graphics, prompt, textLeft, textWidth, continueBaseline,
			hovered(continueBounds) ? HOVER : CONTINUE);
	}

	/** True when the mouse is inside {@code area} on the canvas. */
	private boolean hovered(Rectangle area)
	{
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		return mouse != null && area.contains(mouse.getX(), mouse.getY());
	}

	private void drawOptions(Graphics2D graphics)
	{
		graphics.setFont(PLAIN_12);
		drawCentred(graphics, "Select an Option", bounds.x, bounds.width,
			bounds.y + NAME_BASELINE, TEXT);

		graphics.setFont(PLAIN_12);
		optionBounds = new Rectangle[node.optionText.length];

		// Options are centred as a block in the space below the header, matching
		// how the real menu spaces two options differently from five.
		int step = 18;
		int top = bounds.y + NAME_BASELINE + 10;
		int blockCentre = (top + bounds.y + bounds.height - 8) / 2;
		int y = blockCentre - (node.optionText.length - 1) * step / 2 + 4;

		for (int i = 0; i < node.optionText.length; i++)
		{
			Rectangle row = new Rectangle(bounds.x + 6, y - 13, bounds.width - 12, step);
			optionBounds[i] = row;

			drawCentred(graphics, node.optionText[i], bounds.x, bounds.width, y,
				hovered(row) ? HOVER : TEXT);
			y += step;
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

	/** Greedy word wrap; a single word longer than the column is left to overflow. */
	private static String[] wrap(Graphics2D graphics, String text, int maxWidth)
	{
		java.util.List<String> out = new java.util.ArrayList<>();
		StringBuilder line = new StringBuilder();

		for (String word : text.split(" "))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (graphics.getFontMetrics().stringWidth(candidate) > maxWidth && line.length() > 0)
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

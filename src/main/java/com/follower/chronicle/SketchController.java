package com.follower.chronicle;

import com.follower.follower.ErrandController;
import com.follower.follower.FollowerEntity;
import com.follower.speech.TriggerContext;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.AnimationID;
import net.runelite.client.ui.DrawManager;

/**
 * The scribe sketches the places it has a page for, when you pose for it.
 * Nothing is drawn unasked: somewhere whose plate is still empty it says so
 * once a session, and waits for right-click Sketch or ::follower sketch.
 * Then the scroll comes out, the scribe turns to you and counts you in, and
 * the view is taken - you in the middle of it - a moment into the first
 * emote you do, or at the end of the count if you stand still. It is drawn
 * up in ink and wash for the page, and the scroll put away with a word
 * about the pose, after, so no words are in the picture.
 *
 * <p>A place already drawn is drawn again when asked, its old drawing kept
 * beside it. Moving off your tile, a fight or a window opening spoils the
 * pose, and the scribe says so.
 */
@Slf4j
public class SketchController
{
	/** Standing still this long before an empty plate is pointed out: it is not going to chase you. */
	static final int IDLE_TICKS = 3;

	/** The forced sketch's scroll out this long before the picture is taken. */
	static final int HOLD_TICKS = 3;

	/** A line of the count every this many ticks. */
	static final int COUNT_STEP = 3;

	/** The end of the count, where a pose held still is taken: with the word after, about five seconds. */
	static final int COUNT_TICKS = 8;

	/** An emote is taken this long after it is first seen: mid-move, not its first frame. */
	static final int POSE_DELAY = 2;

	/** And this long after, before it goes away. */
	static final int PUT_AWAY_TICKS = 2;

	/** Said once the sketch is in the book; never before, or it is in the picture. */
	static final String[] DONE = {
		"That's the view. You're in it, for scale.",
		"In the book. I've drawn you a touch taller.",
		"Sketched. The light did most of the work.",
		"Got it. Hold still longer next time and I'll do hands.",
	};

	/** Said instead for a pose it knows, keyed by the emote. */
	static final Map<Integer, String> POSED;

	static
	{
		Map<Integer, String> posed = new HashMap<>();
		posed.put(AnimationID.EMOTE_DANCE, "Drawn mid-dance. I guessed at the feet.");
		posed.put(AnimationID.EMOTE_BOW, "A bow, for posterity. Very proper.");
		posed.put(AnimationID.EMOTE_WAVE, "Waving at whoever reads the book. Friendly of you.");
		posed.put(AnimationID.EMOTE_CHEER, "Mid-cheer. I've toned it down for the book.");
		posed.put(AnimationID.EMOTE_LAUGH, "Laughing, in ink. I left out whatever was funny.");
		posed.put(AnimationID.EMOTE_THINK, "Deep in thought, on paper. I've made you look wise.");
		POSED = Collections.unmodifiableMap(posed);
	}

	/**
	 * The emotes a sketch's caption remembers, by the word filed with it.
	 * Each has a looping copy, played when the game is set to repeat
	 * emotes, which is the same pose. Anything else, standing still
	 * included, is filed with no pose.
	 */
	static final Map<Integer, String> POSE_NAMES;

	static
	{
		Map<Integer, String> names = new HashMap<>();
		names.put(AnimationID.EMOTE_WAVE, "wave");
		names.put(AnimationID.EMOTE_WAVE_LOOP, "wave");
		names.put(AnimationID.EMOTE_DANCE, "dance");
		names.put(AnimationID.EMOTE_DANCE_LOOP, "dance");
		names.put(AnimationID.EMOTE_CHEER, "cheer");
		names.put(AnimationID.EMOTE_CHEER_LOOP, "cheer");
		names.put(AnimationID.EMOTE_BOW, "bow");
		names.put(AnimationID.EMOTE_BOW_LOOP, "bow");
		names.put(AnimationID.EMOTE_CLAP, "clap");
		names.put(AnimationID.EMOTE_CLAP_LOOP, "clap");
		names.put(AnimationID.EMOTE_LAUGH, "laugh");
		names.put(AnimationID.EMOTE_LAUGH_LOOP, "laugh");
		names.put(AnimationID.EMOTE_CRY, "cry");
		names.put(AnimationID.EMOTE_CRY_LOOP, "cry");
		names.put(AnimationID.EMOTE_THINK, "think");
		names.put(AnimationID.EMOTE_THINK_LOOP, "think");
		names.put(AnimationID.EMOTE_FREMMENIK_SALUTE, "salute");
		names.put(AnimationID.EMOTE_FREMMENIK_SALUTE_LOOP, "salute");
		names.put(AnimationID.EMOTE_VARLAMORE_SALUTE, "salute");
		names.put(AnimationID.EMOTE_VARLAMORE_SALUTE_LOOP, "salute");
		names.put(AnimationID.EMOTE_DANCE_SCOTTISH, "jig");
		names.put(AnimationID.EMOTE_DANCE_SCOTTISH_LOOP, "jig");
		names.put(AnimationID.EMOTE_DANCE_HEADBANG, "headbang");
		names.put(AnimationID.EMOTE_DANCE_HEADBANG_LOOP, "headbang");
		names.put(AnimationID.EMOTE_JUMP_WITH_JOY, "jump-for-joy");
		names.put(AnimationID.EMOTE_JUMP_WITH_JOY_LOOP, "jump-for-joy");
		names.put(AnimationID.EMOTE_DANCE_SPIN, "spin");
		names.put(AnimationID.EMOTE_DANCE_SPIN_LOOP, "spin");
		names.put(AnimationID.EMOTE_BLOW_KISS, "blow-kiss");
		names.put(AnimationID.EMOTE_BLOW_KISS_LOOP, "blow-kiss");
		names.put(AnimationID.EMOTE_YAWN, "yawn");
		names.put(AnimationID.EMOTE_YAWN_LOOP, "yawn");
		names.put(AnimationID.EMOTE_SHRUG, "shrug");
		names.put(AnimationID.EMOTE_SHRUG_LOOP, "shrug");
		names.put(AnimationID.EMOTE_FLEX, "flex");
		names.put(AnimationID.EMOTE_FLEX_LOOP, "flex");
		POSE_NAMES = Collections.unmodifiableMap(names);
	}

	/**
	 * Where a plate is empty, once a session and once a day, and only
	 * {@link #INVITES} times; the mute holds it back, as any line of its own.
	 */
	static final String INVITE = "This place wants a sketch. Right-click me and pick Sketch.";

	/** The count, over its head whatever the mute: it was asked for. */
	static final String START = "Pose for me. Any emote will do.";

	static final String[] COUNT = {"Hold it...", "Hold it..."};

	/** The end of the count, once the picture is taken; never before, or it is in it. */
	static final String THERE = "There.";

	/** A pose spoiled by your stepping off. */
	static final String MOVED = "You moved! Ask again when you're set.";

	/** A pose spoiled by a fight, a window or anything else that begins. */
	static final String CAME_UP = "Something came up. Ask again.";

	/** Asked at a bad moment: busy, still walking, or still drawing the last one. */
	static final String NOT_NOW = "Not just now. Ask me again in a moment.";

	static final String HANDS_FULL = "My hands are full just now.";

	/** Asked somewhere no page has a plate for, which only the command can be. */
	static final String NOTHING_HERE = "Nothing in the book wants this place sketched.";

	static final String NO_BACKUP = "I couldn't save a copy of the old drawing, so I haven't drawn over it.";

	enum Stage
	{
		WAITING, HOLDING, POSING, TAKEN
	}

	private final Client client;
	private final FollowerEntity follower;
	private final ErrandController.Hands hands;
	private final ChronicleLoader loader;
	private final SketchBook book;
	private final DrawManager drawManager;
	private final Executor executor;

	/**
	 * Places being drawn up off the client thread and not yet in the book.
	 * Drawing two sizes takes long enough that you could still be standing
	 * there when the scroll is put away, and the place must not be wanted
	 * again, and drawn twice, before the first drawing is filed.
	 */
	private final Set<String> drawing = ConcurrentHashMap.newKeySet();
	private final BooleanSupplier busy;
	private final IntSupplier idleTicks;
	private final IntSupplier region;
	private final Supplier<WorldPoint> playerTile;
	private final IntSupplier playerAnimation;
	private final Consumer<String> say;

	/**
	 * The word after the picture: held by the mute as the invitation is, but
	 * said next, ahead of the lines that waited through the pose, so it
	 * follows "There." and is not put back or pushed out by them.
	 */
	private final Consumer<String> sayNext;

	/** Straight over its head, the mute or not; null clears it, for the picture. */
	private final Consumer<String> overhead;
	private final Random random = new Random();

	/**
	 * How many times a place's empty plate is pointed out, a day apart,
	 * before it is left to you. Text QA5 #64: the same words at nearly every
	 * login, 17 times in 15 sessions, at the same few places.
	 */
	static final int INVITES = 3;

	/** The plates pointed out this session, each once. */
	private final Set<String> invited = new HashSet<>();

	/** The follower's memory, which keeps the invitations from session to session; none in some tests. */
	private Supplier<TriggerContext> memory = () -> null;

	private Stage stage = Stage.WAITING;
	private String key;
	private int ticks;

	/** Whether this sketch was asked for, and so counted in, rather than forced. */
	private boolean asked;

	/** Your tile when you asked: the pose is held on it. */
	private WorldPoint spot;

	/** The count's tick the picture is taken on. */
	private int snapAt;

	/** The first emote you did while posing, or -1. */
	private int pose = -1;

	public SketchController(Client client, FollowerEntity follower, ErrandController.Hands hands,
		ChronicleLoader loader, SketchBook book, DrawManager drawManager, Executor executor,
		BooleanSupplier busy, IntSupplier idleTicks, IntSupplier region, Supplier<WorldPoint> playerTile,
		IntSupplier playerAnimation, Consumer<String> say, Consumer<String> sayNext, Consumer<String> overhead)
	{
		this.client = client;
		this.follower = follower;
		this.hands = hands;
		this.loader = loader;
		this.book = book;
		this.drawManager = drawManager;
		this.executor = executor;
		this.busy = busy;
		this.idleTicks = idleTicks;
		this.region = region;
		this.playerTile = playerTile;
		this.playerAnimation = playerAnimation;
		this.say = say;
		this.sayNext = sayNext;
		this.overhead = overhead;
	}

	/**
	 * The sketch a place still wants, standing in {@code regionId}: the key
	 * of the first plate on any page that sketches here and has none yet.
	 */
	static String wanted(List<ChronicleEntry> entries, int regionId, Predicate<String> have)
	{
		for (ChronicleEntry entry : entries)
		{
			for (ChronicleEntry.Plate plate : entry.platesOrEmpty())
			{
				if (plate.sketch != null && plate.regions != null
					&& plate.regions.contains(regionId) && !have.test(plate.sketch))
				{
					return plate.sketch;
				}
			}
		}
		return null;
	}

	public boolean isSketching()
	{
		return stage != Stage.WAITING;
	}

	/**
	 * Whether you are posing for one, from the ask until the scroll is put
	 * away: the overhead is the count's, and any other line waits, or it
	 * would be in the picture or said over the count.
	 */
	public boolean isPosing()
	{
		return asked && stage != Stage.WAITING;
	}

	/**
	 * Whether asking now would start a sketch: the same gates the asking
	 * passes, with nothing taken out. Play test, 8 Oct: right-click Sketch
	 * was offered wherever a page had a plate, and pressing it while the
	 * follower was busy, walking or holding something only got a refusal.
	 */
	public boolean canSketchNow()
	{
		if (stage != Stage.WAITING || busy.getAsBoolean() || !follower.isSpawned()
			|| !follower.isSettled() || hands.isHolding())
		{
			return false;
		}
		int regionId = region.getAsInt();
		String here = wanted(loader.getEntries(), regionId,
			name -> book.hasBoth(name) || drawing.contains(name));
		if (here == null)
		{
			here = wanted(loader.getEntries(), regionId, name -> false);
		}
		return here != null && !drawing.contains(here);
	}

	/** Once a game tick. */
	public void tick()
	{
		switch (stage)
		{
			case HOLDING:
				if (busy.getAsBoolean() || idleTicks.getAsInt() == 0 || !follower.isSpawned())
				{
					// You moved off, or something else began. Another time.
					putAway(null);
					return;
				}
				if (++ticks >= HOLD_TICKS)
				{
					capture();
					stage = Stage.TAKEN;
					ticks = 0;
				}
				return;
			case POSING:
				holdThePose();
				return;
			case TAKEN:
				ticks++;
				if (asked && ticks == 1)
				{
					// The frame was drawn without it; now it can be said.
					overhead.accept(THERE);
				}
				if (ticks >= PUT_AWAY_TICKS)
				{
					String posed = POSED.get(pose);
					putAway(posed != null ? posed : DONE[random.nextInt(DONE.length)]);
				}
				return;
			default:
				break;
		}
		// Nothing is drawn unasked. A place with a plate still empty is
		// pointed out, once a session, and drawn when you pose for it. Not
		// with a tool kept between trees still in the hand: Sketch would be
		// refused, and the one time it is pointed out spent on nothing. The
		// gates are Sketch's own, so the option is there when the line is.
		if (idleTicks.getAsInt() < IDLE_TICKS || !canSketchNow())
		{
			return;
		}
		String here = wanted(loader.getEntries(), region.getAsInt(),
			name -> book.hasBoth(name) || drawing.contains(name) || invited.contains(name) || !mayInvite(name));
		if (here != null)
		{
			invited.add(here);
			noteInvited(here);
			say.accept(INVITE);
		}
	}

	public void setMemory(Supplier<TriggerContext> memory)
	{
		this.memory = memory == null ? () -> null : memory;
	}

	/**
	 * Whether the plate may be pointed out: not yet today, and not
	 * {@link #INVITES} times already. Each invitation is kept as a dated
	 * one-time line of its own, the first, second and third, so they last
	 * through a logout as the follower's other once-said lines do.
	 */
	private boolean mayInvite(String plate)
	{
		TriggerContext context = memory.get();
		for (int n = 1; context != null && n <= INVITES; n++)
		{
			int days = context.daysSinceSaid(inviteKey(plate, n));
			if (days < 0)
			{
				return true;
			}
			if (days == 0)
			{
				return false;
			}
		}
		return context == null;
	}

	private void noteInvited(String plate)
	{
		TriggerContext context = memory.get();
		for (int n = 1; context != null && n <= INVITES; n++)
		{
			if (!context.hasSaidOnce(inviteKey(plate, n)))
			{
				context.noteSaidOnce(inviteKey(plate, n));
				return;
			}
		}
	}

	private static String inviteKey(String plate, int n)
	{
		return "sketch-invite:" + plate + ":" + n;
	}

	/**
	 * A tick of the pose. Your tile is watched, not how long you have stood
	 * still: an emote resets that, and the emote is the pose.
	 */
	private void holdThePose()
	{
		if (busy.getAsBoolean() || !follower.isSpawned())
		{
			putAway(null);
			overhead.accept(CAME_UP);
			return;
		}
		if (!Objects.equals(spot, playerTile.get()))
		{
			putAway(null);
			overhead.accept(MOVED);
			return;
		}
		ticks++;
		int animation = playerAnimation.getAsInt();
		if (pose == -1 && animation != -1)
		{
			// The first emote is the pose: taken a moment in, mid-move, and
			// the count is over.
			pose = animation;
			snapAt = ticks + POSE_DELAY;
		}
		// A right-click menu open over the view would be in the picture.
		if (ticks >= snapAt && !client.isMenuOpen())
		{
			capture();
			stage = Stage.TAKEN;
			ticks = 0;
		}
		else if (pose == -1 && ticks % COUNT_STEP == 0 && ticks / COUNT_STEP <= COUNT.length)
		{
			overhead.accept(COUNT[ticks / COUNT_STEP - 1]);
		}
	}

	/**
	 * Right-click Sketch, or ::follower sketch: the scroll out, the scribe
	 * turned to you and the count begun. The gates are the forced sketch's,
	 * and so is the backup of a place already drawn.
	 *
	 * @return why not, in the scribe's words, or null once the count is on
	 */
	public String askForSketch()
	{
		String refused = takeOutTheScroll(true, new ArrayList<>());
		if (refused != null)
		{
			return refused;
		}
		stage = Stage.POSING;
		spot = playerTile.get();
		snapAt = COUNT_TICKS;
		// Held on you until the scroll is away. Turned once, a follower that
		// is following turned the way you were turned the moment you began
		// the emote, as it does to watch your work, and was drawn side-on.
		follower.setFacingThePlayer(true);
		follower.facePlayer();
		overhead.accept(START);
		return null;
	}

	/**
	 * ::follower force sketch: this view sketched now, without the count.
	 * A place already drawn is drawn again, and whatever is on disk for it
	 * is backed up first, never written over; if the backup cannot be made,
	 * nothing is drawn. The gates stay: a fight, a window over the view,
	 * anything with the feet or the hands, and moving off mid-sketch.
	 *
	 * @return what it did, or why not, for the chat line
	 */
	public String forceSketch()
	{
		List<String> kept = new ArrayList<>();
		String refused = takeOutTheScroll(false, kept);
		if (refused != null)
		{
			return refused;
		}
		stage = Stage.HOLDING;
		return "Sketching " + key + (kept.isEmpty() ? "."
			: "; the old one is kept as " + String.join(", ", kept) + ".");
	}

	/**
	 * The scroll out and the place chosen, for either way of asking, each
	 * refusal in its own words: the scribe's when you asked, the chat
	 * line's when forced.
	 *
	 * @param kept filled with the backups' names
	 * @return why not, or null with the scroll out and the place chosen
	 */
	private String takeOutTheScroll(boolean asking, List<String> kept)
	{
		if (stage != Stage.WAITING)
		{
			return asking ? NOT_NOW : "Already sketching.";
		}
		if (busy.getAsBoolean() || !follower.isSpawned() || !follower.isSettled())
		{
			return asking ? NOT_NOW : "The follower is busy.";
		}
		// A tool kept between trees is still in the hand, and the scroll
		// would take its place: whichever is out first keeps it.
		if (hands.isHolding())
		{
			return asking ? HANDS_FULL : "The follower's hands are full.";
		}
		int regionId = region.getAsInt();
		String here = wanted(loader.getEntries(), regionId,
			name -> book.hasBoth(name) || drawing.contains(name));
		if (here == null)
		{
			// Drawn already: the first plate here, to be drawn again.
			here = wanted(loader.getEntries(), regionId, name -> false);
		}
		if (here == null)
		{
			return asking ? NOTHING_HERE : "No page sketches this place (region " + regionId + ").";
		}
		if (drawing.contains(here))
		{
			return asking ? NOT_NOW : "Still drawing the last sketch of " + here + ".";
		}
		hands.hold(ErrandController.QUILL_PROP, ErrandController.SCROLL_PROP);
		if (!hands.isHolding())
		{
			return asking ? NOT_NOW : "The scroll would not come out.";
		}
		// Backed up once the scroll is out: taken first, the copies of an
		// attempt that never began were left on disk and never mentioned.
		try
		{
			kept.addAll(book.backUp(here));
		}
		catch (IOException | RuntimeException e)
		{
			hands.release();
			log.warn("Could not back up the sketch of {}", here, e);
			return asking ? NO_BACKUP : "Could not back up the old sketch of " + here + ", so it is left alone.";
		}
		follower.setPoseOverride(ErrandController.WRITE_SCROLL_POSE);
		key = here;
		ticks = 0;
		asked = asking;
		pose = -1;
		return null;
	}

	/**
	 * Puts a sketch under way away without a word, as a scene being filmed
	 * starts: its count and its line after are not the film's. The plates
	 * already pointed out stay pointed out.
	 */
	public void abort()
	{
		if (stage != Stage.WAITING)
		{
			putAway(null);
		}
	}

	/** Drops everything, as a logout does, and the session's invitations with it. */
	public void reset()
	{
		abort();
		invited.clear();
	}

	private void putAway(String line)
	{
		// The pose first, as every other put-away: it is only a field, and the
		// release rebuilds the model, the part that can fail. Only the scroll's
		// own, so a pose something else has put on since stays. The facing
		// is only a field too.
		follower.clearPoseOverride(ErrandController.WRITE_SCROLL_POSE);
		follower.setFacingThePlayer(false);
		hands.release();
		stage = Stage.WAITING;
		key = null;
		ticks = 0;
		asked = false;
		spot = null;
		pose = -1;
		if (line != null)
		{
			sayNext.accept(line);
		}
	}

	/**
	 * Asks for the next frame, and keeps the part of it round you: the
	 * middle of the view, clear of the minimap, the chat and the side
	 * panels, at the plate's own shape.
	 */
	private void capture()
	{
		// Nothing over its head in the picture: the frame asked for is the
		// next drawn, and the count's last line could still be up.
		overhead.accept(null);
		String filing = key;
		String posed = POSE_NAMES.get(pose);
		Rectangle crop = cropAroundYou();
		if (filing == null || crop == null)
		{
			return;
		}
		long seed = filing.hashCode();
		// Nor the scribe: it draws the view, it is not in it. Out for that
		// one frame, back the moment it is taken.
		follower.setOutOfPicture(true);
		drawManager.requestNextFrameListener(frame ->
		{
			follower.setOutOfPicture(false);
			copyAndDraw(frame, crop, filing, seed, posed);
		});
	}

	private void copyAndDraw(Image frame, Rectangle crop, String filing, long seed, String posed)
	{
		// The frame is the client's own buffer, reused on the next draw: copy
		// the part wanted now, and do the slow drawing somewhere else.
		BufferedImage copy = new BufferedImage(crop.width, crop.height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = copy.createGraphics();
		try
		{
			g.drawImage(frame, -crop.x, -crop.y, null);
		}
		finally
		{
			g.dispose();
		}
		drawing.add(filing);
		executor.execute(() ->
		{
			try
			{
				// Twice from the one view: the page's plate, and the plate
				// opened across the window, each drawn at its own size so the
				// ink stays a pen's width in both.
				book.put(filing,
					SketchStyle.draw(copy, ChronicleLayout.PLATE_W - 4, ChronicleLayout.PLATE_H - 4, seed),
					SketchStyle.draw(copy, ChronicleLayout.BIG_PLATE_W - 4, ChronicleLayout.BIG_PLATE_H - 4, seed),
					posed);
			}
			catch (RuntimeException e)
			{
				log.warn("Could not draw the sketch of {}", filing, e);
			}
			finally
			{
				drawing.remove(filing);
			}
		});
	}

	private Rectangle cropAroundYou()
	{
		Player me = client.getLocalPlayer();
		LocalPoint where = me == null ? null : me.getLocalLocation();
		if (where == null)
		{
			return null;
		}
		Point centre = Perspective.localToCanvas(client, where,
			client.getTopLevelWorldView().getPlane(), me.getLogicalHeight() / 2);
		Rectangle clear = clearOfInterfaces(client.getViewportXOffset(), client.getViewportYOffset(),
			client.getViewportWidth(), client.getViewportHeight(), client.isResized());
		return centre == null ? null : crop(clear, centre.getX(), centre.getY(),
			(double) ChronicleLayout.PLATE_W / ChronicleLayout.PLATE_H);
	}

	/**
	 * The part of the view no interface sits on. Fixed mode has none on the
	 * view; resizable lays the minimap, the side panel and the chat over its
	 * corners and edges.
	 */
	static Rectangle clearOfInterfaces(int x, int y, int width, int height, boolean resizable)
	{
		if (!resizable)
		{
			return new Rectangle(x, y, width, height);
		}
		int left = 10;
		int top = 10;
		int right = 250;
		int bottom = 180;
		return new Rectangle(x + left, y + top, Math.max(1, width - left - right),
			Math.max(1, height - top - bottom));
	}

	/**
	 * A crop of the given shape, as wide as half the clear area allows,
	 * centred on you and slid back inside the clear area if it spills.
	 */
	static Rectangle crop(Rectangle clear, int cx, int cy, double aspect)
	{
		int width = (int) Math.min(clear.width, Math.max(160, clear.width * 0.6));
		int height = (int) Math.round(width / aspect);
		if (height > clear.height)
		{
			height = clear.height;
			width = (int) Math.round(height * aspect);
		}
		int x = Math.max(clear.x, Math.min(cx - width / 2, clear.x + clear.width - width));
		int y = Math.max(clear.y, Math.min(cy - height / 2, clear.y + clear.height - height));
		return new Rectangle(x, y, width, height);
	}
}

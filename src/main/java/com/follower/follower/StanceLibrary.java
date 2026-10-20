package com.follower.follower;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.kit.KitType;

/**
 * Learns which stand/walk/run animation set goes with which wielded weapon, by
 * watching real players.
 *
 * <p>A weapon's pose set lives in the game's weapon-stance data, which neither the
 * runtime API nor the cache's {@code ItemDefinition} exposes per item. But every
 * {@link net.runelite.api.Actor} reports its <em>resolved</em> pose ids, and every
 * player's wielded weapon is readable from their composition. Pairing the two gives
 * real, correct data rather than a hardcoded guess.
 *
 * <p>Your own character is the main teacher: equip a weapon and its stances are
 * learned immediately. Crowded areas fill the library quickly. Results persist to
 * {@code stances.json}.
 *
 * <p>What ships with the plugin and what this install learned are kept apart.
 * The shipped library is rebuilt by {@code tools/cache-dumper} from learned
 * stances, Weapon Animation Replacer's data and the game cache; only what was
 * learned here is ever written back, so a fix to the shipped data reaches every
 * install instead of hiding behind an old copy of itself.
 */
@Slf4j
@Singleton
public class StanceLibrary
{
	public static final String FILE_NAME = "stances.json";

	/** Where {@link #backUp} keeps the learned file as it was before the first trim. */
	static final String BACKUP_NAME = FILE_NAME + ".pre-split";

	/** Weapon item id used for "nothing wielded". */
	public static final int UNARMED = 0;

	public static class Stance
	{
		public int idle;
		public int walk;
		public int run;

		/**
		 * The directional poses: back-pedal, side-steps, and turn-in-place -
		 * used when the follower moves while FACING something, the way an
		 * interacting player does. Entries saved before these fields existed
		 * deserialize them as 0; the pose selector falls back to {@code walk}
		 * (the client's own -1 fallback) until the weapon is observed again.
		 */
		public int walkBack;
		public int walkLeft;
		public int walkRight;
		public int turnLeft;
		public int turnRight;

		/**
		 * The weapon's attack animation, learned from players seen swinging it.
		 * Unlike the poses above an actor does not advertise this - it is a
		 * one-shot animation - so it is captured when a player animates while
		 * interacting with something. 0 until observed.
		 */
		public int attack;

		/**
		 * The swing for each attack style, indexed by the game's own style
		 * number (COM_MODE, 0-3). 0 in a slot means that style has not been
		 * seen; null means none have.
		 *
		 * <p>Only the LOCAL player can fill these. Which style anyone else has
		 * selected is not readable - varps are the local player's - so every
		 * other player in the scene can still only teach the generic swing
		 * above. That asymmetry is the whole reason this is a separate field
		 * rather than a replacement: the crowd teaches breadth, your own play
		 * teaches depth, and a weapon ends up with both.
		 *
		 * <p>Deliberately indexed by style NUMBER rather than named stab,
		 * slash or crush. What a style means depends on the weapon type, which
		 * would need a table the cache does not carry - and the follower never
		 * needs to know the name. It needs the animation that goes with the
		 * style the player has selected, and that is exactly what the index is.
		 */
		public int[] attackByStyle;

		/**
		 * The swing for each KIND of blow, in {@link StanceLibrary#STAB},
		 * {@link StanceLibrary#SLASH}, {@link StanceLibrary#CRUSH} order, 0 where
		 * the weapon deals no blow of that kind.
		 *
		 * <p>Shipped only. It comes from Weapon Animation Replacer's sets, which
		 * name a weapon's swings by kind rather than by style button, and which
		 * button deals which kind depends on the weapon. The game's own combat
		 * tab table answers that for the weapon the player is holding (see
		 * {@link StanceLibrary#attackKind}), so the follower throws the same kind of blow the
		 * player has chosen, with its own weapon's swing for it.
		 */
		public int[] attackByType;

		public Stance()
		{
		}

		Stance(int idle, int walk, int run,
			int walkBack, int walkLeft, int walkRight, int turnLeft, int turnRight)
		{
			this.idle = idle;
			this.walk = walk;
			this.run = run;
			this.walkBack = walkBack;
			this.walkLeft = walkLeft;
			this.walkRight = walkRight;
			this.turnLeft = turnLeft;
			this.turnRight = turnRight;
		}

		boolean isComplete()
		{
			return idle > 0 && walk > 0 && run > 0;
		}

		/** How the weapon is carried, without anything about how it is swung. */
		Stance posesOnly()
		{
			return new Stance(idle, walk, run, walkBack, walkLeft, walkRight, turnLeft, turnRight);
		}

		/** How the weapon is swung, without anything about how it is carried. */
		Stance swingsOnly()
		{
			Stance swings = new Stance();
			swings.attack = attack;
			swings.attackByStyle = attackByStyle;
			return swings;
		}

		/**
		 * Whether every pose this learned record knows is the shipped one's.
		 * Such poses are a copy of an older bundle written out by an older
		 * save, and keeping them would hide every later correction to that
		 * weapon.
		 */
		boolean carriedAs(Stance shipped)
		{
			int[] mine = {idle, walk, run, walkBack, walkLeft, walkRight, turnLeft, turnRight};
			int[] theirs = {shipped.idle, shipped.walk, shipped.run, shipped.walkBack,
				shipped.walkLeft, shipped.walkRight, shipped.turnLeft, shipped.turnRight};
			for (int i = 0; i < mine.length; i++)
			{
				if (mine[i] != 0 && mine[i] != theirs[i])
				{
					return false;
				}
			}
			return true;
		}

		/** Whether this learned record knows a swing the shipped one does not. */
		boolean swingsUnlike(Stance shipped)
		{
			if (attack != 0 && attack != shipped.attack)
			{
				return true;
			}
			if (attackByStyle != null)
			{
				for (int styled : attackByStyle)
				{
					if (styled != 0)
					{
						return true;
					}
				}
			}
			return false;
		}

		boolean sameAs(Stance other)
		{
			return idle == other.idle && walk == other.walk && run == other.run
				&& walkBack == other.walkBack
				&& walkLeft == other.walkLeft && walkRight == other.walkRight
				&& turnLeft == other.turnLeft && turnRight == other.turnRight;
		}

		/**
		 * Same weapon CLASS: the idle/walk/run triple is what the game shares
		 * between weapons of a kind. Measured across 190 observed weapons,
		 * those triples collapse to 32 classes - every scimitar walks alike,
		 * every staff walks alike - which is what lets one weapon's attack
		 * stand in for its whole family.
		 */
		boolean sameClassAs(Stance other)
		{
			return idle == other.idle && walk == other.walk && run == other.run;
		}
	}

	/** The kinds of blow, in the order of {@link Stance#attackByType}. */
	public static final int STAB = 0;
	public static final int SLASH = 1;
	public static final int CRUSH = 2;

	/** Shipped beside the stances: what is not an attack, and what each style button deals. */
	public static final String KINDS_FILE = "attack-kinds.json";

	/** The shape of {@link #KINDS_FILE}, written by tools/cache-dumper. */
	private static final class AttackKinds
	{
		/** Blocks, defends and specials: never a weapon's ordinary swing. */
		int[] notAttacks;
		/** Spell casts, by Jagex's names for them. */
		int[] casts;
		/** Combat tab weapon category -> the kind of blow each style button deals, -1 for none. */
		Map<String, int[]> styleKinds;
	}

	private final Gson gson;
	private final com.follower.appearance.ModelRepository models;

	/** weapon item id -> stance set, as shipped. */
	private final Map<Integer, Stance> bundled = new HashMap<>();

	/** weapon item id -> stance set, as learned by this install. The only part saved. */
	private final Map<Integer, Stance> learned = new HashMap<>();

	private Set<Integer> notAttacks = Collections.emptySet();
	private Set<Integer> casts = Collections.emptySet();
	private Map<String, int[]> styleKinds = Collections.emptyMap();

	/** base item name -> a weapon id known to have that name's stance. */
	private Map<String, Integer> byBaseName;

	/** metal-stripped item name -> a weapon id, for inheriting across tiers. */
	private Map<String, Integer> byTierName;

	/** How many stances the name index was built from, so it rebuilds when they grow. */
	private int indexedSize = -1;

	/** weapon id -> the weapon whose stance it inherits, or 0 for none. */
	private final Map<Integer, Integer> donors = new HashMap<>();

	private Path file;
	private boolean dirty;

	@Inject
	public StanceLibrary(Gson gson, com.follower.appearance.ModelRepository models)
	{
		this.gson = gson;
		this.models = models;
		// Unarmed is known and never needs learning.
		bundled.put(UNARMED, new Stance(PlayerPose.IDLE, PlayerPose.WALK, PlayerPose.RUN,
			PlayerPose.TURN_180, PlayerPose.SIDESTEP_LEFT, PlayerPose.SIDESTEP_RIGHT,
			PlayerPose.IDLE_TURN, PlayerPose.IDLE_TURN));
	}

	/**
	 * Loads the bundled starter library first, then whatever this install has
	 * observed on top of it.
	 *
	 * <p>Weapon animations cannot be read from the cache - measured, not
	 * assumed: item params hold combat stats, item category disagrees with
	 * the observed stances, and no struct carries a pose set. The client
	 * resolves them in script at equip time. Observation is therefore the
	 * only honest source, and shipping a library means a new install starts
	 * with hundreds of weapons already right instead of everything falling
	 * back to unarmed.
	 */
	public void load(Path dataDir)
	{
		file = dataDir.resolve(FILE_NAME);

		try (java.io.InputStream in = StanceLibrary.class
			.getResourceAsStream("/com/follower/" + FILE_NAME))
		{
			if (in != null)
			{
				int added = read(new java.io.BufferedReader(
					new java.io.InputStreamReader(in, StandardCharsets.UTF_8)), bundled);
				log.info("Loaded {} bundled weapon stances", added);
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not read the bundled stance library", e);
		}
		loadKinds();

		if (!Files.isRegularFile(file))
		{
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			// Local observations win: this client saw them first-hand.
			log.info("Loaded {} learned weapon stances", read(reader, learned));
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not read {}", file, e);
		}

		// Older saves wrote the whole library, shipped stances included, so the
		// learned file still holds copies of an older bundle, some with a swing
		// learned on top. The copied poses go, any swing of its own stays, and
		// the file is rewritten that way at the next save.
		int copies = 0;
		for (java.util.Iterator<Map.Entry<Integer, Stance>> it = learned.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<Integer, Stance> entry = it.next();
			Stance mine = entry.getValue();
			Stance shipped = bundled.get(entry.getKey());
			if (shipped == null || !mine.carriedAs(shipped))
			{
				continue;
			}
			if (!mine.swingsUnlike(shipped))
			{
				it.remove();
				copies++;
			}
			else if (mine.isComplete())
			{
				entry.setValue(mine.swingsOnly());
				copies++;
			}
		}
		if (copies > 0)
		{
			backUp();
			dirty = true;
			log.info("Trimmed {} learned stance(s) the shipped library already holds", copies);
		}
	}

	/**
	 * Keeps the learned file as it was, once, before the trim rewrites it. An
	 * older build of the plugin ships a smaller library and reads only this
	 * file, so after a rollback the weapons it knew from those copies would
	 * be gone, and its own next save would make that permanent. Renaming the
	 * backup over the file brings them back.
	 */
	private void backUp()
	{
		Path backup = file.resolveSibling(BACKUP_NAME);
		if (Files.exists(backup))
		{
			return;
		}
		try
		{
			Files.copy(file, backup);
		}
		catch (IOException e)
		{
			log.warn("Could not back up {} to {}", file, backup, e);
		}
	}

	private void loadKinds()
	{
		try (java.io.InputStream in = StanceLibrary.class
			.getResourceAsStream("/com/follower/" + KINDS_FILE))
		{
			if (in == null)
			{
				return;
			}
			AttackKinds kinds = gson.fromJson(new java.io.InputStreamReader(in, StandardCharsets.UTF_8),
				AttackKinds.class);
			if (kinds == null)
			{
				return;
			}
			notAttacks = toSet(kinds.notAttacks);
			casts = toSet(kinds.casts);
			if (kinds.styleKinds != null)
			{
				styleKinds = kinds.styleKinds;
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not read {}", KINDS_FILE, e);
		}
	}

	private static Set<Integer> toSet(int[] ids)
	{
		Set<Integer> out = new HashSet<>();
		if (ids != null)
		{
			for (int id : ids)
			{
				out.add(id);
			}
		}
		return out;
	}

	/** @return how many entries were taken from this source. */
	private int read(Reader reader, Map<Integer, Stance> stances)
	{
		Map<String, Stance> loaded = gson.fromJson(reader,
			new com.google.gson.reflect.TypeToken<Map<String, Stance>>()
			{
			}.getType());
		if (loaded == null)
		{
			return 0;
		}
		int added = 0;
		for (Map.Entry<String, Stance> entry : loaded.entrySet())
		{
			try
			{
				stances.put(Integer.parseInt(entry.getKey()), entry.getValue());
				added++;
			}
			catch (NumberFormatException ignored)
			{
				// Skip malformed key.
			}
		}
		return added;
	}

	/**
	 * Forgets every learned attack, keeping how each weapon is CARRIED.
	 *
	 * <p>For repairing a library that learned blocks as attacks before the
	 * observer could tell them apart. The carry poses are safe - they come
	 * from the composition rather than from a moment of combat - and an attack
	 * is re-learned within a fight or two of holding the weapon, so this costs
	 * a little accuracy briefly and fixes a follower that flinches instead of
	 * swinging.
	 */
	public void clearLearnedAttacks()
	{
		int cleared = 0;
		for (Stance stance : learned.values())
		{
			// The styled slots were learned the same way and are just as
			// suspect - a block filed under style 1 flinches as surely as one
			// filed as the generic swing.
			if (stance.attack > 0 || stance.attackByStyle != null)
			{
				stance.attack = 0;
				stance.attackByStyle = null;
				cleared++;
			}
		}
		if (cleared > 0)
		{
			dirty = true;
			log.info("Cleared {} learned attack animation(s) for relearning", cleared);
		}
	}

	public void save()
	{
		if (!dirty || file == null)
		{
			return;
		}

		// Only what this install learned: the shipped stances come from the
		// plugin, and a copy of them here would outlive every fix to them.
		Map<String, Stance> out = new HashMap<>();
		for (Map.Entry<Integer, Stance> entry : learned.entrySet())
		{
			out.put(Integer.toString(entry.getKey()), entry.getValue());
		}

		try
		{
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8))
			{
				gson.toJson(out, writer);
			}
			dirty = false;
		}
		catch (IOException e)
		{
			log.warn("Could not write {}", file, e);
		}
	}

	/**
	 * Records the stance set of every player in the scene. Cheap enough to run each
	 * game tick; only genuinely new weapons cause a write.
	 */
	public void observe(Client client)
	{
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			// Between worlds: nobody to watch this tick.
			return;
		}
		if (!learnedChecked)
		{
			learnedChecked = true;
			forgetObstacleSets(client);
		}
		for (Player player : view.players())
		{
			if (player == null)
			{
				continue;
			}
			learn(client, player);
		}
	}

	/** Whether the learned file has been checked for obstacle sets, which needs the client. */
	private boolean learnedChecked;

	/**
	 * Takes out of the learned file any pose set an older build learned off
	 * a player on an obstacle (see {@link #emptiesAHand}) or mid-swap (see
	 * {@link #isAPassingSwap}), keeping any swing learned with it. Unarmed
	 * ships with the plugin, so a learned unarmed set is always one of these.
	 */
	private void forgetObstacleSets(Client client)
	{
		int forgotten = 0;
		for (java.util.Iterator<Map.Entry<Integer, Stance>> it = learned.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<Integer, Stance> entry = it.next();
			Stance mine = entry.getValue();
			if (!mine.isComplete() || (entry.getKey() != UNARMED && !emptiesAHand(client, mine)
				&& !isAPassingSwap(entry.getKey(), mine)))
			{
				continue;
			}
			if (mine.attack != 0 || mine.attackByStyle != null)
			{
				entry.setValue(mine.swingsOnly());
			}
			else
			{
				it.remove();
			}
			forgotten++;
		}
		if (forgotten > 0)
		{
			dirty = true;
			log.info("Forgot {} learned stance(s) taken from a player on an obstacle or mid-swap", forgotten);
		}
	}

	/**
	 * Whether a set keeps a shipped weapon's own stand but walks or runs
	 * otherwise. Seen once, it overrode the shipped carry for good: the
	 * learned file held seven, two-handers and godswords on their own
	 * stand and the plain walk and run (qa5 #31). A weapon's stand comes
	 * with its walk and run, so a set like that is the game, or another
	 * plugin, swapping the moves for a moment, never a new way to carry it.
	 */
	private boolean isAPassingSwap(int weaponId, Stance stance)
	{
		Stance shipped = bundled.get(weaponId);
		return shipped != null && shipped.isComplete() && shipped.idle == stance.idle
			&& (shipped.walk != stance.walk || shipped.run != stance.run);
	}

	/**
	 * Whether the stand, walk or run of a set empties a hand. No weapon's
	 * does; the sets the game swaps in for an agility obstacle (the log
	 * balance, the ledge shimmy, the monkey bars) empty both, so a player
	 * seen on one teaches nothing.
	 */
	private static boolean emptiesAHand(Client client, Stance stance)
	{
		for (int pose : new int[]{stance.idle, stance.walk, stance.run})
		{
			net.runelite.api.Animation animation = client.loadAnimation(pose);
			if (animation != null && (animation.getLeftHandItem() == FilmSet.EMPTY
				|| animation.getRightHandItem() == FilmSet.EMPTY))
			{
				return true;
			}
		}
		return false;
	}

	private void learn(Client client, Player player)
	{
		PlayerComposition composition = player.getPlayerComposition();
		if (composition == null)
		{
			return;
		}

		int raw = composition.getEquipmentId(KitType.WEAPON);
		// getEquipmentId returns the item id directly, or -1 when nothing is wielded.
		int weaponId = raw <= 0 ? UNARMED : raw;
		if (weaponId == UNARMED)
		{
			// Shipped, and never learned: an empty-handed player on a log
			// would teach the follower to balance on flat ground.
			return;
		}

		Stance observed = new Stance(
			player.getIdlePoseAnimation(),
			player.getWalkAnimation(),
			player.getRunAnimation(),
			player.getWalkRotate180(),
			player.getWalkRotateLeft(),
			player.getWalkRotateRight(),
			player.getIdleRotateLeft(),
			player.getIdleRotateRight());

		if (!observed.isComplete() || emptiesAHand(client, observed) || isAPassingSwap(weaponId, observed))
		{
			return;
		}

		Stance known = learned.get(weaponId);
		// A learned record holding only a swing is carried as shipped.
		Stance reference = known != null && known.isComplete() ? known : bundled.get(weaponId);
		if (reference != null && reference.sameAs(observed))
		{
			return;
		}
		if (known != null)
		{
			// Poses changed but the swings did not: carry them across rather
			// than throwing away a hard-won observation.
			observed.attack = known.attack;
			observed.attackByStyle = known.attackByStyle;
		}

		learned.put(weaponId, observed);
		dirty = true;
		log.debug("Learned stance for weapon {}: idle={} walk={} run={} back={} left={} right={}",
			weaponId, observed.idle, observed.walk, observed.run,
			observed.walkBack, observed.walkLeft, observed.walkRight);
	}

	/**
	 * Records a player's one-shot animation as their weapon's attack.
	 *
	 * <p>The gate is that they are INTERACTING with an actor: combat always
	 * has a target, while the animations that would otherwise pollute this -
	 * woodcutting, mining, cooking, emotes, eating - either target a scene
	 * object or nothing at all. Skilling with an axe therefore never gets
	 * mistaken for an axe attack.
	 */
	public void learnAttack(Player player, int animationId)
	{
		learnAttack(player, animationId, -1);
	}

	/**
	 * @param style the attack style the swing was performed in (COM_MODE,
	 * 0-3), or -1 when it cannot be known - which is every player but the
	 * local one
	 */
	public void learnAttack(Player player, int animationId, int style)
	{
		if (animationId <= 0 || player.getInteracting() == null)
		{
			return;
		}
		PlayerComposition composition = player.getPlayerComposition();
		if (composition == null)
		{
			return;
		}
		int raw = composition.getEquipmentId(KitType.WEAPON);
		int weaponId = raw <= 0 ? UNARMED : raw;

		Stance stance = learned.get(weaponId);
		if (stance == null)
		{
			Stance shipped = bundled.get(weaponId);
			if (shipped == null || shipped.attack == animationId)
			{
				// No pose set yet means the weapon is brand new to us; observe()
				// creates it on the next tick and the attack lands then.
				return;
			}
			// Carried as shipped, swung as seen: the learned record holds the
			// swing alone. A copy of the shipped poses here would outlive
			// every later fix to them.
			stance = new Stance();
			learned.put(weaponId, stance);
		}
		if (stance.attack == animationId)
		{
			return;
		}
		if (style >= 0 && style < STYLES)
		{
			if (stance.attackByStyle == null)
			{
				stance.attackByStyle = new int[STYLES];
			}
			if (stance.attackByStyle[style] != animationId)
			{
				stance.attackByStyle[style] = animationId;
				dirty = true;
				log.debug("Learned attack animation {} for weapon {} in style {}",
					animationId, weaponId, style);
			}
		}

		// The generic slot is still written, and still by everyone: it is what
		// borrowing across a stance class reads, and what an unknown style
		// falls back to.
		if (stance.attack == animationId)
		{
			return;
		}
		stance.attack = animationId;
		dirty = true;
		log.debug("Learned attack animation {} for weapon {}", animationId, weaponId);
	}

	/**
	 * How a weapon is USED, which the pose set does not say.
	 *
	 * <p>Supplied by the plugin from the item's own equipment stats, because
	 * the pose triple describes how a weapon is CARRIED and nothing more. The
	 * 79-weapon class that walks like an unarmed player holds the Dragon
	 * harpoon and the Bow of faerdhinen side by side: identical carry, utterly
	 * different swing. Borrowing across that would put a chop on a bow.
	 *
	 * <p>Combat style alone turned out to be too coarse. That same class went
	 * on to collect a bow, a dart and a knife - all ranged, none animating
	 * remotely alike - and the follower would have shot a bow like a thrown
	 * dart. Handedness splits them cleanly, so the key carries both.
	 */
	public interface StyleSource
	{
		int MELEE = 0;
		int RANGED = 1;
		int MAGIC = 2;
		int UNKNOWN = -1;

		/** A style and handedness folded into one comparable key. */
		static int packed(int style, boolean twoHanded)
		{
			return style < 0 ? UNKNOWN : style * 2 + (twoHanded ? 1 : 0);
		}

		/** {@link #packed} for this item, or {@link #UNKNOWN}. */
		int usageOf(int itemId);
	}

	private StyleSource styleSource;

	public void setStyleSource(StyleSource styleSource)
	{
		this.styleSource = styleSource;
	}

	/**
	 * The attack animation to use for a weapon: its own if it has been seen
	 * used, otherwise one borrowed from a weapon that both carries AND fights
	 * the same way.
	 *
	 * <p>The borrow is what keeps this practical. Nobody owns every weapon, but
	 * weapons of a kind share a pose set, so seeing ONE whip swung covers every
	 * whip in the game. Not every class names a kind, though: see
	 * {@link #isPlainHuman}.
	 *
	 * <p>Matching the pose class alone is not enough, though. It groups by how
	 * a weapon is carried, and melee and ranged weapons can be carried alike,
	 * so the combat style has to agree as well. When the style of either weapon
	 * cannot be established the borrow is refused outright: a wrong animation
	 * is worse than a plain one, which is the same reason the variant matching
	 * is exact rather than fuzzy.
	 *
	 * @return 0 when nothing can be borrowed, leaving the caller to fall back
	 */
	public int attackFor(int weaponItemId)
	{
		return attackFor(weaponItemId, -1, -1);
	}

	/** How many attack styles the game numbers, for the per-style slots. */
	private static final int STYLES = 4;

	/**
	 * @param style the style the swing should be in (COM_MODE, 0-3), or -1 to
	 * take whatever the weapon is generally known to do
	 */
	public int attackFor(int weaponItemId, int style)
	{
		return attackFor(weaponItemId, style, -1);
	}

	/**
	 * The swing for a weapon, most trusted answer first:
	 * <ol>
	 * <li>the shipped swing for the kind of blow being thrown;</li>
	 * <li>the swing this install saw in that style, unless it was really a
	 * block, a special or a spell;</li>
	 * <li>the weapon's shipped swing;</li>
	 * <li>its learned swing, unless that was really a block or special;</li>
	 * <li>the same four for the weapon it inherits by name;</li>
	 * <li>a borrow from its weapon class.</li>
	 * </ol>
	 * The shipped data goes first because what was learned includes blocks,
	 * casts and skilling swings filed as attacks, while the shipped swings
	 * were checked against Weapon Animation Replacer's sets and the cache.
	 *
	 * <p>A real weapon never gets the unarmed punch or kick. A weapon nothing is
	 * known about falls back to the unarmed stance to stand and walk with, and
	 * it used to fall back to the unarmed punch to fight with as well, so a
	 * corrupted bow punched. 0 instead lets the caller pick a plain swing for
	 * the style.
	 *
	 * @param style the style button the player has selected (COM_MODE, 0-3), or -1
	 * @param kind the kind of blow that style deals, {@link #STAB}, {@link #SLASH}
	 * or {@link #CRUSH} (see {@link #attackKind}), or -1 when it is not a melee blow
	 */
	public int attackFor(int weaponItemId, int style, int kind)
	{
		int id = weaponItemId <= 0 ? UNARMED : weaponItemId;
		boolean realWeapon = id != UNARMED;
		int own = ownAttack(id, style, kind, realWeapon);
		if (own > 0)
		{
			return own;
		}
		// Then the weapon it inherits by name, even when it has a record of
		// its own: a variant seen carried but never swung knows how it stands
		// and nothing about how it fights, and its plain version may.
		int donor = realWeapon ? donorFor(id) : UNARMED;
		if (donor != UNARMED)
		{
			int inherited = ownAttack(donor, style, kind, true);
			if (inherited > 0)
			{
				return inherited;
			}
		}

		Stance stance = forWeapon(weaponItemId);
		int usage = styleSource == null
			? StyleSource.UNKNOWN : styleSource.usageOf(weaponItemId);
		if (usage == StyleSource.UNKNOWN || isPlainHuman(stance))
		{
			return 0;
		}

		// A class can hold several different attacks once a few of its weapons
		// have been seen: most share the generic swing for their kind, while
		// the occasional signature weapon has one all of its own. Taking the
		// first match would hand a plain battlestaff the Eye of ayak's unique
		// animation, and worse, WHICH one it took depended on hash order - the
		// same weapon could animate differently between sessions.
		//
		// So: whichever attack the most weapons of this class agree on, with
		// ties going to the lowest item id. Agreement finds the generic swing
		// as soon as two weapons share it, and the tie-break stands in until
		// then, since the plainer weapon of a kind is almost always the older
		// and lower-numbered one.
		Map<Integer, Integer> agreement = new HashMap<>();
		Map<Integer, Integer> lowestDonor = new HashMap<>();
		for (int other : knownIds())
		{
			int attack = genericAttack(other, true);
			if (attack <= 0 || !stanceOf(other).sameClassAs(stance)
				|| styleSource.usageOf(other) != usage)
			{
				continue;
			}
			agreement.merge(attack, 1, Integer::sum);
			lowestDonor.merge(attack, other, Math::min);
		}

		int best = 0;
		for (Map.Entry<Integer, Integer> candidate : agreement.entrySet())
		{
			if (best == 0
				|| candidate.getValue() > agreement.get(best)
				|| (candidate.getValue().equals(agreement.get(best))
					&& lowestDonor.get(candidate.getKey()) < lowestDonor.get(best)))
			{
				best = candidate.getKey();
			}
		}
		return best;
	}

	/** The first four steps of {@link #attackFor}, from one weapon's own records, or 0. */
	private int ownAttack(int key, int style, int kind, boolean realWeapon)
	{
		Stance shipped = bundled.get(key);
		Stance mine = learned.get(key);
		if (shipped != null && shipped.attackByType != null && kind >= 0
			&& kind < shipped.attackByType.length
			&& swingable(shipped.attackByType[kind], realWeapon))
		{
			return shipped.attackByType[kind];
		}
		// The style the player actually has selected, when this weapon has
		// been swung in it. Not borrowed across the class: a style number
		// means different things on different weapon types, so another
		// weapon's slot 2 is not this weapon's slot 2.
		if (mine != null && style >= 0 && mine.attackByStyle != null
			&& style < mine.attackByStyle.length)
		{
			int styled = mine.attackByStyle[style];
			if (swingable(styled, realWeapon) && !notAttacks.contains(styled)
				&& !casts.contains(styled))
			{
				return styled;
			}
		}
		return genericAttack(key, realWeapon);
	}

	/**
	 * A weapon's swing when nothing says which kind of blow: the shipped one,
	 * else the learned one if it is not really a block or special.
	 */
	private int genericAttack(int key, boolean realWeapon)
	{
		Stance shipped = bundled.get(key);
		if (shipped != null && swingable(shipped.attack, realWeapon))
		{
			return shipped.attack;
		}
		Stance mine = learned.get(key);
		if (mine != null && swingable(mine.attack, realWeapon) && !notAttacks.contains(mine.attack))
		{
			return mine.attack;
		}
		return 0;
	}

	/** Whether an animation can be a weapon's swing at all. */
	private static boolean swingable(int animation, boolean realWeapon)
	{
		return animation > 0 && !(realWeapon
			&& (animation == AnimationID.HUMAN_UNARMEDPUNCH || animation == AnimationID.HUMAN_UNARMEDKICK));
	}

	/**
	 * Whether a stance is how a person with nothing in particular stands and
	 * moves, so its class names no weapon type and must not lend a swing.
	 *
	 * <p>Decided by Jagex's own names. A class is normally named for its weapon -
	 * HUMAN_STAFFREADY, XBOWS_HUMAN_READY, SLAYER_ABYSSAL_WHIP_WALK - and borrowing
	 * inside it stays inside one kind of weapon. HUMAN_READY with HUMAN_WALK_F and
	 * HUMAN_RUNNING is the plain human stance instead, which unarmed shares with
	 * every one-handed sword, mace, axe, dagger, pickaxe, claw, bow and dart. A
	 * borrow there gave daggers, maces and warhammers a sword's slash and claws
	 * the macuahuitl's crush. Any other name in the three keeps the borrow: the
	 * whip only walks differently, and that is enough to name it.
	 */
	private static boolean isPlainHuman(Stance stance)
	{
		return stance.idle == AnimationID.HUMAN_READY && stance.walk == AnimationID.HUMAN_WALK_F
			&& stance.run == AnimationID.HUMAN_RUNNING;
	}

	/**
	 * Which kind of blow a style button deals, from the game's own combat tab
	 * table.
	 *
	 * @param weaponCategory the player's weapon category (VarbitID.COMBAT_WEAPON_CATEGORY)
	 * @param style the selected style button (COM_MODE)
	 * @return {@link #STAB}, {@link #SLASH} or {@link #CRUSH}, or -1 when the
	 * button deals none of them or is not known
	 */
	public int attackKind(int weaponCategory, int style)
	{
		int[] kinds = styleKinds.get(Integer.toString(weaponCategory));
		return kinds == null || style < 0 || style >= kinds.length ? -1 : kinds[style];
	}

	/**
	 * A spell cast to go with this weapon, for a follower that fights with
	 * magic: one learned with it, else its shipped swing when that is a cast,
	 * as a powered staff's is.
	 *
	 * <p>Not {@link #attackFor}: a staff's swing is its melee bash, and a
	 * caster should cast. Like a swing, a cast comes from the weapon's own
	 * records first and then from the weapon it inherits by name, never from
	 * the unarmed record for a real weapon.
	 *
	 * @return 0 when there is none, leaving the caller to cast its default
	 */
	public int castFor(int weaponItemId)
	{
		int id = weaponItemId <= 0 ? UNARMED : weaponItemId;
		int cast = ownCast(id);
		if (cast > 0 || id == UNARMED)
		{
			return cast;
		}
		int donor = donorFor(id);
		return donor == UNARMED ? 0 : ownCast(donor);
	}

	private int ownCast(int key)
	{
		Stance mine = learned.get(key);
		if (mine != null)
		{
			if (casts.contains(mine.attack))
			{
				return mine.attack;
			}
			if (mine.attackByStyle != null)
			{
				for (int styled : mine.attackByStyle)
				{
					if (casts.contains(styled))
					{
						return styled;
					}
				}
			}
		}
		Stance shipped = bundled.get(key);
		return shipped != null && casts.contains(shipped.attack) ? shipped.attack : 0;
	}

	/**
	 * A staff's bash as the follower should play it: without a shield the
	 * game swings with both hands, with one it uses the one-handed chop.
	 * Weapon Animation Replacer records this for both staff sets - 419, or 414
	 * for the second staff set, without a shield and 393 with one. Not yet
	 * confirmed in game: four of the five staves this plugin learned swinging
	 * used 393, which fits, but whether a shield was worn is not recorded.
	 */
	public static int withShield(int attack, boolean shield)
	{
		return shield && (attack == AnimationID.HUMAN_STAFFORB_PUMMEL || attack == AnimationID.HUMAN_STAFF_PUMMEL)
			? AnimationID.HUMAN_AXE_CHOP : attack;
	}

	/**
	 * Sets a weapon's stance by hand, for weapons this account cannot wield
	 * and nobody nearby is carrying.
	 *
	 * <p>Weapon animations exist nowhere in the cache - item params hold
	 * combat stats, item category contradicts the observed stances, no struct
	 * carries a pose set, and a byte-level sweep of every archive turns up
	 * only NPC definitions and coincidence. The client assembles them at
	 * runtime. So when observation is impossible, a typed-in id from the
	 * wiki is the honest fallback: real data, just sourced by hand.
	 *
	 * @param attack 0 to leave the learned attack alone
	 */
	public void setManual(int weaponItemId, int idle, int walk, int run, int attack)
	{
		int id = weaponItemId <= 0 ? UNARMED : weaponItemId;
		Stance stance = learned.get(id);
		if (stance == null || !stance.isComplete())
		{
			// Keep any turns and side-steps the shipped stance knows, and any
			// swing already learned.
			Stance shipped = bundled.get(id);
			Stance poses = shipped != null ? shipped.posesOnly() : new Stance();
			if (stance != null)
			{
				poses.attack = stance.attack;
				poses.attackByStyle = stance.attackByStyle;
			}
			stance = poses;
			learned.put(id, stance);
		}
		stance.idle = idle;
		stance.walk = walk;
		stance.run = run;
		// Directional poses are optional; the selector falls back to walk,
		// which is the client's own behaviour for a stance that lacks them.
		if (attack > 0)
		{
			stance.attack = attack;
		}
		dirty = true;
		log.info("Stance set by hand for weapon {}: idle={} walk={} run={} attack={}",
			id, idle, walk, run, stance.attack);
	}

	/** A weapon's own stance, learned poses over shipped, or null when never seen or set. */
	public Stance describe(int weaponItemId)
	{
		return stanceOf(weaponItemId <= 0 ? UNARMED : weaponItemId);
	}

	/** Every weapon's own stance, learned poses over shipped, for auditing what the library actually holds. */
	public Map<Integer, Stance> all()
	{
		Map<Integer, Stance> all = new HashMap<>();
		for (int id : knownIds())
		{
			all.put(id, stanceOf(id));
		}
		return Collections.unmodifiableMap(all);
	}

	/**
	 * A learned record's poses when it has them, else the shipped stance. A
	 * learned record can hold just a swing, seen with a weapon carried exactly
	 * as shipped.
	 */
	private Stance stanceOf(int id)
	{
		Stance mine = learned.get(id);
		return mine != null && mine.isComplete() ? mine : bundled.get(id);
	}

	private boolean known(int id)
	{
		return stanceOf(id) != null;
	}

	private Set<Integer> knownIds()
	{
		Set<Integer> ids = new HashSet<>(bundled.keySet());
		for (Map.Entry<Integer, Stance> entry : learned.entrySet())
		{
			if (entry.getValue().isComplete())
			{
				ids.add(entry.getKey());
			}
		}
		return ids;
	}

	/**
	 * The stance for a weapon: its own if observed, else the one belonging to
	 * the same weapon under a different trim, else unarmed.
	 *
	 * <p>The inheritance is what makes the library cover far more than it has
	 * seen. An ornament kit, a trimmed or gilded version, a poisoned dagger, a
	 * charged or degraded state - all are the same weapon wearing a different
	 * name, and all animate identically. Checked before shipping: of the 13
	 * base-name groups the observed data contains, all 13 agree on their
	 * stance with no exceptions.
	 *
	 * <p>Failing that, the same weapon in a different metal: an Adamant
	 * longsword animates like a Black one. See {@link #tierBaseName(String)}
	 * for why that covers the plain tiers only.
	 *
	 * <p>Deliberately NOT a fuzzy match. Allowing one name to be a subset of
	 * another was measured at 96% - it equates "Dragon axe" with "Dragon
	 * felling axe" - and a wrong animation is worse than a plain one. Both
	 * inheritance steps above are exact matches on what is left after a known
	 * marker is removed, which is why they do not have that failure.
	 */
	public Stance forWeapon(int weaponItemId)
	{
		int id = weaponItemId <= 0 ? UNARMED : weaponItemId;
		Stance stance = stanceOf(id);
		if (stance != null)
		{
			return stance;
		}
		Stance inherited = stanceOf(donorFor(id));
		return inherited != null ? inherited : stanceOf(UNARMED);
	}

	/** The weapon this one inherits its stance from, or 0 when there is none. */
	private int donorFor(int weaponItemId)
	{
		Integer cached = donors.get(weaponItemId);
		if (cached != null)
		{
			return cached;
		}

		if (byBaseName == null || indexedSize != size())
		{
			buildNameIndex();
		}

		int donor = 0;
		String name = models.itemName(weaponItemId);
		String base = baseName(name);
		if (base != null)
		{
			Integer match = byBaseName.get(base);
			if (match != null && match != weaponItemId)
			{
				donor = match;
			}
		}
		// The Deadman cosmetics: "Corrupted" is a prefix, not a bracket, so
		// the marker stripping never reaches it. Only when the rest names a
		// weapon the library knows: the Gauntlet's own "Corrupted sceptre"
		// has no plain form and keeps its name.
		if (donor == 0 && base != null && base.startsWith(CORRUPTED))
		{
			Integer match = byBaseName.get(base.substring(CORRUPTED.length()));
			if (match != null && match != weaponItemId)
			{
				donor = match;
			}
		}
		// Second chance: the same weapon in a different metal.
		if (donor == 0)
		{
			String tierBase = tierBaseName(name);
			if (tierBase != null)
			{
				Integer match = byTierName.get(tierBase);
				if (match != null && match != weaponItemId)
				{
					donor = match;
				}
			}
		}
		donors.put(weaponItemId, donor);
		return donor;
	}

	private void buildNameIndex()
	{
		byBaseName = new HashMap<>();
		byTierName = new HashMap<>();
		for (Integer known : knownIds())
		{
			String name = models.itemName(known);
			String base = baseName(name);
			if (base != null)
			{
				// Lowest id wins: the plain version rather than a variant.
				byBaseName.merge(base, known, Math::min);
			}
			String tierBase = tierBaseName(name);
			if (tierBase != null)
			{
				byTierName.merge(tierBase, known, Math::min);
			}
		}
		indexedSize = size();
		// Names may have arrived since the last resolution failed.
		donors.clear();
	}

	/**
	 * An item name with its metal tier removed, so "Adamant longsword" and
	 * "Black longsword" both become "longsword" - one weapon in two metals,
	 * which animate alike.
	 *
	 * <p>Returns null for anything that does not carry one of these tiers, so
	 * only tiered weapons ever join this index and an untiered weapon can never
	 * be pulled into a group by its noun alone.
	 *
	 * <p>Deliberately excludes dragon, crystal, gilded, 3rd age and the other
	 * ornamental tiers, which the game special-cases. That is measured, not
	 * assumed: across the observed library the plain metal tiers agree on their
	 * stance in all 5 groups that have more than one tier, with no exceptions,
	 * while including the special tiers introduces a real counterexample - the
	 * Dragon longsword stands at 809 where the Black longsword stands at 808.
	 * Admitting them would cover about 65 more weapons at the cost of being
	 * wrong about some of them, and a wrong animation is worse than a plain one.
	 */
	static String tierBaseName(String name)
	{
		String base = baseName(name);
		if (base == null)
		{
			return null;
		}
		java.util.regex.Matcher matcher = TIER_PREFIX.matcher(base);
		if (!matcher.find())
		{
			return null;
		}
		String stripped = base.substring(matcher.end()).trim();
		return stripped.isEmpty() ? null : stripped;
	}

	/** The Deadman cosmetic prefix: "Corrupted dragon claws" are dragon claws. */
	private static final String CORRUPTED = "corrupted ";

	/** Plain metal tiers only - see {@link #tierBaseName(String)}. */
	private static final java.util.regex.Pattern TIER_PREFIX = java.util.regex.Pattern.compile(
		"^(bronze|iron|steel|black|white|mithril|adamant|rune|runite)\\s+");

	/**
	 * An item name with its variant markers removed, so every version of a
	 * weapon collapses to one key: "Abyssal whip (or)", "Dragon dagger(p++)"
	 * and "Dharok's greataxe 25" become "abyssal whip", "dragon dagger" and
	 * "dharok's greataxe".
	 *
	 * <p>Any trailing bracket goes, rather than a list of known markers. The
	 * list was the original approach and it silently rotted: it knew (p) but
	 * not (kp), so every karambwan-poisoned spear and hasta in the game sat
	 * uncovered next to the plain ones it should have inherited from, and the
	 * same held for the Bounty Hunter (bh) weapons, the Gauntlet's basic,
	 * attuned and perfected tiers, the blackjacks' (o) and (d), and charge
	 * counts like "Enchanted lyre(2)". Every marker the game has ever put in
	 * brackets denotes a state of the same weapon, never a different one.
	 *
	 * <p>Measured before adopting: stripping every trailing bracket collapses
	 * the observed library into 16 multi-item groups, and all 16 agree on their
	 * stance with no exceptions. The match stays EXACT on what remains, which
	 * is what keeps "Toxic staff (uncharged)" away from "Toxic staff of the
	 * dead" and why this does not reintroduce the fuzzy matching that was
	 * rejected at 96%.
	 */
	static String baseName(String name)
	{
		if (name == null || name.isEmpty() || "null".equals(name))
		{
			return null;
		}
		String base = name.toLowerCase(java.util.Locale.ROOT).trim();
		// Some items carry two markers, "(bh)(p++)", so strip repeatedly.
		String previous;
		do
		{
			previous = base;
			base = VARIANT_SUFFIX.matcher(base).replaceAll("").trim();
		}
		while (!base.equals(previous));
		base = DEGRADE_SUFFIX.matcher(base).replaceAll("").trim();
		return base.isEmpty() ? null : base;
	}

	/** Any bracketed marker: ornament, trim, poison, charge, tier, lock. */
	private static final java.util.regex.Pattern VARIANT_SUFFIX =
		java.util.regex.Pattern.compile("\\s*\\([^)]*\\)\\s*$");

	/** Trailing wear or charge level: "Dharok's greataxe 75". */
	private static final java.util.regex.Pattern DEGRADE_SUFFIX =
		java.util.regex.Pattern.compile("\\s+\\d+$");

	public boolean knows(int weaponItemId)
	{
		int id = weaponItemId <= 0 ? UNARMED : weaponItemId;
		return known(id) || donorFor(id) > 0;
	}

	public int size()
	{
		int size = bundled.size();
		for (Integer id : learned.keySet())
		{
			if (!bundled.containsKey(id))
			{
				size++;
			}
		}
		return size;
	}
}

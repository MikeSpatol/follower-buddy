package com.follower.follower;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
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
 */
@Slf4j
@Singleton
public class StanceLibrary
{
	public static final String FILE_NAME = "stances.json";

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

	private final Gson gson;
	private final com.follower.appearance.ModelRepository models;

	/** weapon item id -> stance set. */
	private final Map<Integer, Stance> stances = new HashMap<>();

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
		stances.put(UNARMED, new Stance(PlayerPose.IDLE, PlayerPose.WALK, PlayerPose.RUN,
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
					new java.io.InputStreamReader(in, StandardCharsets.UTF_8)));
				log.info("Loaded {} bundled weapon stances", added);
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not read the bundled stance library", e);
		}

		if (!Files.isRegularFile(file))
		{
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			// Local observations win: this client saw them first-hand.
			log.info("Loaded {} learned weapon stances", read(reader));
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Could not read {}", file, e);
		}
	}

	/** @return how many entries were taken from this source. */
	private int read(Reader reader)
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

	public void save()
	{
		if (!dirty || file == null)
		{
			return;
		}

		Map<String, Stance> out = new HashMap<>();
		for (Map.Entry<Integer, Stance> entry : stances.entrySet())
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
		for (Player player : client.getTopLevelWorldView().players())
		{
			if (player == null)
			{
				continue;
			}
			learn(player);
		}
	}

	private void learn(Player player)
	{
		PlayerComposition composition = player.getPlayerComposition();
		if (composition == null)
		{
			return;
		}

		int raw = composition.getEquipmentId(KitType.WEAPON);
		// getEquipmentId returns the item id directly, or -1 when nothing is wielded.
		int weaponId = raw <= 0 ? UNARMED : raw;

		Stance observed = new Stance(
			player.getIdlePoseAnimation(),
			player.getWalkAnimation(),
			player.getRunAnimation(),
			player.getWalkRotate180(),
			player.getWalkRotateLeft(),
			player.getWalkRotateRight(),
			player.getIdleRotateLeft(),
			player.getIdleRotateRight());

		if (!observed.isComplete())
		{
			return;
		}

		Stance known = stances.get(weaponId);
		if (known != null && known.sameAs(observed))
		{
			return;
		}
		if (known != null)
		{
			// Poses changed but the attack did not: carry it across rather
			// than throwing away a hard-won observation.
			observed.attack = known.attack;
		}

		stances.put(weaponId, observed);
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

		Stance stance = stances.get(weaponId);
		if (stance == null || stance.attack == animationId)
		{
			// No pose set yet means the weapon is brand new to us; observe()
			// creates it on the next tick and the attack lands then.
			return;
		}
		stance.attack = animationId;
		dirty = true;
		log.debug("Learned attack animation {} for weapon {}", animationId, weaponId);
	}

	/**
	 * How a weapon is USED, which the pose set does not say.
	 *
	 * <p>Supplied by the plugin from the item's own attack bonuses, because the
	 * pose triple describes how a weapon is CARRIED and nothing more. The
	 * 79-weapon class that walks like an unarmed player holds the Dragon
	 * harpoon and the Bow of faerdhinen side by side: identical carry, utterly
	 * different swing. Borrowing across that would put a chop on a bow.
	 */
	public interface StyleSource
	{
		int MELEE = 0;
		int RANGED = 1;
		int MAGIC = 2;
		int UNKNOWN = -1;

		int styleOf(int itemId);
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
	 * weapons of a kind share a pose set, so seeing ONE scimitar swung covers
	 * every scimitar in the game.
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
		// forWeapon already resolves a trim variant to its plain original.
		Stance stance = forWeapon(weaponItemId);
		if (stance == null)
		{
			return 0;
		}
		if (stance.attack > 0)
		{
			return stance.attack;
		}

		int style = styleSource == null
			? StyleSource.UNKNOWN : styleSource.styleOf(weaponItemId);
		if (style == StyleSource.UNKNOWN)
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
		for (Map.Entry<Integer, Stance> entry : stances.entrySet())
		{
			Stance other = entry.getValue();
			if (other.attack <= 0 || !other.sameClassAs(stance)
				|| styleSource.styleOf(entry.getKey()) != style)
			{
				continue;
			}
			agreement.merge(other.attack, 1, Integer::sum);
			lowestDonor.merge(other.attack, entry.getKey(), Math::min);
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
		Stance stance = stances.get(id);
		if (stance == null)
		{
			stance = new Stance();
			stances.put(id, stance);
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

	/** A weapon's stance exactly as stored, or null when never seen or set. */
	public Stance describe(int weaponItemId)
	{
		return stances.get(weaponItemId <= 0 ? UNARMED : weaponItemId);
	}

	/** Every stance as stored, for auditing what the library actually holds. */
	public Map<Integer, Stance> all()
	{
		return java.util.Collections.unmodifiableMap(stances);
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
		Stance stance = stances.get(id);
		if (stance != null)
		{
			return stance;
		}
		Stance inherited = stances.get(donorFor(id));
		return inherited != null ? inherited : stances.get(UNARMED);
	}

	/** The weapon this one inherits its stance from, or 0 when there is none. */
	private int donorFor(int weaponItemId)
	{
		Integer cached = donors.get(weaponItemId);
		if (cached != null)
		{
			return cached;
		}

		if (byBaseName == null || indexedSize != stances.size())
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
		for (Integer known : stances.keySet())
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
		indexedSize = stances.size();
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

	/** Plain metal tiers only - see {@link #tierBaseName(String)}. */
	private static final java.util.regex.Pattern TIER_PREFIX = java.util.regex.Pattern.compile(
		"^(bronze|iron|steel|black|white|mithril|adamant|rune|runite)\\s+");

	/**
	 * An item name with its variant markers removed, so every version of a
	 * weapon collapses to one key: "Abyssal whip (or)", "Dragon dagger(p++)"
	 * and "Dharok's greataxe 25" become "abyssal whip", "dragon dagger" and
	 * "dharok's greataxe".
	 */
	static String baseName(String name)
	{
		if (name == null || name.isEmpty() || "null".equals(name))
		{
			return null;
		}
		String base = name.toLowerCase(java.util.Locale.ROOT).trim();
		// Some items carry two markers, "(l)(t)", so strip repeatedly.
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

	/** Ornament, trim, poison, charge and lock markers - never the weapon itself. */
	private static final java.util.regex.Pattern VARIANT_SUFFIX = java.util.regex.Pattern.compile(
		"\\s*\\((or|t|g|cr|l|i|e|u|p|p\\+|p\\+\\+|beta|deadman|uncharged|inactive|"
			+ "full|empty|used|charged)\\)\\s*$");

	/** Barrows and crystal wear levels: "Dharok's greataxe 75". */
	private static final java.util.regex.Pattern DEGRADE_SUFFIX =
		java.util.regex.Pattern.compile("\\s+(100|75|50|25|0)$");

	public boolean knows(int weaponItemId)
	{
		int id = weaponItemId <= 0 ? UNARMED : weaponItemId;
		return stances.containsKey(id) || donorFor(id) > 0;
	}

	public int size()
	{
		return stances.size();
	}
}

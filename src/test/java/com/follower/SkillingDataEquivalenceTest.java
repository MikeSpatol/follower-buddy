package com.follower;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The skilling tables moved from Java into skilling.properties: every answer
 * the old hard-coded tables gave, the data gives too. The old tables and
 * lookups are copied here verbatim from 1.5.0 as the oracle; only their names
 * (old...) and their access differ.
 */
public class SkillingDataEquivalenceTest
{
	// ------------------------------------------------------------ the oracle

	/** The option words that start work, matched whole after tag stripping. */
	private static final String[] WORK_OPTIONS = {
		"Chop down", "Mine", "Fish", "Net", "Harpoon", "Cage", "Bait", "Lure",
		// Read off the cache: the same work under other words.
		"Chop", "Chop-down", "Cut", "Cut down", "Cut-Down",
		"Small Net", "Big Net", "Use-rod",
		"Cook", "Smith", "Smelt", "Craft", "Spin", "Weave", "Pray-at", "Craft-rune",
		"Rake", "Plant", "Harvest", "Check-health", "Pick", "Steal-from", "Pickpocket",
		"Build", "Light", "Burn", "Bury", "Lay", "Set-trap", "Catch", "Use",
		// The Wintertodt brazier is fed, not lit, once it is burning.
		"Feed",
	};

	private static final Set<Integer> ONE_SIDED = new HashSet<>(Arrays.asList(
		// Ranges, ovens, a stove and a gnome cooker.
		114, 8750, 16641, 7183, 7184, 9682, 12102, 12611, 25730, 26181, 50563, 54049,
		21792, 27516, 47925, 48169, 27517, 27724, 31631, 59682, 39391, 39458, 39490,
		7421, 52647, 54449, 57332, 57333, 10377, 12269, 17131,
		// Doric's anvil, rocks and a tree or two.
		2031, 25422, 48921, 51485, 51486, 51487, 51488, 51489, 51490, 51491, 51492,
		10048, 25425));

	/** The cold-stamp-feet rule's regions: the Wintertodt camp and the snows. */
	private static final Set<Integer> COLD_REGIONS = new HashSet<>(Arrays.asList(10553, 8253, 6462));

	private static boolean oldIsWorkOption(String option)
	{
		String plain = SkillingController.plainOption(option);
		for (String word : WORK_OPTIONS)
		{
			if (word.equalsIgnoreCase(plain))
			{
				return true;
			}
		}
		return false;
	}

	private static String oldActivityFor(String option)
	{
		switch (SkillingController.plainOption(option).toLowerCase(Locale.ROOT))
		{
			case "chop down":
			case "chop":
			case "chop-down":
			case "cut":
			case "cut down":
			case "cut-down":
				return "woodcutting";
			case "mine":
				return "mining";
			case "fish":
			case "net":
			case "harpoon":
			case "cage":
			case "bait":
			case "lure":
			case "small net":
			case "big net":
			case "use-rod":
				return "fishing";
			case "cook":
				return "cooking";
			case "smith":
			case "smelt":
				return "smithing";
			case "craft":
			case "spin":
			case "weave":
				return "crafting";
			case "pray-at":
			case "bury":
				return "prayer";
			case "craft-rune":
				return "runecraft";
			case "rake":
			case "plant":
			case "harvest":
			case "check-health":
			case "pick":
				return "farming";
			case "steal-from":
			case "pickpocket":
				return "thieving";
			case "build":
				return "construction";
			case "light":
			case "burn":
			case "feed":
				return "firemaking";
			case "lay":
			case "set-trap":
			case "catch":
				return "hunter";
			default:
				return "";
		}
	}

	// Bronze first in each list: it is the fallback when nothing is worn.
	// Every wieldable axe and pickaxe in the cache, ornament and inactive
	// variants included, taken from docs/data/wearable-items.tsv - a short
	// list meant a dragon felling axe borrowed as a bronze one. Refresh them
	// from the dumper when a new tier lands; the combat axes are left out
	// because nothing is felled with a soulreaper.
	private static final int[] AXES = {
		1351, 1349, 1353, 1361, 1355, 1357, 1359,
		6739, 25378, 30352,
		13241, 13242, 25066, 25371, 30347, 30348,
		23673, 23675, 23862,
		20011, 23279, 10491, 23821, 25110,
		28196, 28199, 28202, 28205, 28208, 28211, 28214, 28217, 28220, 28223, 28226,
	};

	private static final int[] PICKAXES = {
		1265, 1267, 1269, 12297, 1273, 1271, 1275,
		11920, 12797, 23677, 25376, 30351,
		13243, 13244, 25063, 25369, 30345, 30346,
		23680, 23682, 23863,
		20014, 23276, 23822, 25112,
		11719, 11720, 11721,
	};

	private static final int[] HARPOONS = {311, 10129, 21028, 21031, 23762, 23864};
	private static final int[] RODS = {307, 309, 11323, 22842};

	// The nets, the pot and the vessel are not equipment, but the cache
	// carries a worn model for each, which is all the hands need. One the
	// hands decline is simply not held.
	private static final int SMALL_NET = 303;
	private static final int BIG_NET = 305;
	private static final int LOBSTER_POT = 301;
	private static final int KARAMBWAN_VESSEL = 3157;

	private static final int ROCK_HAMMER = 4162;
	private static final int SECATEURS = 5329;
	private static final int SAW = 8794;

	/** Only if the cache says it is wearable; {@link #start} finds out. */
	private static final int TORCH = 8987;
	private static final int BUTTERFLY_NET = 10010;

	/**
	 * The farming work, by animation, and what it is done with - 0 for bare
	 * hands, the picking. Read off the cache's names for the animations: a
	 * rake to rake, secateurs to prune, a dibber to plant, a bucket to pour.
	 */
	private static final Map<Integer, Integer> FARMING_TOOLS = farmingTools();

	private static Map<Integer, Integer> farmingTools()
	{
		Map<Integer, Integer> tools = new HashMap<>();
		tools.put(2272, 5325);
		tools.put(11121, 5325);
		tools.put(2273, 5341);
		for (int pruning = 2274; pruning <= 2279; pruning++)
		{
			tools.put(pruning, SECATEURS);
		}
		for (int fairy = 3337; fairy <= 3342; fairy++)
		{
			tools.put(fairy, 7409);
		}
		for (int picking : new int[]{2280, 2281, 2282, 2292})
		{
			tools.put(picking, 0);
		}
		tools.put(2283, 1925);
		tools.put(8197, 22997);
		tools.put(2288, 6036);
		tools.put(2291, 5343);
		tools.put(2293, 5340);
		tools.put(830, 952);
		tools.put(831, 952);
		return tools;
	}

	private static boolean oldFarmingWork(int animationId)
	{
		return FARMING_TOOLS.containsKey(animationId);
	}

	private static int oldFarmingTool(int animationId)
	{
		return FARMING_TOOLS.getOrDefault(animationId, SECATEURS);
	}

	/** The fishing animations, one per tool, from the mime rules' measured list. */
	private static final int HARPOON_ANIMATION = 618;
	private static final int CAGE_ANIMATION = 619;
	private static final int BIG_NET_ANIMATION = 620;
	private static final int NET_ANIMATION = 621;
	private static final int ROD_ANIMATION = 622;
	private static final int FLY_ROD_ANIMATION = 623;
	private static final int KARAMBWAN_ANIMATION = 1193;

	/**
	 * Every harpoon swing and every rod cast the cache names, the tables' own
	 * and the rest, a dragon harpoon's or a pearl rod's. From
	 * docs/data/animation-ids.tsv.
	 */
	private static final Set<Integer> HARPOONING = new HashSet<>(Arrays.asList(
		88, HARPOON_ANIMATION, 5108, 7401, 7402, 8336, 8784));
	private static final Set<Integer> ROD_FISHING = new HashSet<>(Arrays.asList(
		ROD_ANIMATION, FLY_ROD_ANIMATION, 6932, 8188, 8189, 8190, 8191, 8192, 8193, 9350));

	private static int oldToolFor(String activity, int animationId, Set<Integer> worn,
		int wornWeapon, String wornWeaponName)
	{
		if (wornWeapon > 0 && wornWeaponName != null && activity != null)
		{
			String name = wornWeaponName.toLowerCase(Locale.ROOT);
			boolean pickaxe = name.contains("pickaxe");
			boolean fighting = name.contains("battleaxe") || name.contains("greataxe")
				|| name.contains("thrownaxe") || name.contains("poleaxe");
			switch (activity)
			{
				case "woodcutting":
					if (name.endsWith("axe") && !pickaxe && !fighting)
					{
						return wornWeapon;
					}
					break;
				case "mining":
					if (pickaxe)
					{
						return wornWeapon;
					}
					break;
				case "fishing":
					// A harpoon left on through a cage or a net is still called
					// a harpoon, so the name answers only for its own kind of
					// swing, or for one nothing here knows at all. A weapon
					// called a rod, the rod of ivandis, is no fishing rod.
					boolean rod = name.contains("fishing rod") || name.contains("barbarian rod");
					boolean unknown = oldToolFor(activity, animationId, worn) == 0
						&& !HARPOONING.contains(animationId) && !ROD_FISHING.contains(animationId);
					if ((name.contains("harpoon") && (unknown || HARPOONING.contains(animationId)))
						|| (rod && (unknown || ROD_FISHING.contains(animationId))))
					{
						return wornWeapon;
					}
					break;
				default:
					break;
			}
		}
		return oldToolFor(activity, animationId, worn);
	}

	private static int oldToolFor(String activity, int animationId, Set<Integer> worn)
	{
		if (activity == null)
		{
			return 0;
		}
		switch (activity)
		{
			case "woodcutting":
				return wornOr(worn, AXES, AXES[0]);
			case "mining":
				return wornOr(worn, PICKAXES, PICKAXES[0]);
			case "fishing":
				if (animationId == HARPOON_ANIMATION)
				{
					return wornOr(worn, HARPOONS, HARPOONS[0]);
				}
				if (animationId == ROD_ANIMATION || animationId == FLY_ROD_ANIMATION)
				{
					return wornOr(worn, RODS, RODS[0]);
				}
				if (animationId == NET_ANIMATION)
				{
					return SMALL_NET;
				}
				if (animationId == BIG_NET_ANIMATION)
				{
					return BIG_NET;
				}
				if (animationId == CAGE_ANIMATION)
				{
					return LOBSTER_POT;
				}
				if (animationId == KARAMBWAN_ANIMATION)
				{
					return KARAMBWAN_VESSEL;
				}
				return 0;
			case "farming":
				return oldFarmingTool(animationId);
			case "construction":
				return SAW;
			case "firemaking":
				return TORCH;
			case "smithing":
				return ROCK_HAMMER;
			case "hunter":
				return BUTTERFLY_NET;
			default:
				return 0;
		}
	}

	/**
	 * The skilling outfits, each as the hat and then the three pieces that
	 * make it a full set with it: the body, the legs, the boots. Lumberjack,
	 * prospector, angler, farmer's, pyromancer, rogue, carpenter's, graceful.
	 */
	private static final int[][] OUTFITS = {
		{10941, 10939, 10940, 10933},
		{12013, 12014, 12015, 12016},
		{13258, 13259, 13260, 13261},
		{13646, 13642, 13640, 13644},
		{20708, 20704, 20706, 20710},
		{5554, 5553, 5555, 5557},
		{24872, 24874, 24876, 24878},
		{11850, 11854, 11856, 11860},
	};

	/** The work each outfit is for, in the same order; graceful is for none of it. */
	private static final String[] OUTFIT_WORK = {
		"woodcutting", "mining", "fishing", "farming", "firemaking", "thieving",
		"construction", "agility",
	};

	private static int oldHatFor(String activity, Set<Integer> worn)
	{
		if (activity == null || worn == null)
		{
			return 0;
		}
		for (int i = 0; i < OUTFITS.length; i++)
		{
			if (!OUTFIT_WORK[i].equals(activity))
			{
				continue;
			}
			for (int piece : OUTFITS[i])
			{
				if (!worn.contains(piece))
				{
					return 0;
				}
			}
			return OUTFITS[i][0];
		}
		return 0;
	}

	private static int wornOr(Set<Integer> worn, int[] tiers, int fallback)
	{
		if (worn != null)
		{
			for (int id : tiers)
			{
				if (worn.contains(id))
				{
					return id;
				}
			}
		}
		return fallback;
	}

	// ------------------------------------------------------------ the checks

	private static final String[] ACTIVITIES = {
		"woodcutting", "mining", "fishing", "farming", "construction", "firemaking", "smithing",
		"hunter", "cooking", "crafting", "prayer", "thieving", "runecraft", "herblore",
		"fletching", "agility", "magic", "slayer", "attack", "Woodcutting", "", null,
	};

	@Test
	public void theOptionWordsAnswerAsTheyDid()
	{
		List<String> asked = new ArrayList<>();
		for (String word : WORK_OPTIONS)
		{
			asked.add(word);
			asked.add(word.toLowerCase(Locale.ROOT));
			asked.add(word.toUpperCase(Locale.ROOT));
			asked.add("<col=ffffff>" + word + "</col>");
			asked.add(" " + word + " ");
			asked.add(word + "x");
			asked.add(word.replace('i', '\u0131').replace('k', '\u212a').replace('K', '\u212a'));
			asked.add(word.replace('I', '\u0130').replace("s", "\u017f"));
		}
		asked.addAll(Arrays.asList(null, "", "Walk here", "Examine", "Drop", "Attack", "Talk-to",
			"Use-quickly", "Chop down tree", "Net ", "Bank", "Deposit", "Take", "Chop  down", "Cut_down"));
		for (String option : asked)
		{
			assertEquals(option, oldIsWorkOption(option), SkillingController.isWorkOption(option));
			assertEquals(option, oldActivityFor(option), SkillingController.activityFor(option));
		}
	}

	@Test
	public void theSetsAreTheSame()
	{
		assertEquals(ONE_SIDED, SkillingController.idSet("oneSided"));
		assertEquals(COLD_REGIONS, SkillingController.COLD_REGIONS);
		assertEquals(HARPOONING, SkillingController.idSet("harpooning"));
		assertEquals(ROD_FISHING, SkillingController.idSet("rodFishing"));
	}

	@Test
	public void everyToolAndHatAnswersAsItDid()
	{
		Set<Integer> animations = new HashSet<>(Arrays.asList(-1, 0, 1, 618, 619, 620, 621, 622,
			623, 1193, 88, 5108, 7401, 7402, 8336, 8784, 6932, 8188, 8189, 8190, 8191, 8192, 8193,
			9350, 829, 99999));
		animations.addAll(FARMING_TOOLS.keySet());
		List<Set<Integer>> worns = new ArrayList<>();
		worns.add(null);
		worns.add(new HashSet<>());
		Set<Integer> everyTool = new HashSet<>();
		for (int[] tiers : new int[][]{AXES, PICKAXES, HARPOONS, RODS})
		{
			for (int id : tiers)
			{
				worns.add(new HashSet<>(Arrays.asList(id)));
				everyTool.add(id);
			}
		}
		worns.add(everyTool);
		worns.add(new HashSet<>(Arrays.asList(1359, 1275, 23864, 22842)));
		for (int[] outfit : OUTFITS)
		{
			Set<Integer> full = new HashSet<>();
			for (int piece : outfit)
			{
				full.add(piece);
			}
			worns.add(full);
			Set<Integer> partial = new HashSet<>(full);
			partial.remove(outfit[3]);
			worns.add(partial);
			Set<Integer> withAxe = new HashSet<>(full);
			withAxe.add(1359);
			worns.add(withAxe);
		}
		String[][] weapons = {
			{"0", null}, {"1359", "Rune axe"}, {"1275", "Rune pickaxe"}, {"21028", "Dragon harpoon"},
			{"7639", "Rod of ivandis (10)"}, {"307", "Fishing rod"}, {"11323", "Barbarian rod"},
			{"1377", "Dragon battleaxe"}, {"4151", "Abyssal whip"}, {"311", "Harpoon"},
			{"1359", null}, {"-1", "Rune axe"},
		};
		int compared = 0;
		for (String activity : ACTIVITIES)
		{
			for (Set<Integer> worn : worns)
			{
				assertEquals(activity, oldHatFor(activity, worn), Borrow.hatFor(activity, worn));
				for (int animation : animations)
				{
					assertEquals(activity + "/" + animation + "/" + worn,
						oldToolFor(activity, animation, worn), Borrow.toolFor(activity, animation, worn));
					for (String[] weapon : weapons)
					{
						int id = Integer.parseInt(weapon[0]);
						assertEquals(activity + "/" + animation + "/" + weapon[1],
							oldToolFor(activity, animation, worn, id, weapon[1]),
							Borrow.toolFor(activity, animation, worn, id, weapon[1]));
						compared++;
					}
				}
			}
		}
		assertTrue(compared > 1_000_000);
	}

	@Test
	public void everyAnimationPicksTheSameTool()
	{
		List<Set<Integer>> worns = Arrays.asList(null, new HashSet<>(),
			new HashSet<>(Arrays.asList(1359, 1275, 23864, 22842)), new HashSet<>(Arrays.asList(30352, 11721)));
		for (int animation = -1; animation <= 12_000; animation++)
		{
			assertEquals(oldFarmingWork(animation), Borrow.farmingWork(animation));
			assertEquals(oldFarmingTool(animation), Borrow.farmingTool(animation));
			for (String activity : ACTIVITIES)
			{
				for (Set<Integer> worn : worns)
				{
					assertEquals(activity + "/" + animation, oldToolFor(activity, animation, worn),
						Borrow.toolFor(activity, animation, worn));
				}
			}
		}
	}
}

package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.Condition;
import com.follower.speech.SpeechRule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;

/**
 * Rules that load cleanly and can still never fire.
 *
 * <p>The loader only rejects a rule with no {@code when} block or nothing to
 * say. Everything past that is accepted and then quietly fails at runtime: a
 * condition whose list of things to look for is empty matches nothing, a regex
 * that will not compile throws on first evaluation and takes its rule out of
 * service, and a level-up rule naming a skill that does not exist waits for an
 * event that never comes.
 *
 * <p>None of those announce themselves. They look exactly like a trigger that
 * has not happened yet, which is the same trap that hid "Gypsy Aris" - a name
 * the wiki uses and the game does not.
 */
public class RuleTriggerSanityTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private List<SpeechRule> shippedRules() throws IOException
	{
		return new Harness(folder.newFolder().toPath()).loader.getRules();
	}

	/** Walks a condition tree, handing every node to the caller. */
	private static void walk(Condition condition, java.util.function.Consumer<Condition> visit)
	{
		if (condition == null)
		{
			return;
		}
		visit.accept(condition);
		if (condition.conditions != null)
		{
			for (Condition child : condition.conditions)
			{
				walk(child, visit);
			}
		}
	}

	private static String type(Condition condition)
	{
		return condition.type == null ? "" : condition.type.toLowerCase(Locale.ROOT);
	}

	// ------------------------------------------------------- dead conditions

	@Test
	public void noConditionLooksForSomethingWithoutSayingWhat() throws IOException
	{
		// These types match by a list. With the list absent or empty they can
		// never be true, and the rule is dead on arrival.
		Set<String> needsNames = new HashSet<>(Arrays.asList(
			"npcspawn", "npcdespawn", "npcnearby"));
		Set<String> needsIds = new HashSet<>(Arrays.asList(
			"itemequipped", "animationself"));

		List<String> dead = new ArrayList<>();
		for (SpeechRule rule : shippedRules())
		{
			walk(rule.when, condition ->
			{
				String type = type(condition);
				boolean noNames = condition.names == null || condition.names.isEmpty();
				boolean noIds = condition.ids == null || condition.ids.isEmpty();

				// npcNearby is the exception: a combat-level bracket on its own
				// is a complete test, and a deliberate one - it matches every
				// large thing in the game, including the ones added after this
				// was written, which no list of names can manage.
				boolean bracketed = "npcnearby".equals(type)
					&& (condition.minimum != null || condition.maximum != null);

				if (needsNames.contains(type) && noNames && noIds && !bracketed)
				{
					dead.add(rule.id + ": " + type + " with neither names nor ids");
				}
				if (needsIds.contains(type) && noIds)
				{
					dead.add(rule.id + ": " + type + " with no ids");
				}
				if (type.equals("inregion")
					&& (condition.regions == null || condition.regions.isEmpty()))
				{
					dead.add(rule.id + ": inRegion with no regions");
				}
				if (type.equals("regionenter")
					&& (condition.regions == null || condition.regions.isEmpty()))
				{
					dead.add(rule.id + ": regionEnter with no regions");
				}
				if (type.equals("varbitequals") && condition.varbit == null)
				{
					dead.add(rule.id + ": varbitEquals with no varbit");
				}
				if (type.equals("varbitchanged") && condition.varbit == null)
				{
					dead.add(rule.id + ": varbitChanged with no varbit");
				}
				if (type.equals("inarea")
					&& (condition.x1 == null || condition.y1 == null
					|| condition.x2 == null || condition.y2 == null))
				{
					dead.add(rule.id + ": inArea missing a corner");
				}
				if (type.equals("chatmessage")
					&& condition.contains == null && condition.regex == null && noNames)
				{
					dead.add(rule.id + ": chatMessage with nothing to match");
				}
			});
		}

		assertTrue("rules that load fine and can never fire:\n  "
			+ String.join("\n  ", dead), dead.isEmpty());
	}

	@Test
	public void noNameToMatchIsBlank() throws IOException
	{
		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : shippedRules())
		{
			walk(rule.when, condition ->
			{
				if (condition.names == null)
				{
					return;
				}
				for (String name : condition.names)
				{
					if (name == null || name.trim().isEmpty())
					{
						problems.add(rule.id + ": a blank name in " + type(condition));
					}
					else if (!name.equals(name.trim()))
					{
						// Matching trims the candidate, not the pattern, so a
						// stray space here never matches anything.
						problems.add(rule.id + ": \"" + name + "\" has padding");
					}
				}
			});
		}
		assertTrue(String.join("\n  ", problems), problems.isEmpty());
	}

	// -------------------------------------------------------------- patterns

	@Test
	public void everyRegexCompiles() throws IOException
	{
		// A regex that will not compile throws from inside matches(), and the
		// engine's response is to disable that rule for the session.
		List<String> broken = new ArrayList<>();
		for (SpeechRule rule : shippedRules())
		{
			walk(rule.when, condition ->
			{
				if (condition.regex == null)
				{
					return;
				}
				try
				{
					Pattern.compile(condition.regex, Pattern.CASE_INSENSITIVE);
				}
				catch (PatternSyntaxException e)
				{
					broken.add(rule.id + ": " + condition.regex + " - " + e.getDescription());
				}
			});
		}
		assertTrue("regexes that would disable their own rule:\n  "
			+ String.join("\n  ", broken), broken.isEmpty());
	}

	@Test
	public void everyWildcardNameCompiles() throws IOException
	{
		// Names become patterns too, by quoting the literals around each *.
		List<String> broken = new ArrayList<>();
		for (SpeechRule rule : shippedRules())
		{
			walk(rule.when, condition ->
			{
				if (condition.names == null)
				{
					return;
				}
				for (String name : condition.names)
				{
					StringBuilder pattern = new StringBuilder();
					for (String literal : name.split("\\*", -1))
					{
						if (pattern.length() > 0)
						{
							pattern.append(".*");
						}
						pattern.append(Pattern.quote(literal));
					}
					try
					{
						Pattern.compile(pattern.toString(), Pattern.CASE_INSENSITIVE);
					}
					catch (PatternSyntaxException e)
					{
						broken.add(rule.id + ": " + name);
					}
				}
			});
		}
		assertTrue(String.join("\n  ", broken), broken.isEmpty());
	}

	// ---------------------------------------------------------------- values

	@Test
	public void everyLevelUpNamesARealSkill() throws IOException
	{
		Set<String> skills = new HashSet<>();
		for (Skill skill : Skill.values())
		{
			skills.add(skill.getName().toLowerCase(Locale.ROOT));
		}

		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : shippedRules())
		{
			walk(rule.when, condition ->
			{
				if (!type(condition).equals("levelup") || condition.names == null)
				{
					return;
				}
				for (String name : condition.names)
				{
					String plain = name.replace("*", "").trim().toLowerCase(Locale.ROOT);
					if (!plain.isEmpty() && !skills.contains(plain))
					{
						problems.add(rule.id + ": \"" + name + "\" is not a skill");
					}
				}
			});
		}
		assertTrue("level-up rules waiting on a skill that does not exist:\n  "
			+ String.join("\n  ", problems), problems.isEmpty());
	}

	@Test
	public void percentagesAreInRange() throws IOException
	{
		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : shippedRules())
		{
			walk(rule.when, condition ->
			{
				if (condition.percent == null)
				{
					return;
				}
				int percent = condition.percent;
				if (percent < 0 || percent > 100)
				{
					problems.add(rule.id + ": " + type(condition) + " at " + percent + "%");
				}
			});
		}
		assertTrue("a percentage outside 0-100 either never fires or always"
			+ " does:\n  " + String.join("\n  ", problems), problems.isEmpty());
	}

	@Test
	public void distancesAndCountsArePositive() throws IOException
	{
		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : shippedRules())
		{
			walk(rule.when, condition ->
			{
				if (condition.within != null && condition.within <= 0)
				{
					problems.add(rule.id + ": within " + condition.within);
				}
				if (condition.ticks != null && condition.ticks < 0)
				{
					problems.add(rule.id + ": ticks " + condition.ticks);
				}
				if (condition.minimum != null && condition.maximum != null
					&& condition.maximum < condition.minimum)
				{
					problems.add(rule.id + ": minimum " + condition.minimum
						+ " above maximum " + condition.maximum);
				}
			});
		}
		assertTrue(String.join("\n  ", problems), problems.isEmpty());
	}

	@Test
	public void cooldownsAndPrioritiesAreSane() throws IOException
	{
		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : shippedRules())
		{
			if (rule.cooldownMs < 0)
			{
				problems.add(rule.id + ": negative cooldown");
			}
			// A cooldown longer than a very long session means one firing ever.
			if (rule.cooldownMs > 6L * 60 * 60 * 1000)
			{
				problems.add(rule.id + ": cooldown of " + rule.cooldownMs + "ms");
			}
			if (rule.delayTicks != null && rule.delayTicks < 0)
			{
				problems.add(rule.id + ": negative delay");
			}
		}
		assertTrue(String.join("\n  ", problems), problems.isEmpty());
	}

	@Test
	public void aNearbyRadiusIsWithinWhatTheSceneCanSee() throws IOException
	{
		// The scene is 104 tiles; a radius past that is asking about NPCs the
		// client cannot see, so the rule quietly behaves as a smaller one.
		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : shippedRules())
		{
			walk(rule.when, condition ->
			{
				String type = type(condition);
				if ((type.equals("npcnearby") || type.equals("petnearby"))
					&& condition.within != null && condition.within > 52)
				{
					problems.add(rule.id + ": within " + condition.within);
				}
			});
		}
		assertTrue(String.join("\n  ", problems), problems.isEmpty());
	}

	// ------------------------------------------------------------- coherence

	@Test
	public void everyRuleCanBeReachedByAtLeastOneEventType() throws IOException
	{
		// A rule combining two conditions that need DIFFERENT events can never
		// have both true at once, so it never fires. all[login, playerDeath]
		// is the shape to catch.
		Set<String> exclusive = new HashSet<>(Arrays.asList(
			"login", "playerdeath", "npcspawn", "npcdespawn", "levelup",
			"chatmessage", "animationself", "varbitchanged", "lootworth",
			"npckill", "combatstart", "combatend", "regionenter", "returnvisit",
			"damagetaken", "thrallstart", "thrallswitch", "thrallend",
			"errandstart", "errandend"));

		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : shippedRules())
		{
			if (rule.when == null || !"all".equalsIgnoreCase(rule.when.type)
				|| rule.when.conditions == null)
			{
				continue;
			}
			Set<String> events = new HashSet<>();
			for (Condition child : rule.when.conditions)
			{
				String type = type(child);
				if (exclusive.contains(type))
				{
					events.add(type);
				}
			}
			if (events.size() > 1)
			{
				problems.add(rule.id + ": needs " + events + " true at the same moment");
			}
		}
		assertTrue("rules waiting for two different events at once:\n  "
			+ String.join("\n  ", problems), problems.isEmpty());
	}

	@Test
	public void everyRuleThatMentionsAnNpcInItsLinesCanNameOne() throws IOException
	{
		// Belt and braces for the placeholder check: a rule saying {npc} but
		// triggering on something that carries no npc prints the braces.
		List<String> problems = new ArrayList<>();
		for (SpeechRule rule : shippedRules())
		{
			if (rule.say == null)
			{
				continue;
			}
			boolean mentions = false;
			for (String line : rule.say)
			{
				mentions |= line.contains("{npc}");
			}
			if (mentions && rule.when != null
				&& !(rule.when.usesType("npcSpawn") || rule.when.usesType("npcDespawn")
				|| rule.when.usesType("npcKill") || rule.when.usesType("combatStart")
				|| rule.when.usesType("combatEnd")))
			{
				problems.add(rule.id);
			}
		}
		assertTrue(String.join("\n  ", problems), problems.isEmpty());
	}
}

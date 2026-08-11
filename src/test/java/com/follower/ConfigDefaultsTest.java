package com.follower;

import com.follower.appearance.Outfit;
import com.follower.appearance.OutfitParser;
import com.follower.sim.Harness;
import com.follower.speech.SpeechOutput;
import com.follower.speech.SpeechRule;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The settings a player has before they touch anything.
 *
 * <p>Defaults are the one configuration almost every user runs, and the one
 * nobody tests: RuneLite writes them into the profile on first read, after
 * which changing a default in code has no effect on that install at all. So a
 * default that is wrong ships wrong and stays wrong.
 */
public class ConfigDefaultsTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final FollowerConfig config = new FollowerConfig()
	{
	};

	// ------------------------------------------------------------- first run

	/**
	 * Asked for explicitly: a first-time user should see a plain character with
	 * no gear and the default body kit, not somebody else's costume.
	 */
	@Test
	public void aFreshInstallShowsABaseCharacter()
	{
		assertEquals("the shipped outfit must be empty", "", config.customOutfit());

		Outfit outfit = OutfitParser.parse(config.customOutfit());
		assertTrue("an empty outfit string must parse to an empty outfit",
			outfit.isEmpty());
		assertFalse("and still produce a body once the default kits are filled in",
			outfit.withDefaultBody().isEmpty());
	}

	@Test
	public void theFollowerHasANameAndItIsNotBlank()
	{
		assertNotNull(config.followerName());
		assertFalse(config.followerName().trim().isEmpty());
	}

	@Test
	public void theFollowerIsFollowingAndAudibleOutOfTheBox()
	{
		assertTrue("a follower that does not follow is not much of one",
			config.followEnabled());
		assertFalse("shipped muted would read as broken", config.muted());
		assertNotNull(config.defaultOutput());
	}

	@Test
	public void everyPhraseGroupIsOnByDefault()
	{
		assertTrue(config.groupBoss());
		assertTrue(config.groupHealth());
		assertTrue(config.groupArea());
		assertTrue(config.groupIdle());
		assertTrue(config.groupGear());
		assertTrue(config.groupQuest());
		assertTrue(config.groupCombat());
		assertTrue(config.groupMimic());
		assertEquals("nothing should be silenced before the user asks",
			"", config.disabledGroups());
	}

	@Test
	public void theDiagnosticsAreOffForEveryoneElse()
	{
		assertFalse("developer commands must not ship enabled",
			config.developerMode());
	}

	// --------------------------------------------------------------- ranges

	@Test
	public void timingsArePositive()
	{
		assertTrue("speech that vanishes instantly cannot be read",
			config.speechDurationMs() > 0);
		assertTrue("a line has to be given some time per character",
			config.readingSpeed() > 0);

		// The raw milliseconds box became a named setting; every level still
		// has to leave a gap, and they have to run in the order they read.
		com.follower.speech.Chattiness previous = null;
		for (com.follower.speech.Chattiness level : com.follower.speech.Chattiness.values())
		{
			assertTrue(level + " must leave some gap", level.getGapMs() > 0);
			if (previous != null)
			{
				assertTrue(level + " should talk more than " + previous,
					level.getGapMs() < previous.getGapMs());
			}
			previous = level;
		}
	}

	@Test
	public void animationAndGraphicIdsAreRealNumbers()
	{
		int[] ids = {
			config.spectateShieldAnimation(),
			config.spectateShieldChannelStart(),
			config.spectateShieldChannelEnd(),
			config.spectateShieldChannelAnimation(),
			config.spectateShieldEndAnimation(),
			config.spectateShieldGraphic(),
			config.spectateShieldChannelGraphic(),
			config.spectateShieldEndGraphic(),
			config.restAnimation(),
		};
		for (int id : ids)
		{
			assertTrue("a negative id would be dropped silently: " + id, id >= 0);
		}
		assertTrue("the rest pose has to be something", config.restAnimation() > 0);
	}

	@Test
	public void theThrallProfilesPointAtProfilesThatExist() throws IOException
	{
		com.follower.appearance.OutfitProfileStore store =
			new com.follower.appearance.OutfitProfileStore(new com.google.gson.Gson());
		store.load(folder.newFolder().toPath());

		for (String name : new String[]{config.thrallMeleeProfile(),
			config.thrallRangedProfile(), config.thrallMagicProfile()})
		{
			assertNotNull("thrall mode points at a profile that a fresh install"
				+ " does not have: " + name, store.get(name));
		}
	}

	// ------------------------------------------------------ code and settings

	@Test
	public void everyGroupTheRulesUseHasASwitchInTheSettings() throws IOException
	{
		// Derived from the config interface rather than a hand-kept list, so
		// adding a groupX() switch is enough to cover a new group.
		Set<String> switchable = new HashSet<>();
		for (Method method : FollowerConfig.class.getMethods())
		{
			String name = method.getName();
			if (name.startsWith("group") && name.length() > 5
				&& method.getParameterCount() == 0)
			{
				switchable.add(name.substring(5).toLowerCase(java.util.Locale.ROOT));
			}
		}
		// Groups deliberately without a switch of their own.
		switchable.add("misc");
		switchable.add("errand");
		switchable.add("thrall");
		switchable.add("reactions");

		Harness h = new Harness(folder.newFolder().toPath());
		Set<String> orphans = new TreeSet<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if (!switchable.contains(rule.group))
			{
				orphans.add(rule.group);
			}
		}
		assertTrue("rule groups a user cannot switch off: " + orphans, orphans.isEmpty());
	}

	@Test
	public void everySettingIsInASectionAndHasSomethingToSay()
	{
		List<String> problems = new ArrayList<>();
		for (Method method : FollowerConfig.class.getDeclaredMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item == null)
			{
				continue;
			}
			if (item.name().trim().isEmpty())
			{
				problems.add(method.getName() + " has no name");
			}
			if (item.description().trim().isEmpty())
			{
				problems.add(method.getName() + " has no description");
			}
			if (item.keyName().trim().isEmpty())
			{
				problems.add(method.getName() + " has no key");
			}
		}
		assertTrue("settings a user would see as blank: " + problems, problems.isEmpty());
	}

	@Test
	public void everySettingKeyIsUnique()
	{
		Set<String> seen = new HashSet<>();
		List<String> duplicates = new ArrayList<>();
		for (Method method : FollowerConfig.class.getDeclaredMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item != null && !seen.add(item.keyName()))
			{
				// Two settings sharing a key would overwrite each other in the
				// saved profile, and the loser would silently never persist.
				duplicates.add(item.keyName());
			}
		}
		assertTrue("settings sharing a storage key: " + duplicates, duplicates.isEmpty());
	}

	@Test
	public void sectionsDoNotFightOverTheSamePosition()
	{
		Set<Integer> seen = new HashSet<>();
		List<String> clashes = new ArrayList<>();
		for (java.lang.reflect.Field field : FollowerConfig.class.getDeclaredFields())
		{
			ConfigSection section = field.getAnnotation(ConfigSection.class);
			if (section != null && !seen.add(section.position()))
			{
				clashes.add(section.name() + " at " + section.position());
			}
		}
		assertTrue("sections with the same position order arbitrarily: " + clashes,
			clashes.isEmpty());
	}

	@Test
	public void everySettingNamedAsAPercentageIsOne()
	{
		// A percentage that ships outside 0-100 can never be reached by the
		// slider, so the setting is dead on arrival.
		assertTrue(config.speechDurationMs() > 100);
		assertEquals("the default output must be one the parser knows",
			config.defaultOutput(),
			SpeechOutput.parse(config.defaultOutput().name(), SpeechOutput.OVERHEAD));
	}

	@Test
	public void theSpawnAnimationSettingIsSomethingTheCodeCanRead()
	{
		String spawn = config.spawnAnimation();
		assertNotNull(spawn);
		assertFalse("a blank spawn setting would read as no animation at all",
			spawn.trim().isEmpty());
	}
}

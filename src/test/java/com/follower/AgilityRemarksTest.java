// public:file agility,agility-all
package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The follower's remarks on agility it copied: a clean crossing is an odd
 * remark, not one per obstacle, and a fall gets one line, not two.
 */
public class AgilityRemarksTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@SuppressWarnings("unchecked")
	private static Set<String> silenced(boolean copiesAgility) throws ReflectiveOperationException
	{
		FollowerPlugin plugin = new FollowerPlugin();
		Field config = FollowerPlugin.class.getDeclaredField("config");
		config.setAccessible(true);
		config.set(plugin, new FollowerConfig()
		{
			@Override
			public boolean copiesAgility()
			{
				return copiesAgility;
			}
		});
		Method collect = FollowerPlugin.class.getDeclaredMethod("collectDisabledGroups");
		collect.setAccessible(true);
		return (Set<String>) collect.invoke(plugin);
	}

	@Test
	public void theSlipStandsAsideOnlyWhileTheFollowerCopiesTheFall() throws Exception
	{
		assertTrue("copying, the fall is the follower's line", silenced(true).contains("fail-slip"));
		assertFalse("not copying, the slip keeps its line", silenced(false).contains("fail-slip"));
	}

	@Test
	public void aCleanCrossingIsAnOddRemarkAndAFallIsSaid() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		SpeechRule crossed = h.rule("agility-crossed");
		assertNotNull(crossed);
		assertTrue("a cooldown of minutes, not one line a lap", crossed.cooldownMs >= 300_000);
		assertTrue("and a roll", crossed.when.usesType("chance"));

		SpeechRule fell = h.rule("agility-fell");
		assertTrue("a fall too: minutes between, not one a fall", fell.cooldownMs >= 300_000);
		assertTrue("and a roll", fell.when.usesType("chance"));

		// Falls until one passes its roll: about one in three.
		h.gameTicks(2);
		for (int i = 0; i < 40 && h.firedBy("agility-fell").isEmpty(); i++)
		{
			h.dispatch(TriggerEvent.crossed(true));
			h.gameTicks(5);
		}
		assertEquals("a copied fall is said", 1, h.firedBy("agility-fell").size());
		assertEquals("and only that", 0, h.firedBy("agility-crossed").size());
	}
}

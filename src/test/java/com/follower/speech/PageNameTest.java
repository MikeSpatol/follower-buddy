// public:file chronicle
package com.follower.speech;

import com.follower.chronicle.ChronicleEntry;
import com.follower.sim.Harness;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * How a page's name reads in its news. Quests QA5 #56: "Starting a page on
 * The Abyss.", with the capital kept mid-sentence, and "Ruins of Camdozaal
 * has its own page now.", with no article.
 */
public class PageNameTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void aPagesOwnTheIsLowerCaseMidSentence() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		int filled = 0;
		for (SpeechRule rule : h.loader.getRules())
		{
			if (!rule.id.startsWith("chronicle-page-"))
			{
				continue;
			}
			for (String template : rule.say)
			{
				if (!template.contains("{page}"))
				{
					continue;
				}
				String said = h.engine.spokenText(template, TriggerEvent.pageWritten("location", "The Abyss"));
				filled++;
				assertFalse(said, said.matches(".+The Abyss.*"));
				assertTrue(said, said.contains(template.startsWith("{page}") ? "The Abyss" : "the Abyss"));
			}
		}
		assertTrue("the page news was filled", filled > 10);
	}

	@Test
	public void aSpokenNameTakesItsArticleAndItsCapitalAtTheStart() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);
		ChronicleEntry tower = new ChronicleEntry();
		tower.name = "Wizards' Tower";
		assertEquals("left out, the name as it is", "Wizards' Tower", tower.spokenName());
		tower.spoken = "the Wizards' Tower";
		TriggerEvent news = TriggerEvent.pageWritten("location", tower.spokenName());
		assertEquals("Starting a page on the Wizards' Tower.",
			h.engine.spokenText("Starting a page on {page}.", news));
		assertEquals("The Wizards' Tower has a page now.",
			h.engine.spokenText("{page} has a page now.", news));
		String plugin = new String(java.nio.file.Files.readAllBytes(
			java.nio.file.Paths.get("src/main/java/com/follower/FollowerPlugin.java")), java.nio.charset.StandardCharsets.UTF_8);
		assertTrue("the plugin's page news says the spoken name",
			plugin.contains("TriggerEvent.pageWritten(byQuest ? \"quest\" : entry.kindOrEmpty(), entry.spokenName())"));
	}
}

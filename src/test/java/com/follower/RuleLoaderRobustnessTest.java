package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.RuleLoader;
import com.follower.speech.SpeechRule;
import com.follower.speech.TriggerEvent;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the loader does with a file a person edited by hand.
 *
 * <p>phrases.json is meant to be opened in a text editor, so every kind of
 * mistake will eventually be made in it. None of them may throw, and none may
 * leave the follower mute when the previous rules were fine - losing a whole
 * personality to a trailing comma is worse than running slightly stale rules.
 */
public class RuleLoaderRobustnessTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private Path dir() throws IOException
	{
		return folder.newFolder().toPath();
	}

	private RuleLoader loadedWith(Path dir, String contents) throws IOException
	{
		Files.write(dir.resolve(RuleLoader.FILE_NAME),
			contents.getBytes(StandardCharsets.UTF_8));
		RuleLoader loader = new RuleLoader(new Gson());
		loader.initialise(dir);
		return loader;
	}

	// ------------------------------------------------------------- structure

	@Test
	public void aMissingFileIsWrittenFromTheBundledDefaults() throws IOException
	{
		Path dir = dir();
		RuleLoader loader = new RuleLoader(new Gson());
		loader.initialise(dir);

		assertTrue("no starter file was written",
			Files.isRegularFile(dir.resolve(RuleLoader.FILE_NAME)));
		assertFalse("the starter file loaded no rules", loader.getRules().isEmpty());
	}

	@Test
	public void malformedFilesNeverThrow() throws IOException
	{
		String[] nonsense = {
			"",
			"   ",
			"{",
			"[]",
			"null",
			"\"just a string\"",
			"{\"version\": 1}",
			"{\"version\": 1, \"rules\": null}",
			"{\"version\": 1, \"rules\": []}",
			"{\"version\": 1, \"rules\": [null, null]}",
			"{\"version\": 1, \"rules\": [{}]}",
			"{\"version\": 1, \"rules\": [{\"id\": \"x\"}]}",
			"{\"version\": 99, \"rules\": []}",
			"{\"rules\": [{\"id\": \"x\", \"when\": {\"type\": \"always\"}, \"say\": [\"a\"]}]}",
			// A trailing comma: the classic hand-edit slip.
			"{\"version\": 1, \"rules\": [{\"id\": \"x\", \"when\": {\"type\": \"always\"},"
				+ " \"say\": [\"a\"]},]}",
			"not json at all",
			"﻿{\"version\": 1, \"rules\": []}",
		};

		for (String contents : nonsense)
		{
			Path dir = dir();
			RuleLoader loader = loadedWith(dir, contents);
			// The contract is only that it survives and says something useful.
			assertTrue("no status for " + summarise(contents),
				loader.getStatus() != null && !loader.getStatus().isEmpty());
			loader.reload();
			loader.reloadIfChanged();
		}
	}

	private static String summarise(String contents)
	{
		String flat = contents.replace('\n', ' ');
		return flat.length() > 40 ? flat.substring(0, 40) + "..." : "'" + flat + "'";
	}

	@Test
	public void aSyntaxErrorKeepsTheRulesThatWereAlreadyWorking() throws IOException
	{
		Path dir = dir();
		RuleLoader loader = loadedWith(dir,
			"{\"version\": 1, \"rules\": [{\"id\": \"good\", \"group\": \"t\","
				+ " \"when\": {\"type\": \"always\"}, \"say\": [\"a\"]}]}");
		assertEquals(1, loader.getRules().size());

		Files.write(dir.resolve(RuleLoader.FILE_NAME),
			"{ oh dear".getBytes(StandardCharsets.UTF_8));
		loader.reload();

		assertEquals("a broken save must not silence the follower",
			1, loader.getRules().size());
		assertFalse("and it must say why", loader.getErrors().isEmpty());
	}

	@Test
	public void aBrokenSaveIsRetriedRatherThanRememberedAsCurrent() throws IOException
	{
		Path dir = dir();
		Path file = dir.resolve(RuleLoader.FILE_NAME);
		RuleLoader loader = loadedWith(dir,
			"{\"version\": 1, \"rules\": [{\"id\": \"good\", \"group\": \"t\","
				+ " \"when\": {\"type\": \"always\"}, \"say\": [\"a\"]}]}");

		Files.write(file, "{ broken".getBytes(StandardCharsets.UTF_8));
		assertTrue("the change should be noticed", loader.reloadIfChanged());
		assertEquals(1, loader.getRules().size());

		// Fixed in the editor. The loader must not think it has already read
		// this file - it recorded no successful read of the broken one.
		Files.write(file,
			("{\"version\": 1, \"rules\": [{\"id\": \"a\", \"group\": \"t\","
				+ " \"when\": {\"type\": \"always\"}, \"say\": [\"x\"]},"
				+ "{\"id\": \"b\", \"group\": \"t\","
				+ " \"when\": {\"type\": \"always\"}, \"say\": [\"y\"]}]}")
				.getBytes(StandardCharsets.UTF_8));
		assertTrue(loader.reloadIfChanged());
		assertEquals("the repaired file should load", 2, loader.getRules().size());
	}

	// ------------------------------------------------------------- validation

	@Test
	public void invalidRulesAreDroppedAndTheRestSurvive() throws IOException
	{
		RuleLoader loader = loadedWith(dir(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"nowhen\", \"say\": [\"a\"]},"
				+ "{\"id\": \"nothing\", \"when\": {\"type\": \"always\"}},"
				+ "{\"id\": \"empty-say\", \"when\": {\"type\": \"always\"}, \"say\": []},"
				+ "{\"id\": \"good\", \"when\": {\"type\": \"always\"}, \"say\": [\"a\"]}"
				+ "]}");

		assertEquals("only the usable rule should survive", 1, loader.getRules().size());
		assertEquals("good", loader.getRules().get(0).id);
		assertEquals("each dropped rule should be reported", 3, loader.getErrors().size());
	}

	@Test
	public void aRuleWithNoIdGetsOneRatherThanColliding() throws IOException
	{
		RuleLoader loader = loadedWith(dir(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"when\": {\"type\": \"always\"}, \"say\": [\"a\"]},"
				+ "{\"when\": {\"type\": \"always\"}, \"say\": [\"b\"]}"
				+ "]}");

		assertEquals(2, loader.getRules().size());
		assertFalse("two unnamed rules must not share an id",
			loader.getRules().get(0).id.equals(loader.getRules().get(1).id));
	}

	@Test
	public void aRuleWithNoGroupLandsInMisc() throws IOException
	{
		RuleLoader loader = loadedWith(dir(),
			"{\"version\": 1, \"rules\": [{\"id\": \"x\","
				+ " \"when\": {\"type\": \"always\"}, \"say\": [\"a\"]}]}");
		assertEquals("misc", loader.getRules().get(0).group);
	}

	@Test
	public void duplicateIdsAreReportedButBothStillLoad() throws IOException
	{
		RuleLoader loader = loadedWith(dir(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"same\", \"when\": {\"type\": \"always\"}, \"say\": [\"a\"]},"
				+ "{\"id\": \"same\", \"when\": {\"type\": \"always\"}, \"say\": [\"b\"]}"
				+ "]}");
		assertEquals(2, loader.getRules().size());
		assertFalse(loader.getErrors().isEmpty());
	}

	@Test
	public void rulesArriveSortedByPriority() throws IOException
	{
		RuleLoader loader = loadedWith(dir(),
			"{\"version\": 1, \"rules\": ["
				+ "{\"id\": \"low\", \"priority\": 1, \"when\": {\"type\": \"always\"}, \"say\": [\"a\"]},"
				+ "{\"id\": \"high\", \"priority\": 90, \"when\": {\"type\": \"always\"}, \"say\": [\"b\"]},"
				+ "{\"id\": \"mid\", \"priority\": 50, \"when\": {\"type\": \"always\"}, \"say\": [\"c\"]}"
				+ "]}");

		assertEquals("high", loader.getRules().get(0).id);
		assertEquals("mid", loader.getRules().get(1).id);
		assertEquals("low", loader.getRules().get(2).id);
	}

	// -------------------------------------------------- hostile but well-formed

	@Test
	public void nonsensicalButValidRulesCannotBreakADispatch() throws IOException
	{
		String[] awkward = {
			"{\"id\": \"a\", \"when\": {\"type\": \"NoSuchType\"}, \"say\": [\"x\"]}",
			"{\"id\": \"b\", \"when\": {\"type\": \"npcSpawn\"}, \"say\": [\"x\"]}",
			"{\"id\": \"c\", \"when\": {\"type\": \"inRegion\"}, \"say\": [\"x\"]}",
			"{\"id\": \"d\", \"when\": {\"type\": \"inRegion\", \"regions\": []}, \"say\": [\"x\"]}",
			"{\"id\": \"e\", \"when\": {\"type\": \"itemEquipped\"}, \"say\": [\"x\"]}",
			"{\"id\": \"f\", \"when\": {\"type\": \"varbitEquals\"}, \"say\": [\"x\"]}",
			"{\"id\": \"g\", \"when\": {\"type\": \"inArea\"}, \"say\": [\"x\"]}",
			"{\"id\": \"h\", \"when\": {\"type\": \"chance\", \"percent\": -5}, \"say\": [\"x\"]}",
			"{\"id\": \"i\", \"when\": {\"type\": \"chance\", \"percent\": 500}, \"say\": [\"x\"]}",
			"{\"id\": \"j\", \"cooldownMs\": -1, \"when\": {\"type\": \"always\"}, \"say\": [\"x\"]}",
			"{\"id\": \"k\", \"delayTicks\": -4, \"when\": {\"type\": \"always\"}, \"say\": [\"x\"]}",
			"{\"id\": \"l\", \"priority\": -2147483648, \"when\": {\"type\": \"always\"}, \"say\": [\"x\"]}",
			"{\"id\": \"m\", \"when\": {\"type\": \"idle\", \"ticks\": -1}, \"say\": [\"x\"]}",
			"{\"id\": \"n\", \"when\": {\"type\": \"npcNearby\", \"within\": -3}, \"say\": [\"x\"]}",
			"{\"id\": \"o\", \"when\": {\"type\": \"all\", \"conditions\": [null]}, \"say\": [\"x\"]}",
			"{\"id\": \"p\", \"when\": {\"type\": \"animationSelf\", \"ids\": [null]}, \"say\": [\"x\"]}",
			"{\"id\": \"q\", \"when\": {\"type\": \"npcSpawn\", \"names\": [\"\"]}, \"say\": [\"x\"]}",
			"{\"id\": \"r\", \"when\": {\"type\": \"always\"}, \"say\": [\"{unclosed\"]}",
			"{\"id\": \"s\", \"when\": {\"type\": \"always\"}, \"say\": [\"\"]}",
			"{\"id\": \"t\", \"animation\": -1, \"when\": {\"type\": \"always\"}}",
			"{\"id\": \"u\", \"animations\": [], \"when\": {\"type\": \"always\"}, \"say\": [\"x\"]}",
		};

		Harness h = new Harness(dir(),
			"{\"version\": 1, \"rules\": [" + String.join(",", awkward) + "]}");

		// Everything the plugin can raise, at a rule set built to be awkward.
		h.gameTicks(3);
		for (TriggerEvent.Type type : TriggerEvent.Type.values())
		{
			h.dispatch(TriggerEvent.simple(type));
		}
		h.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, 1, null));
		h.dispatch(TriggerEvent.chat(null, 0));
		h.dispatch(TriggerEvent.levelUp(null, 1));
		h.dispatch(TriggerEvent.loot(0, null));
		h.dispatch(TriggerEvent.kill(1, null, 0));
		h.dispatch(TriggerEvent.animation(-1));
		h.gameTicks(3);

		// No assertion on what spoke; the point is that nothing threw and the
		// engine is still willing to work afterwards.
		h.game.hitpoints(1, 99);
		h.gameTicks(2);
	}

	@Test
	public void aRuleWithADeeplyNestedConditionDoesNotOverflow() throws IOException
	{
		StringBuilder when = new StringBuilder();
		int depth = 400;
		for (int i = 0; i < depth; i++)
		{
			when.append("{\"type\": \"all\", \"conditions\": [");
		}
		when.append("{\"type\": \"always\"}");
		for (int i = 0; i < depth; i++)
		{
			when.append("]}");
		}

		Harness h = new Harness(dir(),
			"{\"version\": 1, \"rules\": [{\"id\": \"deep\", \"group\": \"t\","
				+ " \"cooldownMs\": 0, \"when\": " + when + ", \"say\": [\"a\"]}]}");
		h.gameTicks(3);

		// Either it evaluates or it takes itself out of service on the stack
		// overflow; what it must not do is take the dispatch down with it.
		h.game.hitpoints(1, 99);
		h.gameTicks(2);
	}

	@Test
	public void aVeryLargeRuleSetStillLoadsAndDispatches() throws IOException
	{
		StringBuilder rules = new StringBuilder();
		for (int i = 0; i < 5000; i++)
		{
			if (i > 0)
			{
				rules.append(',');
			}
			rules.append("{\"id\": \"r").append(i).append("\", \"group\": \"t\","
				+ " \"when\": {\"type\": \"inRegion\", \"regions\": [").append(i)
				.append("]}, \"say\": [\"line ").append(i).append("\"]}");
		}

		Harness h = new Harness(dir(), "{\"version\": 1, \"rules\": [" + rules + "]}");
		assertEquals(5000, h.loader.getRules().size());
		h.gameTicks(5);
	}

	// --------------------------------------------------------------- reload

	@Test
	public void anUnchangedFileIsNotReloaded() throws IOException
	{
		Path dir = dir();
		RuleLoader loader = loadedWith(dir,
			"{\"version\": 1, \"rules\": [{\"id\": \"x\", \"group\": \"t\","
				+ " \"when\": {\"type\": \"always\"}, \"say\": [\"a\"]}]}");

		assertFalse("nothing changed, so nothing to do", loader.reloadIfChanged());
		assertFalse(loader.reloadIfChanged());
	}

	@Test
	public void aReloadResetsEdgesSoNothingFiresFromTheOldState() throws IOException
	{
		Path dir = dir();
		Harness h = new Harness(dir,
			"{\"version\": 1, \"rules\": [{\"id\": \"hurt\", \"group\": \"t\","
				+ " \"cooldownMs\": 0, \"when\": {\"type\": \"healthBelow\", \"percent\": 50},"
				+ " \"say\": [\"a\"]}]}");

		h.game.hitpoints(10, 99);
		h.gameTicks(2);
		assertEquals(1, h.firedBy("hurt").size());

		// Editing the file re-parses the rules, which resets their edge state.
		// The condition is still true, so it legitimately fires once more.
		Files.write(dir.resolve(RuleLoader.FILE_NAME),
			("{\"version\": 1, \"rules\": [{\"id\": \"hurt\", \"group\": \"t\","
				+ " \"cooldownMs\": 0, \"when\": {\"type\": \"healthBelow\", \"percent\": 50},"
				+ " \"say\": [\"b\"]}]}").getBytes(StandardCharsets.UTF_8));
		assertTrue(h.loader.reloadIfChanged());
		h.gameTicks(2);

		assertEquals("the edited rule should be live", 2, h.firedBy("hurt").size());
		assertEquals("b", h.spoken.get(1).text);
	}

	@Test
	public void ruleStateIsCleanOnLoadNotInheritedFromTheLastParse() throws IOException
	{
		RuleLoader loader = loadedWith(dir(),
			"{\"version\": 1, \"rules\": [{\"id\": \"x\", \"group\": \"t\","
				+ " \"when\": {\"type\": \"always\"}, \"say\": [\"a\"]}]}");
		SpeechRule rule = loader.getRules().get(0);

		assertTrue("a freshly loaded rule must not think it just fired",
			rule.offCooldown(System.currentTimeMillis()));
		assertTrue("nor that its condition was already true",
			rule.risingEdge(true));
	}
}

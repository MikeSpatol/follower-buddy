package com.follower;

import com.google.gson.Gson;
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
 * A boss is not a combat level.
 *
 * <p>It was one, in two places that had to be kept agreeing by hand: the
 * spectating shield asked whether the thing being fought was level 100, and
 * the kill celebration asked the same of the thing that died. Both are proxies
 * and both fail the same way. Brutus fights at thirty, so the follower stood
 * its ground through the whole fight and said nothing when he went down, while
 * the boss rules written for him that same day fired perfectly - two
 * definitions of one word, and only the weaker one consulted.
 *
 * <p>The names the boss rules already list are the other half of the answer,
 * so that list is now the answer and the rule file is the only place it lives.
 * These tests hold both halves: what is named counts however small, and what
 * is merely large still counts without anybody having named it.
 */
public class BossIsNotACombatLevelTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final String RULES = "{'rules':["
		+ "{'id':'boss-brutus','group':'boss','when':{'type':'npcSpawn',"
		+ "'names':['Brutus']},'say':['x']},"
		+ "{'id':'boss-inferno','group':'boss','when':{'type':'npcSpawn',"
		+ "'names':['Jal-*']},'say':['x']},"
		+ "{'id':'boss-low','group':'boss','when':{'type':'all','conditions':["
		+ "{'type':'healthBelow','percent':50},"
		+ "{'type':'npcNearby','within':12,'names':['Vorkath']}]},'say':['x']},"
		+ "{'id':'chat-rat','group':'combat','when':{'type':'npcSpawn',"
		+ "'names':['Giant rat']},'say':['x']}]}";

	private TriggerContext contextFor(String rulesJson) throws Exception
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve("phrases.json"),
			rulesJson.replace('\'', '"').getBytes(StandardCharsets.UTF_8));

		RuleLoader loader = new RuleLoader(new Gson());
		loader.initialise(dir);

		TriggerContext context = new TriggerContext(null);
		context.setBossNames(loader.getBossNames());
		return context;
	}

	@Test
	public void aBossThatFightsBelowItsWeightStillCounts() throws Exception
	{
		assertTrue("Brutus fights at 30 and is still a boss",
			contextFor(RULES).isBoss("Brutus", 30));
	}

	@Test
	public void somethingMerelyLargeStillCountsWithoutBeingNamed() throws Exception
	{
		assertTrue("the level is still half the answer",
			contextFor(RULES).isBoss("Some Future Boss", 640));
	}

	@Test
	public void anOrdinaryMonsterIsStillOrdinary() throws Exception
	{
		TriggerContext context = contextFor(RULES);
		assertFalse("a rat named in a rule that is not a boss rule is not a boss",
			context.isBoss("Giant rat", 30));
		assertFalse("nor is anything else small and unlisted",
			context.isBoss("Man", 2));
	}

	@Test
	public void theNamesComeFromEveryShapeOfBossRule() throws Exception
	{
		TriggerContext context = contextFor(RULES);
		assertTrue("a wildcard boss name still matches",
			context.isBoss("Jal-Nib", 32));
		assertTrue("a name nested inside a combinator is collected too",
			context.isBoss("Vorkath", 1));
	}

	/** The game pads names with non-breaking spaces; the matcher is shared. */
	@Test
	public void paddedNamesDoNotHideABoss() throws Exception
	{
		assertTrue("padded the way the game pads it",
			contextFor(RULES).isBoss(" Brutus ", 30));
		assertEquals("Brutus", Condition.normaliseName(" Brutus "));
	}

	@Test
	public void theShippedRulesNameBrutusAsABoss() throws Exception
	{
		Harness harness = new Harness(folder.newFolder().toPath());
		TriggerContext context = new TriggerContext(null);
		context.setBossNames(harness.loader.getBossNames());

		assertTrue("Brutus, from the rules that ship",
			context.isBoss("Brutus", 30));
		assertTrue("and the form he turns into",
			context.isBoss("Demonic Brutus", 1224));
		assertFalse("while the shipped set has not swallowed everything",
			context.isBoss("Guard", 21));
		assertFalse("nor anything merely nearby in a non-boss rule",
			context.isBoss("Hans", 0));
	}
}

package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * An NPC entering the scene costs a pass over every rule only when a rule's
 * npcSpawn names it. A wave of 20-40 NPCs was 5-12 ms in one frame, nearly
 * all of it passes no rule could use (perf audit, Oct 2026, #58).
 */
public class SpawnPassTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static TriggerEvent spawn(int id, String name)
	{
		return TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, id, name);
	}

	@Test
	public void onlyASpawnARuleNamesIsPassedToTheRules() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		assertTrue("a boss the rules name", h.loader.answersSpawn(spawn(1, "Callisto")));
		assertTrue("by a wildcard", h.loader.answersSpawn(spawn(2, "Jal-Nib")));
		assertFalse("a passer-by no rule names", h.loader.answersSpawn(spawn(3, "Zzyzx the unnamed")));
		assertFalse(h.loader.answersSpawn(spawn(4, null)));
	}

	@Test
	public void thePluginAsksBeforeItPasses() throws IOException
	{
		String plugin = new String(Files.readAllBytes(Paths.get("src/main/java/com/follower/FollowerPlugin.java")),
			StandardCharsets.UTF_8).replace("\r\n", "\n");
		int at = plugin.indexOf("public void onNpcSpawned(");
		assertTrue("no onNpcSpawned - the lint has lost its place", at >= 0);
		String body = plugin.substring(at, plugin.indexOf("\n\t}\n", at));
		assertTrue(body, body.contains("ruleLoader.answersSpawn("));
	}
}

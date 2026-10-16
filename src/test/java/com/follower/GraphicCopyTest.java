package com.follower;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Copying the player's graphics onto the follower (QA, 15 Oct): every
 * copying rule opened a twelve-tick window, eating among them, so in a fight
 * each bite had the follower wear the spells that landed on the player. Now
 * only a rule that says so opens it, and the first graphic closes it.
 */
public class GraphicCopyTest
{
	@Test
	public void oneCastCopiesOneGraphic() throws ReflectiveOperationException
	{
		FollowerPlugin plugin = new FollowerPlugin();
		assertFalse("no window open", plugin.takesMirroredGraphic(100));

		Field until = FollowerPlugin.class.getDeclaredField("mirrorGraphicsUntilTick");
		until.setAccessible(true);
		until.setInt(plugin, 112);
		assertTrue("the cast's own graphic", plugin.takesMirroredGraphic(101));
		assertFalse("and nothing after it", plugin.takesMirroredGraphic(102));

		until.setInt(plugin, 112);
		assertFalse("nor once the window has closed", plugin.takesMirroredGraphic(113));
	}

	@Test
	public void onlyTheCastsCopyGraphics() throws IOException
	{
		Set<String> copying = new HashSet<>();
		Set<String> mirroring = new HashSet<>();
		try (Reader reader = new InputStreamReader(FollowerPlugin.class.getResourceAsStream(
			"/com/follower/default-phrases.json"), StandardCharsets.UTF_8))
		{
			for (JsonElement element : new JsonParser().parse(reader).getAsJsonObject().getAsJsonArray("rules"))
			{
				JsonObject rule = element.getAsJsonObject();
				if (rule.has("copiesGraphics") && rule.get("copiesGraphics").getAsBoolean())
				{
					copying.add(rule.get("id").getAsString());
				}
				if (rule.has("mirrorAnimation") && rule.get("mirrorAnimation").getAsBoolean())
				{
					mirroring.add(rule.get("id").getAsString());
				}
			}
		}
		assertEquals(new HashSet<>(Arrays.asList("mirror-teleport", "casting-along", "mimic-emotes")), copying);
		assertTrue("eating still copies the bite", mirroring.contains("mimic-eat"));
	}

	@Test
	public void theWindowOpensOnlyForARuleThatCopiesGraphics() throws IOException
	{
		String source = new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8)
			.replace("\r\n", "\n");
		int opens = source.indexOf("mirrorGraphicsUntilTick = client.getTickCount() + MIRROR_GRAPHIC_WINDOW_TICKS;");
		assertTrue(opens > 0);
		String guard = source.substring(source.lastIndexOf("if (", opens), opens);
		assertTrue(guard, guard.contains("Boolean.TRUE.equals(rule.copiesGraphics)"));
		assertFalse(guard, guard.contains("mirrorAnimation"));
		assertTrue("a graphic copied closes the window",
			source.contains("if (takesMirroredGraphic(client.getTickCount()))"));
	}
}

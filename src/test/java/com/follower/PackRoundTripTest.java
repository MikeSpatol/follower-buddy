package com.follower;

import com.follower.share.PackFile;
import com.follower.sound.VoiceLibrary;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A pack carries one player's own work to another player, and nothing else.
 *
 * <p>The whole point is the diff. Exporting the rule file wholesale would ship
 * two thousand shipped lines along with it, overwrite whatever the recipient
 * had written, and freeze them at the sender's version of everything the plugin
 * updates. These tests hold that line, and hold the merge to never replacing.
 */
public class PackRoundTripTest
{
	private static final Gson GSON = new Gson();

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** Single quotes for readability; JSON wants double. */
	private static JsonObject rules(String json)
	{
		return GSON.fromJson(json.replace('\'', '"'), JsonObject.class);
	}

	/** What the plugin ships: the baseline both players started from. */
	private static JsonObject shipped()
	{
		return rules("{'rules':["
			+ "{'id':'bank','say':['Deep pockets.','All that gold.']},"
			+ "{'id':'combat','say':['Behind you.']}]}");
	}

	private static List<String> say(JsonObject file, String id)
	{
		for (JsonElement element : file.getAsJsonArray("rules"))
		{
			JsonObject rule = element.getAsJsonObject();
			if (id.equals(rule.get("id").getAsString()))
			{
				List<String> out = new ArrayList<>();
				for (JsonElement line : rule.getAsJsonArray("say"))
				{
					out.add(line.getAsString());
				}
				return out;
			}
		}
		return null;
	}

	private VoiceLibrary voicesIn(String name) throws Exception
	{
		VoiceLibrary voices = new VoiceLibrary(GSON);
		voices.load(folder.newFolder(name).toPath());
		return voices;
	}

	private Path zip(String name) throws Exception
	{
		return folder.getRoot().toPath().resolve(name);
	}

	@Test
	public void aPackCarriesOnlyWhatThePlayerWroteThemselves() throws Exception
	{
		JsonObject sender = rules("{'rules':["
			+ "{'id':'bank','say':['Deep pockets.','All that gold.','Count it twice.']},"
			+ "{'id':'combat','say':['Behind you.']},"
			+ "{'id':'my-own','when':{'type':'tick'},'say':['Just us two.']}]}");

		Path pack = zip("pack.zip");
		PackFile.Summary made = PackFile.export(
			pack, sender, shipped(), voicesIn("mine"), GSON, "Sender");

		// One added line, and one whole rule of their own.
		assertEquals(2, made.rules);
		assertEquals(2, made.lines);

		JsonObject carried = new JsonObject();
		carried.add("rules", PackFile.read(pack, GSON).rules);
		assertEquals("only the added line travels, not the rule around it",
			Arrays.asList("Count it twice."), say(carried, "bank"));
		assertEquals("the player's own rule travels whole",
			Arrays.asList("Just us two."), say(carried, "my-own"));
		assertNull("an untouched rule must not travel at all", say(carried, "combat"));
	}

	@Test
	public void importingAddsToWhatTheRecipientWroteRatherThanReplacingIt() throws Exception
	{
		JsonObject sender = rules("{'rules':["
			+ "{'id':'bank','say':['Deep pockets.','All that gold.','Count it twice.']},"
			+ "{'id':'my-own','when':{'type':'tick'},'say':['Just us two.']}]}");
		Path pack = zip("pack.zip");
		PackFile.export(pack, sender, shipped(), voicesIn("from"), GSON, "Sender");

		// The recipient has written into the same rule.
		JsonObject recipient = rules("{'rules':["
			+ "{'id':'bank','say':['Deep pockets.','All that gold.','Mine, all mine.']},"
			+ "{'id':'combat','say':['Behind you.']}]}");

		VoiceLibrary theirs = voicesIn("to");
		PackFile.Summary took = PackFile.apply(
			PackFile.read(pack, GSON), recipient, theirs.getSoundsFolder(), theirs);

		List<String> bank = say(recipient, "bank");
		assertTrue("their own line survives the import", bank.contains("Mine, all mine."));
		assertTrue("the incoming line arrives", bank.contains("Count it twice."));
		assertEquals(4, bank.size());
		assertEquals(Arrays.asList("Just us two."), say(recipient, "my-own"));
		assertEquals(2, took.rules);
		assertEquals(2, took.lines);
	}

	@Test
	public void importingTheSamePackTwiceChangesNothingTheSecondTime() throws Exception
	{
		JsonObject sender = rules("{'rules':["
			+ "{'id':'bank','say':['Deep pockets.','All that gold.','Count it twice.']}]}");
		Path pack = zip("pack.zip");
		PackFile.export(pack, sender, shipped(), voicesIn("from"), GSON, "Sender");

		JsonObject recipient = shipped();
		VoiceLibrary theirs = voicesIn("to");
		PackFile.apply(PackFile.read(pack, GSON), recipient, theirs.getSoundsFolder(), theirs);
		PackFile.Summary again = PackFile.apply(
			PackFile.read(pack, GSON), recipient, theirs.getSoundsFolder(), theirs);

		assertEquals("no line arrives twice", 3, say(recipient, "bank").size());
		assertEquals(0, again.lines);
	}

	@Test
	public void aSoundTravelsWithTheLineItBelongsTo() throws Exception
	{
		VoiceLibrary sending = voicesIn("from");
		Files.write(sending.getSoundsFolder().resolve("count.wav"), new byte[]{1, 2, 3, 4});
		sending.attach("Count it twice.", "count.wav");

		JsonObject sender = rules("{'rules':["
			+ "{'id':'bank','say':['Deep pockets.','All that gold.','Count it twice.']}]}");
		Path pack = zip("pack.zip");
		assertEquals(1, PackFile.export(pack, sender, shipped(), sending, GSON, "Sender").sounds);

		JsonObject recipient = shipped();
		VoiceLibrary theirs = voicesIn("to");
		PackFile.apply(PackFile.read(pack, GSON), recipient, theirs.getSoundsFolder(), theirs);

		assertTrue(Files.exists(theirs.getSoundsFolder().resolve("count.wav")));
		assertEquals("the line arrives already voiced",
			"count.wav", theirs.attachmentFor("Count it twice."));

		// A second import must not breed count-2.wav, count-3.wav...
		PackFile.apply(PackFile.read(pack, GSON), recipient, theirs.getSoundsFolder(), theirs);
		assertEquals(1, theirs.availableFiles().size());
	}

	@Test
	public void anIncomingSoundNeverOverwritesOneTheRecipientAlreadyHas() throws Exception
	{
		VoiceLibrary sending = voicesIn("from");
		Files.write(sending.getSoundsFolder().resolve("voice.wav"), new byte[]{9, 9, 9});
		sending.attach("Count it twice.", "voice.wav");
		JsonObject sender = rules("{'rules':["
			+ "{'id':'bank','say':['Deep pockets.','All that gold.','Count it twice.']}]}");
		Path pack = zip("pack.zip");
		PackFile.export(pack, sender, shipped(), sending, GSON, "Sender");

		// A different recording, under the same name.
		VoiceLibrary theirs = voicesIn("to");
		Path ours = theirs.getSoundsFolder().resolve("voice.wav");
		Files.write(ours, new byte[]{1, 1, 1, 1, 1});

		JsonObject recipient = shipped();
		PackFile.apply(PackFile.read(pack, GSON), recipient, theirs.getSoundsFolder(), theirs);

		assertEquals("their own recording is untouched", 5, Files.readAllBytes(ours).length);
		assertEquals("the incoming one lands beside it, and the line points at it",
			"voice-2.wav", theirs.attachmentFor("Count it twice."));
	}

	@Test
	public void linesForARuleTheRecipientDoesNotHaveAreReportedNotSilentlyAdded()
		throws Exception
	{
		JsonObject sender = rules("{'rules':["
			+ "{'id':'combat','say':['Behind you.','Mind the spec.']}]}");
		Path pack = zip("pack.zip");
		PackFile.export(pack, sender, shipped(), voicesIn("from"), GSON, "Sender");

		// An older phrases.json, from before 'combat' shipped.
		JsonObject recipient = rules("{'rules':[{'id':'bank','say':['Deep pockets.']}]}");
		VoiceLibrary theirs = voicesIn("to");
		PackFile.Summary took = PackFile.apply(
			PackFile.read(pack, GSON), recipient, theirs.getSoundsFolder(), theirs);

		assertEquals("a rule with nothing to fire it is not worth adding",
			1, recipient.getAsJsonArray("rules").size());
		assertEquals(0, took.lines);
		assertTrue(took.problems.toString(), took.problems.get(0).contains("combat"));
	}

	@Test
	public void aLineTheGameCannotDrawIsRefusedWhoeverWroteIt() throws Exception
	{
		JsonObject sender = rules("{'rules':[{'id':'my-own','when':{'type':'tick'},'say':["
			+ "'Fine.','Dashes — like this.','',"
			+ "'A line so long that nobody could read it in the short moment that it is"
			+ " actually up there on the screen.']}]}");
		Path pack = zip("pack.zip");
		PackFile.export(pack, sender, shipped(), voicesIn("from"), GSON, "Sender");

		PackFile.Loaded loaded = PackFile.read(pack, GSON);
		assertEquals("only the one good line survives", 1, loaded.summary.lines);
		assertEquals(loaded.summary.problems.toString(), 3, loaded.summary.problems.size());
	}

	@Test
	public void anEntryNamedToEscapeTheFolderIsIgnored() throws Exception
	{
		Path pack = zip("hostile.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(pack)))
		{
			out.putNextEntry(new ZipEntry("../../evil.wav"));
			out.write(new byte[]{1});
			out.closeEntry();
			out.putNextEntry(new ZipEntry("sounds/../../evil.wav"));
			out.write(new byte[]{1});
			out.closeEntry();
		}
		PackFile.Loaded loaded = PackFile.read(pack, GSON);
		assertTrue("nothing outside the pack's own names is read", loaded.audio.isEmpty());
	}
}

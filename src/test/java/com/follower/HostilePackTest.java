package com.follower;

import com.follower.share.PackFile;
import com.follower.sound.VoiceLibrary;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A pack written by somebody who means harm.
 *
 * <p>The threat is not code - nothing in a pack is executed, and the plugin
 * takes no server-side action, so an account cannot be made to do anything.
 * The threat is CONTENT reaching two places it should not: the game chatbox,
 * which interprets Jagex markup and would happily render an attacker's text in
 * whatever colour they choose, and the sound attachments, where a pack could
 * point audio at lines it never carried and overwrite recordings the recipient
 * made themselves.
 *
 * <p>Each test here is an attack. They are written from the outside, against
 * the zip, because that is the only thing an attacker controls.
 */
public class HostilePackTest
{
	private static final Gson GSON = new Gson();

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** Builds a pack by hand: an attacker does not use our exporter. */
	private Path pack(String rulesJson, String soundsJson, String... audio) throws Exception
	{
		Path zip = folder.getRoot().toPath().resolve("hostile-" + rulesJson.hashCode() + ".zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip)))
		{
			out.putNextEntry(new ZipEntry("pack.json"));
			out.write("{\"format\":1,\"author\":\"friend\"}".getBytes("UTF-8"));
			out.closeEntry();
			out.putNextEntry(new ZipEntry("rules.json"));
			out.write(rulesJson.replace('\'', '"').getBytes("UTF-8"));
			out.closeEntry();
			if (soundsJson != null)
			{
				out.putNextEntry(new ZipEntry("sounds.json"));
				out.write(soundsJson.replace('\'', '"').getBytes("UTF-8"));
				out.closeEntry();
			}
			for (String name : audio)
			{
				out.putNextEntry(new ZipEntry("sounds/" + name));
				out.write(new byte[]{1, 2, 3});
				out.closeEntry();
			}
		}
		return zip;
	}

	private VoiceLibrary voicesIn(String name) throws Exception
	{
		VoiceLibrary voices = new VoiceLibrary(GSON);
		voices.load(folder.newFolder(name).toPath());
		return voices;
	}

	private static JsonObject theirs()
	{
		return GSON.fromJson("{\"rules\":[{\"id\":\"bank\",\"say\":[\"Deep pockets.\"]}]}",
			JsonObject.class);
	}

	private static String allLines(PackFile.Loaded loaded)
	{
		return loaded.rules.toString();
	}

	/**
	 * The chatbox interprets Jagex markup, and mirroring to it is on by
	 * default, so a tag in a line is a tag on somebody's screen.
	 */
	@Test
	public void markupIsRefusedBecauseTheChatboxWouldRenderIt() throws Exception
	{
		PackFile.Loaded loaded = PackFile.read(pack(
			"{'rules':[{'id':'evil','when':{'type':'tick'},'say':["
				+ "'<col=ff0000>Account flagged. Verify at evil.example',"
				+ "'<img=0> Mod Hint: type your password',"
				+ "'Plain and fine.']}]}", null), GSON);

		assertFalse("a colour tag must not survive", allLines(loaded).contains("<col="));
		assertFalse("an image tag must not survive", allLines(loaded).contains("<img="));
		assertEquals("only the honest line arrives", 1, loaded.summary.lines);
	}

	/** A newline or a null is not a line, and the ASCII check let both through. */
	@Test
	public void controlCharactersAreRefused() throws Exception
	{
		PackFile.Loaded loaded = PackFile.read(pack(
			"{'rules':[{'id':'evil','when':{'type':'tick'},'say':["
				+ "'Two\\nlines in one','Null\\u0000byte','Carriage\\rreturn',"
				+ "'Plain and fine.']}]}", null), GSON);

		assertEquals("only the honest line arrives", 1, loaded.summary.lines);
	}

	/**
	 * The sound map is a separate file from the rules, so nothing stopped it
	 * naming a line the pack never carried - including one that ships.
	 */
	@Test
	public void aPackCannotPutItsAudioOnALineItDidNotBring() throws Exception
	{
		Path zip = pack("{'rules':[{'id':'mine','when':{'type':'tick'},'say':['My own line.']}]}",
			"{'Deep pockets.':'evil.wav','My own line.':'evil.wav'}", "evil.wav");

		VoiceLibrary theirVoices = voicesIn("to");
		JsonObject recipient = theirs();
		PackFile.apply(PackFile.read(zip, GSON), recipient, theirVoices.getSoundsFolder(), theirVoices);

		assertNotNull("the line the pack actually brought is voiced",
			theirVoices.attachmentFor("My own line."));
		assertEquals("a shipped line the pack never carried must stay silent",
			null, theirVoices.attachmentFor("Deep pockets."));
	}

	/** "Nothing is replaced" has to cover recordings, not just text. */
	@Test
	public void aPackCannotReplaceARecordingTheRecipientMadeThemselves() throws Exception
	{
		VoiceLibrary theirVoices = voicesIn("to");
		Files.write(theirVoices.getSoundsFolder().resolve("mine.wav"), new byte[]{7, 7});
		theirVoices.attach("Shared line.", "mine.wav");

		Path zip = pack("{'rules':[{'id':'mine','when':{'type':'tick'},'say':['Shared line.']}]}",
			"{'Shared line.':'evil.wav'}", "evil.wav");

		JsonObject recipient = theirs();
		PackFile.Summary took = PackFile.apply(
			PackFile.read(zip, GSON), recipient, theirVoices.getSoundsFolder(), theirVoices);

		assertEquals("their own recording still plays", "mine.wav",
			theirVoices.attachmentFor("Shared line."));
		assertTrue("and they are told it was left out", took.problems.toString().contains("Shared line."));
	}

	/** Deeply nested JSON overflows Gson's recursive parser. */
	@Test
	public void aDeeplyNestedPackIsRefusedRatherThanThrowingPastTheHandler()
		throws Exception
	{
		StringBuilder nested = new StringBuilder("{\"rules\":");
		for (int i = 0; i < 12000; i++)
		{
			nested.append('[');
		}
		for (int i = 0; i < 12000; i++)
		{
			nested.append(']');
		}
		nested.append('}');

		Path zip = folder.getRoot().toPath().resolve("nested.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip)))
		{
			out.putNextEntry(new ZipEntry("rules.json"));
			out.write(nested.toString().getBytes("UTF-8"));
			out.closeEntry();
		}

		// An IOException is a refusal the caller already reports. A
		// StackOverflowError is an Error, so it would sail straight past the
		// catch (Exception) around the import and out of the action listener -
		// which is why it is deliberately NOT caught here.
		try
		{
			assertEquals(0, PackFile.read(zip, GSON).summary.rules);
		}
		catch (java.io.IOException refused)
		{
			assertNotNull(refused.getMessage());
		}
	}

	/** A pack must not be able to bury the player's file under its own bulk. */
	@Test
	public void anAbsurdNumberOfLinesIsCapped() throws Exception
	{
		StringBuilder say = new StringBuilder();
		for (int i = 0; i < 60000; i++)
		{
			say.append(i == 0 ? "" : ",").append("'Line number ").append(i).append(" here.'");
		}
		PackFile.Loaded loaded = PackFile.read(pack(
			"{'rules':[{'id':'flood','when':{'type':'tick'},'say':[" + say + "]}]}", null), GSON);

		assertTrue("the flood is cut off: " + loaded.summary.lines,
			loaded.summary.lines <= 5000);
		assertFalse("and the player is told", loaded.summary.problems.isEmpty());
	}

	/** Windows opens CON, AUX and COM1 as devices whatever the extension. */
	@Test
	public void aReservedDeviceNameIsNotWrittenTo() throws Exception
	{
		PackFile.Loaded loaded = PackFile.read(pack(
			"{'rules':[{'id':'mine','when':{'type':'tick'},'say':['Fine.']}]}",
			"{'Fine.':'COM1.wav'}", "COM1.wav", "CON.wav"), GSON);

		assertTrue("reserved names never reach a write: " + loaded.audio.keySet(),
			loaded.audio.isEmpty());
	}

	/**
	 * The import dialog is where the player decides whether to trust the pack,
	 * so text FROM the pack must not be able to forge lines in it. A newline
	 * in an author name or in an echoed-back refusal would do exactly that.
	 */
	@Test
	public void packTextCannotForgeLinesInTheDialogThatAsksAboutIt() throws Exception
	{
		Path zip = folder.getRoot().toPath().resolve("spoof.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip)))
		{
			out.putNextEntry(new ZipEntry("pack.json"));
			out.write(("{\"author\":\"Bob\n\n  [Verified by RuneLite - no review needed]"
				+ "\n\nThis pack\"}").getBytes("UTF-8"));
			out.closeEntry();
			out.putNextEntry(new ZipEntry("rules.json"));
			out.write(("{\"rules\":[{\"id\":\"evil\",\"when\":{\"type\":\"tick\"},\"say\":["
				+ "\"\n\nAll checks passed. Nothing was left out.\n\n\","
				+ "\"<html><b>trusted</b>\"]}]}").getBytes("UTF-8"));
			out.closeEntry();
			out.putNextEntry(new ZipEntry("sounds.json"));
			out.write("{\"line\nnot\nbrought\":\"a.wav\"}".getBytes("UTF-8"));
			out.closeEntry();
			out.putNextEntry(new ZipEntry("sounds/a.wav"));
			out.write(new byte[]{1, 2, 3});
			out.closeEntry();
		}

		PackFile.Loaded loaded = PackFile.read(zip, GSON);
		VoiceLibrary theirVoices = voicesIn("to");
		PackFile.Summary took = PackFile.apply(
			loaded, theirs(), theirVoices.getSoundsFolder(), theirVoices);

		java.util.List<String> shown = new java.util.ArrayList<>();
		shown.add(loaded.summary.author);
		shown.addAll(loaded.summary.problems);
		shown.addAll(took.problems);
		for (String text : shown)
		{
			assertFalse("a newline would forge a line in the dialog: " + text,
				text.contains(String.valueOf((char) 10))
				|| text.contains(String.valueOf((char) 13)));
			assertFalse("markup must not reach a Swing label: " + text,
				text.contains("<") || text.contains(">"));
		}
		assertTrue("the forged reassurance is not quoted back verbatim",
			loaded.summary.problems.toString().indexOf("Nothing was left out") < 0
				|| !loaded.summary.problems.toString().contains(String.valueOf((char) 10)));
	}

	/** An id is a key in our own bookkeeping, not free-form text. */
	@Test
	public void anAbsurdIdIsRefused() throws Exception
	{
		StringBuilder id = new StringBuilder();
		for (int i = 0; i < 5000; i++)
		{
			id.append('x');
		}
		PackFile.Loaded loaded = PackFile.read(pack(
			"{'rules':[{'id':'" + id + "','when':{'type':'tick'},'say':['Fine.']},"
				+ "{'id':'has spaces and <tags>','when':{'type':'tick'},'say':['Fine.']}]}",
			null), GSON);

		assertEquals("neither id is one of ours", 0, loaded.summary.rules);
	}
}

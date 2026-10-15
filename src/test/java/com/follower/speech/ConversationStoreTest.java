package com.follower.speech;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The saved conversations: each comes back as it was saved, a name is one
 * conversation however it is capitalised, and a file that cannot be read
 * is never written over.
 */
public class ConversationStoreTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static Conversation sample(String name)
	{
		Conversation script = new Conversation();
		script.name = name;
		script.advance = Conversation.Advance.AUTO;
		script.seconds = 3.5;
		script.lines.add(new Conversation.Line(Conversation.Speaker.YOU, Conversation.Display.BOX,
			Conversation.Expression.HAPPY, "Morning!\nSleep well?"));
		script.lines.add(new Conversation.Line(Conversation.Speaker.FOLLOWER, Conversation.Display.CHAT,
			Conversation.Expression.NEUTRAL, "Like a log \u2014 a \u201Cwet\u201D one."));
		script.lines.add(new Conversation.Line(Conversation.Speaker.FOLLOWER, Conversation.Display.BOX,
			Conversation.Expression.WORRIED, "Is that a goblin?"));
		return script;
	}

	private ConversationStore storeIn(Path dir)
	{
		ConversationStore store = new ConversationStore(new Gson());
		store.load(dir);
		return store;
	}

	@Test
	public void aSavedConversationComesBackAsItWasSaved() throws IOException
	{
		Path dir = folder.newFolder("data").toPath();
		assertTrue(storeIn(dir).save(sample("Morning")));

		Conversation back = storeIn(dir).get("Morning");
		assertNotNull(back);
		assertEquals("Morning", back.name);
		assertEquals(Conversation.Advance.AUTO, back.advance);
		assertEquals(3.5, back.seconds, 0);
		assertEquals(3, back.lines.size());
		Conversation saved = sample("Morning");
		for (int i = 0; i < 3; i++)
		{
			assertEquals(saved.lines.get(i).speaker, back.lines.get(i).speaker);
			assertEquals(saved.lines.get(i).display, back.lines.get(i).display);
			assertEquals(saved.lines.get(i).expression, back.lines.get(i).expression);
			assertEquals(saved.lines.get(i).text, back.lines.get(i).text);
		}
		assertTrue(Files.isRegularFile(dir.resolve(ConversationStore.FILE_NAME)));
	}

	@Test
	public void aNameIsOneConversationHoweverItIsWritten() throws IOException
	{
		Path dir = folder.newFolder("data").toPath();
		ConversationStore store = storeIn(dir);
		store.save(sample("Intro"));
		store.save(sample("Outro"));
		Conversation shorter = sample("  intro ");
		shorter.lines.remove(2);
		store.save(shorter);

		ConversationStore again = storeIn(dir);
		assertEquals("in its place, under the name as last typed", Arrays.asList("intro", "Outro"), again.names());
		assertEquals("written over, not added", 2, again.get("INTRO").lines.size());
	}

	@Test
	public void aCopyHandedOutDoesNotChangeWhatIsSaved() throws IOException
	{
		ConversationStore store = storeIn(folder.newFolder("data").toPath());
		store.save(sample("Intro"));
		store.get("Intro").lines.clear();
		assertEquals(3, store.get("Intro").lines.size());
	}

	@Test
	public void aDeletedConversationIsGoneForGood() throws IOException
	{
		Path dir = folder.newFolder("data").toPath();
		ConversationStore store = storeIn(dir);
		store.save(sample("Intro"));
		store.save(sample("Outro"));
		assertTrue(store.delete("intro"));
		assertFalse("nothing left to delete", store.delete("Intro"));
		assertNull(storeIn(dir).get("Intro"));
		assertEquals(Arrays.asList("Outro"), storeIn(dir).names());
	}

	@Test
	public void noFileIsNoConversations() throws IOException
	{
		ConversationStore store = storeIn(folder.newFolder("data").toPath());
		assertTrue(store.names().isEmpty());
		assertNull(store.get("Anything"));
	}

	@Test
	public void aFileThatCannotBeReadIsKeptAsideBeforeAnythingIsSavedOverIt() throws IOException
	{
		Path dir = folder.newFolder("data").toPath();
		Path file = dir.resolve(ConversationStore.FILE_NAME);
		String broken = "{\"conversations\":[{\"name\":\"Mine\",\"lines\":[";
		Files.write(file, broken.getBytes(StandardCharsets.UTF_8));

		ConversationStore store = storeIn(dir);
		assertTrue(store.names().isEmpty());
		store.save(sample("New"));
		assertEquals(broken, new String(Files.readAllBytes(dir.resolve(ConversationStore.UNREADABLE)),
			StandardCharsets.UTF_8));
	}

	@Test
	public void aHandEditedFileWithGapsStillLoads() throws IOException
	{
		Path dir = folder.newFolder("data").toPath();
		Files.write(dir.resolve(ConversationStore.FILE_NAME), ("{\"conversations\":["
			+ "{\"name\":\"Gaps\",\"lines\":[{\"text\":\"Hi\",\"expression\":\"SMUG\"},null]},"
			+ "{\"lines\":[]},"
			+ "null]}").getBytes(StandardCharsets.UTF_8));

		ConversationStore store = storeIn(dir);
		assertEquals("one without a name is left out", Arrays.asList("Gaps"), store.names());
		Conversation gaps = store.get("Gaps");
		assertEquals(Conversation.Advance.CLICK, gaps.advance);
		assertEquals(1, gaps.lines.size());
		assertEquals(Conversation.Expression.NEUTRAL, gaps.lines.get(0).expression);
		assertEquals(Conversation.Speaker.YOU, gaps.lines.get(0).speaker);
		assertEquals(Conversation.Display.BOX, gaps.lines.get(0).display);
	}
}

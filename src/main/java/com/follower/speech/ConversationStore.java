package com.follower.speech;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * The film window's saved conversations, by name, in one file beside the
 * plugin's other files. A name is matched without regard to case, so a
 * save under a name already taken writes over that one.
 *
 * <p>Read at start-up and written at every save or delete, from the Swing
 * thread, which is the only one that uses it after that.
 */
@Slf4j
@Singleton
public class ConversationStore
{
	public static final String FILE_NAME = "conversations.json";

	/** The file as written. */
	static class Saved
	{
		List<Conversation> conversations = new ArrayList<>();
	}

	private final Gson gson;
	private Path file;
	private final List<Conversation> saved = new ArrayList<>();

	@Inject
	public ConversationStore(Gson gson)
	{
		this.gson = gson;
	}

	public synchronized void load(Path dataDir)
	{
		file = dataDir.resolve(FILE_NAME);
		saved.clear();
		if (!Files.isRegularFile(file))
		{
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			Saved read = gson.fromJson(reader, Saved.class);
			if (read != null && read.conversations != null)
			{
				for (Conversation conversation : read.conversations)
				{
					if (conversation != null && conversation.name != null && !conversation.name.trim().isEmpty())
					{
						saved.add(conversation.tidy());
					}
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			// Kept aside, or the next save would write over what could
			// still be put right by hand.
			log.warn("Could not read {}; kept as {}", file, UNREADABLE, e);
			try
			{
				Files.copy(file, file.resolveSibling(UNREADABLE), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			catch (IOException copy)
			{
				log.warn("Could not keep {} aside", file, copy);
			}
		}
	}

	/** Where a file that could not be read is kept before anything is saved over it. */
	static final String UNREADABLE = FILE_NAME + ".unreadable";

	/** The saved names, in the order they were first saved. */
	public synchronized List<String> names()
	{
		List<String> out = new ArrayList<>();
		for (Conversation conversation : saved)
		{
			out.add(conversation.name);
		}
		return out;
	}

	/** A copy of the one saved under a name, or null. */
	public synchronized Conversation get(String name)
	{
		int at = indexOf(name);
		return at < 0 ? null : saved.get(at).copy();
	}

	/**
	 * Keeps a copy under its name, over any saved under the same one.
	 *
	 * @return false when the file could not be written
	 */
	public synchronized boolean save(Conversation conversation)
	{
		Conversation copy = conversation.copy().tidy();
		copy.name = copy.name.trim();
		int at = indexOf(copy.name);
		if (at >= 0)
		{
			saved.set(at, copy);
		}
		else
		{
			saved.add(copy);
		}
		return write();
	}

	/** @return false when nothing is saved under the name, or the file could not be written */
	public synchronized boolean delete(String name)
	{
		int at = indexOf(name);
		if (at < 0)
		{
			return false;
		}
		saved.remove(at);
		return write();
	}

	private int indexOf(String name)
	{
		String wanted = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
		for (int i = 0; i < saved.size(); i++)
		{
			if (saved.get(i).name.trim().toLowerCase(Locale.ROOT).equals(wanted))
			{
				return i;
			}
		}
		return -1;
	}

	private boolean write()
	{
		if (file == null)
		{
			return false;
		}
		Saved out = new Saved();
		out.conversations = saved;
		try
		{
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8))
			{
				gson.toJson(out, writer);
			}
			return true;
		}
		catch (IOException e)
		{
			log.warn("Could not write {}", file, e);
			return false;
		}
	}
}

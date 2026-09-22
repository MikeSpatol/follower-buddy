package com.follower.chronicle;

import com.follower.speech.TriggerContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;

/**
 * Which pages of the chronicle can be read.
 *
 * <p>Two kinds of knowing. A quest the game remembers for itself, so a
 * quest page asks the client and is right from the first login, quests done
 * years before the plugin existed included. A creature met or a place walked
 * into is something only the follower saw, so those are written into its
 * memory the moment they happen and stay written, through the same profile
 * guarded save as everything else it remembers.
 *
 * <p>Quest states are a script call each, so they are read once per game
 * tick at most and never from a render.
 */
public class ChronicleProgress
{
	private final Client client;
	private final ChronicleLoader loader;
	private final Supplier<TriggerContext> memory;

	/** Quest states as of the last refresh, by enum name. */
	private final Map<String, QuestState> questStates = new HashMap<>();
	private int refreshedOnTick = -1;

	public ChronicleProgress(Client client, ChronicleLoader loader, Supplier<TriggerContext> memory)
	{
		this.client = client;
		this.loader = loader;
		this.memory = memory;
	}

	/**
	 * Re-reads the quest states the pages care about. Once a tick from the
	 * game tick, and again on demand when the window opens, so a quest
	 * finished a moment ago shows finished.
	 */
	public void refresh(int tick)
	{
		if (tick == refreshedOnTick)
		{
			return;
		}
		refreshedOnTick = tick;
		questStates.clear();
		for (ChronicleEntry entry : loader.getEntries())
		{
			if (entry.unlock == null)
			{
				continue;
			}
			readQuest(entry.unlock.quest);
			readQuest(entry.unlock.questBegun);
		}
	}

	private void readQuest(String name)
	{
		if (name == null || questStates.containsKey(name))
		{
			return;
		}
		Quest quest = questNamed(name);
		if (quest == null)
		{
			return;
		}
		try
		{
			QuestState state = quest.getState(client);
			questStates.put(name, state == null ? QuestState.NOT_STARTED : state);
		}
		catch (RuntimeException e)
		{
			// Before the scripts are ready: read as not started, asked again
			// next tick.
			questStates.put(name, QuestState.NOT_STARTED);
			refreshedOnTick = -1;
		}
	}

	/** A quest state as the tests set it, since the fake client runs no scripts. */
	void setQuestState(String name, QuestState state)
	{
		questStates.put(name, state);
	}

	/** The enum constant for a name in the data, or null when there is none. */
	public static Quest questNamed(String name)
	{
		if (name == null)
		{
			return null;
		}
		try
		{
			return Quest.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	/** Whether the page can be read now. */
	public boolean isReadable(ChronicleEntry entry)
	{
		if (entry == null || entry.unlock == null)
		{
			return false;
		}
		ChronicleEntry.ChronicleUnlock unlock = entry.unlock;
		if (unlock.always)
		{
			return true;
		}
		if (unlock.quest != null && questStates.get(unlock.quest) == QuestState.FINISHED)
		{
			return true;
		}
		if (unlock.questBegun != null)
		{
			QuestState state = questStates.get(unlock.questBegun);
			if (state == QuestState.IN_PROGRESS || state == QuestState.FINISHED)
			{
				return true;
			}
		}
		TriggerContext context = memory.get();
		return context != null && context.isChronicled(entry.id);
	}

	/** How many pages of a kind can be read, and how many there are. */
	public int[] tally(String kind)
	{
		int readable = 0;
		List<ChronicleEntry> all = loader.ofKind(kind);
		for (ChronicleEntry entry : all)
		{
			if (isReadable(entry))
			{
				readable++;
			}
		}
		return new int[]{readable, all.size()};
	}

	/** The same over every page. */
	public int[] tally()
	{
		int readable = 0;
		List<ChronicleEntry> all = loader.getEntries();
		for (ChronicleEntry entry : all)
		{
			if (isReadable(entry))
			{
				readable++;
			}
		}
		return new int[]{readable, all.size()};
	}

	/**
	 * An NPC of this name was met: seen close by, or fought. Writes every
	 * page that names it and answers with the pages written for the first
	 * time, so the follower can say so.
	 */
	public List<ChronicleEntry> noteNpc(String npcName)
	{
		List<ChronicleEntry> written = new ArrayList<>();
		if (npcName == null || npcName.isEmpty())
		{
			return written;
		}
		String wanted = npcName.toLowerCase(Locale.ROOT);
		for (ChronicleEntry entry : loader.getEntries())
		{
			if (entry.unlock == null || entry.unlock.npcs == null)
			{
				continue;
			}
			for (String name : entry.unlock.npcs)
			{
				if (name != null && name.toLowerCase(Locale.ROOT).equals(wanted))
				{
					if (write(entry))
					{
						written.add(entry);
					}
					break;
				}
			}
		}
		return written;
	}

	/** The player is standing in this map region. Same contract as {@link #noteNpc}. */
	public List<ChronicleEntry> noteRegion(int regionId)
	{
		List<ChronicleEntry> written = new ArrayList<>();
		for (ChronicleEntry entry : loader.getEntries())
		{
			if (entry.unlock == null || entry.unlock.regions == null
				|| !entry.unlock.regions.contains(regionId))
			{
				continue;
			}
			if (write(entry))
			{
				written.add(entry);
			}
		}
		return written;
	}

	private boolean write(ChronicleEntry entry)
	{
		TriggerContext context = memory.get();
		return context != null && context.noteChronicled(entry.id);
	}
}

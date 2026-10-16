package com.follower.chronicle;

import com.follower.speech.TriggerContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
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
 *
 * <p>And when each page was written. A creature or place page is written the
 * moment it is met; a quest page the moment its quest is seen to begin or
 * end, by comparing what can be read with the last look. Either way the page
 * is dated and placed, and the follower told, so it can say so. The first
 * look of a session only takes stock: a page readable then was written
 * before, perhaps before the book kept dates, and is left undated rather
 * than dated today.
 */
public class ChronicleProgress
{
	private final Client client;
	private final ChronicleLoader loader;
	private final Supplier<TriggerContext> memory;

	/** Quest states as of the last refresh, by enum name. */
	private final Map<String, QuestState> questStates = new HashMap<>();
	private int refreshedOnTick = -1;

	/** Pages readable at the last look, and whether this session has had one. */
	private final Set<String> readableAtLastLook = new HashSet<>();
	private boolean looked;

	/**
	 * Pages met or walked into before this session's first look, and where:
	 * until the quests have been read, a page a quest already opened looks
	 * new, so the first look decides.
	 */
	private final Map<String, String> undecided = new HashMap<>();

	private BiConsumer<ChronicleEntry, Boolean> onWritten = (entry, byQuest) ->
	{
	};
	private Clock clock = Clock.systemDefaultZone();

	/** Whose quests these are; quest pages are dated per account. */
	private Supplier<String> account = () -> null;

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
			for (String quest : entry.gateQuests())
			{
				readQuest(quest);
			}
		}
		// A read that failed stands in as not started; comparing with it
		// would find every finished quest newly finished on the next look.
		if (refreshedOnTick == tick)
		{
			look();
		}
	}

	/**
	 * Told of each page the moment it is written, once, and whether a quest
	 * wrote it (begun or finished) rather than something met or walked into.
	 */
	public void setOnWritten(BiConsumer<ChronicleEntry, Boolean> onWritten)
	{
		this.onWritten = onWritten == null ? (entry, byQuest) ->
		{
		} : onWritten;
	}

	/**
	 * The account playing. The follower's memory belongs to the RuneLite
	 * profile and so to every account on it, but quests belong to each
	 * account, so a quest page's date is kept under the account's key.
	 */
	public void setAccount(Supplier<String> account)
	{
		this.account = account == null ? () -> null : account;
	}

	/** The day as the tests set it. */
	void setClock(Clock clock)
	{
		this.clock = clock;
	}

	/** A new session, or another account: nothing seen yet. */
	public void reset()
	{
		questStates.clear();
		refreshedOnTick = -1;
		readableAtLastLook.clear();
		looked = false;
		undecided.clear();
	}

	/**
	 * What can be read now against the last look: a page readable now and not
	 * then has just been written. The first look of a session takes stock.
	 */
	void look()
	{
		Set<String> now = new HashSet<>();
		for (ChronicleEntry entry : loader.getEntries())
		{
			if (!isReadable(entry))
			{
				continue;
			}
			now.add(entry.id);
			if (looked && !readableAtLastLook.contains(entry.id))
			{
				// Readable now and not then, and not met or walked into: a
				// quest begun or finished since the last look wrote it.
				written(entry, here(), true);
			}
		}
		if (!looked)
		{
			// The quests are read at last: a page met before now is new only
			// if no quest had already opened it.
			for (Map.Entry<String, String> met : undecided.entrySet())
			{
				ChronicleEntry entry = loader.get(met.getKey());
				if (entry != null && !byQuest(entry))
				{
					written(entry, met.getValue(), false);
				}
			}
			undecided.clear();
		}
		readableAtLastLook.clear();
		readableAtLastLook.addAll(now);
		looked = true;
	}

	/** Whether a quest's state alone makes the page readable. */
	private boolean byQuest(ChronicleEntry entry)
	{
		ChronicleEntry.ChronicleUnlock unlock = entry.unlock;
		if (unlock == null)
		{
			return false;
		}
		if (unlock.quest != null && questStates.get(unlock.quest) == QuestState.FINISHED)
		{
			return true;
		}
		QuestState begun = unlock.questBegun == null ? null : questStates.get(unlock.questBegun);
		return begun == QuestState.IN_PROGRESS || begun == QuestState.FINISHED;
	}

	/** The key a quest page's date, or a boss's kill count, is kept under for this account. */
	public String accountKey(String entryId)
	{
		String who = account.get();
		return who == null || who.isEmpty() ? entryId : entryId + "@" + who;
	}

	/**
	 * The epoch day the page was written, or null for one written before the
	 * book kept dates: this account's date for it first, as a quest wrote it,
	 * then the one it was met or walked into on.
	 */
	public Long writtenOn(ChronicleEntry entry)
	{
		TriggerContext context = memory.get();
		if (context == null || entry == null)
		{
			return null;
		}
		Long mine = context.chronicledOn(accountKey(entry.id));
		return mine != null ? mine : context.chronicledOn(entry.id);
	}

	/** Where it was written, or empty, found the same way. */
	public String writtenAt(ChronicleEntry entry)
	{
		TriggerContext context = memory.get();
		if (context == null || entry == null)
		{
			return "";
		}
		String key = accountKey(entry.id);
		return context.chronicledOn(key) != null ? context.chronicledAt(key) : context.chronicledAt(entry.id);
	}

	/**
	 * A page's own number: the follower's counter the page names, or 0 when
	 * it has none or nothing is counted yet. The kills it saw are tallies
	 * and the game's kill counts are records, under keys that never meet,
	 * so whichever holds the key has the number. A kill count is the game's,
	 * so this account's; one kept before counts were per account is the
	 * fallback.
	 */
	public int count(ChronicleEntry entry)
	{
		TriggerContext context = memory.get();
		if (context == null || entry == null || entry.tally == null || entry.tally.of == null)
		{
			return 0;
		}
		int mine = context.getRecord(accountKey(entry.tally.of));
		return Math.max(context.getTally(entry.tally.of), mine > 0 ? mine : context.getRecord(entry.tally.of));
	}

	private String here()
	{
		TriggerContext context = memory.get();
		return context == null ? "" : context.placeName(context.getRegionId());
	}

	private void written(ChronicleEntry entry, String place, boolean byQuest)
	{
		TriggerContext context = memory.get();
		if (context != null)
		{
			context.stampChronicled(byQuest ? accountKey(entry.id) : entry.id,
				LocalDate.now(clock).toEpochDay(), place);
		}
		onWritten.accept(entry, byQuest);
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

	/**
	 * The reward scroll came up with the follower there: it saw this quest
	 * finish. By the name the scroll shows, so a quest with no page counts.
	 */
	public void sawQuestFinish(String questName)
	{
		noteQuestFinish(questName, LocalDate.now(clock).toEpochDay());
	}

	/** Recorded for a quest finished while the follower was parked elsewhere. */
	static final long NOT_THERE = -1L;

	/**
	 * The reward scroll came up with the follower parked elsewhere - a Stay,
	 * a Send, Follow me off - so it did not see the quest finish, and the
	 * page dated that day does not say it did. Chronicle QA4, 16 Oct: the
	 * callbacks said "we" about a quest watched from a bank in another town.
	 */
	public void missedQuestFinish(String questName)
	{
		noteQuestFinish(questName, NOT_THERE);
	}

	private void noteQuestFinish(String questName, long epochDay)
	{
		TriggerContext context = memory.get();
		if (context == null || questName == null)
		{
			return;
		}
		for (Quest quest : Quest.values())
		{
			if (quest.getName().equalsIgnoreCase(questName))
			{
				context.noteQuestSeenFinished(accountKey(quest.name()), epochDay);
				return;
			}
		}
	}

	/**
	 * The day the follower was there for a quest (by constant name): it saw
	 * the quest finish, or wrote its quest page as it did. Null for a quest
	 * done before it came along, which it knows of only from the book, or
	 * one finished while it was parked elsewhere.
	 */
	public Long witnessedOn(String quest)
	{
		TriggerContext context = memory.get();
		if (context == null || quest == null)
		{
			return null;
		}
		Long seen = context.questSeenFinishedOn(accountKey(quest));
		if (seen != null)
		{
			return seen == NOT_THERE ? null : seen;
		}
		for (ChronicleEntry entry : loader.ofKind("quest"))
		{
			if (entry.unlock != null && quest.equals(entry.unlock.quest))
			{
				Long written = writtenOn(entry);
				if (written != null)
				{
					return written;
				}
			}
		}
		return null;
	}

	/** Whether a quest was finished at the last read; what a gated passage waits on. */
	public boolean isDone(String quest)
	{
		return questStates.get(quest) == QuestState.FINISHED;
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
					if (write(entry, here()))
					{
						written.add(entry);
					}
					break;
				}
			}
		}
		return written;
	}

	/** Whether a page waits on meeting an NPC of this name: worth a look at it. */
	public boolean wantsToMeet(String npcName)
	{
		TriggerContext context = memory.get();
		if (npcName == null || npcName.isEmpty() || context == null)
		{
			return false;
		}
		for (ChronicleEntry entry : loader.getEntries())
		{
			if (entry.unlock != null && entry.unlock.npcs != null && !context.isChronicled(entry.id))
			{
				for (String name : entry.unlock.npcs)
				{
					if (npcName.equalsIgnoreCase(name))
					{
						return true;
					}
				}
			}
		}
		return false;
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
			TriggerContext context = memory.get();
			if (write(entry, context == null ? "" : context.placeName(regionId)))
			{
				written.add(entry);
			}
		}
		return written;
	}

	/**
	 * Writes the page into the follower's memory. It is dated, placed and
	 * spoken of only if it could not be read already, as a creature page a
	 * quest opened first could be.
	 */
	private boolean write(ChronicleEntry entry, String place)
	{
		TriggerContext context = memory.get();
		if (context == null)
		{
			return false;
		}
		boolean before = isReadable(entry);
		if (!context.noteChronicled(entry.id))
		{
			return false;
		}
		readableAtLastLook.add(entry.id);
		if (!looked)
		{
			undecided.putIfAbsent(entry.id, place);
		}
		else if (!before)
		{
			written(entry, place, false);
		}
		return true;
	}
}

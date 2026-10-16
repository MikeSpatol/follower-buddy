package com.follower.speech;

import com.follower.follower.FilmSet;
import java.util.function.BiConsumer;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.util.Text;

/**
 * Plays a written {@link Conversation} back, a line at a time: a dialogue
 * box until it is continued, a chat line for its time.
 *
 * <p>In CLICK a box waits for "Click here to continue", as the game's own
 * does. In AUTO each page of a box is continued for the player once it has
 * been up for the script's seconds, or for the time it takes to read at the
 * reading speed setting. A chat line has nothing to click, so it is up for
 * its time in either. A box can be clicked on in either.
 *
 * <p>It never plays over the game's own dialogue: it will not start while
 * one is open, and stops if one opens. Nor over the follower's Talk-to box:
 * it will not start while that is open, and while it plays the plugin
 * leaves Talk-to alone. Anything else that closes its box - a click away,
 * a step, a hit, as with any dialogue - stops it too.
 *
 * <p>Client thread only, as everything the stage does is.
 */
public class ConversationPlayer
{
	/** What it plays on. Every call on the client thread. */
	public interface Stage
	{
		/**
		 * Whether the game's own dialogue - an NPC's, the player's, a choice,
		 * or a message, item or level-up box - is open.
		 */
		boolean gameDialogueOpen();

		/** Whether the follower's own Talk-to box is open. */
		boolean talkToOpen();

		/** Opens the box for a line, with its speaker's head and name. */
		void showBox(Conversation.Line line);

		/** The page of the line's box on show, or -1 once it has closed. */
		int boxPage();

		/** The text of the page on show. */
		String boxPageText();

		/** Whether the box closed by being continued past its last page. */
		boolean boxContinued();

		/** Continues the box a page, as a click would. */
		void continueBox();

		/** Closes the box, as a click away would. */
		void closeBox();

		/** Says a chat line: in the chatbox, and overhead for {@code ms}. */
		void say(Conversation.Line line, long ms);

		/** Takes a chat line still overhead off again. */
		void unsay(Conversation.Line line);

		/** How long a text takes to read, at the reading speed setting. */
		long readingTimeMs(String text);

		/** A word for the film window's status line. */
		void tell(String text);
	}

	private final Stage stage;

	private Conversation playing;
	private int index;

	/** When the line on show, or the page of its box, came up. */
	private long since;

	/** The page of the box last seen, so a new one is timed from when it came up. */
	private int page;

	public ConversationPlayer(Stage stage)
	{
		this.stage = stage;
	}

	public boolean isPlaying()
	{
		return playing != null;
	}

	/**
	 * Starts it from its first line, over any playing.
	 *
	 * @return why it cannot, or null once it has started
	 */
	public String play(Conversation conversation, long now)
	{
		String refused = conversation.refusal();
		if (refused != null)
		{
			return refused;
		}
		if (isPlaying())
		{
			stop(null);
		}
		if (stage.gameDialogueOpen())
		{
			return "Close the game's dialogue first.";
		}
		if (stage.talkToOpen())
		{
			return "Close the follower's Talk-to box first.";
		}
		playing = conversation.copy();
		index = -1;
		next(now);
		return null;
	}

	/** Ends it where it is, and says why, if there is a why. */
	public void stop(String why)
	{
		if (playing == null)
		{
			return;
		}
		Conversation.Line line = playing.lines.get(index);
		playing = null;
		if (line.display == Conversation.Display.BOX)
		{
			if (stage.boxPage() >= 0)
			{
				stage.closeBox();
			}
		}
		else
		{
			stage.unsay(line);
		}
		if (why != null)
		{
			stage.tell(why);
		}
	}

	/** Every client tick: the next page or line when it is time, or the end. */
	public void update(long now)
	{
		if (playing == null)
		{
			return;
		}
		if (stage.gameDialogueOpen())
		{
			stop("Stopped: a game dialogue opened.");
			return;
		}
		Conversation.Line line = playing.lines.get(index);
		if (line.display == Conversation.Display.CHAT)
		{
			if (now - since >= timeFor(Conversation.shown(line)))
			{
				next(now);
			}
			return;
		}
		int shownPage = stage.boxPage();
		if (shownPage < 0)
		{
			if (stage.boxContinued())
			{
				next(now);
			}
			else
			{
				// Nothing to close: it is closed already.
				playing = null;
				stage.tell("Stopped: the box was closed.");
			}
			return;
		}
		if (shownPage != page)
		{
			page = shownPage;
			since = now;
		}
		if (playing.advance == Conversation.Advance.AUTO && now - since >= timeFor(stage.boxPageText()))
		{
			since = now;
			stage.continueBox();
		}
	}

	/**
	 * How long a line or a page is up: the script's seconds when it is
	 * AUTO and has them, and otherwise as long as it takes to read.
	 */
	long timeFor(String text)
	{
		if (playing.advance == Conversation.Advance.AUTO && playing.seconds > 0)
		{
			return Math.round(playing.seconds * 1000);
		}
		return stage.readingTimeMs(text == null ? "" : text);
	}

	private void next(long now)
	{
		index++;
		if (index >= playing.lines.size())
		{
			playing = null;
			stage.tell("The conversation is over.");
			return;
		}
		Conversation.Line line = playing.lines.get(index);
		since = now;
		page = -1;
		stage.tell("Line " + (index + 1) + " of " + playing.lines.size() + ".");
		if (line.display == Conversation.Display.BOX)
		{
			stage.showBox(line);
		}
		else
		{
			stage.say(line, timeFor(Conversation.shown(line)));
		}
	}

	/**
	 * A chat line said as the game shows a public one: "Name: text" in the
	 * chatbox, and the words over the speaker's head. On this client only.
	 * Everything here is a local call - the chatbox's own list, the player's
	 * overhead fields, the follower's drawn overhead - and nothing is typed,
	 * sent, or seen by anybody else.
	 *
	 * <p>The chatbox and the player's overhead read the game's markup, so an
	 * angle bracket in the line is escaped there; the follower's overhead is
	 * drawn glyph for glyph and takes the line as it is.
	 *
	 * @param followerOverhead the follower's overhead: the words, and for how long
	 */
	public static void sayInChat(Client client, FilmSet film, String followerName,
		BiConsumer<String, Long> followerOverhead, Conversation.Line line, long ms)
	{
		String text = Conversation.shown(line);
		String marked = inChat(line);
		if (line.speaker == Conversation.Speaker.FOLLOWER)
		{
			followerOverhead.accept(text, ms);
			client.addChatMessage(ChatMessageType.PUBLICCHAT, followerName, marked, null);
			return;
		}
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}
		film.playerSays(me, marked, ms);
		client.addChatMessage(ChatMessageType.PUBLICCHAT, me.getName(), marked, null);
	}

	/** A chat line's words as {@link #sayInChat} puts them in the chatbox. */
	public static String inChat(Conversation.Line line)
	{
		return Text.escapeJagex(Conversation.shown(line));
	}
}

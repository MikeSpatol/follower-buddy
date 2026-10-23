// public:file posing
package com.follower.speech;

import com.follower.follower.FilmSet;
import com.follower.follower.FollowerEntity;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A written conversation played back: a box waits for its click, or in AUTO
 * for its time a page at a time; a chat line is up for its time; it never
 * starts over the game's own dialogue or the follower's Talk-to, and stops
 * when one opens or its box is closed. A chat line is said with local calls
 * only: nothing reaches the server.
 */
public class ConversationPlayerTest
{
	/**
	 * The dialogue box and the chatbox, as the player sees them. A '|' in a
	 * box's text is a page break, so paging can be stepped without fonts.
	 */
	private static final class FakeStage implements ConversationPlayer.Stage
	{
		boolean gameDialogue;
		boolean talkTo;
		String[] pages;
		int page = -1;
		boolean continued;
		final List<String> shown = new ArrayList<>();
		final List<String> told = new ArrayList<>();

		/** A click on "Click here to continue", once its tick has landed. */
		void click()
		{
			continueBox();
		}

		String lastTold()
		{
			return told.isEmpty() ? null : told.get(told.size() - 1);
		}

		@Override
		public boolean gameDialogueOpen()
		{
			return gameDialogue;
		}

		@Override
		public boolean talkToOpen()
		{
			return talkTo;
		}

		@Override
		public void showBox(Conversation.Line line)
		{
			pages = Conversation.shown(line).split("\\|");
			page = 0;
			continued = false;
			shown.add("box " + line.speaker + " " + line.expression.name() + ": " + Conversation.shown(line));
		}

		@Override
		public int boxPage()
		{
			return page;
		}

		@Override
		public String boxPageText()
		{
			return page >= 0 ? pages[page] : "";
		}

		@Override
		public boolean boxContinued()
		{
			return continued;
		}

		@Override
		public void continueBox()
		{
			if (page < 0)
			{
				return;
			}
			page++;
			if (page >= pages.length)
			{
				page = -1;
				continued = true;
			}
		}

		@Override
		public void closeBox()
		{
			page = -1;
			continued = false;
			shown.add("closed");
		}

		@Override
		public void say(Conversation.Line line, long ms)
		{
			shown.add("chat " + line.speaker + " for " + ms + ": " + Conversation.shown(line));
		}

		@Override
		public void unsay(Conversation.Line line)
		{
			shown.add("unsaid");
		}

		@Override
		public long readingTimeMs(String text)
		{
			// A tenth of a second a character, so times are easy to read.
			return text.length() * 100L;
		}

		@Override
		public void tell(String text)
		{
			told.add(text);
		}
	}

	private final FakeStage stage = new FakeStage();
	private final ConversationPlayer player = new ConversationPlayer(stage);

	private static Conversation script(Conversation.Advance advance, double seconds, Conversation.Line... lines)
	{
		Conversation script = new Conversation();
		script.advance = advance;
		script.seconds = seconds;
		script.lines.addAll(Arrays.asList(lines));
		return script;
	}

	private static Conversation.Line box(Conversation.Speaker who, String text)
	{
		return new Conversation.Line(who, Conversation.Display.BOX, Conversation.Expression.HAPPY, text);
	}

	private static Conversation.Line chat(Conversation.Speaker who, String text)
	{
		return new Conversation.Line(who, Conversation.Display.CHAT, Conversation.Expression.NEUTRAL, text);
	}

	private static final Conversation.Speaker YOU = Conversation.Speaker.YOU;
	private static final Conversation.Speaker IT = Conversation.Speaker.FOLLOWER;

	@Test
	public void inClickABoxWaitsForItsClickHoweverLong()
	{
		assertNull(player.play(script(Conversation.Advance.CLICK, 0,
			box(YOU, "Hello."), box(IT, "Hi there.")), 0));
		assertEquals(Arrays.asList("box YOU HAPPY: Hello."), stage.shown);
		assertEquals("Line 1 of 2.", stage.lastTold());

		player.update(10 * 60_000);
		assertEquals("still the first, ten minutes on", 1, stage.shown.size());

		stage.click();
		player.update(10 * 60_000 + 20);
		assertEquals("box FOLLOWER HAPPY: Hi there.", stage.shown.get(1));
		assertEquals("Line 2 of 2.", stage.lastTold());

		stage.click();
		player.update(10 * 60_000 + 40);
		assertFalse(player.isPlaying());
		assertEquals("The scene is over.", stage.lastTold());
	}

	@Test
	public void aBoxOfManyPagesIsClickedThroughPageByPage()
	{
		player.play(script(Conversation.Advance.CLICK, 0, box(YOU, "One|Two|Three"), chat(IT, "Done.")), 0);
		stage.click();
		player.update(20);
		stage.click();
		player.update(40);
		assertEquals("still in the box, on its last page", 1, stage.shown.size());
		stage.click();
		player.update(60);
		assertEquals("chat FOLLOWER for 500: Done.", stage.shown.get(1));
	}

	@Test
	public void aChatLineIsUpForItsTimeEvenInClick()
	{
		player.play(script(Conversation.Advance.CLICK, 0, chat(YOU, "Hey."), box(IT, "Hm?")), 1000);
		assertEquals("chat YOU for 400: Hey.", stage.shown.get(0));
		player.update(1399);
		assertEquals(1, stage.shown.size());
		player.update(1400);
		assertEquals("box FOLLOWER HAPPY: Hm?", stage.shown.get(1));
	}

	@Test
	public void inAutoEachPageIsContinuedOnceItHasBeenReadAtTheReadingSpeed()
	{
		player.play(script(Conversation.Advance.AUTO, 0, box(IT, "abc|abcdef"), chat(YOU, "Ok.")), 0);
		player.update(0);
		player.update(299);
		assertEquals("first page, not yet read", 0, stage.page);
		player.update(300);
		assertEquals(1, stage.page);
		player.update(320);
		player.update(919);
		assertEquals("the second page is timed from when it came up", 1, stage.page);
		player.update(920);
		assertTrue(stage.continued);
		player.update(940);
		assertEquals("chat YOU for 300: Ok.", stage.shown.get(1));
		player.update(1239);
		assertTrue(player.isPlaying());
		player.update(1240);
		assertFalse(player.isPlaying());
		assertEquals("The scene is over.", stage.lastTold());
	}

	@Test
	public void inAutoTheScriptsSecondsAreEveryLinesAndEveryPagesTime()
	{
		player.play(script(Conversation.Advance.AUTO, 2.5, chat(IT, "A long line, read slowly."),
			box(YOU, "x|y")), 0);
		assertEquals("chat FOLLOWER for 2500: A long line, read slowly.", stage.shown.get(0));
		player.update(2499);
		assertEquals(1, stage.shown.size());
		player.update(2500);
		assertEquals(2, stage.shown.size());
		player.update(2520);
		player.update(5019);
		assertEquals(0, stage.page);
		player.update(5020);
		assertEquals(1, stage.page);
	}

	@Test
	public void aClickInAutoMovesOnAtOnce()
	{
		player.play(script(Conversation.Advance.AUTO, 30, box(YOU, "Skip me."), box(IT, "Next.")), 0);
		stage.click();
		player.update(20);
		assertEquals(2, stage.shown.size());
	}

	@Test
	public void itWillNotStartOverTheGamesOwnDialogue()
	{
		stage.gameDialogue = true;
		assertEquals("Close the game's dialogue first.",
			player.play(script(Conversation.Advance.CLICK, 0, box(YOU, "Hello.")), 0));
		assertFalse(player.isPlaying());
		assertTrue("nothing was shown", stage.shown.isEmpty());
	}

	@Test
	public void norOverTheFollowersTalkTo()
	{
		stage.talkTo = true;
		assertEquals("Close the follower's Talk-to box first.",
			player.play(script(Conversation.Advance.CLICK, 0, box(YOU, "Hello.")), 0));
		assertFalse(player.isPlaying());
		assertTrue(stage.shown.isEmpty());
	}

	@Test
	public void aGameDialogueOpeningStopsItAndTakesItsBoxAway()
	{
		player.play(script(Conversation.Advance.CLICK, 0, box(YOU, "Hello."), box(IT, "Hi.")), 0);
		stage.gameDialogue = true;
		player.update(20);
		assertFalse(player.isPlaying());
		assertEquals("closed", stage.shown.get(stage.shown.size() - 1));
		assertEquals("Stopped: a game dialogue opened.", stage.lastTold());
	}

	@Test
	public void itsBoxClosedAnyOtherWayStopsIt()
	{
		// A click away, a step, a hit: the box is gone, and so is the rest.
		player.play(script(Conversation.Advance.CLICK, 0, box(YOU, "Hello."), box(IT, "Hi.")), 0);
		stage.closeBox();
		player.update(20);
		assertFalse(player.isPlaying());
		assertEquals("Stopped: the box was closed.", stage.lastTold());
		assertEquals("the second line never showed", 2, stage.shown.size());
	}

	@Test
	public void stopClosesTheBoxOrTakesTheLineDown()
	{
		player.play(script(Conversation.Advance.CLICK, 0, box(YOU, "Hello.")), 0);
		player.stop("Stopped.");
		assertFalse(player.isPlaying());
		assertEquals("closed", stage.shown.get(1));
		assertEquals("Stopped.", stage.lastTold());

		player.play(script(Conversation.Advance.CLICK, 0, chat(IT, "Hello.")), 0);
		player.stop(null);
		assertEquals("unsaid", stage.shown.get(stage.shown.size() - 1));
		assertEquals("a stop with no why says nothing", "Line 1 of 1.", stage.lastTold());

		player.stop("Stopped.");
		assertEquals("stopping nothing says nothing", "Line 1 of 1.", stage.lastTold());
	}

	@Test
	public void playAgainStartsFromTheTop()
	{
		Conversation two = script(Conversation.Advance.CLICK, 0, box(YOU, "One."), box(IT, "Two."));
		player.play(two, 0);
		stage.click();
		player.update(20);
		player.play(two, 40);
		assertEquals("box YOU HAPPY: One.", stage.shown.get(stage.shown.size() - 1));
		assertEquals("Line 1 of 2.", stage.lastTold());
	}

	@Test
	public void whatCannotBePlayedIsRefusedBeforeAnythingShows()
	{
		assertEquals("Add a line first.", player.play(script(Conversation.Advance.CLICK, 0), 0));
		assertEquals("Line 2 has no text.",
			player.play(script(Conversation.Advance.CLICK, 0, box(YOU, "Hi."), chat(IT, " ")), 0));
		assertTrue(stage.shown.isEmpty());
	}

	@Test
	public void theScriptIsCopiedSoEditingItWhilePlayingChangesNothing()
	{
		Conversation editing = script(Conversation.Advance.CLICK, 0, box(YOU, "One."), box(IT, "Two."));
		player.play(editing, 0);
		editing.lines.get(1).text = "Changed.";
		stage.click();
		player.update(20);
		assertEquals("box FOLLOWER HAPPY: Two.", stage.shown.get(1));
	}

	// ------------------------------------------------------------ the chat line

	/** Every call made on the client and the player, by name. */
	private final List<String> calls = new ArrayList<>();
	private final List<Object[]> chatMessages = new ArrayList<>();

	private Client recordingClient()
	{
		Player me = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
			(proxy, method, args) ->
			{
				calls.add("player." + method.getName());
				if (method.getName().equals("getName"))
				{
					return "Sir\u00A0Zeebos";
				}
				return nothing(method.getReturnType());
			});
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				calls.add("client." + method.getName());
				if (method.getName().equals("getLocalPlayer"))
				{
					return me;
				}
				if (method.getName().equals("addChatMessage"))
				{
					chatMessages.add(args);
				}
				return nothing(method.getReturnType());
			});
	}

	private static Object nothing(Class<?> type)
	{
		return type == int.class ? 0 : type == long.class ? 0L : type == boolean.class ? false : null;
	}

	/**
	 * The calls a chat line may make, all of them this client's own: the
	 * chatbox's message list, the player's overhead fields, and reading who
	 * the player is. Nothing that types into the chat, runs a script or
	 * sends a menu action - the ways a plugin can reach the server.
	 */
	private static final Set<String> LOCAL = new HashSet<>(Arrays.asList(
		"client.getLocalPlayer", "client.addChatMessage",
		"player.getName", "player.setOverheadText", "player.setOverheadCycle"));

	@Test
	public void aChatLineIsSaidWithLocalCallsOnlyAndNothingIsSent()
	{
		Client client = recordingClient();
		FilmSet film = new FilmSet(new FollowerEntity(null, null));
		List<String> overhead = new ArrayList<>();

		ConversationPlayer.sayInChat(client, film, "Bob", (text, ms) -> overhead.add(text + " for " + ms),
			chat(IT, "Hello <there>."), 3000);
		ConversationPlayer.sayInChat(client, film, "Bob", (text, ms) -> overhead.add(text + " for " + ms),
			chat(YOU, "Hi\u00A0Bob <3"), 3000);

		assertTrue("only local calls: " + calls, LOCAL.containsAll(calls));
		assertTrue(calls.contains("player.setOverheadText"));
		assertEquals(2, chatMessages.size());

		// The follower's: its own drawn overhead takes the words as they are,
		// the chatbox reads markup so the brackets are escaped.
		assertEquals(Arrays.asList("Hello <there>. for 3000"), overhead);
		assertEquals(ChatMessageType.PUBLICCHAT, chatMessages.get(0)[0]);
		assertEquals("Bob", chatMessages.get(0)[1]);
		assertEquals("Hello <lt>there<gt>.", chatMessages.get(0)[2]);

		// The player's, under the player's own name.
		assertEquals(ChatMessageType.PUBLICCHAT, chatMessages.get(1)[0]);
		assertEquals("Sir\u00A0Zeebos", chatMessages.get(1)[1]);
		assertEquals("Hi Bob <lt>3", chatMessages.get(1)[2]);
	}
}

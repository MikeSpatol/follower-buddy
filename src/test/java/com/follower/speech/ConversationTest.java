package com.follower.speech;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.AnimationID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A conversation as written in the film window: its lines and their
 * choices, the text as the game will show it, and the faces a dialogue
 * box can speak with.
 */
public class ConversationTest
{
	private static Conversation.Line box(String text)
	{
		return new Conversation.Line(Conversation.Speaker.YOU, Conversation.Display.BOX,
			Conversation.Expression.NEUTRAL, text);
	}

	private static Conversation.Line chat(String text)
	{
		return new Conversation.Line(Conversation.Speaker.FOLLOWER, Conversation.Display.CHAT,
			Conversation.Expression.NEUTRAL, text);
	}

	@Test
	public void aNewLineIsTheDefaultsAndACopyIsItsOwn()
	{
		Conversation.Line line = new Conversation.Line();
		assertEquals(Conversation.Speaker.YOU, line.speaker);
		assertEquals(Conversation.Display.BOX, line.display);
		assertEquals(Conversation.Expression.NEUTRAL, line.expression);
		assertEquals("", line.text);

		Conversation script = new Conversation();
		script.name = "Intro";
		script.advance = Conversation.Advance.AUTO;
		script.seconds = 2.5;
		script.lines.add(box("Hello."));
		Conversation copy = script.copy();
		copy.lines.get(0).text = "Changed.";
		copy.lines.add(chat("More."));
		assertEquals("Hello.", script.lines.get(0).text);
		assertEquals(1, script.lines.size());
		assertNotSame(script.lines.get(0), copy.lines.get(0));
		assertEquals("Intro", copy.name);
		assertEquals(Conversation.Advance.AUTO, copy.advance);
		assertEquals(2.5, copy.seconds, 0);
	}

	@Test
	public void nothingToSayIsRefusedAndSaysWhichLine()
	{
		Conversation script = new Conversation();
		assertEquals("Add a line first.", script.refusal());
		script.lines.add(box("Hello."));
		assertNull(script.refusal());
		script.lines.add(chat("   \n\t "));
		assertEquals("Line 2 has no text.", script.refusal());
	}

	@Test
	public void theFollowerHasToBeOutOnlyWhenItSpeaks()
	{
		Conversation script = new Conversation();
		script.lines.add(box("Just me."));
		assertFalse(script.followerSpeaks());
		script.lines.add(chat("And me."));
		assertTrue(script.followerSpeaks());
	}

	@Test
	public void anyTextGoesInAndComesOutAsTheGameDrawsIt()
	{
		// A no-break space is a space, and a character with no glyph is a '?'.
		assertEquals("Sir Zeebos", Conversation.shown(box("Sir\u00A0Zeebos")));
		assertEquals("Hi ?? there", Conversation.shown(box("Hi \u4F60\u597D there")));
		// Latin-1 letters and the euro are drawn, and brackets are only text.
		assertEquals("Caf\u00E9 \u20AC5 <b>", Conversation.shown(box("Caf\u00E9 \u20AC5 <b>")));
		// The client finds a slot for curly quotes and dashes, but the
		// dialogue font's are blank, so a pasted one is written plain...
		assertEquals("Wait - \"what\"... it's", Conversation.shown(box("Wait \u2014 \u201Cwhat\u201D\u2026 it\u2019s")));
		assertEquals("a - b * c", Conversation.shown(box("a \u2013 b \u2022 c")));
		// ...and one with no plain form is a '?', as any other with no glyph.
		assertEquals("Brand?", Conversation.shown(box("Brand\u2122")));
		// Tabs are spaces, other control characters go, and the ends are trimmed.
		assertEquals("a b", Conversation.shown(box("  a\tb\u0007 ")));
	}

	@Test
	public void aBoxKeepsItsLineBreaksAndAChatLineIsOneLine()
	{
		assertEquals("One\nTwo\nThree", Conversation.shown(box("One\r\nTwo\rThree")));
		assertEquals("One Two", Conversation.shown(chat("One\nTwo")));
	}

	@Test
	public void aHandEditedFileIsTidiedIntoSomethingPlayable()
	{
		Conversation script = new Conversation();
		script.name = null;
		script.advance = null;
		script.seconds = -3;
		Conversation.Line missing = new Conversation.Line();
		missing.speaker = null;
		missing.display = null;
		missing.expression = null;
		missing.text = null;
		script.lines.add(missing);
		script.lines.add(null);
		script.tidy();
		assertEquals("", script.name);
		assertEquals(Conversation.Advance.CLICK, script.advance);
		assertEquals(0, script.seconds, 0);
		assertEquals(1, script.lines.size());
		Conversation.Line line = script.lines.get(0);
		assertEquals(Conversation.Speaker.YOU, line.speaker);
		assertEquals(Conversation.Display.BOX, line.display);
		assertEquals(Conversation.Expression.NEUTRAL, line.expression);
		assertEquals("", line.text);
	}

	/** Each face and the gameval name of its four animations. */
	private static final Map<Conversation.Expression, String> GAMEVAL = new LinkedHashMap<>();

	static
	{
		GAMEVAL.put(Conversation.Expression.NEUTRAL, "CHATNEU");
		GAMEVAL.put(Conversation.Expression.HAPPY, "CHATHAP");
		GAMEVAL.put(Conversation.Expression.LAUGHING, "CHATLAUGH");
		GAMEVAL.put(Conversation.Expression.SAD, "CHATSAD");
		GAMEVAL.put(Conversation.Expression.ANGRY, "CHATANG");
		GAMEVAL.put(Conversation.Expression.CONFUSED, "CHATCON");
		GAMEVAL.put(Conversation.Expression.THINKING, "CHATQUIZ");
		GAMEVAL.put(Conversation.Expression.WORRIED, "CHATSCARED");
		GAMEVAL.put(Conversation.Expression.SHOCKED, "CHATSHOCK");
		GAMEVAL.put(Conversation.Expression.SHIFTY, "CHATSHIFTY");
		GAMEVAL.put(Conversation.Expression.BORED, "CHATBORED");
		GAMEVAL.put(Conversation.Expression.DRUNK, "CHATDRUNK");
	}

	@Test
	public void everyFaceIsTheGamesOwnChatheadAnimationForOneToFourLines() throws ReflectiveOperationException
	{
		// Read from gameval, not typed twice: a face whose ids drifted from
		// the game's names would play some other head's animation, or none.
		assertEquals("every face is checked", Conversation.Expression.values().length, GAMEVAL.size());
		Set<Integer> seen = new HashSet<>();
		for (Map.Entry<Conversation.Expression, String> face : GAMEVAL.entrySet())
		{
			int[] ids = face.getKey().animations();
			assertEquals(4, ids.length);
			for (int lines = 1; lines <= 4; lines++)
			{
				Field named = AnimationID.class.getField(face.getValue() + lines);
				assertEquals(face.getKey() + " for " + lines + " lines", named.getInt(null), ids[lines - 1]);
				assertTrue("each id once", seen.add(ids[lines - 1]));
			}
		}
		// The talk loop the follower's head was animated with all along.
		assertEquals(588, Conversation.Expression.NEUTRAL.animations()[0]);
		// Talk-to's own box plays it too, at the length for the lines on show.
		for (int lines = 1; lines <= 4; lines++)
		{
			assertEquals(Conversation.Expression.NEUTRAL.animations()[lines - 1],
				FollowerDialog.talkClip(null, 0, lines));
		}
	}

	@Test
	public void anNpcsChatheadNeverPosesOrFramesTheFollowersHead()
	{
		FollowerDialog dialog = new FollowerDialog(null, null, null, null, null, null, null, null);
		int turn = dialog.getNpcTurn(), pitch = dialog.getHeadPitch(), zoom = dialog.getHeadZoom();

		// A troll's talk and an angry human's scowl: neither is kept, and
		// neither one's framing is taken.
		dialog.adoptNpcHead(AnimationID.MY2ARM_TROLL_CHATHEAD_TALK_BASIC, 100, 200, 900);
		dialog.adoptNpcHead(AnimationID.CHATANG1, 100, 200, 900);
		assertEquals(0, dialog.getTalkAnimationId());
		assertEquals(turn, dialog.getNpcTurn());
		assertEquals(pitch, dialog.getHeadPitch());
		assertEquals(zoom, dialog.getHeadZoom());
		for (int lines = 1; lines <= 4; lines++)
		{
			assertEquals(Conversation.Expression.NEUTRAL.animations()[lines - 1],
				FollowerDialog.talkClip(null, dialog.getTalkAnimationId(), lines));
		}

		// A human head's neutral talk lends its framing, still not its clip.
		dialog.adoptNpcHead(AnimationID.CHATNEU2, 1882 + 2048, 64, 796);
		assertEquals(1882, dialog.getNpcTurn());
		assertEquals(64, dialog.getHeadPitch());
		assertEquals(796, dialog.getHeadZoom());
		assertEquals(0, dialog.getTalkAnimationId());

		// A scripted face and ::follower head talk still win.
		int[] happy = Conversation.Expression.HAPPY.animations();
		assertEquals(happy[2], FollowerDialog.talkClip(happy, 0, 3));
		assertEquals(AnimationID.CHATANG1, FollowerDialog.talkClip(null, AnimationID.CHATANG1, 2));
	}

	@Test
	public void theFacesThatMoveOtherHeadsAreLeftOut()
	{
		// Checked against the cache: these move the goblin, ent, skull and
		// hand skeletons, not the human chathead's 82.
		Set<Integer> offered = new HashSet<>();
		for (Conversation.Expression face : Conversation.Expression.values())
		{
			for (int id : face.animations())
			{
				offered.add(id);
			}
		}
		for (int other : new int[]{AnimationID.CHATGOBLIN1, AnimationID.CHATENT1, AnimationID.CHATSKULL1,
			AnimationID.CHATHAND1})
		{
			for (int i = 0; i < 4; i++)
			{
				assertFalse(String.valueOf(other + i), offered.contains(other + i));
			}
		}
	}
}

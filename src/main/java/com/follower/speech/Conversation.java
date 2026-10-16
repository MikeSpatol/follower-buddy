package com.follower.speech;

import java.util.ArrayList;
import java.util.List;

/**
 * A conversation written in the film window: lines said in turn by the
 * player or the follower, each in a dialogue box or as a chat line, played
 * back on this client only for a recording.
 *
 * <p>Plain fields, so the saved file is the object as Gson writes it. A
 * file edited by hand can leave any of them out, and {@link #tidy()} puts
 * back what a line cannot be played without.
 */
public final class Conversation
{
	/** Who says a line. */
	public enum Speaker
	{
		YOU,
		FOLLOWER
	}

	/** How a line is shown. */
	public enum Display
	{
		/** The game's dialogue box over the chatbox, with the speaker's head. */
		BOX,
		/** A public chat line in the chatbox, and the same words overhead. */
		CHAT
	}

	/**
	 * A face for a dialogue box: the game's own chathead animations, each
	 * in the four lengths the game picks from by how many lines the box
	 * holds. Every id is gameval {@code AnimationID} in RuneLite 1.13.1 -
	 * CHATNEU1 to CHATNEU4 and so on - and every one moves skeleton 82, the
	 * human chathead's, the same as 588, the talk loop the follower's head
	 * was already animated with (checked against the game cache, 15 Oct).
	 * The goblin, ent, skull and hand sets in the same block move other
	 * skeletons and are left out.
	 */
	public enum Expression
	{
		NEUTRAL("Neutral", 588),
		HAPPY("Happy", 567),
		LAUGHING("Laughing", 605),
		SAD("Sad", 610),
		ANGRY("Angry", 614),
		CONFUSED("Confused", 575),
		THINKING("Thinking", 554),
		WORRIED("Worried", 596),
		SHOCKED("Shocked", 571),
		SHIFTY("Shifty", 592),
		BORED("Bored", 562),
		DRUNK("Drunk", 600);

		public final String label;

		/** The one-line animation; the two-, three- and four-line ones follow it. */
		public final int first;

		Expression(String label, int first)
		{
			this.label = label;
			this.first = first;
		}

		/** The four animations, for a box of one to four lines. */
		public int[] animations()
		{
			return new int[]{first, first + 1, first + 2, first + 3};
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/** Advance on a click, as the game does, or on a timer, for recording. */
	public enum Advance
	{
		CLICK,
		AUTO
	}

	/** One line of the conversation. */
	public static final class Line
	{
		public Speaker speaker = Speaker.YOU;
		public Display display = Display.BOX;
		public Expression expression = Expression.NEUTRAL;
		public String text = "";

		public Line()
		{
		}

		public Line(Speaker speaker, Display display, Expression expression, String text)
		{
			this.speaker = speaker;
			this.display = display;
			this.expression = expression;
			this.text = text;
		}

		public Line copy()
		{
			return new Line(speaker, display, expression, text);
		}
	}

	public String name = "";
	public Advance advance = Advance.CLICK;

	/**
	 * Seconds each line or page is up for when the advance is AUTO; 0 for
	 * the time the line takes to read at the reading speed setting.
	 */
	public double seconds;

	public List<Line> lines = new ArrayList<>();

	/** A copy whose lines can be changed without touching these. */
	public Conversation copy()
	{
		Conversation out = new Conversation();
		out.name = name;
		out.advance = advance;
		out.seconds = seconds;
		for (Line line : lines)
		{
			out.lines.add(line.copy());
		}
		return out;
	}

	/**
	 * What a file left out put back: a missing choice reads as the default,
	 * a missing line is dropped, and a time below nothing is no time.
	 */
	public Conversation tidy()
	{
		if (name == null)
		{
			name = "";
		}
		if (advance == null)
		{
			advance = Advance.CLICK;
		}
		if (!(seconds > 0))
		{
			seconds = 0;
		}
		List<Line> kept = new ArrayList<>();
		if (lines != null)
		{
			for (Line line : lines)
			{
				if (line == null)
				{
					continue;
				}
				if (line.speaker == null)
				{
					line.speaker = Speaker.YOU;
				}
				if (line.display == null)
				{
					line.display = Display.BOX;
				}
				if (line.expression == null)
				{
					line.expression = Expression.NEUTRAL;
				}
				if (line.text == null)
				{
					line.text = "";
				}
				kept.add(line);
			}
		}
		lines = kept;
		return this;
	}

	/** Whether the follower says any of it, so it has to be out. */
	public boolean followerSpeaks()
	{
		for (Line line : lines)
		{
			if (line.speaker == Speaker.FOLLOWER)
			{
				return true;
			}
		}
		return false;
	}

	/** Why it cannot be played, or null. */
	public String refusal()
	{
		if (lines.isEmpty())
		{
			return "Add a line first.";
		}
		for (int i = 0; i < lines.size(); i++)
		{
			if (shown(lines.get(i)).trim().isEmpty())
			{
				return "Line " + (i + 1) + " has no text.";
			}
		}
		return null;
	}

	/**
	 * A line's text as it is shown. The text is the player's own, so
	 * anything goes in, and what the game's fonts cannot draw comes out the
	 * way the client draws it ({@link com.follower.ui.GameFont#drawable}): a
	 * no-break space as a space, and a character the fonts have no glyph
	 * for as a '?'. A box keeps the line breaks typed into it; a chat line,
	 * which is one line in the game, has each as a space.
	 *
	 * <p>One step before that: the client finds a slot for curly quotes,
	 * dashes and the like, but in the dialogue font every one of those slots
	 * is blank but the euro's (fonts.json, cache dump), so a pasted dash was
	 * drawn as a gap. The common ones are written plain instead, and the
	 * rest of those slots are a '?' like any other character with no glyph.
	 */
	public static String shown(Line line)
	{
		String text = line.text == null ? "" : line.text.replace("\r\n", "\n").replace('\r', '\n');
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (c == '\n')
			{
				out.append(line.display == Display.BOX ? '\n' : ' ');
			}
			else if (c == '\t')
			{
				out.append(' ');
			}
			else if (c >= 0x20 && c != 0x7F && !Character.isISOControl(c))
			{
				out.append(plain(c));
			}
		}
		// A run of blank lines is one: more made a box page with nothing on it.
		String drawable = com.follower.ui.GameFont.drawable(out.toString().replaceAll("\n\\s*\n", "\n\n").trim());
		StringBuilder shown = new StringBuilder(drawable.length());
		for (int i = 0; i < drawable.length(); i++)
		{
			char c = drawable.charAt(i);
			// What drawable kept above 0xFF has a slot among the blank ones.
			shown.append(c > 0xFF && c != '\u20AC' ? '?' : c);
		}
		return shown.toString();
	}

	/** Pasted punctuation as it is typed on a keyboard. */
	private static String plain(char c)
	{
		switch (c)
		{
			case '\u2018':
			case '\u2019':
			case '\u201A':
				return "'";
			case '\u201C':
			case '\u201D':
			case '\u201E':
				return "\"";
			case '\u2013':
			case '\u2014':
				return "-";
			case '\u2026':
				return "...";
			case '\u2022':
				return "*";
			default:
				return String.valueOf(c);
		}
	}
}

package com.follower.speech;

import com.follower.Resources;
import java.util.Locale;
import java.util.Set;

/**
 * Numbers the way a scribe writes them in a sentence.
 *
 * <p>A line that says "41 Vorkath" is a counter with a face; one that says
 * "your forty-first Vorkath" is somebody keeping count. The same goes for
 * time: "eleven days since the last one" is a memory, "15840 minutes" is a
 * log. Everything here is words up to where words stop reading naturally,
 * and digits after.
 */
public final class Words
{
	private static final String[] ONES = Resources.list("ones");

	private static final String[] TENS = Resources.list("tens");

	private static final String[] ORDINAL_ONES = Resources.list("ordinalOnes");

	private static final String[] ORDINAL_TENS = Resources.list("ordinalTens");

	/** Vowel letters said with a consonant: a unicorn, a one-eyed rat, a useful thing. */
	private static final String[] SAID_WITH_A = Resources.list("saidWithA");

	/** And the h that is not said: an hour, an honest mistake. */
	private static final String[] SAID_WITH_AN = Resources.list("saidWithAn");

	/** Things had by the heap, not the piece: soft clay, never a soft clay. */
	private static final Set<String> HEAPS = Resources.words("heaps");

	/** Endings of words that end in s and still mean one: Cerberus, a Nylocas Hagios, a Cyclops. */
	private static final String[] ONE_DESPITE_THE_S = Resources.list("oneDespiteTheS");

	/**
	 * Bosses whose name is who they are, not what: Vorkath, never the
	 * Vorkath. The Corporeal Beast and the Leviathan are kinds of thing,
	 * and keep their article.
	 */
	private static final Set<String> PERSONAL_NAMES = Resources.words("personalNames");

	private Words()
	{
	}

	/** Whether the name is a boss's own name, which takes no article: Vorkath, General Graardor. */
	public static boolean isPersonalName(String name)
	{
		return name != null && PERSONAL_NAMES.contains(name.trim().toLowerCase(Locale.ROOT));
	}

	/** One to ninety-nine in words; anything else as digits. */
	public static String number(int n)
	{
		if (n < 0 || n >= 100)
		{
			return Integer.toString(n);
		}
		if (n < 20)
		{
			return ONES[n];
		}
		return n % 10 == 0 ? TENS[n / 10] : TENS[n / 10] + "-" + ONES[n % 10];
	}

	/**
	 * "first", "forty-first", and "101st" past where the words stop. A
	 * hundredth or a thousandth is worth its word, being the kind of count
	 * that gets said out loud.
	 */
	public static String ordinal(int n)
	{
		if (n < 0)
		{
			return Integer.toString(n);
		}
		if (n < 20)
		{
			return ORDINAL_ONES[n];
		}
		if (n < 100)
		{
			return n % 10 == 0 ? ORDINAL_TENS[n / 10] : TENS[n / 10] + "-" + ORDINAL_ONES[n % 10];
		}
		if (n == 100)
		{
			return "hundredth";
		}
		if (n == 1000)
		{
			return "thousandth";
		}
		int last = n % 100;
		String suffix = "th";
		if (last < 11 || last > 13)
		{
			switch (n % 10)
			{
				case 1:
					suffix = "st";
					break;
				case 2:
					suffix = "nd";
					break;
				case 3:
					suffix = "rd";
					break;
				default:
					break;
			}
		}
		return n + suffix;
	}

	/**
	 * A stretch of time as somebody would say it: "a minute", "forty
	 * minutes", "an hour", "eleven days", "three weeks", "a year". Never more
	 * precise than the unit it picks, because "eleven days" is the memory and
	 * "eleven days and four hours" is the log.
	 */
	public static String duration(long ms)
	{
		long minutes = Math.max(0, ms) / 60_000L;
		if (minutes < 2)
		{
			return "a minute";
		}
		if (minutes < 60)
		{
			return number((int) minutes) + " minutes";
		}
		long hours = minutes / 60;
		if (hours < 2)
		{
			return "an hour";
		}
		if (hours < 36)
		{
			return number((int) hours) + " hours";
		}
		long days = hours / 24;
		if (days < 2)
		{
			return "a day";
		}
		if (days < 14)
		{
			return number((int) days) + " days";
		}
		if (days < 56)
		{
			return number((int) (days / 7)) + " weeks";
		}
		if (days < 365)
		{
			long months = days / 30;
			return months < 2 ? "a month" : number((int) months) + " months";
		}
		long years = days / 365;
		return years < 2 ? "a year" : number((int) years) + " years";
	}

	/**
	 * "a" or "an", by how the name sounds said aloud: an Eclectic impling, a
	 * unicorn, an hour, an 80, an 18. The vowel letters and a few known
	 * exceptions, not a pronouncing dictionary.
	 */
	public static String article(String name)
	{
		String s = name.trim().toLowerCase(Locale.ROOT);
		if (s.isEmpty())
		{
			return "a";
		}
		char first = s.charAt(0);
		if (Character.isDigit(first))
		{
			String digits = s.replaceAll("\\D.*", "");
			return first == '8' || digits.equals("11") || digits.equals("18") ? "an" : "a";
		}
		for (String h : SAID_WITH_AN)
		{
			if (s.startsWith(h))
			{
				return "an";
			}
		}
		if ("aeiou".indexOf(first) < 0)
		{
			return "a";
		}
		for (String y : SAID_WITH_A)
		{
			if (s.startsWith(y))
			{
				return "a";
			}
		}
		return "an";
	}

	/**
	 * A creature's name as the middle of a sentence has it: the guard, a
	 * goblin, a monk of Zamorak. The game capitalises every name's first
	 * letter; one shaped like a plain noun, a capital to open it and none
	 * after until any "of", gives it up. One with more capitals is a name in
	 * its own right and keeps them: Hill Giant, King Black Dragon, TzHaar-Ket.
	 */
	public static String asCommonNoun(String name)
	{
		if (name == null || name.length() < 2 || !Character.isUpperCase(name.charAt(0)))
		{
			return name;
		}
		String head = name.replaceAll("\\s*\\(.*\\)$", "");
		int of = head.indexOf(" of ");
		if (of > 0)
		{
			head = head.substring(0, of);
		}
		for (int i = 1; i < head.length(); i++)
		{
			if (Character.isUpperCase(head.charAt(i)))
			{
				return name;
			}
		}
		return Character.toLowerCase(name.charAt(0)) + name.substring(1);
	}

	/**
	 * Whether the name takes no "a" at all: Coins, Dragon bones, soft clay,
	 * The Leviathan. Judged on the head word - the one before "of" when there
	 * is one, so a Jar of souls keeps its article - with a trailing "(e)"
	 * ignored. A word ending in s is plural unless its ending says otherwise,
	 * which keeps Cerberus, Venenatis, the Nylocas and a Cyclops singular.
	 */
	public static boolean takesNoArticle(String name)
	{
		String s = name.trim().toLowerCase(Locale.ROOT);
		if (s.startsWith("the "))
		{
			return true;
		}
		s = s.replaceAll("\\s*\\(.*\\)$", "");
		int of = s.indexOf(" of ");
		if (of > 0)
		{
			s = s.substring(0, of);
		}
		String head = s.substring(s.lastIndexOf(' ') + 1);
		if (HEAPS.contains(head))
		{
			return true;
		}
		if (!head.endsWith("s"))
		{
			return false;
		}
		for (String one : ONE_DESPITE_THE_S)
		{
			if (head.endsWith(one))
			{
				return false;
			}
		}
		return true;
	}
}

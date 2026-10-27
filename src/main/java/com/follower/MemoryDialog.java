package com.follower;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import javax.swing.*;
import net.runelite.client.ui.*;

/**
 * The memory surface (R20): everything the follower currently knows about
 * you, in one window, with the eraser underneath.
 *
 * <p>Three rounds of features built hidden machinery - tallies, bests, the
 * incident, place feelings, the date it met you - and machinery a player
 * cannot see is machinery they cannot trust. This makes it legible, and the
 * "Forget everything" button is the named mitigation for the invasiveness of
 * remembering anything at all: the memory is a gift the player can decline.
 * It doubles as a debugging window, which is why a region no rule names
 * still shows its id.
 *
 * <p>The window renders a snapshot handed to it (taken on the client thread)
 * rather than reading live state from the event thread.
 */
class MemoryDialog extends JDialog
{
	final JPanel list = Ui.column();

	/** The counts with names of their own, in the order the window lists them. */
	static final List<String> NAMED_COUNTS = java.util.Arrays.asList(
		"kills", "kills" + TriggerContext.TODAY, "deaths", "deaths" + TriggerContext.TODAY,
		"levels", "levels" + TriggerContext.TODAY, "quests", "music", "collections",
		"want:kept", "want:missed");

	MemoryDialog(Runnable onForget)
	{
		super((java.awt.Frame) null, "Follower Buddy - Memory", false);

		setDefaultCloseOperation(HIDE_ON_CLOSE);

		var root = Ui.panel(new BorderLayout(0, 6));
		root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		var hint = Ui.label(wrapped(Resources.text("memoryHint")), ColorScheme.MEDIUM_GRAY_COLOR);

		var where = Ui.label(wrapped(Resources.text("memoryWhere")), ColorScheme.MEDIUM_GRAY_COLOR);
		where.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

		var top = Ui.panel(new BorderLayout());
		top.add(hint, BorderLayout.NORTH);
		top.add(where, BorderLayout.SOUTH);
		root.add(top, BorderLayout.NORTH);

		root.add(Ui.scroller(list), BorderLayout.CENTER);

		var forget = new JButton("Forget everything");
		forget.setToolTipText(Resources.text("forgetTip"));
		forget.setFocusPainted(false);
		forget.addActionListener(e ->
		{
			int sure = JOptionPane.showConfirmDialog(this,
				Resources.text("forgetConfirm"),
				"Forget everything?",
				JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (sure == JOptionPane.YES_OPTION)
				onForget.run();
		});
		root.add(forget, BorderLayout.SOUTH);

		setContentPane(root);
		setMinimumSize(new Dimension(380, 460));
		// Narrow and tall: the rows wrap at the width below, and the summary
		// is a list read downwards. QA, 15 Oct: it opened at 743x460.
		setPreferredSize(new Dimension(420, 640));
		pack();
		setLocationRelativeTo(null);
	}

	/** Renders a snapshot and fronts the window. */
	void show(List<String[]> rows)
	{
		list.removeAll();
		for (var row : rows)
		{
			if (row[1] == null)
			{
				var section = new JLabel(row[0]);
				section.setFont(FontManager.getRunescapeBoldFont());
				section.setForeground(ColorScheme.BRAND_ORANGE);
				section.setBorder(BorderFactory.createEmptyBorder(10, 0, 2, 0));
				section.setAlignmentX(LEFT_ALIGNMENT);
				list.add(section);
			}
			else
			{
				// Wrapped, since a label never wraps by itself and the window
				// scrolls only downwards: long rows were cut off.
				var line = Ui.label(wrapped(row[1].isEmpty()
					? row[0] : row[0] + ":  " + row[1]),
					row[1].isEmpty() ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
				line.setAlignmentX(LEFT_ALIGNMENT);
				list.add(line);
			}
		}
		Ui.relayout(list);
		setVisible(true);
		toFront();
	}

	// ------------------------------------------------------------- the summary

	/**
	 * Everything worth showing, as (label, value) rows; a null value marks a
	 * section header and an empty one a plain remark. Static and pure so the
	 * shape of what the player is told stays testable without a window.
	 *
	 * @param regionNames place names by region id, from the Locations rules;
	 * an id with no name is shown bare
	 * @param accountKey the key a kill count is kept under for this account
	 * (ChronicleProgress.accountKey); only this account's counts are shown
	 */
	static List<String[]> summarise(TriggerContext c, Map<Integer, String> regionNames,
		UnaryOperator<String> accountKey)
	{
		List<String[]> rows = new ArrayList<>();

		rows.add(header("THE BASICS"));
		rows.add(row("First met", c.getMetOnDay() > 0
			? java.time.LocalDate.ofEpochDay(c.getMetOnDay())
				+ " (" + daysAgo(c.getDaysKnown()) + ")"
			: "not recorded yet"));
		rows.add(row("Sessions together", c.getSessionCount()));
		rows.add(row("Mood right now", c.getMoodBand()));

		rows.add(header("COUNTS"));
		if (c.getTallies().isEmpty())
			rows.add(remark("Nothing counted yet."));
		// The named counts always show, in one order, each day's under its
		// lifetime row: only the kills by creature are ever many, and they
		// alone are capped, so they never push the rest out (qa5 #28).
		List<Map.Entry<String, Integer>> tallies = new ArrayList<>();
		for (var tally : c.getTallies().entrySet())
		{
			if (!NAMED_COUNTS.contains(tally.getKey()))
				tallies.add(tally);
		}
		for (String key : NAMED_COUNTS)
		{
			var value = c.getTallies().get(key);
			if (value != null)
				rows.add(row(keyLabel(key), value));
		}
		tallies.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
		for (var tally : tallies.subList(0, Math.min(10, tallies.size())))
			rows.add(row(keyLabel(tally.getKey()), tally.getValue()));
		if (tallies.size() > 10)
			rows.add(remark("...and " + (tallies.size() - 10) + " more."));

		rows.add(header("BESTS"));
		List<String[]> bests = new ArrayList<>();
		c.getRecords().forEach((what, value) ->
		{
			if (isShown(what, c.getRecords(), accountKey))
				bests.add(row(keyLabel(what, c.getKillCountNames()), value));
		});
		if (bests.isEmpty())
			rows.add(remark("No records held yet."));
		rows.addAll(bests);

		// The phrase is written for the middle of a sentence - "I keep
		// thinking about {memory}" - so bare in a window it reads as a
		// riddle ("the way that went"?). Quoting it as its words is what makes
		// it legible out of context. The file key is left out: QA, 15 Oct.
		rows.add(header("THE INCIDENT"));
		rows.add(c.hasIncident()
			? row("Keeps bringing up", "\"" + c.getIncidentPhrase() + c.getMemoryWhere()
				+ ", " + c.getMemoryWhen(System.currentTimeMillis()) + "\" (x"
				+ c.getIncidentCount() + ")")
			: remark("Nothing has stuck yet."));


		rows.add(header("RIGHT NOW"));
		int before = rows.size();
		if (!c.getAskedTree().isEmpty())
			rows.add(row("Waiting on an answer", askedAbout(c.getAskedTree())));
		if (rows.size() == before)
			rows.add(remark("Nothing in hand."));

		rows.add(header("BOOKKEEPING"));
		rows.add(row("One-time lines already said", c.getSpokenOnce().size()));
		rows.add(row("Lines counted, to vary them", c.getLineWear().size()));

		return rows;
	}

	/**
	 * What the follower asked, in words, for the three trees it ships with;
	 * a tree of the player's own shows its id. Text sweep, 14 Oct: the raw
	 * id was shown for all of them.
	 */
	static String askedAbout(String tree)
	{
		switch (tree)
		{
			case "want-outing":
				return "Whether you'll take it somewhere";
			case "game-hands":
				return "Which hand it's hiding something in";
			default:
				return tree;
		}
	}

	/**
	 * A count's or a best's key in words: the keys are what the plugin files
	 * them under, and the window showed them raw ("kill:goblin",
	 * "kills:today", "session: 187"). A key with no words here is shown as
	 * it is.
	 */
	static String keyLabel(String key)
	{
		return keyLabel(key, java.util.Collections.emptyMap());
	}

	/** @param killCountNames each boss's name as the game writes it, by its lower-case name */
	static String keyLabel(String key, Map<String, String> killCountNames)
	{
		boolean today = key.endsWith(TriggerContext.TODAY);
		String base = today ? key.substring(0, key.length() - TriggerContext.TODAY.length()) : key,
			label;
		if (base.startsWith("kill:") && base.length() > "kill:".length())
			label = capitalised(base.substring("kill:".length())) + " kills";
		else if (base.startsWith("kc:") && base.length() > "kc:".length())
		{
			// Per account: the account's key is not for showing.
			String boss = base.substring("kc:".length());
			boss = boss.contains("@") ? boss.substring(0, boss.indexOf('@')) : boss;
			label = killCountNames.getOrDefault(boss, capitalised(boss)) + " kill count";
		}
		else
		{
			switch (base)
			{
				case "kills":
					label = "Kills";
					break;
				case "deaths":
					label = "Deaths";
					break;
				case "levels":
					label = "Levels gained";
					break;
				case "quests":
					label = "Quests completed";
					break;
				case "music":
					label = "Music tracks unlocked";
					break;
				case "collections":
					label = "Collection log items";
					break;
				case "want:kept":
					label = "Outings taken";
					break;
				case "want:missed":
					label = "Outings missed";
					break;
				case "hit":
					label = "Biggest hit";
					break;
				case "session":
					label = "Longest session (minutes)";
					break;
				default:
					return key;
			}
		}
		return today ? label + " today" : label;
	}

	/**
	 * Whether a best belongs in this account's window. Kill counts are kept
	 * per account, so another account's on the same profile is left out, and
	 * the one kept before counts were per account is shown only while this
	 * account has none of its own, as the book's page reads it.
	 */
	static boolean isShown(String key, Map<String, Integer> records,
		UnaryOperator<String> accountKey)
	{
		if (!key.startsWith("kc:"))
			return true;
		String bare = key.contains("@") ? key.substring(0, key.indexOf('@')) : key,
			mine = accountKey.apply(bare);
		return key.equals(mine) || (key.equals(bare) && !records.containsKey(mine));
	}

	static String capitalised(String s)
	{
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** Text that wraps at the window's narrowest, in the HTML renderer's px (x1.3 on screen). */
	static String wrapped(String text)
	{
		return Ui.html(260, text);
	}

	/** "today", "1 day ago" or "N days ago". */
	static String daysAgo(int days)
	{
		if (days <= 0)
			return "today";
		return days == 1 ? "1 day ago" : days + " days ago";
	}

	static String[] header(String text)
	{
		return new String[]{text, null};
	}

	static String[] row(String label, String value)
	{
		return new String[]{label, value};
	}

	static String[] row(String label, int count)
	{
		return row(label, Integer.toString(count));
	}

	static String[] remark(String text)
	{
		return new String[]{text, ""};
	}
}

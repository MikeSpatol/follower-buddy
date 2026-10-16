package com.follower.ui;

import com.follower.speech.TriggerContext;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The memory surface (R20): everything the follower currently knows about
 * you, in one window, with the eraser underneath.
 *
 * <p>Three rounds of features built hidden machinery - tallies, bests, the
 * incident, place feelings, the date it met you - and machinery a player
 * cannot see is machinery they cannot trust. This makes it legible, and the
 * "Forget everything" button is the named mitigation for the invasiveness of
 * remembering anything at all: the memory is a gift the player can decline.
 * It doubles as a debugging window, which is why region ids stay visible.
 *
 * <p>The window renders a snapshot handed to it (taken on the client thread)
 * rather than reading live state from the event thread.
 */
public class MemoryDialog extends JDialog
{
	private final Runnable onForget;
	private final JPanel list = new JPanel();

	public MemoryDialog(Runnable onForget)
	{
		super((java.awt.Frame) null, "Follower Buddy - Memory", false);
		this.onForget = onForget;

		setDefaultCloseOperation(HIDE_ON_CLOSE);

		JPanel root = new JPanel(new BorderLayout(0, 6));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel hint = new JLabel(wrapped("Everything the follower currently remembers"
			+ " about you. It lives on this machine, goes nowhere, and the button"
			+ " below erases all of it for good."));
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);

		JLabel where = new JLabel(wrapped("The Reminiscing toggle in settings silences the"
			+ " lines that use this; Forget everything erases it."));
		where.setFont(FontManager.getRunescapeSmallFont());
		where.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		where.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

		JPanel top = new JPanel(new BorderLayout());
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(hint, BorderLayout.NORTH);
		top.add(where, BorderLayout.SOUTH);
		root.add(top, BorderLayout.NORTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JScrollPane scroll = new JScrollPane(list,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		root.add(scroll, BorderLayout.CENTER);

		JButton forget = new JButton("Forget everything");
		forget.setToolTipText("Erase every count, best, place, and the day it met"
			+ " you - it meets you again as a stranger");
		forget.setFocusPainted(false);
		forget.addActionListener(e ->
		{
			int sure = JOptionPane.showConfirmDialog(this,
				"Everything it knows - counts, bests, places, the incident,\n"
					+ "the day you met - is erased for good, and the follower\n"
					+ "meets you again as a stranger. There is no undo.",
				"Forget everything?",
				JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (sure == JOptionPane.YES_OPTION)
			{
				onForget.run();
			}
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
	public void show(List<String[]> rows)
	{
		list.removeAll();
		for (String[] row : rows)
		{
			if (row[1] == null)
			{
				JLabel section = new JLabel(row[0]);
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
				JLabel line = new JLabel(wrapped(row[1].isEmpty()
					? row[0] : row[0] + ":  " + row[1]));
				line.setFont(FontManager.getRunescapeSmallFont());
				line.setForeground(row[1].isEmpty()
					? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
				line.setAlignmentX(LEFT_ALIGNMENT);
				list.add(line);
			}
		}
		list.revalidate();
		list.repaint();
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
	 */
	public static List<String[]> summarise(TriggerContext c, Map<Integer, String> regionNames)
	{
		List<String[]> rows = new ArrayList<>();

		rows.add(header("THE BASICS"));
		rows.add(row("First met", c.getMetOnDay() > 0
			? java.time.LocalDate.ofEpochDay(c.getMetOnDay())
				+ " (" + daysAgo(c.getDaysKnown()) + ")"
			: "not recorded yet"));
		rows.add(row("Sessions together", Integer.toString(c.getSessionCount())));
		rows.add(row("Mood right now", c.getMoodBand()));

		rows.add(header("COUNTS"));
		List<Map.Entry<String, Integer>> tallies =
			new ArrayList<>(c.getTallies().entrySet());
		if (tallies.isEmpty())
		{
			rows.add(remark("Nothing counted yet."));
		}
		tallies.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
		for (Map.Entry<String, Integer> tally : tallies.subList(0, Math.min(10, tallies.size())))
		{
			rows.add(row(keyLabel(tally.getKey()), Integer.toString(tally.getValue())));
		}
		if (tallies.size() > 10)
		{
			rows.add(remark("...and " + (tallies.size() - 10) + " more."));
		}

		rows.add(header("BESTS"));
		if (c.getRecords().isEmpty())
		{
			rows.add(remark("No records held yet."));
		}
		c.getRecords().forEach((what, value) ->
			rows.add(row(keyLabel(what), Integer.toString(value))));

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

		rows.add(header("PLACES"));
		rows.add(row("Opinions held", Integer.toString(c.getPlaceScores().size())));
		List<Map.Entry<Integer, Integer>> places =
			new ArrayList<>(c.getPlaceScores().entrySet());
		places.sort(Comparator.comparingInt(e -> -Math.abs(e.getValue())));
		for (Map.Entry<Integer, Integer> place : places.subList(0, Math.min(3, places.size())))
		{
			String memory = c.getPlaceMemories().get(place.getKey());
			rows.add(row("Region " + named(place.getKey(), regionNames),
				(place.getValue() > 0 ? "+" : "") + place.getValue()
					+ (memory == null ? "" : " - " + memory)));
		}
		rows.add(row("Likes on a whim", c.getLikedRegions().isEmpty()
			? "nowhere in particular" : joined(c.getLikedRegions(), regionNames)));
		rows.add(row("Avoids on a whim", c.getDislikedRegions().isEmpty()
			? "nowhere in particular" : joined(c.getDislikedRegions(), regionNames)));
		if (c.getCoreLikedRegion() != 0 || c.getCoreDislikedRegion() != 0)
		{
			StringBuilder core = new StringBuilder();
			if (c.getCoreLikedRegion() != 0)
			{
				core.append("likes region ").append(named(c.getCoreLikedRegion(), regionNames));
			}
			if (c.getCoreDislikedRegion() != 0)
			{
				core.append(core.length() == 0 ? "" : ", ")
					.append("avoids region ").append(named(c.getCoreDislikedRegion(), regionNames));
			}
			rows.add(row("Won't be argued out of", core.toString()));
		}

		rows.add(header("RIGHT NOW"));
		boolean anything = false;
		if (c.isCarrying())
		{
			rows.add(row("Carrying", c.getSouvenir()));
			anything = true;
		}
		if (c.isWishing())
		{
			// The handover is in Talk-to, which a waiting question takes over
			// as Answer, so the hint waits with it (QA3).
			rows.add(row("Hoping for", c.getWishLabel()
				+ (c.getAskedTree().isEmpty() ? " (right-click the follower and Talk-to to hand it over)" : "")));
			anything = true;
		}
		if (!c.getAskedTree().isEmpty())
		{
			rows.add(row("Waiting on an answer", askedAbout(c.getAskedTree())));
			anything = true;
		}
		if (c.isChallenging())
		{
			rows.add(row("The challenge", c.getChallengeAbout()
				+ " (" + c.getChallengeLeft() + " to go)"));
			anything = true;
		}
		if (!anything)
		{
			rows.add(remark("Nothing in hand."));
		}

		rows.add(header("BOOKKEEPING"));
		rows.add(row("One-time lines already said", Integer.toString(c.getSpokenOnce().size())));
		rows.add(row("Lines counted, to vary them", Integer.toString(c.getLineWear().size())));

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
			case "nickname-offer":
				return "The name it suggested for you";
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
	public static String keyLabel(String key)
	{
		boolean today = key.endsWith(TriggerContext.TODAY);
		String base = today ? key.substring(0, key.length() - TriggerContext.TODAY.length()) : key;
		String label;
		if (base.startsWith("kill:") && base.length() > "kill:".length())
		{
			label = capitalised(base.substring("kill:".length())) + " killed";
		}
		else if (base.startsWith("kc:") && base.length() > "kc:".length())
		{
			// Per account: the account's key is not for showing.
			String boss = base.substring("kc:".length());
			label = capitalised(boss.contains("@") ? boss.substring(0, boss.indexOf('@')) : boss) + " kill count";
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
					label = "Wishes granted";
					break;
				case "want:missed":
					label = "Wishes missed";
					break;
				case "advice:taken":
					label = "Advice taken";
					break;
				case "advice:ignored":
					label = "Advice ignored";
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

	private static String capitalised(String s)
	{
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** Text that wraps at the window's narrowest, in the HTML renderer's px (x1.3 on screen). */
	private static String wrapped(String text)
	{
		return "<html><body style='width:260px'>"
			+ text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
			+ "</body></html>";
	}

	/** "today", "1 day ago" or "N days ago". */
	static String daysAgo(int days)
	{
		if (days <= 0)
		{
			return "today";
		}
		return days == 1 ? "1 day ago" : days + " days ago";
	}

	private static String[] header(String text)
	{
		return new String[]{text, null};
	}

	private static String[] row(String label, String value)
	{
		return new String[]{label, value};
	}

	private static String[] remark(String text)
	{
		return new String[]{text, ""};
	}

	private static String joined(java.util.Set<Integer> regions, Map<Integer, String> names)
	{
		StringBuilder out = new StringBuilder();
		for (Integer region : regions)
		{
			out.append(out.length() == 0 ? "region " : ", ").append(named(region, names));
		}
		return out.toString();
	}

	/** "12850 (Lumbridge town and castle)", or the bare id when no rule names it. */
	private static String named(int region, Map<Integer, String> names)
	{
		String name = names == null ? null : names.get(region);
		return name == null ? Integer.toString(region) : region + " (" + name + ")";
	}
}

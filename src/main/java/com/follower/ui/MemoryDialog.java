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
		super((java.awt.Frame) null, "Follower Buddy — What it knows", false);
		this.onForget = onForget;

		setDefaultCloseOperation(HIDE_ON_CLOSE);

		JPanel root = new JPanel(new BorderLayout(0, 6));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel hint = new JLabel("<html>Everything the follower currently remembers"
			+ " about you. It lives on this machine, goes nowhere, and the button"
			+ " below erases all of it for good.</html>");
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		root.add(hint, BorderLayout.NORTH);

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
				JLabel line = new JLabel(row[1].isEmpty()
					? row[0] : row[0] + ":  " + row[1]);
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
	 */
	public static List<String[]> summarise(TriggerContext c)
	{
		List<String[]> rows = new ArrayList<>();

		rows.add(header("THE BASICS"));
		rows.add(row("First met", c.getMetOnDay() > 0
			? java.time.LocalDate.ofEpochDay(c.getMetOnDay())
				+ " (" + c.getDaysKnown() + " days ago)"
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
			rows.add(row(tally.getKey(), Integer.toString(tally.getValue())));
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
			rows.add(row(what, Integer.toString(value))));

		rows.add(header("THE INCIDENT"));
		rows.add(c.hasIncident()
			? row("Keeps bringing up", c.getIncidentPhrase()
				+ " (x" + c.getIncidentCount() + ")")
			: remark("Nothing has stuck yet."));

		rows.add(header("PLACES"));
		rows.add(row("Opinions held", Integer.toString(c.getPlaceScores().size())));
		List<Map.Entry<Integer, Integer>> places =
			new ArrayList<>(c.getPlaceScores().entrySet());
		places.sort(Comparator.comparingInt(e -> -Math.abs(e.getValue())));
		for (Map.Entry<Integer, Integer> place : places.subList(0, Math.min(3, places.size())))
		{
			String memory = c.getPlaceMemories().get(place.getKey());
			rows.add(row("Region " + place.getKey(),
				(place.getValue() > 0 ? "+" : "") + place.getValue()
					+ (memory == null ? "" : " - " + memory)));
		}
		rows.add(row("Likes on a whim", c.getLikedRegions().isEmpty()
			? "nowhere in particular" : joined(c.getLikedRegions())));
		rows.add(row("Avoids on a whim", c.getDislikedRegions().isEmpty()
			? "nowhere in particular" : joined(c.getDislikedRegions())));

		rows.add(header("RIGHT NOW"));
		boolean anything = false;
		if (c.isCarrying())
		{
			rows.add(row("Carrying", c.getSouvenir()));
			anything = true;
		}
		if (c.isWishing())
		{
			rows.add(row("Hoping for", c.getWishLabel()));
			anything = true;
		}
		if (!c.getAskedTree().isEmpty())
		{
			rows.add(row("Waiting on an answer", c.getAskedTree()));
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
		rows.add(row("Firsts already said", Integer.toString(c.getSpokenOnce().size())));
		rows.add(row("Lines wear-tracked", Integer.toString(c.getLineWear().size())));

		return rows;
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

	private static String joined(java.util.Set<Integer> regions)
	{
		StringBuilder out = new StringBuilder();
		for (Integer region : regions)
		{
			out.append(out.length() == 0 ? "region " : ", ").append(region);
		}
		return out.toString();
	}
}

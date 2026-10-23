// public:file dialogs-editor
package com.follower.ui;

import com.follower.speech.DialogTree;
import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;

/**
 * The conversation editor: every node of a dialog tree as a row, with who
 * speaks, what they say, where it goes next and which branch counts as the
 * answer.
 *
 * <p>A table rather than raw JSON because a conversation is a graph and the
 * thing that goes wrong with a graph is an edge pointing at nothing. Seeing
 * every node id in one column next to every {@code next} that names one is what
 * makes a typo obvious; in a file it is invisible until the box shuts in
 * somebody's face.
 *
 * <p>Save validates the whole tree before writing. A dialog that dead-ends is
 * worse than one that never opens, because the player is already inside it.
 */
@Slf4j
public class DialogsDialog extends EditorDialog
{
	private final JComboBox<String> treePicker = new JComboBox<>();
	private final List<Row> rows = new ArrayList<>();

	private DialogFile parsed;
	private boolean loading;

	/** The tree the rows on screen belong to, for reverting a cancelled switch. */
	private int shownTreeIndex;

	public DialogsDialog(Gson gson, Path file)
	{
		super("Follower Buddy - Conversations", gson, file, new JPanel(), 300);

		JLabel hint = Ui.label("<html><body style='width:500px'>These are the conversations the follower"
			+ " <b>starts</b>. While it is waiting for an answer, its Talk-to option becomes"
			+ " Answer and opens the conversation it asked about instead of the everyday one.<br>"
			+ "<b>Says</b> is one page per line, spoken by whoever is picked beside the node. <b>Choices</b> are one"
			+ " per line, written <code>label -&gt; node-id</code>. <b>Answer</b> is what"
			+ " reaching that node records as your reply.</html>", ColorScheme.LIGHT_GRAY_COLOR);

		JPanel top = Ui.panel(new BorderLayout(6, 6));
		top.add(hint, BorderLayout.CENTER);
		treePicker.addActionListener(e ->
		{
			if (loading || treePicker.getSelectedIndex() < 0)
			{
				return;
			}
			int wanted = treePicker.getSelectedIndex();
			if (!confirmDirty())
			{
				// Cancelled: put the picker back without re-entering this.
				loading = true;
				treePicker.setSelectedIndex(shownTreeIndex);
				loading = false;
				return;
			}
			// Saving inside confirmDirty refreshes, which resets the picker to
			// the first tree; the user asked for a different one.
			if (treePicker.getSelectedIndex() != wanted)
			{
				loading = true;
				treePicker.setSelectedIndex(wanted);
				loading = false;
			}
			showSelectedTree();
		});
		top.add(treePicker, BorderLayout.SOUTH);

		JButton add = new JButton("Add node");
		add.setToolTipText("A new step in this conversation. Point something at it"
			+ " with Next or a Choice, or nothing will ever reach it.");
		add.addActionListener(e -> addBlankRow());
		buttons.add(add);
		addReloadAndSave();

		layOut(top, 700, 700);
		// Taller than the screen puts Save under the
		// taskbar, as Advanced posing found (QA3). // public:cut posing
		// public:else // taskbar (QA3).
		java.awt.GraphicsConfiguration screen = getGraphicsConfiguration();
		setSize(getWidth(), FilmDialog.heightOnScreen(getHeight(), screen.getBounds(), // public:cut posing
		// public:else java.awt.Insets taskbars = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(screen);
		// public:else setSize(getWidth(), Math.min(getHeight(), screen.getBounds().height - taskbars.top - taskbars.bottom));
			java.awt.Toolkit.getDefaultToolkit().getScreenInsets(screen))); // public:cut posing
		setLocationRelativeTo(null);
	}

	@Override
	void refresh()
	{
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			parsed = gson.fromJson(reader, DialogFile.class);
		}
		catch (Exception e)
		{
			log.warn("Could not read {}", file, e);
			setStatus("The conversations file couldn't be read - it may have been"
				+ " hand-edited. Fix it or restore a backup, then Reload.", true);
			return;
		}

		if (parsed == null || parsed.trees == null || parsed.trees.isEmpty())
		{
			setStatus("No conversations in dialogs.json", true);
			return;
		}

		loading = true;
		treePicker.removeAllItems();
		for (DialogTree tree : parsed.trees)
		{
			// The picker shows the same prettified names as the message editor;
			// the raw id still appears in each node's own rows for editing.
			treePicker.addItem(PhrasesDialog.prettyName(tree.id));
		}
		loading = false;
		showSelectedTree();
	}

	private DialogTree selectedTree()
	{
		int index = treePicker.getSelectedIndex();
		return parsed == null || parsed.trees == null
			|| index < 0 || index >= parsed.trees.size()
			? null : parsed.trees.get(index);
	}

	private void showSelectedTree()
	{
		rows.clear();
		list.removeAll();

		DialogTree tree = selectedTree();
		if (tree == null)
		{
			Ui.relayout(list);
			return;
		}
		if (tree.nodes != null)
		{
			for (DialogTree.DialogNode node : tree.nodes)
			{
				list.add(buildRow(node));
			}
		}
		setStatus((tree.nodes == null ? 0 : tree.nodes.size()) + " nodes, starting at '"
			+ tree.startId() + "'", false);
		Ui.relayout(list);
		shownTreeIndex = treePicker.getSelectedIndex();
		dirty = false;
	}

	private void addBlankRow()
	{
		DialogTree.DialogNode node = new DialogTree.DialogNode();
		node.id = "node-" + (rows.size() + 1);
		node.says = new ArrayList<>();
		list.add(buildRow(node));
		Ui.relayout(list);
		dirty = true;
	}

	private static final class Row
	{
		final JTextField id = new JTextField();
		final JComboBox<String> speaker = new JComboBox<>(new String[]{"Follower", "You"});
		final JTextArea pages = new JTextArea(2, 40);
		final JTextField next = new JTextField();
		final GhostArea choices = new GhostArea("Yes please -> node-thanks", 3, 40);
		final JComboBox<String> answer = new JComboBox<>(new String[]{"(none)", "yes", "no"});
		boolean deleted;
		JPanel panel;
	}

	/**
	 * A text area that shows an example of its little syntax while empty, so
	 * "label -> node-id" is visible at the point of use rather than three
	 * paragraphs up in the header.
	 */
	private static final class GhostArea extends JTextArea
	{
		private final String ghost;

		/**
		 * The node's Next. A node that goes on by Next, or ends, has no
		 * choices by design, so the example is shown only while both are
		 * empty; on every node it read as something missing.
		 */
		private JTextField next;

		GhostArea(String ghost, int rows, int columns)
		{
			super(rows, columns);
			this.ghost = ghost;
		}

		@Override
		protected void paintComponent(java.awt.Graphics g)
		{
			super.paintComponent(g);
			if (showsGhost(getText(), next == null ? "" : next.getText()))
			{
				g.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
				g.setFont(getFont().deriveFont(java.awt.Font.ITALIC));
				java.awt.Insets insets = getInsets();
				g.drawString(ghost, insets.left + 2,
					insets.top + g.getFontMetrics().getAscent());
			}
		}
	}

	/** Whether a node's Choices box shows its example: only with no choices and no Next. */
	static boolean showsGhost(String choices, String next)
	{
		return choices.isEmpty() && next.trim().isEmpty();
	}

	private JPanel buildRow(DialogTree.DialogNode node)
	{
		Row row = new Row();
		rows.add(row);

		row.id.setText(node.id == null ? "" : node.id);
		row.speaker.setSelectedIndex(node.isPlayerSpeaking() ? 1 : 0);
		row.pages.setText(String.join("\n", node.pages()));
		row.next.setText(node.next == null ? "" : node.next);
		row.answer.setSelectedItem(node.answer == null ? "(none)" : node.answer);

		StringBuilder choiceText = new StringBuilder();
		if (node.choices != null)
		{
			for (DialogTree.DialogChoice choice : node.choices)
			{
				if (choice == null)
				{
					continue;
				}
				choiceText.append(choice.label == null ? "" : choice.label)
					.append(" -> ").append(choice.next == null ? "" : choice.next)
					.append('\n');
			}
		}
		row.choices.setText(choiceText.toString().trim());
		row.choices.next = row.next;
		row.next.getDocument().addDocumentListener(Ui.onChange(row.choices::repaint));

		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			BorderFactory.createEmptyBorder(6, 6, 6, 6)));

		JButton remove = new JButton("Delete");
		remove.setToolTipText("Remove this node. Anything pointing at it will need"
			+ " repointing, and Save will say so.");
		remove.addActionListener(e ->
		{
			row.deleted = true;
			panel.setVisible(false);
			dirty = true;
		});

		// One line per row of the grid. The field after each name takes the
		// spare width, and a name with a single field beside it spans the row.
		JComponent[][] grid = {
			{label("Node"), row.id, row.speaker, remove},
			{label("Says"), scrollable(row.pages)},
			{label("Choices"), scrollable(row.choices)},
			{label("Next"), row.next, label("Answer"), row.answer},
		};
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(2, 2, 2, 2);
		c.fill = GridBagConstraints.HORIZONTAL;
		for (int y = 0; y < grid.length; y++)
		{
			for (int x = 0; x < grid[y].length; x++)
			{
				c.gridx = x;
				c.gridy = y;
				c.weightx = x == 1 ? 1 : 0;
				c.gridwidth = x == 1 && grid[y].length == 2 ? 3 : 1;
				panel.add(grid[y][x], c);
			}
		}

		// Attached after the values above are set, so populating a row does
		// not read as the user editing one.
		row.id.getDocument().addDocumentListener(dirtyMarker);
		row.pages.getDocument().addDocumentListener(dirtyMarker);
		row.choices.getDocument().addDocumentListener(dirtyMarker);
		row.next.getDocument().addDocumentListener(dirtyMarker);
		row.speaker.addActionListener(e -> dirty = true);
		row.answer.addActionListener(e -> dirty = true);

		row.panel = panel;
		return panel;
	}

	private static JLabel label(String text)
	{
		return Ui.label(text, ColorScheme.LIGHT_GRAY_COLOR);
	}

	private static JScrollPane scrollable(JTextArea area)
	{
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setBackground(ColorScheme.DARK_GRAY_COLOR);
		area.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		area.setCaretColor(ColorScheme.LIGHT_GRAY_COLOR);
		JScrollPane scroll = new JScrollPane(area,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createLineBorder(ColorScheme.DARK_GRAY_COLOR));
		return scroll;
	}

	@Override
	boolean save()
	{
		DialogTree tree = selectedTree();
		if (tree == null)
		{
			return false;
		}

		List<DialogTree.DialogNode> rebuilt = new ArrayList<>();
		for (Row row : rows)
		{
			if (row.deleted)
			{
				continue;
			}
			String id = row.id.getText().trim();
			if (id.isEmpty())
			{
				continue;
			}

			DialogTree.DialogNode node = new DialogTree.DialogNode();
			node.id = id;

			List<String> pages = new ArrayList<>();
			for (String line : row.pages.getText().split("\r?\n"))
			{
				if (!line.trim().isEmpty())
				{
					pages.add(line.trim());
				}
			}
			if (row.speaker.getSelectedIndex() == 1)
			{
				node.you = pages;
			}
			else
			{
				node.says = pages;
			}

			List<DialogTree.DialogChoice> choices = new ArrayList<>();
			for (String line : row.choices.getText().split("\r?\n"))
			{
				String trimmed = line.trim();
				if (trimmed.isEmpty())
				{
					continue;
				}
				int arrow = trimmed.lastIndexOf("->");
				DialogTree.DialogChoice choice = new DialogTree.DialogChoice();
				if (arrow < 0)
				{
					// Left as a choice with no target so validation names it,
					// rather than silently dropping a line somebody typed.
					choice.label = trimmed;
				}
				else
				{
					choice.label = trimmed.substring(0, arrow).trim();
					choice.next = trimmed.substring(arrow + 2).trim();
				}
				choices.add(choice);
			}
			if (!choices.isEmpty())
			{
				node.choices = choices;
			}

			String next = row.next.getText().trim();
			node.next = next.isEmpty() ? null : next;

			Object answer = row.answer.getSelectedItem();
			node.answer = answer == null || "(none)".equals(answer) ? null : answer.toString();

			rebuilt.add(node);
		}

		List<DialogTree.DialogNode> previous = tree.nodes;
		String previousStart = tree.start;
		tree.nodes = rebuilt;
		// The start has to still exist, or the conversation opens on nothing.
		if (tree.start != null && tree.byId().get(tree.start) == null)
		{
			tree.start = rebuilt.isEmpty() ? null : rebuilt.get(0).id;
		}

		List<String> problems = tree.problems();
		if (!problems.isEmpty())
		{
			// Nothing is written. A conversation that dead-ends is worse than
			// one that never opens, because the player is already inside it
			// when it happens.
			tree.nodes = previous;
			tree.start = previousStart;
			setStatus("Not saved - " + String.join("; ", problems), true);
			return false;
		}

		try
		{
			writeAtomically(gson.toJson(parsed) + "\n");
			setStatus("Saved - the follower picks it up within a second", false);
			dirty = false;
			refresh();
			return true;
		}
		catch (Exception e)
		{
			log.warn("Could not save {}", file, e);
			tree.nodes = previous;
			tree.start = previousStart;
			setStatus("Save failed: " + e.getMessage(), true);
			return false;
		}
	}

	/** Mirrors the on-disk shape so the whole file round-trips, not just one tree. */
	private static final class DialogFile
	{
		int version = 1;
		List<DialogTree> trees;
	}
}

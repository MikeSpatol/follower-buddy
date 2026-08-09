package com.follower.ui;

import com.follower.speech.DialogTree;
import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

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
public class DialogsDialog extends JDialog
{
	private final Gson gson;
	private final Path file;

	private final JComboBox<String> treePicker = new JComboBox<>();
	private final JPanel list = new JPanel();
	private final JLabel status = new JLabel(" ");
	private final List<Row> rows = new ArrayList<>();

	private DialogFile parsed;
	private boolean loading;

	public DialogsDialog(Gson gson, Path file)
	{
		super((java.awt.Frame) null, "Follower Buddy — Conversations", false);
		this.gson = gson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create();
		this.file = file;

		setDefaultCloseOperation(HIDE_ON_CLOSE);

		JPanel root = new JPanel(new BorderLayout(0, 6));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel hint = new JLabel("<html>These are the conversations the follower"
			+ " <b>starts</b>. While it is waiting for an answer, right-clicking Talk-to"
			+ " opens the tree it asked through instead of the everyday one.<br>"
			+ "<b>Says</b> and <b>You</b> are one page per line. <b>Choices</b> are one"
			+ " per line, written <code>label -&gt; node-id</code>. <b>Answer</b> is what"
			+ " reaching that node tells the rules.</html>");
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JPanel top = new JPanel(new BorderLayout(6, 6));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(hint, BorderLayout.CENTER);
		treePicker.addActionListener(e ->
		{
			if (!loading)
			{
				showSelectedTree();
			}
		});
		top.add(treePicker, BorderLayout.SOUTH);
		root.add(top, BorderLayout.NORTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane scroll = new JScrollPane(list,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		root.add(scroll, BorderLayout.CENTER);

		JPanel bottom = new JPanel(new BorderLayout());
		bottom.setBackground(ColorScheme.DARK_GRAY_COLOR);
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		bottom.add(status, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton add = new JButton("Add node");
		add.setToolTipText("A new step in this conversation. Point something at it"
			+ " with Next or a Choice, or nothing will ever reach it.");
		add.addActionListener(e -> addBlankRow());
		buttons.add(add);

		JButton reload = new JButton("Reload");
		reload.setToolTipText("Throw away unsaved edits and re-read dialogs.json");
		reload.addActionListener(e -> refresh());
		buttons.add(reload);

		JButton save = new JButton("Save");
		save.addActionListener(e -> save());
		buttons.add(save);
		bottom.add(buttons, BorderLayout.EAST);

		root.add(bottom, BorderLayout.SOUTH);

		setContentPane(root);
		setPreferredSize(new Dimension(700, 700));
		pack();
		setLocationRelativeTo(null);
	}

	public void open()
	{
		refresh();
		setVisible(true);
		toFront();
	}

	private void refresh()
	{
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			parsed = gson.fromJson(reader, DialogFile.class);
		}
		catch (Exception e)
		{
			log.warn("Could not read {}", file, e);
			setStatus("Could not read dialogs.json: " + e.getMessage(), true);
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
			treePicker.addItem(tree.id);
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
			list.revalidate();
			list.repaint();
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
		list.revalidate();
		list.repaint();
	}

	private void addBlankRow()
	{
		DialogTree.DialogNode node = new DialogTree.DialogNode();
		node.id = "node-" + (rows.size() + 1);
		node.says = new ArrayList<>();
		list.add(buildRow(node));
		list.revalidate();
		list.repaint();
	}

	private static final class Row
	{
		final JTextField id = new JTextField();
		final JComboBox<String> speaker = new JComboBox<>(new String[]{"Follower", "You"});
		final JTextArea pages = new JTextArea(2, 40);
		final JTextField next = new JTextField();
		final JTextArea choices = new JTextArea(2, 40);
		final JComboBox<String> answer = new JComboBox<>(new String[]{"—", "yes", "no"});
		boolean deleted;
		JPanel panel;
	}

	private JPanel buildRow(DialogTree.DialogNode node)
	{
		Row row = new Row();
		rows.add(row);

		row.id.setText(node.id == null ? "" : node.id);
		row.speaker.setSelectedIndex(node.isPlayerSpeaking() ? 1 : 0);
		row.pages.setText(String.join("\n", node.pages()));
		row.next.setText(node.next == null ? "" : node.next);
		row.answer.setSelectedItem(node.answer == null ? "—" : node.answer);

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

		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			BorderFactory.createEmptyBorder(6, 6, 6, 6)));

		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(2, 2, 2, 2);
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridy = 0;

		c.gridx = 0;
		c.weightx = 0;
		panel.add(label("Node"), c);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(row.id, c);
		c.gridx = 2;
		c.weightx = 0;
		panel.add(row.speaker, c);
		c.gridx = 3;
		JButton remove = new JButton("Delete");
		remove.setToolTipText("Remove this node. Anything pointing at it will need"
			+ " repointing, and Save will say so.");
		remove.addActionListener(e ->
		{
			row.deleted = true;
			panel.setVisible(false);
		});
		panel.add(remove, c);

		c.gridy = 1;
		c.gridx = 0;
		c.weightx = 0;
		panel.add(label("Says"), c);
		c.gridx = 1;
		c.gridwidth = 3;
		c.weightx = 1;
		panel.add(scrollable(row.pages), c);

		c.gridwidth = 1;
		c.gridy = 2;
		c.gridx = 0;
		c.weightx = 0;
		panel.add(label("Choices"), c);
		c.gridx = 1;
		c.gridwidth = 3;
		c.weightx = 1;
		panel.add(scrollable(row.choices), c);

		c.gridwidth = 1;
		c.gridy = 3;
		c.gridx = 0;
		c.weightx = 0;
		panel.add(label("Next"), c);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(row.next, c);
		c.gridx = 2;
		c.weightx = 0;
		panel.add(label("Answer"), c);
		c.gridx = 3;
		panel.add(row.answer, c);

		row.panel = panel;
		return panel;
	}

	private static JLabel label(String text)
	{
		JLabel jLabel = new JLabel(text);
		jLabel.setFont(FontManager.getRunescapeSmallFont());
		jLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return jLabel;
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

	private void save()
	{
		DialogTree tree = selectedTree();
		if (tree == null)
		{
			return;
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
			node.answer = answer == null || "—".equals(answer) ? null : answer.toString();

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
			setStatus("Not saved — " + String.join("; ", problems), true);
			return;
		}

		try
		{
			writeAtomically(gson.toJson(parsed) + "\n");
			setStatus("Saved — the follower picks it up within a second", false);
			refresh();
		}
		catch (Exception e)
		{
			log.warn("Could not save {}", file, e);
			tree.nodes = previous;
			tree.start = previousStart;
			setStatus("Save failed: " + e.getMessage(), true);
		}
	}

	/**
	 * Replaces the file in one step, via a sibling temp file, with the same
	 * retry-then-fall-back-to-in-place dance phrases.json uses: the plugin polls
	 * this file every couple of ticks, Windows will not rename over an open
	 * handle, and losing somebody's edits is worse than a torn read the loader
	 * already knows how to survive.
	 */
	private void writeAtomically(String contents) throws IOException
	{
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
		Files.write(temp, bytes);

		for (int attempt = 0; attempt < 20; attempt++)
		{
			try
			{
				Files.move(temp, file,
					StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				return;
			}
			catch (AtomicMoveNotSupportedException e)
			{
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
				return;
			}
			catch (FileSystemException e)
			{
				try
				{
					Thread.sleep(25);
				}
				catch (InterruptedException interrupted)
				{
					Thread.currentThread().interrupt();
				}
			}
		}

		Files.write(file, bytes);
		Files.deleteIfExists(temp);
	}

	private void setStatus(String text, boolean bad)
	{
		status.setText(text);
		status.setForeground(bad ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.MEDIUM_GRAY_COLOR);
	}

	/** Mirrors the on-disk shape so the whole file round-trips, not just one tree. */
	private static final class DialogFile
	{
		int version = 1;
		List<DialogTree> trees;
	}
}

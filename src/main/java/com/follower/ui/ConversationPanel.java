package com.follower.ui;

import com.follower.speech.Conversation;
import com.follower.speech.ConversationStore;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The film window's Conversation tab: lines written for the player and the
 * follower, played back in the game as dialogue boxes or chat lines, and
 * saved by name to play again.
 *
 * <p>Everything here is the Swing thread's. Play and Stop go to the client
 * thread through the window's own door, the same as every other button.
 */
class ConversationPanel extends JPanel
{
	/** The saved list's first choice, which is none. */
	static final String NO_SAVED = "Saved conversations";

	private final BiConsumer<Function<FilmDialog.Crew, String>, String> act;
	private final ConversationStore store;
	private final Supplier<String> followerName;
	private final Consumer<String> status;

	/** The lines as written, row for row with the list. */
	private final List<Conversation.Line> lines = new ArrayList<>();
	private final DefaultListModel<String> rows = new DefaultListModel<>();
	private final JList<String> list = new JList<>(rows);

	private final JTextField name = new JTextField();
	private final JComboBox<String> saved = new JComboBox<>();
	private final JComboBox<String> speaker = new JComboBox<>();
	private final JComboBox<String> display = new JComboBox<>(new String[]{"Dialogue box", "Chat line"});
	private final JComboBox<Conversation.Expression> face = new JComboBox<>(Conversation.Expression.values());
	private final JTextArea text = new JTextArea(4, 20);
	private final JRadioButton click = new JRadioButton("Click to continue", true);
	private final JRadioButton auto = new JRadioButton("Auto");
	private final JTextField seconds = new JTextField(4);

	/** Remove, Up and Down work on the line picked, so are off with none. */
	private final JButton remove = button("Remove", this::removeLine);
	private final JButton up = button("Up", () -> move(-1));
	private final JButton down = button("Down", () -> move(1));

	/** Load and Delete work on a saved one, so are off at the list's first choice. */
	private final JButton load = button("Load", this::load);
	private final JButton delete = button("Delete", this::delete);

	/** Set while a line is put into the editor, so its own listeners leave it be. */
	private boolean loading;

	/**
	 * Whether the tab holds edits not saved under its name. Load asks first,
	 * as Messages and Conversations do. QA2, 15 Oct: Load threw them away.
	 */
	private boolean dirty;

	/**
	 * @param act    the window's door to the client thread: the work, and what
	 *               to show once it is done
	 * @param status the window's status line
	 */
	ConversationPanel(BiConsumer<Function<FilmDialog.Crew, String>, String> act, ConversationStore store,
		Supplier<String> followerName, Consumer<String> status)
	{
		super(new BorderLayout(0, 4));
		this.act = act;
		this.store = store;
		this.followerName = followerName;
		this.status = status;
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		add(savedRows(), BorderLayout.NORTH);
		JPanel columns = new JPanel(new GridLayout(1, 2, 12, 0));
		columns.setBackground(ColorScheme.DARK_GRAY_COLOR);
		columns.add(lineList());
		columns.add(editor());
		add(columns, BorderLayout.CENTER);
		add(playing(), BorderLayout.SOUTH);

		refreshNames();
		refreshSaved(null);
		edit(-1);

		DocumentListener marks = new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				dirty = true;
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				dirty = true;
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				dirty = true;
			}
		};
		name.getDocument().addDocumentListener(marks);
		seconds.getDocument().addDocumentListener(marks);
		auto.addItemListener(e -> dirty = true);
	}

	/** The follower's name again, as the speaker choice and the rows show it. */
	void refreshNames()
	{
		loading = true;
		int picked = speaker.getSelectedIndex();
		speaker.removeAllItems();
		speaker.addItem("You");
		speaker.addItem(followerName.get());
		speaker.setSelectedIndex(Math.max(0, picked));
		loading = false;
		refreshRows();
	}

	/** Name and Save, then the saved list with Load and Delete. */
	private JPanel savedRows()
	{
		JPanel panel = column();
		panel.add(heading("CONVERSATION"));

		JPanel naming = new JPanel(new BorderLayout(6, 0));
		naming.setBackground(ColorScheme.DARK_GRAY_COLOR);
		naming.setAlignmentX(LEFT_ALIGNMENT);
		naming.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		naming.add(label("Name"), BorderLayout.WEST);
		name.setToolTipText("Saving under a name already used writes over that one");
		name.addActionListener(e -> save());
		naming.add(name, BorderLayout.CENTER);
		naming.add(button("Save", this::save), BorderLayout.EAST);
		panel.add(naming);
		panel.add(Box.createVerticalStrut(4));

		JPanel loadRow = new JPanel(new BorderLayout(6, 0));
		loadRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		loadRow.setAlignmentX(LEFT_ALIGNMENT);
		loadRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		loadRow.add(saved, BorderLayout.CENTER);
		saved.addActionListener(e ->
		{
			load.setEnabled(pickedSaved() != null);
			delete.setEnabled(pickedSaved() != null);
		});
		JPanel pair = grid(1, 2);
		pair.add(load);
		pair.add(delete);
		loadRow.add(pair, BorderLayout.EAST);
		panel.add(loadRow);
		return panel;
	}

	/** The lines in order, and Add, Remove, Up and Down. */
	private JPanel lineList()
	{
		JPanel panel = column();
		panel.add(heading("LINES"));
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFont(FontManager.getRunescapeSmallFont());
		list.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		list.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		list.addListSelectionListener(e ->
		{
			if (!e.getValueIsAdjusting() && !loading)
			{
				edit(list.getSelectedIndex());
			}
		});
		JScrollPane scroll = new JScrollPane(list,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setAlignmentX(LEFT_ALIGNMENT);
		scroll.setPreferredSize(new Dimension(260, 200));
		panel.add(scroll);
		panel.add(Box.createVerticalStrut(4));
		JPanel buttons = grid(2, 2);
		buttons.add(button("Add", this::addLine));
		buttons.add(remove);
		buttons.add(up);
		buttons.add(down);
		panel.add(buttons);
		return panel;
	}

	/** The line picked: who says it, how, with what face, and what. */
	private JPanel editor()
	{
		JPanel panel = column();
		panel.add(heading("LINE"));

		JPanel choices = new JPanel(new GridLayout(3, 2, 6, 4));
		choices.setBackground(ColorScheme.DARK_GRAY_COLOR);
		choices.setAlignmentX(LEFT_ALIGNMENT);
		choices.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26 * 3 + 8));
		choices.add(label("Speaker"));
		choices.add(speaker);
		choices.add(label("Show as"));
		display.setToolTipText("A dialogue box like an NPC's, or a chat line with the words overhead");
		choices.add(display);
		choices.add(label("Face"));
		face.setToolTipText("The chathead's expression, for a dialogue box");
		choices.add(face);
		panel.add(choices);
		panel.add(Box.createVerticalStrut(6));

		panel.add(label("Text"));
		panel.add(Box.createVerticalStrut(2));
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setToolTipText("A long line goes onto more pages, as in the game."
			+ " Enter starts a new line in a dialogue box.");
		// Enter is the line break here, so Tab moves on, as everywhere else.
		text.setFocusTraversalKeys(java.awt.KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
		text.setFocusTraversalKeys(java.awt.KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
		JScrollPane scroll = new JScrollPane(text,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setAlignmentX(LEFT_ALIGNMENT);
		scroll.setPreferredSize(new Dimension(260, 110));
		panel.add(scroll);

		speaker.addActionListener(e -> changed());
		display.addActionListener(e -> changed());
		face.addActionListener(e -> changed());
		text.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				changed();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				changed();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				changed();
			}
		});
		return panel;
	}

	/** How it moves on, and Play and Stop. */
	private JPanel playing()
	{
		JPanel panel = column();
		panel.add(heading("PLAY"));

		ButtonGroup group = new ButtonGroup();
		group.add(click);
		group.add(auto);
		click.setToolTipText("Each dialogue box waits for a click, as in the game."
			+ " Chat lines move on once read.");
		auto.setToolTipText("Every line moves on by itself, for recording");
		seconds.setToolTipText("Seconds for each line or page. Blank: as long as it takes"
			+ " to read, at the reading speed setting.");
		JPanel advance = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
		advance.setBackground(ColorScheme.DARK_GRAY_COLOR);
		advance.setAlignmentX(LEFT_ALIGNMENT);
		advance.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		for (JRadioButton radio : new JRadioButton[]{click, auto})
		{
			radio.setFont(FontManager.getRunescapeSmallFont());
			radio.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			radio.setBackground(ColorScheme.DARK_GRAY_COLOR);
			radio.setFocusPainted(false);
			advance.add(radio);
		}
		advance.add(label("Seconds"));
		// Only Auto reads them.
		seconds.setEnabled(auto.isSelected());
		auto.addItemListener(e -> seconds.setEnabled(auto.isSelected()));
		advance.add(seconds);
		advance.add(label("(blank: reading speed)"));
		panel.add(advance);
		panel.add(Box.createVerticalStrut(4));

		JPanel buttons = grid(1, 2);
		JButton play = button("Play", this::play);
		play.setToolTipText("Plays it in the game from the first line. Esc or Stop ends it.");
		buttons.add(play);
		buttons.add(button("Stop", () -> act.accept(c -> c.stopConversation(), null)));
		panel.add(buttons);
		return panel;
	}

	/** Puts a line into the editor, or empties it for none. */
	private void edit(int index)
	{
		loading = true;
		boolean any = index >= 0 && index < lines.size();
		speaker.setEnabled(any);
		display.setEnabled(any);
		text.setEnabled(any);
		remove.setEnabled(any);
		up.setEnabled(any);
		down.setEnabled(any);
		if (any)
		{
			Conversation.Line line = lines.get(index);
			speaker.setSelectedIndex(line.speaker == Conversation.Speaker.YOU ? 0 : 1);
			display.setSelectedIndex(line.display == Conversation.Display.BOX ? 0 : 1);
			face.setSelectedItem(line.expression);
			text.setText(line.text);
		}
		else
		{
			text.setText("");
		}
		face.setEnabled(any && display.getSelectedIndex() == 0);
		loading = false;
	}

	/** The editor's choices into the line picked. */
	private void changed()
	{
		if (loading)
		{
			return;
		}
		int index = list.getSelectedIndex();
		if (index < 0 || index >= lines.size())
		{
			return;
		}
		Conversation.Line line = lines.get(index);
		line.speaker = speaker.getSelectedIndex() == 1 ? Conversation.Speaker.FOLLOWER : Conversation.Speaker.YOU;
		line.display = display.getSelectedIndex() == 1 ? Conversation.Display.CHAT : Conversation.Display.BOX;
		line.expression = (Conversation.Expression) face.getSelectedItem();
		line.text = text.getText();
		face.setEnabled(line.display == Conversation.Display.BOX);
		rows.set(index, row(index, line, followerName.get()));
		dirty = true;
	}

	/**
	 * A new line after the one picked, or at the end: the other speaker's,
	 * shown the same way, so a conversation goes back and forth as written.
	 */
	private void addLine()
	{
		int at = list.getSelectedIndex() >= 0 ? list.getSelectedIndex() + 1 : lines.size();
		Conversation.Line line = new Conversation.Line();
		if (at > 0)
		{
			Conversation.Line before = lines.get(at - 1);
			line.speaker = before.speaker == Conversation.Speaker.YOU
				? Conversation.Speaker.FOLLOWER : Conversation.Speaker.YOU;
			line.display = before.display;
		}
		lines.add(at, line);
		dirty = true;
		refreshRows();
		select(at);
		text.requestFocusInWindow();
	}

	private void removeLine()
	{
		int index = list.getSelectedIndex();
		if (index < 0)
		{
			status.accept("Pick a line first.");
			return;
		}
		lines.remove(index);
		dirty = true;
		refreshRows();
		select(Math.min(index, lines.size() - 1));
	}

	private void move(int by)
	{
		int index = list.getSelectedIndex();
		int to = index + by;
		if (index < 0 || to < 0 || to >= lines.size())
		{
			return;
		}
		lines.add(to, lines.remove(index));
		dirty = true;
		refreshRows();
		select(to);
	}

	private void select(int index)
	{
		if (index >= 0)
		{
			list.setSelectedIndex(index);
			list.ensureIndexIsVisible(index);
		}
		else
		{
			list.clearSelection();
		}
		edit(index);
	}

	private void refreshRows()
	{
		int picked = list.getSelectedIndex();
		loading = true;
		rows.clear();
		String follower = followerName.get();
		for (int i = 0; i < lines.size(); i++)
		{
			rows.addElement(row(i, lines.get(i), follower));
		}
		if (picked >= 0 && picked < lines.size())
		{
			list.setSelectedIndex(picked);
		}
		loading = false;
	}

	/** A line as its row reads: its number, who, how, the face for a box, and the start of the words. */
	static String row(int index, Conversation.Line line, String followerName)
	{
		String who = line.speaker == Conversation.Speaker.YOU ? "You" : followerName;
		String how = line.display == Conversation.Display.BOX ? "box, " + line.expression.label : "chat";
		String words = line.text.replaceAll("\\s+", " ").trim();
		return (index + 1) + ". " + who + " (" + how + "): " + (words.isEmpty() ? "..." : words);
	}

	/** The conversation as the tab has it now. */
	Conversation script()
	{
		Conversation out = new Conversation();
		out.name = name.getText().trim();
		out.advance = auto.isSelected() ? Conversation.Advance.AUTO : Conversation.Advance.CLICK;
		// Click to continue never reads them, so whatever is typed there is no time.
		out.seconds = Math.max(0, secondsTyped());
		for (Conversation.Line line : lines)
		{
			out.lines.add(line.copy());
		}
		return out;
	}

	/** The seconds typed, 0 for blank, or -1 for something that is not a time. */
	private double secondsTyped()
	{
		String typed = seconds.getText().trim();
		if (typed.isEmpty())
		{
			return 0;
		}
		try
		{
			double value = Double.parseDouble(typed);
			return value > 0 && value <= 600 ? value : -1;
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	/** Puts a conversation into the tab. */
	void show(Conversation conversation)
	{
		name.setText(conversation.name);
		(conversation.advance == Conversation.Advance.AUTO ? auto : click).setSelected(true);
		seconds.setText(conversation.seconds > 0 ? trim(conversation.seconds) : "");
		lines.clear();
		for (Conversation.Line line : conversation.lines)
		{
			lines.add(line.copy());
		}
		list.clearSelection();
		refreshRows();
		select(lines.isEmpty() ? -1 : 0);
		dirty = false;
	}

	/** 3 rather than 3.0. */
	private static String trim(double value)
	{
		return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

	/** Whether Auto is picked with seconds that are not a time. Only Auto reads them. */
	private boolean badSeconds()
	{
		return auto.isSelected() && secondsTyped() < 0;
	}

	private void play()
	{
		if (badSeconds())
		{
			status.accept("Type the seconds as a number, such as 3.5, or leave it blank.");
			return;
		}
		Conversation script = script();
		String refused = script.refusal();
		if (refused != null)
		{
			status.accept(refused);
			return;
		}
		act.accept(c -> c.play(script), null);
	}

	/** @return whether it was saved */
	private boolean save()
	{
		Conversation script = script();
		if (script.name.isEmpty())
		{
			status.accept("Type a name first.");
			return false;
		}
		if (badSeconds())
		{
			status.accept("Type the seconds as a number, such as 3.5, or leave it blank.");
			return false;
		}
		if (!store.save(script))
		{
			status.accept("Could not save it. The client's log says why.");
			return false;
		}
		refreshSaved(script.name);
		status.accept("Saved as " + script.name + ".");
		dirty = false;
		return true;
	}

	private void load()
	{
		String picked = pickedSaved();
		Conversation found = picked == null ? null : store.get(picked);
		if (found == null)
		{
			status.accept("Pick a saved conversation first.");
			return;
		}
		if (!confirmDirty())
		{
			return;
		}
		// Read again: Save may just have written over this very one.
		found = store.get(picked);
		show(found);
		status.accept("Loaded " + found.name + ".");
	}

	/** Save, Discard or Cancel over unsaved edits, as Messages asks; false for Cancel. */
	private boolean confirmDirty()
	{
		if (!dirty)
		{
			return true;
		}
		int choice = askToSave();
		if (choice == 0)
		{
			return save();
		}
		return choice == 1;
	}

	/** 0 for Save, 1 for Discard, anything else for Cancel. */
	int askToSave()
	{
		return JOptionPane.showOptionDialog(this,
			"There are unsaved changes.", "Save changes?",
			JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
			new Object[]{"Save", "Discard", "Cancel"}, "Save");
	}

	private void delete()
	{
		String picked = pickedSaved();
		if (picked == null)
		{
			status.accept("Pick a saved conversation first.");
			return;
		}
		int sure = JOptionPane.showConfirmDialog(this, "Delete the saved conversation \"" + picked + "\"?",
			"Delete", JOptionPane.YES_NO_OPTION);
		if (sure != JOptionPane.YES_OPTION)
		{
			return;
		}
		if (!store.delete(picked))
		{
			status.accept("Could not delete it. The client's log says why.");
			return;
		}
		refreshSaved(null);
		status.accept("Deleted " + picked + ".");
	}

	private String pickedSaved()
	{
		return saved.getSelectedIndex() > 0 ? (String) saved.getSelectedItem() : null;
	}

	/** The saved list again, with one picked, or none. */
	private void refreshSaved(String pick)
	{
		saved.removeAllItems();
		saved.addItem(NO_SAVED);
		for (String each : store.names())
		{
			saved.addItem(each);
			if (each.equalsIgnoreCase(pick == null ? "" : pick.trim()))
			{
				saved.setSelectedItem(each);
			}
		}
	}

	private JPanel column()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return panel;
	}

	private JPanel grid(int rows, int columns)
	{
		JPanel panel = new JPanel(new GridLayout(rows, columns, 6, 4));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(LEFT_ALIGNMENT);
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26 * rows + 4 * (rows - 1)));
		return panel;
	}

	private static JLabel heading(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
	}

	private static JLabel label(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
	}

	private static JButton button(String text, Runnable action)
	{
		JButton button = new JButton(text);
		button.setFocusPainted(false);
		button.addActionListener(e -> action.run());
		return button;
	}
}

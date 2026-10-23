// public:file posing
package com.follower.ui;

import com.follower.follower.FilmSet;
import com.follower.speech.Conversation;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The film window: the follower posed, turned and given its lines while the
 * player records. A window beside the game rather than anything in it, so
 * none of it is in the shot.
 *
 * <p>Every button is handed over to the client thread, where the follower
 * and the player live, and what comes back shows on the status line here.
 * Never in the game's chat, which would be in the recording.
 */
public class FilmDialog extends JDialog
{
	/**
	 * What the window asks of the plugin. Each call is made on the client
	 * thread and returns why it could not be done, or null once it is.
	 */
	public interface Crew
	{
		/** The window opened, or a button was pressed after a logout ended the scene. */
		String start();

		String hold(int animationId);

		String once(int animationId);

		/** The last pose played once, again. */
		String again();

		String standNormally();

		/** The player's last animation, or -1. */
		int lastPlayerAnimation();

		/** An actor orientation: 0 south, 512 west, 1024 north, 1536 east. */
		String face(int orientation);

		String faceMe();

		String faceCamera();

		String holdHere();

		String followMe();

		String followerSays(String text);

		String playerSays(String text);

		/** Plays a written conversation from its first line, over any playing. */
		String play(com.follower.speech.Conversation script);

		/** Ends the conversation playing, if one is. */
		String stopConversation();

		/** The window closed: the scene is over. */
		void cut();
	}

	/** Past this a line may run off the screen: the game never wraps overhead text. */
	static final int LONG_LINE = 80;

	/** The group dropdown's first choice, which leaves every group in. */
	static final String ALL_GROUPS = "All groups";

	/** The compass, as it sits on the grid, with each point's actor orientation. */
	private static final String[] POINTS = {"NW", "N", "NE", "W", "Me", "E", "SW", "S", "SE"};
	private static final int[] ORIENTATIONS = {768, 1024, 1280, 512, -1, 1536, 256, 0, 1792};

	private final Crew crew;
	private final Consumer<Runnable> clientThread;
	private final JLabel status = new JLabel(" ");
	private final JTextField customId = new JTextField(6);
	private final JTextField search = new JTextField();
	private final JComboBox<String> group = new JComboBox<>();
	private final JCheckBox showAll = new JCheckBox("Show all");
	private final DefaultListModel<String> rows = new DefaultListModel<>();
	/** The poses the list shows now, row for row. */
	private List<FilmSet.Pose> shown = new ArrayList<>();

	private final ConversationPanel conversation;

	/**
	 * @param clientThread runs a task on the client thread
	 * @param store        the saved conversations
	 * @param followerName the follower's name, as the Conversation tab shows it
	 */
	public FilmDialog(Crew crew, Consumer<Runnable> clientThread,
		com.follower.speech.ConversationStore store, java.util.function.Supplier<String> followerName)
	{
		super((java.awt.Frame) null, "Follower Buddy - Advanced posing", false);
		this.crew = crew;
		this.clientThread = clientThread;
		setDefaultCloseOperation(HIDE_ON_CLOSE);
		// Closing the window is the end of the scene.
		addWindowListener(new java.awt.event.WindowAdapter()
		{
			@Override
			public void windowClosing(java.awt.event.WindowEvent e)
			{
				act(c ->
				{
					c.cut();
					return null;
				}, null);
			}
		});

		JPanel root = new JPanel(new BorderLayout(0, 8));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel hint = new JLabel("<html><body style='width:440px'>"
			+ "The follower holds still and keeps quiet until you close this window."
			+ " To move it, hold Shift, right-click the ground and choose Send."
			+ " Your own lines show over your head on your screen only: nothing is"
			+ " sent, and nobody else sees them.</body></html>");
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		root.add(hint, BorderLayout.NORTH);

		JPanel columns = new JPanel(new GridLayout(1, 2, 12, 0));
		columns.setBackground(ColorScheme.DARK_GRAY_COLOR);
		columns.add(poses());
		columns.add(placing());
		JPanel posing = new JPanel(new BorderLayout(0, 8));
		posing.setBackground(ColorScheme.DARK_GRAY_COLOR);
		posing.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		posing.add(columns, BorderLayout.CENTER);

		JPanel lines = column();
		lines.add(heading("FOLLOWER SAYS"));
		lines.add(sayRow(Crew::followerSays, "Said.", null));
		lines.add(heading("YOU SAY"));
		lines.add(sayRow(Crew::playerSays, "Over your head, on your screen only.",
			"Shows over your head, on your screen only."));
		posing.add(lines, BorderLayout.SOUTH);

		conversation = new ConversationPanel(this::act, store, followerName, status::setText);
		javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
		tabs.addTab("Posing", posing);
		tabs.addTab("Scene", conversation);
		root.add(tabs, BorderLayout.CENTER);

		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(Color.WHITE);
		status.setAlignmentX(LEFT_ALIGNMENT);
		root.add(status, BorderLayout.SOUTH);

		// Esc stops a conversation playing, here as in the game.
		getRootPane().registerKeyboardAction(e -> act(c -> c.stopConversation(), null),
			javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
			javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);

		setContentPane(root);
		// Both tabs need about 700 with the title bar. QA2, 15 Oct: at 760
		// a 1366x768 laptop, about 728 above the taskbar, hid the status line
		// under the taskbar; a still smaller screen gets what it has.
		setPreferredSize(new Dimension(680, 700));
		pack();
		// A dialog can be dragged smaller than its layout; below about 620
		// wide the hint and the saved names were cut (QA2, 15 Oct).
		setMinimumSize(new Dimension(620, 560));
		java.awt.GraphicsConfiguration screen = getGraphicsConfiguration();
		setSize(getWidth(), heightOnScreen(getHeight(), screen.getBounds(),
			java.awt.Toolkit.getDefaultToolkit().getScreenInsets(screen)));
		setLocationRelativeTo(null);
	}

	/** A window height cut down, if need be, to the screen less its taskbar. */
	static int heightOnScreen(int wanted, java.awt.Rectangle screen, java.awt.Insets taskbars)
	{
		return Math.min(wanted, screen.height - taskbars.top - taskbars.bottom);
	}

	/** Shows the window and starts the scene, or carries on with the one under way. */
	public void open()
	{
		conversation.refreshNames();
		setVisible(true);
		toFront();
		act(Crew::start, "Posing. It holds still and keeps quiet until you close this window.");
	}

	/** A word from outside the window, such as a Send it could not walk. Swing thread. */
	public void status(String text)
	{
		status.setText(text);
	}

	/**
	 * The one door to the crew: over to the client thread, and what comes
	 * back onto the status line on this one. A refusal shows in place of
	 * what was done.
	 */
	private void act(Function<Crew, String> work, String done)
	{
		clientThread.accept(() ->
		{
			String said = work.apply(crew);
			String shown = said != null ? said : done;
			if (shown != null)
			{
				SwingUtilities.invokeLater(() -> status.setText(shown));
			}
		});
	}

	/**
	 * The pose list with its search, group and Show all, then Again and Stand
	 * normally, Copy my emote and the custom id.
	 */
	private JPanel poses()
	{
		JPanel panel = column();
		panel.add(heading("POSE"));

		search.setToolTipText("Find a pose by its name or its animation id");
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				refilter();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				refilter();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				refilter();
			}
		});
		JPanel find = new JPanel(new BorderLayout(6, 0));
		find.setBackground(ColorScheme.DARK_GRAY_COLOR);
		find.setAlignmentX(LEFT_ALIGNMENT);
		find.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		JLabel findLabel = new JLabel("Find");
		findLabel.setFont(FontManager.getRunescapeSmallFont());
		findLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		find.add(findLabel, BorderLayout.WEST);
		find.add(search, BorderLayout.CENTER);
		panel.add(find);
		panel.add(javax.swing.Box.createVerticalStrut(4));

		group.addItem(ALL_GROUPS);
		for (String name : FilmSet.GROUPS)
		{
			group.addItem(name);
		}
		group.addActionListener(e -> refilter());
		showAll.setToolTipText("Also list the quest, cutscene and creature animations that fit the follower");
		showAll.setFont(FontManager.getRunescapeSmallFont());
		showAll.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		showAll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		showAll.setFocusPainted(false);
		showAll.addActionListener(e -> refilter());
		JPanel filters = new JPanel(new BorderLayout(6, 0));
		filters.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filters.setAlignmentX(LEFT_ALIGNMENT);
		filters.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		filters.add(group, BorderLayout.CENTER);
		filters.add(showAll, BorderLayout.EAST);
		panel.add(filters);
		panel.add(javax.swing.Box.createVerticalStrut(4));

		JList<String> list = new JList<>(rows);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFont(FontManager.getRunescapeSmallFont());
		list.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		list.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		list.setToolTipText("[hold] holds the pose until you choose another or Stand normally,"
			+ " [once] plays it through, [prop] puts something in its hands");
		// A click, not a change of selection, so the same row clicked again
		// strikes the pose again.
		list.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				int index = list.locationToIndex(e.getPoint());
				if (index >= 0 && index < shown.size() && list.getCellBounds(index, index).contains(e.getPoint()))
				{
					FilmSet.Pose pose = shown.get(index);
					act(c -> pose.hold ? c.hold(pose.id) : c.once(pose.id),
						(pose.hold ? "Holding: " : "Playing: ") + pose.name + ".");
				}
			}
		});
		// No sideways scrollbar: it covered the last row it could show. A row
		// too long for the column is cut at the edge instead.
		JScrollPane scroll = new JScrollPane(list,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setAlignmentX(LEFT_ALIGNMENT);
		// 4 shorter for the gap below it, so the window still fits in 700.
		scroll.setPreferredSize(new Dimension(260, 226));
		panel.add(scroll);
		refilter();
		// QA2, 15 Oct: without a gap the last row showing looked cut by the buttons.
		panel.add(javax.swing.Box.createVerticalStrut(4));

		JPanel pair = grid(1, 2);
		pair.add(button("Again", () -> act(Crew::again, "Again.")));
		pair.add(button("Stand normally", () -> act(Crew::standNormally, "Standing normally.")));
		panel.add(pair);

		JButton copy = button("Copy my emote", this::copyMyEmote);
		copy.setToolTipText("Do any emote in game first, then press this to put its id in the box");
		JPanel copyRow = grid(1, 1);
		copyRow.add(copy);
		panel.add(copyRow);

		JPanel custom = new JPanel(new BorderLayout(6, 0));
		custom.setBackground(ColorScheme.DARK_GRAY_COLOR);
		custom.setAlignmentX(LEFT_ALIGNMENT);
		custom.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		JLabel label = new JLabel("Custom id");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		custom.add(label, BorderLayout.WEST);
		custom.add(customId, BorderLayout.CENTER);
		JPanel holdOrOnce = grid(1, 2);
		holdOrOnce.add(button("Hold", () -> custom(true)));
		holdOrOnce.add(button("Once", () -> custom(false)));
		custom.add(holdOrOnce, BorderLayout.EAST);
		panel.add(javax.swing.Box.createVerticalStrut(4));
		panel.add(custom);
		return panel;
	}

	/** The list again, as the search, the group and Show all now have it. Swing thread. */
	private void refilter()
	{
		shown = filter(FilmSet.POSES, search.getText(), (String) group.getSelectedItem(), showAll.isSelected());
		rows.clear();
		List<String> lines = new ArrayList<>(shown.size());
		for (FilmSet.Pose pose : shown)
		{
			lines.add(row(pose));
		}
		rows.addAll(lines);
	}

	/** A pose as its row reads: its name, whether it holds or plays once, and whether it holds a prop. */
	static String row(FilmSet.Pose pose)
	{
		return pose.name + (pose.hold ? "  [hold]" : "  [once]") + (pose.holdsItem() ? "  [prop]" : "");
	}

	/**
	 * The poses that match: every word of the search in the name, or the
	 * search as the animation id; in the group chosen, or any for
	 * {@link #ALL_GROUPS}; and only the first two tiers unless Show all is
	 * ticked.
	 */
	static List<FilmSet.Pose> filter(List<FilmSet.Pose> poses, String search, String group, boolean showAll)
	{
		String text = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
		String[] words = text.isEmpty() ? new String[0] : text.split("\\s+");
		List<FilmSet.Pose> out = new ArrayList<>();
		for (FilmSet.Pose pose : poses)
		{
			if (!showAll && pose.tier == 'C')
			{
				continue;
			}
			if (group != null && !group.equals(ALL_GROUPS) && !group.equals(pose.group))
			{
				continue;
			}
			if (text.equals(String.valueOf(pose.id)) || containsAll(pose.name.toLowerCase(Locale.ROOT), words))
			{
				out.add(pose);
			}
		}
		return out;
	}

	private static boolean containsAll(String name, String[] words)
	{
		for (String word : words)
		{
			if (!name.contains(word))
			{
				return false;
			}
		}
		return true;
	}

	/** The compass, Camera, and Stay here or Follow me. */
	private JPanel placing()
	{
		JPanel panel = column();
		panel.add(heading("FACING"));
		JPanel compass = grid(3, 3);
		compass.setMaximumSize(new Dimension(Integer.MAX_VALUE, 84));
		for (int i = 0; i < POINTS.length; i++)
		{
			int orientation = ORIENTATIONS[i];
			String point = POINTS[i];
			compass.add(button(point, orientation < 0
				? () -> act(Crew::faceMe, "Facing you.")
				: () -> act(c -> c.face(orientation), "Facing " + point + ".")));
		}
		panel.add(compass);
		JButton camera = button("Camera", () -> act(Crew::faceCamera, "Facing the camera."));
		camera.setToolTipText("Turns it to face the camera");
		JPanel cameraRow = grid(1, 1);
		cameraRow.add(camera);
		panel.add(cameraRow);

		panel.add(heading("POSITION"));
		JPanel position = grid(1, 2);
		position.add(button("Stay here", () -> act(Crew::holdHere, "Staying here.")));
		position.add(button("Follow me", () -> act(Crew::followMe, "Following you, and still quiet.")));
		panel.add(position);
		return panel;
	}

	/** A line to type, Say, and this session's earlier lines for a retake. */
	private JPanel sayRow(BiFunction<Crew, String, String> say, String done, String tip)
	{
		JTextField line = new JTextField();
		line.setToolTipText(tip);
		JComboBox<String> earlier = new JComboBox<>();
		earlier.addItem("Earlier lines");
		Runnable send = () ->
		{
			String typed = line.getText().trim();
			String refused = refusal(typed);
			if (refused != null)
			{
				status.setText(refused);
				return;
			}
			// Said as the Conversation tab says a chat line, a pasted dash or
			// curly quote written plain.
			String text = asSaid(typed);
			remember(earlier, text);
			act(c -> say.apply(c, text), done(done, text));
		};
		line.addActionListener(e -> send.run());
		earlier.addActionListener(e ->
		{
			if (earlier.getSelectedIndex() > 0)
			{
				line.setText((String) earlier.getSelectedItem());
				line.requestFocusInWindow();
				SwingUtilities.invokeLater(() -> earlier.setSelectedIndex(0));
			}
		});

		JPanel typed = new JPanel(new BorderLayout(6, 0));
		typed.setBackground(ColorScheme.DARK_GRAY_COLOR);
		typed.add(line, BorderLayout.CENTER);
		typed.add(button("Say", send), BorderLayout.EAST);

		JPanel row = new JPanel(new GridLayout(2, 1, 0, 4));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
		row.add(typed);
		row.add(earlier);
		return row;
	}

	/** The most recent first, and each line once. */
	private static void remember(JComboBox<String> earlier, String text)
	{
		earlier.removeItem(text);
		earlier.insertItemAt(text, 1);
		earlier.setSelectedIndex(0);
	}

	private void copyMyEmote()
	{
		act(c ->
		{
			int id = c.lastPlayerAnimation();
			if (id == -1)
			{
				return "Do an emote in game first, then press this.";
			}
			SwingUtilities.invokeLater(() -> customId.setText(String.valueOf(id)));
			return "Your last animation was " + id + ". Hold or Once?";
		}, null);
	}

	private void custom(boolean hold)
	{
		int id;
		try
		{
			id = Integer.parseInt(customId.getText().trim());
		}
		catch (NumberFormatException e)
		{
			id = 0;
		}
		if (id <= 0)
		{
			status.setText("Type an animation id in the box first.");
			return;
		}
		int chosen = id;
		act(c -> hold ? c.hold(chosen) : c.once(chosen),
			(hold ? "Holding animation " : "Playing animation ") + id + ".");
	}

	/**
	 * Why a typed line cannot be said, or null: a character the game has no
	 * glyph for ({@link #undrawable}), or an angle bracket, which is read as
	 * the start of a colour tag. A pasted dash or curly quote is said plain.
	 */
	static String refusal(String text)
	{
		if (text.isEmpty())
		{
			return "Type a line first.";
		}
		for (int i = 0; i < text.length(); )
		{
			String one = new String(Character.toChars(text.codePointAt(i)));
			i += one.length();
			if (one.equals("<") || one.equals(">") || undrawable(one) != null)
			{
				return "The game can't show '" + one + "' overhead. Use plain letters and punctuation.";
			}
		}
		return null;
	}

	/** A typed line as the game shows it said: Conversation.shown for a chat line. */
	static String asSaid(String typed)
	{
		return Conversation.shown(new Conversation.Line(Conversation.Speaker.YOU, Conversation.Display.CHAT,
			Conversation.Expression.NEUTRAL, typed));
	}

	/**
	 * The first character of a typed line the game has no glyph for, or
	 * null. One check for both tabs: the Say boxes refuse it, and the
	 * Conversation tab, which plays it as a '?', says so. QA2, 15 Oct: the
	 * Say boxes refused a pasted dash the Conversation tab wrote plain.
	 */
	static String undrawable(String typed)
	{
		for (int i = 0; i < typed.length(); )
		{
			int c = typed.codePointAt(i);
			String one = new String(Character.toChars(c));
			i += one.length();
			if (c != '?' && asSaid(one).contains("?"))
			{
				return one;
			}
		}
		return null;
	}

	/** A warning for a line long enough to run off the screen overhead, or null. */
	static String longLine(String text)
	{
		return text.length() > LONG_LINE
			? "At " + text.length() + " characters it may run off the screen: overhead text never wraps."
			: null;
	}

	/** What was done, with a warning when the line is long enough to run off the screen. */
	static String done(String done, String text)
	{
		String warning = longLine(text);
		return warning == null ? done : done + " " + warning;
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

	private JLabel heading(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
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

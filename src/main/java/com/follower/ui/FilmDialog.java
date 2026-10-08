package com.follower.ui;

import com.follower.follower.FilmSet;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
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

		/** The window closed: the scene is over. */
		void cut();
	}

	/** Past this a line may run off the screen: the game never wraps overhead text. */
	static final int LONG_LINE = 80;

	/** The compass, as it sits on the grid, with each point's actor orientation. */
	private static final String[] POINTS = {"NW", "N", "NE", "W", "Me", "E", "SW", "S", "SE"};
	private static final int[] ORIENTATIONS = {768, 1024, 1280, 512, -1, 1536, 256, 0, 1792};

	private final Crew crew;
	private final Consumer<Runnable> clientThread;
	private final JLabel status = new JLabel(" ");
	private final JTextField customId = new JTextField(6);

	/**
	 * @param clientThread runs a task on the client thread
	 */
	public FilmDialog(Crew crew, Consumer<Runnable> clientThread)
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
		root.add(columns, BorderLayout.CENTER);

		JPanel lines = column();
		lines.add(heading("FOLLOWER SAYS"));
		lines.add(sayRow(Crew::followerSays, "Said."));
		lines.add(heading("YOU SAY (your screen only)"));
		lines.add(sayRow(Crew::playerSays, "Over your head, on your screen only."));
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(Color.WHITE);
		status.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		status.setAlignmentX(LEFT_ALIGNMENT);
		lines.add(status);
		root.add(lines, BorderLayout.SOUTH);

		setContentPane(root);
		setPreferredSize(new Dimension(600, 640));
		pack();
		setLocationRelativeTo(null);
	}

	/** Shows the window and starts the scene, or carries on with the one under way. */
	public void open()
	{
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

	/** The pose list, Again and Stand normally, Copy my emote and the custom id. */
	private JPanel poses()
	{
		JPanel panel = column();
		panel.add(heading("POSE"));

		String[] rows = new String[FilmSet.POSES.size()];
		for (int i = 0; i < rows.length; i++)
		{
			FilmSet.Pose pose = FilmSet.POSES.get(i);
			rows[i] = pose.name + (pose.hold ? "  [hold]" : "  [once]");
		}
		JList<String> list = new JList<>(rows);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFont(FontManager.getRunescapeSmallFont());
		list.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		list.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		// A click, not a change of selection, so the same row clicked again
		// strikes the pose again.
		list.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				int index = list.locationToIndex(e.getPoint());
				if (index >= 0 && list.getCellBounds(index, index).contains(e.getPoint()))
				{
					FilmSet.Pose pose = FilmSet.POSES.get(index);
					act(c -> pose.hold ? c.hold(pose.id) : c.once(pose.id),
						(pose.hold ? "Holding: " : "Playing: ") + pose.name + ".");
				}
			}
		});
		JScrollPane scroll = new JScrollPane(list);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setAlignmentX(LEFT_ALIGNMENT);
		scroll.setPreferredSize(new Dimension(260, 210));
		panel.add(scroll);

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

	/** The compass, Camera, and Hold here or Follow me. */
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
		camera.setToolTipText("Turns it to face the way the camera looks from");
		JPanel cameraRow = grid(1, 1);
		cameraRow.add(camera);
		panel.add(cameraRow);

		panel.add(heading("POSITION"));
		JPanel position = grid(1, 2);
		position.add(button("Hold here", () -> act(Crew::holdHere, "Holding here.")));
		position.add(button("Follow me", () -> act(Crew::followMe, "Following you, and still quiet.")));
		panel.add(position);
		return panel;
	}

	/** A line to type, Say, and this session's earlier lines for a retake. */
	private JPanel sayRow(BiFunction<Crew, String, String> say, String done)
	{
		JTextField line = new JTextField();
		JComboBox<String> earlier = new JComboBox<>();
		earlier.addItem("Earlier lines");
		Runnable send = () ->
		{
			String text = line.getText().trim();
			String refused = refusal(text);
			if (refused != null)
			{
				status.setText(refused);
				return;
			}
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
	 * Why a typed line cannot be said, or null. The game's overhead font
	 * draws plain ASCII, and a dash or a curly quote pasted in from
	 * elsewhere comes out as a box; an angle bracket is read as the start
	 * of a colour tag.
	 */
	static String refusal(String text)
	{
		if (text.isEmpty())
		{
			return "Type a line first.";
		}
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (c < 0x20 || c > 0x7E || c == '<' || c == '>')
			{
				return "The game can't show '" + c + "' overhead. Use plain letters and punctuation.";
			}
		}
		return null;
	}

	/** What was done, with a warning when the line is long enough to run off the screen. */
	static String done(String done, String text)
	{
		return text.length() > LONG_LINE
			? done + " At " + text.length() + " characters it may run off the screen: overhead text never wraps."
			: done;
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

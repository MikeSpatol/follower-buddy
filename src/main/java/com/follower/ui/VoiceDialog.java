package com.follower.ui;

import com.follower.sound.VoiceLibrary;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
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
import javax.swing.JTextField;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Gives a rule's lines their voices: one row per line, each with the sound
 * file that plays when the follower says it.
 *
 * <p>Files are chosen from a folder rather than through a file browser, and
 * deliberately so. It makes the arrangement obvious - these are your files, in
 * your folder, and the plugin only reads them - it survives the folder being
 * moved with the rest of the profile, and it gives somewhere honest to say the
 * one thing a player needs to know before they start: Java plays WAV, AIFF and
 * AU, and nothing else.
 */
@Slf4j
public class VoiceDialog extends JDialog
{
	private final VoiceLibrary voices;
	private final java.util.function.IntSupplier volume;

	public VoiceDialog(VoiceLibrary voices, java.util.function.IntSupplier volume)
	{
		super((java.awt.Frame) null, "Follower Buddy - Sounds", false);
		this.voices = voices;
		this.volume = volume;
		setDefaultCloseOperation(HIDE_ON_CLOSE);
	}

	/**
	 * The window with no lines in it: the folder and how to attach a file,
	 * for the side panel's Attach sounds button.
	 */
	public void open()
	{
		JPanel root = shell();

		JLabel how = new JLabel("<html><body style='width:480px'>Pick a rule in Messages"
			+ " and press Sounds to choose a file for each line.</body></html>");
		how.setFont(FontManager.getRunescapeSmallFont());
		how.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		how.setVerticalAlignment(javax.swing.SwingConstants.TOP);
		root.add(how, BorderLayout.CENTER);

		show(root, "Follower Buddy - Sounds", new Dimension(560, 200));
	}

	/**
	 * @param title what these lines belong to, for the header
	 * @param lines the rule's lines as they stand in the editor right now,
	 * unsaved edits included - a line has to exist before it can be voiced,
	 * but it does not have to have been saved
	 */
	public void open(String title, List<String> lines)
	{
		// Rebuilt every time: the lines may have been edited since last look,
		// and the folder may have gained files.
		voices.reload();
		List<String> files = voices.availableFiles();

		JPanel root = shell();

		JPanel list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);

		List<String> shown = new ArrayList<>();
		for (String line : lines)
		{
			String trimmed = line == null ? "" : line.trim();
			if (!trimmed.isEmpty() && !shown.contains(trimmed))
			{
				shown.add(trimmed);
				list.add(row(trimmed, files));
			}
		}
		if (shown.isEmpty())
		{
			JLabel empty = new JLabel("This one has no lines to give a voice to.");
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			list.add(empty);
		}

		JScrollPane scroll = new JScrollPane(list,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		root.add(scroll, BorderLayout.CENTER);

		show(root, "Follower Buddy - Sounds for " + title, new Dimension(560, 520));
	}

	private void show(JPanel root, String title, Dimension size)
	{
		setTitle(title);
		setContentPane(root);
		setPreferredSize(size);
		pack();
		setLocationRelativeTo(null);
		setVisible(true);
		toFront();
	}

	/** The window's frame: the hint, the folder path and Copy path across the top. */
	private JPanel shell()
	{
		JPanel root = new JPanel(new BorderLayout(0, 8));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel hint = new JLabel("<html><body style='width:480px'>"
			+ "A sound plays when the follower says that line, and the line still"
			+ " appears as usual. Put your own files in the folder below and they"
			+ " turn up in these lists. Java plays <b>WAV, AIFF and AU</b> only -"
			+ " an MP3 will simply stay silent.</body></html>");
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JPanel header = new JPanel(new BorderLayout(6, 6));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.add(hint, BorderLayout.CENTER);

		// The path is SHOWN rather than opened. Opening a file manager needs
		// Desktop::open, which the Plugin Hub restricts - reasonably, since a
		// plugin handing paths to the operating system is a category of thing
		// they cannot review one use of. Shown and copyable answers the same
		// question ("where do my files go?") with nothing but a text field.
		JTextField where = new JTextField(
			voices.getSoundsFolder() == null ? "" : voices.getSoundsFolder().toString());
		where.setEditable(false);
		where.setFont(FontManager.getRunescapeSmallFont());
		where.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		where.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		where.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		where.setCaretPosition(0);

		JButton copy = new JButton("Copy path");
		copy.setToolTipText("Copy the sounds folder path to the clipboard");
		copy.addActionListener(e -> copyFolderPath());

		JPanel folderRow = new JPanel(new BorderLayout(6, 0));
		folderRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		folderRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		folderRow.add(where, BorderLayout.CENTER);
		folderRow.add(copy, BorderLayout.EAST);
		header.add(folderRow, BorderLayout.SOUTH);
		root.add(header, BorderLayout.NORTH);
		return root;
	}

	private JPanel row(String line, List<String> files)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARKER_GRAY_COLOR),
			BorderFactory.createEmptyBorder(6, 0, 6, 4)));
		row.setAlignmentX(LEFT_ALIGNMENT);

		JLabel words = new JLabel("<html><body style='width:470px'>"
			+ escape(line) + "</body></html>");
		words.setFont(FontManager.getRunescapeSmallFont());
		words.setForeground(Color.WHITE);
		words.setAlignmentX(LEFT_ALIGNMENT);
		row.add(words);

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		controls.setAlignmentX(LEFT_ALIGNMENT);
		controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

		JComboBox<String> picker = new JComboBox<>();
		picker.addItem("(no sound)");
		for (String file : files)
		{
			picker.addItem(file);
		}
		String current = voices.attachmentFor(line);
		if (current != null)
		{
			if (!files.contains(current))
			{
				// Attached once, since deleted or renamed. Kept visible rather
				// than silently dropped, or the player cannot tell the
				// difference between a missing file and a lost setting.
				picker.addItem(current + "  (missing)");
				picker.setSelectedIndex(picker.getItemCount() - 1);
			}
			else
			{
				picker.setSelectedItem(current);
			}
		}
		picker.setPreferredSize(new Dimension(300, 24));
		picker.addActionListener(e ->
		{
			int index = picker.getSelectedIndex();
			voices.attach(line, index <= 0 ? null : files.get(index - 1));
		});
		controls.add(picker);

		JButton play = new JButton("Play");
		play.addActionListener(e ->
		{
			int index = picker.getSelectedIndex();
			if (index > 0 && index - 1 < files.size())
			{
				voices.preview(files.get(index - 1), volume.getAsInt());
			}
		});
		controls.add(play);

		row.add(controls);
		return row;
	}

	private void copyFolderPath()
	{
		java.nio.file.Path folder = voices.getSoundsFolder();
		if (folder == null)
		{
			return;
		}
		try
		{
			java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
				new java.awt.datatransfer.StringSelection(folder.toString()), null);
		}
		catch (Exception e)
		{
			// The path is on screen either way, so a clipboard that will not
			// take it costs the player a copy rather than the answer.
			log.warn("Could not copy {} to the clipboard", folder, e);
		}
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}

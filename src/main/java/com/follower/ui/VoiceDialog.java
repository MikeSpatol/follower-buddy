package com.follower.ui;

import com.follower.sound.VoiceLibrary;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
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
		setPreferredSize(new Dimension(560, 520));
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

		JPanel root = new JPanel(new BorderLayout(0, 8));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel hint = new JLabel("<html><body style='width:480px'>"
			+ "A sound plays when the follower says that line, and the line still"
			+ " appears as usual. Put your own files in the sounds folder and they"
			+ " turn up in these lists. Java plays <b>WAV, AIFF and AU</b> only -"
			+ " an MP3 will simply stay silent.</body></html>");
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JPanel header = new JPanel(new BorderLayout(6, 6));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.add(hint, BorderLayout.CENTER);

		JButton folder = new JButton("Open the sounds folder");
		folder.addActionListener(e -> openFolder());
		JPanel headerButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		headerButtons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		headerButtons.add(folder);
		header.add(headerButtons, BorderLayout.SOUTH);
		root.add(header, BorderLayout.NORTH);

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

		setTitle("Follower Buddy - Sounds for " + title);
		setContentPane(root);
		pack();
		setLocationRelativeTo(null);
		setVisible(true);
		toFront();
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

	private void openFolder()
	{
		java.nio.file.Path folder = voices.getSoundsFolder();
		if (folder == null)
		{
			return;
		}
		try
		{
			if (Desktop.isDesktopSupported())
			{
				Desktop.getDesktop().open(folder.toFile());
			}
		}
		catch (Exception e)
		{
			log.warn("Could not open {}", folder, e);
		}
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}

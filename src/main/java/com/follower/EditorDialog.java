package com.follower;

import com.google.gson.Gson;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.*;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;

/**
 * What the two file editors - Messages (phrases.json) and Conversations
 * (dialogs.json) - have in common: the file and a pretty-printing Gson for it,
 * the guard that gives unsaved edits a say on every road out of the window,
 * the scrolling list, the status line, Reload and Save, and the atomic write.
 * Each editor supplies its own top strip, its rows, and refresh() and save().
 */
abstract class EditorDialog extends JDialog
{
	final Gson gson;
	final Path file;

	/** The rows; a vertical box inside {@link #scroll}. */
	final JPanel list;
	final JScrollPane scroll;
	final JLabel status = Ui.label(" ", ColorScheme.MEDIUM_GRAY_COLOR);

	/** The right-hand buttons; an editor puts its own in front of Reload and Save. */
	final JPanel buttons = Ui.panel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

	/** An error status's wrap width, in the HTML renderer's px (x1.3 on screen). */
	final int statusWrap;

	/**
	 * Whether anything on screen differs from the file. Guards every road out
	 * of the window - closing, reopening, switching category or tree - because
	 * the first design lost ten typed lines to a stray click on the X.
	 */
	boolean dirty;

	final DocumentListener dirtyMarker = Ui.onChange(() -> dirty = true);

	EditorDialog(String title, Gson gson, Path file, JPanel list, int statusWrap)
	{
		super((java.awt.Frame) null, title, false);
		this.gson = gson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create();
		this.file = file;
		this.list = Ui.column(list);
		this.statusWrap = statusWrap;
		scroll = Ui.scroller(list);
	}

	/**
	 * Reload and Save, after the editor's own buttons, and the guard that
	 * gives unsaved edits a say when the window is closed.
	 */
	void addReloadAndSave()
	{
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		addWindowListener(new java.awt.event.WindowAdapter()
		{
			@Override
			public void windowClosing(java.awt.event.WindowEvent e)
			{
				if (confirmDirty())
					setVisible(false);
			}
		});

		var reload = new JButton("Reload");
		reload.setToolTipText("Discard unsaved edits and reload from disk");
		reload.addActionListener(e -> refresh());
		buttons.add(reload);
		var save = new JButton("Save");
		save.addActionListener(e -> save());
		buttons.add(save);
	}

	/**
	 * Lays the window out - the editor's own strip on top, the list, then the
	 * status and buttons - and sizes it. Narrower than {@code width}, the
	 * fixed-width hints are cut mid-word (QA3).
	 */
	void layOut(JComponent top, int width, int height)
	{
		var root = Ui.panel(new BorderLayout(0, 6));
		root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		root.add(top, BorderLayout.NORTH);
		root.add(scroll, BorderLayout.CENTER);

		var bottom = Ui.panel(new BorderLayout());
		bottom.add(status, BorderLayout.CENTER);
		bottom.add(buttons, BorderLayout.EAST);
		root.add(bottom, BorderLayout.SOUTH);

		setContentPane(root);
		setPreferredSize(new Dimension(width, height));
		pack();
		setMinimumSize(new Dimension(width, 400));
	}

	/** Reads the file into the window, dropping any unsaved edits. */
	abstract void refresh();

	/** @return whether the file was written */
	abstract boolean save();

	/**
	 * Shows the window, fronting it if already open. Fresh file contents
	 * unless there are edits in progress - alt-tabbing away and clicking the
	 * panel button again must not wipe half-typed lines.
	 */
	void open()
	{
		if (!dirty)
			refresh();
		setVisible(true);
		toFront();
	}

	/**
	 * Gives unsaved edits a say before they would be lost.
	 *
	 * @return true when the action may proceed - nothing was pending, the
	 * edits were saved, or the user chose to discard them
	 */
	boolean confirmDirty()
	{
		if (!dirty)
			return true;
		int choice = JOptionPane.showOptionDialog(this,
			"There are unsaved changes.", "Save changes?",
			JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
			new Object[]{"Save", "Discard", "Cancel"}, "Save");
		if (choice == 0)
			return save();
		if (choice == 1)
		{
			dirty = false;
			return true;
		}
		return false;
	}

	/**
	 * Replaces the file in one step, via a sibling temp file.
	 *
	 * <p>The plugin polls this same file every game tick and reloads it when it
	 * changes, which is what makes editing feel live. Writing in place truncates
	 * it first, so a poll landing mid-write reads half a file; a rename is a
	 * single step - the poller sees the old file or the new one, never a torn
	 * one.
	 *
	 * <p>Windows will not rename over a file another handle has open, and the
	 * plugin's poller opens this one every couple of ticks, so the rename can
	 * lose that race. When it does, write in place rather than lose the edit:
	 * the loader keeps its previous rules if a poll catches the file
	 * half-written, and picks the finished file up on the following poll. (A
	 * sleep-and-retry between move attempts would spare that one-in-a-thousand
	 * poll, but the Hub forbids Thread.sleep; this is the surviving tradeoff.)
	 */
	void writeAtomically(String contents) throws IOException
	{
		var temp = file.resolveSibling(file.getFileName() + ".tmp");
		byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
		Files.write(temp, bytes);

		try
		{
			Files.move(temp, file,
				StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException e)
		{
			// Some filesystems cannot promise it; a plain replace is still
			// one call rather than a truncate followed by a write.
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (FileSystemException e)
		{
			Files.write(file, bytes);
			Files.deleteIfExists(temp);
		}
	}

	void setStatus(String text, boolean error)
	{
		// An error can run long, and a plain label clips it mid-sentence;
		// wrapped, it is read whole.
		status.setText(error ? Ui.html(statusWrap, text) : text);
		status.setForeground(error ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.MEDIUM_GRAY_COLOR);
	}
}

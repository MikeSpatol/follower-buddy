package com.follower.ui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The message editor: one window for every category of message rule in
 * phrases.json, picked from a dropdown, with what triggers each rule, an
 * on/off tick and its message lines (one per line) free to edit. Save writes
 * straight back to phrases.json, which the plugin hot-reloads within a second
 * — no restart, no rebuild.
 *
 * <p>One window rather than seven buttons because the split by rule group is
 * an implementation detail; a user looking for "the thing it says about
 * bosses" should not have to know which of nine doors it lives behind.
 *
 * <p>In the structural category (Locations) each rule's region ids are
 * editable too, rules can be deleted outright and new ones added — the
 * "Use my current location" button fills the id in for you.
 *
 * <p>Deliberately a plain window rather than a sidebar panel: message lines
 * are sentences, and editing sentences in a 225px column is misery.
 */
@Slf4j
public class PhrasesDialog extends JDialog
{
	/**
	 * One entry of the category dropdown: a set of rule groups shown under one
	 * user-facing name. Usually one group, but related groups can be shown
	 * together - idle chatter belongs with the status lines as far as anyone
	 * looking for "things the follower says about itself" is concerned, even
	 * though the engine keeps them separate so they can be toggled apart.
	 */
	public static final class Category
	{
		final String name;
		final List<String> groups;
		final String hint;
		final boolean structural;

		/**
		 * @param groups one or more rule groups, comma separated; the first
		 * receives any rules added from this window
		 */
		public Category(String name, String groups, String hint, boolean structural)
		{
			this.name = name;
			this.groups = java.util.Arrays.asList(groups.split("\\s*,\\s*"));
			this.hint = hint;
			this.structural = structural;
		}
	}

	private final Gson gson;
	private final Path file;
	private final List<Category> categories;

	/** Where "Use my current location" gets its region id; may be null. */
	private final java.util.function.IntSupplier currentRegion;

	/** The export-or-import choice, shared with the side panel; may be null. */
	private Runnable onShare;

	public void setOnShare(Runnable onShare)
	{
		this.onShare = onShare;
	}

	/** Whether the window holds edits the file does not; the panel's Share checks it. */
	public boolean isDirty()
	{
		return dirty;
	}

	/** Opens the sound picker for a rule's lines; may be null. */
	private java.util.function.BiConsumer<String, java.util.List<String>> onEditSounds;

	public void setOnEditSounds(
		java.util.function.BiConsumer<String, java.util.List<String>> onEditSounds)
	{
		this.onEditSounds = onEditSounds;
	}

	private Category category;

	/** What every category has in common; each category adds one line of its own. */
	private static final String HOW_TO = "One message per line. Edit, remove or add lines,"
		+ " untick a rule to silence it, then Save - changes reach the follower within a second.";

	private final JComboBox<String> categoryPicker = new JComboBox<>();
	private final JLabel hint = new JLabel();

	/** Hides rows that do not mention the text, within the current category. */
	private final JTextField find = new JTextField(14);
	private final JButton addLocation = new JButton("Add location");
	private final JPanel list = new JPanel();
	private JScrollPane scroll;
	private final JLabel status = new JLabel(" ");
	private final List<Row> rows = new ArrayList<>();
	private final List<Row> added = new ArrayList<>();
	private final Set<String> deleted = new HashSet<>();

	/** True while the category picker is being set programmatically. */
	private boolean switching;

	/**
	 * Whether anything on screen differs from the file. Guards every road out
	 * of the window - closing, reopening, switching category - because the
	 * first design lost ten typed lines to a stray click on the X.
	 */
	private boolean dirty;

	private final DocumentListener dirtyMarker = new DocumentListener()
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

	/** The sounds button of the row being built, waiting for its say area. */
	private JButton soundsButton;

	private static final class Row
	{
		String id;
		final JTextField name;
		final JCheckBox enabled;
		final JTextField regions;
		final JTextArea say;
		JPanel panel;

		/** The rule's title and note, so Find can look through them. */
		String title = "";
		String note = "";

		Row(String id, JTextField name, JCheckBox enabled, JTextField regions, JTextArea say)
		{
			this.id = id;
			this.name = name;
			this.enabled = enabled;
			this.regions = regions;
			this.say = say;
		}
	}

	public PhrasesDialog(Gson gson, Path file, List<Category> categories,
		java.util.function.IntSupplier currentRegion)
	{
		super((java.awt.Frame) null, "Follower Buddy - Messages", false);
		this.gson = gson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create();
		this.file = file;
		this.categories = categories;
		this.currentRegion = currentRegion;
		this.category = categories.get(0);

		// Closing is handled below so unsaved edits get a say first.
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		addWindowListener(new java.awt.event.WindowAdapter()
		{
			@Override
			public void windowClosing(java.awt.event.WindowEvent e)
			{
				if (confirmDirty())
				{
					setVisible(false);
				}
			}
		});

		JPanel root = new JPanel(new BorderLayout(0, 6));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		for (Category c : categories)
		{
			categoryPicker.addItem(c.name);
		}
		categoryPicker.addActionListener(e ->
		{
			if (switching || categoryPicker.getSelectedIndex() < 0)
			{
				return;
			}
			if (!confirmDirty())
			{
				// Cancelled: put the picker back without re-entering this.
				switching = true;
				categoryPicker.setSelectedItem(category.name);
				switching = false;
				return;
			}
			category = categories.get(categoryPicker.getSelectedIndex());
			refresh();
		});

		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		find.setToolTipText("Show only the rules in this category whose title, note or lines"
			+ " contain this");
		find.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				applyFind();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				applyFind();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				applyFind();
			}
		});
		JPanel pickerRow = new JPanel(new BorderLayout(6, 0));
		pickerRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		pickerRow.add(categoryPicker, BorderLayout.CENTER);
		pickerRow.add(labelledField("Find:", find), BorderLayout.EAST);

		JPanel top = new JPanel(new BorderLayout(6, 6));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(pickerRow, BorderLayout.NORTH);
		top.add(hint, BorderLayout.CENTER);
		root.add(top, BorderLayout.NORTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);

		scroll = new JScrollPane(list,
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
		addLocation.setToolTipText("A new place for the follower to comment on");
		addLocation.addActionListener(e -> addBlankRow());
		// Only the structural category can add rules; refresh() keeps the
		// button's visibility in step with the picked category.
		addLocation.setVisible(category.structural);
		buttons.add(addLocation);
		JButton share = new JButton("Share...");
		share.setToolTipText("Export what you have written, or take on somebody else's");
		share.addActionListener(e ->
		{
			// Both halves work on the file rather than on this window, so
			// half-typed lines have to be settled before either runs. An
			// import re-opens this window on the grown file.
			if (confirmDirty() && onShare != null)
			{
				onShare.run();
			}
		});
		buttons.add(share);

		JButton reload = new JButton("Reload");
		reload.setToolTipText("Discard unsaved edits and reload from disk");
		reload.addActionListener(e -> refresh());
		buttons.add(reload);
		JButton save = new JButton("Save");
		save.addActionListener(e -> save());
		buttons.add(save);
		bottom.add(buttons, BorderLayout.EAST);

		root.add(bottom, BorderLayout.SOUTH);

		setContentPane(root);
		setPreferredSize(new Dimension(620, 640));
		pack();
		setLocationRelativeTo(null);
	}

	/**
	 * Shows the window, fronting it if already open. Fresh file contents
	 * unless there are edits in progress - alt-tabbing away and clicking the
	 * panel button again must not wipe half-typed lines.
	 */
	public void open()
	{
		if (!dirty)
		{
			refresh();
		}
		setVisible(true);
		toFront();
	}

	/**
	 * Gives unsaved edits a say before they would be lost.
	 *
	 * @return true when the action may proceed - nothing was pending, the
	 * edits were saved, or the user chose to discard them
	 */
	private boolean confirmDirty()
	{
		if (!dirty)
		{
			return true;
		}
		int choice = JOptionPane.showOptionDialog(this,
			"There are unsaved changes.", "Save changes?",
			JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
			new Object[]{"Save", "Discard", "Cancel"}, "Save");
		if (choice == 0)
		{
			return save();
		}
		if (choice == 1)
		{
			dirty = false;
			return true;
		}
		return false;
	}

	private void refresh()
	{
		rows.clear();
		added.clear();
		deleted.clear();
		list.removeAll();

		JsonObject parsed;
		try
		{
			parsed = readFile();
		}
		catch (Exception e)
		{
			log.warn("Could not read {}", file, e);
			setStatus("The messages file couldn't be read - it may have been"
				+ " hand-edited. Fix it or restore a backup, then Reload.", true);
			list.revalidate();
			list.repaint();
			return;
		}

		int count = 0;
		for (JsonElement element : parsed.getAsJsonArray("rules"))
		{
			JsonObject rule = element.getAsJsonObject();
			if (!category.groups.contains(optString(rule, "group")) || optString(rule, "id") == null)
			{
				continue;
			}
			list.add(buildRow(rule));
			count++;
		}

		hint.setText("<html><body style='width:440px'>" + HOW_TO + "<br>" + category.hint + "</body></html>");
		addLocation.setVisible(category.structural);
		setStatus(count + " rules loaded", false);
		applyFind();
		list.revalidate();
		list.repaint();
		dirty = false;
		// Every say area moves its caret as its text goes in, and each queues
		// a scroll to bring that caret into view, so the last of them decided
		// where the list opened. QA, 15 Oct: Messages opened near the bottom.
		// Queued behind them, this puts the list back at its top.
		javax.swing.SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(new java.awt.Point(0, 0)));
	}

	/**
	 * Shows only the rows that mention the Find text, in their title, id, note
	 * or lines. Rows stay in the rows list either way, so Save is unaffected;
	 * a row added from this window is always shown.
	 */
	private void applyFind()
	{
		String wanted = find.getText().trim().toLowerCase(Locale.ROOT);
		for (Row row : rows)
		{
			boolean shown = wanted.isEmpty()
				|| row.title.toLowerCase(Locale.ROOT).contains(wanted)
				|| row.id.toLowerCase(Locale.ROOT).contains(wanted)
				|| row.note.toLowerCase(Locale.ROOT).contains(wanted)
				|| row.say.getText().toLowerCase(Locale.ROOT).contains(wanted);
			row.panel.setVisible(shown);
		}
		list.revalidate();
		list.repaint();
	}

	private JPanel buildRow(JsonObject rule)
	{
		String id = optString(rule, "id");
		String ruleTitle = titleOf(rule);

		JPanel row = rowShell();

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setAlignmentX(LEFT_ALIGNMENT);

		JLabel title = new JLabel(ruleTitle);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		// The id beside the title is what the settings' silence-by-name box
		// wants typed into it.
		JLabel idLabel = new JLabel(id);
		idLabel.setFont(FontManager.getRunescapeSmallFont());
		idLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		JPanel titles = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		titles.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titles.add(title);
		titles.add(idLabel);
		header.add(titles, BorderLayout.CENTER);

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JCheckBox enabled = new JCheckBox("On");
		enabled.setSelected(!rule.has("enabled") || rule.get("enabled").getAsBoolean());
		enabled.setBackground(ColorScheme.DARK_GRAY_COLOR);
		enabled.setToolTipText("Untick to silence just this rule");
		enabled.addItemListener(e -> dirty = true);
		controls.add(enabled);
		if (onEditSounds != null)
		{
			JButton sounds = new JButton("Sounds");
			sounds.setToolTipText("Attach a sound file to any of these lines");
			controls.add(sounds);
			// Wired below, once the say area it reads from exists.
			soundsButton = sounds;
		}
		if (category.structural)
		{
			JButton delete = new JButton("Delete");
			delete.setToolTipText("Remove this rule entirely on Save");
			delete.addActionListener(e ->
			{
				deleted.add(id);
				rows.removeIf(r -> id.equals(r.id));
				list.remove(row);
				list.revalidate();
				list.repaint();
				setStatus("'" + ruleTitle + "' will be removed on Save", false);
				dirty = true;
			});
			controls.add(delete);
		}
		header.add(controls, BorderLayout.EAST);
		row.add(header);

		String note = optString(rule, "note");
		String shownNote = aboutOf(rule);
		if (shownNote != null && !shownNote.isEmpty())
		{
			JLabel items = new JLabel("<html><body style='width:430px'>" + escapeHtml(shownNote) + "</body></html>");
			items.setFont(FontManager.getRunescapeSmallFont());
			items.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			items.setAlignmentX(LEFT_ALIGNMENT);
			items.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
			row.add(items);
		}

		JTextField regionsField = null;
		if (category.structural)
		{
			JsonArray regions = regionsOf(rule);
			if (regions != null)
			{
				List<String> ids = new ArrayList<>();
				for (JsonElement r : regions)
				{
					ids.add(r.getAsString());
				}
				regionsField = new JTextField(String.join(", ", ids));
				regionsField.getDocument().addDocumentListener(dirtyMarker);
				row.add(labelledField("Regions:", regionsField));
			}
			else
			{
				JLabel special = new JLabel("(triggered by something other than a place"
					+ " - the messages can still be edited)");
				special.setFont(FontManager.getRunescapeSmallFont());
				special.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
				special.setAlignmentX(LEFT_ALIGNMENT);
				row.add(special);
			}
		}

		StringBuilder text = new StringBuilder();
		JsonArray say = rule.has("say") ? rule.getAsJsonArray("say") : new JsonArray();
		for (JsonElement line : say)
		{
			if (text.length() > 0)
			{
				text.append('\n');
			}
			text.append(line.getAsString());
		}
		JTextArea area = sayArea(text.toString(), Math.max(2, say.size()));
		area.getDocument().addDocumentListener(dirtyMarker);
		row.add(area);

		if (soundsButton != null)
		{
			// Reads the box rather than the file: a line can be given a voice
			// the moment it is typed, without saving first.
			JButton sounds = soundsButton;
			soundsButton = null;
			String ruleName = ruleTitle;
			sounds.addActionListener(e -> onEditSounds.accept(ruleName,
				java.util.Arrays.asList(area.getText().split("\\r?\\n"))));
		}

		Row r = new Row(id, null, enabled, regionsField, area);
		r.panel = row;
		r.title = ruleTitle;
		r.note = (note == null ? "" : note) + "\n" + (shownNote == null ? "" : shownNote);
		rows.add(r);
		return row;
	}

	private void addBlankRow()
	{
		JPanel row = rowShell();

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setAlignmentX(LEFT_ALIGNMENT);
		JLabel title = new JLabel("New location");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		header.add(title, BorderLayout.CENTER);

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JButton delete = new JButton("Delete");
		header.add(controls, BorderLayout.EAST);
		row.add(header);

		JTextField name = new JTextField();
		name.getDocument().addDocumentListener(dirtyMarker);
		row.add(labelledField("Name:", name));
		JTextField regions = new JTextField();
		regions.setToolTipText("Comma separated region ids");
		regions.getDocument().addDocumentListener(dirtyMarker);
		JPanel regionsLine = labelledField("Regions:", regions);
		if (currentRegion != null)
		{
			// The first design taught the user a chat command in a tooltip and
			// had them carry the id back by hand. The plugin knows where you
			// are standing; the button just asks it.
			JButton here = new JButton("Use my current location");
			here.setToolTipText("Fill in the region you are standing in right now");
			here.addActionListener(e ->
			{
				int region = currentRegion.getAsInt();
				if (region <= 0)
				{
					setStatus("The game hasn't said where you are yet - log in first.", true);
					return;
				}
				String existing = regions.getText().trim();
				regions.setText(existing.isEmpty() ? String.valueOf(region)
					: existing + ", " + region);
			});
			regionsLine.add(here, BorderLayout.EAST);
		}
		row.add(regionsLine);
		JTextArea area = sayArea("", 3);
		area.getDocument().addDocumentListener(dirtyMarker);
		row.add(area);

		JCheckBox enabled = new JCheckBox("On", true);
		enabled.setBackground(ColorScheme.DARK_GRAY_COLOR);
		enabled.addItemListener(e -> dirty = true);
		controls.add(enabled);
		controls.add(delete);

		Row r = new Row(null, name, enabled, regions, area);
		r.panel = row;
		added.add(r);
		dirty = true;
		delete.addActionListener(e ->
		{
			added.remove(r);
			list.remove(row);
			list.revalidate();
			list.repaint();
		});

		list.add(row);
		list.revalidate();
		list.repaint();
		name.requestFocusInWindow();
	}

	/**
	 * Applies the edits to a FRESH parse of the file, so hand edits made while
	 * the window sat open are kept rather than clobbered with stale contents.
	 *
	 * @return whether the file was written
	 */
	private boolean save()
	{
		try
		{
			JsonObject parsed = readFile();
			JsonArray rules = parsed.getAsJsonArray("rules");
			JsonArray kept = new JsonArray();
			List<String> warnings = new ArrayList<>();

			for (JsonElement element : rules)
			{
				JsonObject rule = element.getAsJsonObject();
				String id = optString(rule, "id");
				if (id != null && deleted.contains(id))
				{
					continue;
				}
				Row row = rows.stream().filter(r -> r.id.equals(id)).findFirst().orElse(null);
				if (row != null)
				{
					applyRow(rule, row, warnings);
				}
				kept.add(rule);
			}

			Set<String> ids = new HashSet<>();
			for (JsonElement element : kept)
			{
				String id = optString(element.getAsJsonObject(), "id");
				if (id != null)
				{
					ids.add(id);
				}
			}
			for (Row row : added)
			{
				JsonObject rule = buildAddedRule(row, ids, warnings);
				if (rule != null)
				{
					kept.add(rule);
				}
			}

			// Rules deleted here must STAY deleted across updates: the loader
			// merges newly bundled rules in by id at startup, and without a
			// record it would politely resurrect these on the next client.
			if (!deleted.isEmpty())
			{
				JsonArray removed = parsed.has("removed") && parsed.get("removed").isJsonArray()
					? parsed.getAsJsonArray("removed") : new JsonArray();
				Set<String> already = new HashSet<>();
				for (JsonElement element : removed)
				{
					if (element.isJsonPrimitive())
					{
						already.add(element.getAsString());
					}
				}
				for (String id : deleted)
				{
					if (!already.contains(id))
					{
						removed.add(id);
					}
				}
				parsed.add("removed", removed);
			}

			parsed.add("rules", kept);
			writeAtomically(gson.toJson(parsed) + "\n");

			if (warnings.isEmpty())
			{
				setStatus("Saved - the follower picks it up within a second", false);
			}
			else if (warnings.size() == 1)
			{
				setStatus("Saved with a warning: " + warnings.get(0), true);
			}
			else
			{
				// A status line clips past one warning; a list does not.
				setStatus("Saved with warnings", true);
				JOptionPane.showMessageDialog(this,
					"Saved, but:\n\n- " + String.join("\n- ", warnings),
					"Saved with warnings", JOptionPane.WARNING_MESSAGE);
			}
			refresh();
			return true;
		}
		catch (Exception e)
		{
			log.warn("Could not save {}", file, e);
			setStatus("Save failed: " + e.getMessage(), true);
			return false;
		}
	}

	/**
	 * Replaces the rules file in one step, via a sibling temp file.
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
	private void writeAtomically(String contents) throws IOException
	{
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
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

	private void applyRow(JsonObject rule, Row row, List<String> warnings)
	{
		JsonArray say = parseSay(row.say.getText());

		// An emptied rule keeps its old lines but goes quiet: deleting all
		// text reads as "shut this one up", not "make the rule invalid".
		boolean empty = say.size() == 0;
		if (!empty)
		{
			rule.add("say", say);
		}
		if (row.enabled.isSelected() && !empty)
		{
			rule.remove("enabled");
		}
		else
		{
			rule.addProperty("enabled", false);
		}

		if (row.regions != null)
		{
			JsonArray regions = parseRegions(row.regions.getText(), warnings);
			if (regions.size() > 0)
			{
				rule.getAsJsonObject("when").add("regions", regions);
			}
			else
			{
				warnings.add(row.title + " kept its old regions (none given)");
			}
		}
	}

	private JsonObject buildAddedRule(Row row, Set<String> existingIds, List<String> warnings)
	{
		String name = row.name.getText().trim();
		JsonArray regions = parseRegions(row.regions.getText(), warnings);
		JsonArray say = parseSay(row.say.getText());
		if (name.isEmpty() || regions.size() == 0 || say.size() == 0)
		{
			warnings.add("new location needs a name, at least one region id and one message");
			return null;
		}

		String base = "area-" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		String id = base;
		for (int n = 2; existingIds.contains(id); n++)
		{
			id = base + "-" + n;
		}
		existingIds.add(id);

		JsonObject when = new JsonObject();
		when.addProperty("type", "inRegion");
		when.add("regions", regions);

		JsonObject rule = new JsonObject();
		rule.addProperty("id", id);
		rule.addProperty("note", name + ". Added from the editor.");
		rule.addProperty("group", category.groups.get(0));
		rule.addProperty("priority", 40);
		rule.addProperty("output", "overhead");
		rule.addProperty("delayTicks", 2);
		rule.addProperty("cooldownMs", 180000);
		rule.add("when", when);
		rule.add("say", say);
		if (!row.enabled.isSelected())
		{
			rule.addProperty("enabled", false);
		}
		return rule;
	}

	// ------------------------------------------------------------------ helpers

	private JPanel rowShell()
	{
		// Never taller than it needs: the list is a vertical box, which shares
		// any spare height out between rows that allow it, so a Find leaving
		// two rows pulled each one's title, controls and lines far apart.
		JPanel row = new JPanel()
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARKER_GRAY_COLOR),
			BorderFactory.createEmptyBorder(8, 0, 8, 8)));
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	private JPanel labelledField(String label, JTextField field)
	{
		JPanel line = new JPanel(new BorderLayout(6, 0));
		line.setBackground(ColorScheme.DARK_GRAY_COLOR);
		line.setAlignmentX(LEFT_ALIGNMENT);
		line.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		JLabel l = new JLabel(label);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		line.add(l, BorderLayout.WEST);
		field.setFont(FontManager.getRunescapeFont());
		field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		field.setForeground(Color.WHITE);
		field.setCaretColor(Color.WHITE);
		line.add(field, BorderLayout.CENTER);
		return line;
	}

	private JTextArea sayArea(String text, int rows)
	{
		JTextArea area = new JTextArea(text, rows, 40);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setFont(FontManager.getRunescapeFont());
		area.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		area.setForeground(Color.WHITE);
		area.setCaretColor(Color.WHITE);
		area.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		area.setAlignmentX(LEFT_ALIGNMENT);
		return area;
	}

	private static JsonArray parseSay(String text)
	{
		JsonArray say = new JsonArray();
		for (String line : text.split("\n"))
		{
			String trimmed = line.trim();
			if (!trimmed.isEmpty())
			{
				say.add(trimmed);
			}
		}
		return say;
	}

	private static JsonArray parseRegions(String text, List<String> warnings)
	{
		JsonArray regions = new JsonArray();
		for (String token : text.split("[^0-9]+"))
		{
			if (token.isEmpty())
			{
				continue;
			}
			try
			{
				regions.add(Integer.parseInt(token));
			}
			catch (NumberFormatException e)
			{
				// An 11-digit paste used to abort the whole save with a raw
				// "For input string" - name the token and carry on instead.
				warnings.add("'" + token + "' isn't a region id the game uses - skipped");
			}
		}
		return regions;
	}

	private static JsonArray regionsOf(JsonObject rule)
	{
		JsonObject when = rule.has("when") && rule.get("when").isJsonObject()
			? rule.getAsJsonObject("when") : null;
		if (when == null)
		{
			return null;
		}
		String type = optString(when, "type");
		boolean regionBased = "inRegion".equalsIgnoreCase(type) || "regionEnter".equalsIgnoreCase(type);
		return regionBased && when.has("regions") ? when.getAsJsonArray("regions") : null;
	}

	private JsonObject readFile() throws IOException
	{
		return gson.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), JsonObject.class);
	}

	private void setStatus(String text, boolean error)
	{
		// An error can run long, and a plain label clips it mid-sentence;
		// wrapped, it is read whole.
		status.setText(error ? "<html><body style='width:" + STATUS_WRAP + "px'>"
			+ escapeHtml(text) + "</body></html>" : text);
		status.setForeground(error ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.MEDIUM_GRAY_COLOR);
	}

	/** An error status's wrap width, in the HTML renderer's px (x1.3 on screen). */
	private static final int STATUS_WRAP = 170;

	private static String optString(JsonObject object, String key)
	{
		return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
	}

	/** The rule's own title when it has one, otherwise one made from its id. */
	static String titleOf(JsonObject rule)
	{
		String title = optString(rule, "title");
		return title == null || title.trim().isEmpty() ? prettyName(optString(rule, "id")) : title.trim();
	}

	/**
	 * What the window says about a rule: its "about", written for players,
	 * when it has one, otherwise its note. The note stays the authors' own.
	 */
	static String aboutOf(JsonObject rule)
	{
		String about = optString(rule, "about");
		return about == null || about.trim().isEmpty() ? optString(rule, "note") : about.trim();
	}

	static String prettyName(String id)
	{
		if (id == null)
		{
			return "(new)";
		}
		String s = id;
		boolean set = false;
		if (s.startsWith("gear-set-"))
		{
			set = true;
			s = s.substring("gear-set-".length());
		}
		else if (s.startsWith("gear-"))
		{
			s = s.substring("gear-".length());
		}
		else if (s.startsWith("area-"))
		{
			s = s.substring("area-".length());
		}
		else if (s.startsWith("enter-"))
		{
			s = s.substring("enter-".length());
		}
		else if (s.startsWith("boss-"))
		{
			// The Bosses category says it already, as gear- and area- do.
			s = s.substring("boss-".length());
		}
		s = s.replace('-', ' ');
		s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
		return set ? "Full " + s + " (set)" : s;
	}

	private static String escapeHtml(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}

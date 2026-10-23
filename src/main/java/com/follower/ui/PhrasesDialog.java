package com.follower.ui;

// public:cut messages-window
import com.follower.Resources;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet; // public:cut messages-readonly
import java.util.List;
import java.util.Locale;
import java.util.Set; // public:cut messages-readonly
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane; // public:cut messages-readonly
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
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
// public:end messages-window
public class PhrasesDialog extends EditorDialog // public:cut messages-window
// public:else public class PhrasesDialog
{
	// public:cut messages-window
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
		final boolean structural; // public:cut messages-readonly

		/**
		 * @param groups one or more rule groups, comma separated; the first
		 * receives any rules added from this window
		 */
		public Category(String name, String groups, String hint, boolean structural) // public:cut messages-readonly
		// public:else public Category(String name, String groups, String hint)
		{
			this.name = name;
			this.groups = java.util.Arrays.asList(groups.split("\\s*,\\s*"));
			this.hint = hint;
			this.structural = structural; // public:cut messages-readonly
		}
	}

	/** The categories, in the dropdown's order, from message-categories.tsv. */
	public static List<Category> categories()
	{
		List<Category> categories = new ArrayList<>();
		for (String[] row : Resources.rows("message-categories.tsv"))
		{
			categories.add(new Category(row[0], row[1], row[3], Boolean.parseBoolean(row[2]))); // public:cut messages-readonly
			// public:else categories.add(new Category(row[0], row[1], row[3]));
		}
		return categories;
	}

	// public:cut messages-readonly
	/** Where "Use my current location" gets its region id; may be null. */
	private final java.util.function.IntSupplier currentRegion;
	// public:end messages-readonly

	// public:cut sharevoice
	/** The export-or-import choice, shared with the side panel; may be null. */
	private Runnable onShare;

	public void setOnShare(Runnable onShare)
	{
		this.onShare = onShare;
	}

	// public:cut messages-readonly
	/** Whether the window holds edits the file does not; the panel's Share checks it. */
	public boolean isDirty()
	{
		return dirty;
	}

	// public:end messages-readonly
	/** Opens the sound picker for a rule's lines; may be null. */
	private java.util.function.BiConsumer<String, java.util.List<String>> onEditSounds;

	public void setOnEditSounds(
		java.util.function.BiConsumer<String, java.util.List<String>> onEditSounds)
	{
		this.onEditSounds = onEditSounds;
	}

	// public:end sharevoice
	private Category category;

	/** What every category has in common; each category adds one line of its own. */
	private static final String HOW_TO = Resources.text("messagesHowTo");

	private final JComboBox<String> categoryPicker = new JComboBox<>();
	private final JLabel hint = Ui.label("", ColorScheme.LIGHT_GRAY_COLOR);

	/** Hides rows that do not mention the text, within the current category. */
	private final JTextField find = new JTextField(14);
	private final JButton addLocation = new JButton("Add location"); // public:cut messages-readonly
	/** The loaded count, put back once Find matches something again. */
	private String loadedStatus = " ";
	private boolean findMatchedNothing;
	private final List<Row> rows = new ArrayList<>();
	// public:cut messages-readonly
	private final List<Row> added = new ArrayList<>();
	private final Set<String> deleted = new HashSet<>();
	// public:end messages-readonly

	// public:cut messages-readonly
	/** True while the category picker is being set programmatically. */
	private boolean switching;

	// public:end messages-readonly
	// public:cut sharevoice
	/** The sounds button of the row being built, waiting for its say area. */
	private JButton soundsButton;

	// public:end sharevoice
	private static final class Row
	{
		String id;
		// public:cut messages-readonly
		final JTextField name;
		final JCheckBox enabled;
		final JTextField regions;
		// public:end messages-readonly
		final JTextArea say;
		JPanel panel;

		/** The rule's title and note, so Find can look through them. */
		String title = "";
		String note = "";

		Row(String id, JTextField name, JCheckBox enabled, JTextField regions, JTextArea say) // public:cut messages-readonly
		// public:else Row(String id, JTextArea say)
		{
			this.id = id;
			// public:cut messages-readonly
			this.name = name;
			this.enabled = enabled;
			this.regions = regions;
			// public:end messages-readonly
			this.say = say;
		}
	}

	public PhrasesDialog(Gson gson, Path file, List<Category> categories, // public:cut messages-readonly
	// public:else public PhrasesDialog(Gson gson, Path file, List<Category> categories)
		java.util.function.IntSupplier currentRegion) // public:cut messages-readonly
	{
		super("Follower Buddy - Messages", gson, file, new Ui.WidthTrackingPanel(), 170);
		this.currentRegion = currentRegion; // public:cut messages-readonly
		this.category = categories.get(0);

		for (Category c : categories)
		{
			categoryPicker.addItem(c.name);
		}
		categoryPicker.addActionListener(e ->
		{
			if (switching || categoryPicker.getSelectedIndex() < 0) // public:cut messages-readonly
			// public:else if (categoryPicker.getSelectedIndex() < 0)
			{
				return;
			}
			// public:cut messages-readonly
			if (!confirmDirty())
			{
				// Cancelled: put the picker back without re-entering this.
				switching = true;
				categoryPicker.setSelectedItem(category.name);
				switching = false;
				return;
			}
			// public:end messages-readonly
			category = categories.get(categoryPicker.getSelectedIndex());
			showCategory();
		});

		find.setToolTipText(Resources.text("findTip"));
		find.getDocument().addDocumentListener(Ui.onChange(this::applyFind));
		JPanel pickerRow = Ui.panel(new BorderLayout(6, 0));
		pickerRow.add(categoryPicker, BorderLayout.CENTER);
		pickerRow.add(labelledField("Find:", find), BorderLayout.EAST);

		JPanel top = Ui.panel(new BorderLayout(6, 6));
		top.add(pickerRow, BorderLayout.NORTH);
		top.add(hint, BorderLayout.CENTER);

		// public:cut messages-readonly
		addLocation.setToolTipText("A new place for the follower to comment on");
		addLocation.addActionListener(e -> addBlankRow());
		// Only the structural category can add rules; showCategory() keeps the
		// button's visibility in step with the picked category.
		addLocation.setVisible(category.structural);
		buttons.add(addLocation);
		// public:end messages-readonly
		// public:cut sharevoice
		JButton share = new JButton("Share...");
		share.setToolTipText("Export what you have written, or take on somebody else's");
		share.addActionListener(e ->
		{
			// Both halves work on the file rather than on this window, so
			// half-typed lines have to be settled before either runs. An
			// import re-opens this window on the grown file.
			if (confirmDirty() && onShare != null) // public:cut messages-readonly
			// public:else if (onShare != null)
			{
				onShare.run();
			}
		});
		buttons.add(share);
		// public:end sharevoice
		addReloadAndSave(); // public:cut messages-readonly

		layOut(top, 620, 640);
		setLocationRelativeTo(null);
	}

	/**
	 * The file as last read, by open(), Reload or a save, or null when it
	 * would not read. A switch of category lists from it: reading the whole
	 * file again each time held the event thread for most of half a second
	 * (qa5 #71).
	 */
	private JsonObject loaded;

	@Override
	void refresh()
	{
		try
		{
			loaded = readFile();
		}
		catch (Exception e)
		{
			log.warn("Could not read {}", file, e);
			loaded = null;
		}
		showCategory();
	}

	private void showCategory()
	{
		rows.clear();
		// public:cut messages-readonly
		added.clear();
		deleted.clear();
		// public:end messages-readonly
		list.removeAll();

		if (loaded == null)
		{
			setStatus(Resources.text("messagesUnreadable"), true);
			Ui.relayout(list);
			return;
		}

		int count = 0;
		for (JsonElement element : loaded.getAsJsonArray("rules"))
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
		addLocation.setVisible(category.structural); // public:cut messages-readonly
		loadedStatus = count + " rules loaded";
		findMatchedNothing = false;
		setStatus(loadedStatus, false);
		applyFind();
		Ui.relayout(list);
		dirty = false; // public:cut messages-readonly
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
		boolean any = false;
		for (Row row : rows)
		{
			boolean shown = wanted.isEmpty()
				|| row.title.toLowerCase(Locale.ROOT).contains(wanted)
				|| row.id.toLowerCase(Locale.ROOT).contains(wanted)
				|| row.note.toLowerCase(Locale.ROOT).contains(wanted)
				|| row.say.getText().toLowerCase(Locale.ROOT).contains(wanted);
			row.panel.setVisible(shown);
			any |= shown;
		}
		// With nothing shown the window said nothing, and the status still
		// counted what was loaded.
		if (!wanted.isEmpty() && !any)
		{
			setStatus("No rules here mention \"" + find.getText().trim() + "\".", false);
			findMatchedNothing = true;
		}
		else if (findMatchedNothing)
		{
			setStatus(loadedStatus, false);
			findMatchedNothing = false;
		}
		Ui.relayout(list);
	}

	private JPanel buildRow(JsonObject rule)
	{
		String id = optString(rule, "id");
		String ruleTitle = titleOf(rule);

		JPanel row = rowShell();

		JLabel title = title(ruleTitle);
		// The id beside the title is what the settings' silence-by-name box
		// wants typed into it.
		JLabel idLabel = Ui.label(id, ColorScheme.MEDIUM_GRAY_COLOR);
		// A long title or id is cut short with "..." rather than widening the
		// row: the list is as wide as the window, and the controls on the
		// right have to stay on it. QA2, 15 Oct: Quest NPCs and Locations
		// pushed On and Sounds past the edge. The id gives way first: the
		// title's end is what tells a callback from its plain rule (QA3).
		title.setMinimumSize(title.getPreferredSize());
		idLabel.setMinimumSize(new Dimension(0, idLabel.getPreferredSize().height));
		title.setToolTipText(ruleTitle + "  " + id);
		idLabel.setToolTipText(id);
		JPanel titles = new JPanel();
		titles.setLayout(new BoxLayout(titles, BoxLayout.X_AXIS));
		titles.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
		titles.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titles.add(title);
		titles.add(javax.swing.Box.createHorizontalStrut(6));
		titles.add(idLabel);
		JPanel controls = header(row, titles);

		JCheckBox enabled = onTick(!rule.has("enabled") || rule.get("enabled").getAsBoolean());
		enabled.setToolTipText("Untick to silence just this rule"); // public:cut messages-readonly
		// public:else enabled.setEnabled(false);
		controls.add(enabled);
		// public:cut sharevoice
		if (onEditSounds != null)
		{
			JButton sounds = new JButton("Sounds");
			sounds.setToolTipText("Attach a sound file to any of these lines");
			controls.add(sounds);
			// Wired below, once the say area it reads from exists.
			soundsButton = sounds;
		}
		// public:end sharevoice
		// public:cut messages-readonly
		if (category.structural)
		{
			JButton delete = new JButton("Delete");
			delete.setToolTipText("Remove this rule entirely on Save");
			delete.addActionListener(e ->
			{
				deleted.add(id);
				rows.removeIf(r -> id.equals(r.id));
				list.remove(row);
				Ui.relayout(list);
				setStatus("'" + ruleTitle + "' will be removed on Save", false);
				dirty = true;
			});
			controls.add(delete);
		}
		// public:end messages-readonly

		String shownNote = aboutOf(rule);
		if (shownNote != null && !shownNote.isEmpty())
		{
			JLabel items = Ui.label(Ui.html(430, shownNote), ColorScheme.MEDIUM_GRAY_COLOR);
			items.setAlignmentX(LEFT_ALIGNMENT);
			items.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
			row.add(items);
		}

		// public:cut messages-readonly
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
				JLabel special = Ui.label(Resources.text("notAPlace"), ColorScheme.MEDIUM_GRAY_COLOR);
				special.setAlignmentX(LEFT_ALIGNMENT);
				row.add(special);
			}
		}
		// public:end messages-readonly

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
		area.getDocument().addDocumentListener(dirtyMarker); // public:cut messages-readonly
		// public:else area.setEditable(false);
		row.add(area);

		// public:cut sharevoice
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

		// public:end sharevoice
		Row r = new Row(id, null, enabled, regionsField, area); // public:cut messages-readonly
		// public:else Row r = new Row(id, area);
		r.panel = row;
		r.title = ruleTitle;
		// Find looks through what is shown, not the hidden note.
		r.note = shownNote == null ? "" : shownNote;
		rows.add(r);
		return row;
	}

	// public:cut messages-readonly
	private void addBlankRow()
	{
		JPanel row = rowShell();
		JPanel controls = header(row, title("New location"));
		JButton delete = new JButton("Delete");

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
					setStatus(Resources.text("whereUnknown"), true);
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

		JCheckBox enabled = onTick(true);
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
			Ui.relayout(list);
		});

		list.add(row);
		Ui.relayout(list);
		name.requestFocusInWindow();
	}

	/**
	 * Applies the edits to a FRESH parse of the file, so hand edits made while
	 * the window sat open are kept rather than clobbered with stale contents.
	 *
	 * @return whether the file was written
	 */
	@Override
	boolean save()
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
			JsonObject place = placeOf(rule);
			if (regions.size() > 0 && place != null)
			{
				place.add("regions", regions);
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
			warnings.add(Resources.text("newLocationIncomplete"));
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

	// public:end messages-readonly
	// public:else @Override
	// public:else boolean save()
	// public:else {
	// public:else 	return false;
	// public:else }
	// ------------------------------------------------------------------ helpers

	/** A row's top line: {@code left} and, returned for filling, the controls on the right. */
	private static JPanel header(JPanel row, JComponent left)
	{
		JPanel header = Ui.panel(new BorderLayout());
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.add(left, BorderLayout.CENTER);
		JPanel controls = Ui.panel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		header.add(controls, BorderLayout.EAST);
		row.add(header);
		return controls;
	}

	private static JLabel title(String text)
	{
		JLabel title = new JLabel(text);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		return title;
	}

	/** A rule's On tick; changing it marks the window dirty. */
	private JCheckBox onTick(boolean on)
	{
		JCheckBox enabled = new JCheckBox("On", on);
		enabled.setBackground(ColorScheme.DARK_GRAY_COLOR);
		enabled.addItemListener(e -> dirty = true); // public:cut messages-readonly
		return enabled;
	}

	private JPanel rowShell()
	{
		// Never taller than it needs: the list is a vertical box, which shares
		// any spare height out between rows that allow it, so a Find leaving
		// two rows pulled each one's title, controls and lines far apart.
		JPanel row = Ui.column(new JPanel()
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		});
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARKER_GRAY_COLOR),
			BorderFactory.createEmptyBorder(8, 0, 8, 8)));
		return row;
	}

	private JPanel labelledField(String label, JTextField field)
	{
		JPanel line = Ui.panel(new BorderLayout(6, 0));
		line.setAlignmentX(LEFT_ALIGNMENT);
		line.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		line.add(Ui.label(label, ColorScheme.LIGHT_GRAY_COLOR), BorderLayout.WEST);
		line.add(editable(field), BorderLayout.CENTER);
		return line;
	}

	/** White game-font text on the darker grey, for the boxes a player types in. */
	private static <T extends JTextComponent> T editable(T field)
	{
		field.setFont(FontManager.getRunescapeFont());
		field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		field.setForeground(Color.WHITE);
		field.setCaretColor(Color.WHITE);
		return field;
	}

	private JTextArea sayArea(String text, int rows)
	{
		JTextArea area = editable(new JTextArea(text, rows, 40));
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		area.setAlignmentX(LEFT_ALIGNMENT);
		return area;
	}

	// public:cut messages-readonly
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
				warnings.add("'" + token + Resources.text("notARegion"));
			}
		}
		return regions;
	}

	private static JsonArray regionsOf(JsonObject rule)
	{
		JsonObject place = placeOf(rule);
		return place == null ? null : place.getAsJsonArray("regions");
	}

	/**
	 * The condition that holds a rule's regions: its when, or the one place
	 * in an "all" that also asks for a chance or a quest, as the fun- place
	 * lines do. QA2, 15 Oct: those read as "triggered by something other
	 * than a place". Null for a rule that is not about a place.
	 */
	private static JsonObject placeOf(JsonObject rule)
	{
		JsonObject when = rule.has("when") && rule.get("when").isJsonObject()
			? rule.getAsJsonObject("when") : null;
		if (when == null)
		{
			return null;
		}
		if (isPlace(when))
		{
			return when;
		}
		JsonObject found = null;
		if ("all".equalsIgnoreCase(optString(when, "type")) && when.has("conditions")
			&& when.get("conditions").isJsonArray())
		{
			for (JsonElement each : when.getAsJsonArray("conditions"))
			{
				if (each.isJsonObject() && isPlace(each.getAsJsonObject()))
				{
					if (found != null)
					{
						return null;
					}
					found = each.getAsJsonObject();
				}
			}
		}
		return found;
	}

	private static boolean isPlace(JsonObject condition)
	{
		String type = optString(condition, "type");
		return ("inRegion".equalsIgnoreCase(type) || "regionEnter".equalsIgnoreCase(type))
			&& condition.has("regions") && condition.get("regions").isJsonArray();
	}

	// public:end messages-readonly
	private JsonObject readFile() throws IOException
	{
		return gson.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), JsonObject.class);
	}

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
	 * or nothing. The note is the authors' own and never shown: QA2, 15 Oct,
	 * the quest moments' notes put varp numbers and quest steps in front of
	 * players.
	 */
	static String aboutOf(JsonObject rule)
	{
		String about = optString(rule, "about");
		return about == null || about.trim().isEmpty() ? null : about.trim();
	}

	// public:end messages-window
	static String prettyName(String id)
	{
		if (id == null)
		{
			return "(new)";
		}
		String s = id;
		boolean set = id.startsWith("gear-set-");
		// The category says it already: Items, Locations, Bosses.
		for (String prefix : new String[]{"gear-set-", "gear-", "area-", "enter-", "boss-"})
		{
			if (s.startsWith(prefix))
			{
				s = s.substring(prefix.length());
				break;
			}
		}
		s = s.replace('-', ' ');
		s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
		return set ? "Full " + s + " (set)" : s;
	}
}

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
 * The message editor: every rule of one or more groups in phrases.json, with
 * what triggers it, an on/off tick and its message lines (one per line) free
 * to edit. Save writes straight back to phrases.json, which the plugin
 * hot-reloads within a second — no restart, no rebuild.
 *
 * <p>In structural mode (the location editor) each rule's region ids are
 * editable too, rules can be deleted outright and new ones added — paste in
 * the id that ::follower where prints and the follower learns a new place.
 *
 * <p>Deliberately a plain window rather than a sidebar panel: message lines
 * are sentences, and editing sentences in a 225px column is misery.
 */
@Slf4j
public class PhrasesDialog extends JDialog
{
	private final Gson gson;
	private final Path file;

	/**
	 * The rule groups this window edits. Usually one, but related groups can
	 * be shown together - idle chatter belongs with the status lines as far
	 * as anyone looking for "things the follower says about itself" is
	 * concerned, even though the engine keeps them separate so they can be
	 * toggled apart.
	 */
	private final java.util.List<String> groups;

	/** New rules are created in the first group. */
	private final String primaryGroup;
	private final boolean structural;

	private final JPanel list = new JPanel();
	private final JLabel status = new JLabel(" ");
	private final List<Row> rows = new ArrayList<>();
	private final List<Row> added = new ArrayList<>();
	private final Set<String> deleted = new HashSet<>();

	private static final class Row
	{
		String id;
		final JTextField name;
		final JCheckBox enabled;
		final JTextField regions;
		final JTextArea say;
		JPanel panel;

		Row(String id, JTextField name, JCheckBox enabled, JTextField regions, JTextArea say)
		{
			this.id = id;
			this.name = name;
			this.enabled = enabled;
			this.regions = regions;
			this.say = say;
		}
	}

	/**
	 * @param group one or more rule groups to edit; the first receives any
	 * rules added from this window
	 */
	public PhrasesDialog(Gson gson, Path file, String group, String title, String hintText,
		boolean structural)
	{
		super((java.awt.Frame) null, title, false);
		this.gson = gson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create();
		this.file = file;
		this.groups = java.util.Arrays.asList(group.split("\\s*,\\s*"));
		this.primaryGroup = this.groups.get(0);
		this.structural = structural;

		setDefaultCloseOperation(HIDE_ON_CLOSE);

		JPanel root = new JPanel(new BorderLayout(0, 6));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel hint = new JLabel("<html>" + hintText + "</html>");
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		root.add(hint, BorderLayout.NORTH);

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
		if (structural)
		{
			JButton add = new JButton("Add location");
			add.setToolTipText("A new place for the follower to comment on."
				+ " ::follower where prints the region id you are standing in.");
			add.addActionListener(e -> addBlankRow());
			buttons.add(add);
		}
		JButton reload = new JButton("Reload");
		reload.setToolTipText("Throw away unsaved edits and re-read phrases.json");
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

	/** Shows the window with fresh file contents, fronting it if already open. */
	public void open()
	{
		refresh();
		setVisible(true);
		toFront();
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
			setStatus("Could not read phrases.json: " + e.getMessage(), true);
			list.revalidate();
			list.repaint();
			return;
		}

		int count = 0;
		for (JsonElement element : parsed.getAsJsonArray("rules"))
		{
			JsonObject rule = element.getAsJsonObject();
			if (!groups.contains(optString(rule, "group")) || optString(rule, "id") == null)
			{
				continue;
			}
			list.add(buildRow(rule));
			count++;
		}

		setStatus(count + " rules loaded", false);
		list.revalidate();
		list.repaint();
	}

	private JPanel buildRow(JsonObject rule)
	{
		String id = optString(rule, "id");

		JPanel row = rowShell();

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setAlignmentX(LEFT_ALIGNMENT);

		JLabel title = new JLabel(prettyName(id));
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		header.add(title, BorderLayout.CENTER);

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JCheckBox enabled = new JCheckBox("On");
		enabled.setSelected(!rule.has("enabled") || rule.get("enabled").getAsBoolean());
		enabled.setBackground(ColorScheme.DARK_GRAY_COLOR);
		enabled.setToolTipText("Untick to silence just this rule");
		controls.add(enabled);
		if (structural)
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
				setStatus("'" + prettyName(id) + "' will be removed on Save", false);
			});
			controls.add(delete);
		}
		header.add(controls, BorderLayout.EAST);
		row.add(header);

		String note = optString(rule, "note");
		if (note != null && !note.isEmpty())
		{
			JLabel items = new JLabel("<html><body style='width:430px'>" + escapeHtml(note) + "</body></html>");
			items.setFont(FontManager.getRunescapeSmallFont());
			items.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			items.setAlignmentX(LEFT_ALIGNMENT);
			items.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
			row.add(items);
		}

		JTextField regionsField = null;
		if (structural)
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
				row.add(labelledField("Regions:", regionsField));
			}
			else
			{
				JLabel special = new JLabel("(special trigger — edit in phrases.json)");
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
		row.add(area);

		Row r = new Row(id, null, enabled, regionsField, area);
		r.panel = row;
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
		row.add(labelledField("Name:", name));
		JTextField regions = new JTextField();
		regions.setToolTipText("Comma separated region ids — ::follower where prints the one you stand in");
		row.add(labelledField("Regions:", regions));
		JTextArea area = sayArea("", 3);
		row.add(area);

		JCheckBox enabled = new JCheckBox("On", true);
		enabled.setBackground(ColorScheme.DARK_GRAY_COLOR);
		controls.add(enabled);
		controls.add(delete);

		Row r = new Row(null, name, enabled, regions, area);
		r.panel = row;
		added.add(r);
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
	 */
	private void save()
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

			parsed.add("rules", kept);
			writeAtomically(gson.toJson(parsed) + "\n");

			if (warnings.isEmpty())
			{
				setStatus("Saved — the follower picks it up within a second", false);
			}
			else
			{
				setStatus("Saved with warnings: " + String.join("; ", warnings), true);
			}
			refresh();
		}
		catch (Exception e)
		{
			log.warn("Could not save {}", file, e);
			setStatus("Save failed: " + e.getMessage(), true);
		}
	}

	/**
	 * Replaces the rules file in one step, via a sibling temp file.
	 *
	 * <p>The plugin polls this same file every game tick and reloads it when it
	 * changes, which is what makes editing feel live. Writing in place truncates
	 * it first, so a poll landing mid-write reads half a file, fails to parse
	 * and reports a JSON error in the chatbox for something the user did
	 * nothing wrong in. A rename is a single step: the poller sees the old file
	 * or the new one, never a torn one.
	 */
	private void writeAtomically(String contents) throws IOException
	{
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
		Files.write(temp, bytes);

		// Windows will not let a file be replaced while another handle has it
		// open, and the plugin opens this one every game tick to poll it. So
		// the rename loses that race often enough to matter, and losing it
		// means the user's edits vanish with a "save failed" - which is a worse
		// outcome than the torn read the rename was there to prevent.
		//
		// Retry across a few polls first; the reader holds the file only for
		// as long as it takes to parse.
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
				// Some filesystems cannot promise it; a plain replace is still
				// one call rather than a truncate followed by a write.
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
				return;
			}
			catch (FileSystemException e)
			{
				sleepBriefly();
			}
		}

		// Still contended. Write in place rather than lose the edit: the loader
		// keeps its previous rules if it catches this half-written, and picks
		// the file up on the following poll.
		Files.write(file, bytes);
		Files.deleteIfExists(temp);
	}

	private static void sleepBriefly()
	{
		try
		{
			Thread.sleep(25);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
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
			JsonArray regions = parseRegions(row.regions.getText());
			if (regions.size() > 0)
			{
				rule.getAsJsonObject("when").add("regions", regions);
			}
			else
			{
				warnings.add(prettyName(row.id) + " kept its old regions (none given)");
			}
		}
	}

	private JsonObject buildAddedRule(Row row, Set<String> existingIds, List<String> warnings)
	{
		String name = row.name.getText().trim();
		JsonArray regions = parseRegions(row.regions.getText());
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
		rule.addProperty("group", primaryGroup);
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
		JPanel row = new JPanel();
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

	private static JsonArray parseRegions(String text)
	{
		JsonArray regions = new JsonArray();
		for (String token : text.split("[^0-9]+"))
		{
			if (!token.isEmpty())
			{
				regions.add(Integer.parseInt(token));
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
		status.setText(text);
		status.setForeground(error ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.MEDIUM_GRAY_COLOR);
	}

	private static String optString(JsonObject object, String key)
	{
		return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
	}

	private static String prettyName(String id)
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
		s = s.replace('-', ' ');
		s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
		return set ? "Full " + s + " (set)" : s;
	}

	private static String escapeHtml(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The item-message editor: every rule in the "gear" group of phrases.json,
 * shown with the items that trigger it, an on/off tick and its message lines
 * (one per line) free to edit. Save writes straight back to phrases.json,
 * which the plugin hot-reloads within a second — no restart, no rebuild.
 *
 * <p>Deliberately a plain window rather than a sidebar panel: message lines
 * are sentences, and editing sentences in a 225px column is misery.
 */
@Slf4j
public class GearPhrasesDialog extends JDialog
{
	private static final String GROUP = "gear";

	private final Gson gson;
	private final Path file;

	private final JPanel list = new JPanel();
	private final JLabel status = new JLabel(" ");
	private final List<Row> rows = new ArrayList<>();

	private static final class Row
	{
		final String id;
		final JCheckBox enabled;
		final JTextArea say;

		Row(String id, JCheckBox enabled, JTextArea say)
		{
			this.id = id;
			this.enabled = enabled;
			this.say = say;
		}
	}

	public GearPhrasesDialog(Gson gson, Path file)
	{
		super((java.awt.Frame) null, "Follower Buddy — Item messages", false);
		this.gson = gson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create();
		this.file = file;

		setDefaultCloseOperation(HIDE_ON_CLOSE);

		JPanel root = new JPanel(new BorderLayout(0, 6));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel hint = new JLabel("<html>One message per line. Edit, remove or add lines, untick a rule to"
			+ " silence it, then Save — changes reach the follower within a second.</html>");
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
			if (!GROUP.equals(optString(rule, "group")) || optString(rule, "id") == null)
			{
				continue;
			}
			list.add(buildRow(rule));
			count++;
		}

		setStatus(count + " item rules loaded", false);
		list.revalidate();
		list.repaint();
	}

	private JPanel buildRow(JsonObject rule)
	{
		String id = optString(rule, "id");

		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARKER_GRAY_COLOR),
			BorderFactory.createEmptyBorder(8, 0, 8, 8)));
		row.setAlignmentX(LEFT_ALIGNMENT);

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setAlignmentX(LEFT_ALIGNMENT);

		JLabel title = new JLabel(prettyName(id));
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		header.add(title, BorderLayout.CENTER);

		JCheckBox enabled = new JCheckBox("On");
		enabled.setSelected(!rule.has("enabled") || rule.get("enabled").getAsBoolean());
		enabled.setBackground(ColorScheme.DARK_GRAY_COLOR);
		enabled.setToolTipText("Untick to silence just this rule");
		header.add(enabled, BorderLayout.EAST);
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

		JTextArea area = new JTextArea(text.toString(), Math.max(2, say.size()), 40);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setFont(FontManager.getRunescapeFont());
		area.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		area.setForeground(Color.WHITE);
		area.setCaretColor(Color.WHITE);
		area.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		area.setAlignmentX(LEFT_ALIGNMENT);
		row.add(area);

		rows.add(new Row(id, enabled, area));
		return row;
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

			for (JsonElement element : parsed.getAsJsonArray("rules"))
			{
				JsonObject rule = element.getAsJsonObject();
				String id = optString(rule, "id");
				Row row = rows.stream().filter(r -> r.id.equals(id)).findFirst().orElse(null);
				if (row == null)
				{
					continue;
				}

				JsonArray say = new JsonArray();
				for (String line : row.say.getText().split("\n"))
				{
					String trimmed = line.trim();
					if (!trimmed.isEmpty())
					{
						say.add(trimmed);
					}
				}

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
					if (empty)
					{
						row.enabled.setSelected(false);
					}
				}
			}

			Files.write(file, (gson.toJson(parsed) + "\n").getBytes(StandardCharsets.UTF_8));
			setStatus("Saved — the follower picks it up within a second", false);
		}
		catch (Exception e)
		{
			log.warn("Could not save {}", file, e);
			setStatus("Save failed: " + e.getMessage(), true);
		}
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
		s = s.replace('-', ' ');
		s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
		return set ? "Full " + s + " (set)" : s;
	}

	private static String escapeHtml(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}

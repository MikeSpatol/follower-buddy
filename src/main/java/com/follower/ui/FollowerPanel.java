package com.follower.ui;

import com.follower.appearance.GamePalette;
import com.follower.appearance.HslColor;
import com.follower.appearance.ModelRepository;
import com.follower.appearance.Outfit;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.api.kit.KitType;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Outfit picker.
 *
 * <p>Gear is laid out as the in-game equipment tab - an icon grid in the familiar
 * cross shape - because that is a layout every player already knows how to read.
 * Click a slot to search items for it; right-click to clear it. Body styles and
 * colours sit below, with colours as real swatches over the game's own palettes.
 */
public class FollowerPanel extends PluginPanel
{
	private static final int PREVIEW_SCALE = 3;
	private static final int CELL = 44;

	/** The equipment-tab shape; null cells are spacers. */
	private static final KitType[][] GEAR_GRID = {
		{null, KitType.HEAD, null},
		{KitType.CAPE, KitType.AMULET, null},
		{KitType.WEAPON, KitType.TORSO, KitType.SHIELD},
		{null, KitType.LEGS, null},
		{KitType.HANDS, null, KitType.BOOTS},
	};

	/** Body kits that make up the character itself, in a sensible editing order. */
	private static final KitType[] BODY_PARTS = {
		KitType.HAIR, KitType.JAW, KitType.TORSO, KitType.ARMS,
		KitType.HANDS, KitType.LEGS, KitType.BOOTS,
	};

	private final ItemManager itemManager;
	private final ModelRepository repository;

	private final BiConsumer<Integer, KitType> onEquip;
	private final Consumer<KitType> onClear;
	private final Runnable onCopyGear;
	private final Runnable onClearAll;
	private final java.util.function.IntConsumer onGender;
	private final BiConsumer<KitType, Integer> onCycleKit;

	/** (body colour slot 0-4, palette index) - the exact-palette picker. */
	private final BiConsumer<Integer, Integer> onBodyColor;

	/** Opens the message editor window; set by the plugin after construction. */
	private Runnable onEditMessages = () -> { };

	public void setOnEditMessages(Runnable onEditMessages)
	{
		this.onEditMessages = onEditMessages;
	}

	// ------------------------------------------------------------------- mute

	/**
	 * The same Mute as the one in settings, surfaced where people actually
	 * are. Both write the one config key, so they can never disagree.
	 */
	private final javax.swing.JCheckBox muteBox = new javax.swing.JCheckBox("Mute the follower");

	private Consumer<Boolean> onMute = m -> { };

	/** Set while the box is being synced FROM config, so it does not echo. */
	private boolean suppressMuteEvents;

	public void setOnMute(Consumer<Boolean> onMute)
	{
		this.onMute = onMute;
	}

	/** Reflects a config-side change without writing it back again. */
	public void setMuted(boolean muted)
	{
		suppressMuteEvents = true;
		try
		{
			muteBox.setSelected(muted);
		}
		finally
		{
			suppressMuteEvents = false;
		}
	}

	/** Opens the memory window (R20); set by the plugin after construction. */
	private Runnable onShowMemory = () -> { };

	public void setOnShowMemory(Runnable onShowMemory)
	{
		this.onShowMemory = onShowMemory;
	}

	/** Opens the conversation editor; set by the plugin after construction. */
	private Runnable onEditDialogs = () -> { };

	public void setOnEditDialogs(Runnable onEditDialogs)
	{
		this.onEditDialogs = onEditDialogs;
	}

	/** Opens the Sounds window with no lines, just the folder and the how-to. */
	private Runnable onAttachSounds = () -> { };

	public void setOnAttachSounds(Runnable onAttachSounds)
	{
		this.onAttachSounds = onAttachSounds;
	}

	/** Runs the same export-or-import choice the Messages window's Share button runs. */
	private Runnable onShareLines = () -> { };

	public void setOnShareLines(Runnable onShareLines)
	{
		this.onShareLines = onShareLines;
	}

	// -------------------------------------------------------- synced settings

	/**
	 * Chattiness, the chatbox mirror and errands follow the Mute pattern: the
	 * same key as settings, written through the plugin, and reflected back
	 * without an echo when the setting changes from the other side.
	 */
	private final javax.swing.JComboBox<com.follower.speech.Chattiness> chattinessBox =
		new javax.swing.JComboBox<>(com.follower.speech.Chattiness.values());
	private final javax.swing.JCheckBox chatboxBox =
		new javax.swing.JCheckBox("Also show lines in the chatbox");
	private final javax.swing.JCheckBox errandsBox = new javax.swing.JCheckBox("Go on errands");

	private Consumer<com.follower.speech.Chattiness> onChattiness = c -> { };
	private Consumer<Boolean> onMirrorToChat = m -> { };
	private Consumer<Boolean> onErrandsEnabled = e -> { };

	/** Set while a synced control is being set FROM config, so it does not echo. */
	private boolean suppressSyncEvents;

	public void setOnChattiness(Consumer<com.follower.speech.Chattiness> onChattiness)
	{
		this.onChattiness = onChattiness;
	}

	public void setOnMirrorToChat(Consumer<Boolean> onMirrorToChat)
	{
		this.onMirrorToChat = onMirrorToChat;
	}

	public void setOnErrandsEnabled(Consumer<Boolean> onErrandsEnabled)
	{
		this.onErrandsEnabled = onErrandsEnabled;
	}

	public void setChattiness(com.follower.speech.Chattiness chattiness)
	{
		synced(() -> chattinessBox.setSelectedItem(chattiness));
	}

	public void setMirrorToChat(boolean on)
	{
		synced(() -> chatboxBox.setSelected(on));
	}

	public void setErrandsEnabled(boolean on)
	{
		synced(() -> errandsBox.setSelected(on));
	}

	/** Applies a config-side value on the event thread with the echo guard up. */
	private void synced(Runnable update)
	{
		SwingUtilities.invokeLater(() ->
		{
			suppressSyncEvents = true;
			try
			{
				update.run();
			}
			finally
			{
				suppressSyncEvents = false;
			}
		});
	}

	// --------------------------------------------------------------- behaviour

	private final JButton stayButton = new JButton("Stay here");
	private boolean staying;
	private Consumer<Boolean> onFollowEnabled = f -> { };
	private Runnable onComeHere = () -> { };
	private final JLabel behaviourCaption = new JLabel();
	private final javax.swing.JComboBox<String> errandBox = new javax.swing.JComboBox<>();
	private List<String> errandKeys = java.util.Collections.emptyList();
	private Consumer<String> onErrand = k -> { };

	public void setOnFollowEnabled(Consumer<Boolean> onFollowEnabled)
	{
		this.onFollowEnabled = onFollowEnabled;
	}

	public void setOnComeHere(Runnable onComeHere)
	{
		this.onComeHere = onComeHere;
	}

	public void setOnErrand(Consumer<String> onErrand)
	{
		this.onErrand = onErrand;
	}

	/** Reflects the player-commanded stay; the button always offers the other state. */
	public void setStaying(boolean staying)
	{
		SwingUtilities.invokeLater(() ->
		{
			this.staying = staying;
			stayButton.setText(staying ? "Follow me" : "Stay here");
		});
	}

	public void setFollowerName(String name)
	{
		SwingUtilities.invokeLater(() -> behaviourCaption.setText(
			"<html><body style='width:180px'>Right-click " + escapeHtml(name)
				+ " in game for Talk-to, Stay, Dance, Wave and Step-in-front."
				+ " Hold Shift and right-click any tile to send it there.</body></html>"));
	}

	/** The errands on offer, key to display name, in the order to list them. */
	public void setErrands(Map<String, String> keyToLabel)
	{
		SwingUtilities.invokeLater(() ->
		{
			errandKeys = new java.util.ArrayList<>(keyToLabel.keySet());
			errandBox.removeAllItems();
			for (String label : keyToLabel.values())
			{
				errandBox.addItem(label);
			}
		});
	}

	// ---------------------------------------------------------------- commands

	private Runnable onFix = () -> { };
	private Runnable onReload = () -> { };
	private final javax.swing.JTextArea commandList = new javax.swing.JTextArea();
	private final JLabel regionCaption = new JLabel();

	public void setOnFix(Runnable onFix)
	{
		this.onFix = onFix;
	}

	public void setOnReload(Runnable onReload)
	{
		this.onReload = onReload;
	}

	/** The command listing, one line each; the plugin decides what goes in. */
	public void setCommands(List<String> lines)
	{
		SwingUtilities.invokeLater(() ->
		{
			commandList.setText(String.join("\n", lines));
			commandList.setCaretPosition(0);
		});
	}

	/** The region the player stands in, or below zero before the first tick. */
	public void setRegionId(int region)
	{
		SwingUtilities.invokeLater(() -> regionCaption.setText(
			"<html><body style='width:180px'>Your region id: "
				+ (region > 0 ? Integer.toString(region) : "not known until you log in")
				+ ", for Locations rules.</body></html>"));
	}

	// ------------------------------------------------------------------- folds

	/** One foldable section: its header label carries the +/- prefix. */
	private static final class Section
	{
		final String title;
		final JLabel label;
		final JPanel body;

		Section(String title, JLabel label, JPanel body)
		{
			this.title = title;
			this.label = label;
			this.body = body;
		}
	}

	private final Map<String, Section> sections = new java.util.LinkedHashMap<>();
	private final java.util.Set<String> openSections;
	private Consumer<String> onFoldsChanged = s -> { };

	/** The whole scrolling column, revalidated as sections open and close. */
	private final JPanel column = new ScrollableColumn();

	public void setOnFoldsChanged(Consumer<String> onFoldsChanged)
	{
		this.onFoldsChanged = onFoldsChanged;
	}

	/** The stored fold list, "talking,outfit", as the set of open section keys. */
	static java.util.Set<String> parseFolds(String stored)
	{
		java.util.Set<String> open = new java.util.LinkedHashSet<>();
		if (stored == null)
		{
			return open;
		}
		for (String key : stored.split(","))
		{
			String trimmed = key.trim().toLowerCase(java.util.Locale.ROOT);
			if (!trimmed.isEmpty())
			{
				open.add(trimmed);
			}
		}
		return open;
	}

	// ---------------------------------------------------------------- profiles

	private final javax.swing.JComboBox<String> profileCombo = new javax.swing.JComboBox<>();
	private final JButton profileDelete = new JButton("Delete profile");

	private Consumer<String> onProfileLoad = s -> { };
	private Consumer<String> onProfileSave = s -> { };
	private Consumer<String> onProfileDelete = s -> { };

	/** Whether a profile is one of the seeded thrall looks; set by the plugin. */
	private java.util.function.Predicate<String> profileProtected = n -> false;

	public void setOnProfileLoad(Consumer<String> onProfileLoad)
	{
		this.onProfileLoad = onProfileLoad;
	}

	public void setOnProfileSave(Consumer<String> onProfileSave)
	{
		this.onProfileSave = onProfileSave;
	}

	public void setOnProfileDelete(Consumer<String> onProfileDelete)
	{
		this.onProfileDelete = onProfileDelete;
	}

	public void setProfileProtected(java.util.function.Predicate<String> profileProtected)
	{
		this.profileProtected = profileProtected;
	}

	/** Set while the dropdown is being repopulated, so selection events are ignored. */
	private boolean suppressProfileEvents;

	private List<String> profileNames = java.util.Collections.emptyList();

	/**
	 * Refreshes the dropdown and selects {@code active}. Selection events are
	 * suppressed throughout: repopulating must not read as the user picking a
	 * profile, which would re-load the outfit mid-edit. The thrall dropdowns
	 * are filled from the same names.
	 */
	public void setProfileNames(java.util.List<String> names, String active)
	{
		profileNames = new java.util.ArrayList<>(names);
		suppressProfileEvents = true;
		try
		{
			profileCombo.removeAllItems();
			for (String name : names)
			{
				profileCombo.addItem(name);
			}
			profileCombo.setSelectedItem(active == null ? null : active);
		}
		finally
		{
			suppressProfileEvents = false;
		}
		fillThrallBoxes();
		refreshDeleteButton();
	}

	private String profileName()
	{
		Object item = profileCombo.getSelectedItem();
		return item == null ? "" : item.toString().trim();
	}

	// ----------------------------------------------------------------- thralls

	/** Style key to its dropdown, in the order the rows are shown. */
	private final Map<String, javax.swing.JComboBox<String>> thrallBoxes =
		new java.util.LinkedHashMap<>();

	/** Style key to the profile name the setting holds. */
	private final Map<String, String> thrallProfiles = new java.util.LinkedHashMap<>();

	private BiConsumer<String, String> onThrallProfile = (style, name) -> { };

	/** (style key, profile name) as a thrall dropdown is changed. */
	public void setOnThrallProfile(BiConsumer<String, String> onThrallProfile)
	{
		this.onThrallProfile = onThrallProfile;
	}

	/** Reflects the three thrall settings without writing them back. */
	public void setThrallProfiles(String melee, String ranged, String magic)
	{
		SwingUtilities.invokeLater(() ->
		{
			thrallProfiles.put("melee", melee == null ? "" : melee.trim());
			thrallProfiles.put("ranged", ranged == null ? "" : ranged.trim());
			thrallProfiles.put("magic", magic == null ? "" : magic.trim());
			fillThrallBoxes();
			refreshDeleteButton();
			profileCombo.repaint();
		});
	}

	private void fillThrallBoxes()
	{
		suppressSyncEvents = true;
		try
		{
			for (Map.Entry<String, javax.swing.JComboBox<String>> entry : thrallBoxes.entrySet())
			{
				javax.swing.JComboBox<String> box = entry.getValue();
				box.removeAllItems();
				for (String name : profileNames)
				{
					box.addItem(name);
				}
				String wanted = thrallProfiles.get(entry.getKey());
				if (wanted != null && !wanted.isEmpty())
				{
					// A typed name nothing is saved under still shows, as
					// typed, rather than the dropdown quietly claiming another.
					if (!profileNames.contains(wanted))
					{
						box.addItem(wanted);
					}
					box.setSelectedItem(wanted);
				}
				else
				{
					box.setSelectedItem(null);
				}
			}
		}
		finally
		{
			suppressSyncEvents = false;
		}
	}

	/** " (thrall: melee)" and so on, or nothing, for the profile dropdown. */
	private String thrallSuffix(String name)
	{
		List<String> styles = new java.util.ArrayList<>();
		for (Map.Entry<String, String> entry : thrallProfiles.entrySet())
		{
			if (entry.getValue().equals(name))
			{
				styles.add(entry.getKey());
			}
		}
		return styles.isEmpty() ? "" : " (thrall: " + String.join(", ", styles) + ")";
	}

	/** Delete is offered only for a profile no thrall depends on. */
	private void refreshDeleteButton()
	{
		String name = profileName();
		profileDelete.setEnabled(!name.isEmpty() && !profileProtected.test(name)
			&& thrallSuffix(name).isEmpty());
	}

	private final JPanel gearGrid = new JPanel();
	private final JPanel bodyList = new JPanel();
	private final JPanel picker = new JPanel();
	private final JPanel resultList = new JPanel();

	/** Matches not yet built into rows, drained as the list scrolls down. */
	private final java.util.ArrayDeque<ModelRepository.WearableItem> pendingResults =
		new java.util.ArrayDeque<>();

	/** Rows built per slice - the first fill and each scroll-to-the-end. */
	private static final int RESULT_CHUNK = 100;

	private JScrollPane resultScroll;
	private final JTextField search = new JTextField();
	private final JLabel status = new JLabel();
	private final JLabel pickerTitle = new JLabel();

	private final JLabel previewIcon = new JLabel();
	private final JLabel previewName = new JLabel();
	private final javax.swing.JCheckBox showAll = new javax.swing.JCheckBox();

	private Outfit current = new Outfit();
	private KitType activeSlot;

	/** itemId -> slot, filled in asynchronously by the plugin. */
	private Map<Integer, KitType> slotIndex = java.util.Collections.emptyMap();

	public FollowerPanel(ItemManager itemManager, ModelRepository repository,
		BiConsumer<Integer, KitType> onEquip, Consumer<KitType> onClear,
		Runnable onCopyGear, Runnable onClearAll,
		java.util.function.IntConsumer onGender,
		BiConsumer<KitType, Integer> onCycleKit,
		BiConsumer<Integer, Integer> onBodyColor,
		String openFolds)
	{
		super(false);
		this.itemManager = itemManager;
		this.repository = repository;
		this.onEquip = onEquip;
		this.onClear = onClear;
		this.onCopyGear = onCopyGear;
		this.onClearAll = onClearAll;
		this.onGender = onGender;
		this.onCycleKit = onCycleKit;
		this.onBodyColor = onBodyColor;
		this.openSections = parseFolds(openFolds);

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		// The panel is taller than most sidebars and the picker grows as you
		// search, so nothing may impose a floor on the viewport height.
		setMinimumSize(new Dimension(0, 0));

		// A ScrollableColumn, not a plain panel: a plain view is laid out at
		// its own preferred width, so any child preferring more than the
		// sidebar (or the width left once the scrollbar appears) pushed the
		// whole column past the right edge - padding gone, buttons clipped -
		// with the horizontal scrollbar deliberately never there to save it.
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setBackground(ColorScheme.DARK_GRAY_COLOR);
		column.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		column.add(buildHeader());
		buildTalking(section("talking", "TALKING"));
		buildMemory(section("memory", "MEMORY"));
		buildOutfit(section("outfit", "OUTFIT"));
		buildBehaviour(section("behaviour", "BEHAVIOUR"));
		buildCommands(section("commands", "COMMANDS"));

		// Everything scrolls together: the sidebar is never tall enough for
		// the dialogue editors, profiles, equipment grid and item picker at
		// once, and a clipped panel hides controls with no way to reach them.
		JScrollPane scroll = new JScrollPane(column,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setMinimumSize(new Dimension(0, 0));
		add(scroll, BorderLayout.CENTER);

		showPicker(null);
	}

	// ------------------------------------------------------------------ building

	private JPanel buildHeader()
	{
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// The one line that says what this thing IS, before any controls do.
		// The body style width matters: an HTML label's preferred width is one
		// unwrapped line, and a single wide child forces the WHOLE panel past
		// the sidebar, clipping every section against the no-horizontal-scroll
		// viewport.
		JLabel identity = new JLabel("<html><body style='width:180px'>Only you"
			+ " can see this follower. Dress it under Outfit; edit what it says"
			+ " under Talking; see what it remembers under Memory.</body></html>");
		identity.setFont(FontManager.getRunescapeSmallFont());
		identity.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		identity.setAlignmentX(LEFT_ALIGNMENT);
		identity.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		header.add(identity);

		return header;
	}

	/**
	 * A foldable section: a clickable header and a body that shows while the
	 * key is in the open set. Both go straight into the column; the body
	 * comes back for filling.
	 */
	private JPanel section(String key, String title)
	{
		JLabel label = new JLabel();
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.BRAND_ORANGE);
		JPanel header = headerRow(label);
		header.setToolTipText("Click to open or close this section");
		clickable(header, () -> toggleSection(key));

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setAlignmentX(LEFT_ALIGNMENT);
		body.setVisible(openSections.contains(key));

		sections.put(key, new Section(title, label, body));
		label.setText(foldTitle(key));
		column.add(header);
		column.add(body);
		return body;
	}

	private String foldTitle(String key)
	{
		return (openSections.contains(key) ? "- " : "+ ") + sections.get(key).title;
	}

	private void toggleSection(String key)
	{
		if (!openSections.remove(key))
		{
			openSections.add(key);
		}
		Section section = sections.get(key);
		section.body.setVisible(openSections.contains(key));
		section.label.setText(foldTitle(key));
		column.revalidate();
		column.repaint();
		// Stored in section order, whatever order they were clicked in.
		List<String> open = new java.util.ArrayList<>();
		for (String k : sections.keySet())
		{
			if (openSections.contains(k))
			{
				open.add(k);
			}
		}
		onFoldsChanged.accept(String.join(",", open));
	}

	/**
	 * A click anywhere on the component runs the action. Swing hands a click
	 * to the deepest child under it, so the children listen too.
	 */
	private static void clickable(javax.swing.JComponent component, Runnable action)
	{
		component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		component.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					action.run();
				}
			}
		});
		for (java.awt.Component child : component.getComponents())
		{
			if (child instanceof javax.swing.JComponent)
			{
				clickable((javax.swing.JComponent) child, action);
			}
		}
	}

	/** A width-bounded grey line of explanation, so it wraps instead of widening the panel. */
	private JLabel caption(String text)
	{
		JLabel label = new JLabel("<html><body style='width:180px'>" + text + "</body></html>");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		label.setAlignmentX(LEFT_ALIGNMENT);
		label.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
		return label;
	}

	/** A full-width column of buttons. */
	private JPanel buttonColumn(int count)
	{
		JPanel buttons = new JPanel(new GridLayout(0, 1, 6, 4));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.setAlignmentX(LEFT_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30 * count));
		return buttons;
	}

	/** Two buttons side by side. */
	private JPanel buttonPair()
	{
		JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.setAlignmentX(LEFT_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		return buttons;
	}

	private JButton button(String text, String tooltip, Runnable action)
	{
		JButton button = new JButton(text);
		button.setToolTipText(tooltip);
		button.setFocusPainted(false);
		button.addActionListener(e -> action.run());
		return button;
	}

	private void styleTick(javax.swing.JCheckBox box, String tooltip)
	{
		box.setToolTipText(tooltip);
		box.setFont(FontManager.getRunescapeSmallFont());
		box.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		box.setBackground(ColorScheme.DARK_GRAY_COLOR);
		box.setAlignmentX(LEFT_ALIGNMENT);
	}

	/** A small label on the left and a control taking the rest of the row. */
	private JPanel labelledRow(String text, javax.swing.JComponent control)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setPreferredSize(new Dimension(NAME_WIDTH, ROW_CONTENT_HEIGHT));
		row.add(label, BorderLayout.WEST);
		row.add(control, BorderLayout.CENTER);
		return row;
	}

	private void buildTalking(JPanel body)
	{
		body.add(caption("What it says and when. Messages holds every line;"
			+ " Conversations the Talk-to trees."));

		styleTick(muteBox, "The follower stops talking. All your other"
			+ " speech settings are kept. Same switch as Mute in settings.");
		muteBox.addActionListener(e ->
		{
			if (!suppressMuteEvents)
			{
				onMute.accept(muteBox.isSelected());
			}
		});
		body.add(muteBox);

		chattinessBox.setToolTipText("How much it talks. Same setting as Chattiness in settings.");
		chattinessBox.addActionListener(e ->
		{
			Object picked = chattinessBox.getSelectedItem();
			if (!suppressSyncEvents && picked != null)
			{
				onChattiness.accept((com.follower.speech.Chattiness) picked);
			}
		});
		body.add(labelledRow("Chattiness", chattinessBox));

		styleTick(chatboxBox, "Every line also appears in the chatbox under the"
			+ " follower's name. Same switch as in settings.");
		chatboxBox.addActionListener(e ->
		{
			if (!suppressSyncEvents)
			{
				onMirrorToChat.accept(chatboxBox.isSelected());
			}
		});
		body.add(chatboxBox);
		body.add(javax.swing.Box.createVerticalStrut(4));

		// Four doors, not nine: the old grid surfaced every internal rule
		// group as its own button, a navigation decision nobody should have
		// to make cold. The categories live in a dropdown inside Messages.
		JPanel buttons = buttonColumn(4);
		buttons.add(button("Messages...",
			"Everything the follower says - items, places, bosses,"
				+ " combat, statuses, quest NPCs and errands - viewable and editable",
			() -> onEditMessages.run()));
		buttons.add(button("Conversations...",
			"View and edit the conversations the follower STARTS -"
				+ " the ones that replace Talk-to while it is waiting on an answer",
			() -> onEditDialogs.run()));
		buttons.add(button("Attach sounds...",
			"Where sound files go, and how to attach one to a line",
			() -> onAttachSounds.run()));
		buttons.add(button("Share lines...",
			"Export what you have written, or take on somebody else's",
			() -> onShareLines.run()));
		body.add(buttons);
	}

	private void buildMemory(JPanel body)
	{
		body.add(caption("What it remembers about you. The Reminiscing toggle in"
			+ " settings silences the lines that use this; Forget everything in"
			+ " the window erases it."));
		JPanel buttons = buttonColumn(1);
		buttons.add(button("Memory...",
			"Everything the follower knows about you, and the button that erases it",
			() -> onShowMemory.run()));
		body.add(buttons);
	}

	private void buildOutfit(JPanel body)
	{
		JPanel buttons = buttonPair();
		buttons.add(button("Copy my gear",
			"Fill every slot from what you are currently wearing", () -> onCopyGear.run()));
		buttons.add(button("Clear all",
			"Strip the follower back to the default body", () -> onClearAll.run()));
		body.add(buttons);
		body.add(javax.swing.Box.createVerticalStrut(6));

		JLabel profilesLabel = new JLabel("OUTFIT PROFILES");
		profilesLabel.setFont(FontManager.getRunescapeSmallFont());
		profilesLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		profilesLabel.setAlignmentX(LEFT_ALIGNMENT);
		body.add(profilesLabel);

		// Pick a profile and you are wearing it; every change from then on is
		// saved straight back to it. Save as new makes one, Delete removes it.
		profileCombo.setAlignmentX(LEFT_ALIGNMENT);
		profileCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		// The item stays the bare name; only its rendering says which thrall
		// wears it, so profileName() and the plugin see the name as stored.
		profileCombo.setRenderer(new javax.swing.DefaultListCellRenderer()
		{
			@Override
			public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
				Object value, int index, boolean isSelected, boolean cellHasFocus)
			{
				Object shown = value == null ? null : value + thrallSuffix(value.toString());
				return super.getListCellRendererComponent(list, shown, index, isSelected, cellHasFocus);
			}
		});
		profileCombo.addActionListener(e ->
		{
			// Fires for programmatic repopulation too; the guard keeps a
			// refresh from re-loading (and so re-saving) the outfit.
			if (!suppressProfileEvents)
			{
				onProfileLoad.accept(profileName());
			}
			refreshDeleteButton();
		});
		body.add(profileCombo);

		// The autosave behaviour used to live in a hover tooltip, which is
		// exactly where the one surprising fact about profiles should not be.
		body.add(caption("Changes save to the selected profile automatically"));

		JPanel profileButtons = buttonPair();
		profileButtons.add(button("Save as new...",
			"Save the follower's current outfit as a new profile", () ->
		{
			String name = javax.swing.JOptionPane.showInputDialog(this,
				"Name for the new outfit profile:", "New profile",
				javax.swing.JOptionPane.PLAIN_MESSAGE);
			if (name != null && !name.trim().isEmpty())
			{
				onProfileSave.accept(name.trim());
			}
		}));

		profileDelete.setToolTipText("Remove the selected profile. A profile a thrall"
			+ " wears cannot be removed.");
		profileDelete.setFocusPainted(false);
		profileDelete.addActionListener(e ->
		{
			String name = profileName();
			if (name.isEmpty())
			{
				return;
			}
			// The other destructive button in this plugin (Forget everything)
			// confirms; a profile deserves no less than a memory.
			int choice = javax.swing.JOptionPane.showConfirmDialog(this,
				"Delete outfit profile \"" + name + "\"? There is no undo.",
				"Delete profile", javax.swing.JOptionPane.YES_NO_OPTION);
			if (choice == javax.swing.JOptionPane.YES_OPTION)
			{
				onProfileDelete.accept(name);
			}
		});
		profileButtons.add(profileDelete);
		body.add(profileButtons);
		body.add(buildThrallRow());

		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		status.setAlignmentX(LEFT_ALIGNMENT);
		status.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		body.add(status);

		body.add(sectionHeader("EQUIPMENT", null));
		body.add(caption("Click a slot to change it; right-click to clear it."));
		body.add(buildGear());
		body.add(sectionHeader("BODY", "Styles cycle live on the follower; colours are the game's own"));
		// BoxLayout, NOT GridLayout: a grid splits its height evenly between
		// rows and ignores their maximum size, so every row grew as the
		// window did. A vertical box honours the fixed row height.
		bodyList.setLayout(new BoxLayout(bodyList, BoxLayout.Y_AXIS));
		bodyList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bodyList.setAlignmentX(LEFT_ALIGNMENT);
		body.add(bodyList);
	}

	/**
	 * Which profile each thrall wears, folded under the profiles row: three
	 * dropdowns over the same names, writing the three thrall settings.
	 */
	private JPanel buildThrallRow()
	{
		JPanel wrap = new JPanel();
		wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.setAlignmentX(LEFT_ALIGNMENT);

		JPanel rows = new JPanel();
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setBackground(ColorScheme.DARK_GRAY_COLOR);
		rows.setAlignmentX(LEFT_ALIGNMENT);
		rows.setVisible(false);

		JLabel toggle = new JLabel("+ Used for thralls");
		toggle.setFont(FontManager.getRunescapeSmallFont());
		toggle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		toggle.setToolTipText("Which profile stands in for each thrall. Same"
			+ " three settings as in Thrall mode.");
		toggle.setAlignmentX(LEFT_ALIGNMENT);
		toggle.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));
		clickable(toggle, () ->
		{
			rows.setVisible(!rows.isVisible());
			toggle.setText((rows.isVisible() ? "- " : "+ ") + "Used for thralls");
			column.revalidate();
			column.repaint();
		});
		wrap.add(toggle);

		String[][] styles = {{"melee", "Melee"}, {"ranged", "Ranged"}, {"magic", "Magic"}};
		for (String[] style : styles)
		{
			javax.swing.JComboBox<String> box = new javax.swing.JComboBox<>();
			box.addActionListener(e ->
			{
				Object picked = box.getSelectedItem();
				if (!suppressSyncEvents && picked != null)
				{
					onThrallProfile.accept(style[0], picked.toString());
				}
			});
			thrallBoxes.put(style[0], box);
			rows.add(labelledRow(style[1], box));
			rows.add(javax.swing.Box.createVerticalStrut(2));
		}
		wrap.add(rows);
		return wrap;
	}

	private void buildBehaviour(JPanel body)
	{
		JPanel buttons = buttonPair();
		stayButton.setToolTipText("Same as right-click Stay and Follow on the follower,"
			+ " and Follow me in settings");
		stayButton.setFocusPainted(false);
		stayButton.addActionListener(e -> onFollowEnabled.accept(staying));
		buttons.add(stayButton);
		buttons.add(button("Come here", "Places the follower at your feet",
			() -> onComeHere.run()));
		body.add(buttons);

		behaviourCaption.setFont(FontManager.getRunescapeSmallFont());
		behaviourCaption.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		behaviourCaption.setAlignmentX(LEFT_ALIGNMENT);
		behaviourCaption.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
		body.add(behaviourCaption);

		styleTick(errandsBox, "Now and then it wanders off on a small errand and"
			+ " comes back. Same switch as Go on errands in settings.");
		errandsBox.addActionListener(e ->
		{
			if (!suppressSyncEvents)
			{
				onErrandsEnabled.accept(errandsBox.isSelected());
			}
		});
		body.add(errandsBox);

		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		row.add(errandBox, BorderLayout.CENTER);
		row.add(button("Go now", "Runs the chosen errand at the next quiet moment", () ->
		{
			int index = errandBox.getSelectedIndex();
			if (index >= 0 && index < errandKeys.size())
			{
				onErrand.accept(errandKeys.get(index));
			}
		}), BorderLayout.EAST);
		body.add(row);
	}

	private void buildCommands(JPanel body)
	{
		JPanel buttons = buttonColumn(3);
		buttons.add(button("Bring it here", "Places the follower at your feet",
			() -> onComeHere.run()));
		buttons.add(button("Fix if it vanished",
			"Re-attaches the follower to the scene, then rebuilds it from the outfit",
			() -> onFix.run()));
		buttons.add(button("Reload phrase and sound files",
			"Re-reads phrases.json and the attached sounds from disk",
			() -> onReload.run()));
		body.add(buttons);
		body.add(javax.swing.Box.createVerticalStrut(6));

		// Wrapped, so the longest command folds rather than widening the
		// column; the maximum height is left open so every line shows.
		commandList.setEditable(false);
		commandList.setLineWrap(true);
		commandList.setWrapStyleWord(true);
		commandList.setFont(FontManager.getRunescapeSmallFont());
		commandList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		commandList.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		commandList.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		commandList.setAlignmentX(LEFT_ALIGNMENT);
		commandList.setToolTipText("Type any of these in the chat as ::follower <command>");
		body.add(commandList);

		regionCaption.setFont(FontManager.getRunescapeSmallFont());
		regionCaption.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		regionCaption.setAlignmentX(LEFT_ALIGNMENT);
		regionCaption.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		body.add(regionCaption);
		setRegionId(-1);
	}

	/** The equipment grid and the item picker under it. */
	private JPanel buildGear()
	{
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setAlignmentX(LEFT_ALIGNMENT);

		gearGrid.setLayout(new GridLayout(GEAR_GRID.length, 3, 4, 4));
		gearGrid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		gearGrid.setMaximumSize(new Dimension((CELL + 4) * 3, (CELL + 4) * GEAR_GRID.length));

		JPanel gridWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		gridWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		gridWrap.setAlignmentX(LEFT_ALIGNMENT);
		gridWrap.add(gearGrid);
		content.add(gridWrap);

		picker.setLayout(new BoxLayout(picker, BoxLayout.Y_AXIS));
		picker.setBackground(ColorScheme.DARK_GRAY_COLOR);
		picker.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		picker.setAlignmentX(LEFT_ALIGNMENT);

		pickerTitle.setFont(FontManager.getRunescapeSmallFont());
		pickerTitle.setForeground(Color.WHITE);
		pickerTitle.setAlignmentX(LEFT_ALIGNMENT);
		pickerTitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		picker.add(pickerTitle);

		search.setToolTipText("Type to filter items for this slot");
		search.setAlignmentX(LEFT_ALIGNMENT);
		search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			public void insertUpdate(DocumentEvent e)
			{
				refreshResults();
			}

			public void removeUpdate(DocumentEvent e)
			{
				refreshResults();
			}

			public void changedUpdate(DocumentEvent e)
			{
				refreshResults();
			}
		});
		picker.add(search);

		showAll.setText("Show items from every slot");
		showAll.setToolTipText("Some wearable items (mostly quest and holiday ones)"
			+ " don't say which slot they belong to. Tick to include them in every search.");
		showAll.setFont(FontManager.getRunescapeSmallFont());
		showAll.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		showAll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		showAll.setAlignmentX(LEFT_ALIGNMENT);
		showAll.addActionListener(e -> refreshResults());
		picker.add(showAll);

		picker.add(buildPreview());

		resultList.setLayout(new GridLayout(0, 1, 0, 2));
		resultList.setBackground(ColorScheme.DARK_GRAY_COLOR);

		resultScroll = new JScrollPane(resultList,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		resultScroll.setBorder(BorderFactory.createEmptyBorder());
		// Zero preferred WIDTH: a fixed one (this used to say PANEL_WIDTH)
		// out-demanded the sidebar and shoved the whole column off its right
		// edge. The height is the fixed part; the width is the container's.
		resultScroll.setPreferredSize(new Dimension(0, 240));
		resultScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 240));
		resultScroll.setAlignmentX(LEFT_ALIGNMENT);
		resultScroll.getVerticalScrollBar().setUnitIncrement(16);
		// The rest of the catalogue arrives as the list is scrolled toward
		// its end - every match is reachable, none is paid for up front.
		resultScroll.getVerticalScrollBar().addAdjustmentListener(e ->
		{
			javax.swing.JScrollBar bar = resultScroll.getVerticalScrollBar();
			if (!pendingResults.isEmpty()
				&& bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 64)
			{
				appendResults(RESULT_CHUNK);
			}
		});
		picker.add(resultScroll);

		content.add(picker);

		return content;
	}

	/**
	 * A panel whose scroll view always matches the viewport's width, whatever
	 * the viewport happens to be once the vertical scrollbar takes its share.
	 * Content adapts or wraps; it never slides out past the right edge.
	 */
	private static final class ScrollableColumn extends JPanel
		implements javax.swing.Scrollable
	{
		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction)
		{
			return visible.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	/** A sub-header inside a section; the same look as a section header, but nothing folds. */
	private JPanel sectionHeader(String text, String tooltip)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setToolTipText(tooltip);
		return headerRow(label);
	}

	/** The label on the left with a thin rule filling the rest of the line. */
	private JPanel headerRow(JLabel label)
	{
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.setAlignmentX(LEFT_ALIGNMENT);
		wrap.setBorder(BorderFactory.createEmptyBorder(10, 0, 4, 0));
		wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		wrap.add(label, BorderLayout.WEST);

		JPanel rule = new JPanel();
		rule.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		rule.setPreferredSize(new Dimension(10, 1));
		JPanel ruleWrap = new JPanel(new BorderLayout());
		ruleWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		ruleWrap.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
		ruleWrap.add(rule, BorderLayout.CENTER);
		wrap.add(ruleWrap, BorderLayout.CENTER);

		return wrap;
	}

	/** Hover preview: a scaled-up sprite so you can see the item before equipping. */
	private JPanel buildPreview()
	{
		JPanel wrapper = new JPanel(new BorderLayout(6, 0));
		wrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrapper.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		wrapper.setAlignmentX(LEFT_ALIGNMENT);
		wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 116));

		previewIcon.setHorizontalAlignment(SwingConstants.CENTER);
		previewIcon.setVerticalAlignment(SwingConstants.CENTER);
		previewIcon.setPreferredSize(new Dimension(36 * PREVIEW_SCALE, 32 * PREVIEW_SCALE));
		wrapper.add(previewIcon, BorderLayout.WEST);

		previewName.setFont(FontManager.getRunescapeSmallFont());
		previewName.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		previewName.setVerticalAlignment(SwingConstants.TOP);
		// Fixed size: an HTML label sizes itself to its longest line, so a
		// long item name would widen this label, then the wrapper, then the
		// whole sidebar column - the layout visibly jumping on hover.
		previewName.setPreferredSize(new Dimension(PREVIEW_TEXT_WIDTH, 32 * PREVIEW_SCALE));
		previewName.setMaximumSize(new Dimension(PREVIEW_TEXT_WIDTH, 32 * PREVIEW_SCALE));
		wrapper.add(previewName, BorderLayout.CENTER);

		return wrapper;
	}

	/** Width the preview's name column is held to, so long names wrap instead of stretch. */
	private static final int PREVIEW_TEXT_WIDTH = 68;

	// -------------------------------------------------------------- plugin hooks

	public void setOutfit(Outfit outfit)
	{
		SwingUtilities.invokeLater(() ->
		{
			current = new Outfit(outfit);
			rebuildSlots();
		});
	}

	public void setStatus(String text)
	{
		SwingUtilities.invokeLater(() -> status.setText(text));
	}

	/** Called once the plugin has worked out which slot each item belongs to. */
	public void setSlotIndex(Map<Integer, KitType> index)
	{
		SwingUtilities.invokeLater(() ->
		{
			slotIndex = index;
			refreshResults();
		});
	}

	// ---------------------------------------------------------------- gear grid

	private void rebuildSlots()
	{
		gearGrid.removeAll();
		for (KitType[] row : GEAR_GRID)
		{
			for (KitType slot : row)
			{
				gearGrid.add(slot == null ? spacer() : buildSlotCell(slot));
			}
		}
		gearGrid.revalidate();
		gearGrid.repaint();

		bodyList.removeAll();
		bodyList.add(buildGenderRow());
		bodyList.add(javax.swing.Box.createVerticalStrut(2));
		bodyList.add(buildSkinRow());
		for (KitType part : BODY_PARTS)
		{
			bodyList.add(javax.swing.Box.createVerticalStrut(2));
			bodyList.add(buildBodyRow(part));
		}
		bodyList.revalidate();
		bodyList.repaint();
	}

	private JPanel spacer()
	{
		JPanel s = new JPanel();
		s.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return s;
	}

	private JPanel buildSlotCell(KitType slot)
	{
		boolean isActive = slot == activeSlot;
		boolean hasItem = current.isItem(slot);

		JPanel cell = new JPanel(new BorderLayout());
		cell.setPreferredSize(new Dimension(CELL, CELL));
		cell.setBackground(isActive ? ColorScheme.DARK_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
		cell.setBorder(isActive
			? BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE, 1)
			: BorderFactory.createLineBorder(ColorScheme.DARK_GRAY_COLOR, 1));
		cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		String slotName = slot.name().toLowerCase();
		if (hasItem)
		{
			int id = current.itemId(slot);
			String itemName = repository.itemName(id);
			cell.setToolTipText((itemName == null ? "item " + id : itemName)
				+ " - click to change, right-click to clear");

			JLabel icon = new JLabel();
			icon.setHorizontalAlignment(SwingConstants.CENTER);
			AsyncBufferedImage image = itemManager.getImage(id);
			if (image != null)
			{
				image.addTo(icon);
			}
			cell.add(icon, BorderLayout.CENTER);
		}
		else
		{
			cell.setToolTipText("Empty " + slotName + " slot - click to choose an item");

			JLabel empty = new JLabel(slotName);
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			empty.setHorizontalAlignment(SwingConstants.CENTER);
			cell.add(empty, BorderLayout.CENTER);
		}

		cell.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isRightMouseButton(e))
				{
					onClear.accept(slot);
					return;
				}
				showPicker(slot == activeSlot ? null : slot);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (slot != activeSlot)
				{
					cell.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (slot != activeSlot)
				{
					cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				}
			}
		});

		return cell;
	}

	// --------------------------------------------------------------------- body

	private JPanel buildGenderRow()
	{
		JPanel row = bodyRowShell();

		JLabel name = new JLabel("body");
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		name.setPreferredSize(new Dimension(NAME_WIDTH, ROW_CONTENT_HEIGHT));
		row.add(name, BorderLayout.WEST);

		JPanel toggle = new JPanel(new GridLayout(1, 2, 2, 0));
		toggle.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		toggle.add(genderButton("male", 0));
		toggle.add(genderButton("female", 1));
		row.add(toggle, BorderLayout.CENTER);

		return row;
	}

	private JButton genderButton(String label, int gender)
	{
		boolean selected = current.getGender() == gender;
		JButton button = new JButton(label);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setFocusPainted(false);
		button.setForeground(selected ? Color.WHITE : ColorScheme.MEDIUM_GRAY_COLOR);
		if (selected)
		{
			button.setBackground(ColorScheme.MEDIUM_GRAY_COLOR.darker());
		}
		button.setToolTipText("Switches the whole body model, and the default kits with it");
		button.addActionListener(e ->
		{
			if (!selected)
			{
				onGender.accept(gender);
			}
		});
		return button;
	}

	/**
	 * Skin is its own control rather than part of any one slot: the same skin
	 * colours appear across the head, arms and hands, so recolouring them together
	 * is the only way to keep a body consistent.
	 */
	private JPanel buildSkinRow()
	{
		JPanel row = bodyRowShell();

		JLabel name = new JLabel("skin");
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		name.setPreferredSize(new Dimension(NAME_WIDTH, ROW_CONTENT_HEIGHT));
		row.add(name, BorderLayout.WEST);

		row.add(swatch(4, "skin", GamePalette.SKIN), BorderLayout.EAST);
		return row;
	}

	/** Label column width; the style label takes whatever is left over. */
	private static final int NAME_WIDTH = 52;
	private static final int ROW_CONTENT_HEIGHT = 20;
	private static final int ROW_HEIGHT = ROW_CONTENT_HEIGHT + 6;

	/**
	 * A body-part row: fixed height so the vertical BoxLayout cannot stretch
	 * it (rows doubled in height on a maximised window), and never wider than
	 * the panel so the scrollbar cannot clip the colour swatch off the right.
	 */
	private JPanel bodyRowShell()
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
		row.setMinimumSize(new Dimension(0, ROW_HEIGHT));
		row.setPreferredSize(new Dimension(0, ROW_HEIGHT));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
		row.setAlignmentX(LEFT_ALIGNMENT);
		return row;
	}

	/** One body part: cycle its kit, and set its colour. */
	private JPanel buildBodyRow(KitType part)
	{
		JPanel row = bodyRowShell();

		JLabel name = new JLabel(part.name().toLowerCase());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		name.setPreferredSize(new Dimension(NAME_WIDTH, ROW_CONTENT_HEIGHT));
		row.add(name, BorderLayout.WEST);

		// Kits have no names in the cache, so they're cycled and judged by eye - the
		// follower updates live, which makes it its own preview.
		//
		// BorderLayout rather than a flow: the arrows keep their size at the
		// edges and the label in the middle absorbs whatever width is left, so
		// a narrow sidebar squeezes the text instead of pushing the swatch out.
		JPanel cycle = new JPanel(new BorderLayout(2, 0));
		cycle.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JButton prev = new JButton("‹");
		prev.setToolTipText("Previous style");
		prev.setFocusPainted(false);
		prev.setMargin(new java.awt.Insets(0, 2, 0, 2));
		prev.setPreferredSize(new Dimension(18, ROW_CONTENT_HEIGHT));
		prev.addActionListener(e -> onCycleKit.accept(part, -1));
		cycle.add(prev, BorderLayout.WEST);

		java.util.List<Integer> choices = repository.kitsFor(part, current.getGender());
		int index = current.isKit(part) ? choices.indexOf(current.kitId(part)) : -1;
		String label = KitNames.label(part, index, choices.size());

		JLabel style = new JLabel(label);
		style.setFont(FontManager.getRunescapeSmallFont());
		style.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		style.setToolTipText(label);
		style.setPreferredSize(new Dimension(0, ROW_CONTENT_HEIGHT));
		style.setHorizontalAlignment(SwingConstants.CENTER);
		cycle.add(style, BorderLayout.CENTER);

		JButton next = new JButton("›");
		next.setToolTipText("Next style");
		next.setFocusPainted(false);
		next.setMargin(new java.awt.Insets(0, 2, 0, 2));
		next.setPreferredSize(new Dimension(18, ROW_CONTENT_HEIGHT));
		next.addActionListener(e -> onCycleKit.accept(part, 1));
		cycle.add(next, BorderLayout.EAST);

		row.add(cycle, BorderLayout.CENTER);

		// Colours come from the game's own palette tables - every choice here is a
		// colour the game can genuinely produce, applied exactly as the client does.
		// Jaw follows hair, arms follow torso, hands follow skin, as in the game.
		if (part == KitType.HAIR || part == KitType.TORSO
			|| part == KitType.LEGS || part == KitType.BOOTS)
		{
			int colorSlot = colorSlotFor(part);
			row.add(swatch(colorSlot, part.name().toLowerCase(),
				GamePalette.table(colorSlot)), BorderLayout.EAST);
		}

		return row;
	}

	/** A clickable colour square showing the current palette choice. */
	private JPanel swatch(int colorSlot, String title, short[] table)
	{
		JPanel square = new JPanel();
		square.setPreferredSize(new Dimension(20, 20));
		square.setBackground(new Color(HslColor.toRgb(table[paletteIndex(colorSlot)])));
		square.setBorder(BorderFactory.createLineBorder(ColorScheme.DARK_GRAY_COLOR, 1));
		square.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		square.setToolTipText(title + " colour - the game's " + table.length + " exact choices");
		square.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				Integer chosen = pickPaletteIndex(title, table);
				if (chosen != null)
				{
					onBodyColor.accept(colorSlot, chosen);
				}
			}
		});

		JPanel wrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		wrap.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrap.add(square);
		return wrap;
	}

	/** Current palette index for a body-colour slot, bounds-safe. */
	private int paletteIndex(int colorSlot)
	{
		int[] colors = current.getColors();
		int index = colorSlot < colors.length ? colors[colorSlot] : 0;
		short[] table = GamePalette.table(colorSlot);
		return table != null && index >= 0 && index < table.length ? index : 0;
	}

	/** Body-colour slot a part's colour comes from, or -1 if skin covers it. */
	private static int colorSlotFor(KitType part)
	{
		switch (part)
		{
			case HAIR:
			case JAW:
				return 0;
			case TORSO:
			case ARMS:
				return 1;
			case LEGS:
				return 2;
			case BOOTS:
				return 3;
			default:
				return -1;
		}
	}

	private void showPicker(KitType slot)
	{
		activeSlot = slot;
		boolean open = slot != null;

		picker.setVisible(open);
		if (open)
		{
			pickerTitle.setText("Choose " + slot.name().toLowerCase()
				+ (current.isItem(slot) ? "  (right-click the slot to clear)" : ""));
			search.setText("");
			clearPreview();
			refreshResults();
			search.requestFocusInWindow();
		}

		rebuildSlots();
		revalidate();
		repaint();
	}

	// ------------------------------------------------------------------ results

	/**
	 * Swatch grid over one of the game's palette tables; returns the chosen INDEX.
	 *
	 * <p>The swatch is painted with the RGB of the packed table colour, but only the
	 * index is stored - the compose path replays the client's own find/replace with
	 * it, so what renders is the game's colour, not the swatch approximation.
	 */
	private Integer pickPaletteIndex(String title, short[] table)
	{
		JPanel grid = new JPanel(new GridLayout(0, 8, 3, 3));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final Integer[] picked = {null};
		final javax.swing.JDialog[] dialog = {null};

		for (int i = 0; i < table.length; i++)
		{
			int rgb = HslColor.toRgb(table[i]);
			final int index = i;

			JButton swatch = new JButton();
			swatch.setBackground(new Color(rgb));
			swatch.setOpaque(true);
			swatch.setBorderPainted(false);
			swatch.setPreferredSize(new Dimension(26, 26));
			swatch.setToolTipText(index + ": #" + String.format("%06X", rgb));
			swatch.addActionListener(e ->
			{
				picked[0] = index;
				if (dialog[0] != null)
				{
					dialog[0].dispose();
				}
			});
			grid.add(swatch);
		}

		javax.swing.JOptionPane pane = new javax.swing.JOptionPane(grid,
			javax.swing.JOptionPane.PLAIN_MESSAGE,
			javax.swing.JOptionPane.DEFAULT_OPTION, null, new Object[]{}, null);
		dialog[0] = pane.createDialog(this, title + " colour");
		dialog[0].setVisible(true);
		dialog[0].dispose();

		return picked[0];
	}

	private void refreshResults()
	{
		resultList.removeAll();
		pendingResults.clear();

		if (activeSlot == null)
		{
			resultList.revalidate();
			resultList.repaint();
			return;
		}

		// The cache-dumper detail belongs in the log, not in front of a user
		// who has never heard of the build tooling.
		if (!repository.isLoaded() || !repository.hasNames())
		{
			resultList.add(hint("<html><body style='width:180px'>Item data didn't"
				+ " load - try restarting the client. If it keeps happening,"
				+ " report a bug.</body></html>"));
		}
		else
		{
			// EVERY match, not a page of them: rows are built lazily as the
			// list scrolls (see the adjustment listener on resultScroll), so
			// the full catalogue is reachable without being paid for up front.
			// The limit is a formality search() multiplies internally - large,
			// but nowhere near where that multiply would overflow.
			List<ModelRepository.WearableItem> matches =
				repository.search(search.getText(), 100_000);

			boolean filter = !slotIndex.isEmpty() && !showAll.isSelected();
			for (ModelRepository.WearableItem item : matches)
			{
				// Only offer items that belong in this slot. Until the index is built
				// everything is offered, so the picker still works immediately.
				if (filter && slotIndex.get(item.id) != activeSlot)
				{
					continue;
				}
				pendingResults.addLast(item);
			}

			if (pendingResults.isEmpty())
			{
				if (slotIndex.isEmpty())
				{
					resultList.add(hint("Indexing items, try again in a moment..."));
				}
				else
				{
					resultList.add(hint("No " + activeSlot.name().toLowerCase()
						+ " items match - try \"Show items from every slot\""));
				}
			}
			else
			{
				appendResults(RESULT_CHUNK);
			}
		}

		resultList.revalidate();
		resultList.repaint();
	}

	/** Builds the next slice of pending matches into rows. */
	private void appendResults(int count)
	{
		for (int i = 0; i < count && !pendingResults.isEmpty(); i++)
		{
			resultList.add(buildResultRow(pendingResults.pollFirst()));
		}
		resultList.revalidate();
		resultList.repaint();
	}

	private JPanel buildResultRow(ModelRepository.WearableItem item)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
		row.setToolTipText(item.name + " - click to equip (id " + item.id + ")");
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		// Never taller or wider than a row needs: the list lives in a vertical
		// box, which would otherwise stretch rows to fill and let a long name
		// widen the whole column.
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		AsyncBufferedImage image = itemManager.getImage(item.id);
		if (image != null)
		{
			image.addTo(icon);
		}
		row.add(icon, BorderLayout.WEST);

		JLabel name = new JLabel(item.name);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);
		// Zero preferred width: BorderLayout's centre takes whatever is left
		// over at layout time, and the label ellipsises rather than demanding
		// room for the full name. The tooltip carries the untruncated text.
		name.setPreferredSize(new Dimension(0, 20));
		row.add(name, BorderLayout.CENTER);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onEquip.accept(item.id, activeSlot);
				showPicker(null);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
				showPreview(item);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});

		return row;
	}

	// ------------------------------------------------------------------ preview

	private void showPreview(ModelRepository.WearableItem item)
	{
		// The body width is what makes long names WRAP; without it the label
		// grows sideways and drags the sidebar's layout with it.
		previewName.setText("<html><body style='width:" + (PREVIEW_TEXT_WIDTH - 4) + "px'><b>"
			+ escapeHtml(item.name) + "</b><br>id " + item.id + "</body></html>");

		AsyncBufferedImage image = itemManager.getImage(item.id);
		if (image == null)
		{
			previewIcon.setIcon(null);
			return;
		}

		// The sprite may not have arrived yet; scale it once it has.
		image.onLoaded(() -> SwingUtilities.invokeLater(() ->
		{
			Image scaled = image.getScaledInstance(
				image.getWidth() * PREVIEW_SCALE,
				image.getHeight() * PREVIEW_SCALE,
				Image.SCALE_SMOOTH);
			previewIcon.setIcon(new ImageIcon(scaled));
		}));
	}

	private void clearPreview()
	{
		previewIcon.setIcon(null);
		previewName.setText("Hover an item to preview it");
	}

	private JLabel hint(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		return label;
	}

	/** Item names are cache text; they must not be able to inject markup. */
	private static String escapeHtml(String text)
	{
		return text == null ? "" : text
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;");
	}
}

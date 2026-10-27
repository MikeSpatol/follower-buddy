package com.follower;

import java.awt.*;
import java.awt.Color;
import java.awt.Image;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.Map;
import java.util.function.*;
import java.util.function.Consumer;
import javax.swing.*;
import net.runelite.api.kit.KitType;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.*;
import net.runelite.client.util.AsyncBufferedImage;
import lombok.*;
import java.awt.Component;
import java.util.Set;
import javax.swing.Box;
import static javax.swing.BorderFactory.*;
import static javax.swing.JOptionPane.*;
import static javax.swing.SwingUtilities.*;
import static net.runelite.api.kit.KitType.*;
import static net.runelite.client.ui.ColorScheme.*;

/**
 * Outfit picker.
 *
 * <p>Gear is laid out as the in-game equipment tab - an icon grid in the familiar
 * cross shape - because that is a layout every player already knows how to read.
 * Click a slot to search items for it; right-click to clear it. Body styles and
 * colours sit below, with colours as real swatches over the game's own palettes.
 */
class FollowerPanel extends PluginPanel
{
	static final int PREVIEW_SCALE = 3,
		CELL = 44;

	/** The equipment-tab shape; null cells are spacers. */
	static final KitType[][] GEAR_GRID = {
		{null, HEAD, null},
		{CAPE, AMULET, null},
		{WEAPON, TORSO, SHIELD},
		{null, LEGS, null},
		{HANDS, null, BOOTS},
	};

	final ItemManager itemManager;
	final ModelRepository repository;

	final BiConsumer<Integer, KitType> onEquip;
	final Consumer<KitType> onClear;
	final Runnable onCopyGear,
		onClearAll;
	final IntConsumer onGender;
	final BiConsumer<KitType, Integer> onCycleKit;

	/** (body colour slot 0-4, palette index) - the exact-palette picker. */
	final BiConsumer<Integer, Integer> onBodyColor;

	/** Opens the message editor window; set by the plugin after construction. */
	@Setter
	Runnable onEditMessages = () -> { };

	// ------------------------------------------------------------------- mute

	/**
	 * The same Mute as the one in settings, surfaced where people actually
	 * are. Both write the one config key, so they can never disagree.
	 */
	final JCheckBox muteBox = new JCheckBox("Mute the follower");

	/** Opens the memory window (R20); set by the plugin after construction. */
	@Setter
	Runnable onShowMemory = () -> { };

	// -------------------------------------------------------- synced settings

	/**
	 * Chattiness, the chatbox mirror and errands follow the Mute pattern: the
	 * same key as settings, written through the plugin, and reflected back
	 * without an echo when the setting changes from the other side. The
	 * thrall profiles and the open sections are written the same way.
	 */
	final JComboBox<Chattiness> chattinessBox =
		new JComboBox<>(Chattiness.values());
	final JCheckBox chatboxBox =
		new JCheckBox("Also show lines in the chatbox");
	final JCheckBox errandsBox = new JCheckBox("Go on errands");

	/** (config key, value) as the player changes a synced control; set by the plugin. */
	@Setter
	BiConsumer<String, Object> onSetting = (key, value) -> { };

	/** The synced ticks and dropdown, by their config key. */
	final Map<String, JComponent> synced = new java.util.HashMap<>();

	/** Set while a synced control is being set FROM config, so it does not echo. */
	boolean suppressSyncEvents;

	/**
	 * Reflects a config-side change without writing it back again: a tick
	 * takes a Boolean, the dropdown its item. On the event thread like its
	 * neighbours: a profile switch sends this from RuneLite's executor (QA3).
	 */
	void setSetting(String key, Object value)
	{
		synced(() ->
		{
			var control = synced.get(key);
			if (control instanceof JCheckBox)
			{
				((JCheckBox) control).setSelected((Boolean) value);
			}
			else
			{
				((JComboBox<?>) control).setSelectedItem(value);
			}
		});
	}

	/** Writes a synced control's new value, unless config is the one setting it. */
	void write(String key, Object value)
	{
		if (!suppressSyncEvents && value != null)
			onSetting.accept(key, value);
	}

	/** A synced tick, styled like the others, writing its config key when clicked. */
	JCheckBox tick(String key, JCheckBox box, String tooltip)
	{
		styleTick(box, tooltip);
		synced.put(key, box);
		box.addActionListener(e -> write(key, box.isSelected()));
		return box;
	}

	/** Applies a config-side value on the event thread with the echo guard up. */
	void synced(Runnable update)
	{
		invokeLater(() ->
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

	final JButton stayButton = new JButton("Stay here");
	boolean staying;
	@Setter
	Consumer<Boolean> onFollowEnabled = f -> { };
	@Setter
	Runnable onComeHere = () -> { };
	final JLabel behaviourCaption = new JLabel();
	final JComboBox<String> errandBox = new JComboBox<>();
	List<String> errandKeys = Collections.emptyList();
	@Setter
	Consumer<String> onErrand = k -> { };

	/** Reflects the player-commanded stay; the button always offers the other state. */
	void setStaying(boolean staying)
	{
		invokeLater(() ->
		{
			this.staying = staying;
			stayButton.setText(staying ? "Follow me" : "Stay here");
		});
	}

	void setFollowerName(String name)
	{
		invokeLater(() -> behaviourCaption.setText(Ui.html(160, "Right-click " + name
			+ Resources.text("behaviourHint"))));
	}

	/** The errands on offer, key to display name, in the order to list them. */
	void setErrands(Map<String, String> keyToLabel)
	{
		invokeLater(() ->
		{
			errandKeys = new ArrayList<>(keyToLabel.keySet());
			refill(errandBox, keyToLabel.values());
		});
	}

	// ---------------------------------------------------------------- commands

	@Setter
	Runnable onFix = () -> { };
	@Setter
	Runnable onReload = () -> { };
	final JTextArea commandList = new JTextArea();
	final JLabel regionCaption = new JLabel();

	/** The command listing, one line each; the plugin decides what goes in. */
	void setCommands(List<String> lines)
	{
		invokeLater(() ->
		{
			commandList.setText(String.join("\n", lines));
			commandList.setCaretPosition(0);
		});
	}

	/** The region the player stands in, or below zero before the first tick. */
	void setRegionId(int region)
	{
		invokeLater(() -> regionCaption.setText(Ui.html(160, "Your region id: "
			+ (region > 0 ? Integer.toString(region) : "not known until you log in")
			+ ", for Locations rules.")));
	}

	// ------------------------------------------------------------------- folds

	/** One foldable section: its header label carries the +/- prefix. */
	@RequiredArgsConstructor
	static class Section
	{
		final String title;
		final JLabel label;
		final JPanel body;
	}

	final Map<String, Section> sections = new LinkedHashMap<>();
	final Set<String> openSections;

	/** The whole scrolling column, revalidated as sections open and close. */
	final JPanel column = Ui.column(new Ui.WidthTrackingPanel());

	/** The stored fold list, "talking,outfit", as the set of open section keys. */
	static Set<String> parseFolds(String stored)
	{
		Set<String> open = new java.util.LinkedHashSet<>();
		if (stored == null)
			return open;
		for (String key : stored.split(","))
		{
			String trimmed = key.trim().toLowerCase(java.util.Locale.ROOT);
			if (!trimmed.isEmpty())
				open.add(trimmed);
		}
		return open;
	}

	// ---------------------------------------------------------------- profiles

	final JComboBox<String> profileCombo = new JComboBox<>();
	final JButton profileDelete = new JButton("Delete");

	@Setter
	Consumer<String> onProfileLoad = s -> { };
	@Setter
	Consumer<String> onProfileSave = s -> { };
	@Setter
	Consumer<String> onProfileDelete = s -> { };

	/** Whether a profile is one of the seeded thrall looks; set by the plugin. */
	@Setter
	java.util.function.Predicate<String> profileProtected = n -> false;

	/** Set while the dropdown is being repopulated, so selection events are ignored. */
	boolean suppressProfileEvents;

	List<String> profileNames = Collections.emptyList();

	/**
	 * Refreshes the dropdown and selects {@code active}. Selection events are
	 * suppressed throughout: repopulating must not read as the user picking a
	 * profile, which would re-load the outfit mid-edit. The thrall dropdowns
	 * are filled from the same names.
	 *
	 * <p>On the event thread, like the other setters: a profile switch calls
	 * this from the client thread while the thrall settings refill the same
	 * dropdowns on the event thread (QA3).
	 */
	void setProfileNames(List<String> names, String active)
	{
		List<String> copy = new ArrayList<>(names);
		invokeLater(() ->
		{
			profileNames = copy;
			suppressProfileEvents = true;
			try
			{
				refill(profileCombo, copy);
				profileCombo.setSelectedItem(active == null ? null : active);
			}
			finally
			{
				suppressProfileEvents = false;
			}
			fillThrallBoxes();
			refreshDeleteButton();
		});
	}

	String profileName()
	{
		Object item = profileCombo.getSelectedItem();
		return item == null ? "" : item.toString().trim();
	}

	/**
	 * New profile... with the name typed. A name already in the list is
	 * written over only when the player says so (QA3: it was replaced without
	 * a word). The thrall looks are not asked about: the plugin refuses them.
	 */
	void newProfile(String typed)
	{
		if (typed == null || typed.trim().isEmpty())
			return;
		String name = typed.trim();
		if (!profileProtected.test(name) && profileNames.contains(name) && !confirmReplace(name))
			return;
		onProfileSave.accept(name);
	}

	/** Whether to write the outfit worn now over the profile of that name. */
	boolean confirmReplace(String name)
	{
		return showConfirmDialog(this,
			"Replace the outfit profile \"" + name + "\" with what the follower is wearing now?",
			"New profile", YES_NO_OPTION) == YES_OPTION;
	}

	// ----------------------------------------------------------------- thralls

	/** Style key to its dropdown, in the order the rows are shown. */
	final Map<String, JComboBox<String>> thrallBoxes =
		new LinkedHashMap<>();

	/** Style key to the profile name the setting holds. */
	final Map<String, String> thrallProfiles = new LinkedHashMap<>();

	/** Reflects the three thrall settings without writing them back. */
	void setThrallProfiles(String melee, String ranged, String magic)
	{
		invokeLater(() ->
		{
			thrallProfiles.put("melee", melee == null ? "" : melee.trim());
			thrallProfiles.put("ranged", ranged == null ? "" : ranged.trim());
			thrallProfiles.put("magic", magic == null ? "" : magic.trim());
			fillThrallBoxes();
			refreshDeleteButton();
			profileCombo.repaint();
		});
	}

	void fillThrallBoxes()
	{
		suppressSyncEvents = true;
		try
		{
			for (var entry : thrallBoxes.entrySet())
			{
				var box = entry.getValue();
				refill(box, profileNames);
				String wanted = thrallProfiles.get(entry.getKey());
				if (wanted != null && !wanted.isEmpty())
				{
					// A typed name nothing is saved under still shows, as
					// typed, rather than the dropdown quietly claiming another.
					if (!profileNames.contains(wanted))
						box.addItem(wanted);
					box.setSelectedItem(wanted);
				}
				else
					box.setSelectedItem(null);
			}
		}
		finally
		{
			suppressSyncEvents = false;
		}
	}

	/** " (thrall: melee)" and so on, or nothing, for the profile dropdown. */
	String thrallSuffix(String name)
	{
		List<String> styles = new ArrayList<>();
		for (var entry : thrallProfiles.entrySet())
		{
			if (entry.getValue().equals(name))
				styles.add(entry.getKey());
		}
		return styles.isEmpty() ? "" : " (thrall: " + String.join(", ", styles) + ")";
	}

	/** Delete is offered only for a profile no thrall depends on. */
	void refreshDeleteButton()
	{
		String name = profileName();
		profileDelete.setEnabled(!name.isEmpty() && !profileProtected.test(name)
			&& thrallSuffix(name).isEmpty());
	}

	final JPanel gearGrid = new JPanel(),
		bodyList = new JPanel(),
		picker = new JPanel(),
		resultList = new JPanel();

	/** Matches not yet built into rows, drained as the list scrolls down. */
	final ArrayDeque<ModelRepository.WearableItem> pendingResults =
		new ArrayDeque<>();

	/** Names more than one of the current matches share; their rows show the id. */
	Set<String> repeatedNames = Collections.emptySet();

	/** Rows built per slice - the first fill and each scroll-to-the-end. */
	static final int RESULT_CHUNK = 100;

	JScrollPane resultScroll;
	final JTextField search = new JTextField();
	final JLabel status = new JLabel(),
		pickerTitle = new JLabel();

	final JLabel previewIcon = new JLabel(),
		previewName = new JLabel();
	final JCheckBox showAll = new JCheckBox();

	Outfit current = new Outfit();
	KitType activeSlot;

	/** itemId -> slot, filled in asynchronously by the plugin. */
	Map<Integer, KitType> slotIndex = Collections.emptyMap();

	FollowerPanel(ItemManager itemManager, ModelRepository repository,
		BiConsumer<Integer, KitType> onEquip, Consumer<KitType> onClear,
		Runnable onCopyGear, Runnable onClearAll,
		IntConsumer onGender,
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
		setBackground(DARK_GRAY_COLOR);
		// The panel is taller than most sidebars and the picker grows as you
		// search, so nothing may impose a floor on the viewport height.
		setMinimumSize(new Dimension(0, 0));

		// A WidthTrackingPanel, not a plain panel: a plain view is laid out at
		// its own preferred width, so any child preferring more than the
		// sidebar (or the width left once the scrollbar appears) pushed the
		// whole column past the right edge - padding gone, buttons clipped -
		// with the horizontal scrollbar deliberately never there to save it.
		column.setBorder(createEmptyBorder(8, 8, 8, 8));
		column.add(buildHeader());
		buildTalking(section("talking", "TALKING"));
		buildMemory(section("memory", "MEMORY"));
		buildOutfit(section("outfit", "OUTFIT"));
		buildBehaviour(section("behaviour", "BEHAVIOUR"));
		buildCommands(section("commands", "COMMANDS"));

		// Everything scrolls together: the sidebar is never tall enough for
		// the dialogue editors, profiles, equipment grid and item picker at
		// once, and a clipped panel hides controls with no way to reach them.
		var scroll = Ui.scroller(column);
		scroll.setBackground(DARK_GRAY_COLOR);
		scroll.getViewport().setBackground(DARK_GRAY_COLOR);
		scroll.setMinimumSize(new Dimension(0, 0));
		add(scroll, BorderLayout.CENTER);

		showPicker(null);
	}

	// ------------------------------------------------------------------ building

	JPanel buildHeader()
	{
		var header = Ui.column();

		// The one line that says what this thing IS, before any controls do.
		// The body style width matters: an HTML label's preferred width is one
		// unwrapped line, and a single wide child forces the WHOLE panel past
		// the sidebar, clipping every section against the no-horizontal-scroll
		// viewport.
		var identity = caption(Resources.text("identityCaption"));
		identity.setBorder(createEmptyBorder(0, 0, 4, 0));
		header.add(identity);

		return header;
	}

	/**
	 * A foldable section: a clickable header and a body that shows while the
	 * key is in the open set. Both go straight into the column; the body
	 * comes back for filling.
	 */
	JPanel section(String key, String title)
	{
		var label = Ui.label("", BRAND_ORANGE);
		var header = headerRow(label);
		header.setToolTipText("Click to open or close this section");
		clickable(header, () -> toggleSection(key));

		var body = Ui.column();
		body.setVisible(openSections.contains(key));

		sections.put(key, new Section(title, label, body));
		label.setText(foldTitle(key));
		column.add(header);
		column.add(body);
		return body;
	}

	String foldTitle(String key)
	{
		return (openSections.contains(key) ? "- " : "+ ") + sections.get(key).title;
	}

	void toggleSection(String key)
	{
		if (!openSections.remove(key))
			openSections.add(key);
		var section = sections.get(key);
		section.body.setVisible(openSections.contains(key));
		section.label.setText(foldTitle(key));
		Ui.relayout(column);
		// Stored in section order, whatever order they were clicked in.
		List<String> open = new ArrayList<>();
		for (String k : sections.keySet())
		{
			if (openSections.contains(k))
				open.add(k);
		}
		onSetting.accept("panelFolds", String.join(",", open));
	}

	/**
	 * A click anywhere on the component runs the action. Swing hands a click
	 * to the deepest child under it, so the children listen too.
	 */
	static void clickable(JComponent component, Runnable action)
	{
		component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		component.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (isLeftMouseButton(e))
					action.run();
			}
		});
		for (var child : component.getComponents())
		{
			if (child instanceof JComponent)
				clickable((JComponent) child, action);
		}
	}

	/** A width-bounded grey line of explanation, so it wraps instead of widening the panel. */
	JLabel caption(String text)
	{
		var label = Ui.label(Ui.html(160, text), MEDIUM_GRAY_COLOR);
		label.setAlignmentX(LEFT_ALIGNMENT);
		label.setBorder(createEmptyBorder(2, 0, 4, 0));
		return label;
	}

	/** A full-width column of buttons. */
	JPanel buttonColumn(int count)
	{
		var buttons = Ui.panel(new GridLayout(0, 1, 6, 4));
		buttons.setAlignmentX(LEFT_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30 * count));
		return buttons;
	}

	/** Two buttons side by side. */
	JPanel buttonPair()
	{
		var buttons = Ui.panel(new GridLayout(1, 2, 6, 0));
		buttons.setAlignmentX(LEFT_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		return buttons;
	}

	JButton button(String text, String tooltip, Runnable action)
	{
		return button(new JButton(text), tooltip, action);
	}

	static JButton button(JButton button, String tooltip, Runnable action)
	{
		button.setToolTipText(tooltip);
		button.setFocusPainted(false);
		button.addActionListener(e -> action.run());
		return button;
	}

	/** Empties a dropdown and lists the items in it, in order. */
	static void refill(JComboBox<String> box, Iterable<String> items)
	{
		box.removeAllItems();
		for (String item : items)
			box.addItem(item);
	}

	void styleTick(JCheckBox box, String tooltip)
	{
		Ui.small(box, LIGHT_GRAY_COLOR).setToolTipText(tooltip);
		box.setBackground(DARK_GRAY_COLOR);
		box.setAlignmentX(LEFT_ALIGNMENT);
	}

	/** A small label on the left and a control taking the rest of the row. */
	JPanel labelledRow(String text, JComponent control)
	{
		var row = Ui.panel(new BorderLayout(6, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		var label = Ui.label(text, LIGHT_GRAY_COLOR);
		// At least the body rows' column, and wider when the name needs it:
		// QA, 15 Oct, "Chattiness" was cut to "Chattine...".
		label.setPreferredSize(new Dimension(Math.max(NAME_WIDTH, label.getPreferredSize().width),
			ROW_CONTENT_HEIGHT));
		row.add(label, BorderLayout.WEST);
		row.add(control, BorderLayout.CENTER);
		return row;
	}

	void buildTalking(JPanel body)
	{
		body.add(caption(Resources.text("talkingCaption")));

		body.add(tick("muted", muteBox, Resources.text("muteTip")));

		chattinessBox.setToolTipText(Resources.text("chattinessTip"));
		synced.put("chattiness", chattinessBox);
		chattinessBox.addActionListener(e -> write("chattiness", chattinessBox.getSelectedItem()));
		body.add(labelledRow("Chattiness", chattinessBox));

		body.add(tick("mirrorToChat", chatboxBox, Resources.text("chatboxTip")));
		body.add(Box.createVerticalStrut(4));

		// Four doors, not nine: the old grid surfaced every internal rule
		// group as its own button, a navigation decision nobody should have
		// to make cold. The categories live in a dropdown inside Messages.
		var buttons = buttonColumn(0
			+ 1
			);
		buttons.add(button("Messages...",
			Resources.text("messagesTip"),
			() -> onEditMessages.run()));
		body.add(buttons);
	}

	void buildMemory(JPanel body)
	{
		body.add(caption(Resources.text("memoryCaption")));
		var buttons = buttonColumn(1);
		buttons.add(button("Memory...",
			Resources.text("memoryTip"),
			() -> onShowMemory.run()));
		body.add(buttons);
	}

	void buildOutfit(JPanel body)
	{
		var buttons = buttonPair();
		buttons.add(button("Copy my gear",
			"Fill every slot from what you are currently wearing", () -> onCopyGear.run()));
		buttons.add(button("Clear all",
			"Strip the follower back to the default body", () -> onClearAll.run()));
		body.add(buttons);
		body.add(Box.createVerticalStrut(6));

		var profilesLabel = Ui.label("OUTFIT PROFILES", LIGHT_GRAY_COLOR);
		profilesLabel.setAlignmentX(LEFT_ALIGNMENT);
		body.add(profilesLabel);

		// Pick a profile and you are wearing it; every change from then on is
		// saved straight back to it. New profile makes one, Delete removes it.
		profileCombo.setAlignmentX(LEFT_ALIGNMENT);
		profileCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		// The item stays the bare name; only its rendering says which thrall
		// wears it, so profileName() and the plugin see the name as stored.
		profileCombo.setRenderer(new javax.swing.DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(javax.swing.JList<?> list,
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
				onProfileLoad.accept(profileName());
			refreshDeleteButton();
		});
		body.add(profileCombo);

		// The autosave behaviour used to live in a hover tooltip, which is
		// exactly where the one surprising fact about profiles should not be.
		body.add(caption("Changes save to the selected profile automatically"));

		var profileButtons = buttonPair();
		profileButtons.add(button("New profile...",
			"Save the follower's current outfit as a new profile", () ->
		{
			newProfile(showInputDialog(this,
				"Name for the new outfit profile:", "New profile",
				PLAIN_MESSAGE));
		}));

		profileButtons.add(button(profileDelete, Resources.text("deleteProfileTip"), () ->
		{
			String name = profileName();
			if (name.isEmpty())
				return;
			// The other destructive button in this plugin (Forget everything)
			// confirms; a profile deserves no less than a memory.
			int choice = showConfirmDialog(this,
				"Delete outfit profile \"" + name + "\"? There is no undo.",
				"Delete profile", YES_NO_OPTION);
			if (choice == YES_OPTION)
				onProfileDelete.accept(name);
		}));
		// Half the column each is a few px short of both names at the usual
		// margins; QA, 15 Oct: "Save as ne..." and "Delete profi...".
		for (var button : profileButtons.getComponents())
		{
			((JButton) button).setMargin(new Insets(2, 2, 2, 2));
		}
		body.add(profileButtons);
		body.add(buildThrallRow());

		Ui.small(status, MEDIUM_GRAY_COLOR).setAlignmentX(LEFT_ALIGNMENT);
		status.setBorder(createEmptyBorder(4, 0, 0, 0));
		body.add(status);

		body.add(sectionHeader("EQUIPMENT", null));
		body.add(caption(Resources.text("slotTip")));
		body.add(buildGear());
		body.add(sectionHeader("BODY", Resources.text("stylesTip")));
		// BoxLayout, NOT GridLayout: a grid splits its height evenly between
		// rows and ignores their maximum size, so every row grew as the
		// window did. A vertical box honours the fixed row height.
		body.add(Ui.column(bodyList));
	}

	/**
	 * Which profile each thrall wears, folded under the profiles row: three
	 * dropdowns over the same names, writing the three thrall settings.
	 */
	JPanel buildThrallRow()
	{
		JPanel wrap = Ui.column(),
			rows = Ui.column();
		rows.setVisible(false);

		var toggle = Ui.label("+ Used for thralls", LIGHT_GRAY_COLOR);
		toggle.setToolTipText(Resources.text("thrallProfilesTip"));
		toggle.setAlignmentX(LEFT_ALIGNMENT);
		toggle.setBorder(createEmptyBorder(4, 0, 2, 0));
		clickable(toggle, () ->
		{
			rows.setVisible(!rows.isVisible());
			toggle.setText((rows.isVisible() ? "- " : "+ ") + "Used for thralls");
			Ui.relayout(column);
		});
		wrap.add(toggle);

		String[][] styles = {{"melee", "Melee"}, {"ranged", "Ranged"}, {"magic", "Magic"}};
		for (var style : styles)
		{
			JComboBox<String> box = new JComboBox<>();
			// thrallMeleeProfile, thrallRangedProfile, thrallMagicProfile
			box.addActionListener(e -> write("thrall" + style[1] + "Profile", box.getSelectedItem()));
			thrallBoxes.put(style[0], box);
			rows.add(labelledRow(style[1], box));
			rows.add(Box.createVerticalStrut(2));
		}
		wrap.add(rows);
		return wrap;
	}

	void buildBehaviour(JPanel body)
	{
		var buttons = buttonPair();
		buttons.add(button(stayButton, Resources.text("stayTip"), () -> onFollowEnabled.accept(staying)));
		buttons.add(button("Come here", "Places the follower at your feet",
			() -> onComeHere.run()));
		body.add(buttons);

		Ui.small(behaviourCaption, MEDIUM_GRAY_COLOR).setAlignmentX(LEFT_ALIGNMENT);
		behaviourCaption.setBorder(createEmptyBorder(4, 0, 4, 0));
		body.add(behaviourCaption);

		body.add(tick("errandsEnabled", errandsBox, Resources.text("errandsTip")));

		var row = Ui.panel(new BorderLayout(6, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		row.add(errandBox, BorderLayout.CENTER);
		row.add(button("Go now", "Runs the chosen errand at the next quiet moment", () ->
		{
			int index = errandBox.getSelectedIndex();
			if (index >= 0 && index < errandKeys.size())
				onErrand.accept(errandKeys.get(index));
		}), BorderLayout.EAST);
		body.add(row);
	}

	void buildCommands(JPanel body)
	{
		var buttons = buttonColumn(2);
		buttons.add(button("Fix if it vanished",
			Resources.text("fixTip"),
			() -> onFix.run()));
		buttons.add(button("Reload message files",
			Resources.text("reloadTip"),
			() -> onReload.run()));
		body.add(buttons);
		body.add(Box.createVerticalStrut(6));

		// Wrapped, so the longest command folds rather than widening the
		// column; the maximum height is left open so every line shows.
		commandList.setEditable(false);
		commandList.setLineWrap(true);
		commandList.setWrapStyleWord(true);
		Ui.small(commandList, LIGHT_GRAY_COLOR).setBackground(DARKER_GRAY_COLOR);
		commandList.setBorder(createEmptyBorder(4, 6, 4, 6));
		commandList.setAlignmentX(LEFT_ALIGNMENT);
		commandList.setToolTipText(Resources.text("commandsHint"));
		body.add(commandList);

		Ui.small(regionCaption, MEDIUM_GRAY_COLOR).setAlignmentX(LEFT_ALIGNMENT);
		regionCaption.setBorder(createEmptyBorder(4, 0, 0, 0));
		body.add(regionCaption);
		setRegionId(-1);
	}

	/** The equipment grid and the item picker under it. */
	JPanel buildGear()
	{
		var content = Ui.column();

		gearGrid.setLayout(new GridLayout(GEAR_GRID.length, 3, 4, 4));
		gearGrid.setBackground(DARK_GRAY_COLOR);
		gearGrid.setMaximumSize(new Dimension((CELL + 4) * 3, (CELL + 4) * GEAR_GRID.length));

		var gridWrap = Ui.panel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		gridWrap.setAlignmentX(LEFT_ALIGNMENT);
		gridWrap.add(gearGrid);
		content.add(gridWrap);

		Ui.column(picker).setBorder(createEmptyBorder(8, 0, 0, 0));

		Ui.small(pickerTitle, Color.WHITE).setAlignmentX(LEFT_ALIGNMENT);
		pickerTitle.setBorder(createEmptyBorder(0, 0, 4, 0));
		picker.add(pickerTitle);

		search.setToolTipText("Type to filter items for this slot");
		search.setAlignmentX(LEFT_ALIGNMENT);
		search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		search.getDocument().addDocumentListener(Ui.onChange(this::refreshResults));
		picker.add(search);

		showAll.setText("Show items from every slot");
		styleTick(showAll, Resources.text("showAllTip"));
		showAll.addActionListener(e -> refreshResults());
		picker.add(showAll);

		picker.add(buildPreview());

		resultList.setLayout(new GridLayout(0, 1, 0, 2));
		resultList.setBackground(DARK_GRAY_COLOR);

		resultScroll = Ui.scroller(resultList);
		// Zero preferred WIDTH: a fixed one (this used to say PANEL_WIDTH)
		// out-demanded the sidebar and shoved the whole column off its right
		// edge. The height is the fixed part; the width is the container's.
		resultScroll.setPreferredSize(new Dimension(0, 240));
		resultScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 240));
		resultScroll.setAlignmentX(LEFT_ALIGNMENT);
		// The rest of the catalogue arrives as the list is scrolled toward
		// its end - every match is reachable, none is paid for up front.
		resultScroll.getVerticalScrollBar().addAdjustmentListener(e ->
		{
			var bar = resultScroll.getVerticalScrollBar();
			if (!pendingResults.isEmpty()
				&& bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 64)
				appendResults(RESULT_CHUNK);
		});
		picker.add(resultScroll);

		content.add(picker);

		return content;
	}

	/** A sub-header inside a section; the same look as a section header, but nothing folds. */
	JPanel sectionHeader(String text, String tooltip)
	{
		var label = Ui.label(text, BRAND_ORANGE);
		label.setToolTipText(tooltip);
		return headerRow(label);
	}

	/** The label on the left with a thin rule filling the rest of the line. */
	JPanel headerRow(JLabel label)
	{
		var wrap = Ui.panel(new BorderLayout());
		wrap.setAlignmentX(LEFT_ALIGNMENT);
		wrap.setBorder(createEmptyBorder(10, 0, 4, 0));
		wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		wrap.add(label, BorderLayout.WEST);

		var rule = new JPanel();
		rule.setBackground(DARKER_GRAY_COLOR);
		rule.setPreferredSize(new Dimension(10, 1));
		var ruleWrap = Ui.panel(new BorderLayout());
		ruleWrap.setBorder(createEmptyBorder(0, 6, 0, 0));
		ruleWrap.add(rule, BorderLayout.CENTER);
		wrap.add(ruleWrap, BorderLayout.CENTER);

		return wrap;
	}

	/** Hover preview: a scaled-up sprite so you can see the item before equipping. */
	JPanel buildPreview()
	{
		var wrapper = preview;
		wrapper.setBackground(DARKER_GRAY_COLOR);
		wrapper.setBorder(createEmptyBorder(6, 6, 6, 6));
		wrapper.setAlignmentX(LEFT_ALIGNMENT);
		wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 116));

		previewIcon.setHorizontalAlignment(SwingConstants.CENTER);
		previewIcon.setVerticalAlignment(SwingConstants.CENTER);
		previewIcon.setPreferredSize(new Dimension(36 * PREVIEW_SCALE, 32 * PREVIEW_SCALE));
		wrapper.add(previewIcon, BorderLayout.WEST);

		Ui.small(previewName, LIGHT_GRAY_COLOR).setVerticalAlignment(SwingConstants.TOP);
		// Fixed size: an HTML label sizes itself to its longest line, so a
		// long item name would widen this label, then the wrapper, then the
		// whole sidebar column - the layout visibly jumping on hover.
		previewName.setPreferredSize(new Dimension(PREVIEW_TEXT_WIDTH, 32 * PREVIEW_SCALE));
		previewName.setMaximumSize(new Dimension(PREVIEW_TEXT_WIDTH, 32 * PREVIEW_SCALE));
		wrapper.add(previewName, BorderLayout.CENTER);

		return wrapper;
	}

	/**
	 * The preview box, hidden until an item is hovered: empty, it took 116px
	 * of the picker to say so, in a column too narrow to say it.
	 */
	final JPanel preview = new JPanel(new BorderLayout(6, 0));

	/** Width the preview's name column is held to, so long names wrap instead of stretch. */
	static final int PREVIEW_TEXT_WIDTH = 68;

	// -------------------------------------------------------------- plugin hooks

	void setOutfit(Outfit outfit)
	{
		invokeLater(() ->
		{
			current = new Outfit(outfit);
			rebuildSlots();
		});
	}

	void setStatus(String text)
	{
		invokeLater(() -> status.setText(text));
	}

	/** Called once the plugin has worked out which slot each item belongs to. */
	void setSlotIndex(Map<Integer, KitType> index)
	{
		invokeLater(() ->
		{
			slotIndex = index;
			refreshResults();
		});
	}

	// ---------------------------------------------------------------- gear grid

	void rebuildSlots()
	{
		gearGrid.removeAll();
		for (var row : GEAR_GRID)
		{
			for (var slot : row)
				gearGrid.add(slot == null ? Ui.panel(new FlowLayout()) : buildSlotCell(slot));
		}
		Ui.relayout(gearGrid);

		bodyList.removeAll();
		bodyList.add(buildGenderRow());
		bodyList.add(Box.createVerticalStrut(2));
		// Skin is its own control rather than part of any one slot: the same
		// skin colours appear across the head, arms and hands, so recolouring
		// them together is the only way to keep a body consistent.
		var skin = bodyRow("Skin");
		skin.add(swatch(4, "Skin", GamePalette.SKIN), BorderLayout.EAST);
		bodyList.add(skin);
		for (var part : ModelRepository.BODY_PARTS)
		{
			bodyList.add(Box.createVerticalStrut(2));
			bodyList.add(buildBodyRow(part));
		}
		Ui.relayout(bodyList);
	}

	JPanel buildSlotCell(KitType slot)
	{
		boolean isActive = slot == activeSlot,
			hasItem = current.isItem(slot);

		var cell = new JPanel(new BorderLayout());
		cell.setPreferredSize(new Dimension(CELL, CELL));
		cell.setBackground(isActive ? DARK_GRAY_HOVER_COLOR : DARKER_GRAY_COLOR);
		cell.setBorder(isActive
			? createLineBorder(BRAND_ORANGE, 1)
			: createLineBorder(DARK_GRAY_COLOR, 1));
		cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		String slotName = slot.name().toLowerCase();
		if (hasItem)
		{
			int id = current.itemId(slot);
			String itemName = repository.itemName(id);
			cell.setToolTipText((itemName == null ? "item " + id : itemName)
				+ " - click to change, right-click to clear");

			var icon = new JLabel();
			icon.setHorizontalAlignment(SwingConstants.CENTER);
			var image = itemManager.getImage(id);
			if (image != null)
				image.addTo(icon);
			cell.add(icon, BorderLayout.CENTER);
		}
		else
		{
			cell.setToolTipText("Empty " + slotName + " slot - click to choose an item");

			var empty = Ui.label(sentenceCase(slotName), MEDIUM_GRAY_COLOR);
			empty.setHorizontalAlignment(SwingConstants.CENTER);
			cell.add(empty, BorderLayout.CENTER);
		}

		cell.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (isRightMouseButton(e))
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
					cell.setBackground(DARK_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (slot != activeSlot)
					cell.setBackground(DARKER_GRAY_COLOR);
			}
		});

		return cell;
	}

	// --------------------------------------------------------------------- body

	JPanel buildGenderRow()
	{
		JPanel row = bodyRow("Body"),
			toggle = new JPanel(new GridLayout(1, 2, 2, 0));
		toggle.setBackground(DARKER_GRAY_COLOR);
		toggle.add(genderButton("Male", 0));
		toggle.add(genderButton("Female", 1));
		row.add(toggle, BorderLayout.CENTER);

		return row;
	}

	JButton genderButton(String label, int gender)
	{
		boolean selected = current.getGender() == gender;
		var button = Ui.small(new JButton(label), selected ? Color.WHITE : MEDIUM_GRAY_COLOR);
		button.setFocusPainted(false);
		if (selected)
			button.setBackground(MEDIUM_GRAY_COLOR.darker());
		button.setToolTipText(Resources.text("bodyTip"));
		button.addActionListener(e ->
		{
			if (!selected)
				onGender.accept(gender);
		});
		return button;
	}

	/** Label column width; the style label takes whatever is left over. */
	static final int NAME_WIDTH = 52,
		ROW_CONTENT_HEIGHT = 20,
		ROW_HEIGHT = ROW_CONTENT_HEIGHT + 6;

	/**
	 * A body-part row: fixed height so the vertical BoxLayout cannot stretch
	 * it (rows doubled in height on a maximised window), and never wider than
	 * the panel so the scrollbar cannot clip the colour swatch off the right.
	 * The name sits in a fixed column on the left.
	 */
	JPanel bodyRow(String name)
	{
		var row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(DARKER_GRAY_COLOR);
		row.setBorder(createEmptyBorder(3, 6, 3, 6));
		row.setMinimumSize(new Dimension(0, ROW_HEIGHT));
		row.setPreferredSize(new Dimension(0, ROW_HEIGHT));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
		row.setAlignmentX(LEFT_ALIGNMENT);
		var label = Ui.label(name, LIGHT_GRAY_COLOR);
		label.setPreferredSize(new Dimension(NAME_WIDTH, ROW_CONTENT_HEIGHT));
		row.add(label, BorderLayout.WEST);
		return row;
	}

	/** One body part: cycle its kit, and set its colour. */
	JPanel buildBodyRow(KitType part)
	{
		String name = sentenceCase(part.name().toLowerCase());
		var row = bodyRow(name);

		// Kits have no names in the cache, so they're cycled and judged by eye - the
		// follower updates live, which makes it its own preview.
		//
		// BorderLayout rather than a flow: the arrows keep their size at the
		// edges and the label in the middle absorbs whatever width is left, so
		// a narrow sidebar squeezes the text instead of pushing the swatch out.
		var cycle = new JPanel(new BorderLayout(2, 0));
		cycle.setBackground(DARKER_GRAY_COLOR);

		cycle.add(arrow("‹", "Previous style", part, -1), BorderLayout.WEST);

		var choices = repository.kitsFor(part, current.getGender());
		int index = current.isKit(part) ? choices.indexOf(current.kitId(part)) : -1;
		String label = KitNames.label(part, index, choices.size());

		var style = Ui.label(label, MEDIUM_GRAY_COLOR);
		style.setPreferredSize(new Dimension(0, ROW_CONTENT_HEIGHT));
		style.setHorizontalAlignment(SwingConstants.CENTER);
		cycle.add(style, BorderLayout.CENTER);

		cycle.add(arrow("›", "Next style", part, 1), BorderLayout.EAST);

		row.add(cycle, BorderLayout.CENTER);

		// Colours come from the game's own palette tables - every choice here is a
		// colour the game can genuinely produce, applied exactly as the client does.
		// Jaw follows hair, arms follow torso, hands follow skin, as in the game.
		if (part == HAIR || part == TORSO
			|| part == LEGS || part == BOOTS)
		{
			int colorSlot = colorSlotFor(part);
			row.add(swatch(colorSlot, name, GamePalette.table(colorSlot)), BorderLayout.EAST);
		}
		else
			// The swatch's room, kept, so the arrows line up down the column.
			row.add(Box.createRigidArea(new Dimension(20, 20)), BorderLayout.EAST);

		return row;
	}

	/** One of a body row's style arrows: a step back or forward through the part's kits. */
	JButton arrow(String text, String tooltip, KitType part, int step)
	{
		var arrow = button(text, tooltip, () -> onCycleKit.accept(part, step));
		arrow.setMargin(new Insets(0, 2, 0, 2));
		arrow.setPreferredSize(new Dimension(18, ROW_CONTENT_HEIGHT));
		return arrow;
	}

	/** "head" as a label shows it: "Head". */
	static String sentenceCase(String word)
	{
		return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
	}

	/** A clickable colour square showing the current palette choice. */
	JPanel swatch(int colorSlot, String title, short[] table)
	{
		var square = new JPanel();
		square.setPreferredSize(new Dimension(20, 20));
		square.setBackground(new Color(HslColor.toRgb(table[paletteIndex(colorSlot)])));
		square.setBorder(createLineBorder(DARK_GRAY_COLOR, 1));
		square.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		square.setToolTipText(title + " colour: the game's " + table.length + " exact choices");
		square.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				var chosen = pickPaletteIndex(title, table);
				if (chosen != null)
					onBodyColor.accept(colorSlot, chosen);
			}
		});

		var wrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		wrap.setBackground(DARKER_GRAY_COLOR);
		wrap.add(square);
		return wrap;
	}

	/** Current palette index for a body-colour slot, bounds-safe. */
	int paletteIndex(int colorSlot)
	{
		int[] colors = current.getColors();
		int index = colorSlot < colors.length ? colors[colorSlot] : 0;
		short[] table = GamePalette.table(colorSlot);
		return table != null && index >= 0 && index < table.length ? index : 0;
	}

	/** Body-colour slot a part's colour comes from, or -1 if skin covers it. */
	static int colorSlotFor(KitType part)
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

	void showPicker(KitType slot)
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
	Integer pickPaletteIndex(String title, short[] table)
	{
		var grid = Ui.panel(new GridLayout(0, 8, 3, 3));

		final Integer[] picked = {null};
		final javax.swing.JDialog[] dialog = {null};

		for (int i = 0; i < table.length; i++)
		{
			int rgb = HslColor.toRgb(table[i]);
			final int index = i;

			var swatch = new JButton();
			swatch.setBackground(new Color(rgb));
			swatch.setOpaque(true);
			swatch.setBorderPainted(false);
			swatch.setPreferredSize(new Dimension(26, 26));
			swatch.setToolTipText(index + ": #" + String.format("%06X", rgb));
			swatch.addActionListener(e ->
			{
				picked[0] = index;
				if (dialog[0] != null)
					dialog[0].dispose();
			});
			grid.add(swatch);
		}

		var pane = new JOptionPane(grid,
			PLAIN_MESSAGE,
			DEFAULT_OPTION, null, new Object[]{}, null);
		dialog[0] = pane.createDialog(this, title + " colour");
		dialog[0].setVisible(true);
		dialog[0].dispose();

		return picked[0];
	}

	void refreshResults()
	{
		resultList.removeAll();
		pendingResults.clear();

		if (activeSlot == null)
		{
			Ui.relayout(resultList);
			return;
		}

		// The cache-dumper detail belongs in the log, not in front of a user
		// who has never heard of the build tooling.
		if (!repository.isLoaded() || !repository.hasNames())
			resultList.add(hint(Resources.text("itemsUnloaded")));
		else
		{
			// EVERY match, not a page of them: rows are built lazily as the
			// list scrolls (see the adjustment listener on resultScroll), so
			// the full catalogue is reachable without being paid for up front.
			// The limit is a formality search() multiplies internally - large,
			// but nowhere near where that multiply would overflow.
			var matches =
				repository.search(search.getText(), 100_000);

			boolean filter = !slotIndex.isEmpty() && !showAll.isSelected();
			for (var item : matches)
			{
				// Only offer items that belong in this slot. Until the index is built
				// everything is offered, so the picker still works immediately.
				if (filter && slotIndex.get(item.id) != activeSlot)
					continue;
				pendingResults.addLast(item);
			}
			repeatedNames = repeated(pendingResults.stream().map(item -> item.name)
				.collect(java.util.stream.Collectors.toList()));

			if (pendingResults.isEmpty())
			{
				if (slotIndex.isEmpty())
					resultList.add(hint("Indexing items, try again in a moment..."));
				else
					resultList.add(hint("No " + activeSlot.name().toLowerCase()
						+ Resources.text("noMatches")));
			}
			else
				appendResults(RESULT_CHUNK);
		}

		Ui.relayout(resultList);
	}

	/** Builds the next slice of pending matches into rows. */
	void appendResults(int count)
	{
		for (int i = 0; i < count && !pendingResults.isEmpty(); i++)
			resultList.add(buildResultRow(pendingResults.pollFirst()));
		Ui.relayout(resultList);
	}

	JPanel buildResultRow(ModelRepository.WearableItem item)
	{
		var row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(DARKER_GRAY_COLOR);
		row.setBorder(createEmptyBorder(2, 4, 2, 4));
		row.setToolTipText(item.name + " - click to equip (id " + item.id + ")");
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		// Never taller or wider than a row needs: the list lives in a vertical
		// box, which would otherwise stretch rows to fill and let a long name
		// widen the whole column.
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

		var icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		var image = itemManager.getImage(item.id);
		if (image != null)
			image.addTo(icon);
		row.add(icon, BorderLayout.WEST);

		var name = Ui.label(item.name, Color.WHITE);
		// Zero preferred width: BorderLayout's centre takes whatever is left
		// over at layout time, and the label ellipsises rather than demanding
		// room for the full name. The tooltip carries the untruncated text.
		name.setPreferredSize(new Dimension(0, 20));
		row.add(name, BorderLayout.CENTER);

		// Two rows with the same name looked identical; the id tells them apart.
		if (repeatedNames.contains(item.name))
			row.add(Ui.label("#" + item.id, MEDIUM_GRAY_COLOR), BorderLayout.EAST);

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
				row.setBackground(DARK_GRAY_HOVER_COLOR);
				showPreview(item);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(DARKER_GRAY_COLOR);
			}
		});

		return row;
	}

	/** The names that appear more than once. */
	static Set<String> repeated(Iterable<String> names)
	{
		Set<String> seen = new HashSet<>(),
			repeated = new HashSet<>();
		for (String name : names)
		{
			if (!seen.add(name))
				repeated.add(name);
		}
		return repeated;
	}

	// ------------------------------------------------------------------ preview

	void showPreview(ModelRepository.WearableItem item)
	{
		// The body width is what makes long names WRAP; without it the label
		// grows sideways and drags the sidebar's layout with it.
		previewName.setText("<html><body style='width:" + (PREVIEW_TEXT_WIDTH - 4) + "px'><b>"
			+ Ui.escape(item.name) + "</b><br>id " + item.id + "</body></html>");
		preview.setVisible(true);

		var image = itemManager.getImage(item.id);
		if (image == null)
		{
			previewIcon.setIcon(null);
			return;
		}

		// The sprite may not have arrived yet; scale it once it has.
		image.onLoaded(() -> invokeLater(() ->
		{
			var scaled = image.getScaledInstance(
				image.getWidth() * PREVIEW_SCALE,
				image.getHeight() * PREVIEW_SCALE,
				Image.SCALE_SMOOTH);
			previewIcon.setIcon(new ImageIcon(scaled));
		}));
	}

	void clearPreview()
	{
		previewIcon.setIcon(null);
		previewName.setText("Hover an item to preview it");
		preview.setVisible(false);
	}

	/** A grey line in the results, wrapped to the picker's width. */
	JLabel hint(String text)
	{
		return Ui.label(Ui.html(150, text), MEDIUM_GRAY_COLOR);
	}
}

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
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.api.kit.KitType;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
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

	// public:cut messages-window
	/** Opens the message editor window; set by the plugin after construction. */
	private Runnable onEditMessages = () -> { };

	public void setOnEditMessages(Runnable onEditMessages)
	{
		this.onEditMessages = onEditMessages;
	}

	// public:end messages-window
	// ------------------------------------------------------------------- mute

	/**
	 * The same Mute as the one in settings, surfaced where people actually
	 * are. Both write the one config key, so they can never disagree.
	 */
	private final javax.swing.JCheckBox muteBox = new javax.swing.JCheckBox("Mute the follower");

	// public:cut memory-window
	/** Opens the memory window (R20); set by the plugin after construction. */
	private Runnable onShowMemory = () -> { };

	public void setOnShowMemory(Runnable onShowMemory)
	{
		this.onShowMemory = onShowMemory;
	}

	// public:end memory-window
	// public:cut posing
	/** Opens the film window; set by the plugin after construction. */
	private Runnable onFilm = () -> { };

	public void setOnFilm(Runnable onFilm)
	{
		this.onFilm = onFilm;
	}

	// public:end posing
	// public:cut dialogs-editor
	/** Opens the conversation editor; set by the plugin after construction. */
	private Runnable onEditDialogs = () -> { };

	public void setOnEditDialogs(Runnable onEditDialogs)
	{
		this.onEditDialogs = onEditDialogs;
	}

	// public:end dialogs-editor
	// public:cut sharevoice
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

	// public:end sharevoice
	// -------------------------------------------------------- synced settings

	/**
	 * Chattiness, the chatbox mirror and errands follow the Mute pattern: the
	 * same key as settings, written through the plugin, and reflected back
	 * without an echo when the setting changes from the other side. The
	 * thrall profiles and the open sections are written the same way.
	 */
	private final javax.swing.JComboBox<com.follower.speech.Chattiness> chattinessBox =
		new javax.swing.JComboBox<>(com.follower.speech.Chattiness.values());
	private final javax.swing.JCheckBox chatboxBox =
		new javax.swing.JCheckBox("Also show lines in the chatbox");
	private final javax.swing.JCheckBox errandsBox = new javax.swing.JCheckBox("Go on errands"); // public:cut errands

	/** (config key, value) as the player changes a synced control; set by the plugin. */
	private BiConsumer<String, Object> onSetting = (key, value) -> { };

	/** The synced ticks and dropdown, by their config key. */
	private final Map<String, javax.swing.JComponent> synced = new java.util.HashMap<>();

	/** Set while a synced control is being set FROM config, so it does not echo. */
	private boolean suppressSyncEvents;

	public void setOnSetting(BiConsumer<String, Object> onSetting)
	{
		this.onSetting = onSetting;
	}

	/**
	 * Reflects a config-side change without writing it back again: a tick
	 * takes a Boolean, the dropdown its item. On the event thread like its
	 * neighbours: a profile switch sends this from RuneLite's executor (QA3).
	 */
	public void setSetting(String key, Object value)
	{
		synced(() ->
		{
			javax.swing.JComponent control = synced.get(key);
			if (control instanceof javax.swing.JCheckBox)
			{
				((javax.swing.JCheckBox) control).setSelected((Boolean) value);
			}
			else
			{
				((javax.swing.JComboBox<?>) control).setSelectedItem(value);
			}
		});
	}

	/** Writes a synced control's new value, unless config is the one setting it. */
	private void write(String key, Object value)
	{
		if (!suppressSyncEvents && value != null)
		{
			onSetting.accept(key, value);
		}
	}

	/** A synced tick, styled like the others, writing its config key when clicked. */
	private javax.swing.JCheckBox tick(String key, javax.swing.JCheckBox box, String tooltip)
	{
		styleTick(box, tooltip);
		synced.put(key, box);
		box.addActionListener(e -> write(key, box.isSelected()));
		return box;
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
	// public:cut errands
	private final javax.swing.JComboBox<String> errandBox = new javax.swing.JComboBox<>();
	private List<String> errandKeys = java.util.Collections.emptyList();
	private Consumer<String> onErrand = k -> { };
	// public:end errands

	public void setOnFollowEnabled(Consumer<Boolean> onFollowEnabled)
	{
		this.onFollowEnabled = onFollowEnabled;
	}

	public void setOnComeHere(Runnable onComeHere)
	{
		this.onComeHere = onComeHere;
	}

	// public:cut errands
	public void setOnErrand(Consumer<String> onErrand)
	{
		this.onErrand = onErrand;
	}

	// public:end errands
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
		SwingUtilities.invokeLater(() -> behaviourCaption.setText(Ui.html(160, "Right-click " + name
			+ " in game for Talk-to, Stay, Dance, Wave, Step-in-front and Read-chronicle."
			+ " Hold Shift and right-click any tile to send it there.")));
	}

	// public:cut errands
	/** The errands on offer, key to display name, in the order to list them. */
	public void setErrands(Map<String, String> keyToLabel)
	{
		SwingUtilities.invokeLater(() ->
		{
			errandKeys = new java.util.ArrayList<>(keyToLabel.keySet());
			refill(errandBox, keyToLabel.values());
		});
	}

	// public:end errands
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
		SwingUtilities.invokeLater(() -> regionCaption.setText(Ui.html(160, "Your region id: "
			+ (region > 0 ? Integer.toString(region) : "not known until you log in")
			+ ", for Locations rules.")));
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

	/** The whole scrolling column, revalidated as sections open and close. */
	private final JPanel column = Ui.column(new Ui.WidthTrackingPanel());

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

	// public:cut outfit-profiles
	// ---------------------------------------------------------------- profiles

	private final javax.swing.JComboBox<String> profileCombo = new javax.swing.JComboBox<>();
	private final JButton profileDelete = new JButton("Delete");

	private Consumer<String> onProfileLoad = s -> { };
	private Consumer<String> onProfileSave = s -> { };
	private Consumer<String> onProfileDelete = s -> { };

	// public:cut thrall-outfits
	/** Whether a profile is one of the seeded thrall looks; set by the plugin. */
	private java.util.function.Predicate<String> profileProtected = n -> false;

	// public:end thrall-outfits
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

	// public:cut thrall-outfits
	public void setProfileProtected(java.util.function.Predicate<String> profileProtected)
	{
		this.profileProtected = profileProtected;
	}

	// public:end thrall-outfits
	/** Set while the dropdown is being repopulated, so selection events are ignored. */
	private boolean suppressProfileEvents;

	private List<String> profileNames = java.util.Collections.emptyList();

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
	public void setProfileNames(java.util.List<String> names, String active)
	{
		List<String> copy = new java.util.ArrayList<>(names);
		SwingUtilities.invokeLater(() ->
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
			fillThrallBoxes(); // public:cut thrall-outfits
			refreshDeleteButton();
		});
	}

	private String profileName()
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
		{
			return;
		}
		String name = typed.trim();
		if (!profileProtected.test(name) && profileNames.contains(name) && !confirmReplace(name)) // public:cut thrall-outfits
		// public:else if (profileNames.contains(name) && !confirmReplace(name))
		{
			return;
		}
		onProfileSave.accept(name);
	}

	/** Whether to write the outfit worn now over the profile of that name. */
	boolean confirmReplace(String name)
	{
		return javax.swing.JOptionPane.showConfirmDialog(this,
			"Replace the outfit profile \"" + name + "\" with what the follower is wearing now?",
			"New profile", javax.swing.JOptionPane.YES_NO_OPTION) == javax.swing.JOptionPane.YES_OPTION;
	}

	// public:cut thrall-outfits
	// ----------------------------------------------------------------- thralls

	/** Style key to its dropdown, in the order the rows are shown. */
	private final Map<String, javax.swing.JComboBox<String>> thrallBoxes =
		new java.util.LinkedHashMap<>();

	/** Style key to the profile name the setting holds. */
	private final Map<String, String> thrallProfiles = new java.util.LinkedHashMap<>();

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
				refill(box, profileNames);
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

	// public:end thrall-outfits
	/** Delete is offered only for a profile no thrall depends on. */
	private void refreshDeleteButton()
	{
		String name = profileName();
		profileDelete.setEnabled(!name.isEmpty() && !profileProtected.test(name) // public:cut thrall-outfits
		// public:else profileDelete.setEnabled(!name.isEmpty());
			&& thrallSuffix(name).isEmpty()); // public:cut thrall-outfits
	}

	// public:end outfit-profiles
	private final JPanel gearGrid = new JPanel();
	private final JPanel bodyList = new JPanel();
	private final JPanel picker = new JPanel();
	private final JPanel resultList = new JPanel();

	/** Matches not yet built into rows, drained as the list scrolls down. */
	private final java.util.ArrayDeque<ModelRepository.WearableItem> pendingResults =
		new java.util.ArrayDeque<>();

	/** Names more than one of the current matches share; their rows show the id. */
	private java.util.Set<String> repeatedNames = java.util.Collections.emptySet();

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

		// A WidthTrackingPanel, not a plain panel: a plain view is laid out at
		// its own preferred width, so any child preferring more than the
		// sidebar (or the width left once the scrollbar appears) pushed the
		// whole column past the right edge - padding gone, buttons clipped -
		// with the horizontal scrollbar deliberately never there to save it.
		column.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		column.add(buildHeader());
		buildTalking(section("talking", "TALKING"));
		buildMemory(section("memory", "MEMORY")); // public:cut memory-window
		buildOutfit(section("outfit", "OUTFIT"));
		buildBehaviour(section("behaviour", "BEHAVIOUR"));
		buildCommands(section("commands", "COMMANDS"));

		// Everything scrolls together: the sidebar is never tall enough for
		// the dialogue editors, profiles, equipment grid and item picker at
		// once, and a clipped panel hides controls with no way to reach them.
		JScrollPane scroll = Ui.scroller(column);
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.setMinimumSize(new Dimension(0, 0));
		add(scroll, BorderLayout.CENTER);

		showPicker(null);
	}

	// ------------------------------------------------------------------ building

	private JPanel buildHeader()
	{
		JPanel header = Ui.column();

		// The one line that says what this thing IS, before any controls do.
		// The body style width matters: an HTML label's preferred width is one
		// unwrapped line, and a single wide child forces the WHOLE panel past
		// the sidebar, clipping every section against the no-horizontal-scroll
		// viewport.
		JLabel identity = caption("Only you"
			+ " can see this follower. Dress it under Outfit; edit what it says"
			+ " under Talking; see what it remembers under Memory.");
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
		JLabel label = Ui.label("", ColorScheme.BRAND_ORANGE);
		JPanel header = headerRow(label);
		header.setToolTipText("Click to open or close this section");
		clickable(header, () -> toggleSection(key));

		JPanel body = Ui.column();
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
		Ui.relayout(column);
		// Stored in section order, whatever order they were clicked in.
		List<String> open = new java.util.ArrayList<>();
		for (String k : sections.keySet())
		{
			if (openSections.contains(k))
			{
				open.add(k);
			}
		}
		onSetting.accept("panelFolds", String.join(",", open));
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
		JLabel label = Ui.label(Ui.html(160, text), ColorScheme.MEDIUM_GRAY_COLOR);
		label.setAlignmentX(LEFT_ALIGNMENT);
		label.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
		return label;
	}

	/** A full-width column of buttons. */
	private JPanel buttonColumn(int count)
	{
		JPanel buttons = Ui.panel(new GridLayout(0, 1, 6, 4));
		buttons.setAlignmentX(LEFT_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30 * count));
		return buttons;
	}

	/** Two buttons side by side. */
	private JPanel buttonPair()
	{
		JPanel buttons = Ui.panel(new GridLayout(1, 2, 6, 0));
		buttons.setAlignmentX(LEFT_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		return buttons;
	}

	private JButton button(String text, String tooltip, Runnable action)
	{
		return button(new JButton(text), tooltip, action);
	}

	private static JButton button(JButton button, String tooltip, Runnable action)
	{
		button.setToolTipText(tooltip);
		button.setFocusPainted(false);
		button.addActionListener(e -> action.run());
		return button;
	}

	/** Empties a dropdown and lists the items in it, in order. */
	private static void refill(javax.swing.JComboBox<String> box, Iterable<String> items)
	{
		box.removeAllItems();
		for (String item : items)
		{
			box.addItem(item);
		}
	}

	private void styleTick(javax.swing.JCheckBox box, String tooltip)
	{
		Ui.small(box, ColorScheme.LIGHT_GRAY_COLOR).setToolTipText(tooltip);
		box.setBackground(ColorScheme.DARK_GRAY_COLOR);
		box.setAlignmentX(LEFT_ALIGNMENT);
	}

	/** A small label on the left and a control taking the rest of the row. */
	private JPanel labelledRow(String text, javax.swing.JComponent control)
	{
		JPanel row = Ui.panel(new BorderLayout(6, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		JLabel label = Ui.label(text, ColorScheme.LIGHT_GRAY_COLOR);
		// At least the body rows' column, and wider when the name needs it:
		// QA, 15 Oct, "Chattiness" was cut to "Chattine...".
		label.setPreferredSize(new Dimension(Math.max(NAME_WIDTH, label.getPreferredSize().width),
			ROW_CONTENT_HEIGHT));
		row.add(label, BorderLayout.WEST);
		row.add(control, BorderLayout.CENTER);
		return row;
	}

	private void buildTalking(JPanel body)
	{
		body.add(caption("What it says and when. Messages holds every line;"
			+ " Conversations the talks it starts itself."));

		body.add(tick("muted", muteBox, "The follower stops talking. All your other"
			+ " speech settings are kept. Same switch as Mute in settings."));

		chattinessBox.setToolTipText("How much it talks. Same setting as Chattiness in settings.");
		synced.put("chattiness", chattinessBox);
		chattinessBox.addActionListener(e -> write("chattiness", chattinessBox.getSelectedItem()));
		body.add(labelledRow("Chattiness", chattinessBox));

		body.add(tick("mirrorToChat", chatboxBox, "Every line also appears in the chatbox under the"
			+ " follower's name. Same switch as in settings."));
		body.add(javax.swing.Box.createVerticalStrut(4));

		// Four doors, not nine: the old grid surfaced every internal rule
		// group as its own button, a navigation decision nobody should have
		// to make cold. The categories live in a dropdown inside Messages.
		JPanel buttons = buttonColumn(0
			+ 1 // public:cut messages-window
			+ 1 // public:cut dialogs-editor
			+ 2); // public:cut sharevoice
			// public:else );
		// public:cut messages-window
		buttons.add(button("Messages...",
			"Everything the follower says, by subject, to read and edit",
			() -> onEditMessages.run()));
		// public:end messages-window
		// public:cut dialogs-editor
		buttons.add(button("Conversations...",
			"View and edit the conversations the follower starts itself -"
				+ " the ones its Answer option opens while it waits for your reply",
			() -> onEditDialogs.run()));
		// public:end dialogs-editor
		// public:cut sharevoice
		buttons.add(button("Attach sounds...",
			"Where sound files go, and how to attach one to a line",
			() -> onAttachSounds.run()));
		buttons.add(button("Share lines...",
			"Export what you have written, or take on somebody else's",
			() -> onShareLines.run()));
		// public:end sharevoice
		body.add(buttons);
	}

	// public:cut memory-window
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

	// public:end memory-window
	private void buildOutfit(JPanel body)
	{
		JPanel buttons = buttonPair();
		buttons.add(button("Copy my gear",
			"Fill every slot from what you are currently wearing", () -> onCopyGear.run()));
		buttons.add(button("Clear all",
			"Strip the follower back to the default body", () -> onClearAll.run()));
		body.add(buttons);
		body.add(javax.swing.Box.createVerticalStrut(6));
		// public:cut outfit-profiles

		JLabel profilesLabel = Ui.label("OUTFIT PROFILES", ColorScheme.LIGHT_GRAY_COLOR);
		profilesLabel.setAlignmentX(LEFT_ALIGNMENT);
		body.add(profilesLabel);

		// Pick a profile and you are wearing it; every change from then on is
		// saved straight back to it. New profile makes one, Delete removes it.
		profileCombo.setAlignmentX(LEFT_ALIGNMENT);
		profileCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		// public:cut thrall-outfits
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
		// public:end thrall-outfits
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
		profileButtons.add(button("New profile...",
			"Save the follower's current outfit as a new profile", () ->
		{
			newProfile(javax.swing.JOptionPane.showInputDialog(this,
				"Name for the new outfit profile:", "New profile",
				javax.swing.JOptionPane.PLAIN_MESSAGE));
		}));

		profileButtons.add(button(profileDelete, "Remove the selected profile. The built-in looks, and a"
			+ " profile a thrall wears, cannot be removed.", () ->
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
		}));
		// Half the column each is a few px short of both names at the usual
		// margins; QA, 15 Oct: "Save as ne..." and "Delete profi...".
		for (java.awt.Component button : profileButtons.getComponents())
		{
			((JButton) button).setMargin(new java.awt.Insets(2, 2, 2, 2));
		}
		body.add(profileButtons);
		body.add(buildThrallRow()); // public:cut thrall-outfits
		// public:end outfit-profiles

		Ui.small(status, ColorScheme.MEDIUM_GRAY_COLOR).setAlignmentX(LEFT_ALIGNMENT);
		status.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		body.add(status);

		body.add(sectionHeader("EQUIPMENT", null));
		body.add(caption("Click a slot to change it; right-click to clear it."));
		body.add(buildGear());
		body.add(sectionHeader("BODY", "Styles cycle live on the follower; colours are the game's own"));
		// BoxLayout, NOT GridLayout: a grid splits its height evenly between
		// rows and ignores their maximum size, so every row grew as the
		// window did. A vertical box honours the fixed row height.
		body.add(Ui.column(bodyList));
	}

	// public:cut outfit-profiles,thrall-outfits
	/**
	 * Which profile each thrall wears, folded under the profiles row: three
	 * dropdowns over the same names, writing the three thrall settings.
	 */
	private JPanel buildThrallRow()
	{
		JPanel wrap = Ui.column();
		JPanel rows = Ui.column();
		rows.setVisible(false);

		JLabel toggle = Ui.label("+ Used for thralls", ColorScheme.LIGHT_GRAY_COLOR);
		toggle.setToolTipText("Which profile stands in for each thrall. Same"
			+ " three settings as in Thrall mode.");
		toggle.setAlignmentX(LEFT_ALIGNMENT);
		toggle.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));
		clickable(toggle, () ->
		{
			rows.setVisible(!rows.isVisible());
			toggle.setText((rows.isVisible() ? "- " : "+ ") + "Used for thralls");
			Ui.relayout(column);
		});
		wrap.add(toggle);

		String[][] styles = {{"melee", "Melee"}, {"ranged", "Ranged"}, {"magic", "Magic"}};
		for (String[] style : styles)
		{
			javax.swing.JComboBox<String> box = new javax.swing.JComboBox<>();
			// thrallMeleeProfile, thrallRangedProfile, thrallMagicProfile
			box.addActionListener(e -> write("thrall" + style[1] + "Profile", box.getSelectedItem()));
			thrallBoxes.put(style[0], box);
			rows.add(labelledRow(style[1], box));
			rows.add(javax.swing.Box.createVerticalStrut(2));
		}
		wrap.add(rows);
		return wrap;
	}

	// public:end outfit-profiles,thrall-outfits
	private void buildBehaviour(JPanel body)
	{
		JPanel buttons = buttonPair();
		buttons.add(button(stayButton, "Same as right-click Stay and Follow-me on the follower,"
			+ " and Follow me in settings", () -> onFollowEnabled.accept(staying)));
		buttons.add(button("Come here", "Places the follower at your feet",
			() -> onComeHere.run()));
		body.add(buttons);

		Ui.small(behaviourCaption, ColorScheme.MEDIUM_GRAY_COLOR).setAlignmentX(LEFT_ALIGNMENT);
		behaviourCaption.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
		body.add(behaviourCaption);

		// public:cut errands
		body.add(tick("errandsEnabled", errandsBox, "Now and then it wanders off on a small errand and"
			+ " comes back. Same switch as Go on errands in settings."));

		JPanel row = Ui.panel(new BorderLayout(6, 0));
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
		// public:end errands
		// public:cut posing
		body.add(javax.swing.Box.createVerticalStrut(6));

		JPanel film = buttonColumn(1);
		film.add(button("Advanced posing...",
			"A window to pose it, turn it, give it lines, and play out a scene you write",
			() -> onFilm.run()));
		body.add(film);
		// public:end posing
	}

	private void buildCommands(JPanel body)
	{
		JPanel buttons = buttonColumn(2);
		buttons.add(button("Fix if it vanished",
			"Re-attaches the follower to the scene, then rebuilds it from the outfit",
			() -> onFix.run()));
		buttons.add(button("Reload message and sound files",
			"Re-reads phrases.json and the attached sounds from disk",
			() -> onReload.run()));
		body.add(buttons);
		body.add(javax.swing.Box.createVerticalStrut(6));

		// Wrapped, so the longest command folds rather than widening the
		// column; the maximum height is left open so every line shows.
		commandList.setEditable(false);
		commandList.setLineWrap(true);
		commandList.setWrapStyleWord(true);
		Ui.small(commandList, ColorScheme.LIGHT_GRAY_COLOR).setBackground(ColorScheme.DARKER_GRAY_COLOR);
		commandList.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		commandList.setAlignmentX(LEFT_ALIGNMENT);
		commandList.setToolTipText("Type any of these in the chat as ::follower <command>");
		body.add(commandList);

		Ui.small(regionCaption, ColorScheme.MEDIUM_GRAY_COLOR).setAlignmentX(LEFT_ALIGNMENT);
		regionCaption.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		body.add(regionCaption);
		setRegionId(-1);
	}

	/** The equipment grid and the item picker under it. */
	private JPanel buildGear()
	{
		JPanel content = Ui.column();

		gearGrid.setLayout(new GridLayout(GEAR_GRID.length, 3, 4, 4));
		gearGrid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		gearGrid.setMaximumSize(new Dimension((CELL + 4) * 3, (CELL + 4) * GEAR_GRID.length));

		JPanel gridWrap = Ui.panel(new FlowLayout(FlowLayout.CENTER, 0, 0));
		gridWrap.setAlignmentX(LEFT_ALIGNMENT);
		gridWrap.add(gearGrid);
		content.add(gridWrap);

		Ui.column(picker).setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		Ui.small(pickerTitle, Color.WHITE).setAlignmentX(LEFT_ALIGNMENT);
		pickerTitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		picker.add(pickerTitle);

		search.setToolTipText("Type to filter items for this slot");
		search.setAlignmentX(LEFT_ALIGNMENT);
		search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		search.getDocument().addDocumentListener(Ui.onChange(this::refreshResults));
		picker.add(search);

		showAll.setText("Show items from every slot");
		styleTick(showAll, "Some wearable items (mostly quest and holiday ones)"
			+ " don't say which slot they belong to. Tick to include them in every search.");
		showAll.addActionListener(e -> refreshResults());
		picker.add(showAll);

		picker.add(buildPreview());

		resultList.setLayout(new GridLayout(0, 1, 0, 2));
		resultList.setBackground(ColorScheme.DARK_GRAY_COLOR);

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

	/** A sub-header inside a section; the same look as a section header, but nothing folds. */
	private JPanel sectionHeader(String text, String tooltip)
	{
		JLabel label = Ui.label(text, ColorScheme.BRAND_ORANGE);
		label.setToolTipText(tooltip);
		return headerRow(label);
	}

	/** The label on the left with a thin rule filling the rest of the line. */
	private JPanel headerRow(JLabel label)
	{
		JPanel wrap = Ui.panel(new BorderLayout());
		wrap.setAlignmentX(LEFT_ALIGNMENT);
		wrap.setBorder(BorderFactory.createEmptyBorder(10, 0, 4, 0));
		wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		wrap.add(label, BorderLayout.WEST);

		JPanel rule = new JPanel();
		rule.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		rule.setPreferredSize(new Dimension(10, 1));
		JPanel ruleWrap = Ui.panel(new BorderLayout());
		ruleWrap.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
		ruleWrap.add(rule, BorderLayout.CENTER);
		wrap.add(ruleWrap, BorderLayout.CENTER);

		return wrap;
	}

	/** Hover preview: a scaled-up sprite so you can see the item before equipping. */
	private JPanel buildPreview()
	{
		JPanel wrapper = preview;
		wrapper.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrapper.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		wrapper.setAlignmentX(LEFT_ALIGNMENT);
		wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 116));

		previewIcon.setHorizontalAlignment(SwingConstants.CENTER);
		previewIcon.setVerticalAlignment(SwingConstants.CENTER);
		previewIcon.setPreferredSize(new Dimension(36 * PREVIEW_SCALE, 32 * PREVIEW_SCALE));
		wrapper.add(previewIcon, BorderLayout.WEST);

		Ui.small(previewName, ColorScheme.LIGHT_GRAY_COLOR).setVerticalAlignment(SwingConstants.TOP);
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
	private final JPanel preview = new JPanel(new BorderLayout(6, 0));

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
				gearGrid.add(slot == null ? Ui.panel(new FlowLayout()) : buildSlotCell(slot));
			}
		}
		Ui.relayout(gearGrid);

		bodyList.removeAll();
		bodyList.add(buildGenderRow());
		bodyList.add(javax.swing.Box.createVerticalStrut(2));
		// Skin is its own control rather than part of any one slot: the same
		// skin colours appear across the head, arms and hands, so recolouring
		// them together is the only way to keep a body consistent.
		JPanel skin = bodyRow("Skin");
		skin.add(swatch(4, "Skin", GamePalette.SKIN), BorderLayout.EAST);
		bodyList.add(skin);
		for (KitType part : ModelRepository.BODY_PARTS)
		{
			bodyList.add(javax.swing.Box.createVerticalStrut(2));
			bodyList.add(buildBodyRow(part));
		}
		Ui.relayout(bodyList);
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

			JLabel empty = Ui.label(sentenceCase(slotName), ColorScheme.MEDIUM_GRAY_COLOR);
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
		JPanel row = bodyRow("Body");
		JPanel toggle = new JPanel(new GridLayout(1, 2, 2, 0));
		toggle.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		toggle.add(genderButton("Male", 0));
		toggle.add(genderButton("Female", 1));
		row.add(toggle, BorderLayout.CENTER);

		return row;
	}

	private JButton genderButton(String label, int gender)
	{
		boolean selected = current.getGender() == gender;
		JButton button = Ui.small(new JButton(label), selected ? Color.WHITE : ColorScheme.MEDIUM_GRAY_COLOR);
		button.setFocusPainted(false);
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

	/** Label column width; the style label takes whatever is left over. */
	private static final int NAME_WIDTH = 52;
	private static final int ROW_CONTENT_HEIGHT = 20;
	private static final int ROW_HEIGHT = ROW_CONTENT_HEIGHT + 6;

	/**
	 * A body-part row: fixed height so the vertical BoxLayout cannot stretch
	 * it (rows doubled in height on a maximised window), and never wider than
	 * the panel so the scrollbar cannot clip the colour swatch off the right.
	 * The name sits in a fixed column on the left.
	 */
	private JPanel bodyRow(String name)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
		row.setMinimumSize(new Dimension(0, ROW_HEIGHT));
		row.setPreferredSize(new Dimension(0, ROW_HEIGHT));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
		row.setAlignmentX(LEFT_ALIGNMENT);
		JLabel label = Ui.label(name, ColorScheme.LIGHT_GRAY_COLOR);
		label.setPreferredSize(new Dimension(NAME_WIDTH, ROW_CONTENT_HEIGHT));
		row.add(label, BorderLayout.WEST);
		return row;
	}

	/** One body part: cycle its kit, and set its colour. */
	private JPanel buildBodyRow(KitType part)
	{
		String name = sentenceCase(part.name().toLowerCase());
		JPanel row = bodyRow(name);

		// Kits have no names in the cache, so they're cycled and judged by eye - the
		// follower updates live, which makes it its own preview.
		//
		// BorderLayout rather than a flow: the arrows keep their size at the
		// edges and the label in the middle absorbs whatever width is left, so
		// a narrow sidebar squeezes the text instead of pushing the swatch out.
		JPanel cycle = new JPanel(new BorderLayout(2, 0));
		cycle.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		cycle.add(arrow("‹", "Previous style", part, -1), BorderLayout.WEST);

		java.util.List<Integer> choices = repository.kitsFor(part, current.getGender());
		int index = current.isKit(part) ? choices.indexOf(current.kitId(part)) : -1;
		String label = KitNames.label(part, index, choices.size());

		JLabel style = Ui.label(label, ColorScheme.MEDIUM_GRAY_COLOR);
		style.setPreferredSize(new Dimension(0, ROW_CONTENT_HEIGHT));
		style.setHorizontalAlignment(SwingConstants.CENTER);
		cycle.add(style, BorderLayout.CENTER);

		cycle.add(arrow("›", "Next style", part, 1), BorderLayout.EAST);

		row.add(cycle, BorderLayout.CENTER);

		// Colours come from the game's own palette tables - every choice here is a
		// colour the game can genuinely produce, applied exactly as the client does.
		// Jaw follows hair, arms follow torso, hands follow skin, as in the game.
		if (part == KitType.HAIR || part == KitType.TORSO
			|| part == KitType.LEGS || part == KitType.BOOTS)
		{
			int colorSlot = colorSlotFor(part);
			row.add(swatch(colorSlot, name, GamePalette.table(colorSlot)), BorderLayout.EAST);
		}
		else
		{
			// The swatch's room, kept, so the arrows line up down the column.
			row.add(javax.swing.Box.createRigidArea(new Dimension(20, 20)), BorderLayout.EAST);
		}

		return row;
	}

	/** One of a body row's style arrows: a step back or forward through the part's kits. */
	private JButton arrow(String text, String tooltip, KitType part, int step)
	{
		JButton arrow = button(text, tooltip, () -> onCycleKit.accept(part, step));
		arrow.setMargin(new java.awt.Insets(0, 2, 0, 2));
		arrow.setPreferredSize(new Dimension(18, ROW_CONTENT_HEIGHT));
		return arrow;
	}

	/** "head" as a label shows it: "Head". */
	private static String sentenceCase(String word)
	{
		return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
	}

	/** A clickable colour square showing the current palette choice. */
	private JPanel swatch(int colorSlot, String title, short[] table)
	{
		JPanel square = new JPanel();
		square.setPreferredSize(new Dimension(20, 20));
		square.setBackground(new Color(HslColor.toRgb(table[paletteIndex(colorSlot)])));
		square.setBorder(BorderFactory.createLineBorder(ColorScheme.DARK_GRAY_COLOR, 1));
		square.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		square.setToolTipText(title + " colour: the game's " + table.length + " exact choices");
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
		JPanel grid = Ui.panel(new GridLayout(0, 8, 3, 3));

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
			Ui.relayout(resultList);
			return;
		}

		// The cache-dumper detail belongs in the log, not in front of a user
		// who has never heard of the build tooling.
		if (!repository.isLoaded() || !repository.hasNames())
		{
			resultList.add(hint("Item data didn't"
				+ " load - try restarting the client. If it keeps happening,"
				+ " report a bug."));
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
			repeatedNames = repeated(pendingResults.stream().map(item -> item.name)
				.collect(java.util.stream.Collectors.toList()));

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

		Ui.relayout(resultList);
	}

	/** Builds the next slice of pending matches into rows. */
	private void appendResults(int count)
	{
		for (int i = 0; i < count && !pendingResults.isEmpty(); i++)
		{
			resultList.add(buildResultRow(pendingResults.pollFirst()));
		}
		Ui.relayout(resultList);
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

		JLabel name = Ui.label(item.name, Color.WHITE);
		// Zero preferred width: BorderLayout's centre takes whatever is left
		// over at layout time, and the label ellipsises rather than demanding
		// room for the full name. The tooltip carries the untruncated text.
		name.setPreferredSize(new Dimension(0, 20));
		row.add(name, BorderLayout.CENTER);

		// Two rows with the same name looked identical; the id tells them apart.
		if (repeatedNames.contains(item.name))
		{
			row.add(Ui.label("#" + item.id, ColorScheme.MEDIUM_GRAY_COLOR), BorderLayout.EAST);
		}

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

	/** The names that appear more than once. */
	static java.util.Set<String> repeated(Iterable<String> names)
	{
		java.util.Set<String> seen = new java.util.HashSet<>();
		java.util.Set<String> repeated = new java.util.HashSet<>();
		for (String name : names)
		{
			if (!seen.add(name))
			{
				repeated.add(name);
			}
		}
		return repeated;
	}

	// ------------------------------------------------------------------ preview

	private void showPreview(ModelRepository.WearableItem item)
	{
		// The body width is what makes long names WRAP; without it the label
		// grows sideways and drags the sidebar's layout with it.
		previewName.setText("<html><body style='width:" + (PREVIEW_TEXT_WIDTH - 4) + "px'><b>"
			+ Ui.escape(item.name) + "</b><br>id " + item.id + "</body></html>");
		preview.setVisible(true);

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
		preview.setVisible(false);
	}

	/** A grey line in the results, wrapped to the picker's width. */
	private JLabel hint(String text)
	{
		return Ui.label(Ui.html(150, text), ColorScheme.MEDIUM_GRAY_COLOR);
	}
}

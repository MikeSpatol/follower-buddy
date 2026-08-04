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
	private static final int MAX_RESULTS = 60;
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

	private final JPanel gearGrid = new JPanel();
	private final JPanel bodyList = new JPanel();
	private final JPanel picker = new JPanel();
	private final JPanel resultList = new JPanel();
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
		BiConsumer<Integer, Integer> onBodyColor)
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

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildHeader(), BorderLayout.NORTH);
		add(buildContent(), BorderLayout.CENTER);

		showPicker(null);
	}

	// ------------------------------------------------------------------ building

	private JPanel buildHeader()
	{
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Follower outfit");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(LEFT_ALIGNMENT);
		header.add(title);
		header.add(javax.swing.Box.createVerticalStrut(6));

		JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.setAlignmentX(LEFT_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

		JButton copy = new JButton("Copy my gear");
		copy.setToolTipText("Fill every slot from what you are currently wearing");
		copy.setFocusPainted(false);
		copy.addActionListener(e -> onCopyGear.run());
		buttons.add(copy);

		JButton clear = new JButton("Clear all");
		clear.setToolTipText("Strip the follower back to the default body");
		clear.setFocusPainted(false);
		clear.addActionListener(e -> onClearAll.run());
		buttons.add(clear);

		header.add(buttons);

		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		status.setAlignmentX(LEFT_ALIGNMENT);
		status.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		header.add(status);

		return header;
	}

	private JPanel buildContent()
	{
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);

		content.add(sectionHeader("EQUIPMENT", "Click a slot to change it; right-click to clear it"));

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
		showAll.setToolTipText("1,042 wearable items have no equipment-slot data (mostly "
			+ "quest and holiday items). Tick this to include them.");
		showAll.setFont(FontManager.getRunescapeSmallFont());
		showAll.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		showAll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		showAll.setAlignmentX(LEFT_ALIGNMENT);
		showAll.addActionListener(e -> refreshResults());
		picker.add(showAll);

		picker.add(buildPreview());

		resultList.setLayout(new GridLayout(0, 1, 0, 2));
		resultList.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane scroll = new JScrollPane(resultList,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setPreferredSize(new Dimension(PANEL_WIDTH, 240));
		scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 240));
		scroll.setAlignmentX(LEFT_ALIGNMENT);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		picker.add(scroll);

		content.add(picker);

		content.add(sectionHeader("BODY", "Styles cycle live on the follower; colours are the game's own"));
		bodyList.setLayout(new GridLayout(0, 1, 0, 2));
		bodyList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bodyList.setAlignmentX(LEFT_ALIGNMENT);
		content.add(bodyList);

		return content;
	}

	private JPanel sectionHeader(String text, String tooltip)
	{
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrap.setAlignmentX(LEFT_ALIGNMENT);
		wrap.setBorder(BorderFactory.createEmptyBorder(10, 0, 4, 0));
		wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setToolTipText(tooltip);
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
		wrapper.add(previewName, BorderLayout.CENTER);

		return wrapper;
	}

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
		bodyList.add(buildSkinRow());
		for (KitType part : BODY_PARTS)
		{
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
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));

		JLabel name = new JLabel("body type");
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		name.setPreferredSize(new Dimension(64, 20));
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
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));

		JLabel name = new JLabel("skin");
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		name.setPreferredSize(new Dimension(64, 20));
		row.add(name, BorderLayout.WEST);

		row.add(swatch(4, "skin", GamePalette.SKIN), BorderLayout.EAST);
		return row;
	}

	/** One body part: cycle its kit, and set its colour. */
	private JPanel buildBodyRow(KitType part)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));

		JLabel name = new JLabel(part.name().toLowerCase());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		name.setPreferredSize(new Dimension(64, 20));
		row.add(name, BorderLayout.WEST);

		// Kits have no names in the cache, so they're cycled and judged by eye - the
		// follower updates live, which makes it its own preview.
		JPanel cycle = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
		cycle.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JButton prev = new JButton("‹");
		prev.setToolTipText("Previous style");
		prev.setFocusPainted(false);
		prev.setMargin(new java.awt.Insets(0, 6, 0, 6));
		prev.addActionListener(e -> onCycleKit.accept(part, -1));
		cycle.add(prev);

		java.util.List<Integer> choices = repository.kitsFor(part, current.getGender());
		int index = current.isKit(part) ? choices.indexOf(current.kitId(part)) : -1;
		String label = KitNames.label(part, index, choices.size());

		JLabel style = new JLabel(label);
		style.setFont(FontManager.getRunescapeSmallFont());
		style.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		style.setToolTipText(label + "  (kit id "
			+ (current.isKit(part) ? current.kitId(part) : -1) + ")");
		style.setPreferredSize(new Dimension(96, 20));
		style.setHorizontalAlignment(SwingConstants.CENTER);
		cycle.add(style);

		JButton next = new JButton("›");
		next.setToolTipText("Next style");
		next.setFocusPainted(false);
		next.setMargin(new java.awt.Insets(0, 6, 0, 6));
		next.addActionListener(e -> onCycleKit.accept(part, 1));
		cycle.add(next);

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

		if (activeSlot == null)
		{
			resultList.revalidate();
			resultList.repaint();
			return;
		}

		if (!repository.isLoaded())
		{
			resultList.add(hint("No model dump loaded - run tools/cache-dumper"));
		}
		else if (!repository.hasNames())
		{
			resultList.add(hint("Dump has no item names - re-run tools/cache-dumper"));
		}
		else
		{
			List<ModelRepository.WearableItem> matches =
				repository.search(search.getText(), MAX_RESULTS * 6);

			boolean filter = !slotIndex.isEmpty() && !showAll.isSelected();
			int shown = 0;

			for (ModelRepository.WearableItem item : matches)
			{
				// Only offer items that belong in this slot. Until the index is built
				// everything is offered, so the picker still works immediately.
				if (filter && slotIndex.get(item.id) != activeSlot)
				{
					continue;
				}
				resultList.add(buildResultRow(item));
				if (++shown >= MAX_RESULTS)
				{
					break;
				}
			}

			if (shown == 0)
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
		}

		resultList.revalidate();
		resultList.repaint();
	}

	private JPanel buildResultRow(ModelRepository.WearableItem item)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
		row.setToolTipText("Click to equip (id " + item.id + ")");
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

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
		previewName.setText("<html><b>" + item.name + "</b><br>id " + item.id + "</html>");

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
}

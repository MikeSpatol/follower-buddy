package com.follower.ui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import net.runelite.client.ui.laf.RuneLiteLAF;
import org.junit.After;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Messages window: what each rule is called and what is said about it,
 * where the list opens, and how a Find that leaves a few rows lays them out.
 * The window tests need a display and are skipped without one.
 */
public class PhrasesDialogTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final List<PhrasesDialog.Category> CATEGORIES = Arrays.asList(
		new PhrasesDialog.Category("Items", "gear", "Said when you equip something.", false),
		new PhrasesDialog.Category("Locations", "area", "Said in places.", true),
		new PhrasesDialog.Category("Bosses", "boss", "Said about bosses.", false));

	@Test
	public void aBossIsNamedWithoutTheCategorysPrefix()
	{
		// The Bosses category says so already, as Items and Locations do.
		assertEquals("Vorkath", PhrasesDialog.prettyName("boss-vorkath"));
		assertEquals("Crazy arch", PhrasesDialog.prettyName("boss-crazy-arch"));
		assertEquals("Whip", PhrasesDialog.prettyName("gear-whip"));
		assertEquals("Lumbridge", PhrasesDialog.prettyName("area-lumbridge"));
		assertEquals("Kill boss celebrate", PhrasesDialog.prettyName("kill-boss-celebrate"));
	}

	@Test
	public void aRulesOwnTitleWins()
	{
		assertEquals("King Black Dragon",
			PhrasesDialog.titleOf(rule("{'id':'boss-kbd','title':'King Black Dragon'}")));
		assertEquals("Kbd", PhrasesDialog.titleOf(rule("{'id':'boss-kbd'}")));
		assertEquals("a blank title is no title", "Kbd", PhrasesDialog.titleOf(rule("{'id':'boss-kbd','title':'  '}")));
	}

	@Test
	public void theWindowShowsTheAboutAndNeverTheNote()
	{
		assertEquals("Wearing a whip.",
			PhrasesDialog.aboutOf(rule("{'id':'gear-whip','note':'R12: ids 4151.','about':'Wearing a whip.'}")));
		// QA2, 15 Oct: with no about, the authors' note was shown instead.
		assertEquals("no about, no line", null,
			PhrasesDialog.aboutOf(rule("{'id':'gear-whip','note':'R12: ids 4151.'}")));
		assertEquals("a blank about is no about", null,
			PhrasesDialog.aboutOf(rule("{'id':'gear-whip','note':'R12: ids 4151.','about':' '}")));
		assertEquals(null, PhrasesDialog.aboutOf(rule("{'id':'gear-whip'}")));
	}

	@Test
	public void theListOpensAtItsTopAndGoesBackThereOnEverySwitch() throws Exception
	{
		// QA, 15 Oct: each say area's caret queued a scroll to itself, and
		// the last one decided where the list opened - near the bottom.
		// The carets only scroll a list that has been laid out by the time
		// they run, so this opens the window as the plugin does, built and
		// opened in one go, and switches with a layout already pending, as
		// in a window on screen.
		Assume.assumeFalse(GraphicsEnvironment.isHeadless());
		PhrasesDialog dialog = dialog(true);
		JScrollPane scroll = (JScrollPane) field(dialog, "scroll");
		settle(dialog);
		assertEquals("opened at the top", 0, scroll.getViewport().getViewPosition().y);
		assertTrue("and there is somewhere else it could have been",
			scroll.getViewport().getView().getHeight() > scroll.getViewport().getHeight());

		JComboBox<?> picker = (JComboBox<?>) field(dialog, "categoryPicker");
		for (int i : new int[]{2, 1, 0})
		{
			SwingUtilities.invokeAndWait(() ->
			{
				dialog.getRootPane().revalidate();
				picker.setSelectedIndex(i);
			});
			settle(dialog);
			assertEquals(CATEGORIES.get(i).name, 0, scroll.getViewport().getViewPosition().y);
		}
		SwingUtilities.invokeAndWait(dialog::dispose);
	}

	@Test
	public void aFindLeavingFewRowsKeepsThemTheirOwnHeight() throws Exception
	{
		Assume.assumeFalse(GraphicsEnvironment.isHeadless());
		PhrasesDialog dialog = dialog(true);
		JTextField find = (JTextField) field(dialog, "find");
		SwingUtilities.invokeAndWait(() -> find.setText("whip"));
		settle(dialog);
		JPanel list = (JPanel) field(dialog, "list");
		int shown = 0;
		for (Component row : list.getComponents())
		{
			if (row.isVisible())
			{
				shown++;
				assertEquals("a row is as tall as it needs, not stretched to fill",
					row.getPreferredSize().height, row.getHeight());
			}
		}
		assertTrue(shown > 0 && shown < 4);
		SwingUtilities.invokeAndWait(dialog::dispose);
	}

	@Test
	public void everyRowsControlsStayInsideTheWindow() throws Exception
	{
		// QA2, 15 Oct: the list was as wide as its widest row, and a scroll
		// pane that never scrolls sideways left On, Sounds and Delete past
		// the right edge on every row of Quest NPCs and Locations. Every
		// group in the shipped file gets a category of its own here, the
		// place category with its Delete and Regions too.
		Assume.assumeFalse(GraphicsEnvironment.isHeadless());
		JsonObject shipped = new Gson().fromJson(new String(Files.readAllBytes(
			Paths.get("src/main/resources/com/follower/default-phrases.json")),
			java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
		java.util.Set<String> groups = new java.util.TreeSet<>();
		for (com.google.gson.JsonElement r : shipped.getAsJsonArray("rules"))
		{
			if (r.getAsJsonObject().has("group"))
			{
				groups.add(r.getAsJsonObject().get("group").getAsString());
			}
		}
		List<PhrasesDialog.Category> all = new java.util.ArrayList<>();
		for (String g : groups)
		{
			all.add(new PhrasesDialog.Category(g, g, "Said.", "area".equals(g)));
		}
		PhrasesDialog dialog = dialog(false, all);
		dialog.setOnEditSounds((t, l) -> { });
		SwingUtilities.invokeAndWait(dialog::open);
		JScrollPane scroll = (JScrollPane) field(dialog, "scroll");
		JComboBox<?> picker = (JComboBox<?>) field(dialog, "categoryPicker");
		for (int i = 0; i < all.size(); i++)
		{
			int at = i;
			SwingUtilities.invokeAndWait(() -> picker.setSelectedIndex(at));
			settle(dialog);
			int width = scroll.getViewport().getWidth();
			Component view = scroll.getViewport().getView();
			assertEquals(all.get(i).name + ": the list is as wide as the window", width, view.getWidth());
			List<javax.swing.AbstractButton> buttons = new java.util.ArrayList<>();
			collectButtons((java.awt.Container) view, buttons);
			assertTrue(all.get(i).name + " has rows", !buttons.isEmpty());
			for (javax.swing.AbstractButton b : buttons)
			{
				java.awt.Point p = SwingUtilities.convertPoint(b.getParent(), b.getLocation(), view);
				assertTrue(all.get(i).name + ": '" + b.getText() + "' ends at " + (p.x + b.getWidth())
					+ ", past the window's " + width, p.x + b.getWidth() <= width);
			}
		}
		SwingUtilities.invokeAndWait(dialog::dispose);
	}

	@Test
	public void aSwitchOfCategoryListsFromTheFileAsOpenedAndReloadReadsItAgain() throws Exception
	{
		// QA pass 5, #71: every switch read and parsed the whole file again,
		// most of half a second on the event thread for Quest NPCs.
		Assume.assumeFalse(GraphicsEnvironment.isHeadless());
		PhrasesDialog dialog = dialog(true);
		Files.write((Path) field(dialog, "file"), "{".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		JComboBox<?> picker = (JComboBox<?>) field(dialog, "categoryPicker");
		SwingUtilities.invokeAndWait(() -> picker.setSelectedIndex(2));
		assertFalse("listed without reading the file again", ((List<?>) field(dialog, "rows")).isEmpty());

		java.lang.reflect.Method refresh = PhrasesDialog.class.getDeclaredMethod("refresh");
		refresh.setAccessible(true);
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				refresh.invoke(dialog);
			}
			catch (ReflectiveOperationException e)
			{
				throw new IllegalStateException(e);
			}
		});
		assertTrue("Reload does read it", ((List<?>) field(dialog, "rows")).isEmpty());
		SwingUtilities.invokeAndWait(dialog::dispose);
	}

	@Test
	public void aPlaceLineWithAQuestOrAChanceStillHasItsRegions() throws Exception
	{
		// QA2, 15 Oct: the fun- place lines keep their place inside an "all"
		// beside a chance and a quest, and read as "triggered by something
		// other than a place". Their regions show, and an edit goes back there.
		Assume.assumeFalse(GraphicsEnvironment.isHeadless());
		PhrasesDialog dialog = dialog(true);
		JComboBox<?> picker = (JComboBox<?>) field(dialog, "categoryPicker");
		SwingUtilities.invokeAndWait(() -> picker.setSelectedIndex(1));
		settle(dialog);
		JTextField regions = null;
		for (Object row : (List<?>) field(dialog, "rows"))
		{
			Field id = row.getClass().getDeclaredField("id");
			id.setAccessible(true);
			if ("fun-lumbridge-after-cooks-assistant".equals(id.get(row)))
			{
				Field f = row.getClass().getDeclaredField("regions");
				f.setAccessible(true);
				regions = (JTextField) f.get(row);
			}
		}
		assertTrue("the row has its regions", regions != null);
		assertEquals("12850", regions.getText());

		JTextField edited = regions;
		SwingUtilities.invokeAndWait(() -> edited.setText("12850, 12851"));
		java.lang.reflect.Method save = PhrasesDialog.class.getDeclaredMethod("save");
		save.setAccessible(true);
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				assertEquals(true, save.invoke(dialog));
			}
			catch (ReflectiveOperationException e)
			{
				throw new IllegalStateException(e);
			}
		});
		Path file = (Path) field(dialog, "file");
		JsonObject saved = new Gson().fromJson(new String(Files.readAllBytes(file),
			java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
		for (com.google.gson.JsonElement r : saved.getAsJsonArray("rules"))
		{
			JsonObject rule = r.getAsJsonObject();
			if ("fun-lumbridge-after-cooks-assistant".equals(rule.get("id").getAsString()))
			{
				JsonObject when = rule.getAsJsonObject("when");
				assertEquals("all", when.get("type").getAsString());
				assertEquals("[12850,12851]", when.getAsJsonArray("conditions").get(0).getAsJsonObject()
					.getAsJsonArray("regions").toString());
				assertTrue("and nowhere else", !when.has("regions"));
			}
		}
		SwingUtilities.invokeAndWait(dialog::dispose);
	}

	private static void collectButtons(java.awt.Container root, List<javax.swing.AbstractButton> out)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof javax.swing.AbstractButton)
			{
				out.add((javax.swing.AbstractButton) c);
			}
			else if (c instanceof java.awt.Container)
			{
				collectButtons((java.awt.Container) c, out);
			}
		}
	}

	/** The look the window was built under, put back afterwards. */
	private LookAndFeel before;

	@After
	public void restoreTheLook() throws Exception
	{
		if (before != null)
		{
			LookAndFeel look = before;
			SwingUtilities.invokeAndWait(() ->
			{
				try
				{
					UIManager.setLookAndFeel(look);
				}
				catch (Exception e)
				{
					throw new IllegalStateException(e);
				}
			});
		}
	}

	private PhrasesDialog dialog(boolean open) throws Exception
	{
		return dialog(open, CATEGORIES);
	}

	private PhrasesDialog dialog(boolean open, List<PhrasesDialog.Category> categories) throws Exception
	{
		// The client's own look: its text areas' carets are what scrolled.
		before = UIManager.getLookAndFeel();
		SwingUtilities.invokeAndWait(RuneLiteLAF::setup);
		Path file = folder.newFile("phrases.json").toPath();
		Files.copy(Paths.get("src/main/resources/com/follower/default-phrases.json"), file,
			java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		PhrasesDialog[] made = new PhrasesDialog[1];
		SwingUtilities.invokeAndWait(() ->
		{
			made[0] = new PhrasesDialog(new Gson(), file, categories, () -> 12850)
			{
				@Override
				public void setVisible(boolean b)
				{
					// Laid out and measured, never put on screen.
				}
			};
			if (open)
			{
				made[0].open();
			}
		});
		return made[0];
	}

	/** Runs what the window queued, a few rounds deep, then lays it out. */
	private static void settle(PhrasesDialog dialog) throws Exception
	{
		for (int i = 0; i < 4; i++)
		{
			SwingUtilities.invokeAndWait(() -> { });
		}
		SwingUtilities.invokeAndWait(dialog::validate);
		for (int i = 0; i < 4; i++)
		{
			SwingUtilities.invokeAndWait(() -> { });
		}
	}

	private static Object field(Object owner, String name) throws Exception
	{
		Field f = PhrasesDialog.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(owner);
	}

	private static JsonObject rule(String json)
	{
		return new Gson().fromJson(json, JsonObject.class);
	}
}

package com.follower;

import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.FontManager;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * QA3: Messages, Conversations and Sounds could be dragged narrower than
 * their fixed-width text, Conversations opened taller than a 720 work area,
 * and in Messages a long title was cut along with its id. Laid out and
 * measured; no window is put on screen.
 */
public class WindowSizesTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void theThreeEditorsCannotBeDraggedNarrowerThanTheirText() throws Exception
	{
		Assume.assumeFalse(GraphicsEnvironment.isHeadless());
		Path phrases = folder.newFile("phrases.json").toPath();
		Files.copy(Paths.get("src/main/resources/com/follower/default-phrases.json"), phrases,
			java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		SwingUtilities.invokeAndWait(() ->
		{
			PhrasesDialog messages = new PhrasesDialog(new Gson(), phrases, Arrays.asList(
				new PhrasesDialog.Category("Quest NPCs", "quest", "Said.", false)), () -> 0);
			assertEquals(new Dimension(620, 400), messages.getMinimumSize());
			messages.dispose();

		});
	}

	@Test
	public void aLongTitleIsShownWholeAndOnlyTheIdGivesWay() throws Exception
	{
		Assume.assumeFalse(GraphicsEnvironment.isHeadless());
		Path phrases = folder.newFile("phrases.json").toPath();
		Files.copy(Paths.get("src/main/resources/com/follower/default-phrases.json"), phrases,
			java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		List<PhrasesDialog.Category> categories = Arrays.asList(
			new PhrasesDialog.Category("Quest NPCs", "quest", "Said.", false),
			new PhrasesDialog.Category("Locations", "area", "Said.", true));
		PhrasesDialog[] made = new PhrasesDialog[1];
		SwingUtilities.invokeAndWait(() ->
		{
			made[0] = new PhrasesDialog(new Gson(), phrases, categories, () -> 0)
			{
				@Override
				public void setVisible(boolean b)
				{
				}
			};
			made[0].open();
		});
		PhrasesDialog dialog = made[0];
		JComboBox<?> picker = (JComboBox<?>) field(dialog, "categoryPicker");
		List<String> cut = new ArrayList<>();
		int[] measured = {0};
		for (int i = 0; i < categories.size(); i++)
		{
			int at = i;
			SwingUtilities.invokeAndWait(() -> picker.setSelectedIndex(at));
			for (int k = 0; k < 4; k++)
			{
				SwingUtilities.invokeAndWait(() -> { });
			}
			SwingUtilities.invokeAndWait(dialog::validate);
			SwingUtilities.invokeAndWait(() ->
			{
				for (JLabel title : titles(dialog.getContentPane()))
				{
					measured[0]++;
					if (title.getWidth() < title.getPreferredSize().width)
					{
						cut.add(title.getText() + " (" + title.getWidth() + " of "
							+ title.getPreferredSize().width + ")");
					}
				}
			});
		}
		SwingUtilities.invokeAndWait(dialog::dispose);
		assertTrue("titles measured", measured[0] > 100);
		assertTrue("titles cut at the opening size: " + cut, cut.isEmpty());
	}

	/** The rows' titles: the bold labels in a row's title-and-id line. */
	private static List<JLabel> titles(Container root)
	{
		List<JLabel> out = new ArrayList<>();
		for (Component c : root.getComponents())
		{
			if (c instanceof JLabel && ((JLabel) c).getFont() == FontManager.getRunescapeBoldFont()
				&& root.getLayout() instanceof javax.swing.BoxLayout)
			{
				out.add((JLabel) c);
			}
			else if (c instanceof Container)
			{
				out.addAll(titles((Container) c));
			}
		}
		return out;
	}

	private static Object field(Object owner, String name) throws Exception
	{
		// Fields the two file editors share live on their base class.
		java.lang.reflect.Field f;
		try
		{
			f = PhrasesDialog.class.getDeclaredField(name);
		}
		catch (NoSuchFieldException e)
		{
			f = EditorDialog.class.getDeclaredField(name);
		}
		f.setAccessible(true);
		return f.get(owner);
	}
}

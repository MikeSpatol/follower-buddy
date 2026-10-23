package com.follower.ui;

import com.follower.appearance.ModelRepository;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The side panel's outfit profiles. New profile... over a name already in
 * the list asks first, and the plugin's setters reach the dropdowns and the
 * Mute box on the event thread whichever thread calls them (QA3).
 */
public class ProfilePanelTest
{
	// public:cut outfit-profiles
	private static final List<String> NAMES =
		Arrays.asList("Default follower", "Melee", "Ranged", "Magic", "Fishing");

	private final List<String> saved = new ArrayList<>();
	private final List<String> asked = new ArrayList<>();
	private boolean answer;

	@Test
	public void aNameAlreadyTakenIsReplacedOnlyWhenThePlayerSaysSo() throws Exception
	{
		FollowerPanel panel = panel();
		SwingUtilities.invokeAndWait(() ->
		{
			panel.newProfile("Mining");
			assertEquals("a new name asks nothing", Arrays.asList("Mining"), saved);
			assertTrue(asked.isEmpty());

			answer = false;
			panel.newProfile(" Fishing ");
			assertEquals("asked about the profile it would write over", Arrays.asList("Fishing"), asked);
			assertEquals("and No keeps it", Arrays.asList("Mining"), saved);

			answer = true;
			panel.newProfile("Fishing");
			assertEquals("Yes replaces it", Arrays.asList("Mining", "Fishing"), saved);

			// public:cut thrall-outfits
			// A thrall look is not offered for replacing; the plugin refuses it.
			asked.clear();
			panel.newProfile("Melee");
			assertTrue("not asked about", asked.isEmpty());
			assertEquals("Melee", saved.get(saved.size() - 1));

			// public:end thrall-outfits
			saved.clear();
			panel.newProfile("   ");
			panel.newProfile(null);
			assertTrue("blank or cancelled is nothing", saved.isEmpty());
		});
	}

	// public:end outfit-profiles
	@Test
	public void theDropdownsAndMuteAreChangedOnlyOnTheEventThread() throws Exception
	{
		FollowerPanel panel = panel();
		List<String> offThread = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			for (java.awt.Component c : all(panel))
			{
				if (c instanceof JComboBox && ((JComboBox<?>) c).getModel() instanceof javax.swing.DefaultComboBoxModel)
				{
					((JComboBox<?>) c).getModel().addListDataListener(new ListDataListener()
					{
						@Override
						public void intervalAdded(ListDataEvent e)
						{
							note(offThread, "combo add");
						}

						@Override
						public void intervalRemoved(ListDataEvent e)
						{
							note(offThread, "combo remove");
						}

						@Override
						public void contentsChanged(ListDataEvent e)
						{
							note(offThread, "combo select");
						}
					});
				}
				if (c instanceof JCheckBox)
				{
					((JCheckBox) c).getModel().addChangeListener(e -> note(offThread, "box"));
				}
			}
		});

		// As a profile switch does: from a thread that is not Swing's.
		panel.setProfileNames(Arrays.asList("Default follower", "Melee", "Woodcutting"), "Woodcutting"); // public:cut outfit-profiles
		panel.setMuted(true);
		SwingUtilities.invokeAndWait(() -> { });

		assertTrue("Swing touched off its thread: " + offThread, offThread.isEmpty());
		// public:cut outfit-profiles
		SwingUtilities.invokeAndWait(() -> assertEquals("and still applied", "Woodcutting",
			profileCombo(panel).getSelectedItem()));
		// public:end outfit-profiles
	}

	private static void note(List<String> offThread, String what)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			synchronized (offThread)
			{
				offThread.add(what + " on " + Thread.currentThread().getName());
			}
		}
	}

	private FollowerPanel panel() throws Exception
	{
		FollowerPanel[] made = new FollowerPanel[1];
		SwingUtilities.invokeAndWait(() ->
		{
			made[0] = new FollowerPanel(null, new ModelRepository(new Gson()),
				(a, b) -> { }, k -> { }, () -> { }, () -> { }, g -> { }, (k, d) -> { }, (c, p) -> { },
				"outfit") // public:cut outfit-profiles
				// public:else "outfit");
			// public:cut outfit-profiles
			{
				@Override
				boolean confirmReplace(String name)
				{
					asked.add(name);
					return answer;
				}
			};
			made[0].setOnProfileSave(saved::add);
			made[0].setProfileProtected(n -> Arrays.asList("Default follower", "Melee", "Ranged", "Magic").contains(n)); // public:cut thrall-outfits
			// public:end outfit-profiles
		});
		made[0].setProfileNames(NAMES, "Fishing"); // public:cut outfit-profiles
		SwingUtilities.invokeAndWait(() -> { });
		return made[0];
	}
	// public:cut outfit-profiles

	private static JComboBox<?> profileCombo(FollowerPanel panel)
	{
		try
		{
			java.lang.reflect.Field f = FollowerPanel.class.getDeclaredField("profileCombo");
			f.setAccessible(true);
			return (JComboBox<?>) f.get(panel);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException(e);
		}
	}
	// public:end outfit-profiles

	private static List<java.awt.Component> all(java.awt.Container root)
	{
		List<java.awt.Component> out = new ArrayList<>();
		for (java.awt.Component c : root.getComponents())
		{
			out.add(c);
			if (c instanceof java.awt.Container)
			{
				out.addAll(all((java.awt.Container) c));
			}
		}
		return out;
	}
}

package com.follower;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * message-categories.tsv holds the Messages window's categories, so a slip
 * in it must fail here. The list as the code built it before it moved there
 * is kept below as it stood: the file must give the same categories in the
 * same order, name, groups, hint and all.
 */
public class MessageCategoriesFileTest
{
	/** The categories FollowerPlugin built before they moved, verbatim. */
	private static List<PhrasesDialog.Category> before()
	{
		return java.util.Arrays.asList(
			new PhrasesDialog.Category("Items", "gear",
				"Said when you equip something it has an opinion on, and when your"
					+ " bag is nearly full.",
				false),
			new PhrasesDialog.Category("Locations", "area",
				"Said on entering a place, and about places it"
					+ " remembers. Region ids are editable and Add location makes a new one.",
				true),
			new PhrasesDialog.Category("Bosses", "boss",
				"Said when that boss appears nearby, and at boss kills and kill counts.",
				false),
			new PhrasesDialog.Category("Combat", "combat",
				"Said while it stands clear and watches you fight, and when something"
					+ " big comes near.",
				false),
			new PhrasesDialog.Category("Statuses", "health",
				"Your HP, prayer, poison, venom, skull and run energy.",
				false),
			new PhrasesDialog.Category("Idle chatter", "idle",
				"Small talk while nothing much is happening.",
				false),
			new PhrasesDialog.Category("Quest NPCs", "quest",
				"Said near famous quest figures, at moments partway through some"
					+ " quests, and when you finish one.",
				false),
			new PhrasesDialog.Category("Errands", "errand",
				"Each errand has a line for setting off and one for coming"
					+ " back. Go on errands in settings turns them off.",
				false),
			new PhrasesDialog.Category("Mimicry", "mimic",
				"Said now and then while it copies your emotes, spells or teleports,"
					+ " or eats when you eat.",
				false),
			new PhrasesDialog.Category("Reminiscing", "memory",
				"Things that happened before, the outings it was and was not"
					+ " taken on, and how long it has known you.",
				false),
			new PhrasesDialog.Category("The hour and the session", "clock",
				"What time it is where you are, how long you have been playing,"
					+ " and the odd date.",
				false),
			new PhrasesDialog.Category("Reactions", "reactions",
				"Deaths, drops, thieving, level-ups, being thanked or looked"
					+ " at, and what goes on in the chat.",
				false),
			new PhrasesDialog.Category("Thrall", "thrall",
				"Said as it takes a thrall's place and when the thrall's time"
					+ " runs out. Mute the thrall in settings silences them.",
				false),
			new PhrasesDialog.Category("Everything else", "misc",
				"Hellos, goodbyes, milestones and the rest. To silence them all,"
					+ " add misc to Silence groups or rule ids by name in settings.",
				false));
	}

	@Test
	public void theFileGivesTheCategoriesTheCodeGave()
	{
		List<PhrasesDialog.Category> before = before();
		List<PhrasesDialog.Category> now = PhrasesDialog.categories();
		assertEquals(before.size(), now.size());
		for (int i = 0; i < before.size(); i++)
		{
			PhrasesDialog.Category was = before.get(i);
			PhrasesDialog.Category is = now.get(i);
			assertEquals(was.name, is.name);
			assertEquals(was.name, was.groups, is.groups);
			assertEquals(was.name, was.hint, is.hint);
			assertEquals(was.name, was.structural, is.structural);
		}
	}
}

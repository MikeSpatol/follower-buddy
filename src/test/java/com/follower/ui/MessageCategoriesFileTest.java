// public:file messages-window
package com.follower.ui;

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
			new com.follower.ui.PhrasesDialog.Category("Items", "gear",
				"Said when you equip something it has an opinion on, and when your"
					+ " bag is nearly full.", // public:cut messages-readonly
					// public:else + " bag is nearly full."),
				false), // public:cut messages-readonly
			new com.follower.ui.PhrasesDialog.Category("Locations", "area",
				"Said on entering a place, and about places it likes, dislikes or"
					+ " remembers. Region ids are editable and Add location makes a new one.", // public:cut messages-readonly
					// public:else + " remembers. Region ids are editable and Add location makes a new one."),
				true), // public:cut messages-readonly
			new com.follower.ui.PhrasesDialog.Category("Bosses", "boss",
				"Said when that boss appears nearby, and at boss kills and kill counts.", // public:cut messages-readonly
				// public:else "Said when that boss appears nearby, and at boss kills and kill counts."),
				false), // public:cut messages-readonly
			new com.follower.ui.PhrasesDialog.Category("Combat", "combat",
				"Said while it stands clear and watches you fight, and when something"
					+ " big comes near.", // public:cut messages-readonly
					// public:else + " big comes near."),
				false), // public:cut messages-readonly
			new com.follower.ui.PhrasesDialog.Category("Statuses", "health",
				"Your HP, prayer, poison, venom, skull and run energy.", // public:cut messages-readonly
				// public:else "Your HP, prayer, poison, venom, skull and run energy."),
				false), // public:cut messages-readonly
			new com.follower.ui.PhrasesDialog.Category("Idle chatter", "idle",
				"Small talk while nothing much is happening.", // public:cut messages-readonly
				// public:else "Small talk while nothing much is happening."),
				false), // public:cut messages-readonly
			new com.follower.ui.PhrasesDialog.Category("Quest NPCs", "quest",
				"Said near famous quest figures, at moments partway through some"
					+ " quests, and when you finish one.", // public:cut messages-readonly
					// public:else + " quests, and when you finish one."),
				false), // public:cut messages-readonly
			// public:cut errands
			new com.follower.ui.PhrasesDialog.Category("Errands", "errand",
				"Each errand has a line for setting off and one for coming"
					+ " back. Go on errands in settings turns them off.", // public:cut messages-readonly
					// public:else + " back. Go on errands in settings turns them off."),
				false), // public:cut messages-readonly
			// public:end errands
			new com.follower.ui.PhrasesDialog.Category("Mimicry", "mimic",
				"Said now and then while it copies your emotes, spells or teleports,"
					+ " or eats when you eat.", // public:cut messages-readonly
					// public:else + " or eats when you eat."),
				false), // public:cut messages-readonly
			new com.follower.ui.PhrasesDialog.Category("Reminiscing", "memory",
				"Things that happened before, the outings it was and was not"
					+ " taken on, and how long it has known you.", // public:cut messages-readonly
					// public:else + " taken on, and how long it has known you."),
				false), // public:cut messages-readonly
			// public:cut souvenirs-wishes
			new com.follower.ui.PhrasesDialog.Category("Souvenirs", "souvenir",
				"Things it picks up, carries about and loses.", // public:cut messages-readonly
				// public:else "Things it picks up, carries about and loses."),
				false), // public:cut messages-readonly
			// public:end souvenirs-wishes
			// public:cut side-games
			new com.follower.ui.PhrasesDialog.Category("Predictions", "bet",
				"Its calls on the next drop and its bets on you, and how it takes"
					+ " being wrong.", // public:cut messages-readonly
					// public:else + " being wrong."),
				false), // public:cut messages-readonly
			// public:end side-games
			new com.follower.ui.PhrasesDialog.Category("The hour and the session", "clock",
				"What time it is where you are, how long you have been playing,"
					+ " and the odd date.", // public:cut messages-readonly
					// public:else + " and the odd date."),
				false), // public:cut messages-readonly
			new com.follower.ui.PhrasesDialog.Category("Reactions", "reactions",
				"Deaths, drops, thieving, level-ups, being thanked or looked"
					+ " at, and what goes on in the chat.", // public:cut messages-readonly
					// public:else + " at, and what goes on in the chat."),
				false), // public:cut messages-readonly
			new com.follower.ui.PhrasesDialog.Category("Thrall", "thrall",
				"Said as it takes a thrall's place and when the thrall's time"
					+ " runs out. Mute the thrall in settings silences them.", // public:cut messages-readonly
					// public:else + " runs out. Mute the thrall in settings silences them."),
				false), // public:cut messages-readonly
			new com.follower.ui.PhrasesDialog.Category("Everything else", "misc",
				"Hellos, goodbyes, milestones and the rest. To silence them all,"
					+ " add misc to Silence groups or rule ids by name in settings.", // public:cut messages-readonly
					// public:else + " add misc to Silence groups or rule ids by name in settings."));
				false)); // public:cut messages-readonly
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
			assertEquals(was.name, was.structural, is.structural); // public:cut messages-readonly
		}
	}
}

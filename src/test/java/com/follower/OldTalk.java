package com.follower;

/**
 * The everyday Talk-to script and the day summary as FollowerPlugin built
 * them in Java before talk.json, copied verbatim (its public markers included), so
 * TalkScriptEquivalenceTest can prove the file builds the same conversation.
 * Three things differ, none in what is built: the joke method reference names
 * this class, the pool is package-private for the test, and the three
 * per-visit nodes are made as says().dynamic(pages), which is what the
 * removed Node.saysDynamic(pages) did.
 */
final class OldTalk
{
	private OldTalk()
	{
	}

	private static FollowerDialog.Node says(String... pages)
	{
		return FollowerDialog.Node.says(pages);
	}

	private static FollowerDialog.Node you(String... pages)
	{
		return FollowerDialog.Node.you(pages);
	}

	static String[] daySummary(int minutes, int kills, int levels, int deaths,
		String moodBand, String incident)
	{
		java.util.List<String> parts = new java.util.ArrayList<>();

		if (minutes >= 1)
		{
			// A scribe does not round 95 minutes down to "1 hour" - the
			// half hour happened and it was there for it.
			int hours = minutes / 60;
			int rest = minutes % 60;
			parts.add(hours == 0
				? minutes + (minutes == 1 ? " minute" : " minutes")
				: hours + (hours == 1 ? " hour" : " hours")
					+ (rest == 0 ? "" : " and " + rest
						+ (rest == 1 ? " minute" : " minutes")));
		}

		if (kills > 0)
		{
			parts.add(kills + (kills == 1 ? " thing killed" : " things killed"));
		}
		if (levels > 0)
		{
			parts.add(levels + (levels == 1 ? " level" : " levels"));
		}
		if (deaths > 0)
		{
			parts.add(deaths + (deaths == 1 ? " death" : " deaths"));
		}

		java.util.List<String> pages = new java.util.ArrayList<>();
		if (parts.isEmpty())
		{
			pages.add("Today's page is blank. Restful, I call that.");
		}
		else
		{
			pages.add("Today's page: " + join(parts) + ".");
		}

		// The mood is the follower's own verdict on all that, which is a
		// different thing from the figures and worth saying separately.
		switch (moodBand == null ? "" : moodBand)
		{
			case "low":
				pages.add("I've had better days, if I'm honest.");
				break;
			case "down":
				pages.add("Not our finest, but we're still walking.");
				break;
			case "good":
				pages.add("A good day, that. I'd take another like it.");
				break;
			case "high":
				pages.add("One of the good ones. I mean that.");
				break;
			default:
				pages.add("An ordinary day. There's nothing wrong with those.");
				break;
		}

		// And the one thing it has not let go of.
		if (incident != null && !incident.isEmpty())
		{
			pages.add("I'm still thinking about " + incident + ".");
		}
		return pages.toArray(new String[0]);
	}

	private static String join(java.util.List<String> parts)
	{
		if (parts.size() == 1)
		{
			return parts.get(0);
		}
		return String.join(", ", parts.subList(0, parts.size() - 1))
			+ " and " + parts.get(parts.size() - 1);
	}

	static final String[][] JOKES = {
		{"Why did the Wise Old Man rob the bank of Draynor?",
			"Because that's where the money was."},
		{"What do you call an adventurer who takes their whole bank into the Wilderness?",
			"A benefactor."},
		{"Why did the chicken cross the road?",
			"In Lumbridge? It never got the chance."},
		{"I tried talking to a camel in Al Kharid once.",
			"Apparently you need a special amulet. He made his opinion clear without one."},
	};

	private static int lastJoke = -1;

	private static String[] nextJoke()
	{
		int pick;
		do
		{
			pick = java.util.concurrent.ThreadLocalRandom.current().nextInt(JOKES.length);
		}
		while (pick == lastJoke && JOKES.length > 1);
		lastJoke = pick;
		return JOKES[pick];
	}

	static java.util.Map<String, FollowerDialog.Node> talkScript(
		java.util.function.Supplier<String[]> summary,
		java.util.function.Consumer<String> onAnswer,
		String moodBand)
	{
		java.util.Map<String, FollowerDialog.Node> script =
			new java.util.LinkedHashMap<>();

		// When the follower is not itself, "How have you been?" sharpens into
		// "You all right?" - same slot, same spirit, pointed by state. The
		// research calls this answering its mood, and it is the one thing a
		// player can DO about a low band besides witness it.
		boolean lowish = "low".equals(moodBand) || "down".equals(moodBand);
		String howLabel = lowish ? "You all right?" : "How have you been?";
		String howTarget = lowish ? "allright-q" : "how-q";

		// While a wish is open the five-slot box retires who-are-you, not the
		// small talk: who-a stays reachable from the returning hub later, but
		// the jokes and advice are the perishable first-hour charm, and a
		// 45-minute wish window used to wall them off from the opening screen.
		script.put("start", says("Yes?").choices(
				"Who are you, exactly?", "who-q",
				"What is it you actually do?", "do-q",
				howLabel, howTarget,
				"Let's just talk.", "chat-q",
				"Never mind.", "bye-q"));

		// The returning hub, without the greeting. Five options is the most
		// the box has measured spacing for, so while a wish is open the hub
		// retires who-are-you to make room - somebody coming BACK
		// mid-conversation knows who it is.
		script.put("menu", says().choices(
				"Who are you, exactly?", "who-q",
				"What is it you actually do?", "do-q",
				howLabel, howTarget,
				"Let's just talk.", "chat-q",
				"That's all for now.", "done-q"));

		// ------------------------------------------------ answering its mood
		// Asking is itself the kindness: reaching the answer node is what
		// counts (latched on arrival, like every answer), so closing the box
		// early still asked. The lift arrives from the comforted rule; the
		// pages differ by band because "not the best day" and "middling" are
		// different admissions. Either way the usual how-menu follows - a low
		// day does not lock away the notebook.
		if (lowish)
		{
			script.put("allright-q", you("You all right?").then("allright-a"));
			script.put("allright-a", "low".equals(moodBand)
				? says(
					"Honestly? Not the best day in the ledger.",
					"But you noticed. That's worth an entry of its own.")
					.onFinish(() -> onAnswer.accept("comforted"))
					.then("how-menu")
				: says(
					"Middling. The ink's been thicker.",
					"Asking helps. Don't tell anyone I said that.")
					.onFinish(() -> onAnswer.accept("comforted"))
					.then("how-menu"));
		}

		// Shared closings. Both are spoken, like every other option.
		script.put("bye-q", you("Never mind.").then("bye"));
		script.put("done-q", you("That's all for now.").then("bye"));
		script.put("back-q", you("Back to business.").then("menu"));
		script.put("bye", says("Right you are. One step behind."));

		// ------------------------------------------------ who are you
			script.put("who-q", you("Who are you, exactly?").then("who-a"));
			script.put("who-a", says(
				"Now there's a question.",
				"A scribe. Somebody has to write all this down.")
				.then("who-b"));
			script.put("who-b", says(
				"You dress me, I walk behind you, and I keep quiet about your bank.")
				.choices(
					"Writing what, exactly?", "who-me-q",
					"Do you have a name?", "who-name-q",
					"Don't you get tired of following me?", "who-tired-q",
					"Back to business.", "back-q"));

			script.put("who-menu", says()
				.choices(
					"Writing what, exactly?", "who-me-q",
					"Do you have a name?", "who-name-q",
					"Don't you get tired of following me?", "who-tired-q",
					"Back to business.", "back-q"));

			script.put("who-me-q", you("Writing what, exactly?").then("who-me-a"));
			script.put("who-me-a", says(
				"Everything. Roads, rulers, prices, what lives under things.",
				"It's a long project. Nobody's asked to read it.")
				.then("who-menu"));

			script.put("who-name-q", you("Do you have a name?").then("who-name-a"));
			script.put("who-name-a", says(
				"Whatever you've been calling me. I'd have picked my own, but nobody asked.",
				"It's grown on me.")
				.then("who-menu"));

			script.put("who-tired-q", you("Don't you get tired of following me?").then("who-tired-a"));
			script.put("who-tired-a", says(
				"Tired? I once watched you stand at a furnace for three hours.",
				"Nothing has tired me since.")
				.then("who-menu"));

		// ------------------------------------------------ what do you do
		script.put("do-q", you("What is it you actually do?").then("do-a"));
		script.put("do-a", says("Walk behind you. It's more involved than it sounds.")
			.then("do-menu"));
		script.put("do-menu", says()
			.choices(
				"Tell me about the walking.", "do-follow-q",
				"What do you do when I'm fighting?", "do-fight-q",
				"And when I'm working?", "do-work-q",
				"Can you dance?", "do-emote-q",
				"That's all I needed.", "do-done-q"));
		script.put("do-done-q", you("That's all I needed.").then("menu"));

		script.put("do-follow-q", you("Tell me about the walking.").then("do-follow-a"));
		script.put("do-follow-a", says(
			"One tile behind. Corners, doorways, stairs, running: I keep up.",
			"Where you go I go, teleports included. Same swirl, half a step later.")
			.then("do-follow-b"));
		script.put("do-follow-b", says(
			"Tell me to Stay and I'll hold the spot until you want me back.",
			"Or hold Shift, right-click a tile and pick Send. I'll walk there myself.")
			.then("do-menu"));

		script.put("do-fight-q", you("What do you do when I'm fighting?").then("do-fight-a"));
		script.put("do-fight-a", says(
			"Get out of the way, mainly. Then watch.",
			"If something enormous is near, I mention it first. For all the good that does.")
			.then("do-fight-b"));
		script.put("do-fight-b", says(
			"And when it goes down I make a fuss. You've usually earned it by then.")
			.choices(
				"What about my thralls?", "do-thrall-q",
				"Good to know.", "do-fight-done-q"));
		script.put("do-fight-done-q", you("Good to know.").then("do-menu"));

		// The thrall styles are measured, not guessed: zombie ids are melee,
		// skeleton ranged, ghost magic, and each has its own outfit profile in
		// the settings. Worth saying out loud - the follower turning up to a
		// mage fight in melee gear is the one thing about this that looks
		// broken rather than deliberate.
		script.put("do-thrall-q", you("What about my thralls?").then("do-thrall-a"));
		script.put("do-thrall-a", says(
			"Raise one of those Arceuus things and I'll take its place.",
			"Same walking, same swinging, far better dressed.")
			.then("do-thrall-b"));
		script.put("do-thrall-b", says(
			"Three sorts, mind. Zombie, skeleton and ghost, for melee, ranged and magic.")
			.then("do-thrall-c"));
		script.put("do-thrall-c", says(
			"Set me an outfit for each in the settings.",
			"Otherwise I turn up to a mage fight holding a greataxe and we both look foolish.")
			.then("do-menu"));

		script.put("do-work-q", you("And when I'm working?").then("do-work-a"));
		script.put("do-work-a", says(
			"Depends what you're at.",
			"If it's pockets, I give you room and watch the street until you're done.")
			.then("do-work-b"));
		script.put("do-work-b", says(
			"Trees, rocks, fishing spots. I set up beside you and do a bit myself.",
			"Now and then I wander off on an errand of my own. I always come back.")
			.then("do-menu"));

		script.put("do-emote-q", you("Can you dance?").then("do-emote-a"));
		script.put("do-emote-a", says(
			"Ask and find out. Right-click me for a wave or a dance, whatever the moment wants.",
			"Or do one yourself and I'll join in a beat behind. I'm not a mirror. I have timing.")
			.then("do-menu"));

		// ------------------------------------------------ the inner life
		// The everyday entrance; on a lowish day allright-q replaces it (and
		// leads to the same menu), so it is only built when reachable.
		if (!lowish)
		{
			script.put("how-q", you("How have you been?").then("how-a"));
			script.put("how-a", says(
				"That depends on the day. You've given me some days.")
				.then("how-menu"));
		}
		script.put("how-menu", says()
			.choices(
				"How did today go?", "how-today-q",
				"You keep track of things?", "how-count-q",
				"You have moods?", "how-mood-q",
				"Anywhere you'd rather be?", "how-place-q",
				"Back to business.", "back-q"));

		// The one page in the script that is different every time it is read.
		// Everything in it is already counted - the tallies exist for the
		// milestone lines - so this is the same memory said as a sentence
		// rather than as a number, which is what makes it sound like somebody
		// who was there rather than a scoreboard.
		script.put("how-today-q", you("How did today go?").then("how-today-a"));
		script.put("how-today-a", FollowerDialog.Node
			.says().dynamic(summary)
			.then("how-menu"));

		script.put("how-count-q", you("You keep track of things?").then("how-count-a"));
		script.put("how-count-a", says(
			"It's the job. Every rat, every level, every time you've gone down in front of me.",
			"It isn't judgement. Somebody should, that's all.")
			.then("how-count-b"));
		script.put("how-count-b", says(
			"I remember it between days, too. You come back and the number's still there.")
			.then("how-menu"));

		script.put("how-mood-q", you("You have moods?").then("how-mood-a"));
		script.put("how-mood-a", says(
			"I do. You'd know if you looked up.",
			"When it's gone well I stand one way. When it hasn't, I stand another.")
			.then("how-mood-b"));
		script.put("how-mood-b", says(
			"It passes. Everything does, if you keep walking.")
			.then("how-menu"));

		script.put("how-place-q", you("Anywhere you'd rather be?").then("how-place-b"));
		script.put("how-place-b", says(
			"If I ever ask you to take me somewhere, come and talk to me and I'll say where.",
			"I won't ask twice. It's not that sort of arrangement.")
			.then("how-menu"));

		// ------------------------------------------------ small talk
		script.put("chat-q", you("Let's just talk.").then("chat-a"));
		script.put("chat-a", says("My favourite duty.").then("chat-menu"));
		script.put("chat-menu", says()
			.choices(
				"Seen anything interesting?", "chat-seen-q",
				"What do you think of my outfit?", "chat-outfit-q",
				"Any advice?", "advice-q",
				"Tell me a joke.", "chat-joke-q",
				"Back to business.", "back-q"));

		script.put("chat-seen-q", you("Seen anything interesting?").then("chat-seen-a"));
		script.put("chat-seen-a", says(
			"Mostly the back of your head.",
			"It's a fine head. It could carry a better hat.")
			.then("chat-menu"));

		script.put("chat-outfit-q", you("What do you think of my outfit?").then("chat-outfit-a"));
		script.put("chat-outfit-a", says(
			"Anyone who dresses their follower this well has taste.",
			"The rest of your wardrobe I couldn't comment on.")
			.then("chat-menu"));

		// Every visit re-rolls from the pool (never the same joke twice in a
		// row), and the loop lets you keep asking. Both loop options get their
		// own spoken node so the label and the line stay identical.
		script.put("chat-joke-q", you("Tell me a joke.").then("chat-joke-a"));
		script.put("chat-joke-a", FollowerDialog.Node
			.says().dynamic(OldTalk::nextJoke)
			.choices(
				"Heh. Got another one?", "chat-joke2-q",
				"That's terrible.", "chat-groan-q"));

		script.put("chat-joke2-q", you("Heh. Got another one?").then("chat-joke2-a"));
		script.put("chat-joke2-a", FollowerDialog.Node
			.says().dynamic(OldTalk::nextJoke)
			.choices(
				"Another!", "chat-joke3-q",
				"That's terrible.", "chat-groan-q"));

		script.put("chat-joke3-q", you("Another!").then("chat-joke2-a"));

		script.put("chat-groan-q", you("That's terrible.").then("chat-groan-a"));
		script.put("chat-groan-a", says(
			"I've been saving that one since Lumbridge.",
			"There's more where it came from. Choose your next question carefully.")
			.then("chat-menu"));

		// ------------------------------------------------ advice
		script.put("advice-q", you("Any advice?").then("advice-a"));
		script.put("advice-a", says(
			"Don't take the detour for the cabbage. It is never worth it.")
			.then("advice-b"));
		script.put("advice-b", says(
			"A stranger offering to trim your armour is no barber.")
			.then("advice-c"));
		script.put("advice-c", says(
			"And bank before you think you need to. A gravestone is not storage.")
			.then("advice-d"));
		script.put("advice-d", says(
			"That's the lot. The rest I've learned to keep to myself.")
			.then("chat-menu"));

		return script;
	}
}

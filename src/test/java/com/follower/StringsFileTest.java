package com.follower;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * strings.properties holds the longer captions, tooltips and status lines the
 * code used to spell out in place, so a slip in it must fail here rather than
 * show a player different words.
 *
 * <p>Each text is held to the Java literal it replaced, word for word (below,
 * as it stood, split where it was split); the file holds exactly those keys;
 * and the code reads exactly the keys the file holds, so none reads a key
 * that is not there and comes back null.
 */
public class StringsFileTest
{
	/** Every text in strings.properties, as the literal it replaced, verbatim. */
	private static final Map<String, String> OLD = new LinkedHashMap<>();

	static
	{
		// FollowerPlugin
		OLD.put("noName", "No name for now, then. You can change the name"
			+ " any time in the plugin's settings, under Follower.");
		OLD.put("nameLater", "You can change the name any time in the"
			+ " plugin's settings, under Follower.");
		OLD.put("cacheUnread", "The game cache hasn't been read yet. Log in, then try ::follower rebuild.");
		OLD.put("onlyYourScreen", ". Everything it does happens on your screen, and nothing is sent to the game.");
		OLD.put("phrasesUnreadable", "Couldn't read phrases.json, so the follower is quiet until it's fixed.");
		OLD.put("nameTooLong", "Twenty characters is all a name can carry,"
			+ " so '");
		OLD.put("blankPage", "Today's page is blank. Restful, I call that.");
		OLD.put("noRoomInFront", "There's no room in front of you for me to stand.");
		OLD.put("sendHint", "You can also hold Shift and right-click any tile to Send ");
		OLD.put("namePrompt", "Your new follower is waiting. What will you call it?");
		OLD.put("noModel", "No follower model to place. Try ::follower rebuild.");
		OLD.put("useRegion", ". Use that number under Locations in the Messages window.");
		// public:cut errands
		OLD.put("errandsOff", "Errands are off. Tick Go on errands under Errands to send it on one.");
		// public:end errands
		// public:cut outfit-profiles,thrall-outfits
		OLD.put("builtInReplace", "' is one of the built-in looks"
			+ " and cannot be replaced. Pick it and edit it instead.");
		OLD.put("builtInDelete", "' is one of the built-in looks"
			+ " and cannot be deleted. Edit it instead.");
		// public:end outfit-profiles,thrall-outfits
		// public:cut posing-preview
		OLD.put("gfxAddSet", " on the follower."
			+ " Add 'set' to keep it as the shield.");
		OLD.put("gfxHeightHelp", "::follower gfx <graphicId> <height> - that far up its body");
		OLD.put("gfxHelp", "::follower gfx <graphicId> - play a spotanim on the follower");
		OLD.put("gfxNumbers", "Numbers only: ::follower gfx <graphicId> [height|set]");
		OLD.put("animUsage", "Usage: ::follower anim <id> [id ...]");
		// public:end posing-preview
		// public:cut posing-preview,spectate,spectate-keep-fight-lines,spectate-shield
		OLD.put("gfxSetHelp", "::follower gfx <graphicId> set - and keep it as the shield");
		OLD.put("shieldSet", " is now the shield effect for summon, channel and finish.");
		// public:end posing-preview,spectate,spectate-keep-fight-lines,spectate-shield
		// DialogTree
		OLD.put("answerOneWord", "'; an answer is a single plain word for a rule to match on");
		OLD.put("noAnswers", ": no node answers anything, so nothing can come of it");
		// RuleLoader
		OLD.put("ruleIncomplete", "' needs a 'when' block and at least one of"
			+ " a non-empty 'say' list, an 'animation', or 'mirrorAnimation'");
		// DialogsDialog
		// public:cut dialogs-editor
		OLD.put("dialogsHint", "<html><body style='width:500px'>These are the conversations the follower"
			+ " <b>starts</b>. While it is waiting for an answer, its Talk-to option becomes"
			+ " Answer and opens the conversation it asked about instead of the everyday one.<br>"
			+ "<b>Says</b> is one page per line, spoken by whoever is picked beside the node. <b>Choices</b> are one"
			+ " per line, written <code>label -&gt; node-id</code>. <b>Answer</b> is what"
			+ " reaching that node records as your reply.</html>");
		OLD.put("dialogsUnreadable", "The conversations file couldn't be read - it may have been"
			+ " hand-edited. Fix it or restore a backup, then Reload.");
		OLD.put("addNodeTip", "A new step in this conversation. Point something at it"
			+ " with Next or a Choice, or nothing will ever reach it.");
		OLD.put("deleteNodeTip", "Remove this node. Anything pointing at it will need"
			+ " repointing, and Save will say so.");
		// public:end dialogs-editor
		// FollowerPanel
		OLD.put("behaviourHint", " in game for Talk-to, Stay, Dance, Wave, Step-in-front and Read-chronicle."
			+ " Hold Shift and right-click any tile to send it there.");
		OLD.put("identityCaption", "Only you"
			+ " can see this follower. Dress it under Outfit; edit what it says"
			+ " under Talking; see what it remembers under Memory.");
		OLD.put("showAllTip", "Some wearable items (mostly quest and holiday ones)"
			+ " don't say which slot they belong to. Tick to include them in every search.");
		OLD.put("itemsUnloaded", "Item data didn't"
			+ " load - try restarting the client. If it keeps happening,"
			+ " report a bug.");
		OLD.put("muteTip", "The follower stops talking. All your other"
			+ " speech settings are kept. Same switch as Mute in settings.");
		OLD.put("chatboxTip", "Every line also appears in the chatbox under the"
			+ " follower's name. Same switch as in settings.");
		OLD.put("talkingCaption", "What it says and when. Messages holds every line;"
			+ " Conversations the talks it starts itself.");
		OLD.put("stayTip", "Same as right-click Stay and Follow-me on the follower,"
			+ " and Follow me in settings");
		OLD.put("fixTip", "Re-attaches the follower to the scene, then rebuilds it from the outfit");
		OLD.put("chattinessTip", "How much it talks. Same setting as Chattiness in settings.");
		OLD.put("slotTip", "Click a slot to change it; right-click to clear it.");
		OLD.put("stylesTip", "Styles cycle live on the follower; colours are the game's own");
		OLD.put("commandsHint", "Type any of these in the chat as ::follower <command>");
		OLD.put("bodyTip", "Switches the whole body model, and the default kits with it");
		OLD.put("reloadTip", "Re-reads phrases.json and the attached sounds from disk");
		OLD.put("noMatches", " items match - try \"Show items from every slot\"");
		// public:cut dialogs-editor
		OLD.put("conversationsTip", "View and edit the conversations the follower starts itself -"
			+ " the ones its Answer option opens while it waits for your reply");
		// public:end dialogs-editor
		// public:cut errands
		OLD.put("errandsTip", "Now and then it wanders off on a small errand and"
			+ " comes back. Same switch as Go on errands in settings.");
		// public:end errands
		// public:cut memory-window
		OLD.put("memoryCaption", "What it remembers about you. The Reminiscing toggle in"
			+ " settings silences the lines that use this; Forget everything in"
			+ " the window erases it.");
		OLD.put("memoryTip", "Everything the follower knows about you, and the button that erases it");
		// public:end memory-window
		// public:cut messages-window
		OLD.put("messagesTip", "Everything the follower says, by subject, to read and edit");
		// public:end messages-window
		// public:cut outfit-profiles
		OLD.put("deleteProfileTip", "Remove the selected profile. The built-in looks, and a"
			+ " profile a thrall wears, cannot be removed.");
		// public:end outfit-profiles
		// public:cut outfit-profiles,thrall-outfits
		OLD.put("thrallProfilesTip", "Which profile stands in for each thrall. Same"
			+ " three settings as in Thrall mode.");
		// public:end outfit-profiles,thrall-outfits
		// MemoryDialog
		// public:cut memory-window
		OLD.put("forgetConfirm", "Everything it knows is erased for good: counts, bests, places,\n"
			+ "the incident, the day you met, and the drawings in the Chronicle.\n"
			+ "The follower meets you again as a stranger. There is no undo.");
		OLD.put("memoryHint", "Everything the follower currently remembers"
			+ " about you. It is kept with this RuneLite profile's settings, and the"
			+ " button below erases all of it for good.");
		OLD.put("forgetTip", "Erase every count, best, place, the Chronicle's drawings,"
			+ " and the day it met you. It meets you again as a stranger.");
		OLD.put("memoryWhere", "The Reminiscing toggle in settings silences the"
			+ " lines that use this; Forget everything erases it.");
		// public:end memory-window
		// public:cut memory-window,souvenirs-wishes
		OLD.put("wishAnswerFirst", " (answer its question first, then right-click the follower and choose"
			+ " Talk-to to hand it over)");
		OLD.put("wishHandOver", " (right-click the follower and choose Talk-to to hand it over)");
		// public:end memory-window,souvenirs-wishes
		// PhrasesDialog
		// public:cut messages-readonly,messages-window
		OLD.put("notAPlace", "(triggered by something other than a place"
			+ " - the messages can still be edited)");
		OLD.put("whereUnknown", "The game hasn't said where you are yet - log in first.");
		OLD.put("newLocationIncomplete", "new location needs a name, at least one region id and one message");
		OLD.put("notARegion", "' isn't a region id the game uses - skipped");
		// public:end messages-readonly,messages-window
		// public:cut messages-window
		OLD.put("messagesHowTo", "One message per line. Edit, remove or add lines,"
			+ " untick a rule to silence it, then Save - changes reach the follower within a second.");
		OLD.put("messagesUnreadable", "The messages file couldn't be read - it may have been"
			+ " hand-edited. Fix it or restore a backup, then Reload.");
		OLD.put("findTip", "Show only the rules in this category whose title, note or lines"
			+ " contain this");
		// public:end messages-window
	}

	@Test
	public void everyTextReadsAsTheLiteralItReplaced()
	{
		for (Map.Entry<String, String> text : OLD.entrySet())
		{
			assertEquals(text.getKey(), text.getValue(), Resources.text(text.getKey()));
		}
	}

	@Test
	public void theFileHoldsJustThoseTexts()
	{
		assertEquals(new TreeSet<>(OLD.keySet()),
			new TreeSet<>(Resources.properties("strings.properties").stringPropertyNames()));
	}

	@Test
	public void theCodeReadsJustTheKeysTheFileHolds() throws IOException
	{
		Pattern read = Pattern.compile("Resources\\.text\\(\"(\\w+)\"\\)");
		Set<String> keys = new TreeSet<>();
		try (Stream<Path> files = Files.walk(Paths.get("src/main/java")))
		{
			for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator)
			{
				Matcher m = read.matcher(PublicMarkers.strip(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
				while (m.find())
				{
					keys.add(m.group(1));
				}
			}
		}
		assertEquals(new TreeSet<>(OLD.keySet()), keys);
	}
}

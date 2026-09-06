package com.follower;

import com.follower.speech.SpeechOutput;
import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(FollowerConfig.GROUP)
public interface FollowerConfig extends Config
{
	String GROUP = "followerbuddy";

	@ConfigSection(
		name = "Follower",
		description = "The basics. The outfit is dressed in the Follower Buddy side"
			+ " panel, the icon on the right-hand toolbar.",
		position = 0
	)
	String appearanceSection = "appearance";

	@ConfigSection(
		name = "Movement",
		description = "How the follower keeps up",
		position = 1
	)
	String movementSection = "movement";

	@ConfigSection(
		name = "Speech",
		description = "Where the words go and how long they stay.",
		position = 2
	)
	String speechSection = "speech";

	@ConfigSection(
		name = "Sounds",
		description = "Sound files you attach to lines. Nothing ships with the plugin.",
		position = 3
	)
	String soundsSection = "sounds";

	@ConfigSection(
		name = "Phrases",
		description = "Turn whole subjects on or off. To read or edit the lines"
			+ " themselves, open Messages in the Follower Buddy side panel.",
		position = 4
	)
	String groupsSection = "groups";

	@ConfigSection(
		name = "Thrall mode",
		description = "Thralls are the undead helpers from the Arceuus spellbook's"
			+ " Resurrect spells. With this on, the follower stands in for the one"
			+ " you summon and the real thrall is hidden, on your screen only.",
		position = 5
	)
	String thrallSection = "thrall";

	@ConfigSection(
		name = "Errands",
		description = "Little trips the follower makes on its own - banks, altars, cats",
		position = 6
	)
	String errandSection = "errands";

	@ConfigSection(
		name = "Skilling",
		description = "What the follower does while you work a tree, a rock, a spot",
		position = 7
	)
	String skillingSection = "skilling";

	@ConfigSection(
		name = "Combat",
		description = "What the follower does while you are fighting",
		position = 8
	)
	String combatSection = "combat";

	@ConfigSection(
		name = "Advanced: animations and graphics",
		description = "Animation and graphic ids. Preview one with ::follower anim <id>,"
			+ " ::follower gfx <id> or ::follower pose <id>; with Developer commands"
			+ " on, ::follower watch prints ids as they play. The defaults look"
			+ " right; nothing here needs changing.",
		position = 9,
		closedByDefault = true
	)
	String shieldSection = "shield";

	@ConfigSection(
		name = "Developer",
		description = "Diagnostic chat commands used to build the plugin",
		position = 10,
		closedByDefault = true
	)
	String developerSection = "developer";

	@ConfigItem(
		keyName = "spectateCombat",
		name = "Stand clear in combat",
		description = "When a fight starts, the follower walks a few tiles clear and watches,"
			+ " so it is never sitting on top of the boss you are trying to see or click."
			+ " Thrall mode is exempt, being in the fight by definition.",
		section = combatSection,
		position = 0
	)
	default boolean spectateCombat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "spectateShield",
		name = "Cast a shield on bosses",
		description = "While watching a boss fight, the follower occasionally plays a casting"
			+ " animation with a graphic over it, as though warding itself.",
		section = combatSection,
		position = 1
	)
	default boolean spectateShield()
	{
		return true;
	}

	/*
	 * The shield plays in three stages - summoned, held, dispelled - each an
	 * animation and a graphic, either of which may be 0 for none.
	 *
	 * These are settings rather than constants on purpose. A weapon's swing has
	 * a right answer the game will tell you; "what a protective ward looks
	 * like" does not, so the only honest approach is a reasonable default and
	 * an easy way to change it. ::follower watch prints the id of any animation
	 * or graphic as it plays, including other players' with "watch all", and
	 * ::follower gfx <id> set keeps one straight away.
	 */

	@Range(min = 0)
	@ConfigItem(
		keyName = "spectateShieldAnimation",
		name = "Summon animation",
		description = "Animation as the shield goes up. 0 for none."
			+ " Preview any id with ::follower anim <id>.",
		section = shieldSection,
		position = 5
	)
	default int spectateShieldAnimation()
	{
		return 1162;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "spectateShieldGraphic",
		name = "Summon graphic",
		description = "Graphic laid over the summon. 0 for none."
			+ " Audition ids with ::follower gfx <id>.",
		section = shieldSection,
		position = 6
	)
	default int spectateShieldGraphic()
	{
		// The dragon harpoon special's swirl, captured with ::follower watch.
		return 246;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "spectateShieldChannelStart",
		name = "Sit down animation",
		description = "Played once as the follower settles into the channel, before the"
			+ " looping pose takes over. 0 for none.",
		section = shieldSection,
		position = 7
	)
	default int spectateShieldChannelStart()
	{
		// None, deliberately. HUMAN_PRAY (645) was here and produced a visible
		// kneel, stand, kneel: it is a one-shot that ends upright, so the
		// looping pose then had to kneel all over again.
		//
		// The loop does not need help. HUMAN_PRAY_LOOP carries frameStep 5,
		// meaning frames 0-4 play once and only 5-8 repeat - the descent is
		// built into it. Set an id here only for a pose that lacks its own.
		return 0;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "spectateShieldChannelEnd",
		name = "Stand up animation",
		description = "Played as the channel ends, before the finishing cast. 0 for none.",
		section = shieldSection,
		position = 10
	)
	default int spectateShieldChannelEnd()
	{
		// AnimationID.HUMAN_GETUP, 0.40s - back on its feet.
		return 534;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "spectateShieldChannelAnimation",
		name = "Channel animation",
		description = "Looping pose held after the summon, for as long as the follower stands"
			+ " and the fight lasts. Unlike the others this one repeats, so pick something"
			+ " that reads as sustained effort. 0 for none.",
		section = shieldSection,
		position = 8
	)
	default int spectateShieldChannelAnimation()
	{
		// AnimationID.HUMAN_PRAY_LOOP, and it genuinely loops: frameStep 5,
		// meaning the cache says it wraps back to frame 5 rather than ending.
		// That is the distinction worth having for a channel - the previous
		// choice, SITTING_READY, carried frameStep -1 and was never authored to
		// repeat at all.
		return 179;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "spectateShieldChannelGraphic",
		name = "Channel graphic",
		description = "Renewed every ~30s while channelling, since a looping pose shows no"
			+ " particles of its own. 0 for none.",
		section = shieldSection,
		position = 9
	)
	default int spectateShieldChannelGraphic()
	{
		return 246;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "spectateShieldEndAnimation",
		name = "Finish animation",
		description = "Played as the fight ends and the spell is completed. The follower"
			+ " waits for it to finish before walking back. 0 for none.",
		section = shieldSection,
		position = 11
	)
	default int spectateShieldEndAnimation()
	{
		// The same cast as the summon, for now: the spell is released the way
		// it was raised.
		return 1162;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "spectateShieldEndGraphic",
		name = "Finish graphic",
		description = "Graphic as the spell is completed. 0 for none.",
		section = shieldSection,
		position = 12
	)
	default int spectateShieldEndGraphic()
	{
		return 246;
	}

	/**
	 * Gates the diagnostic half of the chat commands.
	 *
	 * <p>The plugin grew a workshop of instruments - model priority dumps,
	 * colour sweeps, animation traces, wrap tuning, the cache and stance audits
	 * - and they were all reachable by anyone who typed them. They are for
	 * building the plugin, not for using it, and a stray one mostly produces a
	 * wall of chat text. The commands people actually have a reason to run
	 * (say, here, anim, outfit, reload, copy, fix, rebuild, errand, status,
	 * where) are never gated.
	 */
	@ConfigItem(
		keyName = "developerMode",
		name = "Developer commands",
		description = "Enable the diagnostic ::follower commands (animation traces,"
			+ " colour sweeps, cachecheck, stanceaudit). Off unless you're working"
			+ " on the plugin itself.",
		section = developerSection,
		position = 0
	)
	default boolean developerMode()
	{
		return false;
	}

	enum ErrandFrequency
	{
		RARE("Rare"),
		OCCASIONAL("Occasional"),
		LIVELY("Lively");

		private final String label;

		ErrandFrequency(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	// ------------------------------------------------------------- appearance

	@ConfigItem(
		keyName = "followerName",
		name = "Follower name",
		description = "What it is called in the dialog box, right-click menu and"
			+ " chat. Its gear, body and colours are set in the Follower Buddy"
			+ " side panel, not here.",
		section = appearanceSection,
		position = 0
	)
	default String followerName()
	{
		return "Follower";
	}

	/**
	 * The outfit itself, edited through the side panel rather than here - hidden
	 * so the raw encoding can't be half-edited into an inconsistent state, while
	 * the stored value keeps its key and survives.
	 */
	@ConfigItem(
		keyName = "customOutfit",
		name = "Custom outfit",
		description = "The follower's outfit; use the side panel to edit it",
		hidden = true
	)
	default String customOutfit()
	{
		// A bare character: no gear, and Outfit fills every empty body slot with
		// the standard character-creation kits, so this is a plain person rather
		// than a floating head. A first install should look like someone who has
		// not been dressed yet, so the user's own choices are the first thing
		// they see the follower wear.
		//
		// This value is rarely the one in play: startUp restores the active
		// outfit profile, which on a fresh install is the empty "Default
		// follower", and that write lands in this same key. It used to read as a
		// full Armadyl set, which was dead weight - overwritten before it could
		// ever render - and misleading about what a new user actually gets.
		return "";
	}

	// --------------------------------------------------------------- movement

	@ConfigItem(
		keyName = "followEnabled",
		name = "Follow me",
		description = "Off parks the follower where it stands. Same as right-click"
			+ " Stay and Follow on the follower. Hold Shift and right-click a tile"
			+ " to send it somewhere in particular.",
		section = appearanceSection,
		position = 1
	)
	default boolean followEnabled()
	{
		return true;
	}

	@Range(min = -64, max = 64)
	@ConfigItem(
		keyName = "verticalOffset",
		name = "Height offset",
		description = "Lifts the follower if its boots sink into the ground. Positive"
			+ " lifts. In small model units; the default 15 lifts the boots just"
			+ " clear. Tune it live with ::follower height <n>.",
		section = shieldSection,
		position = 4
	)
	default int verticalOffset()
	{
		// Determined by eye: the composed origin sits slightly below the feet, so the
		// model needs a small lift to stop the boots clipping into the terrain.
		return 15;
	}

	@ConfigItem(
		keyName = "spawnAnimation",
		name = "Spawn animation",
		description = "Animation played as the follower arrives. Default 715 is the"
			+ " teleport arrival. Comma-separate to chain clips, e.g. \"6723,4191\""
			+ " for a landing followed by a get-up; preview with"
			+ " ::follower anim 6723 4191. Blank or 0 for none.",
		section = shieldSection,
		position = 1
	)
	default String spawnAnimation()
	{
		return "715";
	}

	@ConfigItem(
		keyName = "spawnGraphic",
		name = "Arrival graphic",
		description = "Effect drawn as the follower arrives. Default 1299 is the"
			+ " teleport arrival that goes with animation 715. Blank or 0 for none."
			+ " Preview with ::follower gfx 1299.",
		section = shieldSection,
		position = 2
	)
	default String spawnGraphic()
	{
		return "1299";
	}

	@ConfigItem(
		keyName = "spawnGraphicHeight",
		name = "Arrival graphic height",
		description = "How high up the follower the arrival effect is drawn. 92 wraps"
			+ " the body; 0 draws it at the feet.",
		section = shieldSection,
		position = 3
	)
	default int spawnGraphicHeight()
	{
		return 92;
	}

	@ConfigItem(
		keyName = "hideInPvp",
		name = "Hide in PvP areas",
		description = "Despawns the follower in the Wilderness and other PvP zones so it "
			+ "can't obscure a real player",
		section = appearanceSection,
		position = 4
	)
	default boolean hideInPvp()
	{
		return true;
	}

	// ----------------------------------------------------------------- speech

	/**
	 * Hidden since the overhaul: the engine only ever consulted the overhead
	 * half of this plus mirrorToChat, so choosing Chatbox alone was silence.
	 * It stays as the per-rule fallback the engine reads.
	 */
	@ConfigItem(
		keyName = "defaultOutput",
		name = "Where speech appears",
		description = "Overhead, chatbox, or both. The fallback for rules that do"
			+ " not choose their own place; managed by the plugin",
		hidden = true
	)
	default SpeechOutput defaultOutput()
	{
		return SpeechOutput.OVERHEAD;
	}

	@ConfigItem(
		keyName = "muted",
		name = "Mute",
		description = "The follower stops talking. Everything else keeps working,"
			+ " and your other speech settings are kept.",
		section = appearanceSection,
		position = 2
	)
	default boolean muted()
	{
		return false;
	}

	@Alpha
	@ConfigItem(
		keyName = "speechColor",
		name = "Overhead text colour",
		description = "Colour of the line drawn over the follower's head",
		section = speechSection,
		position = 1
	)
	default Color speechColor()
	{
		return new Color(255, 255, 0);
	}

	// Capped at the same twelve seconds the display itself is capped at. Above
	// that the setting simply lied: the code clamped it and the box went on
	// showing a number that did nothing.
	@Range(min = 500, max = 12000)
	@ConfigItem(
		keyName = "speechDurationMs",
		name = "Shortest time a line stays up",
		description = "In milliseconds; 4000 is four seconds. Longer lines are"
			+ " given longer, at the reading speed below.",
		section = speechSection,
		position = 3
	)
	default int speechDurationMs()
	{
		return 4000;
	}

	@Range(min = 8, max = 40)
	@ConfigItem(
		keyName = "readingSpeed",
		name = "Reading speed",
		description = "Characters per second. Lower it if lines vanish before you"
			+ " finish them; 17 suits most people.",
		section = speechSection,
		position = 4
	)
	default int readingSpeed()
	{
		return 17;
	}

	@Range(min = 0, max = 400)
	@ConfigItem(
		keyName = "speechHeight",
		name = "Overhead text height",
		description = "How far above the follower's feet the line is drawn",
		section = speechSection,
		position = 2
	)
	default int speechHeight()
	{
		return 220;
	}

	@ConfigItem(
		keyName = "chattiness",
		name = "Chattiness",
		description = "How much it talks. Minimal is about one remark a minute at"
			+ " most; Chatty is a running commentary. Occasional is the default.",
		section = appearanceSection,
		position = 3
	)
	default com.follower.speech.Chattiness chattiness()
	{
		// Occasional, not Normal, since 1.4: the first impression was "it
		// talks too much", and a level below Quiet only helps the people who
		// go looking for it. The default is what everybody else hears.
		return com.follower.speech.Chattiness.OCCASIONAL;
	}

	@ConfigItem(
		keyName = "voiceEnabled",
		name = "Play sounds attached to lines",
		description = "Plays the sound file attached to a line as the follower says"
			+ " it. Attach one with the Sounds button next to any rule in Messages,"
			+ " in the side panel; the Sounds window shows the folder to put WAV,"
			+ " AIFF or AU files in and can copy the path.",
		section = soundsSection,
		position = 0
	)
	default boolean voiceEnabled()
	{
		return true;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "voiceVolume",
		name = "Sound volume",
		description = "Volume of those sounds, as a percentage. 0 is silent.",
		section = soundsSection,
		position = 1
	)
	default int voiceVolume()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "mirrorToChat",
		name = "Also show lines in the chatbox",
		description = "Every line also appears in the chatbox under the follower's"
			+ " name. Off by default because it can crowd out the game's own chat.",
		section = speechSection,
		position = 0
	)
	default boolean mirrorToChat()
	{
		return false;
	}

	// ---------------------------------------------------------------- thrall

	@ConfigItem(
		keyName = "thrallMode",
		name = "Replace thralls",
		description = "When you summon an Arceuus thrall, the follower takes its place - moving and attacking as the thrall does",
		section = thrallSection,
		position = 0
	)
	default boolean thrallMode()
	{
		return true;
	}

	@ConfigItem(
		keyName = "thrallMeleeProfile",
		name = "Melee outfit profile",
		description = "Outfit profile it wears in place of a zombie (melee) thrall."
			+ " Type a name from OUTFIT PROFILES in the side panel, or pick it"
			+ " there; if no profile has that name the ready-made Melee one is used.",
		section = thrallSection,
		position = 1
	)
	default String thrallMeleeProfile()
	{
		return "Melee";
	}

	@ConfigItem(
		keyName = "thrallRangedProfile",
		name = "Ranged outfit profile",
		description = "Outfit profile it wears in place of a skeleton (ranged) thrall."
			+ " Type a name from OUTFIT PROFILES in the side panel, or pick it"
			+ " there; if no profile has that name the ready-made Ranged one is used.",
		section = thrallSection,
		position = 2
	)
	default String thrallRangedProfile()
	{
		return "Ranged";
	}

	@ConfigItem(
		keyName = "thrallMagicProfile",
		name = "Magic outfit profile",
		description = "Outfit profile it wears in place of a ghost (magic) thrall."
			+ " Type a name from OUTFIT PROFILES in the side panel, or pick it"
			+ " there; if no profile has that name the ready-made Magic one is used.",
		section = thrallSection,
		position = 3
	)
	default String thrallMagicProfile()
	{
		return "Magic";
	}

	// The three per-style attack animation pickers were removed: the follower
	// now swings whatever its own weapon swings, learned by watching real
	// players, so a typed-in id could only ever contradict the weapon in its
	// hands. The per-style fallbacks live in FollowerPlugin as constants for
	// the case where nothing has been observed yet.

	// --------------------------------------------------------------- errands

	@ConfigItem(
		keyName = "errandsEnabled",
		name = "Go on errands",
		description = "Now and then it wanders off on a small errand and comes back."
			+ " Untick to keep it at your heel.",
		section = errandSection,
		position = 0
	)
	default boolean errandsEnabled()
	{
		return true;
	}

	// The minutes quoted here come from ErrandController.baseScheduleTicks():
	// 3000, 2400 and 1200 game ticks at 0.6s each. Change one, change the other.
	@ConfigItem(
		keyName = "errandFrequency",
		name = "Frequency",
		description = "About every 30 minutes on Rare, 24 on Occasional, 12 on Lively,"
			+ " give or take.",
		section = errandSection,
		position = 1
	)
	default ErrandFrequency errandFrequency()
	{
		return ErrandFrequency.OCCASIONAL;
	}

	@ConfigItem(
		keyName = "errandBank",
		name = "Bank visits",
		description = "A quick trip to a nearby bank booth (::follower errand bank)",
		section = errandSection,
		position = 2
	)
	default boolean errandBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "errandAltar",
		name = "Quick prayers",
		description = "A moment at a nearby altar (::follower errand altar)",
		section = errandSection,
		position = 3
	)
	default boolean errandAltar()
	{
		return true;
	}

	@ConfigItem(
		keyName = "errandFire",
		name = "Warming up",
		description = "Warms its hands at a nearby fire (::follower errand fire)",
		section = errandSection,
		position = 4
	)
	default boolean errandFire()
	{
		return true;
	}

	@ConfigItem(
		keyName = "errandCat",
		name = "Cats",
		description = "Cannot walk past a cat. Will not apologise (::follower errand cat)",
		section = errandSection,
		position = 5
	)
	default boolean errandCat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "errandBootlace",
		name = "Bootlaces",
		description = "Stops to see to a bootlace, then catches up (::follower errand bootlace)",
		section = errandSection,
		position = 6
	)
	default boolean errandBootlace()
	{
		return true;
	}

	@ConfigItem(
		keyName = "errandGlance",
		name = "Did you see that?",
		description = "Wanders a few tiles to investigate nothing in particular"
			+ " (::follower errand glance)",
		section = errandSection,
		position = 7
	)
	default boolean errandGlance()
	{
		return true;
	}

	@ConfigItem(
		keyName = "errandDocument",
		name = "Field notes",
		description = "Stops to write something up in its scroll (::follower errand document)",
		section = errandSection,
		position = 8
	)
	default boolean errandDocument()
	{
		return true;
	}

	@ConfigItem(
		keyName = "errandStudy",
		name = "Field study",
		description = "Walks over to something notable nearby and writes it up"
			+ " (::follower errand study)",
		section = errandSection,
		position = 9
	)
	default boolean errandStudy()
	{
		return true;
	}

	@ConfigItem(
		keyName = "errandExplore",
		name = "Exploring",
		description = "Noses at whatever the place offers - a chest, a trapdoor,"
			+ " a noticeboard - and notes it down, especially somewhere it has"
			+ " just arrived (::follower errand explore)",
		section = errandSection,
		position = 10
	)
	default boolean errandExplore()
	{
		return true;
	}

	// --------------------------------------------------------------- skilling

	@ConfigItem(
		keyName = "keepsCompany",
		name = "Keeps you company while you skill",
		description = "While you work a tree, a rock or a fishing spot it takes a seat"
			+ " off to your side, facing the work, and stays out of the way of"
			+ " your next click. Untick to keep it at your heel.",
		section = skillingSection,
		position = 0
	)
	default boolean keepsCompany()
	{
		return true;
	}

	@ConfigItem(
		keyName = "borrowsTools",
		name = "Borrows your tools",
		description = "Holds a copy of your axe or pickaxe for the session - the one"
			+ " you are wearing, or a bronze one - and gives it back with a bow"
			+ " when you are done.",
		section = skillingSection,
		position = 1
	)
	default boolean borrowsTools()
	{
		return true;
	}

	// ------------------------------------------------------------ rule groups

	@ConfigItem(
		keyName = "groupBoss",
		name = "Bosses",
		description = "Tips, lore and jokes when a boss appears (group \"boss\")."
			+ " Editable under Bosses in Messages.",
		section = groupsSection,
		position = 2
	)
	default boolean groupBoss()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupHealth",
		name = "Statuses",
		description = "Remarks on your HP, prayer, poison, venom, skull and run energy"
			+ " (group \"health\"). Editable under Statuses in Messages.",
		section = groupsSection,
		position = 4
	)
	default boolean groupHealth()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupArea",
		name = "Locations",
		description = "Comments on entering towns, dungeons and landmarks (group \"area\")."
			+ " Editable under Locations in Messages.",
		section = groupsSection,
		position = 1
	)
	default boolean groupArea()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupIdle",
		name = "Idle chatter",
		description = "Small talk while nothing much is happening (group \"idle\")."
			+ " Editable under Idle chatter in Messages.",
		section = groupsSection,
		position = 5
	)
	default boolean groupIdle()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupGear",
		name = "Items",
		description = "Comments when you equip notable items (group \"gear\")."
			+ " Editable under Items in Messages.",
		section = groupsSection,
		position = 0
	)
	default boolean groupGear()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupQuest",
		name = "Quest NPCs",
		description = "Comments when famous quest figures are nearby (group \"quest\")."
			+ " Editable under Quest NPCs in Messages.",
		section = groupsSection,
		position = 6
	)
	default boolean groupQuest()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupCombat",
		name = "Combat",
		description = "Encouragement while it watches you fight (group \"combat\")."
			+ " Editable under Combat in Messages.",
		section = groupsSection,
		position = 3
	)
	default boolean groupCombat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupMimic",
		name = "Mimicry",
		description = "It copies your emotes and joins you when you eat (group \"mimic\")."
			+ " Editable under Mimicry in Messages.",
		section = groupsSection,
		position = 7
	)
	default boolean groupMimic()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupMemory",
		name = "Reminiscing",
		description = "It brings up things that happened and keeps score of the outings"
			+ " it was and was not taken on (group \"memory\"). Does not stop it"
			+ " remembering; the Memory window in the side panel does that."
			+ " Editable under Reminiscing in Messages.",
		section = groupsSection,
		position = 8
	)
	default boolean groupMemory()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupSouvenir",
		name = "Souvenirs",
		description = "It picks things up, carries them about for a while, and is put"
			+ " out when it loses them (group \"souvenir\")."
			+ " Editable under Souvenirs in Messages.",
		section = groupsSection,
		position = 9
	)
	default boolean groupSouvenir()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupBet",
		name = "Predictions",
		description = "It calls what the next drop is worth before it lands, and has to"
			+ " live with being wrong (group \"bet\")."
			+ " Editable under Predictions in Messages.",
		section = groupsSection,
		position = 10
	)
	default boolean groupBet()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupClock",
		name = "The hour and the session",
		description = "Remarks about what time it is where you are and how long you"
			+ " have been playing (group \"clock\")."
			+ " Editable under The hour and the session in Messages.",
		section = groupsSection,
		position = 11
	)
	default boolean groupClock()
	{
		return true;
	}

	@ConfigItem(
		keyName = "wanderWhenIdle",
		name = "Wander when idle",
		description = "While you stand still, the follower drifts to a nearby tile every"
			+ " twenty seconds or so instead of standing to attention. Stops once it is"
			+ " due to sit down.",
		section = movementSection,
		position = 0
	)
	default boolean wanderWhenIdle()
	{
		return true;
	}

	@ConfigItem(
		keyName = "restWhenIdle",
		name = "Rest when idle",
		description = "After about five minutes of standing still, the follower sits down."
			+ " It gets up the moment anything happens.",
		section = movementSection,
		position = 1
	)
	default boolean restWhenIdle()
	{
		return true;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "restAnimation",
		name = "Rest pose",
		description = "Animation held while it sits. 0 for none. Default 10061 is the"
			+ " game's own sit emote; audition others with ::follower pose <id>.",
		section = shieldSection,
		position = 0
	)
	default int restAnimation()
	{
		// The game's own sit emote, captured off a player performing it with
		// ::follower scan rather than guessed from the constant list. It is
		// rigged for a player by construction, which is the one thing the cache
		// can never tell you - and what sank WINTERTODT_RESTING (headless) and
		// HUMAN_SITTING_CHAIR (turned on the spot) before it.
		//
		// frameStep 8: frames 0-7 are the sit-down, 8-16 the held loop, so one
		// id covers settling AND holding. Same shape as the prayer channel.
		return 10061;
	}

	@ConfigItem(
		keyName = "activeProfile",
		name = "Active outfit profile",
		description = "Which outfit profile the follower is wearing; managed by the panel",
		hidden = true
	)
	default String activeProfile()
	{
		return "";
	}

	@ConfigItem(
		keyName = "exactPalette",
		name = "Exact palette",
		description = "Body colour pairs captured by ::follower palette; managed by the plugin",
		hidden = true
	)
	default String exactPalette()
	{
		return "";
	}

	@ConfigItem(
		keyName = "panelFolds",
		name = "Panel folds",
		description = "Which side panel sections are open; managed by the panel",
		hidden = true
	)
	default String panelFolds()
	{
		return "talking,outfit";
	}

	@ConfigItem(
		keyName = "lastSeenMs",
		name = "Last seen",
		description = "When the follower last saw you, so it can tell a quick"
			+ " relog from an absence; managed by the plugin",
		hidden = true
	)
	default String lastSeenMs()
	{
		return "";
	}

	@ConfigItem(
		keyName = "transcriptOn",
		name = "Transcript",
		description = "Whether the session transcript is running; toggled with"
			+ " ::follower transcript, kept here so it survives a restart",
		hidden = true
	)
	default boolean transcriptOn()
	{
		return false;
	}

	@ConfigItem(
		keyName = "namePrompted",
		name = "Name prompted",
		description = "Whether the one-time naming prompt has been offered;"
			+ " managed by the plugin",
		hidden = true
	)
	default boolean namePrompted()
	{
		return false;
	}

	@ConfigItem(
		keyName = "firstSpawnNoticeShown",
		name = "First spawn notice shown",
		description = "Whether the one-time client-side notice and ::follower hint"
			+ " have been printed; managed by the plugin",
		hidden = true
	)
	default boolean firstSpawnNoticeShown()
	{
		return false;
	}

	@ConfigItem(
		keyName = "sendHintShown",
		name = "Send hint shown",
		description = "Whether the one-time shift-right-click Send hint has been"
			+ " printed; managed by the plugin",
		hidden = true
	)
	default boolean sendHintShown()
	{
		return false;
	}

	@ConfigItem(
		keyName = "wishHintShown",
		name = "Wish hint shown",
		description = "Whether the one-time gift handover hint has been printed;"
			+ " managed by the plugin",
		hidden = true
	)
	default boolean wishHintShown()
	{
		return false;
	}

	@ConfigItem(
		keyName = "attacksRelearnedSkilling",
		name = "Attacks relearned again",
		description = "Whether the second clear of learned attack animations has run,"
			+ " after skilling animations were found among them; managed by the plugin",
		hidden = true
	)
	default boolean attacksRelearnedSkilling()
	{
		return false;
	}

	@ConfigItem(
		keyName = "attacksRelearned",
		name = "Attacks relearned",
		description = "Whether the one-time clear of learned attack animations has"
			+ " run; managed by the plugin",
		hidden = true
	)
	default boolean attacksRelearned()
	{
		return false;
	}

	@ConfigItem(
		keyName = "lastRevision",
		name = "Last game revision",
		description = "The game build the last session ran on, so the follower"
			+ " can notice a weekly update; managed by the plugin",
		hidden = true
	)
	default int lastRevision()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "counters",
		name = "Counters",
		description = "What the follower has counted and the records it holds,"
			+ " so its memory survives a logout; managed by the plugin",
		hidden = true
	)
	default String counters()
	{
		return "";
	}

	@ConfigItem(
		keyName = "traits",
		name = "Traits",
		description = "The places this follower likes and dislikes, rolled once"
			+ " when it first met you; managed by the plugin",
		hidden = true
	)
	default String traits()
	{
		return "";
	}

	@ConfigItem(
		keyName = "disabledGroups",
		name = "Silence groups or phrase ids by name",
		description = "Comma-separated group names or single phrase ids to silence,"
			+ " beyond the toggles in the Phrases section. Each Phrases toggle"
			+ " shows its group name in brackets; phrase ids are shown in the"
			+ " Messages window.",
		section = shieldSection,
		position = 13
	)
	default String disabledGroups()
	{
		return "";
	}
}

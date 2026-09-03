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
		name = "Appearance (outfit: side panel)",
		description = "Only the name lives here. The outfit, body styles and"
			+ " colours are edited visually in the Follower Buddy side panel -"
			+ " its icon is on the right edge of the client, with the other"
			+ " plugin panels",
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
		description = "How phrases are shown",
		position = 2
	)
	String speechSection = "speech";

	@ConfigSection(
		name = "Phrases",
		description = "Switch whole categories of what the follower says on and off",
		position = 3
	)
	String groupsSection = "groups";

	@ConfigSection(
		name = "Thrall mode",
		description = "The follower takes the place of your Arceuus thralls",
		position = 4
	)
	String thrallSection = "thrall";

	@ConfigSection(
		name = "Errands",
		description = "Little trips the follower makes on its own - banks, altars, cats",
		position = 5
	)
	String errandSection = "errands";

	@ConfigSection(
		name = "Combat",
		description = "What the follower does while you are fighting",
		position = 6
	)
	String combatSection = "combat";

	@ConfigSection(
		name = "Shield look (advanced)",
		description = "The exact animations and graphics of the boss-fight shield."
			+ " These are OSRS animation and graphic ids: find one with ::follower"
			+ " watch, preview with ::follower anim <id> or ::follower gfx <id>."
			+ " The defaults look right; nothing here needs changing.",
		position = 7,
		closedByDefault = true
	)
	String shieldSection = "shield";

	@ConfigSection(
		name = "Developer",
		description = "Diagnostic chat commands used to build the plugin",
		position = 8,
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

	@ConfigItem(
		keyName = "spectateShieldAnimation",
		name = "Summon animation",
		description = "Animation as the shield goes up. 0 for none."
			+ " Preview any id with ::follower anim <id>.",
		section = shieldSection,
		position = 0
	)
	default int spectateShieldAnimation()
	{
		return 1162;
	}

	@ConfigItem(
		keyName = "spectateShieldGraphic",
		name = "Summon graphic",
		description = "Graphic laid over the summon. 0 for none."
			+ " Audition ids with ::follower gfx <id>.",
		section = shieldSection,
		position = 1
	)
	default int spectateShieldGraphic()
	{
		// The dragon harpoon special's swirl, captured with ::follower watch.
		return 246;
	}

	@ConfigItem(
		keyName = "spectateShieldChannelStart",
		name = "Sit down animation",
		description = "Played once as the follower settles into the channel, before the"
			+ " looping pose takes over. 0 for none.",
		section = shieldSection,
		position = 2
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

	@ConfigItem(
		keyName = "spectateShieldChannelEnd",
		name = "Stand up animation",
		description = "Played as the channel ends, before the finishing cast. 0 for none.",
		section = shieldSection,
		position = 5
	)
	default int spectateShieldChannelEnd()
	{
		// AnimationID.HUMAN_GETUP, 0.40s - back on its feet.
		return 534;
	}

	@ConfigItem(
		keyName = "spectateShieldChannelAnimation",
		name = "Channel animation",
		description = "Looping pose held after the summon, for as long as the follower stands"
			+ " and the fight lasts. Unlike the others this one repeats, so pick something"
			+ " that reads as sustained effort. 0 for none.",
		section = shieldSection,
		position = 3
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

	@ConfigItem(
		keyName = "spectateShieldChannelGraphic",
		name = "Channel graphic",
		description = "Renewed every ~30s while channelling, since a looping pose shows no"
			+ " particles of its own. 0 for none.",
		section = shieldSection,
		position = 4
	)
	default int spectateShieldChannelGraphic()
	{
		return 246;
	}

	@ConfigItem(
		keyName = "spectateShieldEndAnimation",
		name = "Finish animation",
		description = "Played as the fight ends and the spell is completed. The follower"
			+ " waits for it to finish before walking back. 0 for none.",
		section = shieldSection,
		position = 6
	)
	default int spectateShieldEndAnimation()
	{
		// The same cast as the summon, for now: the spell is released the way
		// it was raised.
		return 1162;
	}

	@ConfigItem(
		keyName = "spectateShieldEndGraphic",
		name = "Finish graphic",
		description = "Graphic as the spell is completed. 0 for none.",
		section = shieldSection,
		position = 7
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
		RARE,
		OCCASIONAL,
		LIVELY
	}

	// ------------------------------------------------------------- appearance

	@ConfigItem(
		keyName = "followerName",
		name = "Follower name",
		description = "Shown in the dialog box, right-click menu, hover text and"
			+ " chat. Everything else about its look - gear, body, colours - is"
			+ " in the Follower Buddy side panel, not here",
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
		description = "When off, the follower stays where it was last placed",
		section = movementSection,
		position = 0
	)
	default boolean followEnabled()
	{
		return true;
	}

	@Range(min = -64, max = 64)
	@ConfigItem(
		keyName = "verticalOffset",
		name = "Height offset",
		description = "Raises the follower out of the ground if its feet are clipped."
			+ " In small model units; the default 15 lifts the boots just clear."
			+ " Positive lifts. Tune it live with ::follower height <n>.",
		section = movementSection,
		position = 8
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
		description = "Animation played when the follower appears; the default 715 is"
			+ " the teleport arrival. Comma-separate to chain clips, e.g. \"6723,4191\""
			+ " for a landing followed by a get-up. Preview with"
			+ " ::follower anim 6723 4191. Blank or 0 for none.",
		section = movementSection,
		position = 5
	)
	default String spawnAnimation()
	{
		return "715";
	}

	@ConfigItem(
		keyName = "spawnGraphic",
		name = "Arrival graphic",
		description = "Spotanim drawn as the follower arrives. The default 1299 is the"
			+ " teleport ARRIVAL, which is what animation 715 above is; 111 is the"
			+ " cast that goes with the vanish, and pairing it here looks"
			+ " mistimed because it is the wrong half of the spell. Audition with"
			+ " ::follower gfx 1299. Blank or 0 for none.",
		section = movementSection,
		position = 6
	)
	default String spawnGraphic()
	{
		return "1299";
	}

	@ConfigItem(
		keyName = "spawnGraphicHeight",
		name = "Arrival graphic height",
		description = "How far up the model the arrival graphic sits. A spotanim is"
			+ " drawn at the feet unless it is lifted, and a teleport wraps the"
			+ " body: 92 is the height the follower's other teleports already use.",
		section = movementSection,
		position = 7
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
		section = movementSection,
		position = 1
	)
	default boolean hideInPvp()
	{
		return true;
	}

	// ----------------------------------------------------------------- speech

	@ConfigItem(
		keyName = "defaultOutput",
		name = "Where speech appears",
		description = "Overhead bubble, chatbox, or both. A few special phrases"
			+ " choose their own place.",
		section = speechSection,
		position = 0
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
		section = speechSection,
		position = 1
	)
	default boolean muted()
	{
		return false;
	}

	@Alpha
	@ConfigItem(
		keyName = "speechColor",
		name = "Text colour",
		description = "Colour of the overhead speech bubble",
		section = speechSection,
		position = 2
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
		name = "Minimum display time (ms)",
		description = "The least time a line stays on screen, in milliseconds"
			+ " (4000 = 4 seconds). A long line is given longer, at the reading"
			+ " speed below.",
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
		name = "Reading speed (chars/sec)",
		description = "How fast you read. Subtitling practice treats 17"
			+ " characters a second as comfortable and 20 as the ceiling;"
			+ " lower it to give every line more time.",
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
		name = "Bubble height",
		description = "Height of the speech bubble above the follower's feet",
		section = speechSection,
		position = 6
	)
	default int speechHeight()
	{
		return 220;
	}

	@ConfigItem(
		keyName = "chattiness",
		name = "Chattiness",
		description = "How often the follower speaks at all. Sets the least"
			+ " gap between any two lines; its mood still stretches or shortens"
			+ " that on top.",
		section = speechSection,
		position = 5
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
		name = "Play attached sounds",
		description = "Play the sound file attached to a line when the follower"
			+ " says it. Attach them in the Messages window; drop your own WAV"
			+ " files into the sounds folder beside phrases.json first",
		section = speechSection,
		position = 8
	)
	default boolean voiceEnabled()
	{
		return true;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "voiceVolume",
		name = "Sound volume",
		description = "How loud the attached sounds play, as a percentage."
			+ " Zero is silence",
		section = speechSection,
		position = 9
	)
	default int voiceVolume()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "mirrorToChat",
		name = "Mirror speech to chatbox",
		description = "Overhead lines also appear in the chatbox as public chat under the"
			+ " follower's name. Off by default: the chatbox is the one place it can"
			+ " crowd out the game, and the overhead bubble is the follower's own.",
		section = speechSection,
		position = 7
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
		description = "Outfit profile worn while replacing a zombie thrall. Must"
			+ " match a profile name from the side panel; Melee, Ranged and Magic"
			+ " come ready-made.",
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
		description = "Outfit profile worn while replacing a skeleton thrall",
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
		description = "Outfit profile worn while replacing a ghost thrall",
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
		name = "Errands",
		description = "Now and then the follower wanders off on a little errand and comes back",
		section = errandSection,
		position = 0
	)
	default boolean errandsEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "errandFrequency",
		name = "Frequency",
		description = "Roughly how often an errand happens: rare ~30 min, occasional ~18, lively ~8",
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
		description = "A quick trip to a nearby bank booth",
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
		description = "A moment at a nearby altar",
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
		description = "Warms its hands at a nearby fire",
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
		description = "Cannot walk past a cat. Will not apologise",
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
		description = "Stops to see to a bootlace, then catches up",
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
		description = "Wanders a few tiles to investigate nothing in particular",
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
		description = "Stops to write something up in its scroll",
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
		description = "Walks over to something notable nearby and writes it up",
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
			+ " just arrived",
		section = errandSection,
		position = 10
	)
	default boolean errandExplore()
	{
		return true;
	}

	// ------------------------------------------------------------ rule groups

	@ConfigItem(
		keyName = "groupBoss",
		name = "Boss phrases",
		description = "Tips, lore and jokes when a boss appears (group \"boss\")",
		section = groupsSection,
		position = 0
	)
	default boolean groupBoss()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupHealth",
		name = "Player status phrases",
		description = "Remarks on your HP, prayer, poison, venom, skull and run energy (group \"health\")",
		section = groupsSection,
		position = 1
	)
	default boolean groupHealth()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupArea",
		name = "Location phrases",
		description = "Comments on entering towns, dungeons and landmarks (group \"area\")",
		section = groupsSection,
		position = 2
	)
	default boolean groupArea()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupIdle",
		name = "Idle chatter",
		description = "Small talk while nothing much is happening (group \"idle\")",
		section = groupsSection,
		position = 3
	)
	default boolean groupIdle()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupGear",
		name = "Item reactions",
		description = "Comments when you equip notable items (group \"gear\")",
		section = groupsSection,
		position = 4
	)
	default boolean groupGear()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupQuest",
		name = "Quest NPC phrases",
		description = "Comments when famous quest figures are nearby (group \"quest\")",
		section = groupsSection,
		position = 5
	)
	default boolean groupQuest()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupCombat",
		name = "Combat phrases",
		description = "Encouragement while it watches you fight (group \"combat\")",
		section = groupsSection,
		position = 6
	)
	default boolean groupCombat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupMimic",
		name = "Mimicry",
		description = "It copies your emotes and joins you when you eat (group \"mimic\")",
		section = groupsSection,
		position = 7
	)
	default boolean groupMimic()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupMemory",
		name = "Memory",
		description = "It brings up things that happened, and keeps score of the outings"
			+ " you did and did not take it on (group \"memory\")",
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
			+ " out when it loses them (group \"souvenir\")",
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
			+ " live with being wrong (group \"bet\")",
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
			+ " have been playing (group \"clock\")",
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
		position = 2
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
		position = 3
	)
	default boolean restWhenIdle()
	{
		return true;
	}

	@ConfigItem(
		keyName = "restAnimation",
		name = "Rest pose",
		description = "Looping animation held while resting; the default 10061 is the"
			+ " game's own sit emote. Audition candidates with ::follower pose <id>,"
			+ " and 0 for none.",
		section = movementSection,
		position = 4
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
		name = "Silence by name (advanced)",
		description = "Comma-separated group names or single phrase ids to silence,"
			+ " beyond the toggles above. Group names are shown in each toggle's"
			+ " hover text; phrase ids appear in the panel's message editors.",
		section = groupsSection,
		position = 12
	)
	default String disabledGroups()
	{
		return "";
	}
}

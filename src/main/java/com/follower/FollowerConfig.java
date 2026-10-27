package com.follower;

import com.follower.speech.SpeechOutput;
import java.awt.Color;
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
		// public:cut long-descriptions
		description = "The basics. The outfit is dressed in the Follower Buddy side"
			+ " panel, the icon on the right-hand toolbar.",
		// public:end long-descriptions
		// public:else description = "The basics. The outfit is dressed in the Follower Buddy side panel.",
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

	// public:cut sharevoice
	@ConfigSection(
		name = "Sounds",
		description = "Sound files you attach to lines. Nothing ships with the plugin.",
		position = 3
	)
	String soundsSection = "sounds";

	// public:end sharevoice
	@ConfigSection(
		name = "Messages",
		// public:cut long-descriptions
		description = "Turn whole subjects on or off. To read or edit the lines"
			+ " themselves, open Messages in the Follower Buddy side panel.",
		// public:end long-descriptions
		// public:else description = "Turn whole subjects on or off. Edit the"
		// public:else 	+ " lines under Messages in the side panel.",
		position = 4
	)
	String groupsSection = "groups";

	@ConfigSection(
		name = "Thrall mode",
		// public:cut long-descriptions
		description = "Thralls are the undead helpers from the Arceuus spellbook's"
			+ " Resurrect spells. With this on, the follower stands in for the one"
			+ " you summon and the real thrall is hidden, on your screen only.",
		// public:end long-descriptions
		// public:else description = "With this on, the follower stands in for the"
		// public:else 	+ " Arceuus thrall you summon, on your screen only.",
		position = 5
	)
	String thrallSection = "thrall";

	// public:cut errands
	@ConfigSection(
		name = "Errands",
		description = "Little trips the follower makes on its own - banks, altars, cats",
		position = 6
	)
	String errandSection = "errands";

	// public:end errands
	// public:cut skilling
	@ConfigSection(
		name = "Skilling",
		description = "What the follower does while you work a tree, a rock, a spot",
		position = 7
	)
	String skillingSection = "skilling";

	// public:end skilling
	@ConfigSection(
		name = "Company",
		// public:cut gaze,company-extras
		// public:cut long-descriptions
		description = "The small habits of somebody keeping you company: where it"
			+ " looks, what it notices, how it walks with you",
		// public:end long-descriptions
		// public:else description = "Small habits of company: where it looks,"
		// public:else 	+ " what it notices, how it walks with you",
		// public:end gaze,company-extras
		// public:else description = "The small habits of somebody keeping you"
		// public:else 	+ " company: what it notices, how it walks with you",
		position = 8
	)
	String companySection = "company";

	// public:cut spectate,spectate-keep-fight-lines
	@ConfigSection(
		name = "Combat",
		description = "What the follower does while you are fighting",
		position = 9
	)
	String combatSection = "combat";

	// public:end spectate,spectate-keep-fight-lines
	@ConfigSection(
		name = "Advanced",
		// public:cut dev,posing-preview
		description = "Animation and graphic ids. Preview one with ::follower anim <id>,"
			+ " ::follower gfx <id> or ::follower pose <id>; with Developer commands"
			+ " on, ::follower watch prints ids as they play. The defaults look"
			+ " right; nothing here needs changing.",
		// public:end dev,posing-preview
		// public:else description = "Animation and graphic ids. The defaults look right;"
		// public:else 	+ " nothing here needs changing.",
		position = 10,
		closedByDefault = true
	)
	String shieldSection = "shield";
	// public:cut dev

	@ConfigSection(
		name = "Developer",
		description = "Diagnostic chat commands used to build the plugin",
		position = 11,
		closedByDefault = true
	)
	String developerSection = "developer";
	// public:end dev

	// public:cut spectate,spectate-keep-fight-lines
	@ConfigItem(
		keyName = "spectateCombat",
		name = "Stand clear in combat",
		// public:cut long-descriptions
		description = "When a fight starts, the follower walks a few tiles clear and watches,"
			+ " so it is never sitting on top of the boss you are trying to see or click."
			+ " Thrall mode is exempt, being in the fight by definition.",
		// public:end long-descriptions
		// public:else description = "In a fight, the follower stands a few tiles"
		// public:else 	+ " clear and watches. Thrall mode is exempt.",
		section = combatSection,
		position = 0
	)
	default boolean spectateCombat()
	{
		return true;
	}

	// public:cut spectate-shield
	@ConfigItem(
		keyName = "spectateShield",
		name = "Cast a shield on bosses",
		// public:cut long-descriptions
		description = "In every boss fight it watches, the follower raises a ward: a cast with"
			+ " a graphic over it, then a kneel that holds it until the fight ends."
			+ " Needs Stand clear in combat.",
		// public:end long-descriptions
		// public:else description = "In boss fights it watches, it casts a ward and"
		// public:else 	+ " kneels to hold it. Needs Stand clear in combat.",
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
		name = "Shield summon animation",
		// public:cut posing-preview
		description = "Animation as the shield goes up. 0 for none."
			+ " Preview any id with ::follower anim <id>.",
		// public:end posing-preview
		// public:else description = "Animation as the shield goes up. 0 for none.",
		section = shieldSection,
		position = 5
	)
	default int spectateShieldAnimation()
	{
		return 1162;
	}

	@ConfigItem(
		keyName = "spectateShieldGraphic",
		name = "Shield summon graphic",
		// public:cut posing-preview
		description = "Graphic laid over the summon. 0 for none."
			+ " Audition ids with ::follower gfx <id>.",
		// public:end posing-preview
		// public:else description = "Graphic laid over the summon. 0 for none.",
		section = shieldSection,
		position = 6
	)
	default int spectateShieldGraphic()
	{
		// The dragon harpoon special's swirl, captured with ::follower watch.
		return 246;
	}

	@ConfigItem(
		keyName = "spectateShieldChannelStart",
		name = "Channel start animation",
		// public:cut long-descriptions
		description = "Played once as the follower settles into the channel, before the"
			+ " looping pose takes over. 0 for none.",
		// public:end long-descriptions
		// public:else description = "Played once as the follower settles into the channel. 0 for none.",
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

	@ConfigItem(
		keyName = "spectateShieldChannelEnd",
		name = "Channel end animation",
		// public:cut long-descriptions
		description = "Played as the channel ends, before the finishing cast. 0 for none."
			+ " With the default kneel (179), the default 534 is played as the kneel's"
			+ " own rise.",
		// public:end long-descriptions
		// public:else description = "Played as the channel ends, before the finishing cast. 0 for none.",
		section = shieldSection,
		position = 10
	)
	default int spectateShieldChannelEnd()
	{
		// AnimationID.HUMAN_GETUP, 0.40s - back on its feet.
		return 534;
	}

	@ConfigItem(
		keyName = "spectateShieldChannelAnimation",
		name = "Shield channel animation",
		// public:cut long-descriptions
		description = "Looping pose held after the summon, for as long as the follower stands"
			+ " and the fight lasts. Unlike the others this one repeats, so pick something"
			+ " that reads as sustained effort. 0 for none.",
		// public:end long-descriptions
		// public:else description = "Looping pose held after the summon while the fight"
		// public:else 	+ " lasts. Pick one that reads as effort. 0 for none.",
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

	@ConfigItem(
		keyName = "spectateShieldChannelGraphic",
		name = "Shield channel graphic",
		// public:cut long-descriptions
		description = "Renewed every second or so while channelling, since a looping pose"
			+ " shows no particles of its own. 0 for none.",
		// public:end long-descriptions
		// public:else description = "Renewed every second or so while channelling,"
		// public:else 	+ " as a loop shows no particles. 0 for none.",
		section = shieldSection,
		position = 9
	)
	default int spectateShieldChannelGraphic()
	{
		return 246;
	}

	@ConfigItem(
		keyName = "spectateShieldEndAnimation",
		name = "Shield finish animation",
		// public:cut long-descriptions
		description = "Played as the fight ends and the spell is completed. The follower"
			+ " waits for it to finish before walking back. 0 for none.",
		// public:end long-descriptions
		// public:else description = "Played as the fight ends; the follower waits"
		// public:else 	+ " for it before walking back. 0 for none.",
		section = shieldSection,
		position = 11
	)
	default int spectateShieldEndAnimation()
	{
		// The same cast as the summon, for now: the spell is released the way
		// it was raised.
		return 1162;
	}

	@ConfigItem(
		keyName = "spectateShieldEndGraphic",
		name = "Shield finish graphic",
		description = "Graphic as the spell is completed. 0 for none.",
		section = shieldSection,
		position = 12
	)
	default int spectateShieldEndGraphic()
	{
		return 246;
	}

	// public:end spectate-shield
	// public:end spectate,spectate-keep-fight-lines
	// public:cut dev
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
			+ " colour sweeps, cachecheck, stanceaudit). While it's on, agility is"
			+ " also logged to agility-trace-<date>.log in the plugin's folder, a"
			+ " file a day, kept for 30 days. Off unless you're working on the"
			+ " plugin itself.",
		section = developerSection,
		position = 0
	)
	default boolean developerMode()
	{
		return false;
	}
	// public:end dev

	// public:cut errands
	enum ErrandFrequency
	{
		// errandFrequency()'s description quotes these as minutes (30, 24, 12
		// at 0.6s a tick). Change one, change the other.
		RARE("Rare", 3000),
		// Was 1800. At that pace the follower ran an errand every eighteen
		// minutes all day, which is not something it does now and then - it
		// is something it is always doing.
		OCCASIONAL("Occasional", 2400),
		LIVELY("Lively", 1200);

		private final String label;

		/** Ticks between errands at this pace, before the schedule's 60%..140%. */
		public final int ticks;

		ErrandFrequency(String label, int ticks)
		{
			this.label = label;
			this.ticks = ticks;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	// public:end errands
	// ------------------------------------------------------------- appearance

	@ConfigItem(
		keyName = "followerName",
		name = "Follower name",
		// public:cut long-descriptions
		description = "What it is called in the dialog box, right-click menu and"
			+ " chat. Its gear, body and colours are set in the Follower Buddy"
			+ " side panel, not here.",
		// public:end long-descriptions
		// public:else description = "What it is called in the dialog box, menu"
		// public:else 	+ " and chat. Dress it in the side panel.",
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
	 *
	 * <p>The follower's outfit; use the side panel to edit it
	 */
	@ConfigItem(
		keyName = "customOutfit",
		name = "Custom outfit",
		description = "Edited in the side panel",
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
		// public:cut long-descriptions
		description = "Off parks the follower where it stands. Same as right-click"
			+ " Stay and Follow-me on the follower. Hold Shift and right-click a tile"
			+ " to send it somewhere in particular.",
		// public:end long-descriptions
		// public:else description = "Off parks the follower where it stands, like"
		// public:else 	+ " right-click Stay. Shift right-click a tile to send it.",
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
		// public:cut long-descriptions
		description = "Lifts the follower if its boots sink into the ground. Positive"
			+ " lifts. In small model units; the default 15 lifts the boots just"
			+ " clear. Tune it live with ::follower height <n>.",
		// public:end long-descriptions
		// public:else description = "Lifts the follower if its boots sink into the"
		// public:else 	+ " ground. Tune it live with ::follower height <n>.",
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
		name = "Arrival animation",
		// public:cut long-descriptions
		description = "Animation played as the follower arrives. Default 715 is the"
			+ " teleport arrival. Comma-separate to chain clips, e.g. \"6723,4191\""
			// public:cut posing-preview
			+ " for a landing followed by a get-up; preview with"
			+ " ::follower anim 6723 4191. Blank or 0 for none.",
			// public:end posing-preview
			// public:else + " for a landing followed by a get-up. Blank or 0 for none.",
		// public:end long-descriptions
		// public:else description = "Animation as it arrives. Default 715, the teleport"
		// public:else 	+ " arrival. Comma-separate to chain clips; 0 for none.",
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
		// public:cut long-descriptions
		description = "Effect drawn as the follower arrives. Default 1299 is the"
			// public:cut posing-preview
			+ " teleport arrival that goes with animation 715. Blank or 0 for none."
			+ " Preview with ::follower gfx 1299.",
			// public:end posing-preview
			// public:else + " teleport arrival that goes with animation 715. Blank or 0 for none.",
		// public:end long-descriptions
		// public:else description = "Effect drawn as it arrives. Default 1299 goes"
		// public:else 	+ " with animation 715. Blank or 0 for none.",
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
	 *
	 * <p>Overhead, chatbox, or both. The fallback for rules that do not choose their own
	 * place; managed by the plugin
	 */
	@ConfigItem(
		keyName = "defaultOutput",
		name = "Where speech appears",
		description = "Fallback speech place",
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
		name = "Shortest time on screen",
		// public:cut long-descriptions
		description = "In milliseconds: 4000 is four seconds, 6000 is six. Longer"
			+ " lines are given longer, at the reading speed below.",
		// public:end long-descriptions
		// public:else description = "In milliseconds: 4000 is four seconds. Longer"
		// public:else 	+ " lines stay longer, at the reading speed.",
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

	@Range(max = 400)
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
		// public:cut long-descriptions
		description = "How much it talks. Minimal is a remark every minute or so at"
			+ " most; Chatty is a running commentary. Occasional is the default.",
		// public:end long-descriptions
		// public:else description = "How much it talks, from a remark a minute or"
		// public:else 	+ " so on Minimal to running commentary on Chatty.",
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

	// public:cut sharevoice
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

	@Range(max = 100)
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

	// public:end sharevoice
	@ConfigItem(
		keyName = "mirrorToChat",
		name = "Also show lines in the chatbox",
		// public:cut long-descriptions
		description = "Every line also appears in the chatbox under the follower's"
			+ " name. Off by default because it can crowd out the game's own chat.",
		// public:end long-descriptions
		// public:else description = "Every line also appears in the chatbox. Off"
		// public:else 	+ " by default, as it can crowd the game's chat.",
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
		// public:cut long-descriptions
		description = "When you summon an Arceuus thrall, the follower takes its place, moving"
			+ " and attacking as the thrall does. Thrall only does this too, even with"
			+ " this unticked.",
		// public:end long-descriptions
		// public:else description = "When you summon an Arceuus thrall, the follower"
		// public:else 	+ " takes its place and fights as the thrall does.",
		section = thrallSection,
		position = 0
	)
	default boolean thrallMode()
	{
		return true;
	}

	@ConfigItem(
		keyName = "thrallOnly",
		name = "Thrall only",
		// public:cut long-descriptions
		description = "The follower is absent until you summon a thrall, takes its"
			+ " place while it lasts, and when the thrall goes it phases out and"
			+ " is gone until the next summon: for people who only want the thrall"
			+ " replacement. It talks as the thrall unless Mute the thrall is ticked.",
		// public:end long-descriptions
		// public:else description = "The follower only appears as your thrall, while"
		// public:else 	+ " the thrall lasts. For thrall replacement alone.",
		section = thrallSection,
		position = 1
	)
	default boolean thrallOnly()
	{
		return false;
	}

	@ConfigItem(
		keyName = "muteThrall",
		name = "Mute the thrall",
		// public:cut long-descriptions
		description = "While the follower is your thrall it says nothing: no hello"
			+ " when you summon it, no remarks while it fights, no goodbye when it"
			+ " goes. The rest of the time it talks as usual.",
		// public:end long-descriptions
		// public:else description = "While the follower is your thrall it says"
		// public:else 	+ " nothing. The rest of the time it talks as usual.",
		section = thrallSection,
		position = 2
	)
	default boolean muteThrall()
	{
		return false;
	}
	// public:cut outfit-profiles,thrall-outfits

	@ConfigItem(
		keyName = "thrallMeleeProfile",
		name = "Melee outfit profile",
		// public:cut long-descriptions
		description = "Outfit profile it wears in place of a zombie (melee) thrall."
			+ " Type a name from OUTFIT PROFILES in the side panel, or pick it"
			+ " there; if no profile has that name the ready-made Melee one is used.",
		// public:end long-descriptions
		// public:else description = "Outfit profile worn as a zombie (melee) thrall."
		// public:else 	+ " Unknown names use the ready-made Melee one.",
		section = thrallSection,
		position = 3
	)
	default String thrallMeleeProfile()
	{
		return "Melee";
	}

	@ConfigItem(
		keyName = "thrallRangedProfile",
		name = "Ranged outfit profile",
		// public:cut long-descriptions
		description = "Outfit profile it wears in place of a skeleton (ranged) thrall."
			+ " Type a name from OUTFIT PROFILES in the side panel, or pick it"
			+ " there; if no profile has that name the ready-made Ranged one is used.",
		// public:end long-descriptions
		// public:else description = "Outfit profile worn as a skeleton (ranged) thrall."
		// public:else 	+ " Unknown names use the ready-made Ranged one.",
		section = thrallSection,
		position = 4
	)
	default String thrallRangedProfile()
	{
		return "Ranged";
	}

	@ConfigItem(
		keyName = "thrallMagicProfile",
		name = "Magic outfit profile",
		// public:cut long-descriptions
		description = "Outfit profile it wears in place of a ghost (magic) thrall."
			+ " Type a name from OUTFIT PROFILES in the side panel, or pick it"
			+ " there; if no profile has that name the ready-made Magic one is used.",
		// public:end long-descriptions
		// public:else description = "Outfit profile worn as a ghost (magic) thrall."
		// public:else 	+ " Unknown names use the ready-made Magic one.",
		section = thrallSection,
		position = 5
	)
	default String thrallMagicProfile()
	{
		return "Magic";
	}
	// public:end outfit-profiles,thrall-outfits

	// The three per-style attack animation pickers were removed: the follower
	// now swings whatever its own weapon swings, learned by watching real
	// players, so a typed-in id could only ever contradict the weapon in its
	// hands. The per-style fallbacks live in FollowerPlugin as constants for
	// the case where nothing has been observed yet.

	// public:cut errands
	// --------------------------------------------------------------- errands

	@ConfigItem(
		keyName = "errandsEnabled",
		name = "Go on errands",
		// public:cut long-descriptions
		description = "Now and then it wanders off on a small errand and comes back."
			+ " Untick to keep it at your heel.",
		// public:end long-descriptions
		// public:else description = "Now and then it wanders off on a small errand and comes back.",
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
		name = "Altar visits",
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
		// public:cut long-descriptions
		description = "Noses at whatever the place offers - a chest, a trapdoor,"
			+ " a signpost - and notes it down, especially somewhere it has"
			+ " just arrived (::follower errand explore)",
		// public:end long-descriptions
		// public:else description = "Noses at a chest, trapdoor or signpost nearby"
		// public:else 	+ " and notes it down (::follower errand explore)",
		section = errandSection,
		position = 10
	)
	default boolean errandExplore()
	{
		return true;
	}

	// public:end errands
	// public:cut skilling
	// --------------------------------------------------------------- skilling

	@ConfigItem(
		keyName = "keepsCompany",
		name = "Works beside you when skilling",
		// public:cut long-descriptions
		description = "While you chop, mine or fish it works alongside you: the same"
			+ " tree, rock or spot when there is room, the next one along when"
			+ " there is not. At a farming patch it works the patch with you. Now"
			+ " and then it stops for its notes. Untick to keep it at your heel.",
		// public:end long-descriptions
		// public:else description = "Works alongside you at the same tree,"
		// public:else 	+ " rock, spot or patch, or the next one along.",
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
		// public:cut long-descriptions
		description = "Holds a copy of the tool the work wants for the session - your"
			+ " axe or pickaxe (the one you are wearing, or a bronze one), a net,"
			+ " a rod, a rake - and gives it back with a bow when you are done."
			+ " Without it, it keeps you company with its notes instead.",
		// public:end long-descriptions
		// public:else description = "Holds a copy of the tool the work wants,"
		// public:else 	+ " and gives it back when you are done.",
		section = skillingSection,
		position = 1
	)
	default boolean borrowsTools()
	{
		return true;
	}

	// public:end skilling
	// ---------------------------------------------------------------- company

	// public:cut company-extras,gaze
	@ConfigItem(
		keyName = "paysAttention",
		name = "Looks where you look",
		// public:cut long-descriptions
		description = "Turns its head to whatever your cursor rests on and to where"
			+ " you turn the camera, and looks round at what is nearby while you"
			+ " stand about. Head only; it never moves its feet for this.",
		// public:end long-descriptions
		// public:else description = "Turns its head to your cursor, your camera"
		// public:else 	+ " and what is nearby. Head only, never its feet.",
		section = companySection,
		position = 0
	)
	default boolean paysAttention()
	{
		return true;
	}

	// public:end company-extras,gaze
	@ConfigItem(
		keyName = "noticesThings",
		name = "Notices things",
		// public:cut noticing,company-extras
		// public:cut long-descriptions
		description = "Goes to look at a drop of yours you walked away from, a bird's"
			+ " nest, an impling, a crashed star, or a signpost or statue you stop"
			+ " beside, and says so once. It also claps your levels, greets a new"
			+ " pet, comes over to see a good drop and keeps an eye on a stranger"
			+ " who walks up to you.",
		// public:end long-descriptions
		// public:else description = "Goes to look at drops, nests, implings,"
		// public:else 	+ " stars and landmarks, and claps your levels.",
		// public:end noticing,company-extras
		// public:else description = "Claps your levels, greets a new pet, comes over to see a good"
		// public:else 	+ " drop and keeps an eye on a stranger who walks up to you.",
		section = companySection,
		position = 1
	)
	default boolean noticesThings()
	{
		return true;
	}

	@ConfigItem(
		keyName = "walksBeside",
		name = "Walks at your side",
		// public:cut long-descriptions
		description = "On a long run it draws level and walks at your shoulder instead of"
			+ " trailing behind, and drops back the moment the ground narrows."
			+ " Untick to keep it at your heel.",
		// public:end long-descriptions
		// public:else description = "On a long run it walks at your shoulder,"
		// public:else 	+ " and drops back when the ground narrows.",
		section = companySection,
		position = 2
	)
	default boolean walksBeside()
	{
		return true;
	}

	// ------------------------------------------------------------ rule groups

	@ConfigItem(
		keyName = "groupBoss",
		name = "Bosses",
		// public:cut long-descriptions
		description = "Tips, lore and jokes when a boss appears (group \"boss\")."
			+ " Editable under Bosses in Messages.",
		// public:end long-descriptions
		// public:else description = "Tips, lore and jokes when a boss appears (group \"boss\").",
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
		// public:cut long-descriptions
		description = "Remarks on your HP, prayer, poison, venom, skull and run energy"
			+ " (group \"health\"). Editable under Statuses in Messages.",
		// public:end long-descriptions
		// public:else description = "Remarks on your HP, prayer, poison, venom,"
		// public:else 	+ " skull and run energy (group \"health\").",
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
		// public:cut long-descriptions
		description = "Comments when you equip notable items, and the warning that"
			+ " your bag is nearly full (group \"gear\")."
			+ " Editable under Items in Messages.",
		// public:end long-descriptions
		// public:else description = "Comments on notable items you equip,"
		// public:else 	+ " and a nearly full bag (group \"gear\").",
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
		// public:cut long-descriptions
		description = "Comments near famous quest figures, at moments partway through some"
			+ " quests, and when you finish one (group \"quest\")."
			+ " Editable under Quest NPCs in Messages.",
		// public:end long-descriptions
		// public:else description = "Comments near famous quest figures, during some"
		// public:else 	+ " quests and when you finish one (group \"quest\").",
		section = groupsSection,
		position = 6
	)
	default boolean groupQuest()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupCombat",
		name = "Combat remarks",
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
		// public:cut long-descriptions
		description = "It copies your emotes, spells and teleports, joins in when you eat"
			+ " or work, and answers an emote made at it (group \"mimic\")."
			+ " Editable under Mimicry in Messages.",
		// public:end long-descriptions
		// public:else description = "It copies your emotes, spells and teleports,"
		// public:else 	+ " and answers emotes at it (group \"mimic\").",
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
		// public:cut long-descriptions
		description = "It brings up things that happened and keeps score of the outings"
			+ " it was and was not taken on (group \"memory\"). Turning it off does"
			+ " not stop it remembering; Forget everything in the Memory window"
			+ " erases what it remembers. Editable under Reminiscing in Messages.",
		// public:end long-descriptions
		// public:else description = "It brings up past events and keeps score of outings"
		// public:else 	+ " (group \"memory\"). It still remembers when off.",
		section = groupsSection,
		position = 8
	)
	default boolean groupMemory()
	{
		return true;
	}

	// public:cut souvenirs-wishes
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

	// public:end souvenirs-wishes
	// public:cut side-games
	@ConfigItem(
		keyName = "groupBet",
		name = "Predictions",
		description = "It calls what the next drop is worth before it lands, bets you cannot"
			+ " manage a run of kills, and has to live with being wrong (group \"bet\")."
			+ " Editable under Predictions in Messages.",
		section = groupsSection,
		position = 10
	)
	default boolean groupBet()
	{
		return true;
	}

	// public:end side-games
	@ConfigItem(
		keyName = "groupClock",
		name = "The hour and the session",
		// public:cut long-descriptions
		description = "Remarks about what time it is where you are and how long you"
			+ " have been playing (group \"clock\")."
			+ " Editable under The hour and the session in Messages.",
		// public:end long-descriptions
		// public:else description = "Remarks on the time where you are and"
		// public:else 	+ " how long you have played (group \"clock\").",
		section = groupsSection,
		position = 11
	)
	default boolean groupClock()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupReactions",
		name = "Reactions",
		// public:cut long-descriptions
		description = "Deaths, drops, thieving, level-ups and the chat, and its other answers"
			+ " to what you do and what it sees (group \"reactions\")."
			+ " Editable under Reactions in Messages.",
		// public:end long-descriptions
		// public:else description = "Deaths, drops, thieving, level-ups, chat and its"
		// public:else 	+ " other answers to what happens (group \"reactions\").",
		section = groupsSection,
		position = 12
	)
	default boolean groupReactions()
	{
		return true;
	}

	@ConfigItem(
		keyName = "wanderWhenIdle",
		name = "Wander when idle",
		// public:cut long-descriptions
		description = "While you stand still, the follower drifts to a nearby tile every"
			+ " half a minute or so instead of standing to attention. It stops after"
			+ " five minutes, when it would sit down.",
		// public:end long-descriptions
		// public:else description = "While you stand still, it drifts to a nearby"
		// public:else 	+ " tile now and then, until it would sit down.",
		section = movementSection,
		position = 1
	)
	default boolean wanderWhenIdle()
	{
		return true;
	}

	@ConfigItem(
		keyName = "wanderAfterSeconds",
		name = "Wander after (seconds)",
		// public:cut long-descriptions
		description = "How long you stand still before it starts drifting about."
			+ " Forty-eight seconds by default, so sorting a bank tab does not"
			+ " read as a stay; set it low to have it potter almost at once.",
		// public:end long-descriptions
		// public:else description = "How long you stand still before it starts"
		// public:else 	+ " drifting about. Forty-eight seconds by default.",
		section = movementSection,
		position = 2
	)
	@Range(min = 3, max = 240)
	default int wanderAfterSeconds()
	{
		return 48;
	}

	@ConfigItem(
		keyName = "followDistance",
		name = "Follow distance (tiles)",
		// public:cut long-descriptions
		description = "How many steps behind you it walks and stops. One is the"
			+ " game's own pet distance; two or three keep it out from under"
			+ " your feet when you stop.",
		// public:end long-descriptions
		// public:else description = "How many steps behind you it walks. One is the game's pet distance.",
		section = movementSection,
		position = 0
	)
	@Range(min = 1, max = 3)
	default int followDistance()
	{
		return 1;
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

	// public:cut agility,agility-all
	@ConfigItem(
		keyName = "copiesAgility",
		name = "Does agility with you",
		// public:cut long-descriptions
		description = "Takes the obstacles and most shortcuts you take, after you and the"
			+ " same way, falls and all. Untick and it walks round to you, or appears"
			+ " beside you, instead.",
		// public:end long-descriptions
		// public:else description = "Takes the obstacles and shortcuts you take,"
		// public:else 	+ " falls and all. Untick and it walks round instead.",
		section = movementSection,
		position = 4
	)
	default boolean copiesAgility()
	{
		return true;
	}

	// public:end agility,agility-all
	@ConfigItem(
		keyName = "restAnimation",
		name = "Rest pose",
		// public:cut long-descriptions
		description = "Animation held while it sits. Set 0 and it never sits down. Default"
			// public:cut posing-preview
			+ " 10061 is the game's own sit emote; audition others with"
			+ " ::follower pose <id>.",
			// public:end posing-preview
			// public:else + " 10061 is the game's own sit emote.",
		// public:end long-descriptions
		// public:else description = "Animation held while it sits; 0 and it never"
		// public:else 	+ " sits. Default 10061 is the game's sit emote.",
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

	// public:cut outfit-profiles
	// Which outfit profile the follower is wearing; managed by the panel
	@ConfigItem(
		keyName = "activeProfile",
		name = "Active outfit profile",
		description = "Worn outfit profile",
		hidden = true
	)
	default String activeProfile()
	{
		return "";
	}

	// public:end outfit-profiles
	// public:cut dev
	// Body colour pairs captured by ::follower palette; managed by the plugin
	@ConfigItem(
		keyName = "exactPalette",
		name = "Exact palette",
		description = "Captured body colours",
		hidden = true
	)
	default String exactPalette()
	{
		return "";
	}
	// public:end dev

	// Which side panel sections are open; managed by the panel
	@ConfigItem(
		keyName = "panelFolds",
		name = "Panel folds",
		description = "Open panel sections",
		hidden = true
	)
	default String panelFolds()
	{
		return "talking,outfit";
	}

	// When the follower last saw you, so it can tell a quick relog from an absence; managed
	// by the plugin
	@ConfigItem(
		keyName = "lastSeenMs",
		name = "Last seen",
		description = "Last time seen",
		hidden = true
	)
	default String lastSeenMs()
	{
		return "";
	}
	// public:cut dev

	// Whether the session transcript is running; toggled with ::follower transcript, kept
	// here so it survives a restart
	@ConfigItem(
		keyName = "transcriptOn",
		name = "Transcript",
		description = "Transcript running",
		hidden = true
	)
	default boolean transcriptOn()
	{
		return false;
	}
	// public:end dev

	// Whether the one-time naming prompt has been offered; managed by the plugin
	@ConfigItem(
		keyName = "namePrompted",
		name = "Name prompted",
		description = "Naming prompt offered",
		hidden = true
	)
	default boolean namePrompted()
	{
		return false;
	}

	// public:cut outfit-profiles
	// This profile's own named outfits; managed by the plugin
	@ConfigItem(
		keyName = "outfitProfiles",
		name = "Outfit profiles",
		description = "Named outfits",
		hidden = true
	)
	default String outfitProfiles()
	{
		return "";
	}

	// public:end outfit-profiles
	// The tile the follower was told to wait on, so it is there again after a relog; managed
	// by the plugin
	@ConfigItem(
		keyName = "parkedAt",
		name = "Parked at",
		description = "Wait tile",
		hidden = true
	)
	default String parkedAt()
	{
		return "";
	}

	// Whether the one-time client-side notice and ::follower hint have been printed; managed
	// by the plugin
	@ConfigItem(
		keyName = "firstSpawnNoticeShown",
		name = "First spawn notice shown",
		description = "First notice printed",
		hidden = true
	)
	default boolean firstSpawnNoticeShown()
	{
		return false;
	}

	// Whether the one-time shift-right-click Send hint has been printed; managed by the
	// plugin
	@ConfigItem(
		keyName = "sendHintShown",
		name = "Send hint shown",
		description = "Send hint printed",
		hidden = true
	)
	default boolean sendHintShown()
	{
		return false;
	}

	// public:cut souvenirs-wishes
	// Whether the one-time gift handover hint has been printed; managed by the plugin
	@ConfigItem(
		keyName = "wishHintShown",
		name = "Wish hint shown",
		description = "Gift hint printed",
		hidden = true
	)
	default boolean wishHintShown()
	{
		return false;
	}

	// public:end souvenirs-wishes
	// Whether the one-time Replace thralls hint has been printed; managed by the plugin
	@ConfigItem(
		keyName = "thrallHintShown",
		name = "Thrall hint shown",
		description = "Thrall hint printed",
		hidden = true
	)
	default boolean thrallHintShown()
	{
		return false;
	}
	// public:cut legacy

	// Whether the second clear of learned attack animations has run, after skilling
	// animations were found among them; managed by the plugin
	@ConfigItem(
		keyName = "attacksRelearnedSkilling",
		name = "Attacks relearned again",
		description = "Second attack clear run",
		hidden = true
	)
	default boolean attacksRelearnedSkilling()
	{
		return false;
	}

	// Whether the one-time clear of learned attack animations has run; managed by the plugin
	@ConfigItem(
		keyName = "attacksRelearned",
		name = "Attacks relearned",
		description = "Attack clear run",
		hidden = true
	)
	default boolean attacksRelearned()
	{
		return false;
	}
	// public:end legacy

	// The game build the last session ran on, so the follower can notice a weekly update;
	// managed by the plugin
	@ConfigItem(
		keyName = "lastRevision",
		name = "Last game revision",
		description = "Last game build",
		hidden = true
	)
	default int lastRevision()
	{
		return 0;
	}

	// What the follower has counted and the records it holds, so its memory survives a
	// logout; managed by the plugin
	@ConfigItem(
		keyName = "counters",
		name = "Counters",
		description = "Saved memory",
		hidden = true
	)
	default String counters()
	{
		return "";
	}

	// public:cut likes
	// The places this follower likes and dislikes, rolled once when it first met you;
	// managed by the plugin
	@ConfigItem(
		keyName = "traits",
		name = "Traits",
		description = "Liked and disliked places",
		hidden = true
	)
	default String traits()
	{
		return "";
	}

	// public:end likes
	@ConfigItem(
		keyName = "reachAnimation",
		name = "Reaching for something",
		// public:cut long-descriptions
		description = "Animation played as the follower takes a single thing out,"
			+ " such as a tool it is borrowing, so it does not simply appear in"
			+ " its hand. The thing comes into the hand halfway through. The"
			+ " quill and scroll need none: the writing pose lifts them itself."
			+ " Default 827 is the bend the errands already use. Others worth a"
			// public:cut posing-preview
			+ " look: 832, 5207, 895, 1649, 6217. Preview with"
			+ " ::follower anim <id>. Blank or 0 for none.",
			// public:end posing-preview
			// public:else + " look: 832, 5207, 895, 1649, 6217. Blank or 0 for none.",
		// public:end long-descriptions
		// public:else description = "Animation as it takes out one thing, like"
		// public:else 	+ " a borrowed tool. Default 827; 0 for none.",
		section = shieldSection,
		position = 13
	)
	default String reachAnimation()
	{
		return "827";
	}

	@ConfigItem(
		keyName = "disabledGroups",
		name = "Silence groups or rule ids by name",
		// public:cut long-descriptions
		description = "Comma-separated group names or single rule ids to silence,"
			+ " beyond the toggles in the Messages section. Each of those toggles"
			+ " shows its group name in brackets; rule ids are shown in the"
			+ " Messages window.",
		// public:end long-descriptions
		// public:else description = "Comma-separated group names (shown in brackets"
		// public:else 	+ " on the toggles) or rule ids to silence.",
		section = groupsSection,
		position = 13
	)
	default String disabledGroups()
	{
		return "";
	}
}

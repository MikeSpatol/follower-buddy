package com.follower;

import java.awt.Color;
import net.runelite.client.config.*;
import net.runelite.client.config.Config;
import lombok.RequiredArgsConstructor;

@ConfigGroup(FollowerConfig.GROUP)
public interface FollowerConfig extends Config
{
	String GROUP = "followerbuddy";

	@ConfigSection(
		name = "Follower",
		description = "The basics. The outfit is dressed in the Follower Buddy side panel.",
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
		name = "Messages",
		description = "Turn whole subjects on or off. Edit the lines under Messages in the side panel.",
		position = 4
	)
	String groupsSection = "groups";

	@ConfigSection(
		name = "Thrall mode",
		description = "With this on, the follower stands in for the Arceuus thrall you summon, on your screen only.",
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
		name = "Company",
		description = "Small habits of company: where it looks, what it notices, how it walks with you",
		position = 8
	)
	String companySection = "company";

	@ConfigSection(
		name = "Combat",
		description = "What the follower does while you are fighting",
		position = 9
	)
	String combatSection = "combat";

	@ConfigSection(
		name = "Advanced",
		description = "Animation and graphic ids. The defaults look right; nothing here needs changing.",
		position = 10,
		closedByDefault = true
	)
	String shieldSection = "shield";

	@ConfigItem(
		keyName = "spectateCombat",
		name = "Stand clear in combat",
		description = "In a fight, the follower stands a few tiles clear and watches. Thrall mode is exempt.",
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
		description = "In boss fights it watches, it casts a ward and kneels to hold it. Needs Stand clear in combat.",
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
		description = "Animation as the shield goes up. 0 for none.",
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
		description = "Graphic laid over the summon. 0 for none.",
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
		description = "Played once as the follower settles into the channel. 0 for none.",
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
		description = "Played as the channel ends, before the finishing cast. 0 for none.",
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
		description = "Looping pose held after the summon while the fight lasts. Pick one that reads as effort. 0 for none.",
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
		description = "Renewed every second or so while channelling, as a loop shows no particles. 0 for none.",
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
		description = "Played as the fight ends; the follower waits for it before walking back. 0 for none.",
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


	@RequiredArgsConstructor
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

		final String label;

		/** Ticks between errands at this pace, before the schedule's 60%..140%. */
		final int ticks;

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
		description = "What it is called in the dialog box, menu and chat. Dress it in the side panel.",
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
		description = "Off parks the follower where it stands, like right-click Stay. Shift right-click a tile to send it.",
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
		description = "Lifts the follower if its boots sink into the ground. Tune it live with ::follower height <n>.",
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
		description = "Animation as it arrives. Default 715, the teleport arrival. Comma-separate to chain clips; 0 for none.",
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
		description = "Effect drawn as it arrives. Default 1299 goes with animation 715. Blank or 0 for none.",
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
		description = "How high up the follower the arrival effect is drawn. 92 wraps the body; 0 draws it at the feet.",
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
		description = "Despawns the follower in the Wilderness and other PvP zones so it can't obscure a real player",
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
		description = "The follower stops talking. Everything else keeps working, and your other speech settings are kept.",
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
		description = "In milliseconds: 4000 is four seconds. Longer lines stay longer, at the reading speed.",
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
		description = "Characters per second. Lower it if lines vanish before you finish them; 17 suits most people.",
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
		description = "How much it talks, from a remark a minute or so on Minimal to running commentary on Chatty.",
		section = appearanceSection,
		position = 3
	)
	default Chattiness chattiness()
	{
		// Occasional, not Normal, since 1.4: the first impression was "it
		// talks too much", and a level below Quiet only helps the people who
		// go looking for it. The default is what everybody else hears.
		return Chattiness.OCCASIONAL;
	}

	@ConfigItem(
		keyName = "mirrorToChat",
		name = "Also show lines in the chatbox",
		description = "Every line also appears in the chatbox. Off by default, as it can crowd the game's chat.",
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
		description = "When you summon an Arceuus thrall, the follower takes its place and fights as the thrall does.",
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
		description = "The follower only appears as your thrall, while the thrall lasts. For thrall replacement alone.",
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
		description = "While the follower is your thrall it says nothing. The rest of the time it talks as usual.",
		section = thrallSection,
		position = 2
	)
	default boolean muteThrall()
	{
		return false;
	}

	@ConfigItem(
		keyName = "thrallMeleeProfile",
		name = "Melee outfit profile",
		description = "Outfit profile worn as a zombie (melee) thrall. Unknown names use the ready-made Melee one.",
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
		description = "Outfit profile worn as a skeleton (ranged) thrall. Unknown names use the ready-made Ranged one.",
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
		description = "Outfit profile worn as a ghost (magic) thrall. Unknown names use the ready-made Magic one.",
		section = thrallSection,
		position = 5
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
		description = "Now and then it wanders off on a small errand and comes back.",
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
		description = "About every 30 minutes on Rare, 24 on Occasional, 12 on Lively, give or take.",
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
		description = "Wanders a few tiles to investigate nothing in particular (::follower errand glance)",
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
		description = "Walks over to something notable nearby and writes it up (::follower errand study)",
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
		description = "Noses at a chest, trapdoor or signpost nearby and notes it down (::follower errand explore)",
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
		name = "Works beside you when skilling",
		description = "Works alongside you at the same tree, rock, spot or patch, or the next one along.",
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
		description = "Holds a copy of the tool the work wants, and gives it back when you are done.",
		section = skillingSection,
		position = 1
	)
	default boolean borrowsTools()
	{
		return true;
	}

	// ---------------------------------------------------------------- company

	@ConfigItem(
		keyName = "paysAttention",
		name = "Looks where you look",
		description = "Turns its head to your cursor, your camera and what is nearby. Head only, never its feet.",
		section = companySection,
		position = 0
	)
	default boolean paysAttention()
	{
		return true;
	}

	@ConfigItem(
		keyName = "noticesThings",
		name = "Notices things",
		description = "Goes to look at drops, nests, implings, stars and landmarks, and claps your levels.",
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
		description = "On a long run it walks at your shoulder, and drops back when the ground narrows.",
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
		description = "Tips, lore and jokes when a boss appears (group \"boss\").",
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
		description = "Remarks on your HP, prayer, poison, venom, skull and run energy (group \"health\").",
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
		description = "Comments on entering towns, dungeons and landmarks (group \"area\"). Editable under Locations in Messages.",
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
		description = "Small talk while nothing much is happening (group \"idle\"). Editable under Idle chatter in Messages.",
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
		description = "Comments on notable items you equip, and a nearly full bag (group \"gear\").",
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
		description = "Comments near famous quest figures, during some quests and when you finish one (group \"quest\").",
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
		description = "Encouragement while it watches you fight (group \"combat\"). Editable under Combat in Messages.",
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
		description = "It copies your emotes, spells and teleports, and answers emotes at it (group \"mimic\").",
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
		description = "It brings up past events and keeps score of outings (group \"memory\"). It still remembers when off.",
		section = groupsSection,
		position = 8
	)
	default boolean groupMemory()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupClock",
		name = "The hour and the session",
		description = "Remarks on the time where you are and how long you have played (group \"clock\").",
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
		description = "Deaths, drops, thieving, level-ups, chat and its other answers to what happens (group \"reactions\").",
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
		description = "While you stand still, it drifts to a nearby tile now and then, until it would sit down.",
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
		description = "How long you stand still before it starts drifting about. Forty-eight seconds by default.",
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
		description = "How many steps behind you it walks. One is the game's pet distance.",
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
		description = "After about five minutes of standing still, the follower sits down. It gets up the moment anything happens.",
		section = movementSection,
		position = 3
	)
	default boolean restWhenIdle()
	{
		return true;
	}

	@ConfigItem(
		keyName = "copiesAgility",
		name = "Does agility with you",
		description = "Takes the obstacles and shortcuts you take, falls and all. Untick and it walks round instead.",
		section = movementSection,
		position = 4
	)
	default boolean copiesAgility()
	{
		return true;
	}

	@ConfigItem(
		keyName = "restAnimation",
		name = "Rest pose",
		description = "Animation held while it sits; 0 and it never sits. Default 10061 is the game's sit emote.",
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

	@ConfigItem(
		keyName = "reachAnimation",
		name = "Reaching for something",
		description = "Animation as it takes out one thing, like a borrowed tool. Default 827; 0 for none.",
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
		description = "Comma-separated group names (shown in brackets on the toggles) or rule ids to silence.",
		section = groupsSection,
		position = 13
	)
	default String disabledGroups()
	{
		return "";
	}
}

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
		name = "Appearance",
		description = "What the follower looks like",
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
		name = "Rule groups",
		description = "Switch whole categories of phrases on and off",
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
		description = "Shown in the dialog box, right-click menu, hover text and chat",
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
		// Coherent Armadyl set: helmet / chestplate / chainskirt are 11826/11828/11830.
		return "gender=male\n"
			+ "HEAD=item:11826\n"
			+ "TORSO=item:11828\n"
			+ "LEGS=item:11830\n"
			+ "WEAPON=item:4151\n"
			+ "CAPE=item:6570\n"
			+ "AMULET=item:6585\n"
			+ "HANDS=item:7462\n"
			+ "BOOTS=item:11840";
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
		description = "Raises the follower out of the ground if its feet are clipped. "
			+ "Positive lifts. Tune it live with ::follower height <n>.",
		section = movementSection,
		position = 3
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
		description = "Animation id played when the follower appears. Comma-separate "
			+ "to chain clips, e.g. \"6723,4191\" for a landing followed by a get-up. "
			+ "Preview with ::follower anim 6723 4191. Blank or 0 for none.",
		section = movementSection,
		position = 2
	)
	default String spawnAnimation()
	{
		return "715";
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
		name = "Default output",
		description = "Used by any rule that doesn't set its own \"output\"",
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
		description = "Stops all rule-driven speech without unloading the rules",
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

	@Range(min = 500, max = 15000)
	@ConfigItem(
		keyName = "speechDurationMs",
		name = "Display time (ms)",
		description = "How long a line stays on screen",
		section = speechSection,
		position = 3
	)
	default int speechDurationMs()
	{
		return 4000;
	}

	@Range(min = 0, max = 400)
	@ConfigItem(
		keyName = "speechHeight",
		name = "Bubble height",
		description = "Height of the speech bubble above the follower's feet",
		section = speechSection,
		position = 4
	)
	default int speechHeight()
	{
		return 220;
	}

	@Range(min = 0, max = 30000)
	@ConfigItem(
		keyName = "globalCooldownMs",
		name = "Minimum gap (ms)",
		description = "Nothing is said within this long of the previous line",
		section = speechSection,
		position = 5
	)
	default int globalCooldownMs()
	{
		return 3000;
	}

	@ConfigItem(
		keyName = "mirrorToChat",
		name = "Mirror speech to chatbox",
		description = "Overhead lines also appear in the chatbox as public chat under the follower's name",
		section = speechSection,
		position = 6
	)
	default boolean mirrorToChat()
	{
		return true;
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
		description = "Outfit profile worn while replacing a zombie thrall",
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

	@ConfigItem(
		keyName = "thrallMeleeAttackAnim",
		name = "Melee attack animation",
		description = "Player animation the follower plays when the zombie thrall attacks",
		section = thrallSection,
		position = 5
	)
	default int thrallMeleeAttackAnim()
	{
		return 390;
	}

	@ConfigItem(
		keyName = "thrallRangedAttackAnim",
		name = "Ranged attack animation",
		description = "Player animation the follower plays when the skeleton thrall attacks",
		section = thrallSection,
		position = 6
	)
	default int thrallRangedAttackAnim()
	{
		return 426;
	}

	@ConfigItem(
		keyName = "thrallMagicAttackAnim",
		name = "Magic attack animation",
		description = "Player animation the follower plays when the ghost thrall attacks",
		section = thrallSection,
		position = 7
	)
	default int thrallMagicAttackAnim()
	{
		return 1162;
	}

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

	// ------------------------------------------------------------ rule groups

	@ConfigItem(
		keyName = "groupBoss",
		name = "Boss phrases",
		description = "Rules in the \"boss\" group — tips, lore and jokes when a boss appears",
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
		description = "Rules in the \"health\" group — HP, prayer, poison, venom, skull and run energy",
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
		description = "Rules in the \"area\" group — comments on entering towns, dungeons and landmarks",
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
		description = "Rules in the \"idle\" group",
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
		description = "Rules in the \"gear\" group — comments when you equip notable items",
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
		description = "Rules in the \"quest\" group — comments when famous quest figures are nearby",
		section = groupsSection,
		position = 5
	)
	default boolean groupQuest()
	{
		return true;
	}

	@ConfigItem(
		keyName = "disabledGroups",
		name = "Other disabled groups",
		description = "Comma separated group names or rule ids to silence",
		section = groupsSection,
		position = 6
	)
	default String disabledGroups()
	{
		return "";
	}
}

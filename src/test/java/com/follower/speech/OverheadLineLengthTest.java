package com.follower.speech;

import com.follower.sim.Harness;
import com.follower.ui.GameFont;
import com.follower.ui.GameFontRepository;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Every line fits over the follower's head once it is filled in. QA, 15 Oct:
 * the only length check measured the template, so a line that read fine as
 * "{quest}, done and witnessed" ran to 86 characters with the longest quest
 * in it, and 70 lines were wider than the whole fixed-mode viewport. Measured
 * here as said - through the engine, with the longest value each placeholder
 * really takes - in the font the overlay draws with.
 *
 * <p>The memory lines are left to {@link MemoryLineLengthTest}, which files
 * the memory the way the rules do.
 */
public class OverheadLineLengthTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** Wider than this and the line is clipped in fixed mode unless the follower stands dead centre. */
	static final int OVERHEAD_MAX_PX = 480;

	/**
	 * The longest value each placeholder really takes: real NPC, item, quest
	 * and raid names, and the longest phrases the follower files itself.
	 */
	private static final Map<String, String> LONGEST = new LinkedHashMap<>();

	/** By rule: the longest a value can be where its trigger says what it is. */
	private static final Map<String, Map<String, String>> OVERRIDES = new LinkedHashMap<>();

	static
	{
		LONGEST.put("npc", "Thermonuclear smoke devil");
		LONGEST.put("foe", "Thermonuclear smoke devil");
		LONGEST.put("boss", "Chambers of Xeric Challenge Mode");
		LONGEST.put("item", "Tumeken's shadow (uncharged)");
		LONGEST.put("thing", "Tumeken's shadow (uncharged)");
		LONGEST.put("target", "Divine super combat potion(4)");
		LONGEST.put("value", "1.5B");
		LONGEST.put("skill", "Construction");
		LONGEST.put("level", "99");
		LONGEST.put("total", "2277");
		LONGEST.put("base", "99");
		LONGEST.put("nines", "twenty-three");
		LONGEST.put("ninesOrdinal", "twenty-third");
		LONGEST.put("sinceLast", "eleven months");
		LONGEST.put("count", "3000");
		LONGEST.put("ordinal", "seventy-seventh");
		LONGEST.put("hp", "12");
		LONGEST.put("maxHp", "99");
		LONGEST.put("hpPercent", "12");
		LONGEST.put("prayer", "9");
		LONGEST.put("damage", "84");
		LONGEST.put("days", "1095");
		LONGEST.put("world", "565");
		LONGEST.put("previous", "78");
		LONGEST.put("player", "MumbleWomble");
		LONGEST.put("name", "MumbleWomble");
		LONGEST.put("style", "ranged");
		LONGEST.put("from", "magic");
		LONGEST.put("want", "the Grand Exchange");
		LONGEST.put("wish", "ball of wool");
		LONGEST.put("souvenir", "the ball of wool you found me");
		LONGEST.put("here", "you hit harder than you ever had");
		LONGEST.put("nickname", "the local menace");
		LONGEST.put("candidate", "the local menace");
		LONGEST.put("lastTime", "finishing Desert Treasure II - The Fallen Empire");
		LONGEST.put("track", "Ballad of the Basilisk");
		LONGEST.put("quest", "Desert Treasure II - The Fallen Empire");
		LONGEST.put("page", "Seven Priestly Warriors");
		LONGEST.put("challenge", "ten kills");

		// Where the trigger narrows what can arrive.
		OVERRIDES.put("hesitated-wilderness", Map.of("thing", "the Wilderness"));
		OVERRIDES.put("hesitated-monster", Map.of("thing", "Thermonuclear smoke devil"));
		OVERRIDES.put("level-quick", Map.of("sinceLast", "ten minutes"));
		OVERRIDES.put("kill-count-first", Map.of("count", "1", "ordinal", "first"));
		OVERRIDES.put("kill-count-round", Map.of("count", "2500", "ordinal", "seventy-fifth"));
		OVERRIDES.put("kill-count-thousand", Map.of("ordinal", "3000th"));
		OVERRIDES.put("gifted-swap", Map.of("souvenir", "the soft clay you found me"));
		OVERRIDES.put("noticed-impling", Map.of("thing", "Eclectic impling"));
		OVERRIDES.put("noticed-landmark", Map.of("thing", "Notice board"));
		OVERRIDES.put("chronicle-page-boss", Map.of("page", "King Black Dragon"));
		OVERRIDES.put("chronicle-page-enemy", Map.of("page", "Witch's experiment"));
		OVERRIDES.put("chronicle-page-place", Map.of("page", "Baxtorian Falls"));
		OVERRIDES.put("chronicle-page-quest", Map.of("page", "Children of the Sun"));
	}

	/**
	 * Over the limit with the longest values, and waiting for new wording,
	 * which is the player's to approve: see overhead-text.json. Kept exact,
	 * so a line that is reworded comes off this list, and a line that grows
	 * past the limit cannot hide behind it.
	 */
	private static final List<String> AWAITING_WORDING = Arrays.asList(
		"area-brimhaven: The agility arena's here, if you fancy dodging darts and spikes for tickets.",
		"area-draynor: That manor up the road? Folk go in the front door and come out the back, running.",
		"area-draynor: The Wise Old Man robbed the bank here in broad daylight. I almost admire it.",
		"area-edgeville: People bank here before doing something brave. Or foolish. Usually both.",
		"area-ferox-enclave: Somebody carved a bit of peace out of the Wilderness. That took some doing.",
		"area-lumbridge: Named after the bridge over the River Lum, you know. The locals are very proud of it.",
		"area-musa-point: Luthas pays coin for banana crates. He never checks what else goes in them.",
		"ask-nickname: I'm thinking of filing you under {candidate}. Come and tell me if you mind.",
		"ask-nickname: I've been calling you {candidate} in the notes. Tell me if that's all right.",
		"boss-amoxliatl: Whatever they worshipped down here, it's kept on ice under all that green.",
		"boss-callisto: They say hunters made him this way. The hunters are not available for comment.",
		"boss-crazy-arch: The Archaeologist. When he shouts about books, MOVE. The pages explode.",
		"boss-hydra: The Hydra. Lure it over the vents, it only drops its guard once it's doused.",
		"boss-jad: Jad. Rears up tall, pray Magic. Slams down, pray Ranged. Nothing else matters.",
		"boss-kq: Kalphites go back to the old desert gods' scarabs, they say. She's the best of them.",
		"boss-kq: The Queen. Her first form shrugs off spells and arrows, so use a blade. Then swap.",
		"boss-kril: K'ril. Pray Melee, but his roar breaks through and empties it. Keep a restore up.",
		"boss-kril: Zamorak's general. A greater demon with a title, and the title does a lot of work.",
		"boss-leviathan: The Scar's where the gods drown their mistakes. Not all the way, it seems.",
		"boss-mole: Malignius Mortifer's potion, Wyson's garden, and now it's Falador's problem.",
		"boss-mole: The tunnels go everywhere, and she dug every one. You have to admire that.",
		"boss-nex: Saradomin's people froze her down here. The door was meant to stay shut.",
		"boss-nightmare: The Nightmare. She shows you every attack well in advance. They still hurt.",
		"boss-nightmare: The sisters of Slepe pray over the sleepers. She has other plans for them.",
		"boss-olm: Lightning down the lanes, crystals from above. The head sees everything.",
		"boss-sarachnis: A spider queen under a temple. Someone built ON TOP of that. On purpose.",
		"boss-skotizo: It set up under the Catacombs long ago, and nobody's managed to evict it.",
		"boss-sol: He punishes greed. Take the openings he gives you, not the ones you want.",
		"boss-sol: Varlamore's champion of champions. The crowd adores him from a safe distance.",
		"boss-vetion: The purple glow means trouble. It doesn't go with anything he's wearing, either.",
		"boss-vorkath: The ice pins you in place, then the spawn crawls out. Crumble it or eat the hit.",
		"boss-vorkath: Vorkath. When the pink fireball goes up, MOVE. That one's most of your health.",
		"boss-whisperer: The Whisperer. The lantern is your line back from the shadow realm. Mind it.",
		"boss-zalcano: Zalcano. Weapons mean nothing, it's pickaxes and imbued stone down here.",
		"boss-zulrah: Sacrifices kept it calm for generations. You are not a sacrifice. Probably.",
		"boss-zulrah: The serpent god of Zul-Andra. The villagers worship it AND fight it. Complicated.",
		"breather-bank: Everything we've dragged home is in there now. Safer than out here with us.",
		"chronicle-page-enemy: I saw {page} with my own eyes. That's how a page should start.",
		"chronicle-page-lore: A page for {page}. I'll keep my voice down while I write it.",
		"collection-add: The other ledger's got the {item} down. Mine's better organised.",
		"collection-add: The {item} goes in your list. The moment goes in mine.",
		"defended-dislike: The ledger says this place is good to us. The ledger doesn't have to stand in it.",
		"errand-study-crate-end: Wooden, nailed shut and deeply mysterious. Best crate I've seen all week.",
		"examine-echo: Another {target} for the pile of things we've looked at.",
		"examine-echo: Careful with that {target}. Oh, it's only a {target}. Carry on.",
		"examine-echo: Somebody put real effort into describing the {target}.",
		"examine-echo: That's the {target} noted, filed and forgotten by lunch.",
		"examine-echo: The {target}. Yes. Very much a {target}.",
		"examine-echo: There's a full page on the {target} somewhere. Not my best work.",
		"ge-sold: Somebody's bought your {item}. The economy sends its regards.",
		"gear-voidwaker: Three Wilderness bosses each gave up a piece for that. Generous of them.",
		"gifted-accept: Even trade, {wish} for {wish}. You'll hardly notice, but I very much will.",
		"gifted-accept: I took the fresh {wish} and left you my old one. That's only manners.",
		"gifted-accept: My old {wish} for your new one. You come out even, which is how I like it.",
		"gifted-accept: You've still got my old {wish}. I'm not in the business of leaving you short.",
		"gifted-swap: Out goes {souvenir}, in goes the {wish}, and you get my old one.",
		"gifted-swap: The {wish} bumps {souvenir}. Have my worn one, so you're not short.",
		"hesitated-monster: I'm going. I'm just not hurrying, with that {thing} there.",
		"hesitated-monster: One second. I want to be sure the {thing} has seen me and decided against it.",
		"kill-count-first: Your first {boss}. It goes in the book with the date.",
		"kill-count-first: {boss}, once. I've been saving a good page for this.",
		"kill-count-round: That makes {count} {boss}. The ledger has a column just for it.",
		"kill-count-round: Your {ordinal} {boss}. That page is filling up.",
		"kill-count-round: {boss}, {count} times. I've stopped bringing the notes; I know how it goes.",
		"kill-count-round: {count}. Hard to believe there was ever a first {boss}.",
		"kill-count-round: {ordinal} time. I could draw {boss} from memory now.",
		"kill-count-thousand: Your {ordinal} {boss}. I ran out of room in the margin around the six hundredth.",
		"kill-count-thousand: {count}. If {boss} keeps a book on you, it's a long one.",
		"kill-tally: {count} now. The {npc} population is having a bad day.",
		"last-time: Back, then. Last time it was {lastTime}, as I recall.",
		"last-time: Hello again. I still have the page open from {lastTime}.",
		"last-time: We finished on {lastTime}, if the notes are right. Shall we carry on?",
		"last-time: You're back. I was going over the notes on {lastTime}.",
		"level-after-a-gap: A {skill} level, the first in {sinceLast}. I'll admit that surprised me.",
		"level-after-a-gap: Back to {skill} after {sinceLast}. The ink had dried on that page.",
		"level-after-a-gap: {skill} again, after {sinceLast}. I'd stopped leaving the page open.",
		"level-base: {skill} was the one holding the base down. Not any more: {base} across the board.",
		"level-halfway: Halfway to ninety-nine in {skill}, by experience. Most people don't know that.",
		"level-halfway: Ninety-two in {skill}. The second half is the same size as the first. Sorry.",
		"level-halfway: Ninety-two. Half the experience to ninety-nine, whatever the number says.",
		"level-mastery-again: Ninety-nine {skill} makes your {ninesOrdinal}. The reserved pages are running out.",
		"level-mastery-again: That's {nines} at ninety-nine. Your {ninesOrdinal}, and yes, I'm counting.",
		"level-mastery-again: {skill} to ninety-nine, your {ninesOrdinal}. It doesn't get any smaller.",
		"level-mastery-first: Your first ninety-nine. I had a page reserved and never believed I'd use it.",
		"level-quick: Another {skill} level, {sinceLast} after the last. I can barely keep up.",
		"level-quick: {sinceLast} since the last one. The page for {skill} is getting crowded.",
		"loot-big: That's a {item}. That's a {item}!",
		"loot-nice: That {item} goes straight in the day's highlights.",
		"quest-ali-morrisane: Ali Morrisane, the world's greatest salesman. His words. Always his words.",
		"quest-aubury: Aubury. Sells runes, and knows a good deal more about them than he lets on.",
		"quest-aubury: There's more to that little shop than runes. Ask him about essence sometime.",
		"quest-boy: Every village has a boy who kicks things over hedges. Taverley has this one.",
		"quest-complete: That's {quest} closed. Their scroll is nice. Mine has footnotes.",
		"quest-complete: {quest}, done and witnessed. That deserves a full page.",
		"quest-duke-horacio: The Duke's handed out anti-dragon shields for years and never once run out.",
		"quest-father-aereck: Father Aereck. Between the church and the graveyard, he's never short of work.",
		"quest-hans: That's Hans. He's walked that castle circuit longer than anyone's been alive.",
		"quest-hazelmere: Hazelmere. One of the oldest, wisest gnomes alive, and he talks in signs.",
		"quest-hetty: She asked someone for a rat's tail once, and they actually brought her one.",
		"quest-kaqemeex: Keeper of the stone circle. The Zamorakians ruined the last one. Sore subject.",
		"quest-ned: Give Ned some wool and he'll make you a rope. Or a wig, if you're desperate.",
		"quest-oddenstein: Professor Oddenstein. Turns people into chickens. Turns them back, mostly.",
		"quest-oracle: People climb all this way for wisdom and come down confused. Same thing, really.",
		"quest-oziach: Sends greenhorns at Elvarg and sells armour to the survivors. A system.",
		"quest-regath: Regath deals in many things, he says. Mostly runes, from what I've seen.",
		"quest-sedridor: He studies the runes that move the world. And answers letters. Busy man.",
		"quest-sedridor: Sedridor, head wizard of the tower. He works from the basement, under all the others.",
		"quest-sir-tiffy: Temple Knight recruitment happens over cucumber sandwiches. Naturally.",
		"quest-traiborn: Traiborn. Brilliant wizard, hasn't a clue where anything is. Lovely company.",
		"quest-veronica: Veronica's been waiting by that gate a long while. Her fiance went inside.",
		"session-365: That's three hundred and sixty-five outings. I've got a page for every one.",
		"souvenir-lost: I had {souvenir} and now I don't. I'd rather not talk about it.",
		"souvenir-mention: I'm keeping {souvenir}, and that's not up for discussion.",
		"souvenir-mention: There's {souvenir} in my pocket and you've not asked about it once.",
		"want-fulfilled-fishing-guild: I like it here because nothing is trying to kill us. Do you know how rare that is?",
		"want-fulfilled-grand-exchange: Fortunes made and lost round here before lunch, and I get to watch it happen.",
		"want-fulfilled-grand-exchange: The Exchange. Everyone here wants something, and you can see it on them.",
		"want-fulfilled-lumbridge: The castle, the river, the old stone. Nothing happens here. That's the appeal.",
		"want-fulfilled: I asked for {want} and here we are. You're all right, you are.",
		"want-lumbridge: Can we go back to Lumbridge? Everyone starts there. I've got a page on it.",
		"want-lumbridge: I'd like to see Lumbridge again, when it suits. Old ground makes for good notes.",
		"wonders-about-you: What are we saving for, anyway? Don't tell me. I'll guess wrong on purpose.",
		"wonders-about-you: What do you do when I'm not here? Sleep, I suppose. I've never seen you do it.",
		"wonders-about-you: What's the best thing you've ever pulled out of that bag? Don't say bones.");

	private GameFont overheadFont() throws IOException
	{
		GameFontRepository fonts = new GameFontRepository(new Gson());
		fonts.load(folder.newFolder().toPath());
		return fonts.getByName("b12_full");
	}

	@Test
	public void everyLineFitsOverheadFilledWithTheLongestValues() throws IOException
	{
		GameFont font = overheadFont();
		Harness h = new Harness(folder.newFolder().toPath());
		h.gameTicks(1);

		TreeSet<String> over = new TreeSet<>();
		int measured = 0;
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.say == null)
			{
				continue;
			}
			for (String template : rule.say)
			{
				if (template.contains("{memory"))
				{
					continue;
				}
				TriggerEvent event = TriggerEvent.simple(TriggerEvent.Type.MANUAL);
				event.getPlaceholders().putAll(LONGEST);
				event.getPlaceholders().putAll(OVERRIDES.getOrDefault(rule.id, Map.of()));
				String said = h.engine.spokenText(template, event);
				measured++;
				assertFalse("every placeholder has a longest value: " + said, said.contains("{"));
				if (said.length() > SpeechEngine.OVERHEAD_MAX_CHARS || font.stringWidth(said) > OVERHEAD_MAX_PX)
				{
					over.add(rule.id + ": " + template);
				}
			}
		}
		assertTrue("the lines were measured", measured > 2000);
		assertEquals("lines over " + SpeechEngine.OVERHEAD_MAX_CHARS + " characters or "
			+ OVERHEAD_MAX_PX + " px, filled", new TreeSet<>(AWAITING_WORDING), over);
	}
}

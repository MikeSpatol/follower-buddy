package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Nothing said in a cutscene, or for a little while after one. Play test,
 * 9 Oct: a line was said over the opening of Children of the Sun's first
 * cutscene, and the player asked for the follower to keep quiet for a bit
 * once a scene is over too.
 *
 * <p>Chatter is dropped there, as under the mute. An occasion - a quest's
 * end, a boss, a pet - is the one line the moment has, so it waits the
 * scene out and is said after, within reason.
 */
public class SceneQuietTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** Game time: 600 ms a tick, so the gap between two lines can pass. */
	private final long[] ms = {1_000_000L};

	private final List<String> held = new ArrayList<>();

	private Harness harness(String... bodies) throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath(),
			"{\"version\": 1, \"rules\": [" + String.join(",", bodies) + "]}");
		h.engine.setClock(() -> ms[0]);
		h.engine.setGlobalCooldownMs(3000L);
		h.engine.setOnSuppressed((rule, why) -> held.add(rule.describe() + ":" + why));
		h.gameTicks(1);
		return h;
	}

	private static final String CHATTY = "{\"id\": \"chatty\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50}, \"say\": [\"ouch\"]}";

	private static final String QUEST = "{\"id\": \"quest\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"occasion\": true, \"when\": {\"type\": \"questComplete\"}, \"say\": [\"Done and witnessed.\"]}";

	private static final String LEVEL = "{\"id\": \"level\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"occasion\": true, \"when\": {\"type\": \"levelUp\", \"names\": [\"*\"]}, \"say\": [\"Attack 30.\"]}";

	private void ticks(Harness h, int count)
	{
		for (int i = 0; i < count; i++)
		{
			ms[0] += 600;
			h.gameTick();
		}
	}

	@Test
	public void chatterInTheQuietIsDroppedForTheScene() throws IOException
	{
		Harness h = harness(CHATTY);
		h.engine.setSceneQuiet(true);
		h.game.hitpoints(10, 99);
		ticks(h, 2);
		assertTrue("nothing said in the quiet", h.firedBy("chatty").isEmpty());
		assertEquals("and the transcript says why", "chatty:scene", held.get(0));

		h.engine.setSceneQuiet(false);
		ticks(h, 20);
		assertTrue("dropped, not kept for after", h.firedBy("chatty").isEmpty());
	}

	@Test
	public void theMuteStillSaysMuted() throws IOException
	{
		Harness h = harness(CHATTY);
		h.engine.setMuted(true);
		h.engine.setSceneQuiet(true);
		h.game.hitpoints(10, 99);
		ticks(h, 2);
		assertEquals("the setting is the reason first", "chatty:muted", held.get(0));
	}

	@Test
	public void theRuleHoldingTheFloorIsHeldToo() throws IOException
	{
		// The holder speaks through its own hush; a scene silences it as well.
		Harness h = harness("{\"id\": \"floor\", \"group\": \"t\", \"cooldownMs\": 0, \"hushMs\": 10000,"
			+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50}, \"say\": [\"ouch\"]}");
		h.engine.setSceneQuiet(true);
		h.game.hitpoints(10, 99);
		ticks(h, 2);
		assertTrue(h.firedBy("floor").isEmpty());
		assertEquals("floor:scene", held.get(0));
	}

	@Test
	public void anOccasionInASceneIsSaidAfterTheQuietAndNotBefore() throws IOException
	{
		Harness h = harness(QUEST);
		h.engine.setSceneQuiet(true);
		h.dispatch(TriggerEvent.questComplete("Children of the Sun"));
		ticks(h, 100);
		assertTrue("not over the scene", h.firedBy("quest").isEmpty());

		h.engine.setSceneQuiet(false);
		ticks(h, 1);
		assertEquals("said once it is over", 1, h.firedBy("quest").size());
		ticks(h, 20);
		assertEquals("and only once", 1, h.firedBy("quest").size());
	}

	@Test
	public void twoOccasionsInOneSceneAreBothSaidAfterIt() throws IOException
	{
		// The scene's wait is counted apart from the gap's, so the second
		// still has its gap to wait out after the first is said.
		Harness h = harness(QUEST, LEVEL);
		h.engine.setSceneQuiet(true);
		h.dispatch(TriggerEvent.questComplete("Children of the Sun"));
		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		ticks(h, 50);
		assertTrue(h.spoken.isEmpty());

		h.engine.setSceneQuiet(false);
		ticks(h, 1);
		assertEquals("one at a time", 1, h.spoken.size());
		ticks(h, 15);
		assertEquals("both said", 1, h.firedBy("quest").size());
		assertEquals(1, h.firedBy("level").size());
	}

	/**
	 * At the quietest chattiness the gap is 45 seconds, and the ten ticks an
	 * occasion waits for a gap lost the second of two every time.
	 */
	@Test
	public void twoOccasionsInOneSceneAreBothSaidAtAnyChattiness() throws IOException
	{
		Harness h = harness(QUEST, LEVEL);
		h.engine.setGlobalCooldownMs(45_000L);
		h.engine.setSceneQuiet(true);
		h.dispatch(TriggerEvent.questComplete("Children of the Sun"));
		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		ticks(h, 50);

		h.engine.setSceneQuiet(false);
		ticks(h, 1);
		assertEquals("one at a time", 1, h.spoken.size());
		ticks(h, 100);
		assertEquals("both said", 2, h.spoken.size());
		assertTrue("nothing dropped", held.isEmpty());
	}

	@Test
	public void aLineHeldInTheQuietTakesNoFloor() throws IOException
	{
		// Its hush would outlast the quiet and cost the occasion waiting.
		Harness h = harness(QUEST, "{\"id\": \"floor\", \"group\": \"t\", \"cooldownMs\": 0,"
			+ " \"hushMs\": 10000, \"when\": {\"type\": \"healthBelow\", \"percent\": 50}, \"say\": [\"ouch\"]}");
		h.engine.setSceneQuiet(true);
		h.dispatch(TriggerEvent.questComplete("Children of the Sun"));
		ticks(h, 50);
		h.game.hitpoints(10, 99);
		ticks(h, 2);
		assertEquals("floor:scene", held.get(0));

		h.engine.setSceneQuiet(false);
		ticks(h, 1);
		assertEquals("said as the quiet ends", 1, h.firedBy("quest").size());
	}

	@Test
	public void anOccasionAfterTheSceneWaitsOutAFloorTakenAfterIt() throws IOException
	{
		// The first is said, a line with a floor wins in its gap, and the
		// second waits for the floor as for the gap.
		Harness h = harness(QUEST, LEVEL, "{\"id\": \"floor\", \"group\": \"t\", \"cooldownMs\": 0,"
			+ " \"hushMs\": 10000, \"when\": {\"type\": \"healthBelow\", \"percent\": 50}, \"say\": [\"ouch\"]}");
		h.engine.setSceneQuiet(true);
		h.dispatch(TriggerEvent.questComplete("Children of the Sun"));
		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		ticks(h, 10);

		h.engine.setSceneQuiet(false);
		ticks(h, 1);
		assertEquals(1, h.spoken.size());
		h.game.hitpoints(10, 99);
		ticks(h, 40);
		assertEquals("both occasions said", 1, h.firedBy("quest").size());
		assertEquals(1, h.firedBy("level").size());
	}

	@Test
	public void aRemarkIsNotMadeInTheQuiet() throws IOException
	{
		// The word after a sketch, and its invite, are nobody's asking.
		Harness h = harness(CHATTY);
		h.engine.setSceneQuiet(true);
		h.engine.remark("There.");
		assertTrue(h.spoken.isEmpty());

		h.engine.setSceneQuiet(false);
		h.engine.remark("There.");
		assertEquals(1, h.spoken.size());
	}

	private static final String WAITS = "{\"id\": \"waits\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"waits\": true, \"when\": {\"type\": \"levelUp\", \"names\": [\"*\"]}, \"say\": [\"Prince Itzla.\"]}";

	/**
	 * Play test, 15 Oct (Children of the Sun): Prince Itzla's line came up as
	 * the cutscene opened and was lost to it. A line that waits is said after.
	 */
	@Test
	public void aLineThatWaitsIsSaidAfterTheScene() throws IOException
	{
		Harness h = harness(WAITS);
		assertFalse("waiting is not an occasion's other exemptions", h.rule("waits").isOccasion());
		h.engine.setSceneQuiet(true);
		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		ticks(h, 30);
		assertTrue("not over the scene", h.firedBy("waits").isEmpty());

		h.engine.setSceneQuiet(false);
		ticks(h, 1);
		assertEquals("said once it is over", 1, h.firedBy("waits").size());
	}

	/** And Sergeant Tobyn's, six seconds after a cat: it waits out the gap. */
	@Test
	public void aLineThatWaitsWaitsOutTheGap() throws IOException
	{
		Harness h = harness(CHATTY, WAITS);
		h.game.hitpoints(10, 99);
		ticks(h, 1);
		assertEquals(1, h.firedBy("chatty").size());
		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		ticks(h, 1);
		assertTrue("not in the gap", h.firedBy("waits").isEmpty());
		ticks(h, 10);
		assertEquals("said once the gap is over", 1, h.firedBy("waits").size());
		assertTrue("nothing dropped", held.stream().noneMatch(x -> x.startsWith("waits:")));
	}

	/** Every line about meeting a quest's people has its one chance kept. */
	@Test
	public void everyShippedQuestLineWaitsItsTurn() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> dropped = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if ("quest".equals(rule.group) && !rule.waitsItsTurn())
			{
				dropped.add(rule.id);
			}
		}
		assertTrue("quest lines a scene or a gap would drop: " + dropped, dropped.isEmpty());
	}

	@Test
	public void anOccasionKeptPastTheSceneWaitIsOldNews() throws IOException
	{
		Harness h = harness(QUEST);
		h.engine.setSceneQuiet(true);
		h.dispatch(TriggerEvent.questComplete("Children of the Sun"));
		ticks(h, SpeechEngine.SCENE_WAIT_TICKS + 5);
		assertTrue("given up on", held.contains("quest:scene"));

		h.engine.setSceneQuiet(false);
		ticks(h, 20);
		assertTrue("and not said after", h.firedBy("quest").isEmpty());
	}

	// ------------------------------------------------------- a conversation

	/**
	 * QA, 15 Oct: the quest's people are met by talking to them, so their
	 * lines came up as the box opened and the plugin dropped the words. A
	 * line that waits is held through the conversation and said after it.
	 */
	@Test
	public void aLineThatWaitsIsHeldThroughAConversation() throws IOException
	{
		Harness h = harness(WAITS);
		h.engine.getContext().setDialogueOpen(true);
		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		ticks(h, 60);
		assertTrue("not into the box", h.firedBy("waits").isEmpty());
		assertTrue("held, not dropped", held.isEmpty());

		h.engine.getContext().setDialogueOpen(false);
		ticks(h, 2);
		assertEquals("said once it is over", 1, h.firedBy("waits").size());
	}

	@Test
	public void chatterInAConversationIsNotHeldForIt() throws IOException
	{
		// Handed over as ever; the plugin drops it. A remark about the tree
		// two minutes after the tree is worse than none.
		Harness h = harness(CHATTY);
		h.engine.getContext().setDialogueOpen(true);
		h.game.hitpoints(10, 99);
		ticks(h, 2);
		assertEquals(1, h.firedBy("chatty").size());
	}

	/**
	 * QA, 15 Oct: Children of the Sun's roof moment was held over a scene and
	 * released when the quiet ran out, inside the conversation the scene had
	 * run on into - 26 of 26 runs, none heard.
	 */
	@Test
	public void anOccasionOutOfASceneAndIntoAConversationWaitsForItToEnd() throws IOException
	{
		Harness h = harness(QUEST);
		h.engine.setSceneQuiet(true);
		h.dispatch(TriggerEvent.questComplete("Children of the Sun"));
		ticks(h, 20);
		h.engine.getContext().setDialogueOpen(true);
		h.engine.setSceneQuiet(false);
		ticks(h, 40);
		assertTrue("not into the conversation", h.firedBy("quest").isEmpty());

		h.engine.getContext().setDialogueOpen(false);
		ticks(h, 2);
		assertEquals("said after it", 1, h.firedBy("quest").size());
		assertTrue(held.isEmpty());
	}

	private static final String NOT_IN_DIALOGUE =
		"{\"type\": \"none\", \"conditions\": [{\"type\": \"dialogueOpen\"}]}";

	/** A quest moment on a step of varbit 9632. */
	private static String moment(String id, int minimum, int maximum, int delay, String... more)
	{
		return "{\"id\": \"" + id + "\", \"group\": \"quest\", \"priority\": 60, \"cooldownMs\": 600000,"
			+ " \"once\": true, \"occasion\": true, \"delayTicks\": " + delay + ", \"when\": {\"type\": \"all\","
			+ " \"conditions\": [{\"type\": \"questVar\", \"varbit\": 9632, \"minimum\": " + minimum
			+ ", \"maximum\": " + maximum + "}" + (more.length == 0 ? "" : ", " + String.join(", ", more))
			+ "]}, \"say\": [\"" + id + "\"]}";
	}

	@Test
	public void whatAConversationsEndRaisesGoesBeforeALineHeldThroughIt() throws IOException
	{
		// The roof: Prince Itzla's line comes up during the escort box, and
		// the moment as it closes. The moment is about now, and the player
		// is about to talk to the Prince; his line keeps.
		Harness h = harness(WAITS, moment("roof", 16, 16, 0, NOT_IN_DIALOGUE));
		h.engine.getContext().setDialogueOpen(true);
		h.game.varbit(9632, 16);
		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		ticks(h, 10);
		h.engine.getContext().setDialogueOpen(false);
		ticks(h, 1);
		assertEquals("the moment first", 1, h.spoken.size());
		assertEquals("roof", h.spoken.get(0).rule.id);
		ticks(h, 10);
		assertEquals("then the held line, after the gap", 1, h.firedBy("waits").size());
	}

	@Test
	public void aMomentWhoseStepMovesOnInTheConversationIsStillSaid() throws IOException
	{
		// Children of the Sun's overheard, for a runner: held into Tobyn's
		// briefing, which moves the step on. It is still about the window.
		Harness h = harness(moment("overheard", 10, 10, 2));
		h.game.varbit(9632, 10);
		ticks(h, 1);
		h.engine.getContext().setDialogueOpen(true);
		ticks(h, 20);
		h.game.varbit(9632, 12);
		ticks(h, 2);
		h.engine.getContext().setDialogueOpen(false);
		ticks(h, 3);
		assertEquals(1, h.firedBy("overheard").size());
	}

	@Test
	public void aMomentKeptOverASceneThatMovedItsStepOnIsLetGoUnspent() throws IOException
	{
		// Up on the roof, straight into the Prince's questioning and its
		// scene: "Questioning" after the questioning is old news.
		Harness h = harness(moment("roof", 16, 16, 2));
		h.game.varbit(9632, 16);
		ticks(h, 1);
		h.engine.getContext().setDialogueOpen(true);
		ticks(h, 10);
		h.engine.setSceneQuiet(true);
		h.game.varbit(9632, 18);
		ticks(h, 60);
		h.engine.getContext().setDialogueOpen(false);
		h.engine.setSceneQuiet(false);
		ticks(h, 20);
		assertTrue(h.firedBy("roof").isEmpty());
		assertEquals("roof:moved-on", held.get(0));
		assertFalse("not spent", h.engine.getContext().hasSaidOnce("roof"));
	}

	@Test
	public void aLineKeptOverASceneWithNoStepIsStillSaid() throws IOException
	{
		// A quest's person has no step to move on from: Itzla's line is
		// still true after the interrogation.
		Harness h = harness(WAITS);
		h.engine.getContext().setDialogueOpen(true);
		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		ticks(h, 10);
		h.engine.setSceneQuiet(true);
		ticks(h, 60);
		h.engine.getContext().setDialogueOpen(false);
		h.engine.setSceneQuiet(false);
		ticks(h, 3);
		assertEquals(1, h.firedBy("waits").size());
	}

	// --------------------------------------------------------- a rest, a gap

	/**
	 * Behaviour QA, 15 Oct: Alina and Noah's line met the director's rest in
	 * a busy square and was gone for good, its hour's cooldown charged.
	 */
	@Test
	public void aLineThatWaitsWaitsOutOneRest() throws IOException
	{
		Harness h = harness(CHATTY, WAITS);
		SpeechRule chatty = h.rule("chatty");
		for (int i = 0; i < 3; i++)
		{
			h.engine.getDirector().noteSpoke(chatty, ms[0]);
		}
		assertTrue(h.engine.getDirector().isRelaxing(ms[0]));

		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		h.game.hitpoints(10, 99);
		ticks(h, 40);
		assertTrue("not through the rest", h.firedBy("waits").isEmpty());
		assertTrue("chatter is dropped by it", held.contains("chatty:relax"));

		ticks(h, SpeechEngine.REST_WAIT_TICKS);
		assertEquals("said after it", 1, h.firedBy("waits").size());
		assertFalse(held.contains("waits:relax"));
	}

	// ------------------------------------------------------- the empty chair

	private static final String PERSON = "{\"id\": \"person\", \"group\": \"t\", \"cooldownMs\": 0,"
		+ " \"waits\": true, \"when\": {\"type\": \"npcSpawn\", \"names\": [\"Prince Itzla Arkan\"]},"
		+ " \"say\": [\"Prince Itzla.\"]}";

	/** Three minutes and more with nobody at the keys, and somebody walks up. */
	private Harness unattended() throws IOException
	{
		Harness h = harness(PERSON);
		ticks(h, 320);
		assertTrue(h.engine.getContext().getUnattendedTicks() >= 300);
		h.dispatch(TriggerEvent.npc(TriggerEvent.Type.NPC_SPAWN, 12345, "Prince Itzla Arkan"));
		return h;
	}

	/**
	 * QA, 15 Oct: a line with one chance that came while the player was away
	 * from the keys was dropped by the damper and its cooldown charged: a
	 * quest's person, the two-hour mark, a souvenir lost. A line that waits
	 * is kept for them coming back.
	 */
	@Test
	public void aLineThatWaitsIsKeptForThePlayersReturn() throws IOException
	{
		Harness h = unattended();
		ticks(h, 100);
		assertTrue("not to the empty chair", h.firedBy("person").isEmpty());

		h.game.cameraMoved();
		ticks(h, 1);
		assertEquals("said once they are back", 1, h.firedBy("person").size());
		assertTrue("nothing dropped", held.isEmpty());
	}

	@Test
	public void aLineKeptForTheReturnIsOldNewsInTheEnd() throws IOException
	{
		Harness h = unattended();
		ticks(h, SpeechEngine.SCENE_WAIT_TICKS + 5);
		assertTrue("given up on", held.contains("person:unattended"));

		h.game.cameraMoved();
		ticks(h, 20);
		assertTrue("and not said after", h.firedBy("person").isEmpty());
	}

	// ------------------------------------------------------- two on one pass

	private static String meeting(String id, int priority, String who)
	{
		return "{\"id\": \"" + id + "\", \"group\": \"t\", \"priority\": " + priority + ", \"cooldownMs\": 0,"
			+ " \"waits\": true, \"when\": {\"type\": \"npcNearby\", \"within\": 5, \"names\": [\"" + who + "\"]},"
			+ " \"say\": [\"" + id + ".\"]}";
	}

	/**
	 * Behaviour QA, 15 Oct: Prince Itzla and Sergeant Tobyn came into view on
	 * one tick. Level in priority, Tobyn won on file order and Itzla's edge
	 * was spent with him still in view: 26 of 26 runs, never said.
	 */
	@Test
	public void twoLinesThatWaitRisingLevelOnOnePassAreBothSaid() throws IOException
	{
		Harness h = harness(meeting("tobyn", 35, "Sergeant Tobyn"), meeting("itzla", 35, "Prince Itzla Arkan"));
		h.game.spawnNpc(12645, "Sergeant Tobyn", 0);
		h.game.spawnNpc(12646, "Prince Itzla Arkan", 0);
		ticks(h, 30);
		assertEquals(1, h.firedBy("tobyn").size());
		assertEquals("said behind him", 1, h.firedBy("itzla").size());
		assertTrue("nothing dropped", held.isEmpty());
	}

	/** A point below is outranked on purpose: a callback over its base, one line a meeting. */
	@Test
	public void aLineOutrankedOnPurposeStillStandsAside() throws IOException
	{
		Harness h = harness(meeting("tobyn-after", 36, "Sergeant Tobyn"), meeting("tobyn", 35, "Sergeant Tobyn"));
		h.game.spawnNpc(12645, "Sergeant Tobyn", 0);
		ticks(h, 30);
		assertEquals(1, h.firedBy("tobyn-after").size());
		assertTrue("the base is not said behind its callback", h.firedBy("tobyn").isEmpty());
	}

	/**
	 * A hold can outlast a short cooldown: a health warning, five seconds
	 * apart, kept through a long conversation while the health dips again.
	 * Found by the scene soak: the second copy, queued behind the first, was
	 * said straight after it once the box closed.
	 */
	@Test
	public void aLineWaitingItsTurnIsNotQueuedASecondTime() throws IOException
	{
		Harness h = harness("{\"id\": \"warn\", \"group\": \"t\", \"cooldownMs\": 5000, \"occasion\": true,"
			+ " \"when\": {\"type\": \"healthBelow\", \"percent\": 50}, \"say\": [\"Careful.\"]}");
		h.engine.getContext().setDialogueOpen(true);
		for (int dip = 0; dip < 3; dip++)
		{
			h.game.hitpoints(10, 99);
			ticks(h, 12);
			h.game.hitpoints(99, 99);
			ticks(h, 2);
		}
		h.game.hitpoints(10, 99);
		ticks(h, 1);
		h.engine.getContext().setDialogueOpen(false);
		ticks(h, 40);
		assertEquals("said once for the conversation", 1, h.firedBy("warn").size());
	}

	/** Behaviour QA, 15 Oct: the two shipped lines the damper took in an AFK, 5 of 5 and 4 of 4. */
	@Test
	public void theLinesAnAbsenceCostWaitTheirTurn() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		for (String id : new String[]{"been-at-it-two-hours", "souvenir-lost"})
		{
			assertTrue(id, h.rule(id).waitsItsTurn());
			assertFalse("waiting, not an occasion's exemptions: " + id, h.rule(id).isOccasion());
		}
	}

	/**
	 * QA, 15 Oct (probe P7): two occasions behind one ordinary line. The
	 * first waits out the gap and is said; the second's wait was counted from
	 * when it first blocked, ran out a tick before the first's gap did, and
	 * it was dropped.
	 */
	@Test
	public void theGapAnOccasionWaitsOutIsTheOneInFrontOfIt() throws IOException
	{
		Harness h = harness(CHATTY, QUEST, LEVEL);
		h.engine.setGlobalCooldownMs(6000L);
		h.game.hitpoints(10, 99);
		ticks(h, 1);
		assertEquals(1, h.firedBy("chatty").size());
		h.dispatch(TriggerEvent.questComplete("Sheep Shearer"));
		h.dispatch(TriggerEvent.levelUp("Attack", 30));
		ticks(h, 40);
		assertEquals("both said", 1, h.firedBy("quest").size());
		assertEquals(1, h.firedBy("level").size());
		assertTrue("nothing dropped", held.isEmpty());
	}

	/** The payoffs of something the follower set up itself: the setup was heard, so is its end. */
	@Test
	public void everyShippedPayoffWaitsItsTurn() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		for (String id : new String[]{"advice-taken", "advice-ignored", "bet-won", "bet-lost",
			"challenge-met", "challenge-failed"})
		{
			assertTrue(id, h.rule(id).waitsItsTurn());
			assertFalse("waiting, not an occasion's exemptions: " + id, h.rule(id).isOccasion());
		}
	}
}

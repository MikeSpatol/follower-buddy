// public:file chronicle
package com.follower.chronicle;

import com.follower.speech.TriggerContext;
import com.follower.ui.GameFontRepository;
import com.google.gson.Gson;
import java.awt.Canvas;
import java.awt.Graphics2D;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.QuestState;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Opening a plate across the window, turning it there and putting it back:
 * real presses and keys on the real window, each landing on the tick.
 *
 * <p>Play review: the caption's arrows used to be cleared at the start of a
 * frame and set again after the slow drawing of the plate, so a press on an
 * arrow in between fell through and closed the enlarged plate instead of
 * turning it. The frame's pressable places are now handed over together
 * once it is drawn.
 */
public class ChronicleWindowTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final Canvas canvas = new Canvas();
	private ChronicleWindow window;
	private Client client;
	private GameFontRepository fonts;

	@Before
	public void setUp() throws IOException
	{
		client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getViewportWidth":
						return ChronicleLayout.WIDTH;
					case "getViewportHeight":
						return ChronicleLayout.HEIGHT;
					case "getMouseCanvasPosition":
						return new net.runelite.api.Point(0, 0);
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == args[0];
					default:
						Class<?> type = method.getReturnType();
						return type == int.class ? 0 : type == boolean.class ? false : null;
				}
			});
		fonts = new GameFontRepository(new Gson());
		fonts.load(folder.newFolder().toPath()); // public:cut devfonts
		// public:else fonts.load();
		ChronicleLoader loader = new ChronicleLoader(new Gson());
		loader.load(folder.newFolder().toPath());
		window = new ChronicleWindow(client, null, null, null, fonts, loader);
		window.open();
		draw();
	}

	private void draw()
	{
		BufferedImage image = new BufferedImage(ChronicleLayout.WIDTH, ChronicleLayout.HEIGHT, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		window.render(g);
		g.dispose();
	}

	/** A press, then the tick it waits for, then the frame after. */
	private void press(int x, int y)
	{
		window.clickAdapter.mousePressed(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0,
			InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
		window.tick();
		draw();
	}

	private void escape()
	{
		window.keyAdapter.keyPressed(new KeyEvent(canvas, KeyEvent.KEY_PRESSED, 0, 0,
			KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED));
		draw();
	}

	private Object field(String name) throws Exception
	{
		Field f = ChronicleWindow.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(window);
	}

	private boolean enlarged() throws Exception
	{
		return (Boolean) field("enlarged");
	}

	/** The middle of the page's plate: Cook's Assistant, the first page, has three. */
	private void pressThePlate()
	{
		press(ChronicleLayout.PAGE_TEXT_X + ChronicleLayout.PLATE_W / 2,
			ChronicleLayout.BODY_TOP + ChronicleLayout.PLATE_H / 2);
	}

	private void pressNextArrow()
	{
		java.awt.Rectangle big = ChronicleLayout.BIG_PLATE;
		press(big.x + big.width - 7, ChronicleLayout.BIG_CAPTION_BASELINE - 4);
	}

	@Test
	public void aPressOnThePlateOpensItOnTheTick() throws Exception
	{
		window.clickAdapter.mousePressed(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0,
			InputEvent.BUTTON1_DOWN_MASK, ChronicleLayout.PAGE_TEXT_X + 20, ChronicleLayout.BODY_TOP + 20,
			1, false, MouseEvent.BUTTON1));
		assertFalse("not before the tick", enlarged());
		window.tick();
		assertTrue(enlarged());
	}

	@Test
	public void theArrowsTurnTheEnlargedPlateAndLeaveItOpen() throws Exception
	{
		pressThePlate();
		assertTrue(enlarged());
		pressNextArrow();
		assertTrue("still enlarged", enlarged());
		assertEquals("on to the second plate", 1, field("plateIndex"));
		pressNextArrow();
		assertEquals(2, field("plateIndex"));
	}

	@Test
	public void aPressAnywhereElseOnItPutsItBack() throws Exception
	{
		pressThePlate();
		press(ChronicleLayout.WIDTH / 2, ChronicleLayout.BIG_PLATE.y + 20);
		assertFalse(enlarged());
		assertTrue("and the window stays open", window.isOpen());
	}

	@Test
	public void aTabPutsItBackToo() throws Exception
	{
		pressThePlate();
		java.awt.Rectangle quests = ChronicleLayout.tabs(5)[0];
		press(quests.x + 10, quests.y + 10);
		assertFalse(enlarged());
	}

	/** A press alone, before the tick it waits for. */
	private void pressOnly(int x, int y)
	{
		window.clickAdapter.mousePressed(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0,
			InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
	}

	@Test
	public void aTabChangesOnTheTickAndASecondPressBeforeItIsIgnored() throws Exception
	{
		java.awt.Rectangle[] tabs = ChronicleLayout.tabs(5);
		pressOnly(tabs[1].x + 10, tabs[1].y + 10);
		pressOnly(tabs[2].x + 10, tabs[2].y + 10);
		assertEquals("not before the tick", 0, field("tab"));
		window.tick();
		assertEquals("the first press, as the game takes it", 1, field("tab"));
	}

	@Test
	public void pressingTheTabYouAreOnKeepsYourPlace() throws Exception
	{
		press(ChronicleLayout.LIST.x + 20, ChronicleLayout.ROW_BAND_TOP + ChronicleLayout.ROW_HEIGHT + 7);
		assertEquals("the second quest", "quest-priest-in-peril", field("selectedId"));
		java.awt.Rectangle quests = ChronicleLayout.tabs(5)[0];
		press(quests.x + 10, quests.y + 10);
		assertEquals("still on it", "quest-priest-in-peril", field("selectedId"));
	}

	@Test
	public void escapeBacksOutOfThePlateBeforeItClosesTheBook() throws Exception
	{
		pressThePlate();
		escape();
		assertFalse(enlarged());
		assertTrue("the first Escape only puts the plate back", window.isOpen());
		escape();
		assertFalse(window.isOpen());
	}

	@Test
	public void theArrowsOnThePageTurnItWithoutEnlarging() throws Exception
	{
		// The caption line sits under the plate's eight rows.
		int baseline = ChronicleLayout.BODY_TOP + ChronicleLayout.LINE_HEIGHT - 2
			+ ChronicleLayout.PLATE_ROWS * ChronicleLayout.LINE_HEIGHT;
		press(ChronicleLayout.PAGE_TEXT_RIGHT - 7, baseline - 4);
		assertEquals(1, field("plateIndex"));
		assertFalse(enlarged());
	}

	private List<String> pageTexts() throws Exception
	{
		List<String> texts = new ArrayList<>();
		@SuppressWarnings("unchecked")
		List<ChronicleLayout.Line> lines = (List<ChronicleLayout.Line>) field("pageLines");
		for (ChronicleLayout.Line line : lines)
		{
			texts.add(line.text);
		}
		return texts;
	}

	/**
	 * No quest before its time, on the page itself: a passage waiting on a
	 * quest stays off the open page until the quest is done, then is there
	 * without the page being turned, and its gate is never drawn.
	 */
	@Test
	public void aPassageWaitingOnAQuestAppearsOnTheOpenPageOnceItIsDone() throws Exception
	{
		Path data = folder.newFolder().toPath();
		Files.write(data.resolve(ChronicleLoader.FILE_NAME), ("{\"version\": 1, \"entries\": [{"
			+ "\"id\": \"quest-x\", \"kind\": \"quest\", \"name\": \"X\", \"unlock\": {\"always\": true},"
			+ "\"summary\": \"Before.\\n\\n{done:RECIPE_FOR_DISASTER} After.\","
			+ "\"sections\": [{\"heading\": \"What happened\", \"body\": \"Things.\"}]}]}")
			.getBytes(StandardCharsets.UTF_8));
		ChronicleLoader loader = new ChronicleLoader(new Gson());
		loader.load(data);
		ChronicleProgress progress = new ChronicleProgress(client, loader, () -> null);
		window = new ChronicleWindow(client, null, null, null, fonts, loader);
		window.attach(progress);
		window.openAt("quest-x");
		draw();
		assertTrue(pageTexts().toString(), pageTexts().contains("Before."));
		assertFalse("not before its quest: " + pageTexts(), pageTexts().contains("After."));

		progress.setQuestState("RECIPE_FOR_DISASTER", QuestState.FINISHED);
		draw();
		assertTrue("there once it is done: " + pageTexts(), pageTexts().contains("After."));
		for (String text : pageTexts())
		{
			assertFalse("a gate drawn on the page: " + text, text.contains("{done"));
		}
	}

	private List<ChronicleLayout.Style> pageStyles() throws Exception
	{
		List<ChronicleLayout.Style> styles = new ArrayList<>();
		@SuppressWarnings("unchecked")
		List<ChronicleLayout.Line> lines = (List<ChronicleLayout.Line>) field("pageLines");
		for (ChronicleLayout.Line line : lines)
		{
			styles.add(line.style);
		}
		return styles;
	}

	/** A window on a book of the one page given, as JSON, with the memory and sketches given. */
	private void openOn(String page, String id, TriggerContext memory, SketchBook sketches) throws IOException
	{
		Path data = folder.newFolder().toPath();
		Files.write(data.resolve(ChronicleLoader.FILE_NAME), ("{\"version\": 1, \"entries\": [" + page + "]}")
			.getBytes(StandardCharsets.UTF_8));
		ChronicleLoader loader = new ChronicleLoader(new Gson());
		loader.load(data);
		window = new ChronicleWindow(client, null, null, null, fonts, loader);
		window.attach(new ChronicleProgress(client, loader, () -> memory));
		if (sketches != null)
		{
			window.attach(sketches);
		}
		window.openAt(id);
		draw();
	}

	private static final String GOBLIN_PAGE = "{\"id\": \"enemy-goblin\", \"kind\": \"enemy\", \"name\": \"Goblin\","
		+ "\"unlock\": {\"always\": true}, \"summary\": \"Green.\","
		+ "\"tally\": {\"label\": \"Goblins you've killed while I watched\", \"of\": \"kill:goblin\"}}";

	/**
	 * The page's own number, under the plate: not there until the follower
	 * has counted one, then there, and moving as the count does, without
	 * the page being turned.
	 */
	@Test
	public void aCreaturePageShowsYourKillsOnlyOnceThereAreAny() throws Exception
	{
		TriggerContext memory = new TriggerContext(null);
		openOn(GOBLIN_PAGE, "enemy-goblin", memory, null);
		for (String text : pageTexts())
		{
			assertFalse("no number before there is one: " + text, text.contains("while I watched"));
		}
		assertEquals("Green.", pageTexts().get(0));

		memory.tally("kill:goblin");
		memory.tally("kill:goblin");
		draw();
		assertEquals("first, before the summary", "Goblins you've killed while I watched: 2", pageTexts().get(0));
		assertEquals(ChronicleLayout.Style.TALLY, pageStyles().get(0));

		memory.tally("kill:goblin");
		draw();
		assertEquals("Goblins you've killed while I watched: 3", pageTexts().get(0));
	}

	private void set(String name, Object value) throws Exception
	{
		Field f = ChronicleWindow.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(window, value);
	}

	/** The goblin's page with forty short paragraphs, long enough to scroll a good way. */
	private static String longGoblinPage()
	{
		StringBuilder summary = new StringBuilder();
		for (int i = 1; i <= 40; i++)
		{
			summary.append(i > 1 ? "\\n\\n" : "").append("Paragraph ").append(i).append('.');
		}
		return GOBLIN_PAGE.replace("\"Green.\"", "\"" + summary + "\"");
	}

	private String topLine() throws Exception
	{
		return pageTexts().get((Integer) field("pageScroll"));
	}

	/**
	 * QA: one more goblin killed while the page was open sent it back to
	 * the top, because the number is part of what the page is laid out from.
	 */
	@Test
	public void aKillWhileYouReadKeepsYourPlace() throws Exception
	{
		TriggerContext memory = new TriggerContext(null);
		memory.tally("kill:goblin");
		openOn(longGoblinPage(), "enemy-goblin", memory, null);
		set("pageScroll", 20);
		draw();
		String reading = topLine();

		memory.tally("kill:goblin");
		draw();
		assertEquals(20, field("pageScroll"));
		assertEquals(reading, topLine());
	}

	@Test
	public void theFirstKillWhileYouReadMovesYouPastItsNewLine() throws Exception
	{
		TriggerContext memory = new TriggerContext(null);
		openOn(longGoblinPage(), "enemy-goblin", memory, null);
		set("pageScroll", 20);
		draw();
		String reading = topLine();

		memory.tally("kill:goblin");
		draw();
		assertEquals("the number came in above", "Goblins you've killed while I watched: 1", pageTexts().get(0));
		assertEquals("and the same line is at the top", reading, topLine());
	}

	/** The frame as drawn, to look at its pixels. */
	private BufferedImage picture()
	{
		BufferedImage image = new BufferedImage(ChronicleLayout.WIDTH, ChronicleLayout.HEIGHT, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		window.render(g);
		g.dispose();
		return image;
	}

	private static int count(BufferedImage image, java.awt.Rectangle area, int rgb)
	{
		int n = 0;
		for (int y = area.y; y < area.y + area.height; y++)
		{
			for (int x = area.x; x < area.x + area.width; x++)
			{
				n += (image.getRGB(x, y) & 0xffffff) == rgb ? 1 : 0;
			}
		}
		return n;
	}

	/**
	 * QA: the number was drawn in the prose's pale face and read as an odd
	 * one-line paragraph. It is the header's idiom now: an orange label and
	 * a white number.
	 */
	@Test
	public void theNumberIsDrawnAsALabelAndItsValue() throws Exception
	{
		TriggerContext memory = new TriggerContext(null);
		memory.tally("kill:goblin");
		openOn(GOBLIN_PAGE, "enemy-goblin", memory, null);
		java.awt.Rectangle row = new java.awt.Rectangle(ChronicleLayout.PAGE_TEXT_X, ChronicleLayout.BODY_TOP,
			ChronicleLayout.PLATE_W, ChronicleLayout.LINE_HEIGHT);
		BufferedImage image = picture();
		assertTrue("an orange label", count(image, row, ChronicleLayout.TITLE_ORANGE) > 0);
		assertTrue("a white number", count(image, row, ChronicleLayout.BODY_WHITE) > 0);
		assertEquals("no prose face", 0, count(image, row, ChronicleLayout.NOTE_PALE));
	}

	@Test
	public void theNumberSplitsAfterItsLabelsColon()
	{
		assertEquals(Arrays.asList("Goblins you've killed while I watched: ", "214"),
			Arrays.asList(ChronicleWindow.tallyParts("Goblins you've killed while I watched: 214")));
		assertEquals(Arrays.asList("A label run onto two lines", ""),
			Arrays.asList(ChronicleWindow.tallyParts("A label run onto two lines")));
	}

	@Test
	public void aBossPageShowsTheGamesKillCount() throws Exception
	{
		TriggerContext memory = new TriggerContext(null);
		openOn("{\"id\": \"boss-giant-mole\", \"kind\": \"boss\", \"name\": \"Giant Mole\","
			+ "\"unlock\": {\"always\": true}, \"summary\": \"Big.\","
			+ "\"tally\": {\"label\": \"Moles seen off, all told\", \"of\": \"kc:giant mole\"}}",
			"boss-giant-mole", memory, null);
		assertEquals("Big.", pageTexts().get(0));
		memory.noteRecord("kc:giant mole", 41);
		draw();
		assertEquals("Moles seen off, all told: 41", pageTexts().get(0));
	}

	/** QA2: the page's number was the one number in the book not grouped in thousands. */
	@Test
	public void aLargeNumberIsGroupedAsTheBooksOthersAre() throws Exception
	{
		TriggerContext memory = new TriggerContext(null);
		memory.noteRecord("kc:giant mole", 1234);
		openOn("{\"id\": \"boss-giant-mole\", \"kind\": \"boss\", \"name\": \"Giant Mole\","
			+ "\"unlock\": {\"always\": true}, \"summary\": \"Big.\","
			+ "\"tally\": {\"label\": \"Moles seen off, all told\", \"of\": \"kc:giant mole\"}}",
			"boss-giant-mole", memory, null);
		assertEquals("Moles seen off, all told: 1,234", pageTexts().get(0));
		assertEquals(Arrays.asList("Moles seen off, all told: ", "1,234"),
			Arrays.asList(ChronicleWindow.tallyParts(pageTexts().get(0))));
	}

	@Test
	public void aPageNotYetWrittenKeepsItsNumberToItself() throws Exception
	{
		TriggerContext memory = new TriggerContext(null);
		memory.tally("kill:goblin");
		openOn(GOBLIN_PAGE.replace("{\"always\": true}", "{\"npcs\": [\"Goblin\"]}"), "enemy-goblin", memory, null);
		for (String text : pageTexts())
		{
			assertFalse(text, text.contains("while I watched"));
		}
	}

	private static BufferedImage paper()
	{
		return new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
	}

	private static final String LUMBRIDGE_PAGE = "{\"id\": \"location-lumbridge\", \"kind\": \"location\","
		+ "\"name\": \"Lumbridge\", \"unlock\": {\"always\": true}, \"summary\": \"Home.\","
		+ "\"plates\": [{\"sketch\": \"lumbridge\", \"regions\": [12850], \"caption\": \"Lumbridge, done in a hurry.\","
		+ "\"hint\": \"Lumbridge, when we're there.\"}]}";

	/** Pencil marks along the foot of the page's plate, where the pose is written. */
	private int inkAtThePlatesFoot()
	{
		int foot = ChronicleLayout.BODY_TOP + 2 + ChronicleLayout.PLATE_H - 2 - ChronicleWindow.POSE_RISE;
		return count(picture(), new java.awt.Rectangle(ChronicleLayout.PAGE_TEXT_X, foot - 10,
			ChronicleLayout.PLATE_W, 13), SketchStyle.INK);
	}

	/**
	 * A sketch that caught you mid-emote says so, pencilled on the drawing
	 * on the page and under the caption opened across the window; one drawn
	 * with you standing still, or before the book kept poses, says nothing.
	 *
	 * <p>QA: the pose used to have a line of its own under the caption, held
	 * on the page whenever any of its sketches had one, so it showed blank
	 * under every other plate and pushed the summary down the first screen.
	 */
	@Test
	public void aSketchOfYouMidDanceSaysSoOnTheDrawing() throws Exception
	{
		SketchBook book = new SketchBook(folder.newFolder().toPath());
		book.openFor("acct");
		book.put("lumbridge", paper(), paper(), "dance");
		openOn(LUMBRIDGE_PAGE, "location-lumbridge", null, book);
		assertTrue("pencilled on the drawing", inkAtThePlatesFoot() > 0);
		assertEquals("and the summary straight under the caption", "Home.",
			pageTexts().get(pageStyles().indexOf(ChronicleLayout.Style.CAPTION) + 1));
		pressThePlate();
		assertTrue("and opened across the window, drawn without trouble", enlarged());

		book.put("lumbridge", paper(), paper(), null);
		escape();
		assertEquals("drawn again standing still, the pose goes with the old drawing", 0, inkAtThePlatesFoot());
	}

	/**
	 * QA: a plate not sketched yet opened across the window as a sheet of
	 * bare paper, and lit up under the mouse as if it were worth pressing.
	 */
	@Test
	public void paperNotYetSketchedOnDoesNotOpen() throws Exception
	{
		SketchBook book = new SketchBook(folder.newFolder().toPath());
		book.openFor("acct");
		openOn(LUMBRIDGE_PAGE, "location-lumbridge", null, book);
		pressThePlate();
		assertFalse(enlarged());

		book.put("lumbridge", paper(), paper(), null);
		draw();
		pressThePlate();
		assertTrue("once it is drawn, it does", enlarged());
	}

	@Test
	public void aSketchFromBeforeThereWerePosesHasNoPoseLine() throws Exception
	{
		Path data = folder.newFolder().toPath();
		Path acct = data.resolve(SketchBook.DIR_NAME).resolve("acct");
		Files.createDirectories(acct);
		javax.imageio.ImageIO.write(paper(), "png", acct.resolve("lumbridge.png").toFile());
		javax.imageio.ImageIO.write(paper(), "png", acct.resolve("lumbridge.large.png").toFile());
		SketchBook book = new SketchBook(data);
		book.openFor("acct");
		openOn(LUMBRIDGE_PAGE, "location-lumbridge", null, book);
		assertEquals(0, inkAtThePlatesFoot());
		assertEquals("the summary straight under the caption", "Home.",
			pageTexts().get(pageStyles().indexOf(ChronicleLayout.Style.CAPTION) + 1));
	}

	@Test
	public void aPoseItHasNoWordsForWritesNothing() throws Exception
	{
		SketchBook book = new SketchBook(folder.newFolder().toPath());
		book.openFor("acct");
		book.put("lumbridge", paper(), paper(), "somersault");
		openOn(LUMBRIDGE_PAGE, "location-lumbridge", null, book);
		assertEquals(0, inkAtThePlatesFoot());
	}

	/** The pose under the caption opened across the window, in the caption's pale face. */
	private int poseUnderTheBigCaption()
	{
		int baseline = ChronicleLayout.BIG_CAPTION_BASELINE + ChronicleLayout.LINE_HEIGHT;
		return count(picture(), new java.awt.Rectangle(0, baseline - 10, ChronicleLayout.WIDTH, 13),
			ChronicleLayout.NOTE_PALE);
	}

	/**
	 * QA2: the temple sketch the Seven's page shares with Paterdomus carried
	 * its pose there too, so a jig was pencilled over their graves. A faction
	 * page has no pose, on the page or opened across the window; the same
	 * sketch on a place's page keeps it.
	 */
	@Test
	public void noPoseIsPencilledOverTheSevensGraves() throws Exception
	{
		String temple = "\"plates\": [{\"sketch\": \"paterdomus\", \"regions\": [13622],"
			+ " \"caption\": \"The temple over their graves.\", \"hint\": \"Pose for me at the temple.\"}]}";
		SketchBook book = new SketchBook(folder.newFolder().toPath());
		book.openFor("acct");
		book.put("paterdomus", paper(), paper(), "jig");

		openOn("{\"id\": \"location-paterdomus\", \"kind\": \"location\", \"name\": \"Paterdomus\","
			+ "\"unlock\": {\"always\": true}, \"summary\": \"A temple.\", " + temple,
			"location-paterdomus", null, book);
		assertTrue("a place's page keeps the pose", inkAtThePlatesFoot() > 0);
		pressThePlate();
		assertTrue("and opened across the window", poseUnderTheBigCaption() > 0);

		openOn("{\"id\": \"faction-seven-priestly-warriors\", \"kind\": \"faction\","
			+ "\"name\": \"Seven Priestly Warriors\", \"unlock\": {\"always\": true}, \"summary\": \"The Seven.\", "
			+ temple, "faction-seven-priestly-warriors", null, book);
		assertEquals("none on the Seven's page", 0, inkAtThePlatesFoot());
		pressThePlate();
		assertTrue(enlarged());
		assertEquals("nor opened across the window", 0, poseUnderTheBigCaption());
	}

	/**
	 * QA2: a place written where the book had no name for the spot showed
	 * its date and, under it, "Written when: you visit", written and still
	 * waiting at once. A dated place or land is written at itself, and any
	 * other page at a place it couldn't name: a readable page with a date
	 * and no place never shows Written when:.
	 */
	@Test
	public void aDatedPlaceWithNoPlaceKeptIsWrittenAtItself() throws Exception
	{
		ChronicleLoader loader = new ChronicleLoader(new Gson());
		loader.load(folder.newFolder().toPath());
		int places = 0;
		int others = 0;
		for (ChronicleEntry entry : loader.getEntries())
		{
			String kind = entry.kindOrEmpty();
			String[] where = ChronicleWindow.whereLine(entry, true, 20000L, "");
			assertEquals(entry.id, ChronicleWindow.WRITTEN_AT, where[0]);
			if (kind.equals("location") || kind.equals("world"))
			{
				places++;
				// As a header names a place: "The Abyss" is written at the Abyss.
				assertEquals(entry.id, ChronicleWindow.shortPlace(entry.name), where[1]);
			}
			else
			{
				others++;
				assertEquals(entry.id, "a place I couldn't name", where[1]);
			}
		}
		assertTrue("the book has places and lands", places >= 8);
		assertTrue("and quests, creatures and the faction", others >= 8);

		ChronicleEntry tower = loader.get("location-wizards-tower");
		assertEquals("a place kept still wins", "Lumbridge",
			ChronicleWindow.whereLine(tower, true, 20000L, "Lumbridge town and castle")[1]);
		assertEquals("undated, as before", "before I kept dates", ChronicleWindow.whereLine(tower, true, null, "")[1]);
		assertEquals("locked, as before", "you visit", ChronicleWindow.whereLine(tower, false, null, "")[1]);
	}

	private void wheel(int x, int rotation, double precise)
	{
		window.wheelAdapter.mouseWheelMoved(new java.awt.event.MouseWheelEvent(canvas,
			java.awt.event.MouseWheelEvent.MOUSE_WHEEL, 0, 0, x, 200, 0, 0, 0, false,
			java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation, precise));
		draw();
	}

	/**
	 * QA2: a touchpad's part-notch comes with a whole rotation of 0, which
	 * counted as a notch up, so scrolling down jerked the page back up. The
	 * parts add up now, in the way they turn; a notch is still three lines,
	 * and a flick of three notches is nine.
	 */
	@Test
	public void aTouchpadScrollsThePageTheWayItTurns() throws Exception
	{
		openOn(longGoblinPage(), "enemy-goblin", new TriggerContext(null), null);
		int x = ChronicleLayout.PAGE_TEXT_X + 50;
		wheel(x, 1, 1);
		assertEquals("a notch is three lines", 3, field("pageScroll"));
		wheel(x, 3, 3);
		assertEquals("a flick of three is nine", 12, field("pageScroll"));

		wheel(x, 0, 0.25);
		assertEquals("a quarter notch down never goes up", 12, field("pageScroll"));
		wheel(x, 0, 0.25);
		assertEquals("half a notch is a line", 13, field("pageScroll"));
		wheel(x, 0, 0.25);
		wheel(x, 0, 0.25);
		assertEquals("four quarters are a notch", 15, field("pageScroll"));
		for (int i = 0; i < 3; i++)
		{
			wheel(x, 0, 1 / 3.0);
		}
		assertEquals("three thirds are a notch", 18, field("pageScroll"));

		wheel(x, 0, 0.25);
		wheel(x, 0, -0.25);
		assertEquals("a turn back starts afresh", 18, field("pageScroll"));
		wheel(x, 0, -0.25);
		assertEquals(17, field("pageScroll"));
		wheel(x, 0, 0);
		assertEquals("no turn, no scroll", 17, field("pageScroll"));
	}

	@Test
	public void aTouchpadScrollsTheListARowANotch() throws Exception
	{
		StringBuilder pages = new StringBuilder();
		for (int i = 1; i <= 40; i++)
		{
			pages.append(i > 1 ? "," : "").append("{\"id\": \"quest-").append(i)
				.append("\", \"kind\": \"quest\", \"name\": \"Q").append(i)
				.append("\", \"unlock\": {\"always\": true}, \"summary\": \"S.\"}");
		}
		openOn(pages.toString(), "quest-1", new TriggerContext(null), null);
		int x = ChronicleLayout.SPLIT_X / 2;
		wheel(x, 0, 0.5);
		assertEquals(0, field("listScroll"));
		wheel(x, 0, 0.5);
		assertEquals(1, field("listScroll"));
		wheel(x, 0, -0.25);
		assertEquals("a part-notch up never goes down", 1, field("listScroll"));
	}

	/**
	 * QA2: a book with no pages at all drew the list's scrollbar and not the
	 * page's, so the window looked half drawn. Both are there, as in the game.
	 */
	@Test
	public void anEmptyBookStillHasBothScrollbars() throws Exception
	{
		openOn("", "nothing", new TriggerContext(null), null);
		BufferedImage image = picture();
		// The scrollbar's stand-in, drawn while its sprites have not arrived.
		int bar = 0x6a5c43;
		assertTrue("the list's", count(image, ChronicleLayout.LIST_SCROLL, bar) > 0);
		assertTrue("and the page's", count(image, ChronicleLayout.PAGE_SCROLL, bar) > 0);
	}
}

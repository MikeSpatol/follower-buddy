package com.follower.chronicle;

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
		fonts.load(folder.newFolder().toPath());
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
}

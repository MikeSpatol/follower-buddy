package com.follower;

import com.follower.appearance.ModelRepository;
import com.follower.appearance.SpotAnimRepository;
import com.follower.speech.RuleLoader;
import com.follower.ui.GameFontRepository;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * How much of the player's memory the plugin keeps.
 *
 * <p>It loads the whole equipment catalogue, every spotanim and every game font
 * and holds them for the session, inside a client that is already the largest
 * thing on most people's machines. That has never been measured - only the file
 * sizes on disk, which say almost nothing about the heap: an 865KB JSON of six
 * thousand small objects can land anywhere between a tenth and ten times that
 * once it is a Map of Entry.
 *
 * <p>The answer, measured: about six megabytes for the lot, in a client that
 * routinely holds several hundred. There is nothing to optimise here, and that
 * is worth knowing - the file sizes suggested otherwise and it would have been
 * easy to spend a day making the catalogues clever for no reason.
 *
 * <p>So these are regression budgets rather than targets. Each is roughly
 * triple what is actually used: far enough above that the measurement's own
 * noise can never fail it - four consecutive runs agreed to a tenth of a
 * megabyte - and close enough that a catalogue doubling does. If one of these
 * fails and the growth was deliberate, raise the number and say why in the
 * commit.
 */
public class HeapFootprintTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** The real dumps, if this machine has them. Skipped elsewhere. */
	private static final Path LIVE_DATA =
		Paths.get(System.getProperty("user.home"), ".runelite", "follower");

	private static long usedBytes()
	{
		Runtime runtime = Runtime.getRuntime();
		for (int i = 0; i < 4; i++)
		{
			System.gc();
			try
			{
				Thread.sleep(30);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}
		return runtime.totalMemory() - runtime.freeMemory();
	}

	private static void report(String what, long bytes, long budgetMb)
	{
		double mb = bytes / (1024.0 * 1024.0);
		System.out.printf("  %-28s %6.1f MB   (budget %d MB)%n", what, mb, budgetMb);
		assertTrue(what + " holds " + String.format("%.1f", mb)
			+ " MB, over its " + budgetMb + " MB budget - if that is a deliberate"
			+ " growth, raise the budget and say why", mb < budgetMb);
	}

	@Test
	public void theEquipmentCatalogueFitsInItsBudget() throws IOException
	{
		Path dump = LIVE_DATA.resolve("equipment-models.json");
		assumeTrue("no equipment dump on this machine", Files.isRegularFile(dump));

		long before = usedBytes();
		ModelRepository repository = new ModelRepository(new Gson());
		repository.load(LIVE_DATA);
		long after = usedBytes();

		// Held for the whole session so the outfit panel can search it.
		report("equipment catalogue", after - before, 7);
		assertTrue("the dump did not actually load", repository.getStatus() != null);
	}

	@Test
	public void theSpotAnimCatalogueFitsInItsBudget() throws IOException
	{
		Path dump = LIVE_DATA.resolve("spotanims.json");
		assumeTrue("no spotanim dump on this machine", Files.isRegularFile(dump));

		long before = usedBytes();
		SpotAnimRepository repository = new SpotAnimRepository(new Gson());
		repository.load(LIVE_DATA);
		long after = usedBytes();

		report("spotanim catalogue", after - before, 9);
	}

	@Test
	public void theGameFontsFitInTheirBudget() throws IOException
	{
		Path dump = LIVE_DATA.resolve("fonts.json");
		assumeTrue("no font dump on this machine", Files.isRegularFile(dump));

		// Twenty-one fonts of 256 glyphs each, every glyph a pixel mask. This
		// is the one most likely to surprise: the file is base64 and the heap
		// form is decoded bytes.
		long before = usedBytes();
		GameFontRepository fonts = new GameFontRepository(new Gson());
		fonts.load(LIVE_DATA);
		long after = usedBytes();

		report("game fonts", after - before, 3);
	}

	@Test
	public void theRuleSetFitsInItsBudget() throws IOException
	{
		// Three hundred rules with their notes and phrase lists, plus a
		// compiled pattern per name list once they have been evaluated.
		long before = usedBytes();
		RuleLoader loader = new RuleLoader(new Gson());
		loader.initialise(folder.newFolder().toPath());
		long after = usedBytes();

		report("rules and phrases", after - before, 2);
		assertTrue("the rules did not load", loader.getRules().size() > 100);
	}
}

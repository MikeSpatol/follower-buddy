package com.follower;

import com.follower.appearance.ModelRepository;
import com.follower.appearance.OutfitProfileStore;
import com.follower.appearance.SpotAnimRepository;
import com.follower.follower.StanceLibrary;
import com.follower.speech.RuleLoader;
import com.follower.ui.GameFontRepository;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;

/**
 * What the plugin pays before it can show anything.
 *
 * <p>Every one of these loads inside {@code startUp}, on the thread that
 * enables the plugin. That is a real freeze for the user, and it happens again
 * on every toggle, so it is worth knowing which file costs what rather than
 * guessing. As with the dispatch cost, this is a tripwire and a place to read
 * the numbers from, not a benchmark to tune against.
 */
public class StartupCostTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static long millis(Runnable work)
	{
		long start = System.nanoTime();
		work.run();
		return (System.nanoTime() - start) / 1_000_000;
	}

	@Test
	public void aFreshInstallStartsInAReasonableTime() throws IOException
	{
		Path dir = folder.newFolder().toPath();
		Gson gson = new Gson();

		long fonts = millis(() -> new GameFontRepository(gson).load(dir));
		long rules = millis(() -> new RuleLoader(gson).initialise(dir));
		long stances = millis(() ->
			new StanceLibrary(gson, new ModelRepository(gson)).load(dir));
		long spotAnims = millis(() -> new SpotAnimRepository(gson).load(dir));
		long profiles = millis(() -> new OutfitProfileStore(gson).load(dir));
		long models = millis(() -> new ModelRepository(gson).load(dir));

		long total = fonts + rules + stances + spotAnims + profiles + models;

		System.out.printf("%n  a fresh install's startUp:%n"
				+ "    fonts.json       %4d ms%n"
				+ "    phrases.json     %4d ms%n"
				+ "    stances.json     %4d ms%n"
				+ "    spotanims.json   %4d ms%n"
				+ "    profiles         %4d ms%n"
				+ "    equipment models %4d ms%n"
				+ "    ----------------------%n"
				+ "    total            %4d ms%n%n",
			fonts, rules, stances, spotAnims, profiles, models, total);

		// Enabling a plugin should not read as a hang. This is deliberately
		// loose - it is here to catch something going badly wrong, like a
		// resource being parsed once per rule.
		assertTrue("startUp's file loading took " + total + "ms", total < 5000);
	}

	@Test
	public void loadingIsNotQuadraticInTheRuleCount() throws IOException
	{
		// The shape that matters: doubling the rules should roughly double the
		// load, not square it. A per-rule scan of the whole file would not show
		// up at 260 rules and would be crippling at 2000.
		long small = millis(() -> loadRules(250));
		long large = millis(() -> loadRules(4000));

		System.out.printf("  250 rules %d ms, 4000 rules %d ms%n", small, large);

		// 16x the rules; anything past a few hundred times the work is a
		// different complexity class, not just a slower machine.
		assertTrue("250 rules took " + small + "ms and 4000 took " + large
			+ "ms, which is not linear", large < Math.max(small, 1) * 300 + 3000);
	}

	private void loadRules(int count)
	{
		try
		{
			Path dir = folder.newFolder().toPath();
			StringBuilder out = new StringBuilder("{\"version\": 1, \"rules\": [");
			for (int i = 0; i < count; i++)
			{
				if (i > 0)
				{
					out.append(',');
				}
				out.append("{\"id\": \"r").append(i).append("\", \"group\": \"t\","
					+ " \"when\": {\"type\": \"inRegion\", \"regions\": [").append(i)
					.append("]}, \"say\": [\"line\"]}");
			}
			out.append("]}");
			Files.write(dir.resolve(RuleLoader.FILE_NAME),
				out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

			RuleLoader loader = new RuleLoader(new Gson());
			loader.initialise(dir);
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
	}

	@Test
	public void theBundledResourcesAreReadOnceNotPerLookup() throws IOException
	{
		// A repository that re-read its file on every get would be invisible in
		// a unit test and ruinous in the render loop.
		Path dir = folder.newFolder().toPath();
		GameFontRepository fonts = new GameFontRepository(new Gson());
		fonts.load(dir);

		long lookups = millis(() ->
		{
			for (int i = 0; i < 200000; i++)
			{
				fonts.get(497);
				fonts.getByName("b12_full");
			}
		});

		System.out.printf("  400,000 font lookups in %d ms%n", lookups);
		assertTrue("font lookups cost " + lookups + "ms, which suggests they are"
			+ " doing more than a map read", lookups < 2000);
	}
}

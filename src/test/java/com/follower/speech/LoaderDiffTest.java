package com.follower.speech;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The upgrade path of phrases.json and dialogs.json, against the loaders as
 * they stood before {@link MergingLoader} (kept in {@code speech.before}).
 *
 * <p>Seeded random scenarios start each loader on a data folder like the ones
 * players arrive with: no file, 1.4.0's file, the shipped file, edited copies
 * (lines and rules deleted, added, reordered, switched off, renamed or
 * duplicated, junk entries, the "removed" list, nicknames narrowed or widened,
 * places and nodes edited), or a file that is empty, cut short, null, of
 * another version, or a folder; beside a merge record that is absent, 1.4.0's,
 * an edited one, corrupt, or a folder; and now and then a data folder that
 * cannot be made. Each is started twice, with an editor-style rewrite, a poll
 * and a reload between.
 *
 * <p>Everything a player or the client log could see must match: every file
 * left in the folder (the stamp without its clock time), the status line and
 * its problems, the rules in order with group and priority, nicknames, place
 * and boss names, which flood events are listened for, the trees, and every
 * log line with its level, logger, text and exception.
 */
public class LoaderDiffTest
{
	/** Scenarios per file; {@code -Dloaderdiff.scenarios=N} for a longer run. */
	private static final int SCENARIOS = Integer.getInteger("loaderdiff.scenarios", 400);

	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
	private Logger speech;
	private Level level;
	private boolean additive;

	@Before
	public void listen()
	{
		speech = (Logger) LoggerFactory.getLogger("com.follower.speech");
		level = speech.getLevel();
		additive = speech.isAdditive();
		speech.setLevel(Level.DEBUG);
		speech.setAdditive(false);
		logged.start();
		speech.addAppender(logged);
	}

	@After
	public void stopListening()
	{
		speech.detachAppender(logged);
		speech.setLevel(level);
		speech.setAdditive(additive);
	}

	/** One loader, before or now, as the scenarios drive it. */
	private static final class Driven
	{
		final BiConsumer<Path, Boolean> initialise;
		final BooleanSupplier reloadIfChanged;
		final Runnable reload;
		final Supplier<String> state;

		Driven(BiConsumer<Path, Boolean> initialise, BooleanSupplier reloadIfChanged, Runnable reload,
			Supplier<String> state)
		{
			this.initialise = initialise;
			this.reloadIfChanged = reloadIfChanged;
			this.reload = reload;
			this.state = state;
		}
	}

	/** One of the two files: its names, its shipped copies, how it is edited and loaded. */
	private static final class Kind
	{
		final String fileName;
		final String baseName;
		final String list;
		final JsonObject bundled;
		final JsonObject seen;
		final BiFunction<JsonObject, Random, JsonObject> mutate;
		final Supplier<Driven> before;
		final Supplier<Driven> now;

		Kind(String fileName, String baseName, String list, String seen,
			BiFunction<JsonObject, Random, JsonObject> mutate, Supplier<Driven> before, Supplier<Driven> now)
			throws IOException
		{
			this.fileName = fileName;
			this.baseName = baseName;
			this.list = list;
			this.bundled = resource("/com/follower/default-" + fileName);
			this.seen = resource(seen);
			this.mutate = mutate;
			this.before = before;
			this.now = now;
		}
	}

	@Test
	public void phrasesUpgradeAsBefore() throws IOException
	{
		check(new Kind(RuleLoader.FILE_NAME, RuleLoader.BASE_FILE, "rules", RuleLoader.UNRECORDED_BASE,
			LoaderDiffTest::mutateRules,
			() ->
			{
				com.follower.speech.before.RuleLoader loader = new com.follower.speech.before.RuleLoader(new Gson());
				return new Driven(loader::initialise, loader::reloadIfChanged, loader::reload,
					() -> rules(loader.getStatus(), loader.getErrors(), loader.getRules(), loader.getNicknames(),
						loader.getRegionNames(), loader.getBossNames(), loader::listensFor,
						loader.isVarbitEventRules(), loader.getFile()));
			},
			() ->
			{
				RuleLoader loader = new RuleLoader(new Gson());
				return new Driven(loader::initialise, loader::reloadIfChanged, loader::reload,
					() -> rules(loader.getStatus(), loader.getErrors(), loader.getRules(), loader.getNicknames(),
						loader.getRegionNames(), loader.getBossNames(), loader::listensFor,
						loader.isVarbitEventRules(), loader.getFile()));
			}));
	}

	@Test
	public void dialogsUpgradeAsBefore() throws IOException
	{
		check(new Kind(DialogLoader.FILE_NAME, DialogLoader.BASE_FILE, "trees", DialogLoader.UNRECORDED_BASE,
			LoaderDiffTest::mutateDialogs,
			() ->
			{
				com.follower.speech.before.DialogLoader loader = new com.follower.speech.before.DialogLoader(new Gson());
				return new Driven(loader::initialise, loader::reloadIfChanged, loader::reload,
					() -> dialogs(loader.getStatus(), loader.getErrors(), loader.getTrees(), loader.get("greeting"),
						loader.getFile()));
			},
			() ->
			{
				DialogLoader loader = new DialogLoader(new Gson());
				return new Driven(loader::initialise, loader::reloadIfChanged, loader::reload,
					() -> dialogs(loader.getStatus(), loader.getErrors(), loader.getTrees(), loader.get("greeting"),
						loader.getFile()));
			}));
	}

	/**
	 * The 1.4.0 files a merge with no record falls back on ship whole: the
	 * new loaders read an empty one as "no base" where the old rules loader
	 * gave up on the merge, and that can only differ if they do not.
	 */
	@Test
	public void theUnrecordedBasesShip() throws IOException
	{
		assertTrue(resource(RuleLoader.UNRECORDED_BASE).get("rules").getAsJsonArray().size() > 0);
		assertTrue(resource(DialogLoader.UNRECORDED_BASE).get("trees").getAsJsonArray().size() > 0);
	}

	private void check(Kind kind) throws IOException
	{
		Path dir = folder.newFolder(kind.list).toPath();
		for (int seed = 0; seed < SCENARIOS; seed++)
		{
			String before = scenario(dir, seed, kind, kind.before);
			String now = scenario(dir, seed, kind, kind.now);
			assertEquals(kind.fileName + " scenario " + seed, before, now);
		}
	}

	/** Runs one seeded scenario in an emptied {@code dir} and says what it left. */
	private String scenario(Path dir, int seed, Kind kind, Supplier<Driven> loaders) throws IOException
	{
		wipe(dir);
		logged.list.clear();
		Random r = new Random(seed * 7919L + 13);
		Path run = dir.resolve("run");
		Path file = run.resolve(kind.fileName);
		Path base = run.resolve(kind.baseName);
		StringBuilder rec = new StringBuilder();

		// Now and then the data folder is a file, so nothing can be written there.
		boolean blocked = r.nextInt(25) == 0;
		if (blocked)
		{
			write(run, "not a folder");
		}
		else
		{
			Files.createDirectories(run);
			int user = r.nextInt(6);
			if (user == 5)
			{
				Files.createDirectories(file);
			}
			else if (user > 0)
			{
				JsonObject from = user == 1 ? kind.seen : user == 2 ? kind.bundled : kind.mutate.apply(kind.bundled, r);
				write(file, garbageOr(kind.mutate.apply(from, r), r));
			}
			int record = r.nextInt(6);
			if (record == 1)
			{
				write(base, PRETTY.toJson(kind.seen));
			}
			else if (record == 2)
			{
				write(base, PRETTY.toJson(kind.mutate.apply(kind.bundled, r)));
			}
			else if (record == 3)
			{
				write(base, r.nextBoolean() ? "{ broken" : "");
			}
			else if (record == 4)
			{
				write(base, PRETTY.toJson(kind.mutate.apply(kind.seen, r)));
			}
			else if (record == 5)
			{
				// A folder in the way, with something in it so it cannot be replaced.
				Files.createDirectories(base);
				write(base.resolve("x"), "x");
			}
			rec.append("user ").append(user).append(" base ").append(record).append('\n');
		}

		for (int pass = 0; pass < 2; pass++)
		{
			Driven loader = loaders.get();
			loader.initialise.accept(run, r.nextInt(5) > 0);
			rec.append("== pass ").append(pass).append('\n').append(loader.state.get());
			folderState(run, kind, rec);
			if (pass == 0)
			{
				// Rewrite the file the way an editor might, then poll and reload.
				int edit = r.nextInt(4);
				if (Files.isRegularFile(file))
				{
					String was = read(file);
					if (edit == 0)
					{
						write(file, "");
					}
					else if (edit == 1)
					{
						write(file, was + "  ");
					}
					else if (edit == 2)
					{
						write(file, was.substring(0, was.length() / 2));
					}
				}
				rec.append("polled ").append(loader.reloadIfChanged.getAsBoolean()).append('\n')
					.append(loader.state.get());
				loader.reload.run();
				rec.append(loader.state.get());
			}
		}
		if (Files.isDirectory(run))
		{
			try (Stream<Path> files = Files.list(run))
			{
				files.sorted().forEach(p -> rec.append("has ").append(p.getFileName()).append('\n'));
			}
		}
		for (ILoggingEvent event : logged.list)
		{
			rec.append("log ").append(event.getLevel()).append(' ')
				.append(event.getLoggerName().replace(".before.", ".")).append(' ')
				.append(event.getFormattedMessage());
			IThrowableProxy thrown = event.getThrowableProxy();
			if (thrown != null)
			{
				rec.append(" ! ").append(thrown.getClassName()).append(": ").append(thrown.getMessage());
			}
			rec.append('\n');
		}
		return rec.toString();
	}

	private static void folderState(Path run, Kind kind, StringBuilder rec) throws IOException
	{
		rec.append("file ").append(read(run.resolve(kind.fileName))).append('\n');
		rec.append("base ").append(read(run.resolve(kind.baseName))).append('\n');
		rec.append("bak ").append(read(run.resolve(kind.fileName + ".bak"))).append('\n');
		// The stamp's clock time is when this run wrote the file, so it is left out.
		String stamp = read(run.resolve(RuleLoader.MERGE_STAMP));
		String[] parts = stamp.split(" ");
		rec.append("stamp ").append(parts.length == 4 ? parts[0] + " " + parts[1] + " " + parts[3] : stamp).append('\n');
	}

	private static String rules(String status, List<String> errors, List<SpeechRule> rules,
		Map<String, String> nicknames, Map<Integer, String> regions, Set<String> bosses,
		Predicate<TriggerEvent.Type> listens, boolean varbit, Path file)
	{
		List<String> ids = new ArrayList<>();
		for (SpeechRule rule : rules)
		{
			ids.add(rule.id + ":" + rule.group + ":" + rule.priority);
		}
		StringBuilder heard = new StringBuilder();
		for (TriggerEvent.Type type : TriggerEvent.Type.values())
		{
			heard.append(type).append('=').append(listens.test(type)).append(' ');
		}
		return "status " + status + "\nerrors " + errors + "\nrules " + ids
			+ "\nnick " + new TreeMap<>(nicknames) + "\nregions " + new TreeMap<>(regions)
			+ "\nbosses " + new TreeSet<>(bosses) + "\nlistens " + heard + "null=" + listens.test(null)
			+ " varbit=" + varbit + "\npath " + file + "\n";
	}

	private static String dialogs(String status, List<String> errors, List<DialogTree> trees, DialogTree greeting,
		Path file)
	{
		List<String> ids = new ArrayList<>();
		for (DialogTree tree : trees)
		{
			ids.add(tree.id + ":" + tree.startId() + ":" + (tree.nodes == null ? -1 : tree.nodes.size()));
		}
		return "status " + status + "\nerrors " + errors + "\ntrees " + ids
			+ "\ngreeting " + (greeting == null ? null : greeting.id) + "\npath " + file + "\n";
	}

	private static JsonObject resource(String path) throws IOException
	{
		try (InputStream in = LoaderDiffTest.class.getResourceAsStream(path))
		{
			assertNotNull(path, in);
			return new JsonParser().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static void write(Path path, String text) throws IOException
	{
		Files.write(path, text.getBytes(StandardCharsets.UTF_8));
	}

	private static String read(Path path) throws IOException
	{
		if (Files.isDirectory(path))
		{
			return "<folder>";
		}
		return Files.exists(path) ? new String(Files.readAllBytes(path), StandardCharsets.UTF_8) : "<absent>";
	}

	private static void wipe(Path dir) throws IOException
	{
		if (Files.exists(dir))
		{
			try (Stream<Path> all = Files.walk(dir))
			{
				for (Path path : (Iterable<Path>) all.sorted(Comparator.reverseOrder())::iterator)
				{
					Files.delete(path);
				}
			}
		}
		Files.createDirectories(dir);
	}

	private static <T> T pick(List<T> list, Random r)
	{
		return list.get(r.nextInt(list.size()));
	}

	/** Random edits to a list of objects with ids and string-array lines. */
	private static void mutateList(JsonArray array, String lines, Random r, String prefix, String[] fields)
	{
		int edits = r.nextInt(6);
		for (int i = 0; i < edits && array.size() > 0; i++)
		{
			int at = r.nextInt(array.size());
			JsonElement el = array.get(at);
			switch (r.nextInt(12))
			{
				case 0:
					array.remove(at);
					break;
				case 1:
				{
					JsonObject own = new JsonObject();
					own.addProperty("id", prefix + "-own-" + r.nextInt(5));
					JsonArray say = new JsonArray();
					say.add("own line " + r.nextInt(3));
					own.add(lines, say);
					array.add(own);
					break;
				}
				case 2:
					array.add(r.nextBoolean() ? JsonNull.INSTANCE : new JsonPrimitive("junk"));
					break;
				default:
					if (!el.isJsonObject())
					{
						break;
					}
					JsonObject o = el.getAsJsonObject();
					int what = r.nextInt(9);
					JsonArray say = o.has(lines) && o.get(lines).isJsonArray() ? o.getAsJsonArray(lines) : null;
					if (what == 0 && say != null && say.size() > 0)
					{
						say.remove(r.nextInt(say.size()));
					}
					else if (what == 1 && say != null)
					{
						say.add("added " + r.nextInt(4));
					}
					else if (what == 2 && say != null && say.size() > 1)
					{
						JsonElement first = say.remove(0);
						say.add(first);
					}
					else if (what == 3)
					{
						o.addProperty(pick(List.of(fields), r), r.nextInt(3));
					}
					else if (what == 4)
					{
						o.remove(pick(List.of(fields), r));
					}
					else if (what == 5)
					{
						o.addProperty("enabled", r.nextBoolean());
					}
					else if (what == 6)
					{
						o.remove("id");
					}
					else if (what == 7)
					{
						array.add(o.deepCopy());
					}
					else
					{
						o.addProperty("id", prefix + "-renamed-" + r.nextInt(3));
					}
			}
		}
	}

	private static JsonObject mutateRules(JsonObject file, Random r)
	{
		JsonObject f = file.deepCopy();
		if (f.has("rules"))
		{
			mutateList(f.getAsJsonArray("rules"), "say", r, "rule",
				new String[]{"cooldownSeconds", "chance", "priority", "group", "note", "title"});
		}
		if (r.nextInt(4) == 0 && f.has("rules") && f.getAsJsonArray("rules").size() > 0)
		{
			JsonArray rules = f.getAsJsonArray("rules");
			JsonElement gone = rules.remove(r.nextInt(rules.size()));
			if (gone.isJsonObject() && gone.getAsJsonObject().has("id"))
			{
				JsonArray removed = f.has("removed") ? f.getAsJsonArray("removed") : new JsonArray();
				removed.add(gone.getAsJsonObject().get("id"));
				f.add("removed", removed);
			}
		}
		for (String map : new String[]{"nicknames", "places"})
		{
			if (!f.has(map) || !f.get(map).isJsonObject())
			{
				continue;
			}
			JsonObject m = f.getAsJsonObject(map);
			List<String> keys = new ArrayList<>(m.keySet());
			int what = r.nextInt(5);
			if (what == 0 && !keys.isEmpty())
			{
				m.remove(pick(keys, r));
			}
			else if (what == 1 && !keys.isEmpty())
			{
				String k = pick(keys, r);
				String v = m.get(k).getAsString();
				m.addProperty(k, v.contains("|") ? v.split("\\|")[r.nextInt(v.split("\\|").length)] : v + " own");
			}
			else if (what == 2)
			{
				f.remove(map);
			}
			else if (what == 3)
			{
				m.addProperty(map.equals("places") ? "" + (10000 + r.nextInt(3)) : "own-tally", "own");
			}
		}
		return f;
	}

	private static JsonObject mutateDialogs(JsonObject file, Random r)
	{
		JsonObject f = file.deepCopy();
		if (!f.has("trees"))
		{
			return f;
		}
		JsonArray trees = f.getAsJsonArray("trees");
		int edits = r.nextInt(5);
		for (int i = 0; i < edits && trees.size() > 0; i++)
		{
			int at = r.nextInt(trees.size());
			JsonElement el = trees.get(at);
			int what = r.nextInt(8);
			if (what == 0)
			{
				trees.remove(at);
			}
			else if (el.isJsonObject())
			{
				JsonObject t = el.getAsJsonObject();
				if (what == 1)
				{
					t.addProperty("note", "edited " + r.nextInt(3));
				}
				else if (what == 2)
				{
					t.remove("start");
				}
				else if (t.has("nodes") && t.get("nodes").isJsonArray())
				{
					mutateList(t.getAsJsonArray("nodes"), "says", r, "node", new String[]{"next", "answer", "you"});
				}
				else if (what == 3)
				{
					t.remove("nodes");
				}
			}
		}
		return f;
	}

	/** The file as written, or now and then one an editor or a failed save left behind. */
	private static String garbageOr(JsonObject o, Random r)
	{
		switch (r.nextInt(14))
		{
			case 0:
				return "";
			case 1:
				return "{ \"rules\": [ ";
			case 2:
				return "null";
			case 3:
				return "{\"version\": 2}";
			default:
				return PRETTY.toJson(o) + "\n";
		}
	}
}

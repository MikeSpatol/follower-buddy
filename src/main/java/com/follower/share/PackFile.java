package com.follower.share;

import com.follower.sound.VoiceLibrary;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import lombok.extern.slf4j.Slf4j;

/**
 * Packs: what one player has written, in a file they can hand to another.
 *
 * <p>A pack holds the customisations and nothing else. Exporting the whole rule
 * file would be simpler and much worse - two thousand lines of shipped content
 * would ride along, clobber whatever the recipient had written themselves, and
 * freeze them at the exporter's version of everything the plugin updates. So
 * the pack is a DIFF against the shipped defaults: rules that were added, lines
 * that were added to rules that already existed, and the sounds attached to any
 * of them.
 *
 * <p>Importing merges and never replaces, which is the same promise the startup
 * merge makes. A rule both people have gains the incoming lines rather than
 * losing the existing ones, so importing a friend's bank lines deepens the pool
 * instead of substituting for it.
 *
 * <p>Everything here treats the file as hostile, because a pack arrives from
 * somebody else: entry names are matched against a fixed list rather than
 * trusted as paths, sizes are capped before anything is read into memory, and
 * every line is put through the same rules the shipped ones must satisfy - a
 * line the game's bitmap font cannot draw is not a line, whoever wrote it.
 */
@Slf4j
public final class PackFile
{
	/** Names a pack may contain. Anything else is ignored, not resolved. */
	private static final String MANIFEST = "pack.json";
	private static final String RULES = "rules.json";
	private static final String SOUND_MAP = "sounds.json";
	private static final String SOUND_DIR = "sounds/";

	/** Caps, applied before reading, so a hostile pack cannot exhaust memory. */
	private static final int MAX_ENTRIES = 300;
	private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
	private static final long MAX_ENTRY_BYTES = 8L * 1024 * 1024;

	/** What a line has to be, whoever wrote it. Matches the shipped lints. */
	private static final int MAX_LINE = 85;

	/** Bulk caps. A pack must not be able to bury the player's own file. */
	private static final int MAX_RULES = 500;
	private static final int MAX_LINES = 5000;
	private static final int MAX_ID = 64;

	/** How deep the JSON may nest before we stop, rather than overflow. */
	private static final int MAX_DEPTH = 64;

	/**
	 * Names Windows opens as devices whatever extension is hung on them, and
	 * in whatever directory they appear. Writing to COM1 does not write a file.
	 */
	private static final Set<String> RESERVED = new HashSet<>(java.util.Arrays.asList(
		"con", "prn", "aux", "nul",
		"com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
		"lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"));

	private PackFile()
	{
	}

	/** What a pack turned out to contain, for the report shown to the player. */
	public static final class Summary
	{
		public String name = "";
		public String author = "";
		public int rules;
		public int lines;
		public int sounds;
		public long bytes;
		public final List<String> problems = new ArrayList<>();

		public boolean isEmpty()
		{
			return rules == 0 && lines == 0 && sounds == 0;
		}
	}

	// ------------------------------------------------------------------ export

	/**
	 * Writes everything the player has added to a pack.
	 *
	 * @param shipped the plugin's own defaults, to diff against
	 */
	public static Summary export(Path zip, JsonObject mine, JsonObject shipped,
		VoiceLibrary voices, Gson gson, String author) throws IOException
	{
		Map<String, JsonObject> theirs = byId(shipped);
		JsonArray carried = new JsonArray();
		Set<String> carriedLines = new HashSet<>();
		Summary summary = new Summary();

		for (JsonElement element : mine.getAsJsonArray("rules"))
		{
			JsonObject rule = element.getAsJsonObject();
			String id = optString(rule, "id");
			if (id.isEmpty() || !rule.has("say") || !rule.get("say").isJsonArray())
			{
				continue;
			}
			JsonObject original = theirs.get(id);

			if (original == null)
			{
				// Wholly the player's own: goes as it stands.
				carried.add(rule);
				summary.rules++;
				for (JsonElement line : rule.getAsJsonArray("say"))
				{
					carriedLines.add(line.getAsString());
					summary.lines++;
				}
				continue;
			}

			// A shipped rule the player has written into. Only the added lines
			// travel: the rest is the recipient's own copy to keep.
			Set<String> was = lines(original);
			JsonArray extra = new JsonArray();
			for (JsonElement line : rule.getAsJsonArray("say"))
			{
				if (!was.contains(line.getAsString()))
				{
					extra.add(line);
					carriedLines.add(line.getAsString());
				}
			}
			if (extra.size() > 0)
			{
				JsonObject slim = new JsonObject();
				slim.addProperty("id", id);
				slim.add("say", extra);
				carried.add(slim);
				summary.rules++;
				summary.lines += extra.size();
			}
		}

		// Only the sounds belonging to lines that are actually travelling.
		Map<String, String> soundMap = new LinkedHashMap<>();
		for (String line : carriedLines)
		{
			String file = voices.attachmentFor(line);
			if (file != null)
			{
				soundMap.put(line, file);
			}
		}

		JsonObject manifest = new JsonObject();
		manifest.addProperty("format", 1);
		manifest.addProperty("name", zip.getFileName().toString().replace(".zip", ""));
		manifest.addProperty("author", author == null ? "" : author);

		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip)))
		{
			write(out, MANIFEST, gson.toJson(manifest));
			JsonObject rules = new JsonObject();
			rules.add("rules", carried);
			write(out, RULES, gson.toJson(rules));
			write(out, SOUND_MAP, gson.toJson(soundMap));

			Set<String> written = new HashSet<>();
			for (String file : soundMap.values())
			{
				if (!written.add(file))
				{
					continue;
				}
				Path source = voices.getSoundsFolder().resolve(file);
				if (!Files.isRegularFile(source))
				{
					summary.problems.add("missing sound file, not included: " + file);
					continue;
				}
				long size = Files.size(source);
				if (size > MAX_ENTRY_BYTES)
				{
					summary.problems.add("too large to share, left out: " + file);
					continue;
				}
				out.putNextEntry(new ZipEntry(SOUND_DIR + file));
				Files.copy(source, out);
				out.closeEntry();
				summary.sounds++;
				summary.bytes += size;
			}
		}
		return summary;
	}

	private static void write(ZipOutputStream out, String name, String body) throws IOException
	{
		out.putNextEntry(new ZipEntry(name));
		out.write(body.getBytes(StandardCharsets.UTF_8));
		out.closeEntry();
	}

	// ------------------------------------------------------------------ import

	/** Everything a pack holds, read and checked but not yet applied. */
	public static final class Loaded
	{
		public final Summary summary = new Summary();
		public JsonArray rules = new JsonArray();
		public Map<String, String> sounds = new LinkedHashMap<>();
		public final Map<String, byte[]> audio = new LinkedHashMap<>();
	}

	/**
	 * Reads a pack without applying any of it, so the player can be shown what
	 * they are about to accept before they accept it.
	 */
	public static Loaded read(Path zip, Gson gson) throws IOException
	{
		Loaded loaded = new Loaded();
		long total = 0;
		int entries = 0;

		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip)))
		{
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null)
			{
				if (++entries > MAX_ENTRIES)
				{
					loaded.summary.problems.add("too many files in the pack; stopped reading");
					break;
				}
				String name = entry.getName();
				if (entry.isDirectory() || !safe(name))
				{
					continue;
				}

				byte[] body = read(in, MAX_ENTRY_BYTES);
				if (body == null)
				{
					loaded.summary.problems.add("a file in the pack was too large: " + name);
					continue;
				}
				total += body.length;
				if (total > MAX_TOTAL_BYTES)
				{
					loaded.summary.problems.add("the pack is too large; stopped reading");
					break;
				}

				if (MANIFEST.equals(name))
				{
					JsonObject manifest = parse(body, gson);
					if (manifest != null)
					{
						loaded.summary.name = optString(manifest, "name");
						loaded.summary.author = optString(manifest, "author");
					}
				}
				else if (RULES.equals(name))
				{
					JsonObject parsed = parse(body, gson);
					if (parsed != null && parsed.has("rules")
						&& parsed.get("rules").isJsonArray())
					{
						loaded.rules = parsed.getAsJsonArray("rules");
					}
				}
				else if (SOUND_MAP.equals(name))
				{
					JsonObject parsed = parse(body, gson);
					if (parsed != null)
					{
						for (Map.Entry<String, JsonElement> pair : parsed.entrySet())
						{
							if (pair.getValue().isJsonPrimitive())
							{
								loaded.sounds.put(pair.getKey(), pair.getValue().getAsString());
							}
						}
					}
				}
				else if (name.startsWith(SOUND_DIR))
				{
					String file = name.substring(SOUND_DIR.length());
					if (playable(file))
					{
						loaded.audio.put(file, body);
						loaded.summary.bytes += body.length;
					}
					else
					{
						loaded.summary.problems.add("not a sound the game can play: " + file);
					}
				}
			}
		}

		// Now the content itself, which is the half that reaches the screen.
		JsonArray checked = new JsonArray();
		for (JsonElement element : loaded.rules)
		{
			if (!element.isJsonObject())
			{
				continue;
			}
			JsonObject rule = element.getAsJsonObject();
			String id = optString(rule, "id");
			if (!idIsOurs(id) || !rule.has("say") || !rule.get("say").isJsonArray())
			{
				loaded.summary.problems.add("a rule with no lines, or an id that is not"
					+ " one of ours, was left out");
				continue;
			}
			if (loaded.summary.rules >= MAX_RULES || loaded.summary.lines >= MAX_LINES)
			{
				loaded.summary.problems.add("the pack holds more than we will take on;"
					+ " the rest was left out");
				break;
			}
			JsonArray good = new JsonArray();
			for (JsonElement line : rule.getAsJsonArray("say"))
			{
				if (loaded.summary.lines + good.size() >= MAX_LINES)
				{
					loaded.summary.problems.add("more lines than we will take on; the"
						+ " rest of " + id + " was left out");
					break;
				}
				String text = line.isJsonPrimitive() ? line.getAsString() : "";
				String fault = faultIn(text);
				if (fault == null)
				{
					good.add(text);
				}
				else
				{
					loaded.summary.problems.add(fault + ": " + shorten(text));
				}
			}
			if (good.size() == 0)
			{
				continue;
			}
			rule.add("say", good);
			checked.add(rule);
			loaded.summary.rules++;
			loaded.summary.lines += good.size();
		}
		loaded.rules = checked;
		loaded.summary.sounds = loaded.audio.size();
		return loaded;
	}

	/**
	 * A line the game cannot draw is not a line, whoever wrote it. The same
	 * three rules the shipped content is held to.
	 */
	private static String faultIn(String line)
	{
		if (line == null || line.trim().isEmpty())
		{
			return "empty line";
		}
		if (line.length() > MAX_LINE)
		{
			return "too long to read in the time it is shown";
		}
		for (int i = 0; i < line.length(); i++)
		{
			char c = line.charAt(i);
			if (c > 0x7F)
			{
				return "has a character the game's font cannot draw";
			}
			if (c < 0x20)
			{
				return "has a control character in it";
			}
			if (c == '<' || c == '>')
			{
				// Spoken lines are mirrored into the chatbox by default, and
				// the chatbox reads Jagex markup: <col=ff0000>, <br>, <img=N>.
				// A stranger's tag would render on somebody's screen in
				// whatever colour and with whatever icon it asked for, which
				// is how a line becomes a forged system message. No shipped
				// line needs either bracket, so neither does anybody else's.
				return "has chat markup in it";
			}
		}
		return null;
	}

	/**
	 * An id is a key in our own bookkeeping rather than free text: it names
	 * cooldowns, matches rules across two people's files, and reaches the log.
	 */
	private static boolean idIsOurs(String id)
	{
		if (id.isEmpty() || id.length() > MAX_ID)
		{
			return false;
		}
		for (int i = 0; i < id.length(); i++)
		{
			char c = id.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
				|| (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
			if (!ok)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Gson parses by recursion, so JSON nested deeply enough overflows the
	 * stack - and a StackOverflowError is an Error, which sails straight past
	 * the catch (Exception) around the import and out of the action listener.
	 * Depth is counted first, on the raw text, so the parser is never handed
	 * anything that could do it.
	 */
	private static JsonObject parse(byte[] body, Gson gson)
	{
		String text = new String(body, StandardCharsets.UTF_8);
		int depth = 0;
		boolean inString = false;
		boolean escaped = false;
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (escaped)
			{
				escaped = false;
			}
			else if (inString)
			{
				if (c == '\\')
				{
					escaped = true;
				}
				else if (c == '\"')
				{
					inString = false;
				}
			}
			else if (c == '\"')
			{
				inString = true;
			}
			else if (c == '{' || c == '[')
			{
				if (++depth > MAX_DEPTH)
				{
					return null;
				}
			}
			else if (c == '}' || c == ']')
			{
				depth--;
			}
		}
		try
		{
			return gson.fromJson(text, JsonObject.class);
		}
		catch (RuntimeException | StackOverflowError e)
		{
			// Belt as well as braces. The count above should mean this never
			// fires, and a pack that finds a way past it is still refused
			// rather than thrown out of the window that asked for it.
			log.warn("Unreadable JSON in a pack", e);
			return null;
		}
	}

	/**
	 * Whether an entry name is one of ours. Matched against the fixed set
	 * rather than resolved as a path: a name is never turned into a location
	 * on disk, so there is nothing for a crafted one to escape into.
	 */
	private static boolean safe(String name)
	{
		if (name.contains("..") || name.startsWith("/") || name.contains("\\")
			|| name.contains(":"))
		{
			return false;
		}
		if (MANIFEST.equals(name) || RULES.equals(name) || SOUND_MAP.equals(name))
		{
			return true;
		}
		return name.startsWith(SOUND_DIR)
			&& name.indexOf('/', SOUND_DIR.length()) < 0
			&& name.length() > SOUND_DIR.length();
	}

	private static boolean playable(String file)
	{
		String lower = file.toLowerCase(Locale.ROOT);
		int dot = lower.indexOf('.');
		if (RESERVED.contains(dot < 0 ? lower : lower.substring(0, dot)))
		{
			return false;
		}
		return lower.endsWith(".wav") || lower.endsWith(".aif")
			|| lower.endsWith(".aiff") || lower.endsWith(".au");
	}

	private static byte[] read(InputStream in, long cap) throws IOException
	{
		java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		long total = 0;
		int read;
		while ((read = in.read(chunk)) > 0)
		{
			total += read;
			if (total > cap)
			{
				return null;
			}
			buffer.write(chunk, 0, read);
		}
		return buffer.toByteArray();
	}

	/**
	 * Folds a read pack into the player's own files.
	 *
	 * @return what was actually taken on
	 */
	public static Summary apply(Loaded loaded, JsonObject mine, Path soundsFolder,
		VoiceLibrary voices) throws IOException
	{
		Summary applied = new Summary();
		Map<String, JsonObject> ours = byId(mine);
		JsonArray rules = mine.getAsJsonArray("rules");

		// Sound files first, so the attachments below can point at their real
		// names - a name already taken here belongs to a different recording.
		Map<String, String> renamed = new HashMap<>();
		Files.createDirectories(soundsFolder);
		for (Map.Entry<String, byte[]> file : loaded.audio.entrySet())
		{
			String name = file.getKey();
			Path target = soundsFolder.resolve(name);
			if (Files.exists(target))
			{
				if (same(target, file.getValue()))
				{
					// Already here, byte for byte. Importing the same pack
					// twice should be a no-op, not a folder full of -2, -3, -4.
					continue;
				}
				String stem = name.contains(".")
					? name.substring(0, name.lastIndexOf('.')) : name;
				String extension = name.substring(stem.length());
				for (int n = 2; Files.exists(target); n++)
				{
					name = stem + "-" + n + extension;
					target = soundsFolder.resolve(name);
				}
				renamed.put(file.getKey(), name);
			}
			Files.write(target, file.getValue());
			applied.sounds++;
			applied.bytes += file.getValue().length;
		}

		for (JsonElement element : loaded.rules)
		{
			JsonObject incoming = element.getAsJsonObject();
			String id = optString(incoming, "id");
			JsonObject existing = ours.get(id);

			if (existing == null)
			{
				if (!incoming.has("when"))
				{
					// Lines for a rule this player does not have. Adding them
					// would make a rule with nothing to fire it, which is worse
					// than saying so: their phrases.json is older than the
					// sender's, and the lines have nowhere to live yet.
					applied.problems.add("lines for a rule you do not have: " + id);
					continue;
				}
				rules.add(incoming);
				applied.rules++;
				applied.lines += incoming.getAsJsonArray("say").size();
				continue;
			}

			// Merge, never replace: their lines join yours.
			Set<String> have = lines(existing);
			JsonArray say = existing.getAsJsonArray("say");
			int added = 0;
			for (JsonElement line : incoming.getAsJsonArray("say"))
			{
				if (have.add(line.getAsString()))
				{
					say.add(line);
					added++;
				}
			}
			if (added > 0)
			{
				applied.rules++;
				applied.lines += added;
			}
		}

		// A pack may only give a voice to lines it actually carried, and only
		// where the player has not already given that line one of their own.
		// The sound map is a file of its own, so without this it could name
		// any line in the recipient's install - one that ships, or one they
		// had recorded themselves - and quietly speak over it.
		Set<String> arrived = new HashSet<>();
		for (JsonElement element : loaded.rules)
		{
			arrived.addAll(lines(element.getAsJsonObject()));
		}
		for (Map.Entry<String, String> pair : loaded.sounds.entrySet())
		{
			String line = pair.getKey();
			if (!loaded.audio.containsKey(pair.getValue()))
			{
				continue;
			}
			if (!arrived.contains(line))
			{
				applied.problems.add("a sound for a line the pack did not bring: "
					+ shorten(line));
				continue;
			}
			if (voices.attachmentFor(line) != null)
			{
				applied.problems.add("kept your own recording for: " + shorten(line));
				continue;
			}
			voices.attach(line, renamed.getOrDefault(pair.getValue(), pair.getValue()));
		}
		return applied;
	}

	// ------------------------------------------------------------------ shared

	private static boolean same(Path file, byte[] body)
	{
		try
		{
			return Files.size(file) == body.length
				&& java.util.Arrays.equals(Files.readAllBytes(file), body);
		}
		catch (IOException e)
		{
			return false;
		}
	}

	private static Map<String, JsonObject> byId(JsonObject file)
	{
		Map<String, JsonObject> map = new HashMap<>();
		if (file == null || !file.has("rules") || !file.get("rules").isJsonArray())
		{
			return map;
		}
		for (JsonElement element : file.getAsJsonArray("rules"))
		{
			if (element.isJsonObject())
			{
				String id = optString(element.getAsJsonObject(), "id");
				if (!id.isEmpty())
				{
					map.put(id, element.getAsJsonObject());
				}
			}
		}
		return map;
	}

	private static Set<String> lines(JsonObject rule)
	{
		Set<String> set = new HashSet<>();
		if (rule.has("say") && rule.get("say").isJsonArray())
		{
			for (JsonElement line : rule.getAsJsonArray("say"))
			{
				if (line.isJsonPrimitive())
				{
					set.add(line.getAsString());
				}
			}
		}
		return set;
	}

	private static String optString(JsonObject object, String key)
	{
		return object.has(key) && object.get(key).isJsonPrimitive()
			? object.get(key).getAsString() : "";
	}

	private static String shorten(String text)
	{
		return text.length() <= 40 ? text : text.substring(0, 40) + "...";
	}

	/** The plugin's own defaults, for the diff. */
	public static JsonObject shippedRules(Gson gson)
	{
		try (InputStream in = PackFile.class
			.getResourceAsStream("/com/follower/default-phrases.json"))
		{
			return in == null ? null
				: gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
					JsonObject.class);
		}
		catch (IOException e)
		{
			log.warn("Could not read the bundled defaults for the pack diff", e);
			return null;
		}
	}

	/** Writes a JSON file back out, used after an import. */
	public static void save(Path file, JsonObject data, Gson gson) throws IOException
	{
		try (OutputStream out = Files.newOutputStream(file))
		{
			out.write((gson.toJson(data) + "\n").getBytes(StandardCharsets.UTF_8));
		}
	}
}

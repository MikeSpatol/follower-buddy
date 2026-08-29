package com.follower.sound;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import lombok.extern.slf4j.Slf4j;

/**
 * The follower's voice: a sound file the player has attached to a line, played
 * when that line is spoken.
 *
 * <p>Keyed by the LINE ITSELF rather than by rule and index. A recording is of
 * particular words, so it belongs to those words: reorder a rule's lines and
 * every recording still follows its own line, and reword a line and the link
 * breaks, which is correct - the recording no longer says what the line says.
 * It also means the two thousand lines the plugin ships can be voiced exactly
 * like the ones the player writes, with no distinction anywhere in the code.
 *
 * <p>Nothing is bundled. The plugin ships no audio at all and never fetches
 * any: the player drops their own files into the sounds folder, which is the
 * only arrangement that is unambiguously theirs to make.
 *
 * <p>Java plays WAV, AIFF and AU and nothing else without help, which is worth
 * saying plainly in the interface rather than letting somebody discover it with
 * an MP3 and a silence.
 */
@Slf4j
@Singleton
public class VoiceLibrary
{
	public static final String FILE_NAME = "sounds.json";

	/** Where the player's own recordings live, beside the rules that use them. */
	public static final String SOUND_DIR = "sounds";

	/** What Java can open without a decoder on the classpath. */
	private static final Set<String> PLAYABLE =
		new HashSet<>(java.util.Arrays.asList(".wav", ".aif", ".aiff", ".au"));

	private final Gson gson;

	private Path file;
	private Path sounds;

	/** Line text to file name. Insertion-ordered so the file reads sensibly. */
	private Map<String, String> attached = new LinkedHashMap<>();

	/** Opened clips, kept because opening one per line would stutter. */
	private final Map<String, Clip> clips = new HashMap<>();

	/** Files that failed to open; remembered so a broken one is logged once. */
	private final Set<String> refused = new HashSet<>();

	/** The clip currently sounding. The follower has one mouth. */
	private Clip speaking;

	@Inject
	public VoiceLibrary(Gson gson)
	{
		this.gson = gson;
	}

	public void load(Path dataDir)
	{
		file = dataDir.resolve(FILE_NAME);
		sounds = dataDir.resolve(SOUND_DIR);
		attached = read();
		try
		{
			Files.createDirectories(sounds);
		}
		catch (IOException e)
		{
			log.warn("Could not create the sounds folder at {}", sounds, e);
		}
		log.debug("Voice library: {} line(s) with a sound", attached.size());
	}

	private Map<String, String> read()
	{
		if (file == null || !Files.isRegularFile(file))
		{
			return new LinkedHashMap<>();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			Map<String, String> parsed = gson.fromJson(reader,
				new TypeToken<LinkedHashMap<String, String>>()
				{
				}.getType());
			return parsed == null ? new LinkedHashMap<>() : parsed;
		}
		catch (IOException | JsonSyntaxException e)
		{
			// A hand-edited file that no longer parses costs the voices, not
			// the follower: it goes on speaking in text.
			log.warn("Could not read {}", file, e);
			return new LinkedHashMap<>();
		}
	}

	private void write()
	{
		if (file == null)
		{
			return;
		}
		try
		{
			Files.createDirectories(file.getParent());
			Files.write(file, (gson.toJson(attached) + "\n")
				.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.warn("Could not save {}", file, e);
		}
	}

	/** The sound files the player has put in the folder, for the picker. */
	public List<String> availableFiles()
	{
		if (sounds == null || !Files.isDirectory(sounds))
		{
			return Collections.emptyList();
		}
		List<String> found = new ArrayList<>();
		try (java.util.stream.Stream<Path> entries = Files.list(sounds))
		{
			entries.filter(Files::isRegularFile)
				.map(path -> path.getFileName().toString())
				.filter(VoiceLibrary::playable)
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.forEach(found::add);
		}
		catch (IOException e)
		{
			log.warn("Could not list {}", sounds, e);
		}
		return found;
	}

	private static boolean playable(String fileName)
	{
		String lower = fileName.toLowerCase(Locale.ROOT);
		for (String extension : PLAYABLE)
		{
			if (lower.endsWith(extension))
			{
				return true;
			}
		}
		return false;
	}

	public Path getSoundsFolder()
	{
		return sounds;
	}

	/** The file attached to a line, or null. */
	public String attachmentFor(String line)
	{
		return line == null ? null : attached.get(line);
	}

	public boolean has(String line)
	{
		return attachmentFor(line) != null;
	}

	/** Attaches a file to a line, or detaches when the file is null. */
	public void attach(String line, String fileName)
	{
		if (line == null || line.isEmpty())
		{
			return;
		}
		if (fileName == null || fileName.isEmpty())
		{
			attached.remove(line);
		}
		else
		{
			attached.put(line, fileName);
		}
		write();
	}

	/**
	 * Speaks the line aloud, if a sound is attached to it.
	 *
	 * @param volume 0-100; 0 is silence and skips the work entirely
	 */
	public void play(String line, int volume)
	{
		if (volume <= 0)
		{
			return;
		}
		String fileName = attachmentFor(line);
		if (fileName == null)
		{
			return;
		}
		Clip clip = open(fileName);
		if (clip == null)
		{
			return;
		}

		// One mouth: a new line cuts off the one still sounding, the same way
		// its text replaces the text still on screen.
		if (speaking != null && speaking.isRunning())
		{
			speaking.stop();
		}
		clip.stop();
		clip.setFramePosition(0);
		setVolume(clip, volume);
		clip.start();
		speaking = clip;
	}

	/** Plays a file directly, for the preview button in the editor. */
	public void preview(String fileName, int volume)
	{
		Clip clip = open(fileName);
		if (clip == null)
		{
			return;
		}
		if (speaking != null && speaking.isRunning())
		{
			speaking.stop();
		}
		clip.stop();
		clip.setFramePosition(0);
		setVolume(clip, Math.max(1, volume));
		clip.start();
		speaking = clip;
	}

	private Clip open(String fileName)
	{
		Clip cached = clips.get(fileName);
		if (cached != null)
		{
			return cached;
		}
		if (refused.contains(fileName) || sounds == null)
		{
			return null;
		}

		Path path = sounds.resolve(fileName);
		if (!Files.isRegularFile(path))
		{
			log.warn("Sound file {} is not in {}", fileName, sounds);
			refused.add(fileName);
			return null;
		}

		try (AudioInputStream stream = AudioSystem.getAudioInputStream(path.toFile()))
		{
			Clip clip = AudioSystem.getClip();
			clip.open(stream);
			clips.put(fileName, clip);
			return clip;
		}
		catch (Exception e)
		{
			// Deliberately broad: an unreadable sound must never be able to
			// stop the follower speaking, and the formats Java refuses are
			// the ones a player is most likely to try first.
			log.warn("Could not play {} - Java opens WAV, AIFF and AU only", fileName, e);
			refused.add(fileName);
			return null;
		}
	}

	private static void setVolume(Clip clip, int volume)
	{
		if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN))
		{
			return;
		}
		FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
		// Percentage to decibels, which is what the control speaks. Clamped to
		// whatever range this line actually offers rather than a guessed one.
		float decibels = (float) (20.0 * Math.log10(Math.max(1, volume) / 100.0));
		gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), decibels)));
	}

	/** Releases every open line. Called when the plugin stops. */
	public void close()
	{
		if (speaking != null)
		{
			speaking.stop();
			speaking = null;
		}
		for (Clip clip : clips.values())
		{
			clip.stop();
			clip.close();
		}
		clips.clear();
		refused.clear();
	}

	/** Re-reads the file, so hand edits and the editor agree. */
	public void reload()
	{
		attached = read();
	}
}

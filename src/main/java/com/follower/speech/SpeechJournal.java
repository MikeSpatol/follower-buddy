package com.follower.speech;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * A record of what the follower actually said, and of what it wanted to say and
 * could not.
 *
 * <p>Every tuning decision in this plugin so far has rested on impressions and
 * on a model of what the rules COULD do. Neither is a record of what they did:
 * the engine kept one last-spoken line and some debug logging that is off by
 * default, so an hour of play left nothing to review. That makes questions like
 * "is it repeating itself" and "did the pacing change help" unanswerable except
 * by feel.
 *
 * <p>The suppressed entries are the more useful half. A line that was held back
 * by the shared gap never reaches the player and so never reaches an
 * impression, but it is exactly the evidence needed to tell a follower that has
 * little to say from one that is being throttled.
 */
@Slf4j
public class SpeechJournal
{
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

	/** Flushed on a timer rather than per line, to keep file I/O off the hot path. */
	private static final long FLUSH_EVERY_MS = 30_000L;

	/**
	 * Distinct lines tracked for the repeat count. Comfortably above the whole
	 * rule file, so in practice nothing is ever evicted; the cap only exists so
	 * a runaway cannot grow without bound.
	 */
	private static final int MAX_TRACKED_LINES = 4_000;

	private Path file;

	@Getter
	private boolean enabled;

	/**
	 * Where the player was, supplied per line by the plugin.
	 *
	 * <p>Added after a verification run could not be read: place memory,
	 * taste, return visits and place scores are all answers to "where", and a
	 * transcript that records only "what" cannot be used to check any of them.
	 */
	private java.util.function.IntSupplier region = () -> -1;

	public void setRegionSource(java.util.function.IntSupplier source)
	{
		if (source != null)
		{
			region = source;
		}
	}

	private final List<String> pending = new ArrayList<>();
	private long lastFlushMs;

	// ---- session figures, kept in memory so a summary needs no file read ----

	private int spoken;
	private long firstSpokeMs;
	private long lastSpokeMs;
	private long shortestGapMs = Long.MAX_VALUE;
	private long longestGapMs;

	private final Map<String, Integer> byRule = new LinkedHashMap<>();
	private final Map<String, Integer> byLine = new LinkedHashMap<>();
	private final Map<String, Integer> byReason = new LinkedHashMap<>();
	private int suppressed;

	public void initialise(Path dataDir)
	{
		file = dataDir.resolve("transcript-" + LocalDate.now() + ".log");
	}

	public Path getFile()
	{
		return file;
	}

	/**
	 * @return the new state, so the caller can report it
	 */
	public boolean toggle()
	{
		setEnabled(!enabled);
		return enabled;
	}

	/**
	 * Turned on directly, so the plugin can restore it at startup.
	 *
	 * <p>A diagnostic that quietly switches itself off every time the client
	 * restarts is a diagnostic that records nothing on the run you most wanted
	 * it for - which is exactly what happened the first time this was used in
	 * anger.
	 */
	public void setEnabled(boolean on)
	{
		if (on == enabled)
		{
			return;
		}
		enabled = on;
		if (enabled)
		{
			append("---- transcript opened ----");
			// Written at once rather than on the next flush, so that "is it
			// actually recording" is a question the file can answer. Two
			// verification runs were lost to assuming it was on.
			flush();
		}
		else
		{
			append("---- transcript closed ----");
			flush();
		}
	}

	/** A line the player actually saw. */
	public void spoke(SpeechRule rule, String text)
	{
		if (!enabled || text == null || text.isEmpty())
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (spoken > 0)
		{
			long gap = now - lastSpokeMs;
			shortestGapMs = Math.min(shortestGapMs, gap);
			longestGapMs = Math.max(longestGapMs, gap);
		}
		else
		{
			firstSpokeMs = now;
		}
		lastSpokeMs = now;
		spoken++;

		// A command-driven line has no rule behind it.
		String id = rule == null ? "(command)" : rule.describe();
		String group = rule == null ? "-" : String.valueOf(rule.group);
		bump(byRule, id);
		if (byLine.size() < MAX_TRACKED_LINES || byLine.containsKey(text))
		{
			bump(byLine, text);
		}
		append(String.join("\t", stamp(), "said", id, group, where(), text));
	}

	/**
	 * A rule that won its event and was held back anyway.
	 *
	 * <p>This is the half of the story the player never sees, and the only way
	 * to tell a follower with little to say from one being throttled. A reason
	 * that never reaches here is a blind spot in the only diagnostic there is.
	 *
	 * @param reason whatever {@code SpeechEngine.blockedBy} decided: muted,
	 * gap, hush, or one of the director's - relax after a burst, settling while
	 * the follower is still new. Recorded verbatim rather than checked against
	 * a list, so a reason added later shows up in the transcript without this
	 * needing to know about it.
	 */
	public void suppressed(SpeechRule rule, String reason)
	{
		if (!enabled)
		{
			return;
		}
		suppressed++;
		bump(byReason, reason);
		append(String.join("\t", stamp(), "held", rule.describe(),
			String.valueOf(rule.group), where(), reason));
	}

	private static void bump(Map<String, Integer> counts, String key)
	{
		counts.merge(key, 1, Integer::sum);
	}

	private static String stamp()
	{
		return LocalTime.now().format(CLOCK);
	}

	/** The region id, in its own column so a reader can group by it. */
	private String where()
	{
		return "r" + region.getAsInt();
	}

	private void append(String line)
	{
		pending.add(line);
	}

	/** Called from the tick. Writes only when there is something and it is due. */
	public void flushIfDue()
	{
		long now = System.currentTimeMillis();
		if (!pending.isEmpty() && now - lastFlushMs >= FLUSH_EVERY_MS)
		{
			flush();
		}
	}

	public void flush()
	{
		lastFlushMs = System.currentTimeMillis();
		if (pending.isEmpty() || file == null)
		{
			return;
		}
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND))
		{
			for (String line : pending)
			{
				writer.write(line);
				writer.write("\n");
			}
		}
		catch (IOException e)
		{
			// A diagnostic that breaks the thing it is diagnosing is worse than
			// no diagnostic. Drop the buffer and carry on.
			log.warn("Could not write the transcript to {}", file, e);
		}
		pending.clear();
	}

	/**
	 * The session so far, as lines for the chatbox.
	 *
	 * <p>Computed from memory rather than by reading the file back, so it works
	 * mid-session and cannot be thrown off by an earlier run appending to the
	 * same day's file.
	 */
	public List<String> summary()
	{
		List<String> out = new ArrayList<>();
		if (spoken == 0)
		{
			out.add("Transcript: nothing said yet"
				+ (enabled ? "." : " (and it is off)."));
			return out;
		}

		long span = Math.max(1, lastSpokeMs - firstSpokeMs);
		double minutes = span / 60_000.0;
		out.add(String.format("Transcript: %d lines over %s (%.0f/hour), %d held back.",
			spoken, human(span), spoken / (minutes / 60.0), suppressed));

		if (!byReason.isEmpty())
		{
			out.add("  held back by: " + join(byReason, 4));
		}
		if (shortestGapMs != Long.MAX_VALUE)
		{
			out.add("  closest together " + human(shortestGapMs)
				+ ", longest quiet " + human(longestGapMs) + ".");
		}
		out.add("  loudest rules: " + join(byRule, 5));

		Map.Entry<String, Integer> worst = byLine.entrySet().stream()
			.max(Comparator.comparingInt(Map.Entry::getValue)).orElse(null);
		if (worst != null && worst.getValue() > 1)
		{
			out.add(String.format("  most repeated (%dx): \"%s\"",
				worst.getValue(), worst.getKey()));
		}
		return out;
	}

	/** The top few entries of a count map, biggest first. */
	private static String join(Map<String, Integer> counts, int limit)
	{
		return counts.entrySet().stream()
			.sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue())
				.reversed())
			.limit(limit)
			.map(e -> e.getKey() + " " + e.getValue())
			.reduce((a, b) -> a + ", " + b)
			.orElse("none");
	}

	/** 4.2s / 3m11s / 1h04m - the way a person says a duration. */
	private static String human(long ms)
	{
		long seconds = ms / 1000;
		if (seconds < 60)
		{
			return String.format("%.1fs", ms / 1000.0);
		}
		if (seconds < 3600)
		{
			return String.format("%dm%02ds", seconds / 60, seconds % 60);
		}
		return String.format("%dh%02dm", seconds / 3600, (seconds % 3600) / 60);
	}

	/** Forgets the session figures. The file is left alone. */
	public void reset()
	{
		spoken = 0;
		suppressed = 0;
		firstSpokeMs = 0;
		lastSpokeMs = 0;
		shortestGapMs = Long.MAX_VALUE;
		longestGapMs = 0;
		byRule.clear();
		byLine.clear();
		byReason.clear();
	}
}

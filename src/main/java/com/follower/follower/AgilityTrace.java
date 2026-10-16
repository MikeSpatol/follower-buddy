package com.follower.follower;

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
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * A plain text record of the player's agility and what the follower made of
 * it, for tuning against a real lap: {@code agility-trace-YYYY-MM-DD.log} in
 * the plugin's folder, written only while Developer commands is on.
 *
 * <p>Two kinds of line. A tick line, once a game tick while there is
 * agility about (a crossing watched or queued, XP, an odd pose lately, the
 * follower holding), and event lines, marked {@code *}, for what the watch
 * and the follower decided: a crossing recognised, ended, cut or rebuilt, a
 * cap tripped, every snap and placement with its reason.
 *
 * <p>Buffered, and written every 5 s in one small append, as the speech
 * transcript is; flushed at once when switched on or off. The client thread
 * writes it and the plugin's shutdown flushes it from another, so the
 * buffer is only touched under its lock.
 */
@Slf4j
public class AgilityTrace
{
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

	/** Short, so a lap is on disk soon after it is run. */
	private static final long FLUSH_EVERY_MS = 5_000L;

	/** What a tick line's columns are, written when the trace starts. */
	static final String COLUMNS = "tick fps | player x,y,plane pose anim gfx moving|still xp+N hit type"
		+ " | watch state | follower x,y,plane queue N state";

	private Path dataDir;
	private boolean enabled;
	private final List<String> pending = new ArrayList<>();
	private long lastFlushMs;

	public void initialise(Path dataDir)
	{
		this.dataDir = dataDir;
		// TODO(merge): keep only recent days' files, with fix5/ui's
		// LogRetention.prune(dataDir, "agility-trace-", LocalDate.now()).
	}

	/** Today's file: a session past midnight carries on in the new day's. */
	public Path getFile()
	{
		return dataDir == null ? null : dataDir.resolve("agility-trace-" + LocalDate.now() + ".log");
	}

	public boolean isEnabled()
	{
		return enabled;
	}

	public synchronized void setEnabled(boolean on)
	{
		if (on == enabled)
		{
			return;
		}
		if (on)
		{
			enabled = true;
			append("---- agility trace on: " + COLUMNS + " ----");
		}
		else
		{
			append("---- agility trace off ----");
			enabled = false;
		}
		flush();
	}

	/** Something decided: a crossing, a cap, a snap. */
	public void event(String what)
	{
		if (enabled)
		{
			append("* " + what);
		}
	}

	/** One game tick's state. */
	public void tick(String line)
	{
		if (enabled)
		{
			append("  " + line);
		}
	}

	private synchronized void append(String line)
	{
		pending.add(LocalTime.now().format(CLOCK) + " " + line);
	}

	/** Called from the tick. Writes only when there is something and it is due. */
	public synchronized void flushIfDue()
	{
		if (!pending.isEmpty() && System.currentTimeMillis() - lastFlushMs >= FLUSH_EVERY_MS)
		{
			flush();
		}
	}

	public synchronized void flush()
	{
		lastFlushMs = System.currentTimeMillis();
		Path file = getFile();
		if (pending.isEmpty() || file == null)
		{
			pending.clear();
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
			// A diagnostic that breaks the thing it is diagnosing is worse
			// than none. Drop the buffer and carry on.
			log.warn("Could not write the agility trace to {}", file, e);
		}
		pending.clear();
	}
}

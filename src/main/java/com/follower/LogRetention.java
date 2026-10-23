// public:file dev
package com.follower;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import lombok.extern.slf4j.Slf4j;

/**
 * The developer logs written one file a day ({@code <prefix>YYYY-MM-DD.log})
 * kept for a while and then let go: nothing removed them, and a summer of
 * transcripts was 51 files (perf audit, Oct 2026).
 */
@Slf4j
public final class LogRetention
{
	/** How long a day's log is kept. */
	public static final int DAYS_KEPT = 30;

	private LogRetention()
	{
	}

	/**
	 * Removes the logs of this prefix dated more than {@link #DAYS_KEPT} days
	 * before today. The date is read from the name, so a log copied or
	 * touched since is judged by the day it records. Never throws.
	 */
	public static void prune(Path dir, String prefix, LocalDate today)
	{
		if (dir == null || !Files.isDirectory(dir))
		{
			return;
		}
		LocalDate oldest = today.minusDays(DAYS_KEPT);
		try (DirectoryStream<Path> logs = Files.newDirectoryStream(dir, prefix + "*.log"))
		{
			for (Path file : logs)
			{
				String name = file.getFileName().toString();
				String day = name.substring(prefix.length(), name.length() - ".log".length());
				try
				{
					if (LocalDate.parse(day).isBefore(oldest))
					{
						Files.deleteIfExists(file);
					}
				}
				catch (DateTimeParseException e)
				{
					// Not one of ours: a name that only starts the same way.
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("Could not tidy the {} logs in {}", prefix, dir, e); // public:cut logs
		}
	}
}

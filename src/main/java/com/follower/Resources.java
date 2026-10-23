package com.follower;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * The data files bundled in the jar beside the code, under /com/follower/:
 * the tables and scripts the plugin reads as it loads them. One that is
 * missing or cannot be read is a broken jar, and fails where it is read.
 */
public final class Resources
{
	private Resources()
	{
	}

	/** A bundled file's text, as UTF-8. */
	public static BufferedReader reader(String name)
	{
		return new BufferedReader(new InputStreamReader(
			Resources.class.getResourceAsStream("/com/follower/" + name), StandardCharsets.UTF_8));
	}

	/** A bundled properties file. */
	public static Properties properties(String name)
	{
		Properties properties = new Properties();
		try (InputStream in = Resources.class.getResourceAsStream("/com/follower/" + name))
		{
			properties.load(in);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		return properties;
	}
}

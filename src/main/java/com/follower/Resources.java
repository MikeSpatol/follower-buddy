package com.follower;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * The data files bundled in the jar beside the code, under /com/follower/:
 * the tables and scripts the plugin reads as it loads them. One that is
 * missing or cannot be read is a broken jar, and fails where it is read.
 */
public final class Resources
{
	/** lists.properties: the small word and id lists, each comma separated. */
	private static final Properties LISTS = properties("lists.properties");

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

	/**
	 * A bundled table (.tsv): its rows in order, each split at its tabs.
	 * Blank lines and lines that start with # are not rows.
	 */
	public static List<String[]> rows(String name)
	{
		List<String[]> rows = new ArrayList<>();
		try (BufferedReader in = reader(name))
		{
			for (String line = in.readLine(); line != null; line = in.readLine())
			{
				if (!line.isEmpty() && !line.startsWith("#"))
				{
					rows.add(line.split("\t", -1));
				}
			}
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		return rows;
	}

	/** One of lists.properties' lists, in its order. */
	public static String[] list(String key)
	{
		return LISTS.getProperty(key).split(",");
	}

	/** One of lists.properties' lists, as a set of its words. */
	public static Set<String> words(String key)
	{
		return new HashSet<>(Arrays.asList(list(key)));
	}

	/** One of lists.properties' lists, as a set of its ids. */
	public static Set<Integer> ids(String key)
	{
		Set<Integer> ids = new HashSet<>();
		for (String id : list(key))
		{
			ids.add(Integer.valueOf(id));
		}
		return ids;
	}
}

package com.follower;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.Set;

/**
 * The data files bundled in the jar beside the code, under /com/follower/:
 * the tables and scripts the plugin reads as it loads them. One that is
 * missing or cannot be read is a broken jar, and fails where it is read.
 */
class Resources
{
	/** lists.properties: the small word and id lists, each comma separated. */
	static final Properties LISTS = properties("lists.properties");

	/** strings.properties: the longer captions, tooltips and status lines, word for word. */
	static final Properties TEXT = properties("strings.properties");


	/** A bundled file's text, as UTF-8. */
	static BufferedReader reader(String name)
	{
		return new BufferedReader(new InputStreamReader(
			Resources.class.getResourceAsStream("/com/follower/" + name), StandardCharsets.UTF_8));
	}

	/** A bundled properties file. */
	static Properties properties(String name)
	{
		var properties = new Properties();
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
	static List<String[]> rows(String name)
	{
		List<String[]> rows = new ArrayList<>();
		try (BufferedReader in = reader(name))
		{
			for (String line = in.readLine(); line != null; line = in.readLine())
			{
				if (!line.isEmpty() && !line.startsWith("#"))
					rows.add(line.split("\t", -1));
			}
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		return rows;
	}

	/** One of strings.properties' texts. */
	static String text(String key)
	{
		return TEXT.getProperty(key);
	}

	/** One of lists.properties' lists, in its order. */
	static String[] list(String key)
	{
		return LISTS.getProperty(key).split(",");
	}

	/** One of lists.properties' lists, as a set of its words. */
	static Set<String> words(String key)
	{
		return new HashSet<>(Arrays.asList(list(key)));
	}

	/** One of lists.properties' lists, as a set of its ids. */
	static Set<Integer> ids(String key)
	{
		Set<Integer> ids = new HashSet<>();
		for (String id : list(key))
			ids.add(Integer.valueOf(id));
		return ids;
	}
}

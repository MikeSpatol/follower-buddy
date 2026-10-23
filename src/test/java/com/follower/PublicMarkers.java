package com.follower;

/**
 * The source as javac reads it, for the tests that read it as text: the
 * public build's markers are comments, so they are taken out before a test
 * looks for a line of code. Lines that only hold a marker go, and so does a
 * marker at the end of a line of code.
 */
public final class PublicMarkers
{
	private PublicMarkers()
	{
	}

	public static String strip(String source)
	{
		return source
			.replaceAll("(?m)^[ \\t]*// public:(cut|end|else|file)\\b.*\\n", "")
			.replaceAll("[ \\t]*// public:cut [a-z0-9,-]+(?=\\r?\\n)", "");
	}
}

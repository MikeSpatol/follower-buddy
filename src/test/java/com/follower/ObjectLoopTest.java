package com.follower;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * A clip's finished handler runs inside the client's walk over every
 * RuneLiteObject, which has already stepped to the next one. Taking that
 * one out of the scene, even to put it straight back, leaves the client on
 * a dead link and it crashes (issue #5, Nov 2026: the next chained clip's
 * graphic restacked the follower from in there). So a handler may set
 * frames, let its own spent graphic go, or hand the rest to the next
 * frame's update; anything else it calls fails here.
 */
public class ObjectLoopTest
{
	private static final Path MAIN = Paths.get("src/main/java");

	/** Calls a handler may make: frames and reads only, and dropGraphic, which takes out only the graphic that finished. */
	private static final List<String> ALLOWED_CALLS = List.of("setFrame", "setOnFinished", "isMayaAnim",
		"getDuration", "getFrameLengths", "getFrameStep", "getAnimation", "frameCount", "max", "dropGraphic");

	/** Method references a handler may be: both only set the frame. */
	private static final List<String> ALLOWED_REFERENCES = List.of(
		"FollowerEntity::holdLastFrame", "FollowerEntity::loopSafely");

	private static final Pattern CALL = Pattern.compile("([A-Za-z_]\\w*)\\s*\\(");

	@Test
	public void finishedHandlersChangeNoObjectButTheirOwn() throws IOException
	{
		List<Path> files;
		try (Stream<Path> walk = Files.walk(MAIN))
		{
			files = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
		}
		List<String> bad = new ArrayList<>();
		int found = 0;
		for (Path file : files)
		{
			String source = PublicMarkers.strip(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
			for (int at = source.indexOf(".setOnFinished("); at >= 0; at = source.indexOf(".setOnFinished(", at + 1))
			{
				found++;
				int open = at + ".setOnFinished".length();
				String handler = source.substring(open + 1, closing(source, open));
				String left = withoutDeferred(handler);
				for (String reference : ALLOWED_REFERENCES)
				{
					left = left.replace(reference, "");
				}
				Matcher call = CALL.matcher(left);
				while (call.find())
				{
					if (!ALLOWED_CALLS.contains(call.group(1)))
					{
						bad.add(file.getFileName() + ": " + call.group(1) + "(...) in " + handler.trim());
					}
				}
				if (left.contains("::"))
				{
					bad.add(file.getFileName() + ": a method reference not known to set only the frame: " + handler.trim());
				}
			}
		}
		assertTrue("no finished handlers found - the lint has lost its place", found >= 5);
		assertTrue("a finished handler changes the scene from inside the client's object loop;"
			+ " hand it to afterObjectLoop:\n" + String.join("\n", bad), bad.isEmpty());
	}

	/** The handler with every afterObjectLoop.add(...) taken out: that work runs after the loop. */
	private static String withoutDeferred(String handler)
	{
		String marker = "afterObjectLoop.add(";
		StringBuilder out = new StringBuilder();
		int from = 0;
		for (int at = handler.indexOf(marker); at >= 0; at = handler.indexOf(marker, from))
		{
			out.append(handler, from, at);
			from = closing(handler, at + marker.length() - 1) + 1;
		}
		return out.append(handler.substring(from)).toString();
	}

	/** The index of the parenthesis closing the one at {@code open}. */
	private static int closing(String text, int open)
	{
		int depth = 0;
		for (int i = open; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (c == '(')
			{
				depth++;
			}
			else if (c == ')' && --depth == 0)
			{
				return i;
			}
		}
		throw new AssertionError("unbalanced parentheses after " + text.substring(open, Math.min(text.length(), open + 80)));
	}
}

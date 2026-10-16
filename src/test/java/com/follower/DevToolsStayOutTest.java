package com.follower;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * The Blender export tools never ship. They live on the dev/model-export
 * branch and write to ~/follower-studio, apart from the plugin; a release
 * that picked them up by a careless commit or merge fails here.
 *
 * <p>A checkout that is meant to carry them says so with an untracked
 * {@code .dev-tools-allowed} file at its root. It is gitignored, so the
 * permission cannot be committed along with the tools.
 */
public class DevToolsStayOutTest
{
	private static final String[] MARKS = {
		"follower-studio", "STUDIO_EXPORTS", "exportChatbox", "ModelExporter", "AreaExporter", "follower export",
	};

	@Test
	public void theExportToolsAreNotInTheRelease() throws IOException
	{
		if (Files.exists(Paths.get(".dev-tools-allowed")))
		{
			return;
		}
		List<String> found = new ArrayList<>();
		if (Files.exists(Paths.get("src/main/java/com/follower/dev")))
		{
			found.add("src/main/java/com/follower/dev/");
		}
		if (Files.exists(Paths.get("tools/blender")))
		{
			found.add("tools/blender/");
		}
		try (Stream<Path> files = Files.walk(Paths.get("src/main")))
		{
			for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator)
			{
				String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				for (String mark : MARKS)
				{
					if (text.contains(mark))
					{
						found.add(file + ": " + mark);
					}
				}
			}
		}
		assertTrue("dev export tools in the release: " + found, found.isEmpty());
	}
}

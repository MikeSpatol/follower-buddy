package com.follower.tools;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.SpriteDefinition;
import net.runelite.cache.definitions.loaders.SpriteLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;

/**
 * The pixel size of the interface sprites a window is built from, and a PNG
 * of each, straight from the cache.
 *
 * <p>The API names the sprites but not their sizes, and a frame drawn a pixel
 * off looks wrong in a way that is hard to put a finger on. This prints every
 * frame of every id asked for and writes it out so it can be looked at.
 *
 * <pre>
 *   gradlew runSpriteProbe --args="310 311 312 313 314 315"
 *   gradlew runSpriteProbe --args="--out C:/some/dir 310 311"
 * </pre>
 */
public class SpriteProbe
{
	public static void main(String[] args) throws IOException
	{
		Path cacheDir = Paths.get(System.getProperty("user.home"),
			".runelite", "jagexcache", "oldschool", "LIVE");
		Path out = null;
		java.util.List<Integer> ids = new java.util.ArrayList<>();
		for (int i = 0; i < args.length; i++)
		{
			if ("--out".equals(args[i]) && i + 1 < args.length)
			{
				out = Paths.get(args[++i]);
			}
			else if ("--cache".equals(args[i]) && i + 1 < args.length)
			{
				cacheDir = Paths.get(args[++i]);
			}
			else
			{
				ids.add(Integer.parseInt(args[i]));
			}
		}
		if (out != null)
		{
			Files.createDirectories(out);
		}

		try (Store store = new Store(cacheDir.toFile()))
		{
			store.load();
			Storage storage = store.getStorage();
			Index sprites = store.getIndex(IndexType.SPRITES);
			SpriteLoader loader = new SpriteLoader();

			System.out.printf("%-6s %-5s %-5s %-5s %-6s %-6s %-4s %-4s%n",
				"id", "frame", "w", "h", "maxW", "maxH", "offX", "offY");
			for (int id : ids)
			{
				Archive archive = sprites.getArchive(id);
				if (archive == null)
				{
					System.out.printf("%-6d (no such sprite)%n", id);
					continue;
				}
				FSFile file = archive.getFiles(storage.loadArchive(archive)).findFile(0);
				SpriteDefinition[] frames = loader.load(id, file.getContents());
				for (SpriteDefinition frame : frames)
				{
					System.out.printf("%-6d %-5d %-5d %-5d %-6d %-6d %-4d %-4d%n",
						id, frame.getFrame(), frame.getWidth(), frame.getHeight(),
						frame.getMaxWidth(), frame.getMaxHeight(),
						frame.getOffsetX(), frame.getOffsetY());
					if (out != null && frame.getWidth() > 0 && frame.getHeight() > 0)
					{
						BufferedImage image = new BufferedImage(frame.getWidth(),
							frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
						int[] pixels = frame.getPixels();
						for (int y = 0; y < frame.getHeight(); y++)
						{
							for (int x = 0; x < frame.getWidth(); x++)
							{
								int argb = pixels[y * frame.getWidth() + x];
								// The loader hands back 0 for the transparent colour.
								image.setRGB(x, y, argb == 0 ? 0 : 0xFF000000 | argb);
							}
						}
						ImageIO.write(image, "png",
							out.resolve(id + "_" + frame.getFrame() + ".png").toFile());
					}
				}
			}
		}
	}
}

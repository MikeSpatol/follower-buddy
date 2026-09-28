package com.follower.chronicle;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The sketches are kept: through a restart, one account's apart from
 * another's, and a missing or spoiled file costs a picture, never the book.
 */
public class SketchBookTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static BufferedImage picture(int rgb)
	{
		BufferedImage image = new BufferedImage(12, 6, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < 6; y++)
		{
			for (int x = 0; x < 12; x++)
			{
				image.setRGB(x, y, rgb);
			}
		}
		return image;
	}

	@Test
	public void aSketchSurvivesARestart() throws IOException
	{
		Path data = folder.newFolder().toPath();
		SketchBook before = new SketchBook(data);
		before.openFor("account-one");
		before.put("lumbridge", picture(0x8a6a40), picture(0x9a7a50));
		assertTrue(before.has("lumbridge"));

		SketchBook after = new SketchBook(data);
		after.openFor("account-one");
		BufferedImage back = after.get("lumbridge");
		assertNotNull("kept on disk", back);
		assertEquals(0x8a6a40, back.getRGB(3, 3) & 0xffffff);
		assertEquals("and its large copy beside it", 0x9a7a50, after.getLarge("lumbridge").getRGB(3, 3) & 0xffffff);
		assertTrue(after.hasBoth("lumbridge"));
	}

	@Test
	public void aPlaceDrawnBeforeThereWereTwoSizesIsWantedAgain() throws IOException
	{
		// The first sketches were filed small only; the plate opened across
		// the window needs the large copy, so the scribe draws the place again.
		Path data = folder.newFolder().toPath();
		Path acct = data.resolve(SketchBook.DIR_NAME).resolve("acct");
		Files.createDirectories(acct);
		javax.imageio.ImageIO.write(picture(0x707070), "png", acct.resolve("lumbridge.png").toFile());

		SketchBook book = new SketchBook(data);
		book.openFor("acct");
		assertTrue("the old one still shows", book.has("lumbridge"));
		assertNull(book.getLarge("lumbridge"));
		assertFalse("but it is drawn again", book.hasBoth("lumbridge"));
		assertEquals("the large file is not a place of its own", null, book.get("lumbridge" + SketchBook.LARGE));
	}

	@Test
	public void oneAccountsTravelsAreNotAnothers() throws IOException
	{
		Path data = folder.newFolder().toPath();
		SketchBook book = new SketchBook(data);
		book.openFor("account-one");
		book.put("varrock", picture(0x505050), picture(0x606060));

		book.openFor("account-two");
		assertFalse("the second character has not been there", book.has("varrock"));

		book.openFor("account-one");
		assertTrue("and the first still has", book.has("varrock"));
	}

	@Test
	public void aSpoiledFileCostsOnlyThatPicture() throws IOException
	{
		Path data = folder.newFolder().toPath();
		SketchBook book = new SketchBook(data);
		book.openFor("acct");
		book.put("paterdomus", picture(0x303030), picture(0x404040));
		Files.write(data.resolve(SketchBook.DIR_NAME).resolve("acct").resolve("morytania.png"),
			"not a picture".getBytes(StandardCharsets.UTF_8));

		SketchBook again = new SketchBook(data);
		again.openFor("acct");
		assertTrue(again.has("paterdomus"));
		assertFalse(again.has("morytania"));
	}

	@Test
	public void noFolderIsAnEmptyBookNotAnError() throws IOException
	{
		SketchBook book = new SketchBook(folder.newFolder().toPath());
		book.openFor("somebody-new");
		assertNull(book.get("lumbridge"));
		book.openFor(null);
		book.openFor("");
		new SketchBook(null).openFor("x");
	}

	@Test
	public void aBackupKeepsEveryFileAndNeverWritesOverAnother() throws IOException
	{
		// ::follower force sketch draws a place again on purpose; what was
		// there must survive it, and so must the backup of a force before.
		Path data = folder.newFolder().toPath();
		Path acct = data.resolve(SketchBook.DIR_NAME).resolve("acct");
		SketchBook book = new SketchBook(data);
		book.openFor("acct");
		book.put("lumbridge", picture(0x8a6a40), picture(0x9a7a50));
		byte[] small = Files.readAllBytes(acct.resolve("lumbridge.png"));
		byte[] large = Files.readAllBytes(acct.resolve("lumbridge.large.png"));

		assertEquals(java.util.Arrays.asList("lumbridge.large.png.bak", "lumbridge.png.bak"),
			book.backUp("lumbridge"));
		assertTrue(java.util.Arrays.equals(small, Files.readAllBytes(acct.resolve("lumbridge.png.bak"))));
		assertTrue(java.util.Arrays.equals(large, Files.readAllBytes(acct.resolve("lumbridge.large.png.bak"))));
		assertTrue("the sketch itself stays until it is drawn over", book.hasBoth("lumbridge"));

		book.put("lumbridge", picture(0x101010), picture(0x202020));
		assertEquals(java.util.Arrays.asList("lumbridge.large.png.1.bak", "lumbridge.png.1.bak"),
			book.backUp("lumbridge"));
		assertTrue("the first backup is untouched",
			java.util.Arrays.equals(small, Files.readAllBytes(acct.resolve("lumbridge.png.bak"))));

		SketchBook again = new SketchBook(data);
		again.openFor("acct");
		assertEquals("a backup is not read back as a place", 0x101010,
			again.get("lumbridge").getRGB(3, 3) & 0xffffff);
		assertNull(again.get("lumbridge.png"));
	}

	@Test
	public void nothingOnDiskIsNothingToBackUp() throws IOException
	{
		SketchBook book = new SketchBook(folder.newFolder().toPath());
		book.openFor("acct");
		assertTrue(book.backUp("lumbridge").isEmpty());
		assertTrue(new SketchBook(null).backUp("lumbridge").isEmpty());
	}

	@Test
	public void anAccountKeyIsAlwaysAFileName()
	{
		assertEquals("rsprofile_abc_123", SketchBook.safe("rsprofile.abc:123"));
		assertEquals("___", SketchBook.safe("../"));
	}
}

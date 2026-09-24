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
		before.put("lumbridge", picture(0x8a6a40));
		assertTrue(before.has("lumbridge"));

		SketchBook after = new SketchBook(data);
		after.openFor("account-one");
		BufferedImage back = after.get("lumbridge");
		assertNotNull("kept on disk", back);
		assertEquals(0x8a6a40, back.getRGB(3, 3) & 0xffffff);
	}

	@Test
	public void oneAccountsTravelsAreNotAnothers() throws IOException
	{
		Path data = folder.newFolder().toPath();
		SketchBook book = new SketchBook(data);
		book.openFor("account-one");
		book.put("varrock", picture(0x505050));

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
		book.put("paterdomus", picture(0x303030));
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
	public void anAccountKeyIsAlwaysAFileName()
	{
		assertEquals("rsprofile_abc_123", SketchBook.safe("rsprofile.abc:123"));
		assertEquals("___", SketchBook.safe("../"));
	}
}

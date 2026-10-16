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

	/** Forget everything forgets the Chronicle's drawings too (the user, 16 Oct). */
	@Test
	public void forgettingTearsOutThisAccountsDrawingsAndNoOneElses() throws IOException
	{
		Path data = folder.newFolder().toPath();
		SketchBook book = new SketchBook(data);
		book.openFor("account-two");
		book.put("varrock", picture(0x405060), picture(0x506070));
		book.openFor("account-one");
		book.put("lumbridge", picture(0x8a6a40), picture(0x9a7a50), "dance");
		book.backUp("lumbridge");

		book.forgetAll();
		assertFalse("gone from the open book", book.has("lumbridge"));
		SketchBook again = new SketchBook(data);
		again.openFor("account-one");
		assertFalse("and from disk, with its pose and backups", again.has("lumbridge"));
		try (java.util.stream.Stream<Path> left = java.nio.file.Files.list(data.resolve(SketchBook.DIR_NAME).resolve("account-one")))
		{
			assertEquals(0, left.count());
		}
		again.openFor("account-two");
		assertTrue("another account's book is its own", again.has("varrock"));
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
	public void aSketchIsReadWhenItIsFirstWanted() throws IOException
	{
		// Every sketch was decoded at login and held for the session: about
		// 9 MB and a quarter of a second for a whole book (perf audit, #60).
		Path data = folder.newFolder().toPath();
		Path acct = data.resolve(SketchBook.DIR_NAME).resolve("acct");
		Files.createDirectories(acct);
		javax.imageio.ImageIO.write(picture(0x111111), "png", acct.resolve("lumbridge.png").toFile());
		javax.imageio.ImageIO.write(picture(0x222222), "png", acct.resolve("lumbridge.large.png").toFile());

		SketchBook book = new SketchBook(data);
		book.openFor("acct");
		javax.imageio.ImageIO.write(picture(0x333333), "png", acct.resolve("lumbridge.png").toFile());
		javax.imageio.ImageIO.write(picture(0x444444), "png", acct.resolve("lumbridge.large.png").toFile());
		assertTrue(book.hasBoth("lumbridge"));
		assertEquals("read when asked for, not at opening", 0x333333, book.get("lumbridge").getRGB(3, 3) & 0xffffff);
		assertEquals(0x444444, book.getLarge("lumbridge").getRGB(3, 3) & 0xffffff);

		// And once read, kept: the page does not read the file every frame.
		javax.imageio.ImageIO.write(picture(0x555555), "png", acct.resolve("lumbridge.png").toFile());
		assertEquals(0x333333, book.get("lumbridge").getRGB(3, 3) & 0xffffff);
	}

	@Test
	public void aRedrawKeepsOnlyTheLastFewBackups() throws IOException
	{
		// Each redraw kept the old drawing for good: 18 backups, 1.65 MB of a
		// 4 MB folder, on the developer's machine (perf audit, #61).
		Path data = folder.newFolder().toPath();
		Path acct = data.resolve(SketchBook.DIR_NAME).resolve("acct");
		SketchBook book = new SketchBook(data);
		book.openFor("acct");
		for (int i = 0; i < 5; i++)
		{
			book.put("lumbridge", picture(0x101010 * (i + 1)), picture(0x9a7a50));
			book.backUp("lumbridge");
		}
		java.util.List<String> left = new java.util.ArrayList<>();
		try (java.util.stream.Stream<Path> files = Files.list(acct))
		{
			files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("lumbridge.png") && n.endsWith(".bak"))
				.sorted().forEach(left::add);
		}
		assertEquals(java.util.Arrays.asList("lumbridge.png.2.bak", "lumbridge.png.3.bak", "lumbridge.png.4.bak"), left);
		assertEquals("the newest is the last drawing", 0x505050,
			javax.imageio.ImageIO.read(acct.resolve("lumbridge.png.4.bak").toFile()).getRGB(3, 3) & 0xffffff);
		assertFalse(Files.exists(acct.resolve("lumbridge.large.png.1.bak")));
		assertTrue(Files.exists(acct.resolve("lumbridge.large.png.4.bak")));
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

	@Test
	public void thePoseIsKeptWithItsSketchThroughARestart() throws IOException
	{
		Path data = folder.newFolder().toPath();
		SketchBook before = new SketchBook(data);
		before.openFor("acct");
		before.put("lumbridge", picture(0x8a6a40), picture(0x9a7a50), "dance");
		before.put("varrock-square", picture(0x505050), picture(0x606060));
		assertEquals("dance", before.poseOf("lumbridge"));

		SketchBook after = new SketchBook(data);
		after.openFor("acct");
		assertEquals("dance", after.poseOf("lumbridge"));
		assertNull("one drawn standing still has none", after.poseOf("varrock-square"));
		assertNull(after.poseOf(null));

		after.openFor("another");
		assertNull("another account's sketches are not theirs", after.poseOf("lumbridge"));
	}

	@Test
	public void aSketchFromBeforeThereWerePosesHasNone() throws IOException
	{
		Path data = folder.newFolder().toPath();
		Path acct = data.resolve(SketchBook.DIR_NAME).resolve("acct");
		Files.createDirectories(acct);
		javax.imageio.ImageIO.write(picture(0x707070), "png", acct.resolve("lumbridge.png").toFile());
		javax.imageio.ImageIO.write(picture(0x808080), "png", acct.resolve("lumbridge.large.png").toFile());

		SketchBook book = new SketchBook(data);
		book.openFor("acct");
		assertTrue(book.hasBoth("lumbridge"));
		assertNull(book.poseOf("lumbridge"));
	}

	@Test
	public void thePoseFollowsTheDrawingAndTheOldOneIsBackedUpWithIt() throws IOException
	{
		// Drawn again on purpose: the old drawing, pose and all, kept beside
		// it to be put back by hand, and the new drawing's pose, or none, shown.
		Path data = folder.newFolder().toPath();
		Path acct = data.resolve(SketchBook.DIR_NAME).resolve("acct");
		SketchBook book = new SketchBook(data);
		book.openFor("acct");
		book.put("lumbridge", picture(0x8a6a40), picture(0x9a7a50), "wave");

		assertEquals(java.util.Arrays.asList("lumbridge.large.png.bak", "lumbridge.png.bak", "lumbridge.pose.bak"),
			book.backUp("lumbridge"));
		assertEquals("wave", new String(Files.readAllBytes(acct.resolve("lumbridge.pose.bak")), StandardCharsets.UTF_8));

		book.put("lumbridge", picture(0x101010), picture(0x202020), "bow");
		assertEquals("bow", book.poseOf("lumbridge"));

		book.backUp("lumbridge");
		book.put("lumbridge", picture(0x303030), picture(0x404040), null);
		assertNull("drawn again standing still, the old pose goes", book.poseOf("lumbridge"));
		assertFalse(Files.exists(acct.resolve("lumbridge.pose")));

		SketchBook again = new SketchBook(data);
		again.openFor("acct");
		assertNull("and stays gone; a backup is not read back", again.poseOf("lumbridge"));
	}
}

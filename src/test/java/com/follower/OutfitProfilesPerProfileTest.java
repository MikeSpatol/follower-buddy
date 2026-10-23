// public:file outfit-profiles
package com.follower;

import com.follower.appearance.OutfitProfileStore;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Each RuneLite profile keeps its own outfit profiles.
 *
 * <p>Release play test, 29 Sep (and GitHub issue #1): one list in
 * outfit-profiles.json was shared by every RuneLite profile, every outfit
 * change saved into the selected name, and a profile switch reloaded that
 * name. Two profiles on "Default follower" wrote over each other, so
 * switching back put the other profile's outfit on this one.
 */
public class OutfitProfilesPerProfileTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static final String SHARED = "{\"Default follower\": \"TORSO=item:23336,SHIELD=item:10352\"}";

	/** One RuneLite profile: its own settings, as a string the store reads and writes. */
	private static final class Profile
	{
		final AtomicReference<String> kept = new AtomicReference<>("");
		final OutfitProfileStore store = new OutfitProfileStore(new Gson());

		Profile load(Path dir, String active, String worn)
		{
			store.load(dir, kept.get(), active, worn, kept::set);
			return this;
		}
	}

	private Path sharedDir() throws IOException
	{
		Path dir = folder.newFolder().toPath();
		Files.write(dir.resolve("outfit-profiles.json"), SHARED.getBytes(StandardCharsets.UTF_8));
		return dir;
	}

	@Test
	public void anOutfitChangedOnOneProfileLeavesTheOtherAlone() throws IOException
	{
		Path dir = sharedDir();
		Profile a = new Profile().load(dir, "Default follower", "TORSO=item:23336,SHIELD=item:10352");
		Profile b = new Profile().load(dir, "Default follower", "TORSO=item:23336,SHIELD=item:10352");

		b.store.put("Default follower", "HEAD=item:25322");

		a.load(dir, "Default follower", "TORSO=item:23336,SHIELD=item:10352");
		assertEquals("switching back to A finds A's own",
			"TORSO=item:23336,SHIELD=item:10352", a.store.get("Default follower"));
		b.load(dir, "Default follower", "HEAD=item:25322");
		assertEquals("and B still has its edit", "HEAD=item:25322", b.store.get("Default follower"));
	}

	@Test
	public void aProfilesFirstLoadStartsFromTheSharedListWhichIsNeverWrittenAgain() throws IOException
	{
		Path dir = sharedDir();
		byte[] before = Files.readAllBytes(dir.resolve("outfit-profiles.json"));

		// A profile the old version ran: it had an outfit selected.
		Profile a = new Profile().load(dir, "Default follower", "TORSO=item:23336,SHIELD=item:10352");
		assertEquals("its list starts as the shared one",
			"TORSO=item:23336,SHIELD=item:10352", a.store.get("Default follower"));
		assertFalse("and is kept with the profile at once", a.kept.get().isEmpty());

		a.store.put("My costume", "WEAPON=item:4151");
		assertArrayEquals("the shared file is only read now",
			before, Files.readAllBytes(dir.resolve("outfit-profiles.json")));
	}

	@Test
	public void aProfilesFirstLoadKeepsTheOutfitItIsWearing() throws IOException
	{
		// Before this version another profile may already have written over
		// the shared entry; what this profile is wearing is the truer record.
		Path dir = sharedDir();
		Profile a = new Profile().load(dir, "Default follower", "HEAD=item:25322,TORSO=item:23336");

		assertEquals("HEAD=item:25322,TORSO=item:23336", a.store.get("Default follower"));
	}

	@Test
	public void aProfileWithItsOwnListIgnoresTheSharedFile() throws IOException
	{
		Path dir = sharedDir();
		Profile a = new Profile().load(dir, null, null);
		a.store.put("Default follower", "HEAD=item:1");

		Files.write(dir.resolve("outfit-profiles.json"),
			"{\"Default follower\": \"HEAD=item:2\"}".getBytes(StandardCharsets.UTF_8));
		a.load(dir, null, null);

		assertEquals("HEAD=item:1", a.store.get("Default follower"));
	}

	/**
	 * Review, 29 Sep: RuneLite's Reset clears the hidden list with the rest,
	 * and a profile made after the upgrade never had one. Neither ran the old
	 * version, so neither starts from the shared list as it stood on the day
	 * of the upgrade: both start from the defaults.
	 */
	@Test
	public void aProfileTheOldVersionNeverRanStartsFromTheDefaults() throws IOException
	{
		Path dir = sharedDir();
		Profile reset = new Profile().load(dir, "", "");

		assertEquals("the bare body, not the shared entry", "", reset.store.get("Default follower"));
	}

	@Test
	public void aNewProfileStillHasTheThrallStyles() throws IOException
	{
		Profile a = new Profile().load(folder.newFolder().toPath(), null, null);
		for (String name : new String[]{"Melee", "Ranged", "Magic", OutfitProfileStore.DEFAULT_PROFILE})
		{
			assertTrue(name, a.store.get(name) != null);
		}
	}

	/**
	 * The switch is glue no harness drives, so it is read out of the source:
	 * the new profile's list has to be read before its outfit is restored
	 * from it, or the switch restores from the last profile's list.
	 */
	@Test
	public void aSwitchReadsTheNewProfilesListBeforeRestoringItsOutfit() throws IOException
	{
		String source = PublicMarkers.strip(new String(Files.readAllBytes(
			Paths.get("src/main/java/com/follower/FollowerPlugin.java")), StandardCharsets.UTF_8));
		int handler = source.indexOf("public void onProfileChanged(");
		int end = source.indexOf("\n\t}", handler);
		String body = source.substring(handler, end);
		int load = body.indexOf("loadOutfitProfiles();");
		int restore = body.indexOf("restoreActiveProfile();");
		assertTrue("the switch reads the new profile's list", load >= 0);
		assertTrue("before it restores the outfit from it", restore > load);
	}
}

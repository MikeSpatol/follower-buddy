package com.follower;

import com.follower.appearance.ModelRepository;
import com.follower.follower.StanceLibrary;
import com.google.gson.Gson;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

/**
 * What {@code ::follower stance <weaponId>} says about a weapon.
 */
public class StanceStatusTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private StanceLibrary library;

	@Before
	public void setUp() throws IOException
	{
		library = new StanceLibrary(new Gson(), new ModelRepository(new Gson()));
		library.load(folder.newFolder().toPath());
	}

	@Test
	public void theAttackShownIsTheOneTheFollowerUses()
	{
		// Set by hand with no attack: the learned record knows none, and the
		// shipped lash is still what the follower swings.
		library.setManual(4151, 808, 1660, 1661, 0);

		assertEquals("Abyssal whip: idle 808, walk 1660, run 1661, attack 1658",
			FollowerPlugin.stanceStatus(library, "Abyssal whip", 4151));
	}

	@Test
	public void aWeaponWithNoStanceSaysSo()
	{
		assertEquals("Item 999999: no stance known - it will stand unarmed",
			FollowerPlugin.stanceStatus(library, null, 999999));
	}
}

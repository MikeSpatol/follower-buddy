package com.follower;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The follower's menu belongs to the game scene, not to whatever interface is
 * drawn over it. QA, 15 Oct: its clickbox is a projection and goes on under
 * the bank, the inventory and the chatbox, so Talk-to took the left-click
 * from a bank item, the follower's options sat above the item's own, and
 * shift-click drop was swallowed. The game offers Walk here only over the
 * scene, so that is the test, as Send has always used it.
 *
 * <p>The handlers need a client and an event bus the harness does not have,
 * so, as in {@link LifecycleGlueTest}, the reading is kept as a lint.
 */
public class MenuOverTheSceneTest
{
	private static final Path PLUGIN = Paths.get("src/main/java/com/follower/FollowerPlugin.java");

	private static String source() throws IOException
	{
		assertTrue("cannot read " + PLUGIN, Files.isRegularFile(PLUGIN));
		return new String(Files.readAllBytes(PLUGIN), StandardCharsets.UTF_8).replace("\r\n", "\n");
	}

	/** The plugin's members, split where one closes at the class's own depth. */
	private static String[] members() throws IOException
	{
		return source().split("\n\t\\};?\n");
	}

	private static MenuEntry entry(MenuAction type)
	{
		return (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
			new Class<?>[]{MenuEntry.class},
			(proxy, method, args) -> "getType".equals(method.getName()) ? type : null);
	}

	@Test
	public void aMenuWithWalkHereIsTheScenes()
	{
		assertTrue(FollowerPlugin.offersWalkHere(new MenuEntry[]{
			entry(MenuAction.CANCEL), entry(MenuAction.WALK), entry(MenuAction.RUNELITE)}));
		assertFalse("an inventory slot's menu", FollowerPlugin.offersWalkHere(new MenuEntry[]{
			entry(MenuAction.CANCEL), entry(MenuAction.CC_OP), entry(MenuAction.CC_OP)}));
		assertFalse(FollowerPlugin.offersWalkHere(new MenuEntry[0]));
		assertFalse(FollowerPlugin.offersWalkHere(null));
	}

	/**
	 * QA, 15 Oct: the hover Talk-to goes on top of the menu, so it took the
	 * left-click from a bank booth, a tree, an NPC to attack. It is offered
	 * over bare ground only.
	 */
	@Test
	public void theHoverTalkToIsOfferedOverBareGroundOnly() throws IOException
	{
		assertTrue("bare ground", FollowerPlugin.onlyGroundUnder(new MenuEntry[]{
			entry(MenuAction.CANCEL), entry(MenuAction.WALK)}));
		assertTrue("a plugin's own entry is not the world's", FollowerPlugin.onlyGroundUnder(new MenuEntry[]{
			entry(MenuAction.CANCEL), entry(MenuAction.WALK), entry(MenuAction.RUNELITE)}));
		assertFalse("a bank booth", FollowerPlugin.onlyGroundUnder(new MenuEntry[]{
			entry(MenuAction.CANCEL), entry(MenuAction.WALK), entry(MenuAction.EXAMINE_OBJECT),
			entry(MenuAction.GAME_OBJECT_FIRST_OPTION)}));
		assertFalse("an NPC", FollowerPlugin.onlyGroundUnder(new MenuEntry[]{
			entry(MenuAction.CANCEL), entry(MenuAction.WALK), entry(MenuAction.NPC_SECOND_OPTION)}));
		assertFalse("a player", FollowerPlugin.onlyGroundUnder(new MenuEntry[]{
			entry(MenuAction.CANCEL), entry(MenuAction.WALK), entry(MenuAction.PLAYER_FIRST_OPTION)}));
		assertFalse("an item on the ground", FollowerPlugin.onlyGroundUnder(new MenuEntry[]{
			entry(MenuAction.CANCEL), entry(MenuAction.WALK), entry(MenuAction.GROUND_ITEM_THIRD_OPTION)}));
		assertFalse(FollowerPlugin.onlyGroundUnder(null));

		String tick = source().substring(source().indexOf("public void onClientTick("));
		tick = tick.substring(0, tick.indexOf("\n\t}\n"));
		int added = tick.indexOf("addFollowerMenuEntry(talkOption()");
		assertTrue("the hover entry has moved - the lint has lost its place", added >= 0);
		assertTrue("the hover entry asks what else is under the mouse first",
			tick.substring(0, added).contains("onlyGroundUnder("));
	}

	@Test
	public void everyLookUnderTheMouseAsksWhetherItIsOverTheScene() throws IOException
	{
		List<String> unguarded = new ArrayList<>();
		int found = 0;
		for (String member : members())
		{
			int at = member.indexOf("follower.isUnderMouse(");
			if (at < 0)
			{
				continue;
			}
			found++;
			if (!member.substring(at).contains("offersWalkHere("))
			{
				unguarded.add(member.substring(Math.max(0, member.lastIndexOf("\n\t", at) - 200), at).trim());
			}
		}
		assertTrue("no look under the mouse found at all - the lint has lost its place", found >= 4);
		assertTrue("the follower under the mouse is not the follower clicked while an interface"
			+ " covers it:\n" + String.join("\n---\n", unguarded), unguarded.isEmpty());
	}

	@Test
	public void noEntryIsAddedBeforeTheMenuIsKnownToBeTheScenes() throws IOException
	{
		List<String> unguarded = new ArrayList<>();
		int found = 0;
		for (String member : members())
		{
			if (member.contains("private void addFollowerMenuEntry("))
			{
				continue;
			}
			int first = -1;
			for (String add : new String[]{"addFollowerMenuEntry(", "createMenuEntry("})
			{
				int at = member.indexOf(add);
				if (at >= 0 && (first < 0 || at < first))
				{
					first = at;
				}
			}
			if (first < 0)
			{
				continue;
			}
			found++;
			if (!member.substring(0, first).contains("offersWalkHere("))
			{
				unguarded.add(member.substring(Math.max(0, first - 300), first).trim());
			}
		}
		assertTrue("no menu entries added at all - the lint has lost its place", found >= 3);
		assertTrue("a menu entry added without asking whether the menu is the scene's:\n"
			+ String.join("\n---\n", unguarded), unguarded.isEmpty());
	}
}

package com.follower;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Development launcher. Starts a real RuneLite client with Follower Buddy loaded as a
 * built-in plugin, so you can test without publishing to the plugin hub.
 *
 * <p>Run it with {@code gradlew runClient}, or straight from your IDE by running this
 * class's main method.
 *
 * <p>Lives in the test source set on purpose: it depends on the RuneLite client at
 * runtime, which the plugin jar itself must not.
 */
public class FollowerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(FollowerPlugin.class);
		RuneLite.main(args);
	}
}

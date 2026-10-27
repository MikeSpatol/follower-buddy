package com.follower;

import java.lang.reflect.Field;
import net.runelite.api.Client;

/**
 * For tests that build a bare FollowerPlugin (no startUp) and drive its event
 * handlers or tick methods: the idle controllers startUp always builds before
 * RuneLite registers the handlers, so the handlers can use them without a
 * null check, as they do in the plugin. Each is built from the plugin's own
 * client, follower and config, whatever the test set, and a controller the
 * test already set is left alone.
 */
final class IdleControllers
{
	private IdleControllers()
	{
	}

	static void fill(FollowerPlugin plugin) throws ReflectiveOperationException
	{
		// A test that set no client gets an idle world's, for the controllers only.
		Client client = get(plugin, "client") != null ? (Client) get(plugin, "client")
			: new FakeGame().client;
		FollowerEntity follower = (FollowerEntity) get(plugin, "follower");
		FollowerConfig config = (FollowerConfig) get(plugin, "config");
		SpeechEngine engine = (SpeechEngine) get(plugin, "speechEngine");
		ErrandController.Hands hands = new ErrandController.Hands()
		{
			@Override
			public void hold(int itemId)
			{
			}

			@Override
			public void release()
			{
			}
		};
		fillIn(plugin, "errands", () -> new ErrandController(client, follower, config,
			event -> { }, null, () -> false, hands));
		fillIn(plugin, "spectate", () -> new SpectateController(client, follower, config,
			() -> engine == null ? null : engine.getContext(), null, event -> { }, disarm -> { }));
		fillIn(plugin, "skilling", () -> new SkillingController(client, follower, config,
			hands, event -> { }, () -> false, () -> false, () -> false));
		fillIn(plugin, "moments", () -> new Moments(client, follower,
			(SkillingController) get(plugin, "skilling"),
			() -> false, () -> false, () -> 0, null,
			() -> false,
			() -> false));
		// With no player tile to read, Will.send falls straight back to
		// follower.stayAt, which is what these fakes give it.
		fillIn(plugin, "will", () -> new Will(client, follower, event -> { }, () -> true));
	}

	private interface Idle
	{
		Object build() throws ReflectiveOperationException;
	}

	private static void fillIn(FollowerPlugin plugin, String name, Idle idle) throws ReflectiveOperationException
	{
		if (get(plugin, name) == null)
		{
			Field field = FollowerPlugin.class.getDeclaredField(name);
			field.setAccessible(true);
			field.set(plugin, idle.build());
		}
	}

	private static Object get(FollowerPlugin plugin, String name) throws ReflectiveOperationException
	{
		Field field = FollowerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(plugin);
	}
}

package com.follower;

import com.follower.follower.ErrandController;
import com.follower.follower.FollowerEntity;
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
			: new com.follower.sim.FakeGame().client;
		FollowerEntity follower = (FollowerEntity) get(plugin, "follower");
		FollowerConfig config = (FollowerConfig) get(plugin, "config");
		com.follower.speech.SpeechEngine engine = (com.follower.speech.SpeechEngine) get(plugin, "speechEngine");
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
		// public:cut errands
		fillIn(plugin, "errands", () -> new ErrandController(client, follower, config,
			event -> { }, null, () -> false, hands));
		// public:end errands
		// public:cut spectate,spectate-keep-fight-lines
		fillIn(plugin, "spectate", () -> new com.follower.follower.SpectateController(client, follower, config,
			() -> engine == null ? null : engine.getContext(), null, event -> { }, disarm -> { })); // public:cut spectate-shield
			// public:else () -> engine == null ? null : engine.getContext(), event -> { }));
		// public:end spectate,spectate-keep-fight-lines
		// public:cut skilling
		fillIn(plugin, "skilling", () -> new com.follower.follower.SkillingController(client, follower, config,
			hands, event -> { }, () -> false, () -> false, () -> false));
		// public:end skilling
		// public:cut moments
		fillIn(plugin, "moments", () -> new com.follower.follower.Moments(client, follower,
			(com.follower.follower.SkillingController) get(plugin, "skilling"), // public:cut skilling
			() -> false, () -> false, () -> 0));
		// public:end moments
		// public:cut company-extras,will
		// With no player tile to read, Will.send falls straight back to
		// follower.stayAt, which is what these fakes give it.
		fillIn(plugin, "will", () -> new com.follower.follower.Will(client, follower, event -> { }, () -> true));
		// public:end company-extras,will
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

package com.follower;

import com.follower.follower.FollowerEntity;
import com.follower.sim.Harness;
import com.follower.speech.SpeechOutput;
import com.follower.speech.SpeechRule;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Where a rule's gesture goes once its line is said (QA, 15 Oct). Said on
 * the move, it waits for the follower to stand, or the walk drops it on its
 * first frame. Said while the follower rests on its sit, the body stays
 * seated: the gesture would pop it up with no stand-up and sit it down again.
 *
 * <p>The plugin's glue, which no harness drives, so through a bare plugin as
 * StayHoldsTest does it.
 */
public class RuleGestureTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	/** Records the gestures asked for; plays none of them. */
	private static final class Body extends FollowerEntity
	{
		final List<Integer> now = new ArrayList<>();
		final List<Integer> whenStill = new ArrayList<>();
		boolean planted;

		Body()
		{
			super(null, null);
		}

		@Override
		public void playAnimation(int id)
		{
			now.add(id);
		}

		@Override
		public void playAnimations(int[] ids)
		{
			for (int id : ids)
			{
				now.add(id);
			}
		}

		@Override
		public void playWhenStill(int id, int ticks)
		{
			assertEquals("a few ticks at most", FollowerPlugin.EMOTE_WAIT_TICKS, ticks);
			whenStill.add(id);
		}

		@Override
		public void stayHere()
		{
			planted = true;
		}
	}

	private FollowerPlugin plugin;
	private Body body;

	@Before
	public void setUp() throws IOException, ReflectiveOperationException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		plugin = new FollowerPlugin();
		body = new Body();
		set("client", h.game.client);
		set("config", new FollowerConfig()
		{
		});
		set("follower", body);
		set("speechEngine", h.engine);
		set("dialog", new com.follower.speech.FollowerDialog(null, null, null, null, null, null, null, null));
		set("clientThread", onTheClientThread());
	}

	private static SpeechRule gesture(int animation)
	{
		SpeechRule rule = new SpeechRule();
		rule.id = "greet-back";
		rule.animation = animation;
		return rule;
	}

	@Test
	public void aRulesGestureWaitsForTheFollowerToStand() throws ReflectiveOperationException
	{
		speak(gesture(863), 863);
		assertEquals(Collections.singletonList(863), body.whenStill);
		assertTrue(body.now.isEmpty());
	}

	@Test
	public void aPlantedGesturePlaysAtOnceSinceTheFeetAreAlreadyStopped() throws ReflectiveOperationException
	{
		SpeechRule rule = gesture(862);
		rule.holdStill = true;
		speak(rule, 862);
		assertTrue(body.planted);
		assertEquals(Collections.singletonList(862), body.now);
		assertTrue(body.whenStill.isEmpty());
	}

	@Test
	public void aGestureOnTheRestLeavesTheFollowerSeated() throws ReflectiveOperationException
	{
		set("resting", true);
		SpeechRule rule = gesture(4278);
		rule.holdStill = true;
		speak(rule, 4278);
		assertTrue("no gesture from the sit", body.now.isEmpty() && body.whenStill.isEmpty());
		assertFalse("and no plant", body.planted);
	}

	@Test
	public void aWritingFlourishIsNotTakenOutOnTheRestEither() throws IOException
	{
		// The flourish would put the writing pose over the sit: the same pop.
		String source = PublicMarkers.strip(new String(Files.readAllBytes(Paths.get("src/main/java/com/follower/FollowerPlugin.java")),
			StandardCharsets.UTF_8).replace("\r\n", "\n"));
		int flourish = source.indexOf("if (rule != null && rule.prop != null && flourish != null");
		assertTrue("the flourish has moved", flourish > 0);
		assertTrue(source.substring(flourish, source.indexOf("{", flourish)).contains("!seated"));
	}

	@Test
	public void theReachComesFirstAndThePropAtTheBottomOfIt() throws IOException
	{
		// QA, 15 Oct: the prop was put in the hand and then the reach played,
		// so the follower bent to the floor already holding it. And the
		// writing pair takes no reach: 13302 lifts them from the waist itself.
		String source = PublicMarkers.strip(new String(Files.readAllBytes(Paths.get("src/main/java/com/follower/FollowerPlugin.java")),
			StandardCharsets.UTF_8).replace("\r\n", "\n"));
		int pair = source.indexOf("public void hold(int leftItemId, int rightItemId)");
		int single = source.indexOf("public void hold(int itemId)");
		assertTrue(pair > 0 && single > 0);
		assertTrue("a single tool reaches",
			source.substring(single, source.indexOf("\n\t\t\t\t}\n", single)).contains("takeOut(true);"));
		assertTrue("the quill and scroll do not",
			source.substring(pair, source.indexOf("\n\t\t\t\t}\n", pair)).contains("takeOut(false);"));
		int takeOut = source.indexOf("private void takeOut(boolean reach)");
		String body = source.substring(takeOut, source.indexOf("\n\t\t\t\t}\n", takeOut));
		int reach = body.indexOf("follower.playAnimations(clip);");
		assertTrue("the reach plays", reach > 0);
		assertTrue("the prop waits behind it", body.lastIndexOf("propBehindReach = true;", reach) > 0);
		assertTrue("and comes in partway",
			body.indexOf("follower.atEmoteFraction(REACH_BOTTOM", reach) > reach);
		String rebuild = source.substring(source.indexOf("private void rebuildFollower()"));
		assertTrue("the model leaves out a prop behind its reach",
			rebuild.substring(0, rebuild.indexOf("\n\t}\n")).contains("!propBehindReach"));
	}

	private void speak(SpeechRule rule, int animationId) throws ReflectiveOperationException
	{
		Class<?> utterance = Class.forName("com.follower.FollowerPlugin$Utterance");
		Constructor<?> line = utterance.getDeclaredConstructor(String.class, SpeechOutput.class,
			SpeechRule.class, int.class, Runnable.class);
		line.setAccessible(true);
		Method speakNow = FollowerPlugin.class.getDeclaredMethod("speakNow", utterance);
		speakNow.setAccessible(true);
		speakNow.invoke(plugin, line.newInstance("", SpeechOutput.CHATBOX, rule, animationId, null));
	}

	/** A client thread that runs what it is given at once, as on the client thread itself. */
	private static Object onTheClientThread() throws ReflectiveOperationException
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) -> method.getName().equals("isClientThread") ? Boolean.TRUE : null);
		net.runelite.client.callback.ClientThread thread = new net.runelite.client.callback.ClientThread();
		Field field = thread.getClass().getDeclaredField("client");
		field.setAccessible(true);
		field.set(thread, client);
		return thread;
	}

	private void set(String name, Object value) throws ReflectiveOperationException
	{
		Field field = FollowerPlugin.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(plugin, value);
	}
}

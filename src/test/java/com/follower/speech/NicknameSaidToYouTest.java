package com.follower.speech;

import com.follower.sim.Harness;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A nickname said to the player is a name. Text QA pass 3, #21: every
 * shipped nickname starts with "the", and the lines that greet the player
 * by it said "Hello, the swot." and "All right, the gravedigger."
 */
public class NicknameSaidToYouTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static String said(Harness h, String template, String nickname)
	{
		TriggerEvent event = TriggerEvent.simple(TriggerEvent.Type.MANUAL);
		event.getPlaceholders().put("nickname", nickname);
		event.getPlaceholders().put("candidate", nickname);
		return h.engine.spokenText(template, event);
	}

	@Test
	public void calledByItTheNameLosesItsThe() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		assertEquals("Hello, gravedigger. I've saved you a fresh page.",
			said(h, "Hello, {nickname}. I've saved you a fresh page.", "the gravedigger"));
		assertEquals("All right, swot.", said(h, "All right, {nickname}.", "the swot"));

		// Spoken about, it keeps it.
		assertEquals("The gravedigger. Back again. Good.",
			said(h, "{nickname}. Back again. Good.", "the gravedigger"));
		assertEquals("It's official. The index says the swot.",
			said(h, "It's official. The index says {nickname}.", "the swot"));
		assertEquals("Then that's settled. The local menace.",
			said(h, "Then that's settled. {nickname}.", "the local menace"));
	}

	@Test
	public void noShippedLineCallsYouTheAnything() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> bad = new ArrayList<>();
		int checked = 0;
		for (SpeechRule rule : h.loader.getRules())
		{
			if (rule.say == null)
			{
				continue;
			}
			for (String template : rule.say)
			{
				if (!template.contains("{nickname}") && !template.contains("{candidate}"))
				{
					continue;
				}
				for (String nickname : h.loader.getNicknames().values())
				{
					checked++;
					String line = said(h, template, nickname).toLowerCase(Locale.ROOT);
					if (line.matches(".*, " + nickname + "[.!?].*"))
					{
						bad.add(rule.id + ": " + line);
					}
				}
			}
		}
		assertTrue("the scan found the nickname lines", checked >= 30);
		assertTrue("greeted as \"the\" something: " + bad, bad.isEmpty());
	}
}

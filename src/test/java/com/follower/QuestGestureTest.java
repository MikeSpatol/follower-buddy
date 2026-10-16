package com.follower;

import com.follower.sim.Harness;
import com.follower.speech.SpeechRule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.gameval.AnimationID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;

/**
 * A quest moment's gesture is one the eye can see (QA, 15 and 16 Oct).
 * HUMAN_DISTANT_IDLE sits 16 units from the idle at most, so a line said
 * over it looks like the follower doing nothing; the concerned idle sits 63
 * units off it, eased in and out (cache, default male body). The look about
 * the peer and the thieving lookout now play was swapped in for the same
 * reason. Only the mood rules keep it, as a quiet beat of their own.
 */
public class QuestGestureTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void noQuestMomentPlaysTheDistantIdle() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		List<String> still = new ArrayList<>();
		for (SpeechRule rule : h.loader.getRules())
		{
			if ("quest".equals(rule.group) && rule.animation != null
				&& rule.animation == AnimationID.HUMAN_DISTANT_IDLE)
			{
				still.add(rule.id);
			}
		}
		assertTrue("a gesture nobody can see: " + still, still.isEmpty());
	}
}

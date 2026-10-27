package com.follower;

import com.follower.sim.Harness;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

/**
 * QA3: a rule sits in the group whose setting and Messages category say
 * what it is. The dare made during a fight was under Idle chatter ("while
 * nothing much is happening"), and the remark on saving the Messages
 * window's own edits under Reminiscing.
 */
public class GroupFitTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void theDareIsAPredictionAndTheEditRemarkIsEverythingElse() throws IOException
	{
		Harness h = new Harness(folder.newFolder().toPath());
		// public:cut side-games
		for (String id : new String[]{"offer-challenge", "challenge-met", "challenge-failed"})
		{
			assertEquals(id, "bet", h.rule(id).group);
		}
		// public:end side-games
		assertEquals("misc", h.rule("notes-edited").group);
	}
}

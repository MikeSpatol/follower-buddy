// public:file dialogs-editor
package com.follower.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Conversations window shows an example in an empty Choices box. QA,
 * 15 Oct: it showed on every node, including those that go on by Next or
 * end, where no choices is right, so it read as something missing.
 */
public class DialogsDialogTest
{
	@Test
	public void theExampleShowsOnlyWhereChoicesCouldGo()
	{
		assertTrue("no choices and no Next", DialogsDialog.showsGhost("", ""));
		assertTrue("a blank Next is no Next", DialogsDialog.showsGhost("", "  "));
		assertFalse("a node that goes on by Next", DialogsDialog.showsGhost("", "ask"));
		assertFalse("a node with choices", DialogsDialog.showsGhost("Yes -> thanks", ""));
	}
}

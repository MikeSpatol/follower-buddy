package com.follower.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The few component recipes the sidebar panel and the three windows all
 * repeat: small grey text, dark panels and columns, wrapped HTML labels, a
 * vertical-only scroller and a document listener that just runs something.
 */
final class Ui
{
	private Ui()
	{
	}

	/** The component in RuneLite's small font, in the given colour. */
	static <T extends JComponent> T small(T component, Color colour)
	{
		component.setFont(FontManager.getRunescapeSmallFont());
		component.setForeground(colour);
		return component;
	}

	/** A small-font label. */
	static JLabel label(String text, Color colour)
	{
		return small(new JLabel(text), colour);
	}

	/** A panel on the dark grey every window here is painted in. */
	static JPanel panel(LayoutManager layout)
	{
		JPanel panel = new JPanel(layout);
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return panel;
	}

	/** A dark vertical box that sits flush left in its parent column. */
	static JPanel column()
	{
		return column(new JPanel());
	}

	static JPanel column(JPanel panel)
	{
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	/**
	 * Text that wraps at {@code width}, in the HTML renderer's px (x1.3 on
	 * screen). A label's preferred width is otherwise one unwrapped line, and
	 * one wide child pushes a whole column past its edge.
	 */
	static String html(int width, String text)
	{
		return "<html><body style='width:" + width + "px'>" + escape(text) + "</body></html>";
	}

	/** Cache and file text must not be able to inject markup. */
	static String escape(String text)
	{
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** Scrolls up and down only, borderless, at a usable wheel speed. */
	static JScrollPane scroller(Component view)
	{
		JScrollPane scroll = new JScrollPane(view,
			JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		return scroll;
	}

	/** Lays the component out again after its children changed, and repaints it. */
	static void relayout(JComponent component)
	{
		component.revalidate();
		component.repaint();
	}

	/** Runs the action on every edit of a document. */
	static DocumentListener onChange(Runnable action)
	{
		return new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				action.run();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				action.run();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				action.run();
			}
		};
	}

	/**
	 * A scroll view that is always exactly as wide as its viewport, whatever
	 * the viewport is once the vertical scrollbar takes its share. A wide child
	 * is squeezed to fit instead of pushing the content past the right edge,
	 * where a view that never scrolls sideways would leave it unreachable.
	 */
	static final class WidthTrackingPanel extends JPanel implements Scrollable
	{
		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
		{
			return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}
}

package com.dooglemaps.ui;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * One card on the Stats tab: a headline you can read while the card is shut, and a body you
 * open when the headline made you curious.
 *
 * <h2>The header is the card</h2>
 *
 * Not a title above a panel. One clickable bar carrying two lines — a caret and a name on the
 * left with the answer on the right, then a single grey line of context under it — so a closed
 * accordion of four of these still shows four numbers rather than four words. That is the whole
 * reason this exists: the tab it replaces opened on a paragraph, and a paragraph is not
 * something you land on.
 *
 * <h2>Weight, not brightness</h2>
 *
 * The headline is the one place in this plugin that uses {@link FontManager#getRunescapeBoldFont}.
 * The tab's old class comment argued for brightness as the only hierarchy and it was right to
 * avoid a second font <i>family</i> — but brightness had run out of headroom (the column headers
 * had fallen to 1.68:1 contrast trying to be the third tier of it). Bold is the same family
 * RuneLite itself sets panel titles in, so this adds the missing axis without adding a typeface.
 *
 * <h2>Why the caption is a plain label</h2>
 *
 * {@link WrappedText} computes its height against the full sidebar width and clips its last line
 * the moment it is given any less — which is exactly what happens inside a bordered card. The
 * caption is therefore one hard-capped line, and anything that will not fit goes in the tooltip
 * the whole header carries.
 */
class StatSection extends JPanel
{
	/**
	 * The width this asks for, whatever its contents.
	 *
	 * <p>Same budget {@link WrappedText} uses: the 225px sidebar less the borders. A caption is a
	 * label, and a label's preferred width is however long its text happens to be — so without a
	 * cap one long caption quietly widens the whole tab past the sidebar and everything above it
	 * is clipped. Capping the <i>preferred</i> width only; the maximum stays unbounded so the
	 * card still fills a sidebar the player has dragged wider.
	 */
	private static final int MAX_WIDTH = 195;

	private final PanelLayoutStore layout;
	private final String key;
	private final String title;

	/** The caret line. A button, so the affordance matches every other section in the plugin. */
	private final JButton caret = new JButton();

	private final JLabel headline = new JLabel();
	private final JLabel caption = new JLabel();
	private final JPanel content = new JPanel();

	private boolean open;

	StatSection(PanelLayoutStore layout, String key, String title, String tooltip,
		boolean openByDefault, JComponent... body)
	{
		this.layout = layout;
		this.key = key;
		this.title = title;
		this.open = layout.isOpen(key, openByDefault);

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setAlignmentX(LEFT_ALIGNMENT);
		// Space above rather than below, so a header sits with the card it names instead of
		// floating equidistant between two of them.
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		caret.setFont(FontManager.getRunescapeSmallFont());
		Controls.styleButton(caret);
		caret.setHorizontalAlignment(SwingConstants.LEFT);
		caret.addActionListener(e -> toggle());

		headline.setFont(FontManager.getRunescapeBoldFont());
		headline.setForeground(ColorScheme.TEXT_COLOR);
		headline.setHorizontalAlignment(SwingConstants.RIGHT);
		headline.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));

		header.add(caret, BorderLayout.WEST);
		header.add(headline, BorderLayout.CENTER);

		caption.setFont(FontManager.getRunescapeSmallFont());
		caption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		caption.setAlignmentX(LEFT_ALIGNMENT);
		caption.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 0));

		// The long explanation lives on hover, on every piece of the header a mouse can land on.
		// A parent's tooltip is not inherited by a child that has none: the child is the deepest
		// component under the pointer, so it would simply swallow the hover.
		caret.setToolTipText(tooltip);
		headline.setToolTipText(tooltip);
		caption.setToolTipText(tooltip);

		// The caret already says this is interactive; the rest of the bar should behave like it
		// looks. A listener per piece for the same reason the tooltip is set per piece.
		clickToToggle(header);
		clickToToggle(headline);
		clickToToggle(caption);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(getBackground());
		content.setAlignmentX(LEFT_ALIGNMENT);
		content.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
		for (JComponent child : body)
		{
			child.setAlignmentX(LEFT_ALIGNMENT);
			if (child.isOpaque())
			{
				child.setBackground(getBackground());
			}
			content.add(child);
		}

		add(header);
		add(caption);
		add(content);

		apply();
	}

	/** The number the card exists to show, in bold beside the caret. */
	void setHeadline(String value)
	{
		headline.setText(value == null ? "" : value);
	}

	/** One grey line of context. Hidden rather than left blank when there is nothing to say. */
	void setCaption(String line)
	{
		caption.setText(line == null ? "" : line);
		caption.setVisible(caption.getText().length() > 0);
	}

	/** Replaces the explanation the header carries on hover, where it depends on the numbers. */
	void setTooltip(String tooltip)
	{
		caret.setToolTipText(tooltip);
		headline.setToolTipText(tooltip);
		caption.setToolTipText(tooltip);
	}

	/** Opens or shuts the card and remembers the choice, as if the player had clicked it. */
	void setOpen(boolean open)
	{
		setOpen(open, true);
	}

	/**
	 * Opens or shuts the card, optionally without recording it as a preference.
	 *
	 * <p>{@code remember} is false for the one case the panel decides rather than the player: a
	 * fresh install has nothing to show under Harvests, and four shut headers with nothing under
	 * any of them is a worse first screen than the default order is worth. That is a reaction to
	 * this account's state, not a choice about furniture, so it must not be written down and
	 * inflicted on every other account the player logs into.
	 */
	void setOpen(boolean open, boolean remember)
	{
		this.open = open;
		if (remember)
		{
			layout.setOpen(key, open);
		}
		apply();
	}

	boolean isOpen()
	{
		return open;
	}

	private void toggle()
	{
		setOpen(!open);
		revalidate();
		repaint();
	}

	private void apply()
	{
		caret.setText(Controls.collapseLabel(title, open));
		content.setVisible(open);
	}

	private void clickToToggle(JComponent component)
	{
		component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		component.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggle();
			}
		});
	}

	@Override
	public Dimension getPreferredSize()
	{
		Dimension preferred = super.getPreferredSize();
		return new Dimension(Math.min(preferred.width, MAX_WIDTH), preferred.height);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
